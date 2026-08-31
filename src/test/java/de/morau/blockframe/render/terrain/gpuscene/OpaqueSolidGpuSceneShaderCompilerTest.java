package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.junit.jupiter.api.Test;

class OpaqueSolidGpuSceneShaderCompilerTest {
    @Test
    void mojangVulkanCompilerAcceptsCoreBaseInstance() throws Exception {
        String source = """
            #version 460

            void main() {
                uint slot = uint(gl_BaseInstance);
                gl_Position = vec4(float(slot), 0.0, 0.0, 1.0);
            }
            """;

        try (
            GlslCompiler compiler = new GlslCompiler();
            IntermediaryShaderModule module =
                compiler.createIntermediary(
                    "blockframe_base_instance_contract.vsh",
                    source,
                    ShaderType.VERTEX
                )
        ) {
            assertNotNull(module.spirv());
            assertTrue(module.spirv().remaining() > 0);
        }
    }
}
