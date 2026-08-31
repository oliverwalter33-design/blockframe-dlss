package de.morau.blockframe.core.diagnostics;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Isolated Phase 1A.4 CPU benchmark for the fixed GPU-submission breadcrumb
 * ring.
 *
 * <p>Both paths consume the same deterministic three-pass frame trace. The
 * control path keeps only a primitive checksum and owns no trace storage.
 * The measured ring path reuses one already-created
 * {@link GpuSubmissionBreadcrumbs}; its setup, snapshots and close remain
 * outside every steady-state measurement window.</p>
 *
 * <p>This benchmark measures CPU bookkeeping only. It does not execute GPU
 * work and makes no renderer, Vulkan-driver, end-to-end or speedup claim.</p>
 */
public final class Phase1a4GpuBreadcrumbsBenchmark {
    private static final int WARMUP_ROUNDS = 5;
    private static final int WARMUP_ITERATIONS = 50_000;
    private static final int SAMPLE_COUNT = 15;
    private static final int MEASURED_ITERATIONS = 100_000;
    private static final int DESTROY_INTERVAL = 64;
    private static final int PASSES_PER_FRAME = 3;
    private static final long EXPECTED_NATIVE_RING_BYTES = 3_072L;
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;
    private static final long COMPLETION_MARKER =
        0x434F4D504C455445L;
    private static final long ABANDONMENT_MARKER =
        0x4142414E444F4E45L;
    private static final String CONTROL_PATH =
        "primitive_checksum_control_no_storage";
    private static final String RING_PATH =
        "gpu_submission_breadcrumbs_native_ring_steady_state";
    private static final String WORKLOAD =
        "three_pass_frame_trace_complete_normally_destroy_every_64th";
    private static final String SCOPE =
        "isolated_cpu_bookkeeping_no_gpu_renderer_or_speedup_claim";

    private static volatile long blackhole;

