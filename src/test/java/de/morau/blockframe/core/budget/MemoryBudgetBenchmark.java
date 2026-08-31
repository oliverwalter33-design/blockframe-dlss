package de.morau.blockframe.core.budget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reproducible CPU microbenchmark for successful no-eviction steady-state
 * reserve/release cycles. Eviction selection and callbacks are excluded.
 */
public final class MemoryBudgetBenchmark {
    private static final int WARMUP_ITERATIONS = 100_000;
    private static final int MEASURED_ITERATIONS = 1_000_000;
    private static final int SAMPLE_COUNT = 5;
    private static final long LEASE_BYTES = 4096L;

    private MemoryBudgetBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                "expected output CSV path"
            );
        }
        MemoryBudgetManager manager = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        run(manager, WARMUP_ITERATIONS);

        long[] samples = new long[SAMPLE_COUNT];
        long[] allocatedSamples = new long[SAMPLE_COUNT];
        com.sun.management.ThreadMXBean allocationBean = allocationBean();
        long threadId = Thread.currentThread().threadId();
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocationBean == null
                ? -1L
                : allocationBean.getThreadAllocatedBytes(threadId);
            long started = System.nanoTime();
            run(manager, MEASURED_ITERATIONS);
            samples[sample] = System.nanoTime() - started;
            long allocatedAfter = allocationBean == null
                ? -1L
                : allocationBean.getThreadAllocatedBytes(threadId);
            allocatedSamples[sample] = allocationBean == null
                ? -1L
                : Math.max(0L, allocatedAfter - allocatedBefore);
        }
        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        if (snapshot.outstanding() != 0 || snapshot.rejections() != 0L) {
            throw new IllegalStateException(
                "benchmark leaked or rejected a reservation"
            );
        }

        StringBuilder csv = new StringBuilder(
            "operation,sample,iterations,total_nanos,nanos_per_reserve_release,allocated_bytes,allocated_bytes_per_cycle,lease_bytes,used_after,peak,rejections,outstanding\n"
        );
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            appendRow(
                csv,
                "reserve_release_no_eviction",
                sample + 1,
                samples[sample],
                allocatedSamples[sample],
                snapshot
            );
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long[] sortedAllocated = allocatedSamples.clone();
        Arrays.sort(sortedAllocated);
        appendRow(
            csv,
            "reserve_release_no_eviction_median",
            0,
            sorted[sorted.length / 2],
            sortedAllocated[sortedAllocated.length / 2],
            snapshot
        );
        Path output = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        manager.close();
        System.out.print(csv);
    }

    private static void run(
        MemoryBudgetManager manager,
        int iterations
    ) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            long lease = manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.STAGING,
                LEASE_BYTES
            );
            if (lease == 0L || !manager.release(lease)) {
                throw new IllegalStateException(
                    "lease cycle failed at iteration " + iteration
                );
            }
        }
    }

    private static void appendRow(
        StringBuilder csv,
        String operation,
        int sample,
        long elapsed,
        long allocatedBytes,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        csv.append(
            String.format(
                Locale.ROOT,
                "%s,%d,%d,%d,%.3f,%d,%.6f,%d,%d,%d,%d,%d%n",
                operation,
                sample,
                MEASURED_ITERATIONS,
                elapsed,
                elapsed / (double)MEASURED_ITERATIONS,
                allocatedBytes,
                allocatedBytes < 0L
                    ? -1.0D
                    : allocatedBytes / (double)MEASURED_ITERATIONS,
                LEASE_BYTES,
                snapshot.usedBytes(MemoryKind.RAM),
                snapshot.peakBytes(MemoryKind.RAM),
                snapshot.rejections(),
                snapshot.outstanding()
            )
        );
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();
        if (
            !(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()
        ) {
            return null;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }
}
