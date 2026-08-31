package de.morau.blockframe.core.memory;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fork-isolated Phase 1A.6 CPU benchmark for the productive shader-staging
 * pool's late-registered eviction callback.
 *
 * <p>Every comparison worker is a fresh JVM. Paired worker launch order
 * alternates between an unregistered control pool and an otherwise identical
 * pool with one registered eviction callback. The measured borrow, buffer and
 * release trace is identical and does not invoke eviction. LRU touch and
 * successful pressure-driven eviction are reported separately.</p>
 *
 * <p>This benchmark does not launch Minecraft, execute Vulkan or GPU work,
 * load SPIR-V, or measure whole-process RSS. The production pool is
 * setup/reload-scoped, so none of these rows is a renderer-frame claim.</p>
 */
public final class Phase1a6EvictableStagingBenchmark {
    private static final int DEFAULT_FORKS = 5;
    private static final int WARMUP_ROUNDS = 5;
    private static final int WARMUP_ITERATIONS = 200_000;
    private static final int SAMPLE_COUNT = 21;
    private static final int MEASURED_ITERATIONS = 1_000_000;
    private static final int BLOCK_BYTES = 32 * 1024;
    private static final int REQUIRED_BYTES = 18_380;
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;
    private static final String CONTROL_WORKER = "control";
    private static final String EVICTABLE_WORKER = "evictable";
    private static final String CONTROL_SCENARIO =
        "borrow_buffer_release_unregistered_control";
    private static final String EVICTABLE_SCENARIO =
        "borrow_buffer_release_registered_evictable";
    private static final String TOUCH_SCENARIO =
        "registered_lease_setup_touch";
    private static final String EVICTION_SCENARIO =
        "pressure_evict_close_32768_bytes";
    private static final String HEADER =
        "fork,order,worker,scenario,statistic,samples,"
            + "operations_per_sample,ns_per_op,allocated_bytes_per_op,"
            + "gc_collections,gc_pause_ms,checksum,reclaimed_bytes";

    private static volatile long blackhole;

