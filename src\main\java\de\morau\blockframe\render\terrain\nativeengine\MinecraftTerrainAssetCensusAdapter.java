package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import de.morau.nvidiadlss.mixin.accessor
    .NativeTerrainMultiPartModelAccessor;
import de.morau.nvidiadlss.mixin.accessor
    .NativeTerrainWeightedVariantsAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.AttributeContract;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Provenance;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.AlphaMode;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MotionModel;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.VertexLayout;

/**
 * Main-thread, post-model-bake registry and render-contract census.
 *
 * <p>The adapter observes model metadata and baked quads, but never creates a
 * world or invokes unknown dynamic models. Every ambiguity is represented by
 * a stable typed reason and keeps the complete native backend ineligible.</p>
 */
public final class MinecraftTerrainAssetCensusAdapter {
    public enum Reason {
        SUPPORTED_SOLID,
        SUPPORTED_CUTOUT,
        NO_STATIC_GEOMETRY,
        REQUIRES_TRANSLUCENT_LANE,
        REQUIRES_FLUID_LANE,
        REQUIRES_DYNAMIC_MODEL_DATA,
        REQUIRES_MOD_EXTRA_ADAPTER,
        REQUIRES_CUSTOM_TINT_ADAPTER,
        UNSUPPORTED_VERTEX_FORMAT,
        UNSUPPORTED_RENDER_TYPE,
        UNSUPPORTED_SHADER_ABI,
        MODEL_CENSUS_FAILED
    }

