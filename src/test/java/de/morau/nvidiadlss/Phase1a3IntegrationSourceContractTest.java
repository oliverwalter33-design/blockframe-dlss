package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a3IntegrationSourceContractTest {
    @Test
    void timerScratchIsBudgetedLazyAndClosedBeforeItsManager()
        throws Exception {
        String timer = source(
            "src/main/java/de/morau/blockframe/profiler/"
                + "VulkanGpuFrameTimer.java"
        );
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );
        String configure = section(
            timer,
            "private boolean configure(",
            "private boolean resolve("
        );
        String close = section(
            engine,
            "public synchronized void close()",
            "public synchronized void disableForCompatibility("
        );

        assertTrue(timer.contains("BudgetedNativeArena"));
        assertTrue(
            timer.contains(
                "MemoryCategory.STAGING"
            )
        );
        assertFalse(timer.contains("MemoryUtil"));
        assertFalse(timer.contains("memAllocLong"));
        assertFalse(timer.contains("memFree"));
        assertTrue(
            configure.indexOf("ensureQueryResultsScratch()")
                < configure.indexOf("createTimestampQueryPool(")
        );
        assertTrue(
            engine.indexOf(
                "this.gpuFrameTimer.configurationReloaded();"
            ) >= 0
        );
        assertTrue(
            close.indexOf("this.gpuFrameTimer.close();")
                < close.indexOf("stagingPool.close();")
        );
        assertTrue(
            close.indexOf("stagingPool.close();")
                < close.indexOf(
                    "ReusableNativeBlockPool.retryPendingCleanup();"
                )
        );
        assertTrue(
            close.indexOf(
                "BudgetedNativeArena.retryPendingCleanup();"
            )
                < close.indexOf("this.memoryBudgets.closeAndReport()")
        );
    }

    @Test
    void shaderStagingIsScopedAndKeepsTheDirectFallback()
        throws Exception {
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );
        String constructor = section(
            motion,
            "public MotionVectorGenerator(VulkanDevice backend)",
            "public void dispatch("
        );
        String load = section(
            motion,
            "private static ShaderCodeOwner loadShader()",
            "private static ShaderCodeOwner tryLoadShaderFromPool("
        );
        String poolLoad = section(
            motion,
            "private static ShaderCodeOwner tryLoadShaderFromPool(",
            "static boolean readShaderIntoFixedBuffer("
        );
        String directLoad = section(
            motion,
            "private static ShaderCodeOwner loadShaderDirect(",
            "private abstract static class ShaderCodeOwner"
        );

        assertTrue(
            motion.contains(
                "BlockframeRuntime.nativeStagingPoolOrNull()"
            )
        );
        assertTrue(
            constructor.contains(
                "try (ShaderCodeOwner shaderCode = loadShader())"
            )
        );
        assertTrue(
            constructor.contains(
                "shaderCode.createShaderModule("
            )
        );
        assertTrue(load.contains("catch (RuntimeException error)"));
        assertTrue(load.contains("return loadShaderDirect(shaderResource);"));
        assertFalse(load.contains("catch (Error"));
        assertTrue(poolLoad.contains("pool.release(token);"));
        assertTrue(poolLoad.contains("} finally {"));
        assertFalse(poolLoad.contains("readAllBytes"));
        assertTrue(directLoad.contains("input.readAllBytes()"));
        assertTrue(directLoad.contains("MemoryUtil.memAlloc("));
        assertTrue(directLoad.contains("MemoryUtil.memFree(result);"));
    }

    @Test
    void objectScratchClosesBeforeTheRuntimeAndDeviceResetDoesNotCloseIt()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String lifecycle = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "MinecraftLifecycleMixin.java"
        );
        String deviceConnected = section(
            renderer,
            "public static void deviceConnected(VulkanDevice device)",
            "public static boolean prepareDeviceClose()"
        );

        assertTrue(
            deviceConnected.contains(
                "clearTransformScratchDeviceState();"
            )
        );
        assertFalse(
            deviceConnected.contains(
                "transformScratch.close();"
            )
        );
        int scratchClose = lifecycle.indexOf(
            "DlssRenderer.closeClientResourcesAndReport();"
        );
        int runtimeClose = lifecycle.indexOf(
            "BlockframeRuntime.clientCloseReturned("
        );
        assertTrue(scratchClose >= 0);
        assertTrue(runtimeClose > scratchClose);
        assertTrue(
            renderer.contains(
                "frameScratch.commitPreviousViewProjection();"
            )
        );
        assertTrue(
            renderer.contains(
                "Transform-Scratch: "
            )
        );
    }

    @Test
    void dormantGpuStagingReplacementRemainsUnattachedAndTruthful()
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
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "MotionVectorGenerator.java"
        );

        assertTrue(
            engine.contains(
                "eligible / NOT_ATTACHED (vanilla owner)"
            )
        );
        assertFalse(engine.contains("new BlockframeStagingBuffer"));
        assertFalse(runtime.contains("BlockframeStagingBuffer"));
        assertFalse(renderer.contains("BlockframeStagingBuffer"));
        assertFalse(motion.contains("BlockframeStagingBuffer"));
        assertTrue(
            engine.contains(
                "RAM exact entities/shader/staging: "
            )
        );
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
