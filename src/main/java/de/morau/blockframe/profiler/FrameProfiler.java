package de.morau.blockframe.profiler;

import java.util.Arrays;

/**
 * Low-overhead frame counters. Render counters are owned by the render thread;
 * upload counters use one small critical section because accounting may
 * originate on worker threads and byte/time pairs must remain coherent across
 * frame boundaries. Only {@link #snapshot()} allocates.
 */
public final class FrameProfiler {
    public static final int DEFAULT_ROLLING_WINDOW_SIZE = 240;

    private static final Snapshot DISABLED_SNAPSHOT = new Snapshot(
        0L,
        0L,
        false,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0,
        0L,
        0L,
        0L,
        0L,
        0L,
        0,
        0L,
        0L,
        0L
    );
    private static final FrameProfiler DISABLED =
        new FrameProfiler(0, false);

    private final boolean enabled;
    private final long[] frameDurationHistory;
    private final long[] gpuFrameDurationHistory;
    private final Object historyLock;
    private final Object uploadLock;

    private volatile long frameNumber;
    private volatile long completedFrames;
    private volatile long frameStartNanos;
    private volatile long frameEndNanos;
    private volatile long frameDurationNanos;
    private volatile long gpuFrameDurationNanos;
    private volatile long completedGpuFrames;
    private volatile long drawCalls;
    private volatile long visibleSections;
    private volatile long cpuCullNanos;
    private volatile long ringUsedBytes;
    private volatile long ringCapacityBytes;
    private volatile boolean frameOpen;
    private long totalUploadBytes;
    private long totalUploadNanos;
    private long uploadBytesAtFrameStart;
    private long uploadNanosAtFrameStart;
    private long frameUploadBytes;
    private long frameUploadNanos;
    private int historyCursor;
    private int historyCount;
    private int gpuHistoryCursor;
    private int gpuHistoryCount;

    public FrameProfiler() {
        this(DEFAULT_ROLLING_WINDOW_SIZE, true);
    }

    public FrameProfiler(int rollingWindowSize) {
        if (rollingWindowSize < 1) {
            throw new IllegalArgumentException("rollingWindowSize must be at least one");
        }
        this.enabled = true;
        this.frameDurationHistory = new long[rollingWindowSize];
        this.gpuFrameDurationHistory = new long[rollingWindowSize];
        this.historyLock = new Object();
        this.uploadLock = new Object();
    }

