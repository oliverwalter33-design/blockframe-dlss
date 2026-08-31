package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlineLocalResourceTagSourceContractTest {
    @Test
    void evaluateUsesLocalUntilEvaluateTagsWithoutVolatileClones()
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

        assertFalse(bridge.contains("gSetTagForFrame"));
        assertFalse(bridge.contains("slSetTagForFrame"));
        assertFalse(bridge.contains("eOnlyValidNow"));
        assertEquals(
            13,
            occurrences(evaluation, "ResourceLifecycle::eValidUntilEvaluate")
        );
        assertTrue(
            bridge.contains(
                "sl::PreferenceFlags::eUseFrameBasedResourceTagging"
            )
        );

        int dlssLocalInputs = evaluation.indexOf(
            "const sl::BaseStructure* inputsWithTransparency[]"
        );
        int constants = evaluation.indexOf(
            "gSetConstants(constants, *token, viewport)",
            dlssLocalInputs
        );
        int dlssEvaluate = evaluation.indexOf(
            "gEvaluateFeature(sl::kFeatureDLSS",
            constants
        );
        int nisLocalInputs = evaluation.indexOf(
            "const sl::BaseStructure* nisInputs[]",
            dlssEvaluate
        );
        int nisEvaluate = evaluation.indexOf(
            "gEvaluateFeature(sl::kFeatureNIS",
            nisLocalInputs
        );
        String order = "dlssLocalInputs=" + dlssLocalInputs
            + " constants=" + constants
            + " dlssEvaluate=" + dlssEvaluate
            + " nisLocalInputs=" + nisLocalInputs
            + " nisEvaluate=" + nisEvaluate;

        assertTrue(dlssLocalInputs >= 0, order);
        assertTrue(constants > dlssLocalInputs, order);
        assertTrue(dlssEvaluate > constants, order);
        assertTrue(nisLocalInputs > dlssEvaluate, order);
        assertTrue(nisEvaluate > nisLocalInputs, order);
        assertTrue(evaluation.contains("? inputsWithTransparency"));
        assertTrue(evaluation.contains(": inputsWithoutTransparency"));
        assertTrue(
            evaluation.contains(
                "const uint32_t dlssInputCount = "
                    + "includeTransparencyHint ? 7u : 6u;"
            )
        );
        assertTrue(
            section(
                evaluation,
                "gEvaluateFeature(sl::kFeatureDLSS",
                "const bool dlssSucceeded"
            ).contains("dlssInputs,\n        dlssInputCount")
        );
        assertTrue(
            section(
                evaluation,
                "gEvaluateFeature(sl::kFeatureNIS",
                "const bool nisSucceeded"
            ).contains("nisInputs,\n            3")
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
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
