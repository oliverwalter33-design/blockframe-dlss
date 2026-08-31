package de.morau.blockframe.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FrameProfilerTest {
    @Test
    void capturesAndResetsPrimitiveFrameCounters() {
        FrameProfiler profiler = new FrameProfiler(8);
        profiler.beginFrame(1_000L);
        profiler.recordDrawCall();
        profiler.recordDrawCalls(4L);
        profiler.recordVisibleSections(12L);
        profiler.recordCpuCull(300L);
        profiler.recordUpload(4_096L, 700L);
        profiler.recordRingUsage(2_048L, 8_192L);
        profiler.endFrame(2_500L);

        FrameProfiler.Snapshot first = profiler.snapshot();
        assertFalse(first.frameOpen());
        assertEquals(1_500L, first.frameDurationNanos());
        assertEquals(5L, first.drawCalls());
        assertEquals(12L, first.visibleSections());
        assertEquals(300L, first.cpuCullNanos());
        assertEquals(4_096L, first.uploadBytes());
        assertEquals(700L, first.uploadNanos());
        assertEquals(0.25D, first.ringUtilization());

        profiler.beginFrame(3_000L);
        FrameProfiler.Snapshot second = profiler.snapshot();
        assertTrue(second.frameOpen());
        assertEquals(0L, second.drawCalls());
        assertEquals(0L, second.visibleSections());
        assertEquals(0L, second.uploadBytes());
        assertEquals(0L, second.ringUsedBytes());
        assertEquals(8_192L, second.ringCapacityBytes());
    }

    @Test
    void uploadCountersAreSafeForWorkerThreads() throws Exception {
        FrameProfiler profiler = new FrameProfiler(8);
        profiler.beginFrame(0L);
        int workers = 4;
        int recordsPerWorker = 10_000;
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            tasks.add(() -> {
                for (int record = 0; record < recordsPerWorker; record++) {
                    profiler.recordUpload(64L, 2L);
                }
                return null;
            });
        }

        try (var executor = Executors.newFixedThreadPool(workers)) {
            executor.invokeAll(tasks);
        }
        profiler.endFrame(1L);

        FrameProfiler.Snapshot snapshot = profiler.snapshot();
        assertEquals((long)workers * recordsPerWorker * 64L, snapshot.uploadBytes());
        assertEquals((long)workers * recordsPerWorker * 2L, snapshot.uploadNanos());
    }

    @Test
    void rollingPercentilesUseOnlyTheNewestFramesAfterWraparound() {
        FrameProfiler profiler = new FrameProfiler(4);
        recordDuration(profiler, 10L);
        recordDuration(profiler, 20L);
        recordDuration(profiler, 30L);
        recordDuration(profiler, 40L);
        recordDuration(profiler, 50L);

        FrameProfiler.Snapshot snapshot = profiler.snapshot();
        assertEquals(5L, snapshot.completedFrames());
        assertEquals(4, snapshot.rollingSampleCount());
        assertEquals(30L, snapshot.p50FrameNanos());
        assertEquals(50L, snapshot.p95FrameNanos());
        assertEquals(50L, snapshot.p99FrameNanos());
    }

    @Test
    void emptyHistoryHasZeroPercentiles() {
        FrameProfiler.Snapshot snapshot = new FrameProfiler(3).snapshot();

        assertEquals(0, snapshot.rollingSampleCount());
        assertEquals(0L, snapshot.p50FrameNanos());
        assertEquals(0L, snapshot.p95FrameNanos());
        assertEquals(0L, snapshot.p99FrameNanos());
    }

    @Test
    void abortDropsIncompleteFrameAndItsUploadMeasurements() {
        FrameProfiler profiler = new FrameProfiler(3);
        profiler.beginFrame(10L);
        profiler.recordUpload(128L, 20L);

        profiler.abortFrame();

        FrameProfiler.Snapshot aborted = profiler.snapshot();
        assertFalse(aborted.frameOpen());
        assertEquals(0L, aborted.completedFrames());
        assertEquals(0L, aborted.uploadBytes());
        assertEquals(0L, aborted.uploadNanos());

        profiler.beginFrame(20L);
        profiler.recordUpload(64L, 5L);
        profiler.endFrame(30L);
        FrameProfiler.Snapshot recovered = profiler.snapshot();
        assertEquals(64L, recovered.uploadBytes());
        assertEquals(5L, recovered.uploadNanos());
        assertEquals(1L, recovered.completedFrames());
    }

    @Test
    void gpuHistoryIsIndependentAndUsesTheSameBoundedWindow() {
        FrameProfiler profiler = new FrameProfiler(3);
        profiler.recordGpuFrame(10L);
        profiler.recordGpuFrame(20L);
        profiler.recordGpuFrame(30L);
        profiler.recordGpuFrame(40L);

        FrameProfiler.Snapshot snapshot = profiler.snapshot();
        assertEquals(4L, snapshot.completedGpuFrames());
        assertEquals(40L, snapshot.gpuFrameDurationNanos());
        assertEquals(3, snapshot.rollingGpuSampleCount());
        assertEquals(30L, snapshot.p50GpuFrameNanos());
        assertEquals(40L, snapshot.p95GpuFrameNanos());
        assertEquals(40L, snapshot.p99GpuFrameNanos());
        assertEquals(0L, snapshot.completedFrames());
    }

    @Test
    void disabledProfilerOwnsNoRollingStorageAndReturnsCachedZeroState() {
        FrameProfiler profiler = FrameProfiler.disabled();
        FrameProfiler.Snapshot first = profiler.snapshot();

        profiler.beginFrame(10L);
        profiler.recordDrawCall();
        profiler.recordVisibleSections(3L);
        profiler.recordCpuCull(5L);
        profiler.recordUpload(64L, 2L);
        profiler.recordRingUsage(32L, 64L);
        profiler.recordGpuFrame(7L);
        profiler.endFrame(20L);

        assertFalse(profiler.enabled());
        assertEquals(0L, profiler.rollingStorageBytes());
        assertSame(first, profiler.snapshot());
        assertEquals(0L, first.completedFrames());
        assertEquals(0L, first.completedGpuFrames());
        assertEquals(0, first.rollingSampleCount());
        assertEquals(0, first.rollingGpuSampleCount());
    }

    private static void recordDuration(FrameProfiler profiler, long duration) {
        profiler.beginFrame(1_000L);
        profiler.endFrame(1_000L + duration);
    }
}
