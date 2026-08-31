package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.AlphaMode;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MotionModel;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeTerrainSurfaceIdRegistryTest {
    @Test
    void idsAreSequentialCollisionFreeAndZeroRemainsReserved() {
        NativeTerrainSurfaceIdRegistry registry =
            new NativeTerrainSurfaceIdRegistry(1L, 8);
        MaterialBinding firstMaterial = material(1L, 10L);
        MaterialBinding secondMaterial = material(1L, 20L);
        ShaderContract shader = shader(100L);

        int first = registry.idFor(firstMaterial, shader);
        int duplicate = registry.idFor(firstMaterial, shader);
        int second = registry.idFor(secondMaterial, shader);

        assertEquals(1, first);
        assertEquals(first, duplicate);
        assertEquals(2, second);
        assertNotEquals(
            NativeTerrainSurfaceIdRegistry.INVALID_ID,
            first
        );
        assertEquals(
            firstMaterial,
            registry.requireKey(1L, first).material()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.requireKey(
                1L,
                NativeTerrainSurfaceIdRegistry.INVALID_ID
            )
        );
        registry.close();
    }

    @Test
    void everyMaterialAndShaderFieldParticipatesInIdentity() {
        NativeTerrainSurfaceIdRegistry registry =
            new NativeTerrainSurfaceIdRegistry(1L, 32);
        MaterialBinding base = material(1L, 10L);
        ShaderContract shader = shader(100L);
        List<MaterialBinding> variants = List.of(
            base,
            copy(base, id(101L), base.textureId(), base.samplerId(),
                base.layerId(), base.animationTableId(),
                base.pbrContractId(), base.alphaMode(),
                base.alphaCutoffBits()),
            copy(base, base.materialFamilyId(), id(102L),
                base.samplerId(), base.layerId(),
                base.animationTableId(), base.pbrContractId(),
                base.alphaMode(), base.alphaCutoffBits()),
            copy(base, base.materialFamilyId(), base.textureId(),
                id(103L), base.layerId(),
                base.animationTableId(), base.pbrContractId(),
                base.alphaMode(), base.alphaCutoffBits()),
            copy(base, base.materialFamilyId(), base.textureId(),
                base.samplerId(), id(104L),
                base.animationTableId(), base.pbrContractId(),
                base.alphaMode(), base.alphaCutoffBits()),
            copy(base, base.materialFamilyId(), base.textureId(),
                base.samplerId(), base.layerId(), id(105L),
                base.pbrContractId(), base.alphaMode(),
                base.alphaCutoffBits()),
            copy(base, base.materialFamilyId(), base.textureId(),
                base.samplerId(), base.layerId(),
                base.animationTableId(), id(106L),
                base.alphaMode(), base.alphaCutoffBits()),
            copy(base, base.materialFamilyId(), base.textureId(),
                base.samplerId(), base.layerId(),
                base.animationTableId(), base.pbrContractId(),
                AlphaMode.MASKED,
                TerrainMeshProducerABI
                    .MOJANG_CUTOUT_ALPHA_CUTOFF_BITS)
        );

        List<Integer> ids = new ArrayList<>();
        for (MaterialBinding variant : variants) {
            ids.add(registry.idFor(variant, shader));
        }
        int differentShader = registry.idFor(
            base,
            shader(200L)
        );

        assertEquals(variants.size() + 1, registry.size());
        for (int index = 0; index < variants.size(); index++) {
            assertEquals(index + 1, ids.get(index));
        }
        assertEquals(variants.size() + 1, differentShader);
        registry.close();
    }

    @Test
    void insertionOrderProducesDeterministicSnapshots() {
        NativeTerrainSurfaceIdRegistry first =
            new NativeTerrainSurfaceIdRegistry(1L, 8);
        NativeTerrainSurfaceIdRegistry second =
            new NativeTerrainSurfaceIdRegistry(1L, 8);
        MaterialBinding a = material(1L, 10L);
        MaterialBinding b = material(1L, 20L);
        ShaderContract shader = shader(100L);

        first.idFor(a, shader);
        first.idFor(b, shader);
        second.idFor(a, shader);
        second.idFor(b, shader);

        assertEquals(first.snapshot(1L), second.snapshot(1L));
        first.close();
        second.close();
    }

    @Test
    void capacityFailureDoesNotDisturbExistingMappings() {
        NativeTerrainSurfaceIdRegistry registry =
            new NativeTerrainSurfaceIdRegistry(1L, 2);
        ShaderContract shader = shader(100L);
        MaterialBinding first = material(1L, 10L);
        MaterialBinding second = material(1L, 20L);
        registry.idFor(first, shader);
        registry.idFor(second, shader);

        assertThrows(
            IllegalStateException.class,
            () -> registry.idFor(material(1L, 30L), shader)
        );
        assertEquals(2, registry.size());
        assertEquals(1, registry.idFor(first, shader));
        assertEquals(2, registry.idFor(second, shader));
        registry.close();
    }

    @Test
    void reloadInvalidatesOldGenerationAndRestartsIds() {
        NativeTerrainSurfaceIdRegistry old =
            new NativeTerrainSurfaceIdRegistry(1L, 4);
        old.idFor(material(1L, 10L), shader(100L));
        assertThrows(
            IllegalArgumentException.class,
            () -> old.idFor(material(2L, 10L), shader(100L))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> old.requireKey(2L, 1)
        );

        NativeTerrainSurfaceIdRegistry current = old.reload(2L);
        assertThrows(IllegalStateException.class, old::size);
        assertThrows(
            IllegalArgumentException.class,
            () -> current.reload(2L)
        );
        assertEquals(
            1,
            current.idFor(material(2L, 10L), shader(100L))
        );
        current.close();
        assertThrows(IllegalStateException.class, current::size);
        assertThrows(
            IllegalStateException.class,
            () -> current.idFor(
                material(2L, 20L),
                shader(100L)
            )
        );
    }

    @Test
    void constructorRejectsInvalidGenerationAndCapacity() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new NativeTerrainSurfaceIdRegistry(0L, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new NativeTerrainSurfaceIdRegistry(1L, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new NativeTerrainSurfaceIdRegistry(
                1L,
                Integer.MAX_VALUE
            )
        );
    }

    private static MaterialBinding material(
        long generation,
        long seed
    ) {
        return new MaterialBinding(
            generation,
            id(seed),
            id(seed + 1L),
            id(seed + 2L),
            id(seed + 3L),
            id(seed + 4L),
            id(seed + 5L),
            AlphaMode.OPAQUE,
            Float.floatToRawIntBits(0.0F)
        );
    }

    private static MaterialBinding copy(
        MaterialBinding source,
        StableId materialFamily,
        StableId texture,
        StableId sampler,
        StableId layer,
        StableId animation,
        StableId pbr,
        AlphaMode alphaMode,
        int alphaCutoffBits
    ) {
        return new MaterialBinding(
            source.registryGeneration(),
            materialFamily,
            texture,
            sampler,
            layer,
            animation,
            pbr,
            alphaMode,
            alphaCutoffBits
        );
    }

    private static ShaderContract shader(long seed) {
        return new ShaderContract(
            new Digest(seed, seed + 1L, seed + 2L, seed + 3L),
            TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
            MotionModel.STATIC_WORLD_WITH_CAMERA_HISTORY
        );
    }

    private static StableId id(long value) {
        return new StableId(0xB10CF4A0L, value);
    }
}
