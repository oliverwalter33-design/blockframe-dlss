package de.morau.blockframe.benchmark.phase2a0b;

/**
 * One-process topology snapshot. Unknown hardware data is carried as
 * NOT_AVAILABLE and is never inferred from the CPU model string.
 */
public record CpuTopology(
    String status,
    String model,
    int physicalCores,
    int logicalProcessors,
    double smtRatio,
    int jvmAvailableProcessors,
    String windowsProcessorGroups,
    int numaNodes,
    String hybridCoreClasses,
    String processAffinityMask,
    int affinityLogicalProcessors,
    String operatingSystem,
    String jvm,
    String javaVersion
) {
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";

    public boolean physicalCoresAvailable() {
        return this.physicalCores > 0;
    }

    public boolean logicalProcessorsAvailable() {
        return this.logicalProcessors > 0;
    }

    public boolean numaAvailable() {
        return this.numaNodes > 0;
    }
}
