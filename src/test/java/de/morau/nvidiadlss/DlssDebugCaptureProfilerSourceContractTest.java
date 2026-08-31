package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssDebugCaptureProfilerSourceContractTest {
    @Test
    void debugMetadataContainsRollingCpuAndGpuFramePercentiles() throws Exception {
        String source = source("src/main/java/de/morau/nvidiadlss/DlssRenderer.java");
        assertTrue(source.contains("BlockframeRuntime.engine().profiler().snapshot()"));
        assertTrue(source.contains("\"frameProfiler\""));
        assertTrue(source.contains("\"frameTimeMs\": {\"last\": %s, \"p50\": %s, \"p95\": %s, \"p99\": %s}"));
        assertTrue(source.contains("\"gpuFrameTimeMs\": {\"last\": %s, \"p50\": %s, \"p95\": %s, \"p99\": %s}"));
        assertTrue(source.contains("profiler.p95FrameNanos()"));
        assertTrue(source.contains("profiler.p99FrameNanos()"));
        assertTrue(source.contains("profiler.p95GpuFrameNanos()"));
        assertTrue(source.contains("profiler.p99GpuFrameNanos()"));
        assertTrue(source.contains("sampleCount > 0L"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
