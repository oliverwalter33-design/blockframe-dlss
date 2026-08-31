package de.morau.blockframe.render.terrain.gpuscene;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * Exact terrain pipeline ABI for the indirect opaque-solid path.
 *
 * <p>The scene and visibility buffers replace only Mojang's per-draw
 * {@code ChunkSection} UBO. Globals, fog, projection, vertex format, block
 * atlas, lightmap, depth state, culling and raster state are inherited from
 * the vanilla generic-blocks contract.</p>
 */
public final class OpaqueSolidGpuScenePipelines {
    public static final int PIPELINE_KEY =
        "voxellift:pipeline/opaque_solid_gpu_scene_indirect_v1".hashCode();
    public static final int SHADER_ABI_KEY =
        "opaque-solid-gpu-scene-indirect-v1:scene-rgba32ui:"
            .hashCode();
    public static final int MATERIAL_KEY =
        "minecraft:block-atlas:solid".hashCode();

    public static final BindGroupLayout GPU_SCENE_LAYOUT =
        BindGroupLayout.builder()
            .withUniform(
                "OpaqueSolidFrame",
                UniformType.UNIFORM_BUFFER
            )
            .withUniform(
                "OpaqueSolidScene",
                UniformType.TEXEL_BUFFER,
                GpuFormat.RGBA32_UINT
            )
            .withUniform(
                "OpaqueSolidVisibility",
                UniformType.TEXEL_BUFFER,
                GpuFormat.R32_UINT
            )
            .build();

    private static final Identifier SHADER =
        Identifier.fromNamespaceAndPath(
            "voxellift",
            "core/opaque_solid_gpu_scene_indirect_v1"
        );

    public static final RenderPipeline OPAQUE_SOLID_INDIRECT =
        RenderPipeline.builder(RenderPipelines.GENERIC_BLOCKS_SNIPPET)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(GPU_SCENE_LAYOUT)
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "voxellift",
                    "pipeline/opaque_solid_gpu_scene_indirect_v1"
                )
            )
            .build();

    private OpaqueSolidGpuScenePipelines() {
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(OPAQUE_SOLID_INDIRECT);
    }
}
