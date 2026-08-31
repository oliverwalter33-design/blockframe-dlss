package de.morau.blockframe.render;

/**
 * Allocation-free accounting for the bounded upload ring.
 *
 * <p>The GPU ownership and synchronization stay in Minecraft's
 * {@code MappableRingBuffer}; this class only mirrors slot and capacity state
 * so telemetry can remain independent from backend implementation details.</p>
 */
final class TripleUploadRingState {
    static final int SLOT_COUNT = 3;

    private final int capacityPerSlot;
    private int currentSlot;
    private int usedBytes;
    private int peakUsedBytes;
    private long appendedBytes;
    private long copiedBytes;
    private long rotations;

    TripleUploadRingState(int capacityPerSlot) {
        if (capacityPerSlot <= 0) {
            throw new IllegalArgumentException("capacityPerSlot must be positive");
        }

        this.capacityPerSlot = capacityPerSlot;
    }

    static boolean shouldUseManagedPath(boolean engineEnabled, boolean persistentMapping, int requestedBufferSize) {
        return engineEnabled && persistentMapping && requestedBufferSize >= 2;
    }

    static int capacityPerSlot(int requestedBufferSize) {
        if (requestedBufferSize < 2) {
            throw new IllegalArgumentException("requestedBufferSize must be at least 2");
        }

        return requestedBufferSize / 2;
    }

    void recordAppend(int byteCount) {
        if (byteCount < 0 || byteCount > this.capacityPerSlot - this.usedBytes) {
            throw new IllegalStateException(
                "Upload accounting diverged: cannot append "
                    + byteCount
                    + " bytes with "
                    + (this.capacityPerSlot - this.usedBytes)
                    + " bytes remaining"
            );
        }

        this.usedBytes += byteCount;
        this.appendedBytes += byteCount;
        this.peakUsedBytes = Math.max(this.peakUsedBytes, this.usedBytes);
    }

    void recordCopy(long byteCount) {
        if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }

        this.copiedBytes += byteCount;
    }

    int rotate() {
        this.currentSlot = (this.currentSlot + 1) % SLOT_COUNT;
        this.usedBytes = 0;
        this.rotations++;
        return this.currentSlot;
    }

    int capacityPerSlot() {
        return this.capacityPerSlot;
    }

    int currentSlot() {
        return this.currentSlot;
    }

    int usedBytes() {
        return this.usedBytes;
    }

    int peakUsedBytes() {
        return this.peakUsedBytes;
    }

    long appendedBytes() {
        return this.appendedBytes;
    }

    long copiedBytes() {
        return this.copiedBytes;
    }

    long rotations() {
        return this.rotations;
    }

    boolean currentSlotIsBeingReused() {
        return this.rotations >= SLOT_COUNT;
    }
}
