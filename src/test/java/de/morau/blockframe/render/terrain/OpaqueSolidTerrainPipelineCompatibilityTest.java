package de.morau.blockframe.render.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.junit.jupiter.api.Test;

class OpaqueSolidTerrainPipelineCompatibilityTest {
    private static List<BindGroupLayout> terrainLayouts() {
        return List.of(
            BindGroupLayouts.GLOBALS,
            BindGroupLayouts.FOG,
            BindGroupLayouts.SAMPLER0_SAMPLER2,
            BindGroupLayouts.PROJECTION,
            BindGroupLayouts.CHUNK_SECTION
        );
    }

    @Test
    void acceptsExactMojangTerrainLayout() {
        assertTrue(
            OpaqueSolidTerrainBatchCache
                .hasExpectedTerrainBindGroups(terrainLayouts())
        );
    }

    @Test
    void acceptsOnlyTheAttestedVulkanExtension() {
        List<BindGroupLayout> layouts =
            new ArrayList<>(terrainLayouts());
        layouts.add(
            BindGroupLayout.builder()
                .withUniform(
                    "MilkshadeDynamicLights",
                    UniformType.UNIFORM_BUFFER
                )
                .build()
        );

        assertTrue(
            OpaqueSolidTerrainBatchCache
                .hasKnownVulkanTerrainExtension(layouts)
        );
        assertFalse(
            OpaqueSolidTerrainBatchCache
                .hasExpectedTerrainBindGroups(layouts)
        );
    }

    @Test
    void rejectsUnknownOrReorderedExtensions() {
        List<BindGroupLayout> unknown =
            new ArrayList<>(terrainLayouts());
        unknown.add(
            BindGroupLayout.builder()
                .withUniform(
                    "UnknownRendererState",
                    UniformType.UNIFORM_BUFFER
                )
                .build()
        );
        assertFalse(
            OpaqueSolidTerrainBatchCache
                .hasKnownVulkanTerrainExtension(unknown)
        );

        List<BindGroupLayout> reordered =
            new ArrayList<>(terrainLayouts());
        BindGroupLayout first = reordered.get(0);
        reordered.set(0, reordered.get(1));
        reordered.set(1, first);
        reordered.add(
            BindGroupLayout.builder()
                .withUniform(
                    "MilkshadeDynamicLights",
                    UniformType.UNIFORM_BUFFER
                )
                .build()
        );
        assertFalse(
            OpaqueSolidTerrainBatchCache
                .hasKnownVulkanTerrainExtension(reordered)
        );
    }

    @Test
    void rejectsAdditionalSamplerOrUniform() {
        List<BindGroupLayout> extraSampler =
            new ArrayList<>(terrainLayouts());
        extraSampler.add(
            BindGroupLayout.builder()
                .withSampler("UnexpectedSampler")
                .withUniform(
                    "MilkshadeDynamicLights",
                    UniformType.UNIFORM_BUFFER
                )
                .build()
        );
        assertFalse(
            OpaqueSolidTerrainBatchCache
                .hasKnownVulkanTerrainExtension(extraSampler)
        );

        List<BindGroupLayout> extraUniform =
            new ArrayList<>(terrainLayouts());
        extraUniform.add(
            BindGroupLayout.builder()
                .withUniform(
                    "MilkshadeDynamicLights",
                    UniformType.UNIFORM_BUFFER
                )
                .withUniform(
                    "UnexpectedState",
                    UniformType.UNIFORM_BUFFER
                )
                .build()
        );
        assertFalse(
            OpaqueSolidTerrainBatchCache
                .hasKnownVulkanTerrainExtension(extraUniform)
        );
    }
}
