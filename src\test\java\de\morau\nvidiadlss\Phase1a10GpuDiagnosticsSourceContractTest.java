package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a10GpuDiagnosticsSourceContractTest {
    @Test
    void motionAndEvaluateUseTheSameStaticIdentitiesAcrossOwners()
        throws Exception {
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String identity = source(
            "src/main/java/de/morau/blockframe/core/diagnostics/"
                + "GpuPassIdentity.java"
        );

        assertTrue(
            identity.contains("\"BlockFrame / Motion Compute\"")
        );
        assertTrue(
            identity.contains("\"BlockFrame / DLSS Evaluate\"")
        );
        assertTrue(
            identity.contains("\"BlockFrame / Graphics Submit\"")
        );
        assertTrue(
            identity.contains(
                "GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE"
            )
        );
        assertTrue(
            identity.contains(
                "GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE"
            )
        );
        assertTrue(
            identity.contains(
                "GpuSubmissionBreadcrumbs.PASS_GRAPHICS_SUBMIT"
            )
        );

        String dispatch = section(
            motion,
            "private void dispatchInternal(",
            "static int nextFrameRingIndex("
        );
        int motionBegin = dispatch.indexOf(
            "GpuPassDiagnostics.beginDebugGroup("
        );
        int motionWork = dispatch.indexOf("VK12.vkCmdDispatch(");
        int motionEnd = dispatch.indexOf(
            "GpuPassDiagnostics.endDebugGroup("
        );
        int motionBreadcrumb = dispatch.indexOf(
            "BlockframeRuntime.recordMotionComputePass();"
        );
        assertTrue(
            dispatch.contains("GpuPassIdentity.MOTION_COMPUTE")
        );
        assertTrue(motionBegin >= 0);
        assertTrue(motionWork > motionBegin);
        assertTrue(motionEnd > motionWork);
        assertTrue(motionBreadcrumb > motionEnd);
        assertFalse(motion.contains("DEBUG_GROUP_LABEL"));

        String finish = section(
            renderer,
            "public static RenderTarget finishWorldFrame(",
            "private static void prepareNativeOutlineDepthSafely()"
        );
        int motionGpuBegin = finish.indexOf(
            "GpuPassDiagnostics.beginGpuTracyZone("
        );
        int motionDispatch = finish.indexOf(
            "motionGenerator.dispatch(",
            motionGpuBegin
        );
        int motionGpuEnd = finish.indexOf(
            "GpuPassDiagnostics.endGpuTracyZone(",
            motionDispatch
        );
        assertTrue(
            finish.indexOf(
                "GpuPassIdentity.MOTION_COMPUTE",
                motionGpuBegin
            ) > motionGpuBegin
        );
        assertTrue(motionDispatch > motionGpuBegin);
        assertTrue(motionGpuEnd > motionDispatch);

        int evaluateDebugBegin = finish.indexOf(
            "GpuPassDiagnostics.beginDebugGroup(",
            motionGpuEnd
        );
        int evaluate = finish.indexOf(
            "NativeStreamline.evaluate(",
            evaluateDebugBegin
        );
        int evaluateDebugEnd = finish.indexOf(
            "GpuPassDiagnostics.endDebugGroup(",
            evaluate
        );
        int evaluateBreadcrumb = finish.indexOf(
            "BlockframeRuntime.recordDlssEvaluationPass();",
            evaluateDebugEnd
        );
        assertTrue(
            finish.indexOf(
                "GpuPassIdentity.DLSS_EVALUATE",
                motionGpuEnd
            ) < evaluate
        );
        assertTrue(evaluate > evaluateDebugBegin);
        assertTrue(evaluateDebugEnd > evaluate);
        assertTrue(evaluateBreadcrumb > evaluateDebugEnd);
    }

    @Test
    void tracyGpuZonesReuseMinecraftsExistingProfiler()
        throws Exception {
        String accessor = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "CommandEncoderAccessor.java"
        );
        String diagnostics = source(
            "src/main/java/de/morau/blockframe/core/diagnostics/"
                + "GpuPassDiagnostics.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );

        assertTrue(
            accessor.contains(
                "@Accessor(\"profiler\")"
            )
        );
        assertTrue(
            accessor.contains(
                "TracyGpuProfiler blockframe$tracyGpuProfiler()"
            )
        );
        assertTrue(diagnostics.contains("profiler.pushZone("));
        assertTrue(diagnostics.contains("profiler.popZone("));
        assertTrue(
            renderer.contains(
                "Optional Tracy cannot select the spatial safety fallback."
            )
        );
        assertFalse(diagnostics.contains("createTimestampQueryPool("));
        assertFalse(diagnostics.contains("BudgetedNativeArena"));
        assertFalse(diagnostics.contains("new Thread("));
        assertFalse(diagnostics.contains("Executor"));
    }

    @Test
    void graphicsSubmitTracesOnlyTheRealQueueSubmissionBoundary()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanCommandEncoderDiagnosticsMixin.java"
        );
        String trace = section(
            mixin,
            "private void blockframe$traceGraphicsSubmit(",
            "@Inject("
        );

        assertTrue(
            mixin.contains(
                "VulkanQueue$Submission;close()V"
            )
        );
        assertTrue(
            trace.contains("GpuPassIdentity.GRAPHICS_SUBMIT")
        );
        assertTrue(trace.contains("original.call(submission);"));
        assertTrue(
            trace.contains(
                "GpuPassDiagnostics.closeCpuTracyZone(zone);"
            )
        );
        assertFalse(trace.contains("beginGpuTracyZone"));
        assertFalse(trace.contains("beginDebugGroup"));

        int submitClose = mixin.indexOf(
            "VulkanQueue$Submission;close()V",
            mixin.indexOf(
                "private void blockframe$recordSubmit("
            ) - 300
        );
        int submitRecord = mixin.indexOf(
            "BlockframeRuntime.recordVulkanSubmit("
        );
        assertTrue(submitClose >= 0);
        assertTrue(submitRecord > submitClose);
        assertTrue(
            mixin.contains("shift = At.Shift.AFTER")
        );
    }

    @Test
    void currentMissingObjectsReceiveFailOpenNames()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String timer = source(
            "src/main/java/de/morau/blockframe/profiler/"
                + "VulkanGpuFrameTimer.java"
        );
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanDeviceDiagnosticsMixin.java"
        );
        String diagnostics = source(
            "src/main/java/de/morau/blockframe/core/diagnostics/"
                + "GpuPassDiagnostics.java"
        );
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );

        assertTrue(
            renderer.contains("labelLowResolutionTarget(")
        );
        String resizeRollback = section(
            renderer,
            "} else if (resizedTarget && lowTarget != null) {",
            "if (retainReplacementLease) {"
        );
        assertTrue(
            resizeRollback.contains(
                "labelLowResolutionTarget(lowTarget);"
            )
        );
        assertTrue(
            renderer.contains(
                "LOW_RESOLUTION_COLOR_IMAGE"
            )
        );
        assertTrue(
            renderer.contains(
                "LOW_RESOLUTION_COLOR_VIEW"
            )
        );
        assertTrue(
            renderer.contains(
                "LOW_RESOLUTION_DEPTH_IMAGE"
            )
        );
        assertTrue(
            renderer.contains(
                "LOW_RESOLUTION_DEPTH_VIEW"
            )
        );
        assertTrue(
            timer.contains(
                "labelFrameTimestampQueryPool("
            )
        );
        assertTrue(
            deviceMixin.contains(
                "GpuPassDiagnostics.labelBorrowedQueues(device);"
            )
        );
        assertTrue(
            diagnostics.contains("GRAPHICS_COMPUTE_QUEUE")
        );
        assertTrue(
            motion.contains(
                "GpuPassDiagnostics.setObjectName("
            )
        );
        assertTrue(
            motion.contains(
                "Optional names never roll back a published motion pipeline."
            )
        );
    }

    @Test
    void frameCpuAndGpuTimerNamesAreCanonicalAndHonest()
        throws Exception {
        String gameRenderer = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "GameRendererDiagnosticsMixin.java"
        );
        String timer = source(
            "src/main/java/de/morau/blockframe/profiler/"
                + "VulkanGpuFrameTimer.java"
        );
        String diagnostics = source(
            "src/main/java/de/morau/blockframe/core/diagnostics/"
                + "GpuPassDiagnostics.java"
        );

        String frameBegin = section(
            gameRenderer,
            "private void blockframe$measureFrame(",
            "@Inject("
        );
        assertTrue(
            frameBegin.contains(
                "GpuPassDiagnostics.beginCpuTracyZone("
            )
        );
        assertTrue(frameBegin.contains("GpuPassIdentity.FRAME"));
        assertTrue(
            diagnostics.contains(
                "TracyClient.beginZone(identity.label(), false)"
            )
        );
        assertTrue(
            timer.contains(
                "GpuPassIdentity.FRAME.label()"
            )
        );
        assertFalse(timer.contains("MOTION_COMPUTE"));
        assertFalse(timer.contains("DLSS_EVALUATE"));
        assertFalse(timer.contains("GRAPHICS_SUBMIT"));
    }

    @Test
    void phaseDoesNotIntroduceARawOrParallelVulkanDiagnosticOwner()
        throws Exception {
        Path root = projectRoot().resolve("src/main/java");
        StringBuilder all = new StringBuilder();
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        all.append(
                            Files.readString(
                                path,
                                StandardCharsets.UTF_8
                            )
                        );
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                });
        }
        String source = all.toString();
        assertFalse(
            source.contains("vkQueueBeginDebugUtilsLabelEXT")
        );
        assertFalse(
            source.contains("vkQueueEndDebugUtilsLabelEXT")
        );
        assertFalse(
            source.contains("VkDebugUtilsMessengerCallbackEXT")
        );
    }

    private static String source(String relative) throws IOException {
        return Files.readString(
            projectRoot().resolve(relative),
            StandardCharsets.UTF_8
        );
    }

    private static String section(
        String source,
        String start,
        String end
    ) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin);
        assertTrue(begin >= 0, "missing section start " + start);
        assertTrue(finish > begin, "missing section end " + end);
        return source.substring(begin, finish);
    }

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
    }
}