    private Phase1a6EvictableStagingBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "--worker".equals(arguments[0])) {
            runWorker(arguments);
            return;
        }
        runCoordinator(arguments);
    }

    private static void runCoordinator(String[] arguments)
        throws Exception {
        if (arguments.length < 1 || arguments.length > 2) {
            throw new IllegalArgumentException(
                "expected output CSV path and optional fork count"
            );
        }
        Path output = Path.of(arguments[0])
            .toAbsolutePath()
            .normalize();
        int forks = arguments.length == 2
            ? Integer.parseInt(arguments[1])
            : DEFAULT_FORKS;
        if (forks < 2) {
            throw new IllegalArgumentException(
                "at least two fresh JVM forks are required"
            );
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path workerDirectory = output.resolveSibling(
            output.getFileName() + ".workers-" + System.nanoTime()
        );
        Files.createDirectories(workerDirectory);

        List<Sample> samples = new ArrayList<>();
        for (int fork = 1; fork <= forks; fork++) {
            boolean controlFirst = (fork & 1) != 0;
            String order = controlFirst
                ? "control_then_evictable"
                : "evictable_then_control";
            String first = controlFirst
                ? CONTROL_WORKER
                : EVICTABLE_WORKER;
            String second = controlFirst
                ? EVICTABLE_WORKER
                : CONTROL_WORKER;
            samples.addAll(
                runFreshWorker(
                    workerDirectory,
                    fork,
                    order,
                    first
                )
            );
            samples.addAll(
                runFreshWorker(
                    workerDirectory,
                    fork,
                    order,
                    second
                )
            );
        }

        validateCoordinatorEvidence(samples, forks);
        String csv = createFinalCsv(samples);
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        printSummary(samples, CONTROL_SCENARIO);
        printSummary(samples, EVICTABLE_SCENARIO);
        printSummary(samples, TOUCH_SCENARIO);
        printSummary(samples, EVICTION_SCENARIO);
        double ratio =
            percentile(values(samples, EVICTABLE_SCENARIO, false), 0.50)
                / percentile(
                    values(samples, CONTROL_SCENARIO, false),
                    0.50
                );
        System.out.printf(
            Locale.ROOT,
            "Registered/control borrow p50 ratio: %.6fx%n",
            ratio
        );
        System.out.println(
            "Scope: isolated CPU/setup paths; no Minecraft, Vulkan, GPU, "
                + "renderer-frame, RSS or end-to-end claim"
        );
        System.out.println(
            "Blackhole: " + Long.toUnsignedString(blackhole)
        );
        System.out.println("CSV: " + output);
        System.out.println("Worker evidence: " + workerDirectory);
    }

    private static List<Sample> runFreshWorker(
        Path workerDirectory,
        int fork,
        String order,
        String worker
    ) throws Exception {
        Path workerOutput = workerDirectory.resolve(
            String.format(
                Locale.ROOT,
                "fork-%02d-%s.csv",
                fork,
                worker
            )
        );
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Phase1a6EvictableStagingBenchmark.class.getName());
        command.add("--worker");
        command.add(workerOutput.toString());
        command.add(Integer.toString(fork));
        command.add(order);
        command.add(worker);

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        String processOutput = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                "benchmark worker failed with exit "
                    + exitCode
                    + System.lineSeparator()
                    + processOutput
            );
        }
        if (!Files.isRegularFile(workerOutput)) {
            throw new IllegalStateException(
                "benchmark worker did not publish " + workerOutput
            );
        }
        return readWorkerSamples(workerOutput);
    }

    private static void runWorker(String[] arguments) throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                "worker expects output, fork, order and worker kind"
            );
        }
        Path output = Path.of(arguments[1])
            .toAbsolutePath()
            .normalize();
        int fork = Integer.parseInt(arguments[2]);
        String order = arguments[3];
        String worker = arguments[4];
        if (
            !CONTROL_WORKER.equals(worker)
                && !EVICTABLE_WORKER.equals(worker)
        ) {
            throw new IllegalArgumentException(
                "unknown worker kind " + worker
            );
        }

        com.sun.management.ThreadMXBean allocationBean =
            optionalAllocationBean();
        long threadId = Thread.currentThread().threadId();
        List<Sample> samples = new ArrayList<>();
        if (CONTROL_WORKER.equals(worker)) {
            samples.addAll(
                measureBorrowScenario(
                    fork,
                    order,
                    worker,
                    false,
                    allocationBean,
                    threadId
                )
            );
        } else {
            samples.addAll(
                measureBorrowScenario(
                    fork,
                    order,
                    worker,
                    true,
                    allocationBean,
                    threadId
                )
            );
            samples.addAll(
                measureTouchScenario(
                    fork,
                    order,
                    worker,
                    allocationBean,
                    threadId
                )
            );
            samples.addAll(
                measureEvictionScenario(
                    fork,
                    order,
                    worker,
                    allocationBean,
                    threadId
                )
            );
        }

        StringBuilder csv = new StringBuilder(4_096);
        csv.append(HEADER).append('\n');
        for (Sample sample : samples) {
            appendSample(csv, sample);
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        System.out.println(
            "worker "
                + worker
                + " fork "
                + fork
                + " wrote "
                + samples.size()
                + " samples"
        );
    }

    private static List<Sample> measureBorrowScenario(
        int fork,
        String order,
        String worker,
        boolean registered,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                BLOCK_BYTES
            );
        if (pool == null) {
            throw new IllegalStateException(
                "benchmark pool reservation was rejected"
            );
        }
        if (
            registered
                && !pool.registerEvictable(
                    () -> {
                        pool.close();
                        return true;
                    }
                )
        ) {
            throw new IllegalStateException(
                "benchmark eviction registration was rejected"
            );
        }
        String scenario = registered
            ? EVICTABLE_SCENARIO
            : CONTROL_SCENARIO;
        try {
            for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
                publish(runBorrowTrace(pool, WARMUP_ITERATIONS));
            }
            List<Sample> samples = new ArrayList<>(SAMPLE_COUNT);
            String stableChecksum = null;
            for (int sample = 1; sample <= SAMPLE_COUNT; sample++) {
                Measurement measurement = measure(
                    allocationBean,
                    threadId,
                    () -> runBorrowTrace(
                        pool,
                        MEASURED_ITERATIONS
                    )
                );
                stableChecksum = requireStableChecksum(
                    scenario,
                    stableChecksum,
                    measurement.checksum()
                );
                samples.add(
                    Sample.measured(
                        fork,
                        order,
                        worker,
                        scenario,
                        sample,
                        MEASURED_ITERATIONS,
                        measurement,
                        0L
                    )
                );
                publish(measurement.checksum());
            }
            if (pool.outstandingBorrows() != 0) {
                throw new IllegalStateException(
                    "measured pool retained a borrow"
                );
            }
            return samples;
        } finally {
            pool.close();
            budgets.close();
            requireCleanClosedManager(budgets.snapshot());
        }
    }

    private static List<Sample> measureTouchScenario(
        int fork,
        String order,
        String worker,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                BLOCK_BYTES
            );
        if (
            pool == null
                || !pool.registerEvictable(
                    () -> {
                        pool.close();
                        return true;
                    }
                )
        ) {
            throw new IllegalStateException(
                "touch benchmark setup failed"
            );
        }
        try {
            for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
                publish(runTouchTrace(pool, WARMUP_ITERATIONS));
            }
            List<Sample> samples = new ArrayList<>(SAMPLE_COUNT);
            String stableChecksum = null;
            for (int sample = 1; sample <= SAMPLE_COUNT; sample++) {
                Measurement measurement = measure(
                    allocationBean,
                    threadId,
                    () -> runTouchTrace(
                        pool,
                        MEASURED_ITERATIONS
                    )
                );
                stableChecksum = requireStableChecksum(
                    TOUCH_SCENARIO,
                    stableChecksum,
                    measurement.checksum()
                );
                samples.add(
                    Sample.measured(
                        fork,
                        order,
                        worker,
                        TOUCH_SCENARIO,
                        sample,
                        MEASURED_ITERATIONS,
                        measurement,
                        0L
                    )
                );
                publish(measurement.checksum());
            }
            return samples;
        } finally {
            pool.close();
            budgets.close();
            requireCleanClosedManager(budgets.snapshot());
        }
    }

    private static List<Sample> measureEvictionScenario(
        int fork,
        String order,
        String worker,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
            Sample ignored = measureOneEviction(
                fork,
                order,
                worker,
                0,
                null,
                threadId
            );
            publish(Long.parseUnsignedLong(ignored.checksum()));
        }
        List<Sample> samples = new ArrayList<>(SAMPLE_COUNT);
        String stableChecksum = null;
        for (int sample = 1; sample <= SAMPLE_COUNT; sample++) {
            Sample measured = measureOneEviction(
                fork,
                order,
                worker,
                sample,
                allocationBean,
                threadId
            );
            stableChecksum = requireStableChecksum(
                EVICTION_SCENARIO,
                stableChecksum,
                Long.parseUnsignedLong(measured.checksum())
            );
            samples.add(measured);
            publish(Long.parseUnsignedLong(measured.checksum()));
        }
        return samples;
    }

    private static Sample measureOneEviction(
        int fork,
        String order,
        String worker,
        int sample,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            evictionSettings()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                BLOCK_BYTES
            );
        if (
            pool == null
                || !pool.registerEvictable(
                    () -> {
                        pool.close();
                        return true;
                    }
                )
        ) {
            throw new IllegalStateException(
                "eviction benchmark setup failed"
            );
        }

        GcSnapshot gcBefore = gcSnapshot();
        long allocationBefore = allocatedBytes(allocationBean, threadId);
        long started = System.nanoTime();
        long replacement = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            BLOCK_BYTES
        );
        long elapsed = System.nanoTime() - started;
        long allocationAfter = allocatedBytes(allocationBean, threadId);
        GcSnapshot gcAfter = gcSnapshot();
        long checksum = mix(CHECKSUM_SEED, replacement);
        Measurement measurement = new Measurement(
            elapsed,
            allocationDelta(allocationBefore, allocationAfter),
            gcAfter.collections() - gcBefore.collections(),
            gcAfter.pauseMillis() - gcBefore.pauseMillis(),
            checksum
        );

        MemoryBudgetManager.Snapshot afterEviction = budgets.snapshot();
        if (
            replacement == 0L
                || afterEviction.evictions() != 1L
                || afterEviction.reclaimedBytes() != BLOCK_BYTES
                || afterEviction.usedBytes(MemoryKind.RAM)
                    != BLOCK_BYTES
                || afterEviction.outstanding() != 1
        ) {
            throw new IllegalStateException(
                "eviction did not reclaim exactly one 32768-byte pool"
            );
        }
        if (!budgets.release(replacement)) {
            throw new IllegalStateException(
                "replacement lease could not be released"
            );
        }
        budgets.close();
        requireCleanClosedManager(budgets.snapshot());
        return Sample.measured(
            fork,
            order,
            worker,
            EVICTION_SCENARIO,
            sample,
            1,
            measurement,
            BLOCK_BYTES
        );
    }

    private static long runBorrowTrace(
        ReusableNativeBlockPool pool,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int iteration = 0; iteration < iterations; iteration++) {
            long token = pool.tryBorrow(REQUIRED_BYTES);
            if (token == 0L) {
                throw new IllegalStateException(
                    "native staging pool unexpectedly exhausted"
                );
            }
            try {
                ByteBuffer buffer = pool.buffer(
                    token,
                    REQUIRED_BYTES
                );
                long value = mix(iteration, checksum);
                buffer.putLong(0, value);
                buffer.putInt(
                    REQUIRED_BYTES - Integer.BYTES,
                    iteration
                );
                checksum = mix(
                    checksum,
                    buffer.getLong(0)
                        ^ Integer.toUnsignedLong(
                            buffer.getInt(
                                REQUIRED_BYTES - Integer.BYTES
                            )
                        )
                );
            } finally {
                pool.release(token);
            }
        }
        return checksum;
    }

    private static long runTouchTrace(
        ReusableNativeBlockPool pool,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int iteration = 0; iteration < iterations; iteration++) {
            if (!pool.touchLease()) {
                throw new IllegalStateException(
                    "registered setup lease touch was rejected"
                );
            }
            checksum = mix(checksum, iteration);
        }
        return checksum;
    }

    private static Measurement measure(
        com.sun.management.ThreadMXBean allocationBean,
        long threadId,
        Trace trace
    ) {
        GcSnapshot gcBefore = gcSnapshot();
        long allocationBefore = allocatedBytes(allocationBean, threadId);
        long started = System.nanoTime();
        long checksum = trace.run();
        long elapsed = System.nanoTime() - started;
        long allocationAfter = allocatedBytes(allocationBean, threadId);
        GcSnapshot gcAfter = gcSnapshot();
        return new Measurement(
            elapsed,
            allocationDelta(allocationBefore, allocationAfter),
            gcAfter.collections() - gcBefore.collections(),
            gcAfter.pauseMillis() - gcBefore.pauseMillis(),
            checksum
        );
    }

    private static void validateCoordinatorEvidence(
        List<Sample> samples,
        int forks
    ) {
        int expectedBorrowSamples = forks * SAMPLE_COUNT;
        requireSampleCount(
            samples,
            CONTROL_SCENARIO,
            expectedBorrowSamples
        );
        requireSampleCount(
            samples,
            EVICTABLE_SCENARIO,
            expectedBorrowSamples
        );
        requireSampleCount(
            samples,
            TOUCH_SCENARIO,
            expectedBorrowSamples
        );
        requireSampleCount(
            samples,
            EVICTION_SCENARIO,
            expectedBorrowSamples
        );
        String controlChecksum = stableChecksum(
            samples,
            CONTROL_SCENARIO
        );
        String evictableChecksum = stableChecksum(
            samples,
            EVICTABLE_SCENARIO
        );
        if (!controlChecksum.equals(evictableChecksum)) {
            throw new IllegalStateException(
                "registered and control borrow checksums differ"
            );
        }
        for (Sample sample : samples) {
            if (
                EVICTION_SCENARIO.equals(sample.scenario())
                    && sample.reclaimedBytes() != BLOCK_BYTES
            ) {
                throw new IllegalStateException(
                    "an eviction sample did not reclaim 32768 bytes"
                );
            }
        }
    }

    private static String createFinalCsv(List<Sample> samples) {
        StringBuilder csv = new StringBuilder(32_768);
        csv.append(HEADER).append('\n');
        for (Sample sample : samples) {
            appendSample(csv, sample);
        }
        for (
            String scenario : List.of(
                CONTROL_SCENARIO,
                EVICTABLE_SCENARIO,
                TOUCH_SCENARIO,
                EVICTION_SCENARIO
            )
        ) {
            appendSummary(csv, samples, scenario, "p50", 0.50);
            appendSummary(csv, samples, scenario, "p95", 0.95);
            appendSummary(csv, samples, scenario, "p99", 0.99);
        }
        double ratio =
            percentile(values(samples, EVICTABLE_SCENARIO, false), 0.50)
                / percentile(
                    values(samples, CONTROL_SCENARIO, false),
                    0.50
                );
        csv.append(
            "all,mixed,mixed,registered_vs_control_borrow,"
        );
        csv.append("p50_ratio,")
            .append(
                values(
                    samples,
                    EVICTABLE_SCENARIO,
                    false
                ).size()
            )
            .append(',')
            .append(MEASURED_ITERATIONS)
            .append(',');
        appendDouble(csv, ratio);
        csv.append(",0,")
            .append(totalGcCollections(samples, EVICTABLE_SCENARIO))
            .append(',')
            .append(totalGcPause(samples, EVICTABLE_SCENARIO))
            .append(',')
            .append(stableChecksum(samples, EVICTABLE_SCENARIO))
            .append(",0\n");
        return csv.toString();
    }

    private static void appendSample(
        StringBuilder csv,
        Sample sample
    ) {
        csv.append(sample.fork())
            .append(',')
            .append(sample.order())
            .append(',')
            .append(sample.worker())
            .append(',')
            .append(sample.scenario())
            .append(',')
            .append("sample_")
            .append(sample.sample())
            .append(",1,")
            .append(sample.operations())
            .append(',');
        appendDouble(csv, sample.nsPerOperation());
        csv.append(',');
        appendDouble(csv, sample.allocatedBytesPerOperation());
        csv.append(',')
            .append(sample.gcCollections())
            .append(',')
            .append(sample.gcPauseMillis())
            .append(',')
            .append(sample.checksum())
            .append(',')
            .append(sample.reclaimedBytes())
            .append('\n');
    }

    private static void appendSummary(
        StringBuilder csv,
        List<Sample> samples,
        String scenario,
        String statistic,
        double percentile
    ) {
        List<Double> timings = values(samples, scenario, false);
        List<Double> allocations = values(samples, scenario, true);
        Sample first = firstSample(samples, scenario);
        csv.append("all,mixed,mixed,")
            .append(scenario)
            .append(',')
            .append(statistic)
            .append(',')
            .append(timings.size())
            .append(',')
            .append(first.operations())
            .append(',');
        appendDouble(csv, percentile(timings, percentile));
        csv.append(',');
        appendDouble(csv, percentile(allocations, percentile));
        csv.append(',')
            .append(totalGcCollections(samples, scenario))
            .append(',')
            .append(totalGcPause(samples, scenario))
            .append(',')
            .append(first.checksum())
            .append(',')
            .append(first.reclaimedBytes())
            .append('\n');
    }

    private static List<Sample> readWorkerSamples(Path workerOutput)
        throws IOException {
        List<String> lines = Files.readAllLines(
            workerOutput,
            StandardCharsets.UTF_8
        );
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw new IllegalStateException(
                "worker CSV has an invalid header: " + workerOutput
            );
        }
        List<Sample> samples = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(",", -1);
            if (fields.length != 13) {
                throw new IllegalStateException(
                    "worker CSV row has "
                        + fields.length
                        + " fields: "
                        + line
                );
            }
            String statistic = fields[4];
            if (!statistic.startsWith("sample_")) {
                throw new IllegalStateException(
                    "worker CSV contains a non-sample row"
                );
            }
            samples.add(
                new Sample(
                    Integer.parseInt(fields[0]),
                    fields[1],
                    fields[2],
                    fields[3],
                    Integer.parseInt(
                        statistic.substring("sample_".length())
                    ),
                    Integer.parseInt(fields[6]),
                    Double.parseDouble(fields[7]),
                    Double.parseDouble(fields[8]),
                    Long.parseLong(fields[9]),
                    Long.parseLong(fields[10]),
                    fields[11],
                    Long.parseLong(fields[12])
                )
            );
        }
        return samples;
    }

    private static void requireSampleCount(
        List<Sample> samples,
        String scenario,
        int expected
    ) {
        long count = samples
            .stream()
            .filter(sample -> scenario.equals(sample.scenario()))
            .count();
        if (count != expected) {
            throw new IllegalStateException(
                scenario
                    + " has "
                    + count
                    + " samples, expected "
                    + expected
            );
        }
    }

    private static String stableChecksum(
        List<Sample> samples,
        String scenario
    ) {
        String checksum = null;
        for (Sample sample : samples) {
            if (!scenario.equals(sample.scenario())) {
                continue;
            }
            if (checksum == null) {
                checksum = sample.checksum();
            } else if (!checksum.equals(sample.checksum())) {
                throw new IllegalStateException(
                    scenario + " has unstable checksums"
                );
            }
        }
        if (checksum == null) {
            throw new IllegalStateException(
                scenario + " has no checksum"
            );
        }
        return checksum;
    }

    private static String requireStableChecksum(
        String scenario,
        String current,
        long observed
    ) {
        String value = Long.toUnsignedString(observed);
        if (current != null && !current.equals(value)) {
            throw new IllegalStateException(
                scenario + " checksum changed between samples"
            );
        }
        return value;
    }

    private static List<Double> values(
        List<Sample> samples,
        String scenario,
        boolean allocations
    ) {
        List<Double> values = new ArrayList<>();
        for (Sample sample : samples) {
            if (scenario.equals(sample.scenario())) {
                values.add(
                    allocations
                        ? sample.allocatedBytesPerOperation()
                        : sample.nsPerOperation()
                );
            }
        }
        if (values.isEmpty()) {
            throw new IllegalStateException(
                scenario + " has no measurements"
            );
        }
        return values;
    }

    private static double percentile(
        List<Double> unsorted,
        double percentile
    ) {
        double[] values = new double[unsorted.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = unsorted.get(index);
        }
        Arrays.sort(values);
        int rank = Math.max(
            1,
            (int)Math.ceil(percentile * values.length)
        );
        return values[rank - 1];
    }

    private static Sample firstSample(
        List<Sample> samples,
        String scenario
    ) {
        return samples
            .stream()
            .filter(sample -> scenario.equals(sample.scenario()))
            .findFirst()
            .orElseThrow();
    }

    private static long totalGcCollections(
        List<Sample> samples,
        String scenario
    ) {
        return samples
            .stream()
            .filter(sample -> scenario.equals(sample.scenario()))
            .mapToLong(Sample::gcCollections)
            .sum();
    }

    private static long totalGcPause(
        List<Sample> samples,
        String scenario
    ) {
        return samples
            .stream()
            .filter(sample -> scenario.equals(sample.scenario()))
            .mapToLong(Sample::gcPauseMillis)
            .sum();
    }

    private static void printSummary(
        List<Sample> samples,
        String scenario
    ) {
        List<Double> timings = values(samples, scenario, false);
        List<Double> allocations = values(samples, scenario, true);
        System.out.printf(
            Locale.ROOT,
            "%s p50/p95/p99 %.6f/%.6f/%.6f ns/op, "
                + "allocation %.6f/%.6f/%.6f B/op, "
                + "GC %d collections / %d ms%n",
            scenario,
            percentile(timings, 0.50),
            percentile(timings, 0.95),
            percentile(timings, 0.99),
            percentile(allocations, 0.50),
            percentile(allocations, 0.95),
            percentile(allocations, 0.99),
            totalGcCollections(samples, scenario),
            totalGcPause(samples, scenario)
        );
    }

    private static void requireCleanClosedManager(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            !snapshot.closed()
                || snapshot.outstanding() != 0
                || snapshot.usedBytes(MemoryKind.RAM) != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "benchmark manager did not close cleanly"
            );
        }
    }

    private static MemoryBudgetSettings evictionSettings() {
        long[] ram = new long[MemoryCategory.values().length];
        long[] vram = new long[MemoryCategory.values().length];
        Arrays.fill(ram, BLOCK_BYTES);
        Arrays.fill(vram, BLOCK_BYTES);
        return new MemoryBudgetSettings(
            BLOCK_BYTES,
            BLOCK_BYTES,
            0L,
            0L,
            ram,
            vram
        );
    }

    private static com.sun.management.ThreadMXBean optionalAllocationBean() {
        if (
            !(ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()
        ) {
            return null;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return bean;
    }

    private static long allocatedBytes(
        com.sun.management.ThreadMXBean bean,
        long threadId
    ) {
        return bean == null
            ? -1L
            : bean.getThreadAllocatedBytes(threadId);
    }

    private static long allocationDelta(long before, long after) {
        return before < 0L || after < before ? -1L : after - before;
    }

    private static GcSnapshot gcSnapshot() {
        long collections = 0L;
        long pauseMillis = 0L;
        for (
            GarbageCollectorMXBean collector
                : ManagementFactory.getGarbageCollectorMXBeans()
        ) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count >= 0L) {
                collections += count;
            }
            if (time >= 0L) {
                pauseMillis += time;
            }
        }
        return new GcSnapshot(collections, pauseMillis);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win")
            ? "java.exe"
            : "java";
        return Path.of(
            System.getProperty("java.home"),
            "bin",
            executable
        );
    }

    private static long mix(long left, long right) {
        long mixed = left ^ Long.rotateLeft(right, 23);
        mixed *= 0x9E3779B97F4A7C15L;
        return Long.rotateLeft(mixed, 17);
    }

    private static void publish(long value) {
        blackhole ^= value;
    }

    private static void appendDouble(
        StringBuilder csv,
        double value
    ) {
        csv.append(String.format(Locale.ROOT, "%.6f", value));
    }

    @FunctionalInterface
    private interface Trace {
        long run();
    }

    private record Measurement(
        long elapsedNanos,
        long allocatedBytes,
        long gcCollections,
        long gcPauseMillis,
        long checksum
    ) {
    }

    private record GcSnapshot(long collections, long pauseMillis) {
    }

    private record Sample(
        int fork,
        String order,
        String worker,
        String scenario,
        int sample,
        int operations,
        double nsPerOperation,
        double allocatedBytesPerOperation,
        long gcCollections,
        long gcPauseMillis,
        String checksum,
        long reclaimedBytes
    ) {
        private static Sample measured(
            int fork,
            String order,
            String worker,
            String scenario,
            int sample,
            int operations,
            Measurement measurement,
            long reclaimedBytes
        ) {
            return new Sample(
                fork,
                order,
                worker,
                scenario,
                sample,
                operations,
                (double)measurement.elapsedNanos() / operations,
                measurement.allocatedBytes() < 0L
                    ? -1.0
                    : (double)measurement.allocatedBytes() / operations,
                measurement.gcCollections(),
                measurement.gcPauseMillis(),
                Long.toUnsignedString(measurement.checksum()),
                reclaimedBytes
            );
        }
    }
}
