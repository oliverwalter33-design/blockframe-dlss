package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a12FeatureSwitchSourceContractTest {
    @Test
    void dlssOverrideKeepsTheRequestButSelectsMojangsHighTarget()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String begin = section(
            renderer,
            "public static RenderTarget beginFrame(",
            "public static Matrix4f applyWorldJitter("
        );

        int requested = begin.indexOf(
            "requestedMode = DlssConfig.mode();"
        );
        int policy = begin.indexOf(
            "BlockframeRuntime.featureEnabled("
        );
        int feature = begin.indexOf(
            "FeatureId.DLSS_MODE",
            policy
        );
        int disabled = begin.indexOf(
            ": DlssMode.OFF;",
            feature
        );
        int offCheck = begin.indexOf(
            "mode == DlssMode.OFF",
            disabled
        );
        int highTarget = begin.indexOf(
            "return highTarget;",
            offCheck
        );
        int resources = begin.indexOf("ensureResources(");

        assertTrue(requested >= 0);
        assertTrue(policy > requested);
        assertTrue(feature > policy);
        assertTrue(disabled > feature);
        assertTrue(offCheck > disabled);
        assertTrue(highTarget > offCheck);
        assertTrue(resources > highTarget);
        assertEquals(
            1,
            occurrences(begin, "requestedMode = DlssConfig.mode();")
        );
        assertFalse(begin.contains("requestedMode = DlssMode.OFF"));
    }

    @Test
    void motionAndNativeHistoryGatesPrecedeEveryOptionalAllocation()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String owner = section(
            renderer,
            "private static MotionObjectBatch motionObjectBatchOrNull()",
            "private static void clearMotionObjectHistory()"
        );

        int attempted = owner.indexOf(
            "motionObjectBatchCreationAttempted = true;"
        );
        int motionGate = owner.indexOf(
            "FeatureId.ENTITY_MOTION_SCRATCH",
            attempted
        );
        int disabledReturn = owner.indexOf(
            "return null;",
            motionGate
        );
        int batchAllocation = owner.indexOf(
            "MotionObjectBatch.tryCreate("
        );
        assertTrue(motionGate >= 0);
        assertTrue(motionGate > attempted);
        assertTrue(disabledReturn > motionGate);
        assertTrue(batchAllocation > attempted);
        assertTrue(
            owner.substring(motionGate, disabledReturn)
                .contains("disabled by process feature policy")
        );

        int configuredBackend = owner.indexOf(
            "configuredHistoryBackend ="
        );
        int nativeGate = owner.indexOf(
            ".ENTITY_HISTORY_NATIVE_EXPERIMENTAL",
            configuredBackend
        );
        int heapSelection = owner.indexOf(
            ": EntityMotionHistory.BackendPreference.HEAP;",
            nativeGate
        );
        int historyAllocation = owner.indexOf(
            "EntityMotionHistory.tryCreate(",
            heapSelection
        );
        assertTrue(configuredBackend > attempted);
        assertTrue(nativeGate > configuredBackend);
        assertTrue(heapSelection > nativeGate);
        assertTrue(historyAllocation > heapSelection);
        assertTrue(
            owner.contains("\"Heap-Process-Policy-Fallback \"")
        );
    }

    @Test
    void transformGateSelectsTheExactLegacyMatrixPathWithoutALease()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String owner = section(
            renderer,
            "private static DlssTransformScratch transformScratchOrNull()",
            "private static DlssTransformScratch activeTransformScratch()"
        );
        String finish = section(
            renderer,
            "public static RenderTarget finishWorldFrame(",
            "private static void prepareNativeOutlineDepthSafely()"
        );

        int attempted = owner.indexOf(
            "transformScratchCreationAttempted = true;"
        );
        int gate = owner.indexOf(
            "FeatureId.TRANSFORM_SCRATCH",
            attempted
        );
        int disabledReturn = owner.indexOf("return null;", gate);
        int allocation = owner.indexOf(
            "DlssTransformScratch.tryCreate("
        );
        assertTrue(gate >= 0);
        assertTrue(gate > attempted);
        assertTrue(disabledReturn > gate);
        assertTrue(allocation > attempted);
        assertTrue(
            owner.substring(gate, disabledReturn)
                .contains("disabled by process feature policy")
        );

        int legacy = finish.indexOf(
            "if (!transformScratchFrame) {"
        );
        int publish = finish.indexOf(
            "lastTransformScratchPath = transformScratchFrame;",
            legacy
        );
        String exactLegacy = finish.substring(legacy, publish);
        assertTrue(exactLegacy.contains("new Matrix4f("));
        assertTrue(exactLegacy.contains("new Vector3f("));
        assertTrue(
            exactLegacy.contains("previousViewProjection == null")
        );
    }

    @Test
    void materialSamplerGateReturnsOriginalBeforeCacheAndLeaseCreation()
        throws Exception {
        String policy = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssSamplerPolicy.java"
        );
        String materialSampler = section(
            policy,
            "public static synchronized GpuSampler materialSampler(",
            "public static synchronized void deviceConnected("
        );

        int gate = materialSampler.indexOf(
            "FeatureId.MATERIAL_SAMPLER_CACHE"
        );
        int original = materialSampler.indexOf(
            "return original;",
            gate
        );
        int bias = materialSampler.indexOf(
            "DlssRenderer.currentLodBias()",
            original
        );
        assertTrue(gate >= 0);
        assertTrue(original > gate);
        assertTrue(bias > original);
        assertFalse(materialSampler.contains("createCache(device)"));
        assertTrue(
            materialSampler.substring(gate, original)
                .contains("disabled by process feature policy")
        );
        String activation = section(
            policy,
            "public static synchronized boolean activateGeneration(",
            "public static synchronized boolean deactivateGeneration("
        );
        int activationGate = activation.indexOf(
            "FeatureId.MATERIAL_SAMPLER_CACHE"
        );
        int switchGeneration = activation.indexOf(
            "cacheLifecycle.switchTo(",
            activationGate
        );
        int create = activation.indexOf(
            "createCache(device)",
            switchGeneration
        );
        assertTrue(activationGate >= 0);
        assertTrue(switchGeneration > activationGate);
        assertTrue(create > switchGeneration);
        assertTrue(
            activation.substring(activationGate, switchGeneration)
                .contains("cacheLifecycle.deactivate(PRODUCTION_CLOSER)")
        );
    }

    @Test
    void disabledPhysicalTelemetryPrecedesVulkanProbeConstruction()
        throws Exception {
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );
        String connected = section(
            engine,
            "public synchronized void vulkanDeviceConnected(",
            "private void detectDevice()"
        );

        int gate = connected.indexOf(
            "FeatureId.PHYSICAL_MEMORY"
        );
        int disabledReturn = connected.indexOf("return;", gate);
        int runtimeInfo = connected.indexOf(
            "VulkanRuntimeInfo runtimeInfo",
            disabledReturn
        );
        int probe = connected.indexOf(
            "new VulkanMemoryBudgetProbe(device)",
            runtimeInfo
        );

        assertTrue(gate >= 0);
        assertTrue(disabledReturn > gate);
        assertTrue(runtimeInfo > disabledReturn);
        assertTrue(probe > runtimeInfo);
    }

    @Test
    void outlineGateUsesOneSubmissionAndFreshLegacyPoseWithoutScratch()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "GameRendererMixin.java"
        );
        String outline = section(
            mixin,
            "private void nvidiaDlss$renderNativeBlockOutline()",
            "private static PoseStack "
                + "nvidiaDlss$createFreshBlockOutlinePose()"
        );
        String fresh = section(
            mixin,
            "private static PoseStack "
                + "nvidiaDlss$createFreshBlockOutlinePose()",
            "@Inject(method = {\"resetData\", \"resize\"}, at = @At(\"HEAD\"))"
        );

        int gate = outline.indexOf(
            "FeatureId.OUTLINE_POSE_REUSE"
        );
        int scratchCreation = outline.indexOf(
            ".createForCurrentThread()",
            gate
        );
        int legacyBranch = outline.indexOf("} else {", scratchCreation);
        int freshCall = outline.indexOf(
            "nvidiaDlss$createFreshBlockOutlinePose();",
            legacyBranch
        );
        int submit = outline.indexOf(
            ".nvidiaDlss$submitBlockOutline("
        );
        int completed = outline.indexOf(
            "submissionCompleted = true;",
            submit
        );
        int release = outline.indexOf(
            "if (scratch != null)",
            completed
        );
        int endUse = outline.indexOf(
            "scratch.endUse(",
            release
        );

        assertTrue(gate >= 0);
        assertTrue(scratchCreation > gate);
        assertTrue(legacyBranch > scratchCreation);
        assertTrue(freshCall > legacyBranch);
        assertTrue(submit > freshCall);
        assertTrue(completed > submit);
        assertTrue(release > completed);
        assertTrue(endUse > release);
        assertEquals(
            1,
            occurrences(
                outline,
                ".nvidiaDlss$submitBlockOutline("
            )
        );
        assertFalse(outline.contains("new PoseStack("));
        assertTrue(fresh.contains("return new PoseStack();"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(
            endMarker,
            start + startMarker.length()
        );
        assertTrue(start >= 0, "missing marker: " + startMarker);
        assertTrue(end > start, "missing marker: " + endMarker);
        return source.substring(start, end);
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(
            System.getProperty("blockframe.projectDir")
        );
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
