package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.AttributeContract;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Provenance;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Vertex;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.AlphaMode;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MotionModel;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ProducerIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.VertexLayout;
import java.util.List;

final class NativeTerrainCompilerTestFixtures {
    static final long RESOURCE_GENERATION = 4L;

    private NativeTerrainCompilerTestFixtures() {
    }

    static Entry entry(Category category) {
        long seed = 100L + category.ordinal() * 20L;
        AlphaMode alpha = switch (category) {
            case SOLID, MOD_EXTRA -> AlphaMode.OPAQUE;
            case CUTOUT -> AlphaMode.MASKED;
            case TRANSLUCENT, FLUID -> AlphaMode.BLENDED;
            case UNSUPPORTED -> throw new IllegalArgumentException(
                "use unsupportedEntry()"
            );
        };
        int cutoff = category == Category.CUTOUT
            ? TerrainMeshProducerABI.MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
            : Float.floatToRawIntBits(0.0F);
        Provenance provenance = switch (category) {
            case FLUID -> Provenance.VANILLA_FLUID;
            case MOD_EXTRA -> Provenance.NEOFORGE_EXTRA;
            default -> Provenance.VANILLA_BLOCK;
        };
        boolean submissionCompatible =
            category == Category.SOLID
                || category == Category.CUTOUT;
        return Entry.supported(
            id(seed),
            id(seed + 1L),
            id(seed + 2L),
            category,
            provenance,
            VertexLayout.blockPayloadV2(id(seed + 3L)),
            IndexType.UINT16,
            new MaterialBinding(
                1L,
                id(seed + 4L),
                id(seed + 5L),
                id(seed + 6L),
                id(seed + 7L),
                id(seed + 8L),
                id(seed + 9L),
                alpha,
                cutoff
            ),
            new ShaderContract(
                digest(seed + 10L),
                TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
                MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
            ),
            AttributeContract.blockPayloadV2(),
            false,
            true,
            submissionCompatible,
            digest(seed + 11L)
        );
    }

    static Entry unsupportedEntry() {
        return Entry.unsupported(
            id(900L),
            new StableId(0L, 0L),
            id(901L),
            Provenance.UNKNOWN,
            true,
            true,
            digest(902L),
            "CUSTOM_RENDER_TYPE_UNAVAILABLE"
        );
    }

    static Entry copyEntry(
        Entry source,
        Provenance provenance,
        boolean customRenderType,
        Digest contractDigest
    ) {
        return Entry.supported(
            source.assetId(),
            source.blockStateOrModelId(),
            source.renderTypeId(),
            source.category(),
            provenance,
            source.vertexLayout().orElseThrow(),
            source.indexType().orElseThrow(),
            source.material().orElseThrow(),
            source.shader().orElseThrow(),
            source.attributes(),
            customRenderType,
            source.requiredForActiveProfile(),
            source.submissionCompatible(),
            contractDigest
        );
    }

    static NativeTerrainAssetCensus.Result census(Entry... entries) {
        return NativeTerrainAssetCensus.capture(
            RESOURCE_GENERATION,
            true,
            List.of(entries)
        );
    }

    static NativeTerrainSectionSnapshot snapshot(
        NativeTerrainAssetCensus.Result census,
        Primitive... primitives
    ) {
        return new NativeTerrainSectionSnapshot(
            generations(),
            section(),
            census.digest(),
            List.of(primitives)
        );
    }

    static Primitive quad(long primitiveId, Entry entry, float x) {
        Bounds bounds = new Bounds(
            x,
            0.0F,
            0.0F,
            x + 1.0F,
            1.0F,
            0.0F
        );
        return new Primitive(
            primitiveId,
            entry,
            bounds,
            digest(1_000L + primitiveId),
            List.of(
                vertex(x, 0.0F),
                vertex(x + 1.0F, 0.0F),
                vertex(x + 1.0F, 1.0F),
                vertex(x, 1.0F)
            )
        );
    }

    static Primitive triangle(long primitiveId, Entry entry) {
        return new Primitive(
            primitiveId,
            entry,
            new Bounds(
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                0.0F
            ),
            digest(2_000L + primitiveId),
            List.of(
                vertex(0.0F, 0.0F),
                vertex(1.0F, 0.0F),
                vertex(0.0F, 1.0F)
            )
        );
    }

    static BlockFrameSectionCompiler compiler() {
        return new BlockFrameSectionCompiler(
            new BlockFrameSectionCompiler.CompilerContract(
                new ProducerIdentity(id(40L), 1),
                id(41L),
                1L,
                100L
            )
        );
    }

    static GenerationStamp generations() {
        return new GenerationStamp(
            1L,
            2L,
            3L,
            RESOURCE_GENERATION,
            5L,
            6L
        );
    }

    static SectionIdentity section() {
        return new SectionIdentity(id(50L), 0x1234L);
    }

    static StableId id(long low) {
        return new StableId(0x004E41544956454CL, low);
    }

    static Digest digest(long seed) {
        return new Digest(seed, seed + 1L, seed + 2L, seed + 3L);
    }

    private static Vertex vertex(float x, float y) {
        return new Vertex(
            x,
            y,
            0.0F,
            0xffccbbaa,
            x,
            y,
            0x00f000f0,
            0x007F0000
        );
    }
}
