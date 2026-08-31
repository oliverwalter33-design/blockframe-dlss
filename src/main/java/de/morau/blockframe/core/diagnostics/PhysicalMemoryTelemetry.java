package de.morau.blockframe.core.diagnostics;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Throttled, fail-closed physical-memory observations cached for F8.
 *
 * <p>This telemetry is deliberately independent from BlockFrame's logical
 * memory budgets. It never changes allocation, eviction or rendering choices.
 */
public final class PhysicalMemoryTelemetry implements AutoCloseable {
    public static final long DEFAULT_SAMPLE_INTERVAL_NANOS =
        1_000_000_000L;

    private final RamProbe ramProbe;
    private final LongSupplier nanoClock;
    private final long sampleIntervalNanos;
    private final boolean enabled;

    private boolean ramFaulted;
    private RamStatus ramStatus;
    private long ramTotalBytes;
    private long ramAvailableBytes;

    private Object deviceOwner;
    private Thread deviceOwnerThread;
    private DeviceProbe deviceProbe;
    private DeviceStatus deviceStatus = DeviceStatus.NOT_REQUESTED;
    private long deviceHeapBytes;
    private long deviceBudgetBytes;
    private long deviceUsageBytes;
    private long deviceHeadroomBytes;
    private int deviceLocalHeapCount;

    private boolean sampled;
    private long lastSampleNanos;
    private boolean sampling;
    private boolean closed;
    private long refreshes;
    private long ramSamples;
    private long ramFailures;
    private long deviceSamples;
    private long deviceFailures;
    private long wrongThreadSkips;
    private long reentrantSkips;
    private long ownerConflicts;
    private long staleCloseAttempts;
    private Snapshot snapshot;

    public static PhysicalMemoryTelemetry createDefault() {
        RamProbe probe = null;
        try {
            probe = SystemPhysicalMemoryProbe.tryCreate();
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Explicit UNSUPPORTED is safer than coupling engine startup to JMX.
        }
        return new PhysicalMemoryTelemetry(
            probe,
            System::nanoTime,
            DEFAULT_SAMPLE_INTERVAL_NANOS,
            true
        );
    }

    /** Creates a no-probe owner for an explicitly disabled process. */
    public static PhysicalMemoryTelemetry createDisabled() {
        return new PhysicalMemoryTelemetry(
            null,
            System::nanoTime,
            DEFAULT_SAMPLE_INTERVAL_NANOS,
            false
        );
    }

    public PhysicalMemoryTelemetry(
        RamProbe ramProbe,
        LongSupplier nanoClock,
        long sampleIntervalNanos
    ) {
        this(ramProbe, nanoClock, sampleIntervalNanos, true);
    }

    private PhysicalMemoryTelemetry(
        RamProbe ramProbe,
        LongSupplier nanoClock,
        long sampleIntervalNanos,
        boolean enabled
    ) {
        if (sampleIntervalNanos <= 0L) {
            throw new IllegalArgumentException(
                "sampleIntervalNanos must be positive"
            );
        }
        this.ramProbe = ramProbe;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.sampleIntervalNanos = sampleIntervalNanos;
        this.enabled = enabled;
        this.ramStatus = !enabled
            ? RamStatus.DISABLED
            : ramProbe == null
                ? RamStatus.UNSUPPORTED
                : RamStatus.NOT_SAMPLED;
        if (!enabled) {
            this.deviceStatus = DeviceStatus.DISABLED;
        }
        this.publishSnapshot();
    }

    /**
     * Binds a borrowed query to exactly one live Vulkan device generation.
     *
     * @param extensionAdvertised whether the selected physical device
     *                            enumerated {@code VK_EXT_memory_budget}
     */
    public synchronized boolean attachVulkanDevice(
        Object owner,
        boolean extensionAdvertised,
        DeviceProbe probe
    ) {
        Objects.requireNonNull(owner, "owner");
        if (this.closed || !this.enabled) {
            return false;
        }
        if (this.deviceOwner != null && this.deviceOwner != owner) {
            this.ownerConflicts = incrementSaturated(this.ownerConflicts);
            this.deviceProbe = null;
            this.deviceOwnerThread = null;
            this.clearDeviceValues();
            this.deviceStatus = DeviceStatus.OWNER_CONFLICT;
            this.publishSnapshot();
            return false;
        }
        if (this.deviceOwner == owner) {
            return true;
        }

        this.deviceOwner = owner;
        this.deviceOwnerThread = Thread.currentThread();
        this.clearDeviceValues();
        if (!extensionAdvertised) {
            this.deviceProbe = null;
            this.deviceStatus = DeviceStatus.EXTENSION_NOT_ADVERTISED;
        } else if (probe == null) {
            this.deviceProbe = null;
            this.deviceStatus = DeviceStatus.QUERY_FAILED;
            this.deviceFailures = incrementSaturated(this.deviceFailures);
        } else {
            this.deviceProbe = probe;
            this.deviceStatus = DeviceStatus.NOT_SAMPLED;
        }
        this.sampled = false;
        this.publishSnapshot();
        return true;
    }

