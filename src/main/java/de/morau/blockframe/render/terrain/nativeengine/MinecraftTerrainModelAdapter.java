package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.vertex.QuadInstance;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CancellationSignal;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Vertex;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3fc;

/**
 * Real Minecraft/NeoForge model adapter writing only BlockFrame primitives.
 *
 * <p>The adapter intentionally reuses the public model, culling, AO and tint
 * contracts, but never a Mojang BufferBuilder, MeshData or GPU upload. One
 * instance belongs to one compiler worker and is not shared concurrently.</p>
 */
public final class MinecraftTerrainModelAdapter {
    public enum FailureReason {
        CANCELLED,
        GENERATION_MISMATCH,
        CENSUS_MISMATCH,
        FLUID_LANE_REQUIRED,
        MOD_EXTRA_ADAPTER_REQUIRED,
        FAST_LEAVES_POLICY_UNSUPPORTED,
        ASSET_NOT_IN_CENSUS,
        UNSUPPORTED_ASSET_CONTRACT,
        SNAPSHOT_HALO_OR_TINT_UNSUPPORTED,
        MODEL_COMPILATION_FAILED
    }

    public record CompileResult(
        NativeTerrainSectionSnapshot snapshot,
        FailureReason failureReason,
        String detail,
        int visitedBlocks,
        int emittedQuads,
        long compileNanos
    ) {
        public CompileResult {
            detail = Objects.requireNonNull(detail, "detail");
            if (
                visitedBlocks < 0
                    || emittedQuads < 0
                    || compileNanos < 0L
            ) {
                throw new IllegalArgumentException(
                    "invalid compiler metrics"
                );
            }
            if ((snapshot == null) == (failureReason == null)) {
                throw new IllegalArgumentException(
                    "compile result must contain exactly one outcome"
                );
            }
        }

        public boolean successful() {
            return this.snapshot != null;
        }

        public Optional<NativeTerrainSectionSnapshot>
        snapshotOptional() {
            return Optional.ofNullable(this.snapshot);
        }
    }

    private static final class AdapterFailure
        extends RuntimeException {
        private final FailureReason reason;

        private AdapterFailure(
            FailureReason reason,
            String message
        ) {
            super(message);
            this.reason = reason;
        }
    }

    private final BlockStateModelSet modelSet;
    private final ModelBlockRenderer blockRenderer;
    private final boolean cutoutLeaves;

    public MinecraftTerrainModelAdapter(
        BlockStateModelSet modelSet,
        BlockColors blockColors,
        boolean ambientOcclusion,
        boolean cutoutLeaves
    ) {
        this.modelSet = Objects.requireNonNull(modelSet, "modelSet");
        this.blockRenderer = new ModelBlockRenderer(
            ambientOcclusion,
            true,
            Objects.requireNonNull(blockColors, "blockColors")
        );
        this.cutoutLeaves = cutoutLeaves;
    }

