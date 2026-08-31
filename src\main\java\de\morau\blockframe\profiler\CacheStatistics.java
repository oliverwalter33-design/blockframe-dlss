package de.morau.blockframe.profiler;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocation-free cache counters shared by cache providers.
 *
 * <p>Recording is safe from loader workers. Only {@link #snapshot()} creates
 * an immutable object for diagnostics or benchmark export.</p>
 */
public final class CacheStatistics {
    private final AtomicBoolean attached = new AtomicBoolean();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong rejectedEntries = new AtomicLong();
    private final AtomicLong writtenEntries = new AtomicLong();
    private final AtomicLong loadedBytes = new AtomicLong();
    private final AtomicLong writtenBytes = new AtomicLong();
    private final AtomicLong loadNanos = new AtomicLong();
    private final AtomicLong saveNanos = new AtomicLong();
    private final AtomicLong bytesOnDisk = new AtomicLong();

    public void markAttached() {
        this.attached.set(true);
    }

    public boolean attached() {
        return this.attached.get();
    }

    public void recordHit(long bytes, long nanos) {
        requireNonNegative(bytes, "bytes");
        requireNonNegative(nanos, "nanos");
        this.hits.incrementAndGet();
        this.loadedBytes.addAndGet(bytes);
        this.loadNanos.addAndGet(nanos);
    }

    public void recordMiss(long nanos) {
        requireNonNegative(nanos, "nanos");
        this.misses.incrementAndGet();
        this.loadNanos.addAndGet(nanos);
    }

    public void recordRejectedEntry(long nanos) {
        requireNonNegative(nanos, "nanos");
        this.rejectedEntries.incrementAndGet();
        this.loadNanos.addAndGet(nanos);
    }

    public void recordWrite(long bytes, long nanos) {
        requireNonNegative(bytes, "bytes");
        requireNonNegative(nanos, "nanos");
        this.writtenEntries.incrementAndGet();
        this.writtenBytes.addAndGet(bytes);
        this.saveNanos.addAndGet(nanos);
    }

    public void setBytesOnDisk(long bytes) {
        requireNonNegative(bytes, "bytes");
        this.bytesOnDisk.set(bytes);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.hits.get(),
            this.misses.get(),
            this.rejectedEntries.get(),
            this.writtenEntries.get(),
            this.loadedBytes.get(),
            this.writtenBytes.get(),
            this.loadNanos.get(),
            this.saveNanos.get(),
            this.bytesOnDisk.get(),
            this.attached.get()
        );
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    public record Snapshot(
        long hits,
        long misses,
        long rejectedEntries,
        long writtenEntries,
        long loadedBytes,
        long writtenBytes,
        long loadNanos,
        long saveNanos,
        long bytesOnDisk,
        boolean attached
    ) {
        public long lookups() {
            return this.hits + this.misses;
        }

        public double hitRate() {
            long lookups = this.lookups();
            return lookups == 0L ? 0.0D : (double)this.hits / lookups;
        }
    }
}