    private FrameProfiler(int rollingWindowSize, boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            this.frameDurationHistory = new long[rollingWindowSize];
            this.gpuFrameDurationHistory = new long[rollingWindowSize];
            this.historyLock = new Object();
            this.uploadLock = new Object();
        } else {
            this.frameDurationHistory = null;
            this.gpuFrameDurationHistory = null;
            this.historyLock = null;
            this.uploadLock = null;
        }
    }

    /**
     * Shared no-op profiler for a disabled optional diagnostic feature.
     *
     * <p>It owns no rolling arrays or locks, and its snapshot is cached.
     */
    public static FrameProfiler disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public long rollingStorageBytes() {
        return this.enabled
            ? (long)(
                this.frameDurationHistory.length
                    + this.gpuFrameDurationHistory.length
            ) * Long.BYTES
            : 0L;
    }

    public void beginFrame() {
        if (!this.enabled) {
            return;
        }
        this.beginFrame(System.nanoTime());
    }

    public void beginFrame(long nowNanos) {
        if (!this.enabled) {
            return;
        }
        this.abortFrame();
        this.frameNumber++;
        this.frameStartNanos = nowNanos;
        this.frameEndNanos = nowNanos;
        this.frameDurationNanos = 0L;
        this.drawCalls = 0L;
        this.visibleSections = 0L;
        this.cpuCullNanos = 0L;
        this.ringUsedBytes = 0L;
        synchronized (this.uploadLock) {
            this.uploadBytesAtFrameStart = this.totalUploadBytes;
            this.uploadNanosAtFrameStart = this.totalUploadNanos;
            this.frameUploadBytes = 0L;
            this.frameUploadNanos = 0L;
            this.frameOpen = true;
        }
    }

    public void endFrame() {
        if (!this.enabled) {
            return;
        }
        this.endFrame(System.nanoTime());
    }

    public void endFrame(long nowNanos) {
        if (!this.enabled) {
            return;
        }
        synchronized (this.uploadLock) {
            if (!this.frameOpen) {
                return;
            }
            this.frameOpen = false;
            this.frameUploadBytes =
                this.totalUploadBytes - this.uploadBytesAtFrameStart;
            this.frameUploadNanos =
                this.totalUploadNanos - this.uploadNanosAtFrameStart;
        }

        long duration = Math.max(0L, nowNanos - this.frameStartNanos);
        this.frameEndNanos = Math.max(this.frameStartNanos, nowNanos);
        this.frameDurationNanos = duration;
        synchronized (this.historyLock) {
            this.frameDurationHistory[this.historyCursor] = duration;
            this.historyCursor = (this.historyCursor + 1) % this.frameDurationHistory.length;
            if (this.historyCount < this.frameDurationHistory.length) {
                this.historyCount++;
            }
            this.completedFrames++;
        }
    }

    /**
     * Drops an incomplete frame without adding a percentile sample. This is
     * used on deterministic recovery after a render exception.
     */
    public void abortFrame() {
        if (!this.enabled) {
            return;
        }
        synchronized (this.uploadLock) {
            if (!this.frameOpen) {
                return;
            }
            this.frameOpen = false;
            this.frameUploadBytes = 0L;
            this.frameUploadNanos = 0L;
        }
    }

    public void recordDrawCall() {
        if (!this.enabled) {
            return;
        }
        this.drawCalls++;
    }

    public void recordDrawCalls(long count) {
        requireNonNegative(count, "count");
        if (!this.enabled) {
            return;
        }
        this.drawCalls += count;
    }

    public void recordVisibleSection() {
        if (!this.enabled) {
            return;
        }
        this.visibleSections++;
    }

    public void recordVisibleSections(long count) {
        requireNonNegative(count, "count");
        if (!this.enabled) {
            return;
        }
        this.visibleSections += count;
    }

    public void setVisibleSections(long count) {
        requireNonNegative(count, "count");
        if (!this.enabled) {
            return;
        }
        this.visibleSections = count;
    }

    public void recordCpuCull(long nanos) {
        requireNonNegative(nanos, "nanos");
        if (!this.enabled) {
            return;
        }
        this.cpuCullNanos += nanos;
    }

    public void recordUpload(long bytes, long nanos) {
        requireNonNegative(bytes, "bytes");
        requireNonNegative(nanos, "nanos");
        if (!this.enabled) {
            return;
        }
        synchronized (this.uploadLock) {
            if (this.frameOpen) {
                this.totalUploadBytes += bytes;
                this.totalUploadNanos += nanos;
            }
        }
    }

    public void recordUploadBytes(long bytes) {
        requireNonNegative(bytes, "bytes");
        if (!this.enabled) {
            return;
        }
        synchronized (this.uploadLock) {
            if (this.frameOpen) {
                this.totalUploadBytes += bytes;
            }
        }
    }

    public void recordUploadNanos(long nanos) {
        requireNonNegative(nanos, "nanos");
        if (!this.enabled) {
            return;
        }
        synchronized (this.uploadLock) {
            if (this.frameOpen) {
                this.totalUploadNanos += nanos;
            }
        }
    }

    public void recordRingUsage(long usedBytes, long capacityBytes) {
        requireNonNegative(usedBytes, "usedBytes");
        requireNonNegative(capacityBytes, "capacityBytes");
        if (!this.enabled) {
            return;
        }
        this.ringCapacityBytes = capacityBytes;
        this.ringUsedBytes = usedBytes;
    }

    public void recordGpuFrame(long nanos) {
        requireNonNegative(nanos, "nanos");
        if (!this.enabled) {
            return;
        }
        synchronized (this.historyLock) {
            this.gpuFrameDurationNanos = nanos;
            this.gpuFrameDurationHistory[this.gpuHistoryCursor] = nanos;
            this.gpuHistoryCursor =
                (this.gpuHistoryCursor + 1) % this.gpuFrameDurationHistory.length;
            if (this.gpuHistoryCount < this.gpuFrameDurationHistory.length) {
                this.gpuHistoryCount++;
            }
            this.completedGpuFrames++;
        }
    }

    public Snapshot snapshot() {
        if (!this.enabled) {
            return DISABLED_SNAPSHOT;
        }
        long[] sortedDurations;
        long[] sortedGpuDurations;
        long completed;
        long completedGpu;
        long lastGpuDuration;
        boolean snapshotFrameOpen;
        long snapshotUploadBytes;
        long snapshotUploadNanos;
        synchronized (this.historyLock) {
            sortedDurations = Arrays.copyOf(this.frameDurationHistory, this.historyCount);
            sortedGpuDurations =
                Arrays.copyOf(this.gpuFrameDurationHistory, this.gpuHistoryCount);
            completed = this.completedFrames;
            completedGpu = this.completedGpuFrames;
            lastGpuDuration = this.gpuFrameDurationNanos;
        }
        synchronized (this.uploadLock) {
            snapshotFrameOpen = this.frameOpen;
            snapshotUploadBytes = snapshotFrameOpen
                ? this.totalUploadBytes - this.uploadBytesAtFrameStart
                : this.frameUploadBytes;
            snapshotUploadNanos = snapshotFrameOpen
                ? this.totalUploadNanos - this.uploadNanosAtFrameStart
                : this.frameUploadNanos;
        }
        Arrays.sort(sortedDurations);
        Arrays.sort(sortedGpuDurations);

        long currentDuration = snapshotFrameOpen
            ? Math.max(0L, System.nanoTime() - this.frameStartNanos)
            : this.frameDurationNanos;
        return new Snapshot(
            this.frameNumber,
            completed,
            snapshotFrameOpen,
            currentDuration,
            this.drawCalls,
            this.visibleSections,
            this.cpuCullNanos,
            snapshotUploadBytes,
            snapshotUploadNanos,
            this.ringUsedBytes,
            this.ringCapacityBytes,
            sortedDurations.length,
            percentile(sortedDurations, 50),
            percentile(sortedDurations, 95),
            percentile(sortedDurations, 99),
            completedGpu,
            lastGpuDuration,
            sortedGpuDurations.length,
            percentile(sortedGpuDurations, 50),
            percentile(sortedGpuDurations, 95),
            percentile(sortedGpuDurations, 99)
        );
    }

    private static long percentile(long[] sortedValues, int percentile) {
        if (sortedValues.length == 0) {
            return 0L;
        }
        int rank = (int)Math.ceil(percentile / 100.0D * sortedValues.length);
        return sortedValues[Math.max(0, rank - 1)];
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    public record Snapshot(
        long frameNumber,
        long completedFrames,
        boolean frameOpen,
        long frameDurationNanos,
        long drawCalls,
        long visibleSections,
        long cpuCullNanos,
        long uploadBytes,
        long uploadNanos,
        long ringUsedBytes,
        long ringCapacityBytes,
        int rollingSampleCount,
        long p50FrameNanos,
        long p95FrameNanos,
        long p99FrameNanos,
        long completedGpuFrames,
        long gpuFrameDurationNanos,
        int rollingGpuSampleCount,
        long p50GpuFrameNanos,
        long p95GpuFrameNanos,
        long p99GpuFrameNanos
    ) {
        public double ringUtilization() {
            return this.ringCapacityBytes == 0L
                ? 0.0D
                : Math.min(1.0D, (double)this.ringUsedBytes / this.ringCapacityBytes);
        }
    }
}
