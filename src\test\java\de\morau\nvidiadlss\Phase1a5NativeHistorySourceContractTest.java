package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a5NativeHistorySourceContractTest {
    @Test
    void productionFactoryIsHeapDefaultAndNativeIsExplicitExperimental()
        throws Exception {
        String history = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "EntityMotionHistory.java"
        );

        int defaultPreference = history.indexOf(
            "BackendPreference.HEAP"
        );
        int heapGate = history.indexOf(
            "if (preference == BackendPreference.HEAP)"
        );
        int heapDefault = history.indexOf(
            "return tryCreateHeap(budgets, layout);",
            heapGate
        );
        int nativeAttempt = history.indexOf(
            "EntityMotionHistory nativeHistory = tryCreateNative(",
            heapDefault
        );
        int nativePublication = history.indexOf(
            "if (nativeHistory != null)",
            nativeAttempt
        );
        assertTrue(defaultPreference >= 0);
        assertTrue(heapGate > defaultPreference);
        assertTrue(heapDefault > heapGate);
        assertTrue(nativeAttempt > heapDefault);
        assertTrue(nativePublication > nativeAttempt);
        assertTrue(nativeAttempt >= 0);
        assertTrue(
            history.contains(
                "NATIVE_EXPERIMENTAL(\"native-experimental\")"
            )
        );
        assertTrue(
            history.contains(
                "static EntityMotionHistory tryCreateExperimentalNative("
            )
        );
        assertTrue(history.contains("BudgetedNativeArena.tryCreate("));
        assertTrue(history.contains("MemoryCategory.ENTITIES"));
        assertTrue(history.contains("new NativeStorage("));
        assertTrue(history.contains("new HeapStorage("));
        assertEquals(
            1,
            occurrences(history, "storageClaimer.claim(")
        );
        String frameOperations = section(
            history,
            "public void beginFrame()",
            "public enum StorageKind"
        );
        assertFalse(frameOperations.contains(".claim("));
        assertFalse(history.contains("MemoryUtil"));
        assertFalse(history.contains("Unsafe"));
    }

    @Test
    void rendererIsTheRealConsumerAndOpenGlCannotRequestTheHistory()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String config = source(
            "src/main/java/de/morau/nvidiadlss/DlssConfig.java"
        );
        String beginFrame = section(
            renderer,
            "public static RenderTarget beginFrame(",
            "public static Matrix4f applyWorldJitter("
        );
        String finishFrame = section(
            renderer,
            "public static RenderTarget finishWorldFrame(",
            "private static void prepareNativeOutlineDepthSafely()"
        );
        String motionOwner = section(
            renderer,
            "private static MotionObjectBatch motionObjectBatchOrNull()",
            "private static void clearMotionObjectHistory()"
        );

        assertTrue(
            beginFrame.contains(
                "highTarget.getColorTexture() instanceof VulkanGpuTexture"
            )
        );
        assertTrue(
            beginFrame.indexOf(
                "highTarget.getColorTexture() instanceof VulkanGpuTexture"
            )
                < beginFrame.indexOf("active = true;")
        );
        assertTrue(
            finishFrame.indexOf(
                "if (!active) return originalTarget;"
            )
                < finishFrame.indexOf("motionObjectBatchOrNull()")
        );
        assertTrue(
            motionOwner.contains("EntityMotionHistory.tryCreate(")
        );
        assertTrue(
            motionOwner.contains("DlssConfig.entityHistoryBackend()")
        );
        assertTrue(motionOwner.contains("\"Heap-Standard \""));
        assertTrue(
            motionOwner.contains("\"Native-Experimental \"")
        );
        assertTrue(
            motionOwner.contains(
                "history.storageKind()"
            )
        );
        assertTrue(
            renderer.contains("\"Entity-History: \"")
        );
        assertEquals(
            1,
            occurrences(renderer, "EntityMotionHistory.tryCreate(")
        );
        assertFalse(engine.contains("EntityMotionHistory"));
        assertFalse(runtime.contains("EntityMotionHistory"));
        assertTrue(
            config.contains(
                "EntityMotionHistory.BackendPreference.HEAP"
            )
        );
        assertTrue(
            config.contains("\"entityHistoryBackend\"")
        );
    }

    @Test
    void overflowRebuildsTheSameFrameThroughLegacyBeforeClosingScratch()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String finishFrame = section(
            renderer,
            "public static RenderTarget finishWorldFrame(",
            "private static void prepareNativeOutlineDepthSafely()"
        );
        String overflowClose = section(
            renderer,
            "private static void disableMotionScratchAfterHistoryOverflow()",
            "private static int collectMotionObjects("
        );

        int fixedCollection = finishFrame.indexOf(
            "collectMotionObjects("
        );
        int legacyRebuild = finishFrame.indexOf(
            "collectLegacyMotionObjects(",
            fixedCollection
        );
        int disableScratch = finishFrame.indexOf(
            "disableMotionScratchAfterHistoryOverflow();",
            legacyRebuild
        );
        assertTrue(fixedCollection >= 0);
        assertTrue(legacyRebuild > fixedCollection);
        assertTrue(disableScratch > legacyRebuild);
        assertTrue(
            finishFrame.substring(
                legacyRebuild,
                disableScratch
            ).contains("overflowHistory")
        );
        assertTrue(
            overflowClose.contains("motionScratchDisabled = true;")
        );
        assertTrue(
            overflowClose.contains(
                "\"Legacy-Fallback: fixed history overflow\""
            )
        );
        assertTrue(overflowClose.contains("history::close"));
        assertTrue(overflowClose.contains("batch::close"));
    }

    @Test
    void runtimeStorageFaultFallsBackInTheSameFrameAndQuarantinesScratch()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String finishFrame = section(
            renderer,
            "public static RenderTarget finishWorldFrame(",
            "private static void prepareNativeOutlineDepthSafely()"
        );
        String faultClose = section(
            renderer,
            "private static void disableMotionScratchAfterHistoryFailure(",
            "private static int collectMotionObjects("
        );

        int guardedCollection = finishFrame.indexOf(
            "fixedMovingObjectCount = collectMotionObjects("
        );
        int faultDisable = finishFrame.indexOf(
            "disableMotionScratchAfterHistoryFailure(error);",
            guardedCollection
        );
        int forceLegacy = finishFrame.indexOf(
            "batch = null;",
            faultDisable
        );
        int legacyRebuild = finishFrame.indexOf(
            "collectLegacyMotionObjects(",
            forceLegacy
        );
        assertTrue(guardedCollection >= 0);
        assertTrue(faultDisable > guardedCollection);
        assertTrue(forceLegacy > faultDisable);
        assertTrue(legacyRebuild > forceLegacy);
        assertTrue(
            finishFrame.substring(
                faultDisable,
                legacyRebuild
            ).contains("reset = true;")
        );
        assertTrue(
            faultClose.contains("motionScratchDisabled = true;")
        );
        assertTrue(faultClose.contains("history::close"));
        assertTrue(faultClose.contains("batch::close"));
    }

    @Test
    void finalClientCloseRetriesPublishedAndConstructionOwners()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String close = section(
            renderer,
            "public static void closeClientResources()",
            "private static void closeEntityMotionHistoryOwner("
        );
        String creationCatch = section(
            renderer,
            "private static MotionObjectBatch motionObjectBatchOrNull()",
            "private static void clearMotionObjectHistory()"
        );

        assertEquals(
            2,
            occurrences(
                close,
                "closeEntityMotionHistoryOwner("
            )
        );
        assertEquals(
            2,
            occurrences(
                close,
                "EntityMotionHistory::retryPendingCleanup"
            )
        );
        assertTrue(
            creationCatch.contains(
                "EntityMotionHistory.hasPendingCleanup()"
            )
        );
        assertTrue(
            creationCatch.contains(
                "\"Legacy-Fallback: creation cleanup retained\""
            )
        );
    }

    @Test
    void sliceDoesNotAttachTheDormantStagingRingOrASecondScheduler()
        throws Exception {
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String history = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "EntityMotionHistory.java"
        );

        for (String integrationSource :
            new String[] {engine, runtime, renderer, history}) {
            assertFalse(
                integrationSource.contains("BlockframeStagingBuffer")
            );
            assertFalse(
                integrationSource.contains("TripleUploadRingState")
            );
            assertFalse(
                integrationSource.contains("FrameBudgetController")
            );
        }
        assertTrue(
            engine.contains(
                "eligible / NOT_ATTACHED (vanilla owner)"
            )
        );
        Path root = projectRoot();
        assertFalse(
            Files.exists(
                root.resolve(
                    "src/main/java/de/morau/blockframe/core/budget/"
                        + "FrameBudgetController.java"
                )
            )
        );
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
        return Files.readString(
            projectRoot().resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir")
        );
    }
}
