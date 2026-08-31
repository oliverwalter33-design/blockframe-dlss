package de.morau.blockframe.core.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Java-management probe for total and available physical system RAM. */
public final class SystemPhysicalMemoryProbe
    implements PhysicalMemoryTelemetry.RamProbe {
    private final LongSupplier totalBytes;
    private final LongSupplier availableBytes;

    private SystemPhysicalMemoryProbe(
        LongSupplier totalBytes,
        LongSupplier availableBytes
    ) {
        this.totalBytes = Objects.requireNonNull(totalBytes, "totalBytes");
        this.availableBytes = Objects.requireNonNull(
            availableBytes,
            "availableBytes"
        );
    }

    public static SystemPhysicalMemoryProbe tryCreate() {
        java.lang.management.OperatingSystemMXBean base =
            ManagementFactory.getOperatingSystemMXBean();
        if (
            !(base instanceof
                com.sun.management.OperatingSystemMXBean extended)
        ) {
            return null;
        }
        return new SystemPhysicalMemoryProbe(
            extended::getTotalMemorySize,
            extended::getFreeMemorySize
        );
    }

    static SystemPhysicalMemoryProbe fromSuppliers(
        LongSupplier totalBytes,
        LongSupplier availableBytes
    ) {
        return new SystemPhysicalMemoryProbe(totalBytes, availableBytes);
    }

    @Override
    public PhysicalMemoryTelemetry.RamMeasurement query() {
        long total = this.totalBytes.getAsLong();
        long available = this.availableBytes.getAsLong();
        return new PhysicalMemoryTelemetry.RamMeasurement(
            total,
            available
        );
    }
}
