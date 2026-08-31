package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlineCommandBufferIsolationSourceContractTest {
    @Test
    void streamlineRunsInTwoOrderedIsolatedCommandBuffers()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String evaluation = section(
            renderer,
            "float sharpness = DlssConfig.effectiveSharpness(",
            "lastEvaluationCode = evaluationResult.dlssResult();"
        );

        int dlssAllocate = evaluation.indexOf(
            "dlssCommandBuffer ="
        );
        int dlssBegin = evaluation.indexOf(
            ".allocateAndBeginTransientCommandBuffer()",
            dlssAllocate
        );
        int dlssPreBarrier = evaluation.indexOf(
            "VulkanCommandEncoder.memoryBarrier(",
            dlssBegin
        );
        int nisRequested = evaluation.indexOf(
            "if (sharpness > 0.0F)",
            dlssPreBarrier
        );
        int nisAllocate = evaluation.indexOf(
            "nisCommandBuffer =",
            nisRequested
        );
        int nisBegin = evaluation.indexOf(
            ".allocateAndBeginTransientCommandBuffer()",
            nisAllocate
        );
        int debugBegin = evaluation.indexOf(
            "dlssDebugGroup =",
            nisBegin
        );
        int evaluate = evaluation.indexOf(
            "NativeStreamline.evaluate(",
            debugBegin
        );
        int dlssAddress = evaluation.indexOf(
            "dlssCommandBuffer.address()",
            evaluate
        );
        int nisAddress = evaluation.indexOf(
            "nisCommandBuffer.address()",
            dlssAddress
        );
        int dlssDebugEnd = evaluation.indexOf(
            "GpuPassDiagnostics.endDebugGroup(",
            nisAddress
        );
        int dlssPostBarrier = evaluation.indexOf(
            "VulkanCommandEncoder.memoryBarrier(",
            dlssDebugEnd
        );
        int dlssEnd = evaluation.indexOf(
            "VK12.vkEndCommandBuffer(",
            dlssPostBarrier
        );
        int dlssSuccessGuard = evaluation.indexOf(
            "if (evaluationResult.dlssSucceeded())",
            dlssEnd
        );
        int dlssExecute = evaluation.indexOf(
            "vulkanEncoder.execute(dlssCommandBuffer)",
            dlssSuccessGuard
        );
        int nisPostBarrier = evaluation.indexOf(
            "VulkanCommandEncoder.memoryBarrier(",
            dlssExecute
        );
        int nisEnd = evaluation.indexOf(
            "VK12.vkEndCommandBuffer(",
            nisPostBarrier
        );
        int nisSuccessGuard = evaluation.indexOf(
            "if (evaluationResult.nisSucceeded())",
            nisEnd
        );
        int nisExecute = evaluation.indexOf(
            "vulkanEncoder.execute(nisCommandBuffer)",
            nisSuccessGuard
        );
        String order = "dlssAllocate=" + dlssAllocate
            + " dlssBegin=" + dlssBegin
            + " dlssPreBarrier=" + dlssPreBarrier
            + " nisRequested=" + nisRequested
            + " nisAllocate=" + nisAllocate
            + " nisBegin=" + nisBegin
            + " debugBegin=" + debugBegin
            + " evaluate=" + evaluate
            + " dlssAddress=" + dlssAddress
            + " nisAddress=" + nisAddress
            + " dlssDebugEnd=" + dlssDebugEnd
            + " dlssPostBarrier=" + dlssPostBarrier
            + " dlssEnd=" + dlssEnd
            + " dlssSuccessGuard=" + dlssSuccessGuard
            + " dlssExecute=" + dlssExecute
            + " nisPostBarrier=" + nisPostBarrier
            + " nisEnd=" + nisEnd
            + " nisSuccessGuard=" + nisSuccessGuard
            + " nisExecute=" + nisExecute;

        assertTrue(dlssAllocate >= 0, order);
        assertTrue(dlssBegin > dlssAllocate, order);
        assertTrue(dlssPreBarrier > dlssBegin, order);
        assertTrue(nisRequested > dlssPreBarrier, order);
        assertTrue(nisAllocate > nisRequested, order);
        assertTrue(nisBegin > nisAllocate, order);
        assertTrue(debugBegin > nisBegin, order);
        assertTrue(evaluate > debugBegin, order);
        assertTrue(dlssAddress > evaluate, order);
        assertTrue(nisAddress > dlssAddress, order);
        assertTrue(dlssDebugEnd > nisAddress, order);
        assertTrue(dlssPostBarrier > dlssDebugEnd, order);
        assertTrue(dlssEnd > dlssPostBarrier, order);
        assertTrue(dlssSuccessGuard > dlssEnd, order);
        assertTrue(dlssExecute > dlssSuccessGuard, order);
        assertTrue(nisPostBarrier > dlssExecute, order);
        assertTrue(nisEnd > nisPostBarrier, order);
        assertTrue(nisSuccessGuard > nisEnd, order);
        assertTrue(nisExecute > nisSuccessGuard, order);
        assertTrue(evaluation.contains("dlssCommandBuffer != null"));
        assertTrue(evaluation.contains("nisCommandBuffer != null"));
        assertTrue(
            evaluation.contains(
                "&& !dlssCommandBufferEndAttempted"
            )
        );
        assertTrue(
            evaluation.contains(
                "&& !nisCommandBufferEndAttempted"
            )
        );
    }

    @Test
    void postStreamlineRawCommandsReacquireMojangsSuccessorBuffer()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String post = section(
            renderer,
            "GpuTexture finalWorld = nisCommandBufferSubmitted",
            "previousJitterX = jitterX;"
        );

        int copy = post.indexOf("encoder.copyTextureToTexture(finalWorld");
        int reacquire = post.indexOf(
            "VkCommandBuffer postStreamlineCommandBuffer"
        );
        int accessor = post.indexOf(
            ".nvidiaDlss$commandBuffer();",
            reacquire
        );
        int outline = post.indexOf(
            "prepareNativeOutlineDepthSafely(",
            accessor
        );
        String order = "copy=" + copy
            + " reacquire=" + reacquire
            + " accessor=" + accessor
            + " outline=" + outline;

        assertTrue(copy >= 0, order);
        assertTrue(reacquire > copy, order);
        assertTrue(accessor > reacquire, order);
        assertTrue(outline > accessor, order);
        assertTrue(
            post.substring(outline).contains(
                "postStreamlineCommandBuffer"
            )
        );
    }

    @Test
    void manualHookingStateTrackingRemainsDisabledOnlyInsideIsolation()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");

        assertTrue(
            bridge.contains("sl::PreferenceFlags::eUseManualHooking")
        );
        assertTrue(
            bridge.contains("sl::PreferenceFlags::eDisableCLStateTracking")
        );
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing start marker: " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "missing end marker: " + endMarker);
        return source.substring(start, end);
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