    /**
     * Compiles all real block-model quads in one immutable source section.
     * Any missing lane or contract invalidates the complete result.
     */
    public CompileResult compile(
        MinecraftTerrainSectionSnapshot source,
        NativeTerrainAssetCensus.Result census,
        CancellationSignal cancellation,
        int additionalRendererCount
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(census, "census");
        Objects.requireNonNull(cancellation, "cancellation");
        long started = System.nanoTime();
        if (
            !source.validFor(source.generations())
                || source.generations().resources()
                    != census.resourceGeneration()
        ) {
            return failure(
                FailureReason.GENERATION_MISMATCH,
                "source/census generation mismatch",
                0,
                0,
                started
            );
        }
        if (!source.censusDigest().equals(census.digest())) {
            return failure(
                FailureReason.CENSUS_MISMATCH,
                "source census digest is stale",
                0,
                0,
                started
            );
        }
        if (additionalRendererCount != 0) {
            return failure(
                FailureReason.MOD_EXTRA_ADAPTER_REQUIRED,
                "AddSectionGeometryEvent renderers="
                    + additionalRendererCount,
                0,
                0,
                started
            );
        }

        List<Primitive> primitives = new ArrayList<>();
        BlockPos.MutableBlockPos position =
            new BlockPos.MutableBlockPos();
        int visited = 0;
        long[] primitiveSerial = {1L};
        try {
            BlockModelLighter.enableCaching();
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        if (cancellation.cancelled()) {
                            throw new AdapterFailure(
                                FailureReason.CANCELLED,
                                "compile cancellation observed"
                            );
                        }
                        visited++;
                        position.set(
                            source.coreMinimumX() + x,
                            source.coreMinimumY() + y,
                            source.coreMinimumZ() + z
                        );
                        BlockState state =
                            source.getBlockState(position);
                        if (state.isAir()) {
                            continue;
                        }
                        if (!state.getFluidState().isEmpty()) {
                            throw new AdapterFailure(
                                FailureReason.FLUID_LANE_REQUIRED,
                                "fluid at " + position.asLong()
                            );
                        }
                        if (
                            state.getRenderShape()
                                != RenderShape.MODEL
                        ) {
                            continue;
                        }
                        if (
                            ModelBlockRenderer.forceOpaque(
                                this.cutoutLeaves,
                                state
                            )
                        ) {
                            throw new AdapterFailure(
                                FailureReason
                                    .FAST_LEAVES_POLICY_UNSUPPORTED,
                                "fast-leaves solid remap needs a "
                                    + "census-keyed policy contract"
                            );
                        }

                        BlockStateModel model =
                            this.modelSet.get(state);
                        int localX = x;
                        int localY = y;
                        int localZ = z;
                        this.blockRenderer.tesselateBlock(
                            (offsetX, offsetY, offsetZ, quad, instance) ->
                                primitives.add(
                                    primitive(
                                        source,
                                        census,
                                        state,
                                        quad,
                                        instance,
                                        offsetX,
                                        offsetY,
                                        offsetZ,
                                        primitiveSerial[0]++
                                    )
                                ),
                            localX,
                            localY,
                            localZ,
                            source,
                            position,
                            state,
                            model,
                            state.getSeed(position)
                        );
                        if (source.unsupportedQueryObserved()) {
                            throw new AdapterFailure(
                                FailureReason
                                    .SNAPSHOT_HALO_OR_TINT_UNSUPPORTED,
                                "model queried outside the one-block "
                                    + "halo or used an unknown tint resolver"
                            );
                        }
                    }
                }
            }
            if (cancellation.cancelled()) {
                throw new AdapterFailure(
                    FailureReason.CANCELLED,
                    "compile cancellation observed before publication"
                );
            }
            if (!source.validFor(source.generations())) {
                throw new AdapterFailure(
                    FailureReason.GENERATION_MISMATCH,
                    "source generation invalidated during compile"
                );
            }
            return new CompileResult(
                new NativeTerrainSectionSnapshot(
                    source.generations(),
                    source.section(),
                    source.censusDigest(),
                    primitives
                ),
                null,
                "",
                visited,
                primitives.size(),
                System.nanoTime() - started
            );
        } catch (AdapterFailure failure) {
            return failure(
                failure.reason,
                failure.getMessage(),
                visited,
                primitives.size(),
                started
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            return failure(
                FailureReason.MODEL_COMPILATION_FAILED,
                error.getClass().getSimpleName(),
                visited,
                primitives.size(),
                started
            );
        } finally {
            BlockModelLighter.clearCache();
        }
    }

    private static Primitive primitive(
        MinecraftTerrainSectionSnapshot source,
        NativeTerrainAssetCensus.Result census,
        BlockState state,
        BakedQuad quad,
        QuadInstance instance,
        float offsetX,
        float offsetY,
        float offsetZ,
        long primitiveId
    ) {
        StableId assetId =
            MinecraftTerrainAssetCensusAdapter.assetId(state, quad);
        Entry entry = census.entry(assetId).orElseThrow(
            () -> new AdapterFailure(
                FailureReason.ASSET_NOT_IN_CENSUS,
                "quad contract was absent from post-reload census"
            )
        );
        if (!entry.hasCompleteContract()) {
            throw new AdapterFailure(
                FailureReason.UNSUPPORTED_ASSET_CONTRACT,
                entry.unavailableReason()
            );
        }

        List<Vertex> vertices = new ArrayList<>(4);
        float minimumX = Float.POSITIVE_INFINITY;
        float minimumY = Float.POSITIVE_INFINITY;
        float minimumZ = Float.POSITIVE_INFINITY;
        float maximumX = Float.NEGATIVE_INFINITY;
        float maximumY = Float.NEGATIVE_INFINITY;
        float maximumZ = Float.NEGATIVE_INFINITY;
        StringBuilder digestInput = new StringBuilder(256);
        int lightEmission = quad.materialInfo().lightEmission();
        for (int vertex = 0; vertex < 4; vertex++) {
            Vector3fc position = quad.position(vertex);
            float x = position.x() + offsetX;
            float y = position.y() + offsetY;
            float z = position.z() + offsetZ;
            long packedUv = quad.packedUV(vertex);
            int color = ARGB.multiply(
                instance.getColor(vertex),
                quad.bakedColors().color(vertex)
            );
            int light = instance.getLightCoordsWithEmission(
                vertex,
                lightEmission
            );
            float u = UVPair.unpackU(packedUv);
            float v = UVPair.unpackV(packedUv);
            int packedNormal = quad.bakedNormals().normal(vertex);
            if (BakedNormals.isUnspecified(packedNormal)) {
                packedNormal = BakedNormals.pack(
                    quad.direction().getUnitVec3f()
                );
            }
            vertices.add(
                new Vertex(
                    x,
                    y,
                    z,
                    color,
                    u,
                    v,
                    light,
                    packedNormal
                )
            );
            minimumX = Math.min(minimumX, x);
            minimumY = Math.min(minimumY, y);
            minimumZ = Math.min(minimumZ, z);
            maximumX = Math.max(maximumX, x);
            maximumY = Math.max(maximumY, y);
            maximumZ = Math.max(maximumZ, z);
            digestInput
                .append(Float.floatToRawIntBits(x)).append(',')
                .append(Float.floatToRawIntBits(y)).append(',')
                .append(Float.floatToRawIntBits(z)).append(',')
                .append(color).append(',')
                .append(Float.floatToRawIntBits(u)).append(',')
                .append(Float.floatToRawIntBits(v)).append(',')
                .append(light).append(',')
                .append(packedNormal).append(';');
        }
        Bounds bounds = new Bounds(
            minimumX,
            minimumY,
            minimumZ,
            maximumX,
            maximumY,
            maximumZ
        );
        Digest geometryDigest = NativeTerrainContractIds.digest(
            "compiled-quad",
            digestInput.toString()
        );
        return new Primitive(
            primitiveId,
            entry,
            bounds,
            geometryDigest,
            vertices
        );
    }

    private static CompileResult failure(
        FailureReason reason,
        String detail,
        int visited,
        int emitted,
        long started
    ) {
        return new CompileResult(
            null,
            reason,
            detail,
            visited,
            emitted,
            System.nanoTime() - started
        );
    }
}