    /**
     * Drops the borrowed Vulkan query before its owning handles are destroyed.
     * A delayed close from another generation cannot clear the current owner.
     */
    public synchronized boolean vulkanDeviceClosing(Object owner) {
        Objects.requireNonNull(owner, "owner");
        if (this.closed || !this.enabled) {
            return false;
        }
        if (this.deviceOwner != owner) {
            this.staleCloseAttempts = incrementSaturated(
                this.staleCloseAttempts
            );
            this.publishSnapshot();
            return false;
        }
        this.deviceOwner = null;
        this.deviceOwnerThread = null;
        this.deviceProbe = null;
        this.clearDeviceValues();
        this.deviceStatus = this.closed
            ? DeviceStatus.CLOSED
            : DeviceStatus.DEVICE_CLOSING;
        this.publishSnapshot();
        return true;
    }

    /** Marks a backend without a live Vulkan owner, such as OpenGL. */
    public synchronized void notVulkanBackend() {
        if (this.closed || !this.enabled) {
            return;
        }
        this.deviceOwner = null;
        this.deviceProbe = null;
        this.deviceOwnerThread = null;
        this.clearDeviceValues();
        this.deviceStatus = DeviceStatus.NOT_VULKAN;
        this.publishSnapshot();
    }

    /**
     * Samples at most once per configured interval. One due refresh performs
     * at most one real OS probe and, only with the exact live Vulkan owner on
     * its owning thread, at most one real device probe. Any number of F8
     * extractions before the interval expires reads the same cached snapshot.
     * The render-thread production caller is
     * {@code BlockframeEngine.beginFrame()}; the interval gate keeps real
     * OS/driver queries bounded. F8 only reads {@link #snapshot()} and never
     * triggers an OS, driver or Vulkan query.
     */
    public synchronized Snapshot sampleIfDue() {
        if (this.closed || !this.enabled) {
            return this.snapshot;
        }
        if (this.sampling) {
            this.reentrantSkips = incrementSaturated(this.reentrantSkips);
            return this.snapshot;
        }

        this.sampling = true;
        try {
            long now = this.nanoClock.getAsLong();
            long elapsed = now - this.lastSampleNanos;
            if (
                this.sampled
                    && elapsed >= 0L
                    && elapsed < this.sampleIntervalNanos
            ) {
                return this.snapshot;
            }
            this.sampled = true;
            this.lastSampleNanos = now;
            this.refreshes = incrementSaturated(this.refreshes);
            this.sampleRam();
            this.sampleDevice();
            this.publishSnapshot();
            return this.snapshot;
        } finally {
            this.sampling = false;
        }
    }

    public synchronized Snapshot snapshot() {
        return this.snapshot;
    }

    private void sampleRam() {
        if (this.ramProbe == null || this.ramFaulted) {
            return;
        }
        try {
            RamMeasurement measurement = Objects.requireNonNull(
                this.ramProbe.query(),
                "RAM measurement"
            );
            if (
                measurement.totalBytes() <= 0L
                    || measurement.availableBytes() < 0L
                    || measurement.availableBytes() > measurement.totalBytes()
            ) {
                throw new IllegalStateException(
                    "invalid physical RAM measurement"
                );
            }
            this.ramTotalBytes = measurement.totalBytes();
            this.ramAvailableBytes = measurement.availableBytes();
            this.ramStatus = RamStatus.AVAILABLE;
            this.ramSamples = incrementSaturated(this.ramSamples);
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            this.ramFaulted = true;
            this.ramTotalBytes = 0L;
            this.ramAvailableBytes = 0L;
            this.ramStatus = RamStatus.QUERY_FAILED;
            this.ramFailures = incrementSaturated(this.ramFailures);
        }
    }

    private void sampleDevice() {
        DeviceProbe probe = this.deviceProbe;
        if (probe == null) {
            return;
        }
        if (Thread.currentThread() != this.deviceOwnerThread) {
            this.clearDeviceValues();
            this.deviceStatus = DeviceStatus.WRONG_THREAD;
            this.wrongThreadSkips = incrementSaturated(
                this.wrongThreadSkips
            );
            return;
        }
        try {
            DeviceMeasurement measurement = Objects.requireNonNull(
                probe.query(),
                "device-memory measurement"
            );
            if (measurement.deviceLocalHeapCount() <= 0) {
                this.deviceProbe = null;
                this.clearDeviceValues();
                this.deviceStatus = DeviceStatus.NO_DEVICE_LOCAL_HEAP;
                return;
            }
            validateDeviceMeasurement(measurement);
            this.deviceHeapBytes = measurement.heapBytes();
            this.deviceBudgetBytes = measurement.budgetBytes();
            this.deviceUsageBytes = measurement.usageBytes();
            this.deviceHeadroomBytes = measurement.headroomBytes();
            this.deviceLocalHeapCount =
                measurement.deviceLocalHeapCount();
            this.deviceStatus = DeviceStatus.AVAILABLE;
            this.deviceSamples = incrementSaturated(this.deviceSamples);
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            this.deviceProbe = null;
            this.clearDeviceValues();
            this.deviceStatus = DeviceStatus.QUERY_FAILED;
            this.deviceFailures = incrementSaturated(this.deviceFailures);
        }
    }

