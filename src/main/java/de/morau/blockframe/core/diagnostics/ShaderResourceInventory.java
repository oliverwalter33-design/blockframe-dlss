package de.morau.blockframe.core.diagnostics;

import java.util.Arrays;

/**
 * Exact object and descriptor-slot inventory for BlockFrame-owned shader
 * resources.
 *
 * <p>Vulkan does not expose portable per-object driver-memory sizes for
 * pipelines, descriptor objects or samplers. This inventory therefore counts
 * ownership transitions without pretending that those counts are physical
 * byte measurements. Byte-accounted payloads remain in
 * {@code MemoryBudgetManager}.</p>
 */
public final class ShaderResourceInventory implements AutoCloseable {
    private static final int RETIREMENT_FRAMES = 3;
    private static final ResourceKind[] KINDS = ResourceKind.values();

    private final int[] active = new int[KINDS.length];
    private final int[] retiring = new int[KINDS.length];
    private final int[] peak = new int[KINDS.length];
    private final long[] created = new long[KINDS.length];
    private final long[] destroyed = new long[KINDS.length];
    private final long[] creationFailures = new long[KINDS.length];
    private final long[] cleanupFailures = new long[KINDS.length];
    private final int[][] retirementRing =
        new int[RETIREMENT_FRAMES][KINDS.length];

    private long epoch;
    private long integrityErrors;
    private long leaks;
    private boolean closed;

    public enum ResourceKind {
        SHADER_MODULE,
        DESCRIPTOR_SET_LAYOUT,
        DESCRIPTOR_POOL,
        DESCRIPTOR_SET,
        DESCRIPTOR,
        PIPELINE_LAYOUT,
        COMPUTE_PIPELINE,
        RAW_DEPTH_SAMPLER,
        MANAGED_UNIFORM_BUFFER,
        MATERIAL_SAMPLER,
        RAW_BUFFER_VIEW,
        MANAGED_GPU_SCENE_BUFFER
    }

    public synchronized void created(ResourceKind kind) {
        this.created(kind, 1);
    }

    public synchronized void created(ResourceKind kind, int count) {
        int index = requireCount(kind, count);
        if (this.closed) {
            this.integrityErrors++;
            return;
        }
        this.active[index] = addSaturated(
            this.active[index],
            count
        );
        this.created[index] = addSaturated(
            this.created[index],
            count
        );
        this.peak[index] = Math.max(
            this.peak[index],
            owned(index)
        );
    }

    public synchronized void creationFailed(ResourceKind kind) {
        int index = requireKind(kind);
        this.creationFailures[index] = addSaturated(
            this.creationFailures[index],
            1L
        );
    }

    public synchronized void cleanupFailed(ResourceKind kind) {
        int index = requireKind(kind);
        this.cleanupFailures[index] = addSaturated(
            this.cleanupFailures[index],
            1L
        );
    }

    /**
     * Moves a Mojang-managed object into the same conservative three-frame
     * retirement window used by the central memory budget manager.
     */
    public synchronized void queuedForRetirement(
        ResourceKind kind
    ) {
        this.queuedForRetirement(kind, 1);
    }

    public synchronized void queuedForRetirement(
        ResourceKind kind,
        int count
    ) {
        int index = requireCount(kind, count);
        if (this.closed || this.active[index] < count) {
            this.integrityErrors++;
            return;
        }
        int bucket = (int)(this.epoch % RETIREMENT_FRAMES);
        int scheduled = addSaturated(
            this.retirementRing[bucket][index],
            count
        );
        if (scheduled == Integer.MAX_VALUE) {
            this.integrityErrors++;
            return;
        }
        this.retirementRing[bucket][index] = scheduled;
        this.active[index] -= count;
        this.retiring[index] = addSaturated(
            this.retiring[index],
            count
        );
    }

    /** Records a directly confirmed raw Vulkan destruction. */
    public synchronized void destroyed(ResourceKind kind) {
        this.destroyed(kind, 1);
    }

    public synchronized void destroyed(
        ResourceKind kind,
        int count
    ) {
        int index = requireCount(kind, count);
        if (this.active[index] < count) {
            this.integrityErrors++;
            return;
        }
        this.active[index] -= count;
        this.destroyed[index] = addSaturated(
            this.destroyed[index],
            count
        );
    }

    /** Advances one completed client frame without allocating. */
    public synchronized void advanceFrame() {
        if (this.closed) {
            return;
        }
        this.epoch++;
        int bucket = (int)(this.epoch % RETIREMENT_FRAMES);
        for (int index = 0; index < KINDS.length; index++) {
            int count = this.retirementRing[bucket][index];
            if (count == 0) {
                continue;
            }
            this.retirementRing[bucket][index] = 0;
            if (this.retiring[index] < count) {
                this.integrityErrors++;
                count = this.retiring[index];
            }
            this.retiring[index] -= count;
            this.destroyed[index] = addSaturated(
                this.destroyed[index],
                count
            );
        }
    }

