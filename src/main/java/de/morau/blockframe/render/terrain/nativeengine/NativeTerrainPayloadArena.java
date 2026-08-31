package de.morau.blockframe.render.terrain.nativeengine;

import java.util.ArrayDeque;

/**
 * Bounded, size-classified byte storage for compiler-owned payloads.
 *
 * <p>One arena belongs to one native terrain worker generation. Leases may
 * cross from the compiler worker to the upload owner, but a byte array is
 * returned to the arena only after upload completion or failed compilation.
 * The pool is synchronized because release normally happens on the client
 * thread while acquisition happens on a compiler worker.</p>
 */
public final class NativeTerrainPayloadArena implements AutoCloseable {
    public static final int MINIMUM_CLASS_BYTES = 4 * 1024;

    public record Snapshot(
        long acquisitions,
        long pooledHits,
        long allocations,
        long returned,
        long discarded,
        long residentBytes,
        long highWaterResidentBytes,
        int outstandingLeases,
        boolean closed
    ) {
    }

    public static final class Lease implements AutoCloseable {
        private final NativeTerrainPayloadArena owner;
        private byte[] bytes;
        private boolean closed;

        private Lease(
            NativeTerrainPayloadArena owner,
            byte[] bytes
        ) {
            this.owner = owner;
            this.bytes = bytes;
        }

        public int capacity() {
            requireOpen();
            return this.bytes.length;
        }

        byte[] bytes() {
            requireOpen();
            return this.bytes;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                byte[] returned = this.bytes;
                this.bytes = null;
                this.owner.release(returned);
            }
        }

        private void requireOpen() {
            if (this.closed || this.bytes == null) {
                throw new IllegalStateException(
                    "payload arena lease is closed"
                );
            }
        }
    }

    private final int maximumClassBytes;
    private final int maximumArraysPerClass;
    private final int classCount;
    private final ArrayDeque<byte[]>[] free;
    private long acquisitions;
    private long pooledHits;
    private long allocations;
    private long returned;
    private long discarded;
    private long residentBytes;
    private long highWaterResidentBytes;
    private int outstandingLeases;
    private boolean closed;

    @SuppressWarnings("unchecked")
    public NativeTerrainPayloadArena(
        int maximumClassBytes,
        int maximumArraysPerClass
    ) {
        if (
            maximumClassBytes < MINIMUM_CLASS_BYTES
                || !isPowerOfTwo(maximumClassBytes)
                || maximumArraysPerClass <= 0
        ) {
            throw new IllegalArgumentException(
                "invalid payload arena limits"
            );
        }
        this.maximumClassBytes = maximumClassBytes;
        this.maximumArraysPerClass = maximumArraysPerClass;
        this.classCount =
            Integer.numberOfTrailingZeros(maximumClassBytes)
                - Integer.numberOfTrailingZeros(
                    MINIMUM_CLASS_BYTES
                )
                + 1;
        this.free = new ArrayDeque[this.classCount];
        for (int index = 0; index < this.classCount; index++) {
            this.free[index] = new ArrayDeque<>();
        }
    }

    public synchronized Lease acquire(int minimumBytes) {
        requireOpen();
        int classBytes = classBytes(minimumBytes);
        int classIndex = classIndex(classBytes);
        byte[] bytes = this.free[classIndex].pollFirst();
        this.acquisitions++;
        if (bytes == null) {
            bytes = new byte[classBytes];
            this.allocations++;
            this.residentBytes += classBytes;
            this.highWaterResidentBytes = Math.max(
                this.highWaterResidentBytes,
                this.residentBytes
            );
        } else {
            this.pooledHits++;
        }
        this.outstandingLeases++;
        return new Lease(this, bytes);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.acquisitions,
            this.pooledHits,
            this.allocations,
            this.returned,
            this.discarded,
            this.residentBytes,
            this.highWaterResidentBytes,
            this.outstandingLeases,
            this.closed
        );
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        if (this.outstandingLeases != 0) {
            throw new IllegalStateException(
                "payload arena still has outstanding leases"
            );
        }
        for (ArrayDeque<byte[]> sizeClass : this.free) {
            byte[] bytes;
            while ((bytes = sizeClass.pollFirst()) != null) {
                this.residentBytes -= bytes.length;
                this.discarded++;
            }
        }
        this.closed = true;
    }

    private synchronized void release(byte[] bytes) {
        this.outstandingLeases--;
        if (
            this.closed
                || bytes.length > this.maximumClassBytes
        ) {
            this.residentBytes -= bytes.length;
            this.discarded++;
            return;
        }
        ArrayDeque<byte[]> sizeClass =
            this.free[classIndex(bytes.length)];
        if (sizeClass.size() >= this.maximumArraysPerClass) {
            this.residentBytes -= bytes.length;
            this.discarded++;
            return;
        }
        sizeClass.addFirst(bytes);
        this.returned++;
    }

    private int classBytes(int minimumBytes) {
        if (minimumBytes <= 0) {
            return MINIMUM_CLASS_BYTES;
        }
        if (minimumBytes > this.maximumClassBytes) {
            throw new IllegalArgumentException(
                "payload exceeds configured arena class ceiling"
            );
        }
        int selected = Math.max(
            MINIMUM_CLASS_BYTES,
            Integer.highestOneBit(minimumBytes - 1) << 1
        );
        if (selected <= 0 || selected > this.maximumClassBytes) {
            selected = this.maximumClassBytes;
        }
        return selected;
    }

    private static int classIndex(int bytes) {
        if (
            bytes < MINIMUM_CLASS_BYTES
                || !isPowerOfTwo(bytes)
        ) {
            throw new IllegalArgumentException(
                "payload byte array is not a size class"
            );
        }
        return Integer.numberOfTrailingZeros(bytes)
            - Integer.numberOfTrailingZeros(MINIMUM_CLASS_BYTES);
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                "payload arena is closed"
            );
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
