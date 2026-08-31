package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PhysicalMemoryTelemetryTest {
    @Test
    void explicitlyDisabledOwnerNeverSamplesOrAttaches() {
        PhysicalMemoryTelemetry telemetry =
            PhysicalMemoryTelemetry.createDisabled();

        assertEquals(
            PhysicalMemoryTelemetry.RamStatus.DISABLED,
            telemetry.sampleIfDue().ramStatus()
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.DISABLED,
            telemetry.snapshot().deviceStatus()
        );
        assertFalse(
            telemetry.attachVulkanDevice(
                new Object(),
                true,
                () -> new PhysicalMemoryTelemetry.DeviceMeasurement(
                    1L,
                    1L,
                    0L,
                    1L,
                    1
                )
            )
        );
        assertEquals(0L, telemetry.snapshot().refreshes());
        assertEquals(0L, telemetry.snapshot().ramSamples());
        assertEquals(0L, telemetry.snapshot().deviceSamples());
        telemetry.close();
    }

    private static final long INTERVAL_NANOS = 100L;

    @Test
    void samplesAtTheExactIntervalAndReusesSnapshotBeforeIt() {
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicInteger ramQueries = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> {
                ramQueries.incrementAndGet();
                return ram(32_000L, 12_000L);
            },
            clock::get,
            INTERVAL_NANOS
        );

        PhysicalMemoryTelemetry.Snapshot initial = telemetry.snapshot();
        PhysicalMemoryTelemetry.Snapshot first = telemetry.sampleIfDue();

        assertNotSame(initial, first);
        assertSame(first, telemetry.snapshot());
        assertEquals(1L, first.refreshes());
        assertEquals(1L, first.ramSamples());
        assertEquals(1, ramQueries.get());

        clock.set(1_099L);
        PhysicalMemoryTelemetry.Snapshot cached = telemetry.sampleIfDue();

        assertSame(first, cached);
        assertSame(cached, telemetry.snapshot());
        assertEquals(1, ramQueries.get());

        clock.set(1_100L);
        PhysicalMemoryTelemetry.Snapshot second = telemetry.sampleIfDue();

        assertNotSame(first, second);
        assertEquals(2L, second.refreshes());
        assertEquals(2L, second.ramSamples());
        assertEquals(2, ramQueries.get());
        telemetry.close();
    }

    @Test
    void backwardsClockForcesARefreshInsteadOfThrottlingForever() {
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicInteger queries = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> {
                queries.incrementAndGet();
                return ram(32_000L, 12_000L);
            },
            clock::get,
            INTERVAL_NANOS
        );
        PhysicalMemoryTelemetry.Snapshot first = telemetry.sampleIfDue();

        clock.set(900L);
        PhysicalMemoryTelemetry.Snapshot afterClockReset =
            telemetry.sampleIfDue();

        assertNotSame(first, afterClockReset);
        assertEquals(2L, afterClockReset.refreshes());
        assertEquals(2, queries.get());
        telemetry.close();
    }

    @Test
    void ramFailureDoesNotSuppressDeviceSamplingAndDoesNotRetry() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger ramQueries = new AtomicInteger();
        AtomicInteger deviceQueries = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> {
                ramQueries.incrementAndGet();
                throw new IllegalStateException("RAM unavailable");
            },
            clock::get,
            INTERVAL_NANOS
        );
        Object owner = new Object();
        assertTrue(
            telemetry.attachVulkanDevice(
                owner,
                true,
                () -> {
                    deviceQueries.incrementAndGet();
                    return device(24_000L, 20_000L, 4_000L, 16_000L);
                }
            )
        );

        PhysicalMemoryTelemetry.Snapshot first = telemetry.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.RamStatus.QUERY_FAILED,
            first.ramStatus()
        );
        assertEquals(1L, first.ramFailures());
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE,
            first.deviceStatus()
        );
        assertEquals(1L, first.deviceSamples());

        clock.set(INTERVAL_NANOS);
        PhysicalMemoryTelemetry.Snapshot second = telemetry.sampleIfDue();

        assertEquals(1, ramQueries.get());
        assertEquals(2, deviceQueries.get());
        assertEquals(1L, second.ramFailures());
        assertEquals(2L, second.deviceSamples());
        telemetry.close();
    }

    @Test
    void deviceFailureDoesNotSuppressRamSamplingAndDoesNotRetry() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger ramQueries = new AtomicInteger();
        AtomicInteger deviceQueries = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> {
                ramQueries.incrementAndGet();
                return ram(64_000L, 48_000L);
            },
            clock::get,
            INTERVAL_NANOS
        );
        assertTrue(
            telemetry.attachVulkanDevice(
                new Object(),
                true,
                () -> {
                    deviceQueries.incrementAndGet();
                    throw new LinkageError("driver query unavailable");
                }
            )
        );

        PhysicalMemoryTelemetry.Snapshot first = telemetry.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.RamStatus.AVAILABLE,
            first.ramStatus()
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.QUERY_FAILED,
            first.deviceStatus()
        );
        assertEquals(1L, first.deviceFailures());
        assertEquals(0L, first.deviceHeapBytes());

        clock.set(INTERVAL_NANOS);
        PhysicalMemoryTelemetry.Snapshot second = telemetry.sampleIfDue();

        assertEquals(2, ramQueries.get());
        assertEquals(1, deviceQueries.get());
        assertEquals(2L, second.ramSamples());
        assertEquals(1L, second.deviceFailures());
        telemetry.close();
    }

    @Test
    void wrongThreadSkipsDriverQueryAndOwnerThreadCanRecover()
        throws InterruptedException {
        AtomicLong clock = new AtomicLong();
        AtomicInteger deviceQueries = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> ram(16_000L, 8_000L),
            clock::get,
            INTERVAL_NANOS
        );
        Object owner = new Object();
        assertTrue(
            telemetry.attachVulkanDevice(
                owner,
                true,
                () -> {
                    deviceQueries.incrementAndGet();
                    return device(8_000L, 7_000L, 2_000L, 5_000L);
                }
            )
        );
        AtomicReference<PhysicalMemoryTelemetry.Snapshot> otherSnapshot =
            new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(
            () -> {
                try {
                    otherSnapshot.set(telemetry.sampleIfDue());
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            "physical-memory-wrong-thread-test"
        );

        other.start();
        other.join();

        assertEquals(null, failure.get());
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.WRONG_THREAD,
            otherSnapshot.get().deviceStatus()
        );
        assertEquals(1L, otherSnapshot.get().wrongThreadSkips());
        assertEquals(0, deviceQueries.get());

        clock.set(INTERVAL_NANOS);
        PhysicalMemoryTelemetry.Snapshot recovered =
            telemetry.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE,
            recovered.deviceStatus()
        );
        assertEquals(1L, recovered.deviceSamples());
        assertEquals(1L, recovered.wrongThreadSkips());
        assertEquals(1, deviceQueries.get());
        telemetry.close();
    }

    @Test
    void reentrantSamplingReturnsPublishedSnapshotAndOuterSampleCompletes() {
        AtomicReference<PhysicalMemoryTelemetry> owner =
            new AtomicReference<>();
        AtomicReference<PhysicalMemoryTelemetry.Snapshot> nested =
            new AtomicReference<>();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> ram(32_000L, 24_000L),
            () -> 10L,
            INTERVAL_NANOS
        );
        owner.set(telemetry);
        Object deviceOwner = new Object();
        assertTrue(
            telemetry.attachVulkanDevice(
                deviceOwner,
                true,
                () -> {
                    nested.set(owner.get().sampleIfDue());
                    return device(12_000L, 10_000L, 3_000L, 7_000L);
                }
            )
        );
        PhysicalMemoryTelemetry.Snapshot before = telemetry.snapshot();

        PhysicalMemoryTelemetry.Snapshot sampled = telemetry.sampleIfDue();

        assertSame(before, nested.get());
        assertNotSame(before, sampled);
        assertEquals(1L, sampled.reentrantSkips());
        assertEquals(1L, sampled.refreshes());
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE,
            sampled.deviceStatus()
        );
        telemetry.close();
    }

    @Test
    void reentrantClockCannotEnterBeforeTheSamplingGuard() {
        AtomicReference<PhysicalMemoryTelemetry> owner =
            new AtomicReference<>();
        AtomicReference<PhysicalMemoryTelemetry.Snapshot> nested =
            new AtomicReference<>();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> ram(32_000L, 24_000L),
            () -> {
                nested.set(owner.get().sampleIfDue());
                return 10L;
            },
            INTERVAL_NANOS
        );
        owner.set(telemetry);
        PhysicalMemoryTelemetry.Snapshot before = telemetry.snapshot();

        PhysicalMemoryTelemetry.Snapshot sampled = telemetry.sampleIfDue();

        assertSame(before, nested.get());
        assertNotSame(before, sampled);
        assertEquals(1L, sampled.reentrantSkips());
        assertEquals(1L, sampled.refreshes());
        telemetry.close();
    }

    @Test
    void ownerConflictAndStaleCloseCannotReplaceOrClearLiveOwner() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger firstQueries = new AtomicInteger();
        AtomicInteger secondQueries = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> ram(32_000L, 16_000L),
            clock::get,
            INTERVAL_NANOS
        );
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        assertTrue(
            telemetry.attachVulkanDevice(
                firstOwner,
                true,
                () -> {
                    firstQueries.incrementAndGet();
                    return device(10_000L, 9_000L, 1_000L, 8_000L);
                }
            )
        );

        assertFalse(
            telemetry.attachVulkanDevice(
                secondOwner,
                true,
                () -> {
                    secondQueries.incrementAndGet();
                    return device(20_000L, 18_000L, 2_000L, 16_000L);
                }
            )
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.OWNER_CONFLICT,
            telemetry.snapshot().deviceStatus()
        );
        assertEquals(1L, telemetry.snapshot().ownerConflicts());
        assertFalse(telemetry.vulkanDeviceClosing(secondOwner));
        assertEquals(1L, telemetry.snapshot().staleCloseAttempts());
        assertTrue(telemetry.vulkanDeviceClosing(firstOwner));

        assertTrue(
            telemetry.attachVulkanDevice(
                secondOwner,
                true,
                () -> {
                    secondQueries.incrementAndGet();
                    return device(20_000L, 18_000L, 2_000L, 16_000L);
                }
            )
        );
        assertFalse(telemetry.vulkanDeviceClosing(firstOwner));
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.NOT_SAMPLED,
            telemetry.snapshot().deviceStatus()
        );
        PhysicalMemoryTelemetry.Snapshot live =
            telemetry.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE,
            live.deviceStatus()
        );
        assertEquals(0, firstQueries.get());
        assertEquals(1, secondQueries.get());
        assertEquals(2L, live.staleCloseAttempts());
        telemetry.close();
    }

    @Test
    void detachBackendResetAndCloseClearAllBorrowedDeviceValues() {
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> ram(64_000L, 32_000L),
            () -> 0L,
            INTERVAL_NANOS
        );
        Object owner = new Object();
        assertTrue(
            telemetry.attachVulkanDevice(
                owner,
                true,
                () -> device(24_000L, 20_000L, 5_000L, 15_000L)
            )
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE,
            telemetry.sampleIfDue().deviceStatus()
        );

        assertTrue(telemetry.vulkanDeviceClosing(owner));
        assertDeviceValuesCleared(telemetry.snapshot());
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.DEVICE_CLOSING,
            telemetry.snapshot().deviceStatus()
        );

        telemetry.notVulkanBackend();
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.NOT_VULKAN,
            telemetry.snapshot().deviceStatus()
        );
        assertDeviceValuesCleared(telemetry.snapshot());

        telemetry.close();
        PhysicalMemoryTelemetry.Snapshot closed = telemetry.snapshot();
        assertEquals(
            PhysicalMemoryTelemetry.RamStatus.CLOSED,
            closed.ramStatus()
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.CLOSED,
            closed.deviceStatus()
        );
        assertEquals(0L, closed.ramTotalBytes());
        assertEquals(0L, closed.ramAvailableBytes());
        assertDeviceValuesCleared(closed);

        telemetry.close();
        assertSame(closed, telemetry.snapshot());
        assertSame(closed, telemetry.sampleIfDue());
        assertFalse(
            telemetry.attachVulkanDevice(
                new Object(),
                true,
                () -> device(1L, 1L, 0L, 1L)
            )
        );
    }

    @Test
    void unsupportedOrInvalidInputsPublishNoNumericDeviceValue() {
        AtomicInteger unusedProbe = new AtomicInteger();
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            null,
            () -> 0L,
            INTERVAL_NANOS
        );
        Object owner = new Object();

        assertTrue(
            telemetry.attachVulkanDevice(
                owner,
                false,
                () -> {
                    unusedProbe.incrementAndGet();
                    return device(1L, 1L, 0L, 1L);
                }
            )
        );
        PhysicalMemoryTelemetry.Snapshot unsupported =
            telemetry.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.RamStatus.UNSUPPORTED,
            unsupported.ramStatus()
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus
                .EXTENSION_NOT_ADVERTISED,
            unsupported.deviceStatus()
        );
        assertEquals(0, unusedProbe.get());
        assertDeviceValuesCleared(unsupported);
        assertTrue(telemetry.vulkanDeviceClosing(owner));
        telemetry.close();

        PhysicalMemoryTelemetry invalid = new PhysicalMemoryTelemetry(
            () -> ram(100L, 101L),
            () -> 0L,
            INTERVAL_NANOS
        );
        assertTrue(
            invalid.attachVulkanDevice(
                new Object(),
                true,
                () -> device(100L, 101L, 0L, 101L)
            )
        );
        PhysicalMemoryTelemetry.Snapshot rejected =
            invalid.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.RamStatus.QUERY_FAILED,
            rejected.ramStatus()
        );
        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.QUERY_FAILED,
            rejected.deviceStatus()
        );
        assertEquals(1L, rejected.ramFailures());
        assertEquals(1L, rejected.deviceFailures());
        assertDeviceValuesCleared(rejected);
        invalid.close();
    }

    @Test
    void overBudgetUsageRetainsUsageAndRequiresZeroHeadroom() {
        PhysicalMemoryTelemetry telemetry = new PhysicalMemoryTelemetry(
            () -> ram(1_000L, 500L),
            () -> 0L,
            INTERVAL_NANOS
        );
        assertTrue(
            telemetry.attachVulkanDevice(
                new Object(),
                true,
                () -> device(2_000L, 1_500L, 1_700L, 0L)
            )
        );

        PhysicalMemoryTelemetry.Snapshot snapshot =
            telemetry.sampleIfDue();

        assertEquals(
            PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE,
            snapshot.deviceStatus()
        );
        assertEquals(1_700L, snapshot.deviceUsageBytes());
        assertEquals(0L, snapshot.deviceHeadroomBytes());
        telemetry.close();
    }

    private static PhysicalMemoryTelemetry.RamMeasurement ram(
        long total,
        long available
    ) {
        return new PhysicalMemoryTelemetry.RamMeasurement(total, available);
    }

    private static PhysicalMemoryTelemetry.DeviceMeasurement device(
        long heap,
        long budget,
        long usage,
        long headroom
    ) {
        return new PhysicalMemoryTelemetry.DeviceMeasurement(
            heap,
            budget,
            usage,
            headroom,
            1
        );
    }

    private static void assertDeviceValuesCleared(
        PhysicalMemoryTelemetry.Snapshot snapshot
    ) {
        assertEquals(0L, snapshot.deviceHeapBytes());
        assertEquals(0L, snapshot.deviceBudgetBytes());
        assertEquals(0L, snapshot.deviceUsageBytes());
        assertEquals(0L, snapshot.deviceHeadroomBytes());
        assertEquals(0, snapshot.deviceLocalHeapCount());
    }
}
