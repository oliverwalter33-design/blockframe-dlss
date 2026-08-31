package de.morau.blockframe.render;

import de.morau.blockframe.profiler.FrameProfiler;
import java.util.Objects;

/**
 * Bridges staging activity into the engine profiler without exposing mapped
 * memory or allocating per upload.
 */
public final class FrameProfilerStagingObserver implements BlockframeStagingBuffer.Observer {
    private final FrameProfiler profiler;
    private long capacityBytes;

    public FrameProfilerStagingObserver(FrameProfiler profiler) {
        this.profiler = Objects.requireNonNull(profiler, "profiler");
    }

    @Override
    public void bytesAppended(int slot, long bytes, long usedBytes, long capacityBytes, long peakUsedBytes) {
        this.capacityBytes = capacityBytes;
        this.profiler.recordRingUsage(usedBytes, capacityBytes);
    }

    @Override
    public void bytesCopied(int slot, long bytes, long totalCopiedBytes) {
        this.profiler.recordUploadBytes(bytes);
    }

    @Override
    public void rotated(int previousSlot, int nextSlot, long totalRotations) {
        this.profiler.recordRingUsage(0L, this.capacityBytes);
    }
}
