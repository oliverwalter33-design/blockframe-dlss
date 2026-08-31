package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
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
    .TerrainMeshProducerABI.Digest;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MinecraftTerrainModelAdapterTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        try {
            net.neoforged.fml.loading.FMLLoader.getCurrent();
        } catch (IllegalStateException unavailable) {
            Assumptions.assumeTrue(
                false,
                "real model fixtures require the moddev FML client"
            );
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void snapshotFreezesWorldLightTintAndModelData() {
        MutableFixtureWorld world = new MutableFixtureWorld();
        BlockPos position = new BlockPos(1, 1, 1);
        world.states.put(
            position.asLong(),
            Blocks.STONE.defaultBlockState()
        );
        MemoryBudgetManager budgets = budgets();
        var capture = MinecraftTerrainSectionSnapshot.capture(
            world,
            SectionPos.of(0, 0, 0),
            NativeTerrainCompilerTestFixtures.generations(),
            NativeTerrainCompilerTestFixtures.section(),
            NativeTerrainCompilerTestFixtures.digest(8000L),
            budgets,
            1024L * 1024L,
            true
        );
        assertTrue(capture.successful());
        var snapshot = capture.snapshot();
        world.states.put(
            position.asLong(),
            Blocks.DIRT.defaultBlockState()
        );
        assertEquals(
            Blocks.STONE.defaultBlockState(),
            snapshot.getBlockState(position)
        );
        assertEquals(
            15,
            snapshot.getBrightness(LightLayer.SKY, position)
        );
        assertEquals(
            0x229944,
            snapshot.getBlockTint(
                position,
                BiomeColors.GRASS_COLOR_RESOLVER
            )
        );
        assertFalse(snapshot.unsupportedQueryObserved());
        snapshot.getBlockState(new BlockPos(40, 40, 40));
        assertTrue(snapshot.unsupportedQueryObserved());
        snapshot.close();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void actualSingleVariantModelsProduceSeparatedDeterministicQuads() {
        try (
            SpriteFixture stoneSprite =
                new SpriteFixture("stone");
            SpriteFixture plantSprite =
                new SpriteFixture("plant")
        ) {
            BakedQuad solidQuad = faceQuad(
                Direction.NORTH,
                stoneSprite.sprite,
                ChunkSectionLayer.SOLID,
                -1
            );
            BakedQuad cutoutQuadA = crossQuad(
                true,
                plantSprite.sprite,
                0
            );
            BakedQuad cutoutQuadB = crossQuad(
                false,
                plantSprite.sprite,
                0
            );
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState plant = Blocks.SHORT_GRASS.defaultBlockState();
            SingleVariant stoneModel = new SingleVariant(
                new FixturePart(Map.of(Direction.NORTH, solidQuad), List.of())
            );
            SingleVariant plantModel = new SingleVariant(
                new FixturePart(
                    Map.of(),
                    List.of(cutoutQuadA, cutoutQuadB)
                )
            );
            BlockStateModelSet models = new BlockStateModelSet(
                Map.of(stone, stoneModel, plant, plantModel),
                stoneModel
            );
            NativeTerrainAssetCensus.Result census =
                NativeTerrainAssetCensus.capture(
                    NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION,
                    true,
                    List.of(
                        entry(stone, solidQuad, Category.SOLID),
                        entry(plant, cutoutQuadA, Category.CUTOUT)
                    )
                );
            MutableFixtureWorld world = new MutableFixtureWorld();
            world.states.put(
                new BlockPos(1, 1, 1).asLong(),
                stone
            );
            world.states.put(
                new BlockPos(2, 1, 1).asLong(),
                plant
            );
            MemoryBudgetManager budgets = budgets();
            var firstSource = capture(world, census, budgets);
            var secondSource = capture(world, census, budgets);
            MinecraftTerrainModelAdapter adapter =
                new MinecraftTerrainModelAdapter(
                    models,
                    BlockColors.createDefault(),
                    true,
                    true
                );

            var first = adapter.compile(
                firstSource,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER,
                0
            );
            var second = adapter.compile(
                secondSource,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER,
                0
            );
            assertTrue(first.successful(), first.detail());
            assertTrue(second.successful(), second.detail());
            assertEquals(3, first.emittedQuads());
            assertEquals(
                first.snapshot().primitives().stream()
                    .map(
                        NativeTerrainSectionSnapshot.Primitive
                            ::geometryDigest
                    )
                    .toList(),
                second.snapshot().primitives().stream()
                    .map(
                        NativeTerrainSectionSnapshot.Primitive
                            ::geometryDigest
                    )
                    .toList()
            );
            long solid = first.snapshot().primitives().stream()
                .filter(
                    primitive ->
                        primitive.category() == Category.SOLID
                )
                .count();
            long cutout = first.snapshot().primitives().stream()
                .filter(
                    primitive ->
                        primitive.category() == Category.CUTOUT
                )
                .count();
            assertEquals(1L, solid);
            assertEquals(2L, cutout);
            int cutoutColor = first.snapshot().primitives().stream()
                .filter(
                    primitive ->
                        primitive.category() == Category.CUTOUT
                )
                .findFirst()
                .orElseThrow()
                .vertices()
                .getFirst()
                .color();
            assertNotEquals(-1, cutoutColor);

            var compiled = NativeTerrainCompilerTestFixtures.compiler()
                .compile(
                    first.snapshot(),
                    census,
                    BlockFrameSectionCompiler.CancellationSignal.NEVER
                );
            assertTrue(compiled.successful(), compiled.detail());
            var batch = compiled.batch().orElseThrow();
            assertEquals(
                128,
                batch.channel(Category.SOLID).byteLength()
            );
            assertEquals(
                256,
                batch.channel(Category.CUTOUT).byteLength()
            );
            batch.close();
            firstSource.close();
            secondSource.close();
            assertTrue(budgets.closeAndReport());
        }
    }

    @Test
    void cancellationAndModExtraFailBeforePublication() {
        try (
            SpriteFixture sprite =
                new SpriteFixture("cancel")
        ) {
            BakedQuad quad = faceQuad(
                Direction.UP,
                sprite.sprite,
                ChunkSectionLayer.SOLID,
                -1
            );
            BlockState stone = Blocks.STONE.defaultBlockState();
            SingleVariant model = new SingleVariant(
                new FixturePart(Map.of(Direction.UP, quad), List.of())
            );
            BlockStateModelSet models = new BlockStateModelSet(
                Map.of(stone, model),
                model
            );
            NativeTerrainAssetCensus.Result census =
                NativeTerrainAssetCensus.capture(
                    NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION,
                    true,
                    List.of(entry(stone, quad, Category.SOLID))
                );
            MutableFixtureWorld world = new MutableFixtureWorld();
            world.states.put(
                new BlockPos(1, 1, 1).asLong(),
                stone
            );
            MemoryBudgetManager budgets = budgets();
            var source = capture(world, census, budgets);
            MinecraftTerrainModelAdapter adapter =
                new MinecraftTerrainModelAdapter(
                    models,
                    BlockColors.createDefault(),
                    true,
                    true
                );
            assertEquals(
                MinecraftTerrainModelAdapter.FailureReason
                    .MOD_EXTRA_ADAPTER_REQUIRED,
                adapter.compile(
                    source,
                    census,
                    BlockFrameSectionCompiler.CancellationSignal.NEVER,
                    1
                ).failureReason()
            );
            assertEquals(
                MinecraftTerrainModelAdapter.FailureReason.CANCELLED,
                adapter.compile(source, census, () -> true, 0)
                    .failureReason()
            );
            source.close();
            assertTrue(budgets.closeAndReport());
        }
    }

    private static MinecraftTerrainSectionSnapshot capture(
        MutableFixtureWorld world,
        NativeTerrainAssetCensus.Result census,
        MemoryBudgetManager budgets
    ) {
        return MinecraftTerrainSectionSnapshot.capture(
            world,
            SectionPos.of(0, 0, 0),
            NativeTerrainCompilerTestFixtures.generations(),
            NativeTerrainCompilerTestFixtures.section(),
            census.digest(),
            budgets,
            1024L * 1024L,
            true
        ).snapshot();
    }

    private static Entry entry(
        BlockState state,
        BakedQuad quad,
        Category category
    ) {
        String renderType =
            quad.materialInfo().layer().pipeline().getLocation().toString();
        String texture = quad.materialInfo().sprite().atlasLocation()
            + "#"
            + quad.materialInfo().sprite().contents().name();
        AlphaMode alpha = category == Category.SOLID
            ? AlphaMode.OPAQUE
            : AlphaMode.MASKED;
        int cutoff = category == Category.CUTOUT
            ? TerrainMeshProducerABI.MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
            : Float.floatToRawIntBits(0.0F);
        return Entry.supported(
            MinecraftTerrainAssetCensusAdapter.assetId(state, quad),
            NativeTerrainContractIds.stableId(
                "block-state-model",
                state + "|" + SingleVariant.class.getName()
            ),
            NativeTerrainContractIds.stableId(
                "render-type",
                renderType
            ),
            category,
            Provenance.VANILLA_BLOCK,
            VertexLayout.blockPayloadV2(
                NativeTerrainContractIds.stableId(
                    "vertex-layout",
                    quad.materialInfo().layer().vertexFormat()
                        + "|packed-snorm8x3-normal-v2"
                )
            ),
            IndexType.UINT16,
            new MaterialBinding(
                NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION,
                NativeTerrainContractIds.stableId(
                    "material-family",
                    "minecraft:block-atlas"
                ),
                NativeTerrainContractIds.stableId("texture", texture),
                NativeTerrainContractIds.stableId(
                    "sampler",
                    "minecraft:block-atlas-default"
                ),
                NativeTerrainContractIds.stableId(
                    "layer",
                    renderType
                ),
                new StableId(0L, 0L),
                new StableId(0L, 0L),
                alpha,
                cutoff
            ),
            new ShaderContract(
                NativeTerrainContractIds.digest(
                    "shader-abi",
                    renderType
                ),
                TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
                MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
            ),
            AttributeContract.blockPayloadV2(),
            false,
            true,
            true,
            NativeTerrainContractIds.digest(
                "asset-contract",
                state + "|" + renderType + "|" + texture
            )
        );
    }

    private static BakedQuad faceQuad(
        Direction direction,
        TextureAtlasSprite sprite,
        ChunkSectionLayer layer,
        int tintIndex
    ) {
        return new BakedQuad(
            new Vector3f(0.0F, 0.0F, 0.0F),
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f(1.0F, 1.0F, 0.0F),
            new Vector3f(0.0F, 1.0F, 0.0F),
            UVPair.pack(0.0F, 0.0F),
            UVPair.pack(1.0F, 0.0F),
            UVPair.pack(1.0F, 1.0F),
            UVPair.pack(0.0F, 1.0F),
            direction,
            new BakedQuad.MaterialInfo(
                sprite,
                layer,
                net.minecraft.client.renderer.Sheets
                    .cutoutBlockItemSheet(),
                tintIndex,
                true,
                0,
                true
            )
        );
    }

    private static BakedQuad crossQuad(
        boolean first,
        TextureAtlasSprite sprite,
        int tintIndex
    ) {
        float x0 = first ? 0.0F : 1.0F;
        float x1 = first ? 1.0F : 0.0F;
        return new BakedQuad(
            new Vector3f(x0, 0.0F, 0.0F),
            new Vector3f(x1, 0.0F, 1.0F),
            new Vector3f(x1, 1.0F, 1.0F),
            new Vector3f(x0, 1.0F, 0.0F),
            UVPair.pack(0.0F, 0.0F),
            UVPair.pack(1.0F, 0.0F),
            UVPair.pack(1.0F, 1.0F),
            UVPair.pack(0.0F, 1.0F),
            Direction.UP,
            new BakedQuad.MaterialInfo(
                sprite,
                ChunkSectionLayer.CUTOUT,
                net.minecraft.client.renderer.Sheets
                    .cutoutBlockItemSheet(),
                tintIndex,
                true,
                0,
                true
            )
        );
    }

    private static MemoryBudgetManager budgets() {
        return new MemoryBudgetManager(MemoryBudgetSettings.defaults());
    }

    private static final class FixturePart
        implements BlockStateModelPart {
        private final Map<Direction, List<BakedQuad>> byDirection;
        private final List<BakedQuad> unculled;
        private final Material.Baked material;

        private FixturePart(
            Map<Direction, BakedQuad> byDirection,
            List<BakedQuad> unculled
        ) {
            EnumMap<Direction, List<BakedQuad>> copied =
                new EnumMap<>(Direction.class);
            byDirection.forEach(
                (direction, quad) -> copied.put(
                    direction,
                    List.of(quad)
                )
            );
            this.byDirection = Map.copyOf(copied);
            this.unculled = List.copyOf(unculled);
            BakedQuad representative = !unculled.isEmpty()
                ? unculled.getFirst()
                : byDirection.values().iterator().next();
            this.material = new Material.Baked(
                representative.materialInfo().sprite(),
                false
            );
        }

        @Override
        public List<BakedQuad> getQuads(
            @Nullable Direction direction
        ) {
            return direction == null
                ? this.unculled
                : this.byDirection.getOrDefault(
                    direction,
                    List.of()
                );
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public Material.Baked particleMaterial() {
            return this.material;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }

    private static final class MutableFixtureWorld
        implements BlockAndTintGetter {
        private final Map<Long, BlockState> states = new HashMap<>();

        @Override
        public BlockState getBlockState(BlockPos position) {
            return this.states.getOrDefault(
                position.asLong(),
                Blocks.AIR.defaultBlockState()
            );
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return this.getBlockState(position).getFluidState();
        }

        @Override
        public int getBrightness(
            LightLayer layer,
            BlockPos position
        ) {
            return 15;
        }

        @Override
        public int getBlockTint(
            BlockPos position,
            ColorResolver resolver
        ) {
            if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
                return 0x229944;
            }
            if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
                return 0x228844;
            }
            if (
                resolver
                    == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER
            ) {
                return 0x887744;
            }
            return 0x3366AA;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(
            BlockPos position
        ) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    private static final class SpriteFixture
        implements AutoCloseable {
        private final NativeImage image;
        private final net.minecraft.client.renderer.texture
            .SpriteContents contents;
        private final TextureAtlasSprite sprite;

        private SpriteFixture(String name) {
            this.image = new NativeImage(1, 1, false);
            this.contents =
                new net.minecraft.client.renderer.texture.SpriteContents(
                    Identifier.fromNamespaceAndPath(
                        "blockframe_test",
                        name
                    ),
                    new FrameSize(1, 1),
                    this.image
                );
            this.sprite = new TestSprite(this.contents);
        }

        @Override
        public void close() {
            this.sprite.close();
            this.contents.close();
        }
    }

    private static final class TestSprite
        extends TextureAtlasSprite {
        private TestSprite(
            net.minecraft.client.renderer.texture.SpriteContents contents
        ) {
            super(
                TextureAtlas.LOCATION_BLOCKS,
                contents,
                16,
                16,
                0,
                0,
                0
            );
        }
    }
}
