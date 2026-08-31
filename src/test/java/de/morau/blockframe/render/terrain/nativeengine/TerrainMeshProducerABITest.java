package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.AlphaMode;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ContentProvenance;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexLayout;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.InstancingContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Layer;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MeshDescriptor;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MotionModel;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.PayloadRange;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.RetirementToken;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.VertexLayout;
import org.junit.jupiter.api.Test;

class TerrainMeshProducerABITest {
    @Test
    void solidAndExactCutoutShareThePermanentProducerContract() {
        MeshDescriptor solid =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);
        MeshDescriptor cutout =
            NativeTerrainTestFixtures.mesh(Layer.CUTOUT);

        assertTrue(
            solid.structurallyCompatibleWithFirstMilestone()
        );
        assertTrue(
            cutout.structurallyCompatibleWithFirstMilestone()
        );
        assertTrue(Layer.SOLID.safeHzbOccluder());
        assertFalse(Layer.CUTOUT.safeHzbOccluder());
        assertEquals(
            TerrainMeshProducerABI.VERSION,
            solid.abiVersion()
        );
    }

    @Test
    void v2PayloadStatesBakedAndExplicitNormalSemanticsHonestly() {
        VertexLayout layout =
            NativeTerrainTestFixtures.mesh(Layer.SOLID)
                .vertexLayout();

        assertEquals(
            TerrainMeshProducerABI.BLOCK_PAYLOAD_V2_STRIDE_BYTES,
            layout.strideBytes()
        );
        assertTrue(
            (
                layout.semanticMask()
                    & TerrainMeshProducerABI
                        .SEMANTIC_AO_BAKED_IN_COLOR
            ) != 0L
        );
        assertTrue(
            (
                layout.semanticMask()
                    & TerrainMeshProducerABI
                        .SEMANTIC_TINT_BAKED_IN_COLOR
            ) != 0L
        );
        assertTrue(
            (
                layout.semanticMask()
                    & TerrainMeshProducerABI
                        .SEMANTIC_NORMAL_EXPLICIT
            ) != 0L
        );
        assertTrue(
            (
                layout.semanticMask()
                    & TerrainMeshProducerABI
                        .SEMANTIC_NORMAL_FROM_GEOMETRY
            ) == 0L
        );
    }

    @Test
    void missingLightAoTintUvOrNormalFailsClosed() {
        long base = TerrainMeshProducerABI.REQUIRED_BLOCK_SEMANTICS;

        assertThrows(
            IllegalArgumentException.class,
            () -> new VertexLayout(
                NativeTerrainTestFixtures.id(100L),
                28,
                base & ~TerrainMeshProducerABI.SEMANTIC_LIGHT_UV
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new VertexLayout(
                NativeTerrainTestFixtures.id(101L),
                28,
                base
            )
        );
    }

    @Test
    void everyTemporalAndDeferredOutputIsMandatory() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ShaderContract(
                new Digest(1L, 2L, 3L, 4L),
                TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS
                    & ~TerrainMeshProducerABI.OUTPUT_MOTION,
                MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ShaderContract(
                new Digest(0L, 0L, 0L, 0L),
                TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
                MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
            )
        );
    }

    @Test
    void cutoutRequiresTheExactKnownMaskedAlphaContract() {
        MeshDescriptor solid =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);
        MaterialBinding wrongCutout = new MaterialBinding(
            solid.material().registryGeneration(),
            solid.material().materialFamilyId(),
            solid.material().textureId(),
            solid.material().samplerId(),
            solid.material().layerId(),
            solid.material().animationTableId(),
            solid.material().pbrContractId(),
            AlphaMode.MASKED,
            Float.floatToRawIntBits(0.25F)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                solid,
                Layer.CUTOUT,
                solid.vertexPayload(),
                solid.indexLayout(),
                wrongCutout,
                solid.bounds(),
                solid.geometryDigest(),
                solid.instancing(),
                solid.retirement()
            )
        );
    }

    @Test
    void byteRangesCountsBoundsAndGenerationsRejectCorruption() {
        MeshDescriptor solid =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                solid,
                solid.layer(),
                new PayloadRange(0L, 27L),
                solid.indexLayout(),
                solid.material(),
                solid.bounds(),
                solid.geometryDigest(),
                solid.instancing(),
                solid.retirement()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                solid,
                solid.layer(),
                solid.vertexPayload(),
                IndexLayout.sequentialQuads(
                    IndexType.UINT16,
                    12,
                    0
                ),
                solid.material(),
                solid.bounds(),
                solid.geometryDigest(),
                solid.instancing(),
                solid.retirement()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Bounds(
                Float.NaN,
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                1.0F
            )
        );
        RetirementToken stale = new RetirementToken(
            solid.generations().device() + 1L,
            solid.generations().renderer(),
            solid.generations().world(),
            solid.generations().resources(),
            solid.generations().producer(),
            solid.generations().sectionMesh(),
            solid.retirement().serial()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                solid,
                solid.layer(),
                solid.vertexPayload(),
                solid.indexLayout(),
                solid.material(),
                solid.bounds(),
                solid.geometryDigest(),
                solid.instancing(),
                stale
            )
        );
    }

    @Test
    void instancingIdentityIncludesExactShaderVisibleGeometry() {
        MeshDescriptor solid =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);
        InstancingContract unrelated = new InstancingContract(
            new Digest(91L, 92L, 93L, 94L),
            NativeTerrainTestFixtures.id(95L),
            1L,
            100
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> copy(
                solid,
                solid.layer(),
                solid.vertexPayload(),
                solid.indexLayout(),
                solid.material(),
                solid.bounds(),
                solid.geometryDigest(),
                unrelated,
                solid.retirement()
            )
        );
    }

    @Test
    void futureSortedLanesExistButCannotEnterV1() {
        assertFalse(
            NativeTerrainTestFixtures.mesh(Layer.TRANSLUCENT)
                .structurallyCompatibleWithFirstMilestone()
        );
        assertFalse(Layer.FLUID.firstMilestone());
    }

    @Test
    void mergedFluidAdditionalOrUnknownProvenanceCannotEnterV1() {
        MeshDescriptor solid =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);
        ContentProvenance mixed = new ContentProvenance(
            TerrainMeshProducerABI.CONTENT_BLOCK_GEOMETRY
                | TerrainMeshProducerABI.CONTENT_FLUID_GEOMETRY,
            new Digest(71L, 72L, 73L, 74L),
            true
        );
        ContentProvenance incomplete = new ContentProvenance(
            TerrainMeshProducerABI.CONTENT_BLOCK_GEOMETRY,
            new Digest(75L, 76L, 77L, 78L),
            false
        );

        assertFalse(
            withProvenance(solid, mixed)
                .structurallyCompatibleWithFirstMilestone()
        );
        assertFalse(
            withProvenance(solid, incomplete)
                .structurallyCompatibleWithFirstMilestone()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ContentProvenance(
                TerrainMeshProducerABI.CONTENT_BLOCK_GEOMETRY
                    | (1L << 20),
                new Digest(79L, 80L, 81L, 82L),
                true
            )
        );
    }

    @Test
    void sharedUint16QuadIndicesRejectVertexOverflow() {
        MeshDescriptor solid =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);
        int vertexCount = 65_540;

        assertThrows(
            IllegalArgumentException.class,
            () -> new MeshDescriptor(
                solid.abiVersion(),
                solid.producer(),
                solid.generations(),
                solid.section(),
                solid.layer(),
                solid.vertexLayout(),
                new PayloadRange(
                    0L,
                    (long)vertexCount
                        * solid.vertexLayout().strideBytes()
                ),
                vertexCount,
                IndexLayout.sequentialQuads(
                    IndexType.UINT16,
                    vertexCount / 4 * 6,
                    0
                ),
                solid.material(),
                solid.shader(),
                solid.bounds(),
                solid.provenance(),
                solid.geometryDigest(),
                solid.instancing(),
                solid.retirement()
            )
        );
    }

    private static MeshDescriptor withProvenance(
        MeshDescriptor source,
        ContentProvenance provenance
    ) {
        return new MeshDescriptor(
            source.abiVersion(),
            source.producer(),
            source.generations(),
            source.section(),
            source.layer(),
            source.vertexLayout(),
            source.vertexPayload(),
            source.vertexCount(),
            source.indexLayout(),
            source.material(),
            source.shader(),
            source.bounds(),
            provenance,
            source.geometryDigest(),
            source.instancing(),
            source.retirement()
        );
    }

    private static MeshDescriptor copy(
        MeshDescriptor source,
        Layer layer,
        PayloadRange vertexPayload,
        IndexLayout indexLayout,
        MaterialBinding material,
        Bounds bounds,
        Digest geometryDigest,
        InstancingContract instancing,
        RetirementToken retirement
    ) {
        return new MeshDescriptor(
            source.abiVersion(),
            source.producer(),
            source.generations(),
            source.section(),
            layer,
            source.vertexLayout(),
            vertexPayload,
            source.vertexCount(),
            indexLayout,
            material,
            source.shader(),
            bounds,
            source.provenance(),
            geometryDigest,
            instancing,
            retirement
        );
    }
}
