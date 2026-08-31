package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MotionObjectOverflowSourceContractTest {
    @Test
    void fixedCollectorCountsEveryMovingObjectBeforeEncodingCapacity()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String fixedCollector = section(
            renderer,
            "private static int collectMotionObjects(",
            "private static LegacyMotionCollection "
                + "collectLegacyMotionObjects("
        );

        int historyPublication = fixedCollector.indexOf(
            "history.putCurrent("
        );
        int movementClassification = fixedCollector.indexOf(
            "observedMovingObjects++;"
        );
        int capacityDecision = fixedCollector.indexOf(
            "motionObjectCapacityExceeded("
        );
        int packedPublication = fixedCollector.indexOf(
            "result.add("
        );
        assertTrue(historyPublication >= 0);
        assertTrue(movementClassification > historyPublication);
        assertTrue(capacityDecision > movementClassification);
        assertTrue(packedPublication > capacityDecision);
        assertTrue(
            fixedCollector.contains(
                "return MOTION_HISTORY_OVERFLOW;"
            )
        );
        assertTrue(
            fixedCollector.contains(
                "return observedMovingObjects;"
            )
        );
        assertFalse(
            fixedCollector.contains(
                "result.size() >= MotionVectorGenerator.MAX_OBJECTS"
            )
        );
    }

    @Test
    void bothTransportsRejectTheWholeFrameInsteadOfTruncating()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );
        String rejection = section(
            renderer,
            "private static boolean "
                + "rejectIncompleteMotionCoverage(",
            "private static int collectMotionObjects("
        );
        String legacyCollector = section(
            renderer,
            "private static LegacyMotionCollection "
                + "collectLegacyMotionObjects(",
            "private static float[] rowMajor("
        );

        assertTrue(rejection.contains("batch.clear();"));
        assertTrue(rejection.contains("legacyObjects"));
        assertTrue(rejection.contains(".clear();"));
        assertTrue(rejection.contains("requestReset("));
        assertTrue(
            renderer.contains(
                "reset = true;"
            )
        );
        assertTrue(
            legacyCollector.contains(
                "observedMovingObjects++;"
            )
        );
        assertTrue(
            legacyCollector.contains(
                "new LegacyMotionCollection("
            )
        );
        assertFalse(
            legacyCollector.contains(
                "result.size() >= MotionVectorGenerator.MAX_OBJECTS"
            )
        );
        assertTrue(
            motion.contains(
                "motion-object transport exceeds shader capacity"
            )
        );
        assertFalse(
            motion.contains(
                "Math.min(MAX_OBJECTS"
            )
        );
    }

    private static String section(
        String source,
        String start,
        String end
    ) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0, () -> "missing start marker: " + start);
        assertTrue(to > from, () -> "missing end marker: " + end);
        return source.substring(from, to);
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