    public record Observation(
        String blockState,
        String namespace,
        String modelClass,
        String renderType,
        String vertexFormat,
        String texture,
        Reason reason,
        int tintSourceCount,
        boolean ambientOcclusion,
        boolean animated,
        boolean fluidPresent,
        String detail
    ) {
        public Observation {
            blockState = requireText(blockState, "blockState");
            namespace = requireText(namespace, "namespace");
            modelClass = requireText(modelClass, "modelClass");
            renderType = requireText(renderType, "renderType");
            vertexFormat = requireText(vertexFormat, "vertexFormat");
            texture = Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(reason, "reason");
            if (tintSourceCount < 0) {
                throw new IllegalArgumentException(
                    "tintSourceCount must not be negative"
                );
            }
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    public record Report(
        long resourceGeneration,
        long captureNanos,
        int blockStateCount,
        int fluidStateCount,
        List<Observation> observations,
        Map<String, Integer> byNamespace,
        Map<String, Integer> byModelClass,
        Map<String, Integer> byRenderType,
        Map<Reason, Integer> byReason,
        NativeTerrainAssetCensus.Result census
    ) {
        public Report {
            if (
                resourceGeneration <= 0L
                    || captureNanos < 0L
                    || blockStateCount < 0
                    || fluidStateCount < 0
            ) {
                throw new IllegalArgumentException(
                    "invalid census report counters"
                );
            }
            observations = List.copyOf(observations);
            byNamespace = Map.copyOf(byNamespace);
            byModelClass = Map.copyOf(byModelClass);
            byRenderType = Map.copyOf(byRenderType);
            byReason = Map.copyOf(byReason);
            Objects.requireNonNull(census, "census");
        }

        public boolean hasUnsupportedContracts() {
            return this.byReason.entrySet().stream().anyMatch(
                entry -> entry.getKey() != Reason.SUPPORTED_SOLID
                    && entry.getKey() != Reason.SUPPORTED_CUTOUT
                    && entry.getKey() != Reason.NO_STATIC_GEOMETRY
                    && entry.getValue() != 0
            );
        }
    }

    private MinecraftTerrainAssetCensusAdapter() {
    }

    public static Report capture(
        ModelManager modelManager,
        BlockColors blockColors,
        long resourceGeneration
    ) {
        Objects.requireNonNull(modelManager, "modelManager");
        Objects.requireNonNull(blockColors, "blockColors");
        if (resourceGeneration <= 0L) {
            throw new IllegalArgumentException(
                "resourceGeneration must be positive"
            );
        }

        long started = System.nanoTime();
        List<Observation> observations = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> namespaceCounts = new TreeMap<>();
        Map<String, Integer> modelCounts = new TreeMap<>();
        Map<String, Integer> renderTypeCounts = new TreeMap<>();
        EnumMap<Reason, Integer> reasonCounts =
            new EnumMap<>(Reason.class);
        BlockStateModelSet modelSet =
            modelManager.getBlockStateModelSet();
        int stateCount = 0;

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
            String namespace = blockId.getNamespace();
            for (
                BlockState state
                    : block.getStateDefinition().getPossibleStates()
            ) {
                stateCount++;
                observeState(
                    state,
                    blockId,
                    namespace,
                    modelSet,
                    blockColors,
                    resourceGeneration,
                    observations,
                    entries,
                    namespaceCounts,
                    modelCounts,
                    renderTypeCounts,
                    reasonCounts
                );
            }
        }

        int fluidCount = 0;
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            FluidState state = fluid.defaultFluidState();
            fluidCount++;
            if (!state.isEmpty()) {
                Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
                addUnsupported(
                    observations,
                    entries,
                    namespaceCounts,
                    modelCounts,
                    renderTypeCounts,
                    reasonCounts,
                    id + "[default]",
                    id.getNamespace(),
                    modelManager.getFluidStateModelSet()
                        .get(state)
                        .getClass()
                        .getName(),
                    "minecraft:fluid",
                    "not-applicable",
                    "",
                    Reason.REQUIRES_FLUID_LANE,
                    0,
                    false,
                    false,
                    true,
                    "fluid compiler/submission lane is not implemented",
                    resourceGeneration,
                    Provenance.VANILLA_FLUID
                );
            }
        }

        /*
         * AddSectionGeometryEvent is deliberately section/world dependent and
         * exposes only raw layer VertexConsumers. There is no complete global
         * provenance/material census contract, so absence cannot be inferred
         * from a registry scan.
         */
        addUnsupported(
            observations,
            entries,
            namespaceCounts,
            modelCounts,
            renderTypeCounts,
            reasonCounts,
            "neoforge:add_section_geometry_event",
            "neoforge",
            "AddSectionGeometryEvent.AdditionalSectionRenderer",
            "dynamic-section-layer",
            "callback-defined",
            "",
            Reason.REQUIRES_MOD_EXTRA_ADAPTER,
            0,
            false,
            false,
            false,
            "per-section raw geometry has no complete V1 provenance ABI",
            resourceGeneration,
            Provenance.NEOFORGE_EXTRA
        );

        return new Report(
            resourceGeneration,
            System.nanoTime() - started,
            stateCount,
            fluidCount,
            observations,
            namespaceCounts,
            modelCounts,
            renderTypeCounts,
            reasonCounts,
            NativeTerrainAssetCensus.capture(
                resourceGeneration,
                true,
                entries
            )
        );
    }

