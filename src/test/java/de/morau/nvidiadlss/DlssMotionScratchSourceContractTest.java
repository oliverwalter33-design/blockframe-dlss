package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssMotionScratchSourceContractTest {
    @Test
    void normalMotionCollectorUsesTheFixedBudgetedTransportBatch()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        int batchStart = renderer.indexOf(
            "private static int collectMotionObjects("
        );
        int legacyStart = renderer.indexOf(
            "private static LegacyMotionCollection "
                + "collectLegacyMotionObjects("
        );

        assertTrue(batchStart >= 0);
        assertTrue(legacyStart > batchStart);
        String batchCollector = renderer.substring(batchStart, legacyStart);
        assertTrue(
            renderer.contains(
                "batch = MotionObjectBatch.tryCreate("
            )
        );
        assertTrue(
            renderer.contains(
                "history = EntityMotionHistory.tryCreate("
            )
        );
        assertTrue(
            renderer.contains(
                "if (motionObjectBatchCreationAttempted)"
            )
        );
        assertTrue(batchCollector.contains("MotionObjectBatch result"));
        assertTrue(batchCollector.contains("result.add("));
        assertTrue(batchCollector.contains("entity.getBoundingBox()"));
        assertFalse(batchCollector.contains("new ArrayList"));
        assertFalse(
            batchCollector.contains(
                "new MotionVectorGenerator.MotionObject"
            )
        );
        assertFalse(batchCollector.contains(".move("));
        assertFalse(batchCollector.contains("new HashMap"));
        assertFalse(batchCollector.contains("new EntityFrame"));
        assertFalse(batchCollector.contains("Integer.valueOf"));
        assertTrue(
            batchCollector.contains("history.findPrevious(entityId)")
        );
        assertTrue(
            batchCollector.contains("history.putCurrent(")
        );
    }

    @Test
    void batchDispatchKeepsTheLegacyFallbackAndPackedWriter()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java"
        );

        assertTrue(
            renderer.contains(
                "collectLegacyMotionObjects("
            )
        );
        assertTrue(
            renderer.contains(
                "motionObjectFallbackFrames++"
            )
        );
        assertTrue(motion.contains("MotionObjectBatch objects"));
        assertTrue(motion.contains("List<MotionObject> objects"));
        assertTrue(motion.contains("batch.writeObject(i, bytes)"));
        assertTrue(motion.contains("putObject(bytes, legacyObjects.get(i))"));
    }

    @Test
    void finalClientCloseReleasesScratchBeforeTheBudgetManager()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String lifecycle = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "MinecraftLifecycleMixin.java"
        );

        assertTrue(renderer.contains("public static void closeClientResources()"));
        assertTrue(
            renderer.contains(
                "public static boolean closeClientResourcesAndReport()"
            )
        );
        assertTrue(renderer.contains("boolean cleanupSucceeded = true;"));
        assertTrue(
            renderer.contains("cleanupSucceeded &= runCloseStage(")
        );
        assertTrue(renderer.contains("return cleanupSucceeded;"));
        int scratchClose = lifecycle.indexOf(
            "DlssRenderer.closeClientResourcesAndReport();"
        );
        int runtimeClose = lifecycle.indexOf(
            "BlockframeRuntime.clientCloseReturned("
        );
        assertTrue(scratchClose >= 0);
        assertTrue(runtimeClose > scratchClose);
        assertTrue(
            lifecycle.substring(scratchClose, runtimeClose)
                .contains("catch (Throwable error)")
        );
        assertTrue(lifecycle.contains("original.call();"));
        assertTrue(lifecycle.contains("} finally {"));
    }

    @Test
    void failedCreationRollbackRetainsAnyUnclosedScratchOwner()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );

        assertTrue(
            renderer.contains(
                "entityMotionHistory = historyClosed ? null : history;"
            )
        );
        assertTrue(
            renderer.contains(
                "motionObjectBatch = batchClosed ? null : batch;"
            )
        );
        assertTrue(renderer.contains("motionScratchDisabled = true;"));
        assertTrue(
            renderer.contains(
                "boolean historyClosed = CreationRollback.close("
            )
        );
        assertTrue(
            renderer.contains(
                "boolean batchClosed = CreationRollback.close("
            )
        );
    }

    @Test
    void frameRingIndexRemainsBoundedWithoutIntegerWraparound()
        throws Exception {
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java"
        );

        assertFalse(
            motion.contains("ringIndex++ % FRAME_RING_SIZE")
        );
        assertTrue(
            motion.contains(
                "this.ringIndex = nextFrameRingIndex(slot);"
            )
        );
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
