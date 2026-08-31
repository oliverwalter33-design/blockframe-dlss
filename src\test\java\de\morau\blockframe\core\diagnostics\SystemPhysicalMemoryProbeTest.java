package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SystemPhysicalMemoryProbeTest {
    @Test
    void supplierBackedProbeReadsEachOsValueExactlyOnce() {
        AtomicInteger totalReads = new AtomicInteger();
        AtomicInteger availableReads = new AtomicInteger();
        SystemPhysicalMemoryProbe probe =
            SystemPhysicalMemoryProbe.fromSuppliers(
                () -> {
                    totalReads.incrementAndGet();
                    return 64L << 30;
                },
                () -> {
                    availableReads.incrementAndGet();
                    return 24L << 30;
                }
            );

        PhysicalMemoryTelemetry.RamMeasurement measurement =
            probe.query();

        assertEquals(64L << 30, measurement.totalBytes());
        assertEquals(24L << 30, measurement.availableBytes());
        assertEquals(1, totalReads.get());
        assertEquals(1, availableReads.get());
    }

    @Test
    void supplierFailureIsLeftForTelemetryToFailClosed() {
        SystemPhysicalMemoryProbe probe =
            SystemPhysicalMemoryProbe.fromSuppliers(
                () -> {
                    throw new IllegalStateException("OS total unavailable");
                },
                () -> 1L
            );

        assertThrows(IllegalStateException.class, probe::query);
    }

    @Test
    void nullSuppliersAreRejectedAtConstruction() {
        assertThrows(
            NullPointerException.class,
            () -> SystemPhysicalMemoryProbe.fromSuppliers(null, () -> 1L)
        );
        assertThrows(
            NullPointerException.class,
            () -> SystemPhysicalMemoryProbe.fromSuppliers(() -> 1L, null)
        );
    }

    @Test
    void java25RuntimeProbeIsEitherExplicitlyUnsupportedOrSane() {
        SystemPhysicalMemoryProbe probe =
            SystemPhysicalMemoryProbe.tryCreate();
        if (probe == null) {
            return;
        }

        PhysicalMemoryTelemetry.RamMeasurement measurement =
            probe.query();

        assertNotNull(measurement);
        assertTrue(measurement.totalBytes() > 0L);
        assertTrue(measurement.availableBytes() >= 0L);
        assertTrue(
            measurement.availableBytes() <= measurement.totalBytes()
        );
    }
}