    private static void observeState(
        BlockState state,
        Identifier blockId,
        String namespace,
        BlockStateModelSet modelSet,
        BlockColors blockColors,
        long resourceGeneration,
        List<Observation> observations,
        List<Entry> entries,
        Map<String, Integer> namespaceCounts,
        Map<String, Integer> modelCounts,
        Map<String, Integer> renderTypeCounts,
        Map<Reason, Integer> reasonCounts
    ) {
        String stateId = state.toString();
        RenderShape shape = state.getRenderShape();
        if (shape != RenderShape.MODEL) {
            addObservationOnly(
                observations,
                namespaceCounts,
                modelCounts,
                renderTypeCounts,
                reasonCounts,
                new Observation(
                    stateId,
                    namespace,
                    shape.getClass().getName(),
                    shape.name(),
                    "not-applicable",
                    "",
                    Reason.NO_STATIC_GEOMETRY,
                    0,
                    false,
                    false,
                    !state.getFluidState().isEmpty(),
                    "RenderShape=" + shape
                )
            );
            return;
        }

        BlockStateModel model = modelSet.get(state);
        String modelClass = model.getClass().getName();
        if (!isAuditedBuiltInModel(model)) {
            Reason reason = model instanceof DynamicBlockStateModel
                ? Reason.REQUIRES_DYNAMIC_MODEL_DATA
                : Reason.REQUIRES_MOD_EXTRA_ADAPTER;
            addUnsupported(
                observations,
                entries,
                namespaceCounts,
                modelCounts,
                renderTypeCounts,
                reasonCounts,
                stateId,
                namespace,
                modelClass,
                "unknown",
                "unknown",
                "",
                reason,
                blockColors.getTintSources(state).size(),
                false,
                false,
                !state.getFluidState().isEmpty(),
                "model class is outside the audited built-in model set",
                resourceGeneration,
                provenance(namespace)
            );
            return;
        }

        List<BlockTintSource> tintSources =
            blockColors.getTintSources(state);
        for (BlockTintSource source : tintSources) {
            if (
                !source.getClass().getName().startsWith(
                    "net.minecraft."
                )
            ) {
                addUnsupported(
                    observations,
                    entries,
                    namespaceCounts,
                    modelCounts,
                    renderTypeCounts,
                    reasonCounts,
                    stateId,
                    namespace,
                    modelClass,
                    "unknown",
                    "unknown",
                    "",
                    Reason.REQUIRES_CUSTOM_TINT_ADAPTER,
                    tintSources.size(),
                    false,
                    false,
                    !state.getFluidState().isEmpty(),
                    "custom BlockTintSource="
                        + source.getClass().getName(),
                    resourceGeneration,
                    provenance(namespace)
                );
                return;
            }
        }

        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            RandomSource random = RandomSource.create(
                state.getSeed(BlockPos.ZERO)
            );
            collectAllPossibleParts(
                model,
                state,
                random,
                parts,
                new IdentityHashMap<>()
            );
        } catch (RuntimeException | LinkageError error) {
            addUnsupported(
                observations,
                entries,
                namespaceCounts,
                modelCounts,
                renderTypeCounts,
                reasonCounts,
                stateId,
                namespace,
                modelClass,
                "unknown",
                "unknown",
                "",
                Reason.MODEL_CENSUS_FAILED,
                tintSources.size(),
                false,
                false,
                !state.getFluidState().isEmpty(),
                error.getClass().getSimpleName(),
                resourceGeneration,
                provenance(namespace)
            );
            return;
        }