    private Phase1a4GpuBreadcrumbsBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                "expected output CSV path"
            );
        }

        requireFixedFootprintConstants();
        com.sun.management.ThreadMXBean allocationBean =
            optionalAllocationBean();
        long threadId = Thread.currentThread().threadId();
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs = null;
        boolean breadcrumbsClosed = false;
        boolean managerClosed = false;

        try {
            breadcrumbs = GpuSubmissionBreadcrumbs.tryCreate(budgets);
            if (breadcrumbs == null) {
                throw new IllegalStateException(
                    "GPU breadcrumb ring could not be reserved"
                );
            }
            requireFixedSetupFootprint(budgets.snapshot());

            for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
                long controlChecksum = runControlTrace(
                    WARMUP_ITERATIONS
                );
                long ringChecksum = runRingTrace(
                    breadcrumbs,
                    WARMUP_ITERATIONS
                );
                requireEqualChecksum(
                    "warmup " + (warmup + 1),
                    controlChecksum,
                    ringChecksum
                );
                publish(controlChecksum);
                publish(ringChecksum);
            }

            Samples controlSamples = new Samples();
            Samples ringSamples = new Samples();
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                Measurement control = measureControl(
                    allocationBean,
                    threadId
                );
                Measurement ring = measureRing(
                    breadcrumbs,
                    allocationBean,
                    threadId
                );
                requireEqualChecksum(
                    "measurement " + (sample + 1),
                    control.checksum(),
                    ring.checksum()
                );
                controlSamples.record(sample, control);
                ringSamples.record(sample, ring);
                publish(control.checksum());
                publish(ring.checksum());
            }
            controlSamples.requireStableChecksums(CONTROL_PATH);
            ringSamples.requireStableChecksums(RING_PATH);
            requireEqualChecksum(
                "summary",
                controlSamples.stableChecksum(),
                ringSamples.stableChecksum()
            );
            requireSteadyRingState(breadcrumbs.snapshot());

            breadcrumbs.close();
            breadcrumbsClosed = true;
            GpuSubmissionBreadcrumbs.retryPendingCleanup();
            requireReleasedFootprint(budgets.snapshot());

            budgets.close();
            managerClosed = true;
            requireClosedManager(budgets.snapshot());

            String csv = createCsv(controlSamples, ringSamples);
            Path output = Path.of(arguments[0])
                .toAbsolutePath()
                .normalize();
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, csv, StandardCharsets.UTF_8);
            System.out.print(csv);
            printSummary(CONTROL_PATH, controlSamples);
            printSummary(RING_PATH, ringSamples);
            System.out.println(
                "Thread allocation accounting supported: "
                    + (allocationBean != null)
            );
            System.out.println(
                "Blackhole: " + Long.toUnsignedString(blackhole)
            );
            System.out.println("CSV: " + output);
        } finally {
            if (!breadcrumbsClosed && breadcrumbs != null) {
                breadcrumbs.close();
            }
            GpuSubmissionBreadcrumbs.retryPendingCleanup();
            if (!managerClosed) {
                budgets.close();
            }
        }
    }

    private static Measurement measureControl(
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        long allocatedBefore = allocatedBytes(
            allocationBean,
            threadId
        );
        long started = System.nanoTime();
        long checksum = runControlTrace(MEASURED_ITERATIONS);
        long elapsed = System.nanoTime() - started;
        long allocatedAfter = allocatedBytes(
            allocationBean,
            threadId
        );
        return new Measurement(
            elapsed,
            allocationDelta(allocatedBefore, allocatedAfter),
            checksum
        );
    }

    private static Measurement measureRing(
        GpuSubmissionBreadcrumbs breadcrumbs,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        long allocatedBefore = allocatedBytes(
            allocationBean,
            threadId
        );
        long started = System.nanoTime();
        long checksum = runRingTrace(
            breadcrumbs,
            MEASURED_ITERATIONS
        );
        long elapsed = System.nanoTime() - started;
        long allocatedAfter = allocatedBytes(
            allocationBean,
            threadId
        );
        return new Measurement(
            elapsed,
            allocationDelta(allocatedBefore, allocatedAfter),
            checksum
        );
    }

    /**
     * Executes the trace using primitive locals and checksum state only.
     */
    private static long runControlTrace(int iterations) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < iterations; frame++) {
            long submitIndex = frame & (DESTROY_INTERVAL - 1L);
            boolean destroyed =
                (frame + 1) % DESTROY_INTERVAL == 0;
            checksum = consumeTrace(
                checksum,
                frame,
                submitIndex,
                PASSES_PER_FRAME,
                PASSES_PER_FRAME,
                destroyed
            );
        }
        return checksum;
    }

    /**
     * Executes the identical trace against the pre-created native ring.
     */
    private static long runRingTrace(
        GpuSubmissionBreadcrumbs breadcrumbs,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < iterations; frame++) {
            long submitIndex = frame & (DESTROY_INTERVAL - 1L);
            breadcrumbs.recordEncoded(
                frame,
                GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
            );
            breadcrumbs.recordEncoded(
                frame,
                GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
            );
            int submitted = breadcrumbs.recordSubmit(
                frame,
                submitIndex
            );
            boolean destroyed =
                (frame + 1) % DESTROY_INTERVAL == 0;
            int terminal;
            if (destroyed) {
                breadcrumbs.deviceClosing();
                terminal =
                    breadcrumbs
                        .encoderDestroyedWithoutCompletionProof();
            } else {
                terminal = breadcrumbs.recordCompletion(submitIndex);
            }
            checksum = consumeTrace(
                checksum,
                frame,
                submitIndex,
                submitted,
                terminal,
                destroyed
            );
        }
        return checksum;
    }

    private static long consumeTrace(
        long checksum,
        long frame,
        long submitIndex,
        int submitted,
        int terminal,
        boolean destroyed
    ) {
        long result = mix(checksum, frame);
        result = mix(
            result,
            GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
        );
        result = mix(
            result,
            GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
        );
        result = mix(
            result,
            GpuSubmissionBreadcrumbs.PASS_GRAPHICS_SUBMIT
        );
        result = mix(result, submitIndex);
        result = mix(result, submitted);
        result = mix(
            result,
            destroyed ? ABANDONMENT_MARKER : COMPLETION_MARKER
        );
        return mix(result, terminal);
    }

    private static long mix(long checksum, long value) {
        long mixed = Long.rotateLeft(
            checksum ^ value ^ 0x9E3779B97F4A7C15L,
            23
        );
        return mixed * 0xD6E8FEB86659FD93L;
    }

    private static void publish(long checksum) {
        blackhole = mix(blackhole, checksum);
    }

    private static void requireFixedFootprintConstants() {
        if (
            GpuSubmissionBreadcrumbs.REQUESTED_BYTES
                != EXPECTED_NATIVE_RING_BYTES
                || GpuSubmissionBreadcrumbs.COMMITTED_BYTES
                    != EXPECTED_NATIVE_RING_BYTES
        ) {
            throw new IllegalStateException(
                "benchmark requires a fixed 3072-byte breadcrumb ring"
            );
        }
    }

    private static void requireFixedSetupFootprint(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            snapshot.requestedBytes(MemoryKind.RAM)
                != EXPECTED_NATIVE_RING_BYTES
                || snapshot.usedBytes(MemoryKind.RAM)
                    != EXPECTED_NATIVE_RING_BYTES
                || snapshot.usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.DIAGNOSTICS
                ) != EXPECTED_NATIVE_RING_BYTES
                || snapshot.usedBytes(MemoryKind.VRAM) != 0L
                || snapshot.requestedBytes(MemoryKind.VRAM) != 0L
                || snapshot.outstanding() != 1
                || snapshot.rejections() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "unexpected benchmark setup footprint: " + snapshot
            );
        }
    }

    private static void requireSteadyRingState(
        GpuSubmissionBreadcrumbs.Snapshot snapshot
    ) {
        long ringIterations =
            (long)WARMUP_ROUNDS * WARMUP_ITERATIONS
                + (long)SAMPLE_COUNT * MEASURED_ITERATIONS;
        long expectedRecorded = ringIterations * PASSES_PER_FRAME;
        long expectedAbandoned =
            (
                (long)WARMUP_ROUNDS
                    * (WARMUP_ITERATIONS / DESTROY_INTERVAL)
                    + (long)SAMPLE_COUNT
                        * (MEASURED_ITERATIONS / DESTROY_INTERVAL)
            ) * PASSES_PER_FRAME;
        if (
            snapshot.recorded() != expectedRecorded
                || snapshot.abandoned() != expectedAbandoned
                || snapshot.encodedEntries() != 0
                || snapshot.submittedEntries() != 0
                || snapshot.deviceClosing()
                || snapshot.closed()
        ) {
            throw new IllegalStateException(
                "breadcrumb ring ended outside the deterministic "
                    + "steady-state contract: "
                    + snapshot
            );
        }
    }

    private static void requireReleasedFootprint(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            snapshot.requestedBytes(MemoryKind.RAM) != 0L
                || snapshot.usedBytes(MemoryKind.RAM) != 0L
                || snapshot.requestedBytes(MemoryKind.VRAM) != 0L
                || snapshot.usedBytes(MemoryKind.VRAM) != 0L
                || snapshot.outstanding() != 0
                || snapshot.rejections() != 0L
                || snapshot.staleReleases() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "breadcrumb benchmark resources did not close cleanly: "
                    + snapshot
            );
        }
    }

    private static void requireClosedManager(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            !snapshot.closed()
                || snapshot.outstanding() != 0
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "breadcrumb benchmark manager did not close cleanly: "
                    + snapshot
            );
        }
    }

    private static String createCsv(
        Samples control,
        Samples ring
    ) {
        StringBuilder csv = new StringBuilder(8192);
        csv.append(
            "row_type,path,sample,warmup_rounds,warmup_iterations,"
                + "measurement_samples,iterations,total_nanos,ns_per_op,"
                + "thread_allocated_bytes,"
                + "thread_allocated_bytes_per_op,median_ns_per_op,"
                + "p95_ns_per_op,p99_ns_per_op,"
                + "median_thread_allocated_bytes_per_op,"
                + "p95_thread_allocated_bytes_per_op,"
                + "p99_thread_allocated_bytes_per_op,checksum,workload,"
                + "native_ring_requested_bytes,"
                + "native_ring_committed_bytes,vram_bytes,scope\n"
        );
        appendPath(csv, CONTROL_PATH, control);
        appendPath(csv, RING_PATH, ring);
        return csv.toString();
    }

    private static void appendPath(
        StringBuilder csv,
        String path,
        Samples samples
    ) {
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            Measurement measurement = samples.measurement(sample);
            csv.append("sample,");
            csv.append(path).append(',');
            csv.append(sample + 1).append(',');
            appendExperimentMetadata(csv);
            csv.append(measurement.elapsedNanos()).append(',');
            csv.append(decimal(measurement.nsPerOperation())).append(',');
            csv.append(measurement.allocatedBytes()).append(',');
            csv.append(
                decimal(measurement.allocatedBytesPerOperation())
            ).append(',');
            csv.append(",,,,,,");
            appendSharedTail(csv, path, measurement.checksum());
        }

        csv.append("summary,");
        csv.append(path).append(',');
        csv.append("summary,");
        appendExperimentMetadata(csv);
        csv.append(",,,,");
        csv.append(samples.medianNanosPerOperation()).append(',');
        csv.append(samples.p95NanosPerOperation()).append(',');
        csv.append(samples.p99NanosPerOperation()).append(',');
        csv.append(
            samples.medianAllocatedBytesPerOperation()
        ).append(',');
        csv.append(
            samples.p95AllocatedBytesPerOperation()
        ).append(',');
        csv.append(
            samples.p99AllocatedBytesPerOperation()
        ).append(',');
        appendSharedTail(csv, path, samples.stableChecksum());
    }

    private static void appendExperimentMetadata(StringBuilder csv) {
        csv.append(WARMUP_ROUNDS).append(',');
        csv.append(WARMUP_ITERATIONS).append(',');
        csv.append(SAMPLE_COUNT).append(',');
        csv.append(MEASURED_ITERATIONS).append(',');
    }

    private static void appendSharedTail(
        StringBuilder csv,
        String path,
        long checksum
    ) {
        long nativeRingBytes = RING_PATH.equals(path)
            ? EXPECTED_NATIVE_RING_BYTES
            : 0L;
        csv.append(Long.toUnsignedString(checksum)).append(',');
        csv.append(WORKLOAD).append(',');
        csv.append(nativeRingBytes).append(',');
        csv.append(nativeRingBytes).append(',');
        csv.append(0).append(',');
        csv.append(SCOPE).append('\n');
    }

    private static String decimal(double value) {
        if (value < 0.0D) {
            return "-1";
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static void printSummary(
        String path,
        Samples samples
    ) {
        System.out.printf(
            Locale.ROOT,
            "%s: median=%.3f ns/op, p95=%.3f ns/op, "
                + "p99=%.3f ns/op, median allocation=%.6f B/op, "
                + "checksum=%s%n",
            path,
            samples.medianNanosPerOperation(),
            samples.p95NanosPerOperation(),
            samples.p99NanosPerOperation(),
            samples.medianAllocatedBytesPerOperation(),
            Long.toUnsignedString(samples.stableChecksum())
        );
    }

    private static void requireEqualChecksum(
        String phase,
        long expected,
        long actual
    ) {
        if (expected != actual) {
            throw new IllegalStateException(
                phase
                    + " checksum mismatch: "
                    + Long.toUnsignedString(expected)
                    + " != "
                    + Long.toUnsignedString(actual)
            );
        }
    }

    private static com.sun.management.ThreadMXBean
        optionalAllocationBean() {
        java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();
        if (
            !(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()
        ) {
            return null;
        }
        try {
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
            return allocationBean.isThreadAllocatedMemoryEnabled()
                ? allocationBean
                : null;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static long allocatedBytes(
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        if (allocationBean == null) {
            return -1L;
        }
        long allocated = allocationBean.getThreadAllocatedBytes(threadId);
        return allocated < 0L ? -1L : allocated;
    }

    private static long allocationDelta(long before, long after) {
        if (before < 0L || after < 0L) {
            return -1L;
        }
        return Math.max(0L, after - before);
    }

    private record Measurement(
        long elapsedNanos,
        long allocatedBytes,
        long checksum
    ) {
        private double nsPerOperation() {
            return this.elapsedNanos / (double)MEASURED_ITERATIONS;
        }

        private double allocatedBytesPerOperation() {
            return this.allocatedBytes < 0L
                ? -1.0D
                : this.allocatedBytes / (double)MEASURED_ITERATIONS;
        }
    }

    private static final class Samples {
        private final Measurement[] measurements =
            new Measurement[SAMPLE_COUNT];

        private void record(int sample, Measurement measurement) {
            this.measurements[sample] = measurement;
        }

        private Measurement measurement(int sample) {
            return this.measurements[sample];
        }

        private long stableChecksum() {
            return this.measurements[0].checksum();
        }

        private void requireStableChecksums(String path) {
            long expected = this.stableChecksum();
            for (int sample = 1; sample < SAMPLE_COUNT; sample++) {
                if (this.measurements[sample].checksum() != expected) {
                    throw new IllegalStateException(
                        path
                            + " checksum changed in sample "
                            + (sample + 1)
                    );
                }
            }
        }

        private double medianNanosPerOperation() {
            return percentile(values(false), 0.50D);
        }

        private double p95NanosPerOperation() {
            return percentile(values(false), 0.95D);
        }

        private double p99NanosPerOperation() {
            return percentile(values(false), 0.99D);
        }

        private double medianAllocatedBytesPerOperation() {
            return allocationPercentile(0.50D);
        }

        private double p95AllocatedBytesPerOperation() {
            return allocationPercentile(0.95D);
        }

        private double p99AllocatedBytesPerOperation() {
            return allocationPercentile(0.99D);
        }

        private double allocationPercentile(double percentile) {
            double[] values = values(true);
            for (double value : values) {
                if (value < 0.0D) {
                    return -1.0D;
                }
            }
            return percentile(values, percentile);
        }

        private double[] values(boolean allocation) {
            double[] values = new double[SAMPLE_COUNT];
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                values[sample] = allocation
                    ? this.measurements[sample]
                        .allocatedBytesPerOperation()
                    : this.measurements[sample].nsPerOperation();
            }
            return values;
        }

        private static double percentile(
            double[] values,
            double percentile
        ) {
            Arrays.sort(values);
            int rank = (int)Math.ceil(percentile * values.length);
            return values[Math.max(0, rank - 1)];
        }
    }
}