    private static void validateDeviceMeasurement(
        DeviceMeasurement measurement
    ) {
        if (
            measurement.heapBytes() <= 0L
                || measurement.budgetBytes() <= 0L
                || measurement.budgetBytes() > measurement.heapBytes()
                || measurement.usageBytes() < 0L
                || measurement.headroomBytes() < 0L
                || measurement.headroomBytes()
                    > measurement.budgetBytes()
        ) {
            throw new IllegalStateException(
                "invalid Vulkan memory-budget measurement"
            );
        }
    }

    private void clearDeviceValues() {
        this.deviceHeapBytes = 0L;
        this.deviceBudgetBytes = 0L;
        this.deviceUsageBytes = 0L;
        this.deviceHeadroomBytes = 0L;
        this.deviceLocalHeapCount = 0;
    }

    private void publishSnapshot() {
        this.snapshot = new Snapshot(
            this.ramStatus,
            this.ramTotalBytes,
            this.ramAvailableBytes,
            this.deviceStatus,
            this.deviceHeapBytes,
            this.deviceBudgetBytes,
            this.deviceUsageBytes,
            this.deviceHeadroomBytes,
            this.deviceLocalHeapCount,
            this.refreshes,
            this.ramSamples,
            this.ramFailures,
            this.deviceSamples,
            this.deviceFailures,
            this.wrongThreadSkips,
            this.reentrantSkips,
            this.ownerConflicts,
            this.staleCloseAttempts
        );
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.ramFaulted = true;
        this.deviceOwner = null;
        this.deviceOwnerThread = null;
        this.deviceProbe = null;
        this.ramTotalBytes = 0L;
        this.ramAvailableBytes = 0L;
        this.clearDeviceValues();
        this.ramStatus = RamStatus.CLOSED;
        this.deviceStatus = DeviceStatus.CLOSED;
        this.publishSnapshot();
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    @FunctionalInterface
    public interface RamProbe {
        RamMeasurement query();
    }

    @FunctionalInterface
    public interface DeviceProbe {
        DeviceMeasurement query();
    }

    public record RamMeasurement(long totalBytes, long availableBytes) {
    }

    /**
     * A probe-owned measurement that may be reused between queries.
     * Telemetry copies every primitive before the probe can be queried again.
     */
    public static final class DeviceMeasurement {
        private long heapBytes;
        private long budgetBytes;
        private long usageBytes;
        private long headroomBytes;
        private int deviceLocalHeapCount;

        public DeviceMeasurement(
            long heapBytes,
            long budgetBytes,
            long usageBytes,
            long headroomBytes,
            int deviceLocalHeapCount
        ) {
            this.update(
                heapBytes,
                budgetBytes,
                usageBytes,
                headroomBytes,
                deviceLocalHeapCount
            );
        }

        public DeviceMeasurement update(
            long heapBytes,
            long budgetBytes,
            long usageBytes,
            long headroomBytes,
            int deviceLocalHeapCount
        ) {
            this.heapBytes = heapBytes;
            this.budgetBytes = budgetBytes;
            this.usageBytes = usageBytes;
            this.headroomBytes = headroomBytes;
            this.deviceLocalHeapCount = deviceLocalHeapCount;
            return this;
        }

        public long heapBytes() {
            return this.heapBytes;
        }

        public long budgetBytes() {
            return this.budgetBytes;
        }

        public long usageBytes() {
            return this.usageBytes;
        }

        public long headroomBytes() {
            return this.headroomBytes;
        }

        public int deviceLocalHeapCount() {
            return this.deviceLocalHeapCount;
        }
    }

    public enum RamStatus {
        DISABLED,
        NOT_SAMPLED,
        AVAILABLE,
        UNSUPPORTED,
        QUERY_FAILED,
        CLOSED
    }

    public enum DeviceStatus {
        DISABLED,
        NOT_REQUESTED,
        NOT_VULKAN,
        NOT_SAMPLED,
        AVAILABLE,
        EXTENSION_NOT_ADVERTISED,
        NO_DEVICE_LOCAL_HEAP,
        QUERY_FAILED,
        WRONG_THREAD,
        OWNER_CONFLICT,
        DEVICE_CLOSING,
        CLOSED
    }

    public record Snapshot(
        RamStatus ramStatus,
        long ramTotalBytes,
        long ramAvailableBytes,
        DeviceStatus deviceStatus,
        long deviceHeapBytes,
        long deviceBudgetBytes,
        long deviceUsageBytes,
        long deviceHeadroomBytes,
        int deviceLocalHeapCount,
        long refreshes,
        long ramSamples,
        long ramFailures,
        long deviceSamples,
        long deviceFailures,
        long wrongThreadSkips,
        long reentrantSkips,
        long ownerConflicts,
        long staleCloseAttempts
    ) {
        public Snapshot {
            Objects.requireNonNull(ramStatus, "ramStatus");
            Objects.requireNonNull(deviceStatus, "deviceStatus");
        }
    }
}