        IdentityHashMap<BakedQuad, Boolean> seen =
            new IdentityHashMap<>();
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            collectUnique(quads, seen, part.getQuads(null));
            for (var direction : net.minecraft.core.Direction.values()) {
                collectUnique(
                    quads,
                    seen,
                    part.getQuads(direction)
                );
            }
        }
        if (quads.isEmpty()) {
            addObservationOnly(
                observations,
                namespaceCounts,
                modelCounts,
                renderTypeCounts,
                reasonCounts,
                new Observation(
                    stateId,
                    namespace,
                    modelClass,
                    "none",
                    "not-applicable",
                    "",
                    Reason.NO_STATIC_GEOMETRY,
                    tintSources.size(),
                    false,
                    false,
                    !state.getFluidState().isEmpty(),
                    "audited model emitted no baked quads"
                )
            );
            return;
        }

        Map<String, BakedQuad> uniqueContracts =
            new LinkedHashMap<>();
        for (BakedQuad quad : quads) {
            var material = quad.materialInfo();
            String texture = material.sprite().atlasLocation()
                + "#"
                + material.sprite().contents().name();
            String contractKey =
                material.layer().name() + "|" + texture;
            uniqueContracts.putIfAbsent(contractKey, quad);
        }
        for (BakedQuad quad : uniqueContracts.values()) {
            observeQuad(
                state,
                stateId,
                namespace,
                modelClass,
                quad,
                tintSources.size(),
                resourceGeneration,
                observations,
                entries,
                namespaceCounts,
                modelCounts,
                renderTypeCounts,
                reasonCounts
            );
        }
    }

    private static void observeQuad(
        BlockState state,
        String stateId,
        String namespace,
        String modelClass,
        BakedQuad quad,
        int tintSourceCount,
        long resourceGeneration,
        List<Observation> observations,
        List<Entry> entries,
        Map<String, Integer> namespaceCounts,
        Map<String, Integer> modelCounts,
        Map<String, Integer> renderTypeCounts,
        Map<Reason, Integer> reasonCounts
    ) {
        ChunkSectionLayer layer = quad.materialInfo().layer();
        String renderType = layer.pipeline().getLocation().toString();
        String vertexFormat = layer.vertexFormat().toString();
        String texture = quad.materialInfo().sprite().atlasLocation()
            + "#"
            + quad.materialInfo().sprite().contents().name();
        Reason reason;
        if (layer == ChunkSectionLayer.TRANSLUCENT) {
            reason = Reason.REQUIRES_TRANSLUCENT_LANE;
        } else if (
            !layer.vertexFormat().equals(DefaultVertexFormat.BLOCK)
        ) {
            reason = Reason.UNSUPPORTED_VERTEX_FORMAT;
        } else if (layer == ChunkSectionLayer.SOLID) {
            reason = Reason.SUPPORTED_SOLID;
        } else if (layer == ChunkSectionLayer.CUTOUT) {
            reason = Reason.SUPPORTED_CUTOUT;
        } else {
            reason = Reason.UNSUPPORTED_RENDER_TYPE;
        }

        boolean representedContract =
            reason == Reason.SUPPORTED_SOLID
                || reason == Reason.SUPPORTED_CUTOUT
                || reason == Reason.REQUIRES_TRANSLUCENT_LANE;
        Observation observation = new Observation(
            stateId,
            namespace,
            modelClass,
            renderType,
            vertexFormat,
            texture,
            reason,
            tintSourceCount,
            quad.materialInfo().ambientOcclusion(),
            quad.materialInfo().sprite().contents().isAnimated(),
            !state.getFluidState().isEmpty(),
            "itemRenderType=" + quad.materialInfo().itemRenderType()
        );
        addObservationOnly(
            observations,
            namespaceCounts,
            modelCounts,
            renderTypeCounts,
            reasonCounts,
            observation
        );

        String canonical =
            stateId + "|" + renderType + "|" + texture;
        StableId assetId = NativeTerrainContractIds.stableId(
            "terrain-asset",
            canonical
        );
        if (!representedContract) {
            entries.add(
                Entry.unsupported(
                    assetId,
                    NativeTerrainContractIds.stableId(
                        "block-state-model",
                        stateId + "|" + modelClass
                    ),
                    NativeTerrainContractIds.stableId(
                        "render-type",
                        renderType
                    ),
                    provenance(namespace),
                    false,
                    true,
                    NativeTerrainContractIds.digest(
                        "asset-contract",
                        canonical + "|" + reason
                    ),
                    reason.name()
                )
            );
            return;
        }

        Category category = switch (layer) {
            case SOLID -> Category.SOLID;
            case CUTOUT -> Category.CUTOUT;
            case TRANSLUCENT -> Category.TRANSLUCENT;
        };
        AlphaMode alpha = switch (category) {
            case SOLID -> AlphaMode.OPAQUE;
            case CUTOUT -> AlphaMode.MASKED;
            case TRANSLUCENT -> AlphaMode.BLENDED;
            default -> throw new IllegalStateException(
                "unexpected represented block category " + category
            );
        };
        entries.add(
            Entry.supported(
                assetId,
                NativeTerrainContractIds.stableId(
                    "block-state-model",
                    stateId + "|" + modelClass
                ),
                NativeTerrainContractIds.stableId(
                    "render-type",
                    renderType
                ),
                category,
                provenance(namespace),
                VertexLayout.blockPayloadV2(
                    NativeTerrainContractIds.stableId(
                        "vertex-layout",
                        DefaultVertexFormat.BLOCK
                            + "|packed-snorm8x3-normal-v2"
                    )
                ),
                IndexType.UINT16,
                new MaterialBinding(
                    resourceGeneration,
                    NativeTerrainContractIds.stableId(
                        "material-family",
                        "minecraft:block-atlas"
                    ),
                    NativeTerrainContractIds.stableId(
                        "texture",
                        texture
                    ),
                    NativeTerrainContractIds.stableId(
                        "sampler",
                        "minecraft:block-atlas-default"
                    ),
                    NativeTerrainContractIds.stableId(
                        "layer",
                        renderType
                    ),
                    quad.materialInfo().sprite().contents().isAnimated()
                        ? NativeTerrainContractIds.stableId(
                            "animation-table",
                            texture
                        )
                        : new StableId(0L, 0L),
                    new StableId(0L, 0L),
                    alpha,
                    category == Category.CUTOUT
                        ? TerrainMeshProducerABI
                            .MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
                        : Float.floatToRawIntBits(0.0F)
                ),
                new ShaderContract(
                    NativeTerrainContractIds.digest(
                        "shader-abi",
                        renderType + "|block-payload-v2"
                    ),
                    TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
                    MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
                ),
                AttributeContract.blockPayloadV2(),
                false,
                true,
                false,
                NativeTerrainContractIds.digest(
                    "asset-contract",
                    canonical + "|block-payload-v2"
                )
            )
        );
    }

    static StableId assetId(
        BlockState state,
        BakedQuad quad
    ) {
        String texture = quad.materialInfo().sprite().atlasLocation()
            + "#"
            + quad.materialInfo().sprite().contents().name();
        return NativeTerrainContractIds.stableId(
            "terrain-asset",
            state
                + "|"
                + quad.materialInfo().layer().pipeline().getLocation()
                + "|"
                + texture
        );
    }

    private static boolean isAuditedBuiltInModel(
        BlockStateModel model
    ) {
        return model instanceof SingleVariant
            || model instanceof WeightedVariants
            || model instanceof MultiPartModel;
    }

    /**
     * Enumerates the exact built-in model graph. Weighted variants are never
     * sampled. Multipart selection is state-dependent but not random; after
     * the normal contract initializes its selected child list, every selected
     * weighted child is expanded recursively.
     */
    private static void collectAllPossibleParts(
        BlockStateModel model,
        BlockState state,
        RandomSource random,
        List<BlockStateModelPart> output,
        IdentityHashMap<BlockStateModel, Boolean> path
    ) {
        if (path.put(model, Boolean.TRUE) != null) {
            throw new IllegalStateException(
                "cyclic baked block-state model graph"
            );
        }
        try {
            if (model instanceof SingleVariant) {
                model.collectParts(
                    BlockAndTintGetter.EMPTY,
                    BlockPos.ZERO,
                    state,
                    random,
                    output
                );
                return;
            }
            if (model instanceof WeightedVariants variants) {
                var accessible =
                    (NativeTerrainWeightedVariantsAccessor)variants;
                for (
                    var weighted
                        : accessible
                            .blockframe$getNativeTerrainVariants()
                            .unwrap()
                ) {
                    BlockStateModel child = weighted.value();
                    if (!isAuditedBuiltInModel(child)) {
                        throw new IllegalStateException(
                            "weighted child model is outside "
                                + "the audited built-in set: "
                                + child.getClass().getName()
                        );
                    }
                    collectAllPossibleParts(
                        child,
                        state,
                        random,
                        output,
                        path
                    );
                }
                return;
            }
            if (model instanceof MultiPartModel multipart) {
                /*
                 * This initializes only the state-selected child list. Any
                 * sampled output is discarded; weighted children are expanded
                 * below from their complete lists.
                 */
                multipart.collectParts(
                    BlockAndTintGetter.EMPTY,
                    BlockPos.ZERO,
                    state,
                    random,
                    new ArrayList<>()
                );
                List<BlockStateModel> children =
                    ((NativeTerrainMultiPartModelAccessor)multipart)
                        .blockframe$getNativeTerrainSelectedModels();
                if (children == null) {
                    throw new IllegalStateException(
                        "multipart child selection was not initialized"
                    );
                }
                for (BlockStateModel child : children) {
                    if (!isAuditedBuiltInModel(child)) {
                        throw new IllegalStateException(
                            "multipart child model is outside "
                                + "the audited built-in set: "
                                + child.getClass().getName()
                        );
                    }
                    collectAllPossibleParts(
                        child,
                        state,
                        random,
                        output,
                        path
                    );
                }
                return;
            }
            throw new IllegalStateException(
                "model is outside the audited built-in set"
            );
        } finally {
            path.remove(model);
        }
    }

    private static void collectUnique(
        List<BakedQuad> destination,
        IdentityHashMap<BakedQuad, Boolean> seen,
        Collection<BakedQuad> source
    ) {
        for (BakedQuad quad : source) {
            if (seen.put(quad, Boolean.TRUE) == null) {
                destination.add(quad);
            }
        }
    }

    private static void addUnsupported(
        List<Observation> observations,
        List<Entry> entries,
        Map<String, Integer> namespaceCounts,
        Map<String, Integer> modelCounts,
        Map<String, Integer> renderTypeCounts,
        Map<Reason, Integer> reasonCounts,
        String stateId,
        String namespace,
        String modelClass,
        String renderType,
        String vertexFormat,
        String texture,
        Reason reason,
        int tintSourceCount,
        boolean ambientOcclusion,
        boolean animated,
        boolean fluidPresent,
        String detail,
        long resourceGeneration,
        Provenance provenance
    ) {
        Observation observation = new Observation(
            stateId,
            namespace,
            modelClass,
            renderType,
            vertexFormat,
            texture,
            reason,
            tintSourceCount,
            ambientOcclusion,
            animated,
            fluidPresent,
            detail
        );
        addObservationOnly(
            observations,
            namespaceCounts,
            modelCounts,
            renderTypeCounts,
            reasonCounts,
            observation
        );
        String canonical =
            stateId + "|" + renderType + "|" + reason + "|" + detail;
        entries.add(
            Entry.unsupported(
                NativeTerrainContractIds.stableId(
                    "terrain-asset",
                    canonical
                ),
                NativeTerrainContractIds.stableId(
                    "block-state-model",
                    stateId + "|" + modelClass
                ),
                NativeTerrainContractIds.stableId(
                    "render-type",
                    renderType
                ),
                provenance,
                reason == Reason.UNSUPPORTED_RENDER_TYPE,
                true,
                NativeTerrainContractIds.digest(
                    "asset-contract",
                    canonical + "|" + resourceGeneration
                ),
                reason.name() + ":" + detail
            )
        );
    }

    private static void addObservationOnly(
        List<Observation> observations,
        Map<String, Integer> namespaceCounts,
        Map<String, Integer> modelCounts,
        Map<String, Integer> renderTypeCounts,
        Map<Reason, Integer> reasonCounts,
        Observation observation
    ) {
        observations.add(observation);
        namespaceCounts.merge(observation.namespace(), 1, Integer::sum);
        modelCounts.merge(observation.modelClass(), 1, Integer::sum);
        renderTypeCounts.merge(
            observation.renderType(),
            1,
            Integer::sum
        );
        reasonCounts.merge(observation.reason(), 1, Integer::sum);
    }

    private static Provenance provenance(String namespace) {
        return "minecraft".equals(namespace)
            ? Provenance.VANILLA_BLOCK
            : Provenance.CUSTOM;
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
