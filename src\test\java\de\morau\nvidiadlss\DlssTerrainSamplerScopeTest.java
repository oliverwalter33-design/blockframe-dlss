package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class DlssTerrainSamplerScopeTest {
    @Test
    void acceptsOnlyTheFiveExactOpaqueSolidTerrainPipelineIds() {
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("minecraft", "pipeline/solid_terrain"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/solid_terrain"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("milkshade", "pipeline/solid_terrain"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("voxellift", "pipeline/native_terrain_solid_v1"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id(
                    "voxellift",
                    "pipeline/opaque_solid_gpu_scene_indirect_v1"
                ),
                false
            )
        );
    }

    @Test
    void acceptsAndClassifiesOnlyTheFourExactCutoutPipelineIds() {
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("minecraft", "pipeline/cutout_terrain"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/cutout_terrain"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("milkshade", "pipeline/cutout_terrain"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.eligible(
                id("voxellift", "pipeline/native_terrain_cutout_v1"),
                false
            )
        );
        assertTrue(
            DlssTerrainSamplerScope.isCutout(
                id("sodium", "pipeline/cutout_terrain")
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.isCutout(
                id("sodium", "pipeline/solid_terrain")
            )
        );
    }

    @Test
    void rejectsTranslucentParticlesAndLookalikePaths() {
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/translucent_terrain"),
                false
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/solid_terrain_particles"),
                false
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/solid_terrain_extra"),
                false
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("foreign", "pipeline/solid_terrain"),
                false
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("foreign", "pipeline/cutout_terrain"),
                false
            )
        );
    }

    @Test
    void rejectsBlendedAndUnknownPipelines() {
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/solid_terrain"),
                true
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.eligible(
                id("sodium", "pipeline/cutout_terrain"),
                true
            )
        );
        assertFalse(DlssTerrainSamplerScope.eligible(null, false));
        assertFalse(DlssTerrainSamplerScope.isCutout(null));
    }

    @Test
    void acceptsOnlyTheExactMinecraftBlockAtlasLabel() {
        assertTrue(
            DlssTerrainSamplerScope.isBlockAtlas(
                "minecraft:textures/atlas/blocks.png"
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.isBlockAtlas(
                "foreign:textures/atlas/blocks.png"
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.isBlockAtlas(
                "minecraft:textures/atlas/blocks.png-atlas"
            )
        );
        assertFalse(
            DlssTerrainSamplerScope.isBlockAtlas(
                "prefix-minecraft:textures/atlas/blocks.png"
            )
        );
        assertFalse(DlssTerrainSamplerScope.isBlockAtlas(null));
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
