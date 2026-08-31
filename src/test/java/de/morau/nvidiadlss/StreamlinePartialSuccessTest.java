package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.nvidiadlss.nativebridge.StreamlineEvaluationResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlinePartialSuccessTest {
    @Test
    void packedResultsKeepDlssAndNisOutcomesIndependent() {
        StreamlineEvaluationResult partial =
            StreamlineEvaluationResult.unpack(
                StreamlineEvaluationResult.pack(0, -1212)
            );

        assertEquals(0, partial.dlssResult());
        assertEquals(-1212, partial.nisResult());
        assertTrue(partial.dlssSucceeded());
        assertTrue(partial.nisRequested());
        assertFalse(partial.nisSucceeded());

        StreamlineEvaluationResult dlssFailure =
            StreamlineEvaluationResult.unpack(
                StreamlineEvaluationResult.pack(
                    -1203,
                    StreamlineEvaluationResult.NIS_NOT_REQUESTED
                )
            );

        assertEquals(-1203, dlssFailure.dlssResult());
        assertEquals(
            StreamlineEvaluationResult.NIS_NOT_REQUESTED,
            dlssFailure.nisResult()
        );
        assertFalse(dlssFailure.dlssSucceeded());
        assertFalse(dlssFailure.nisRequested());
        assertFalse(dlssFailure.nisSucceeded());
    }

    @Test
    void nativeEvaluationUsesOneTokenAndTwoFeatureBuffers()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String evaluation = section(
            bridge,
            "JNIEXPORT jlong JNICALL "
                + "Java_de_morau_nvidiadlss_nativebridge_"
                + "NativeStreamline_evaluate",
            "JNIEXPORT jint JNICALL "
                + "Java_de_morau_nvidiadlss_nativebridge_"
                + "NativeStreamline_resetViewport"
        );
        String dlssEvaluate = section(
            evaluation,
            "const sl::Result result = gEvaluateFeature(",
            "const bool dlssSucceeded"
        );
        String nisEvaluate = section(
            evaluation,
            "const sl::Result nisResult = gEvaluateFeature(",
            "const bool nisSucceeded"
        );

        assertTrue(
            evaluation.contains(
                "jlong dlssCommandBuffer, jlong nisCommandBuffer"
            )
        );
        assertEquals(1, occurrences(evaluation, "gGetNewFrameToken("));
        assertTrue(
            evaluation.contains("sl::CommandBuffer* dlssCmd =")
        );
        assertTrue(
            evaluation.contains(
                "static_cast<uintptr_t>(dlssCommandBuffer)"
            )
        );
        assertTrue(
            evaluation.contains("sl::CommandBuffer* nisCmd =")
        );
        assertTrue(
            evaluation.contains(
                "static_cast<uintptr_t>(nisCommandBuffer)"
            )
        );
        assertTrue(dlssEvaluate.contains("sl::kFeatureDLSS"));
        assertTrue(dlssEvaluate.contains("dlssCmd"));
        assertFalse(dlssEvaluate.contains("nisCmd"));
        assertTrue(nisEvaluate.contains("sl::kFeatureNIS"));
        assertTrue(nisEvaluate.contains("nisCmd"));
        assertFalse(nisEvaluate.contains("dlssCmd"));
        assertTrue(
            evaluation.contains(
                "return packEvaluationResults(0, -1212);"
            )
        );
        assertTrue(
            evaluation.contains(
                "return packEvaluationResults(\n"
                    + "                0,\n"
                    + "                static_cast<jint>(nisResult)"
            )
        );
    }

    @Test
    void rendererKeepsSuccessfulDlssWhenNisFails()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String nativeApi = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/"
                + "NativeStreamline.java"
        );
        String finish = section(
            renderer,
            "float sharpness = DlssConfig.effectiveSharpness(",
            "private static void prepareNativeOutlineDepthSafely()"
        );
        String sharpeningOutcome = section(
            renderer,
            "private static void recordSharpeningOutcome(",
            "private static String sharpeningResultDescription()"
        );

        assertTrue(
            nativeApi.contains("public static native long evaluate(")
        );
        assertTrue(
            nativeApi.contains(
                "long dlssCommandBuffer, long nisCommandBuffer"
            )
        );
        assertEquals(
            2,
            occurrences(
                finish,
                ".allocateAndBeginTransientCommandBuffer()"
            )
        );
        assertEquals(
            1,
            occurrences(finish, "NativeStreamline.evaluate(")
        );
        assertTrue(finish.contains("dlssCommandBuffer.address()"));
        assertTrue(finish.contains("nisCommandBuffer.address()"));
        assertTrue(
            finish.contains(
                "nisCommandBufferFailure != null\n"
                    + "                            ? 0L"
            )
        );

        int dlssGuard = finish.indexOf(
            "if (evaluationResult.dlssSucceeded())"
        );
        int dlssSubmit = finish.indexOf(
            "vulkanEncoder.execute(dlssCommandBuffer)",
            dlssGuard
        );
        int nisGuard = finish.indexOf(
            "if (evaluationResult.nisSucceeded())",
            dlssSubmit
        );
        int nisSubmit = finish.indexOf(
            "vulkanEncoder.execute(nisCommandBuffer)",
            nisGuard
        );
        int dlssFailureGuard = finish.indexOf(
            "if (!lastEvaluationActive)",
            nisSubmit
        );
        int outcome = finish.indexOf(
            "recordSharpeningOutcome(",
            dlssFailureGuard
        );
        int finalWorld = finish.indexOf(
            "GpuTexture finalWorld = nisCommandBufferSubmitted",
            outcome
        );
        String order = "dlssGuard=" + dlssGuard
            + " dlssSubmit=" + dlssSubmit
            + " nisGuard=" + nisGuard
            + " nisSubmit=" + nisSubmit
            + " dlssFailureGuard=" + dlssFailureGuard
            + " outcome=" + outcome
            + " finalWorld=" + finalWorld;

        assertTrue(dlssGuard >= 0, order);
        assertTrue(dlssSubmit > dlssGuard, order);
        assertTrue(nisGuard > dlssSubmit, order);
        assertTrue(nisSubmit > nisGuard, order);
        assertTrue(dlssFailureGuard > nisSubmit, order);
        assertTrue(outcome > dlssFailureGuard, order);
        assertTrue(finalWorld > outcome, order);
        assertTrue(
            finish.substring(finalWorld).contains(
                "? resources.sharpenTexture\n"
                    + "                : resources.outputTexture"
            )
        );
        assertFalse(finish.contains("if (result != 0)"));

        assertTrue(
            sharpeningOutcome.contains(
                "lastSharpeningActive = nisSubmitted;"
            )
        );
        assertTrue(
            sharpeningOutcome.contains(
                "erfolgreiche DLSS-Ausgabe wird beibehalten"
            )
        );
        assertFalse(sharpeningOutcome.contains("throw "));
        assertFalse(
            sharpeningOutcome.contains("DlssStatus.error(")
        );
    }

    private static int occurrences(String source, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing start marker: " + startMarker);
        int end = source.indexOf(
            endMarker,
            start + startMarker.length()
        );
        assertTrue(end > start, "missing end marker: " + endMarker);
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
