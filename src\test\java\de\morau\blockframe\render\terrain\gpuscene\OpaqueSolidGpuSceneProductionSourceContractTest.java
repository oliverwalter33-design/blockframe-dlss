package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.EngineConfig;
import de.morau.blockframe.core.state.FeatureId;
import de.morau.blockframe.core.state.RuntimeFeaturePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpaqueSolidGpuSceneProductionSourceContractTest {
    @Test
    void noGoCandidateIsDefaultOffAndRequiresExplicitProcessRequest() {
        assertFalse(
            EngineConfig.Settings.defaults()
                .opaqueSolidGpuSceneIndirectExperimentalEnabled()
        );
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "off",
            "heap",
            false
        );
        assertFalse(
            policy.requested(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
            )
        );
        assertFalse(
            policy.enabled(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
            )
        );

        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        assertFalse(
            bootstrap.contains(
                "OpaqueSolidIndirectNegotiator.configure("
            )
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuSceneRuntime.java"
        );
        assertTrue(
            runtime.contains(
                ".OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL"
            )
        );
    }

    @Test
    void archivedOwnerHooksAreNotRegisteredAndV1RemainsDisabled() {
        String mixins = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidGpuSceneUberBufferMixin\""
            )
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidGpuSceneRenderSectionMixin\""
            )
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidGpuSceneLevelRendererMixin\""
            )
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidGpuSceneRenderGroupMixin\""
            )
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidTerrainLevelRendererMixin\""
            )
        );
        assertFalse(
            mixins.contains(
                "\"OpaqueSolidTerrainRenderGroupMixin\""
            )
        );
        String uber = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "OpaqueSolidGpuSceneUberBufferMixin.java"
        );
        assertTrue(uber.contains("method = \"freeAllocation\""));
        assertTrue(uber.contains("@WrapMethod"));
        assertTrue(uber.contains("at = @At(\"HEAD\")"));
        assertTrue(uber.contains("rangePublished("));
        String section = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "OpaqueSolidGpuSceneRenderSectionMixin.java"
        );
        assertTrue(
            section.indexOf("sectionMeshInvalidating(")
                < section.indexOf("sectionMeshPublished(")
        );
        String policy = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuScenePolicy.java"
        );
        assertTrue(policy.contains("return false;"));
        assertFalse(policy.contains("featureEnabledIfInitialized("));
    }

    @Test
    void successfulSolidPrepareDoesNotConstructCpuDrawRecords() {
        String runtime = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuSceneRuntime.java"
        );
        int solidBranch = runtime.indexOf(
            "if (layer == ChunkSectionLayer.SOLID)"
        );
        int skip = runtime.indexOf("continue;", solidBranch);
        int drawConstruction = runtime.indexOf(
            "new RenderPass.Draw<>",
            solidBranch
        );
        assertTrue(solidBranch >= 0);
        assertTrue(skip > solidBranch);
        assertTrue(drawConstruction > skip);
        assertTrue(runtime.contains("MODEL.appendVisible("));
        assertTrue(runtime.contains("resources.prepare("));
        assertTrue(
            runtime.contains(
                "blockframe$drawIndexedIndirectCount("
            )
        );
        assertFalse(runtime.contains("vkCmdDrawIndexedIndirect("));
    }

    @Test
    void computeCompactsCommandsAndCountWithoutReadback() {
        String compute = source(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "opaque_solid_gpu_scene_compact_v1.comp"
        );
        assertTrue(compute.contains("atomicAdd(counts[bucket], 1u)"));
        assertTrue(compute.contains("commands[commandBase + 4u] = slot"));
        assertTrue(compute.contains("visibility[slot] = visibilityBits"));
        String resources = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuSceneDeviceResources.java"
        );
        assertTrue(resources.contains("vkCmdDispatch("));
        assertTrue(resources.contains("USAGE_INDIRECT_PARAMETERS"));
        assertTrue(resources.contains("STORAGE_BUFFER_USAGE"));
        assertTrue(resources.contains("FRAME_COUNT = 2"));
        assertTrue(resources.contains("tryReserve("));
        assertTrue(resources.contains("queueAllBufferViews()"));
        assertTrue(resources.contains("closeAllMappedAndBuffers()"));
        assertTrue(resources.contains("retryFailure"));
        assertTrue(resources.contains("cleanupFailed("));
        assertFalse(resources.contains("USAGE_MAP_READ"));
        assertFalse(resources.contains("readToBuffer("));
        String renderPass = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanRenderPassMixin.java"
        );
        assertTrue(
            renderPass.contains(
                "blockframe$pushCachedGpuSceneDescriptors("
            )
        );
        assertTrue(
            renderPass.contains(
                "blockframe$prepareOpaqueSolidDescriptors("
            )
        );
        assertTrue(renderPass.contains("sceneBufferView"));
        assertTrue(renderPass.contains("visibilityBufferView"));
        String cachedPush = method(
            renderPass,
            "blockframe$pushCachedGpuSceneDescriptors("
        );
        assertFalse(cachedPush.contains("vkCreateBufferView("));
        assertFalse(cachedPush.contains("queueForDestroy("));
    }

    @Test
    void graphicsShaderUsesBaseInstanceAndRetainsVanillaContracts() {
        String vertex = source(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "opaque_solid_gpu_scene_indirect_v1.vsh"
        );
        String fragment = source(
            "src/main/resources/assets/voxellift/shaders/core/"
                + "opaque_solid_gpu_scene_indirect_v1.fsh"
        );
        assertTrue(vertex.contains("#version 460"));
        assertTrue(vertex.contains("gl_BaseInstance"));
        assertFalse(vertex.contains("gl_BaseInstanceARB"));
        assertFalse(vertex.contains("GL_ARB_shader_draw_parameters"));
        assertTrue(vertex.contains("CameraBlockPos"));
        assertTrue(vertex.contains("CameraOffset"));
        assertTrue(vertex.contains("sample_lightmap(Sampler2, UV2)"));
        assertTrue(fragment.contains("UseRgss == 1"));
        assertTrue(fragment.contains("chunkVisibility"));
        assertTrue(fragment.contains("apply_fog("));
        assertFalse(vertex.contains("ChunkSection"));
        assertFalse(fragment.contains("ChunkSection"));
    }

    @Test
    void fallbackAndNoReplayBoundariesAreExplicit() {
        String runtime = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuSceneRuntime.java"
        );
        assertTrue(runtime.contains("MOJANG_ONLY_OPENGL"));
        assertTrue(runtime.contains("MOJANG_ONLY_UNKNOWN_SHADER"));
        assertTrue(runtime.contains("OpaqueSolidPipelineAbi.mismatch("));
        assertFalse(
            runtime.contains(
                "ChunkSectionLayer.SOLID.pipeline()\n"
                    + "                    != RenderPipelines.SOLID_TERRAIN"
            )
        );
        assertTrue(runtime.contains("MOJANG_ONLY_CAPABILITY:"));
        assertTrue(runtime.contains("MOJANG_FALLBACK_COMPUTE_PREPARE"));
        assertTrue(runtime.contains("resources.beginSubmission()"));
        assertTrue(runtime.contains("postSubmissionFailures++"));
        assertTrue(runtime.contains("renderMojangSolidFallback("));
        assertTrue(runtime.contains("sameFrameMojangFallbacks++"));
        assertTrue(runtime.contains("OPAQUE_SOLID_GPU_SCENE event={}"));
        assertTrue(runtime.contains("logLifecycleSnapshot(\"device-close\")"));
        assertTrue(runtime.contains("preActivationFallbackFrames"));
        assertTrue(runtime.contains("runtimeFallbackFrames"));
        assertTrue(runtime.contains("recordFallbackFrame()"));
        assertTrue(
            runtime.contains(
                "indirectSubmittedInOwnerGeneration = true"
            )
        );
        assertTrue(runtime.contains("throw error;"));
        assertFalse(runtime.contains("ThreadPool"));
        assertFalse(runtime.contains("Executor"));
        assertFalse(runtime.contains("parallelStream"));
        String ownerMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "OpaqueSolidGpuSceneUberBufferMixin.java"
        );
        assertTrue(ownerMixin.contains("finally {"));
        assertTrue(ownerMixin.contains("callback.bufferHasBeenUploaded("));
        assertTrue(ownerMixin.contains("ownerHookFailed("));
    }

    @Test
    void framePathHasNoFullSceneEnumeration() {
        String model = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuSceneModel.java"
        );
        String append = method(model, "appendVisible(");
        String drain = method(model, "drainDirty(");
        String visibility = method(model, "writeVisibility(");
        assertFalse(append.contains("for (int"));
        assertFalse(drain.contains("slot < this.capacity"));
        assertFalse(visibility.contains("slot < this.capacity"));
        assertTrue(
            method(model, "clearAfterOwnerInvalidation(")
                .contains("slot < this.capacity")
        );
    }

    @Test
    void worldUnavailableSnapshotRunsOnlyOnTheWorldTransition() {
        String runtime = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "gpuscene/OpaqueSolidGpuSceneRuntime.java"
        );
        String unavailable = method(runtime, "worldUnavailable()");
        int guard = unavailable.indexOf("if (worldIdentity == null)");
        int snapshot = unavailable.indexOf(
            "logLifecycleSnapshot(\"world-unavailable\")"
        );
        int invalidation = unavailable.indexOf(
            "invalidateAllOwnerState(\"world-unavailable\")"
        );
        assertTrue(guard >= 0);
        assertTrue(snapshot > guard);
        assertTrue(invalidation > snapshot);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("missing " + signature);
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int index = brace; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("unterminated " + signature);
    }

    private static String source(String relative) {
        Path root = Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
        try {
            return Files.readString(
                root.resolve(relative),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
