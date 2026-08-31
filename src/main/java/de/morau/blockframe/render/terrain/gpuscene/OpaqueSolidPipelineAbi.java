package de.morau.blockframe.render.terrain.gpuscene;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Structural Mojang solid-terrain pipeline contract.
 *
 * <p>NeoForge may rebuild an unchanged pipeline as a distinct Java object.
 * Object identity is therefore not an ABI guarantee. Every state consumed by
 * Vulkan pipeline creation is compared instead; the sort key is intentionally
 * excluded because it affects ordering rather than graphics compatibility.</p>
 */
final class OpaqueSolidPipelineAbi {
    private OpaqueSolidPipelineAbi() {
    }

    static String mismatch(RenderPipeline actual) {
        RenderPipeline expected = RenderPipelines.SOLID_TERRAIN;
        if (actual == null) {
            return "MISSING";
        }
        if (!Objects.equals(
            actual.getLocation(),
            expected.getLocation()
        )) {
            return "LOCATION";
        }
        if (!Objects.equals(
            actual.getVertexShader(),
            expected.getVertexShader()
        )) {
            return "VERTEX_SHADER";
        }
        if (!Objects.equals(
            actual.getFragmentShader(),
            expected.getFragmentShader()
        )) {
            return "FRAGMENT_SHADER";
        }
        if (!Objects.equals(
            actual.getShaderDefines(),
            expected.getShaderDefines()
        )) {
            return "SHADER_DEFINES";
        }
        if (!sameBindGroups(
            actual.getBindGroupLayouts(),
            expected.getBindGroupLayouts()
        )) {
            return "BIND_GROUP_LAYOUTS";
        }
        if (!sameVertexBindings(
            actual.getVertexFormatBindings(),
            expected.getVertexFormatBindings()
        )) {
            return "VERTEX_BINDINGS";
        }
        if (
            actual.getPrimitiveTopology()
                != expected.getPrimitiveTopology()
        ) {
            return "PRIMITIVE_TOPOLOGY";
        }
        if (!Objects.equals(
            actual.getDepthStencilState(),
            expected.getDepthStencilState()
        )) {
            return "DEPTH_STENCIL";
        }
        if (actual.getPolygonMode() != expected.getPolygonMode()) {
            return "POLYGON_MODE";
        }
        if (actual.isCull() != expected.isCull()) {
            return "CULL";
        }
        if (!Arrays.equals(
            actual.getColorTargetStates(),
            expected.getColorTargetStates()
        )) {
            return "COLOR_TARGETS";
        }
        if (!Objects.equals(
            actual.getStencilTest(),
            expected.getStencilTest()
        )) {
            return "STENCIL_TEST";
        }
        return null;
    }

    private static boolean sameBindGroups(
        List<BindGroupLayout> actual,
        List<BindGroupLayout> expected
    ) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            BindGroupLayout actualLayout = actual.get(index);
            BindGroupLayout expectedLayout = expected.get(index);
            if (
                !actualLayout.getSamplers().equals(
                    expectedLayout.getSamplers()
                )
                    || !actualLayout.getUniforms().equals(
                        expectedLayout.getUniforms()
                    )
            ) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameVertexBindings(
        VertexFormat[] actual,
        VertexFormat[] expected
    ) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual.length != expected.length) {
            return false;
        }
        for (int index = 0; index < actual.length; index++) {
            VertexFormat actualFormat = actual[index];
            VertexFormat expectedFormat = expected[index];
            if (actualFormat == null || expectedFormat == null) {
                if (actualFormat != expectedFormat) {
                    return false;
                }
            } else if (
                !actualFormat.equals(expectedFormat)
                    || actualFormat.getStepRate()
                        != expectedFormat.getStepRate()
            ) {
                return false;
            }
        }
        return true;
    }
}
