package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

class NativeTerrainRendererCShaderCompilerTest {
    @Test
    void mojangVulkanCompilerAcceptsExpandedGraphicsPair()
        throws Exception {
        String vertex = expandMinecraftImports(
            resource("native_terrain_v2.vsh")
        );
        String fragment = expandMinecraftImports(
            resource("native_terrain_v2.fsh")
        );
        try (
            GlslCompiler compiler = new GlslCompiler();
            IntermediaryShaderModule vertexModule =
                compiler.createIntermediary(
                    "native_terrain_v2.vsh",
                    vertex,
                    ShaderType.VERTEX
                );
            IntermediaryShaderModule fragmentModule =
                compiler.createIntermediary(
                    "native_terrain_v2.fsh",
                    fragment,
                    ShaderType.FRAGMENT
                )
        ) {
            assertTrue(vertexModule.spirv().remaining() > 0);
            assertTrue(fragmentModule.spirv().remaining() > 0);
        }
    }

    @Test
    void shadercAcceptsFrustumAndIndirectComputeShader()
        throws IOException {
        byte[] source = resource(
            "native_terrain_frustum_indirect_v1.comp"
        ).getBytes(StandardCharsets.UTF_8);

        long compiler = Shaderc.shaderc_compiler_initialize();
        assertTrue(compiler != MemoryUtil.NULL);
        long result = MemoryUtil.NULL;
        try {
            result = Shaderc.shaderc_compile_into_spv(
                compiler,
                new String(source, StandardCharsets.UTF_8),
                Shaderc.shaderc_compute_shader,
                "native_terrain_frustum_indirect_v1.comp",
                "main",
                MemoryUtil.NULL
            );
            assertTrue(result != MemoryUtil.NULL);
            String compilationMessage =
                Shaderc.shaderc_result_get_error_message(result);
            assertTrue(
                Shaderc.shaderc_result_get_compilation_status(result)
                    == Shaderc.shaderc_compilation_status_success,
                compilationMessage
            );
            assertTrue(
                Shaderc.shaderc_result_get_length(result) > 0L
            );
        } finally {
            if (result != MemoryUtil.NULL) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static String resource(String name) throws IOException {
        try (
            var input =
                NativeTerrainRendererCShaderCompilerTest.class
                    .getResourceAsStream(
                        "/assets/voxellift/shaders/core/" + name
                    )
        ) {
            if (input == null) {
                throw new IOException(
                    "missing Renderer C shader " + name
                );
            }
            return new String(
                input.readAllBytes(),
                StandardCharsets.UTF_8
            );
        }
    }

    private static String expandMinecraftImports(String source)
        throws IOException {
        String expanded = source;
        for (String include : new String[] {
            "fog",
            "globals",
            "dynamictransforms",
            "projection",
            "sample_lightmap"
        }) {
            String marker =
                "#moj_import <minecraft:" + include + ".glsl>";
            if (!expanded.contains(marker)) {
                continue;
            }
            expanded = expanded.replace(
                marker,
                minecraftInclude(include)
            );
        }
        assertTrue(!expanded.contains("#moj_import"));
        return expanded;
    }

    private static String minecraftInclude(String name)
        throws IOException {
        Path root = Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
        Path jar = root.resolve(
            "build/moddev/artifacts/"
                + "minecraft-patched-26.2.0.23-beta-sources.jar"
        );
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(
                "assets/minecraft/shaders/include/" + name + ".glsl"
            );
            if (entry == null) {
                throw new IOException(
                    "missing Minecraft shader include " + name
                );
            }
            String text = new String(
                zip.getInputStream(entry).readAllBytes(),
                StandardCharsets.UTF_8
            );
            return text.replaceFirst(
                "(?m)^#version\\s+\\d+\\s*\\R",
                ""
            );
        }
    }
}