    /**
     * Completes every queued object after the Vulkan encoder and its
     * destruction queue have been synchronously drained.
     */
    public synchronized int completeGpuRetirements() {
        if (this.closed) {
            return 0;
        }
        int completed = 0;
        for (int index = 0; index < KINDS.length; index++) {
            int count = this.retiring[index];
            if (count == 0) {
                continue;
            }
            completed = addSaturated(completed, count);
            this.destroyed[index] = addSaturated(
                this.destroyed[index],
                count
            );
            this.retiring[index] = 0;
        }
        for (int[] bucket : this.retirementRing) {
            Arrays.fill(bucket, 0);
        }
        return completed;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.active.clone(),
            this.retiring.clone(),
            this.peak.clone(),
            this.created.clone(),
            this.destroyed.clone(),
            this.creationFailures.clone(),
            this.cleanupFailures.clone(),
            this.integrityErrors,
            this.leaks,
            this.closed
        );
    }

    @Override
    public synchronized void close() {
        this.closeAndReport();
    }

    /**
     * Closes the inventory and reports whether ownership and cleanup
     * accounting prove a clean result.
     */
    public synchronized boolean closeAndReport() {
        if (this.closed) {
            return this.closeReportedCleanly();
        }
        this.leaks = addSaturated(
            this.leaks,
            currentTotal()
        );
        this.closed = true;
        return this.closeReportedCleanly();
    }

    private boolean closeReportedCleanly() {
        return this.closed
            && this.currentTotal() == 0
            && this.leaks == 0L
            && this.integrityErrors == 0L
            && total(this.cleanupFailures) == 0L;
    }

    private int currentTotal() {
        int total = 0;
        for (int index = 0; index < KINDS.length; index++) {
            total = addSaturated(total, owned(index));
        }
        return total;
    }

    private int owned(int index) {
        return addSaturated(
            this.active[index],
            this.retiring[index]
        );
    }

    private static int requireKind(ResourceKind kind) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        return kind.ordinal();
    }

    private static int requireCount(
        ResourceKind kind,
        int count
    ) {
        int index = requireKind(kind);
        if (count <= 0) {
            throw new IllegalArgumentException(
                "resource count must be positive"
            );
        }
        return index;
    }

    private static int addSaturated(int left, int right) {
        long sum = (long)left + right;
        return sum >= Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : (int)sum;
    }

    private static long addSaturated(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long total(long[] values) {
        long total = 0L;
        for (long value : values) {
            total = addSaturated(total, value);
        }
        return total;
    }

    public record Snapshot(
        int[] activeCounts,
        int[] retiringCounts,
        int[] peakCounts,
        long[] createdCounts,
        long[] destroyedCounts,
        long[] creationFailureCounts,
        long[] cleanupFailureCounts,
        long integrityErrors,
        long leaks,
        boolean closed
    ) {
        public int active(ResourceKind kind) {
            return this.activeCounts[requireKind(kind)];
        }

        public int retiring(ResourceKind kind) {
            return this.retiringCounts[requireKind(kind)];
        }

        public int current(ResourceKind kind) {
            int index = requireKind(kind);
            return addSaturated(
                this.activeCounts[index],
                this.retiringCounts[index]
            );
        }

        public int peak(ResourceKind kind) {
            return this.peakCounts[requireKind(kind)];
        }

        public long created(ResourceKind kind) {
            return this.createdCounts[requireKind(kind)];
        }

        public long destroyed(ResourceKind kind) {
            return this.destroyedCounts[requireKind(kind)];
        }

        public long creationFailures(ResourceKind kind) {
            return this.creationFailureCounts[requireKind(kind)];
        }

        public long cleanupFailures(ResourceKind kind) {
            return this.cleanupFailureCounts[requireKind(kind)];
        }

        public long creationFailuresTotal() {
            return sumSaturated(this.creationFailureCounts);
        }

        public long cleanupFailuresTotal() {
            return sumSaturated(this.cleanupFailureCounts);
        }

        public int currentTotal() {
            int total = 0;
            for (ResourceKind kind : KINDS) {
                total = addSaturated(total, this.current(kind));
            }
            return total;
        }

        private static long sumSaturated(long[] values) {
            long total = 0L;
            for (long value : values) {
                total = addSaturated(total, value);
            }
            return total;
        }
    }
}
