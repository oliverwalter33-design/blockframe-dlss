package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event
    .RegisterRenderPipelinesEvent;

/**
 * Exact 32-byte V2 terrain graphics ABI for the native backend.
 */
public final class NativeTerrainPipelines {
    public static final VertexFormat BLOCK_PAYLOAD_V2 =
        VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
            .build();

    public static final BindGroupLayout SCENE_LAYOUT =
        BindGroupLayout.builder()
            .withUniform(
                "NativeTerrainScene",
                UniformType.TEXEL_BUFFER,
                GpuFormat.RGBA32_UINT
            )
            .build();

    private static final Identifier SHADER =
        Identifier.fromNamespaceAndPath(
            "voxellift",
            "core/native_terrain_v2"
        );

    public static final RenderPipeline SOLID =
        RenderPipeline.builder(RenderPipelines.GENERIC_BLOCKS_SNIPPET)
            .withBindGroupLayout(
                BindGroupLayouts.MATRICES_PROJECTION
            )
            .withBindGroupLayout(SCENE_LAYOUT)
            .withVertexBinding(0, BLOCK_PAYLOAD_V2)
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "voxellift",
                    "pipeline/native_terrain_solid_v1"
                )
            )
            .build();

    public static final RenderPipeline CUTOUT =
        RenderPipeline.builder(RenderPipelines.GENERIC_BLOCKS_SNIPPET)
            .withBindGroupLayout(
                BindGroupLayouts.MATRICES_PROJECTION
            )
            .withBindGroupLayout(SCENE_LAYOUT)
            .withVertexBinding(0, BLOCK_PAYLOAD_V2)
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine(
                "ALPHA_CUTOUT",
                Float.intBitsToFloat(
                    TerrainMeshProducerABI
                        .MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
                )
            )
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "voxellift",
                    "pipeline/native_terrain_cutout_v1"
                )
            )
            .build();

    static {
        if (
            BLOCK_PAYLOAD_V2.getVertexSize()
                != TerrainMeshProducerABI
                    .BLOCK_PAYLOAD_V2_STRIDE_BYTES
        ) {
            throw new ExceptionInInitializerError(
                "native terrain V2 vertex stride mismatch"
            );
        }
    }

    private NativeTerrainPipelines() {
    }

    public static void register(
        RegisterRenderPipelinesEvent event
    ) {
        event.registerPipeline(SOLID);
        event.registerPipeline(CUTOUT);
    }
}
