package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.AlphaMode;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.CompatibilityProof;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ContentProvenance;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
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
    .TerrainMeshProducerABI.ProducerIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.RetirementToken;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.VertexLayout;

final class NativeTerrainTestFixtures {
    private static final Digest GEOMETRY =
        new Digest(101L, 102L, 103L, 104L);
    private static final Digest SOURCE =
        new Digest(31L, 32L, 33L, 34L);
    private static final StableId HOOK =
        new StableId(0xB10CF4A0L, 35L);

    private NativeTerrainTestFixtures() {
    }

    static GenerationStamp generations() {
        return new GenerationStamp(1L, 2L, 3L, 4L, 5L, 6L);
    }

    static MeshDescriptor mesh(Layer layer) {
        GenerationStamp generations = generations();
        AlphaMode alpha = layer == Layer.CUTOUT
            ? AlphaMode.MASKED
            : layer == Layer.SOLID
                ? AlphaMode.OPAQUE
                : AlphaMode.BLENDED;
        int cutoff = layer == Layer.CUTOUT
            ? TerrainMeshProducerABI.MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
            : Float.floatToRawIntBits(0.0F);
        return new MeshDescriptor(
            TerrainMeshProducerABI.VERSION,
            new ProducerIdentity(id(10L), 1),
            generations,
            new SectionIdentity(id(11L), 0x1234L),
            layer,
            VertexLayout.blockPayloadV2(id(12L)),
            new PayloadRange(
                0L,
                4L * TerrainMeshProducerABI
                    .BLOCK_PAYLOAD_V2_STRIDE_BYTES
            ),
            4,
            IndexLayout.sequentialQuads(IndexType.UINT16, 6, 0),
            new MaterialBinding(
                1L,
                id(13L),
                id(14L),
                id(15L),
                id(16L),
                id(17L),
                id(18L),
                alpha,
                cutoff
            ),
            new ShaderContract(
                new Digest(21L, 22L, 23L, 24L),
                TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
                MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
            ),
            new Bounds(0.0F, 0.0F, 0.0F, 16.0F, 16.0F, 16.0F),
            new ContentProvenance(
                TerrainMeshProducerABI.CONTENT_BLOCK_GEOMETRY,
                new Digest(41L, 42L, 43L, 44L),
                true
            ),
            GEOMETRY,
            new InstancingContract(GEOMETRY, id(19L), 1L, 1),
            new RetirementToken(
                generations.device(),
                generations.renderer(),
                generations.world(),
                generations.resources(),
                generations.producer(),
                generations.sectionMesh(),
                20L
            )
        );
    }

    static CompatibilityProof proof(MeshDescriptor mesh) {
        return new CompatibilityProof(
            mesh.abiVersion(),
            mesh.generations(),
            SOURCE,
            HOOK,
            mesh.producer(),
            mesh.layer(),
            mesh.section(),
            mesh.vertexLayout().stableId(),
            mesh.vertexPayload(),
            mesh.vertexCount(),
            mesh.indexLayout(),
            mesh.shader().abiDigest(),
            mesh.material().registryGeneration(),
            mesh.material().materialFamilyId(),
            mesh.material().textureId(),
            mesh.material().samplerId(),
            mesh.material().layerId(),
            mesh.material().animationTableId(),
            mesh.material().pbrContractId(),
            mesh.material().alphaCutoffBits(),
            mesh.shader().outputMask(),
            mesh.shader().motionModel(),
            mesh.provenance().contentMask(),
            mesh.provenance().auditDigest(),
            mesh.bounds(),
            mesh.geometryDigest(),
            mesh.instancing(),
            mesh.retirement().serial(),
            mesh.generations().device(),
            true,
            true,
            true,
            true,
            true,
            true
        );
    }

    static Digest sourceContract() {
        return SOURCE;
    }

    static StableId hookContract() {
        return HOOK;
    }

    static StableId id(long low) {
        return new StableId(0xB10CF4A0L, low);
    }
}
