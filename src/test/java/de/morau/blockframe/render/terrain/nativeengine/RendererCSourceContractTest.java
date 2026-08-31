package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RendererCSourceContractTest {
    @Test
    void productionSelectionRemainsMojangUntilCompleteShaderOwnerExists() {
        String foundation = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainBackendFoundation.java"
        );
        assertTrue(foundation.contains(
            "minecraft-shader-and-material-adapter-not-connected"
        ));
        assertTrue(foundation.contains(
            "new NativeTerrainBackendSelector.FormatAttestation("
        ));
        assertFalse(foundation.contains(
            "demoteUnavailableNativeFactory("
        ));
        assertTrue(foundation.contains(
            "beginReferenceWorldResourceCreation()"
        ));
        assertTrue(foundation.contains(
            "Mojang construction is forbidden"
        ));
        assertFalse(foundation.contains(
            "new NativeTerrainSubmissionOwner("
        ));
    }

    @Test
    void sceneAndComputeOwnPersistentGpuDataWithoutCpuVisibleList() {
        String scene = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainGpuScene.java"
        );
        assertTrue(scene.contains(
            "BufferKind.STORAGE_SCENE"
        ));
        assertTrue(scene.contains(
            "BufferKind.INDIRECT_COMMAND"
        ));
        assertTrue(scene.contains(
            "BufferKind.INDIRECT_COUNT"
        ));
        assertTrue(scene.contains("recordDirtyUpload("));
        assertTrue(scene.contains("requireNoPendingMutation();"));
        assertFalse(scene.contains("visibleSections"));
        assertFalse(scene.contains("Stream<"));

        String compute = local(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "native_terrain_frustum_indirect_v1.comp"
        );
        assertTrue(compute.contains(
            "layout(local_size_x = 64) in;"
        ));
        assertTrue(compute.contains(
            "atomicAdd(counts[bucket], 1u)"
        ));
        assertTrue(compute.contains(
            "commands[command + 4u] = slot;"
        ));
        assertTrue(compute.contains(
            "atomicAnd(scene[base], ~2u);"
        ));
        assertFalse(compute.contains("HZB"));
        assertFalse(compute.contains("occlusion"));
    }

    @Test
    void commandStreamUsesExactPortableSynchronizationAndNoNewSubmit() {
        String resources = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/"
                + "NativeTerrainGpuSceneVulkanResources.java"
        );
        assertTrue(resources.contains(
            "VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR"
        ));
        assertTrue(resources.contains(
            "VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR"
        ));
        assertTrue(resources.contains(
            "VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR"
        ));
        assertTrue(resources.contains(
            "VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR"
        ));
        assertFalse(resources.contains("vkQueueSubmit"));
        assertFalse(resources.contains("vkDeviceWaitIdle"));
        assertFalse(resources.contains("new Thread"));
        assertFalse(resources.contains("Executor"));
    }

    @Test
    void submissionIsConstantPerVertexPageAndCannotReplayAfterDraw() {
        String submission = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainSubmissionOwner.java"
        );
        assertTrue(submission.contains(
            "for (int page = 0; page < this.vertexPages.length; page++)"
        ));
        assertTrue(submission.contains(
            "blockframe$drawNativeTerrainIndirectCount("
        ));
        assertTrue(submission.contains(
            "FAILED_AFTER_SUBMISSION"
        ));
        assertFalse(submission.contains("visibleSections"));
        assertFalse(submission.contains("new RenderPass.Draw"));
        assertFalse(submission.contains("stream()"));
    }

    @Test
    void graphicsAbiKeepsExactStrideAlphaAndReversedDepthSnippet() {
        String pipelines = local(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/NativeTerrainPipelines.java"
        );
        assertTrue(pipelines.contains(
            "RenderPipelines.GENERIC_BLOCKS_SNIPPET"
        ));
        assertTrue(pipelines.contains(
            "BLOCK_PAYLOAD_V2_STRIDE_BYTES"
        ));
        assertTrue(pipelines.contains(
            "MOJANG_CUTOUT_ALPHA_CUTOFF_BITS"
        ));
        assertTrue(pipelines.contains(
            "UniformType.TEXEL_BUFFER"
        ));

        String vertex = local(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "native_terrain_v2.vsh"
        );
        assertTrue(vertex.contains("gl_BaseInstance"));
        assertTrue(vertex.contains("CameraBlockPos"));
        assertTrue(vertex.contains("+ CameraOffset"));
        assertTrue(vertex.contains(
            "#moj_import <minecraft:dynamictransforms.glsl>"
        ));
        assertTrue(vertex.contains("sample_lightmap(Sampler2, UV2)"));

        String fragment = local(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "native_terrain_v2.fsh"
        );
        assertTrue(fragment.contains(
            "if (color.a < ALPHA_CUTOUT)"
        ));
        assertTrue(fragment.contains(
            "fragColor.a = 254.0 / 255.0"
        ));
        assertFalse(fragment.contains("flat in vec3 nativeTerrainNormal"));
        assertTrue(fragment.contains("apply_fog("));

        String compute = local(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "native_terrain_frustum_indirect_v1.comp"
        );
        assertTrue(
            compute.contains(
                ") + CameraOffsetAndCapacity.xyz"
            )
        );
        assertFalse(
            compute.contains(
                ") - CameraOffsetAndCapacity.xyz"
            )
        );
        assertTrue(
            compute.contains(
                "+ plane.w < 0.0"
            )
        );
        assertFalse(compute.contains("1.0 / 1024.0"));
    }

    @Test
    void archivedHybridHooksRemainDisabled() {
        String mixins = local(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        for (String name : new String[] {
            "OpaqueSolidGpuSceneUberBufferMixin",
            "OpaqueSolidGpuSceneLevelRendererMixin",
            "OpaqueSolidGpuSceneRenderSectionMixin",
            "OpaqueSolidGpuSceneRenderGroupMixin"
        }) {
            assertFalse(mixins.contains("\"" + name + "\""));
        }
    }

    private static String local(String relative) {
        try {
            return Files.readString(
                Path.of(
                    System.getProperty("blockframe.projectDir", ".")
                ).resolve(relative),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
