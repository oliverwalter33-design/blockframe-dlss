package de.morau.blockframe.faststart;

/**
 * Allocation-free stability window for a render-thread-owned observation.
 *
 * <p>The timer starts only while the complete readiness predicate is true.
 * A changed visible-state signature restarts the timer, so a merely
 * momentary empty queue or a mesh replacement cannot publish T16.</p>
 */
final class FastStartStabilityWindow {
    private final long requiredNanos;
    private long readySinceNanos = Long.MIN_VALUE;
    private long lastSignature;

    FastStartStabilityWindow(long requiredNanos) {
        if (requiredNanos < 0L) {
            throw new IllegalArgumentException(
                "requiredNanos must not be negative"
            );
        }
        this.requiredNanos = requiredNanos;
    }

    boolean observe(boolean eligible, long signature, long nowNanos) {
        if (!eligible) {
            reset();
            return false;
        }
        if (
            this.readySinceNanos == Long.MIN_VALUE
                || this.lastSignature != signature
                || nowNanos < this.readySinceNanos
        ) {
            this.readySinceNanos = nowNanos;
            this.lastSignature = signature;
            return this.requiredNanos == 0L;
        }
        return nowNanos - this.readySinceNanos >= this.requiredNanos;
    }

    long stableForNanos(long nowNanos) {
        if (
            this.readySinceNanos == Long.MIN_VALUE
                || nowNanos < this.readySinceNanos
        ) {
            return 0L;
        }
        return nowNanos - this.readySinceNanos;
    }

    void reset() {
        this.readySinceNanos = Long.MIN_VALUE;
        this.lastSignature = 0L;
    }
}
