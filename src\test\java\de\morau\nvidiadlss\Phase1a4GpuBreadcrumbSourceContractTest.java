package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a4GpuBreadcrumbSourceContractTest {
    @Test
    void submitIsRecordedOnlyAfterTheRealSubmissionCloses()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanCommandEncoderDiagnosticsMixin.java"
        );
        String submitHook = annotationBefore(
            mixin,
            "private void blockframe$recordSubmit("
        );

        assertTrue(submitHook.contains("method = \"submit\""));
        assertTrue(
            submitHook.contains(
                "Lcom/mojang/blaze3d/vulkan/"
                    + "VulkanQueue$Submission;close()V"
            )
        );
        assertTrue(submitHook.contains("shift = At.Shift.AFTER"));
        assertFalse(submitHook.contains("@At(\"HEAD\")"));
        assertTrue(
            mixin.contains(
                "BlockframeRuntime.recordVulkanSubmit("
            )
        );
    }

    @Test
    void completionIsObservedOnlyAtSubmitReturn() throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanCommandEncoderDiagnosticsMixin.java"
        );
        String completionHook = annotationBefore(
            mixin,
            "private void blockframe$recordCompletion("
        );

        assertTrue(completionHook.contains("method = \"submit\""));
        assertTrue(completionHook.contains("@At(\"RETURN\")"));
        assertFalse(completionHook.contains("@At(\"HEAD\")"));
        assertTrue(
            mixin.contains(
                "BlockframeRuntime.recordVulkanCompletion("
            )
        );
        assertTrue(mixin.contains("this.completedSubmitIndex"));
    }

    @Test
    void deviceDestroyAbandonsUnprovenWorkInsteadOfCompletingIt()
        throws Exception {
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanDeviceDiagnosticsMixin.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );
        String breadcrumbs = source(
            "src/main/java/de/morau/blockframe/core/diagnostics/"
                + "GpuSubmissionBreadcrumbs.java"
        );

        assertTrue(
            deviceMixin.contains(
                "Lcom/mojang/blaze3d/vulkan/"
                    + "VulkanCommandEncoder;destroy()V"
            )
        );
        assertTrue(deviceMixin.contains("shift = At.Shift.AFTER"));
        assertTrue(deviceMixin.contains("BlockframeRuntime"));
        assertTrue(
            deviceMixin.contains(
                "vulkanEncoderDestroyedWithoutCompletionProof();"
            )
        );
        assertFalse(deviceMixin.contains("vulkanEncoderDrained"));
        assertFalse(runtime.contains("vulkanEncoderDrained"));
        String runtimeDestroy = section(
            runtime,
            "public static void "
                + "vulkanEncoderDestroyedWithoutCompletionProof()",
            "public static void beginFrame()"
        );
        assertTrue(
            runtimeDestroy.contains(
                "engine().vulkanEncoderDestroyedWithoutCompletionProof();"
            )
        );
        assertFalse(runtimeDestroy.contains("recordVulkanCompletion"));
        assertTrue(
            engine.contains(
                "breadcrumbs.encoderDestroyedWithoutCompletionProof();"
            )
        );
        assertFalse(engine.contains("completeSubmittedAfterDrain"));
        assertFalse(breadcrumbs.contains("COMPLETED_AFTER_DRAIN"));
        assertTrue(
            breadcrumbs.contains(
                "encoderDestroyedWithoutCompletionProof()"
            )
        );
        String destroy = section(
            breadcrumbs,
            "encoderDestroyedWithoutCompletionProof()",
            "public Snapshot snapshot()"
        );
        assertTrue(destroy.contains("abandonPendingEntries();"));
        assertFalse(destroy.contains("STATE_COMPLETED"));
        assertFalse(destroy.contains("completeEntry("));
        String abandon = section(
            breadcrumbs,
            "private int abandonPendingEntries()",
            "private void resetDeviceLocalIndices()"
        );
        assertTrue(abandon.contains("STATE_ABANDONED"));
        assertFalse(abandon.contains("STATE_COMPLETED"));
        assertFalse(abandon.contains("completeEntry("));
    }

    @Test
    void motionAndDlssPassesAreRealProductionConsumers()
        throws Exception {
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssRenderer.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );

        int dispatch = motion.indexOf("vkCmdDispatch(");
        int motionBreadcrumb = motion.indexOf(
            "BlockframeRuntime.recordMotionComputePass();",
            dispatch
        );
        assertTrue(dispatch >= 0);
        assertTrue(motionBreadcrumb > dispatch);

        int evaluate = renderer.indexOf(
            "NativeStreamline.evaluate("
        );
        int dlssBreadcrumb = renderer.indexOf(
            "BlockframeRuntime.recordDlssEvaluationPass();",
            evaluate
        );
        assertTrue(evaluate >= 0);
        assertTrue(dlssBreadcrumb > evaluate);

        String runtimeMotion = section(
            runtime,
            "public static void recordMotionComputePass()",
            "public static void recordDlssEvaluationPass()"
        );
        String runtimeDlss = section(
            runtime,
            "public static void recordDlssEvaluationPass()",
            "public static void recordVulkanSubmit("
        );
        assertTrue(
            runtimeMotion.contains(
                "current.engine.recordMotionComputePass();"
            )
        );
        assertTrue(
            runtimeDlss.contains(
                "current.engine.recordDlssEvaluationPass();"
            )
        );
        assertTrue(
            runtimeMotion.indexOf(
                "current.engine.recordMotionComputePass();"
            ) < runtimeMotion.indexOf(
                "current.observeBreadcrumbAfterRecord();"
            )
        );
        assertTrue(
            runtimeDlss.indexOf(
                "current.engine.recordDlssEvaluationPass();"
            ) < runtimeDlss.indexOf(
                "current.observeBreadcrumbAfterRecord();"
            )
        );
        assertTrue(
            engine.contains(
                "GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE"
            )
        );
        assertTrue(
            engine.contains(
                "GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE"
            )
        );
    }

    @Test
    void commandEncoderDiagnosticsMixinIsConfiguredExactlyOnce()
        throws Exception {
        String config = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        String name = "\"VulkanCommandEncoderDiagnosticsMixin\"";

        assertEquals(
            config.indexOf(name),
            config.lastIndexOf(name)
        );
        assertTrue(config.contains(name));
    }

    private static String annotationBefore(
        String source,
        String methodMarker
    ) {
        int method = source.indexOf(methodMarker);
        assertTrue(method >= 0, "missing marker: " + methodMarker);
        int annotation = source.lastIndexOf("@Inject", method);
        assertTrue(annotation >= 0, "missing @Inject for " + methodMarker);
        return source.substring(annotation, method);
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
