package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Fork-isolated Phase 1A.5 comparison of the heap and native entity-history
 * stores.
 *
 * <p>Every backend/entity-count/fork cell runs in a fresh JVM with identical
 * heap and collector flags. The controller alternates heap/native launch
 * order for three of six fork pairs in each direction. Setup, priming,
 * cleanup and file output remain outside the timed and thread-allocation
 * windows. RSS is intentionally not compared: only the fixed logical storage
 * footprint is reported.</p>
 */
public final class Phase1a5ForkedEntityHistoryBenchmark {
    private static final int CAPACITY = 65_536;
    private static final int[] ENTITY_COUNTS = {
        128,
        512,
        2_048,
        8_192,
        49_152
    };
    private static final int DEFAULT_FORKS = 6;
    private static final int WARMUP_BATCHES = 20;
    private static final int SAMPLE_COUNT = 201;
    private static final int ENTITY_OPERATIONS_PER_BATCH = 196_608;
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;
    private static final long WORKER_TIMEOUT_MINUTES = 10L;
    private static final String WORKLOAD =
        "fixed_capacity_65536_previous_lookup_four_reads_current_write_"
            + "196608_entity_transactions_per_sample";
    private static final String RAM_SCOPE =
        "logical_storage_only_rss_not_compared_across_fresh_processes";
    private static final String SCOPE =
        "isolated_cpu_entity_history_no_minecraft_renderer_gpu_or_"
            + "end_to_end_speedup_claim";
    private static final String LIMITATIONS =
        "batch_mean_frame_percentiles_setup_prime_close_excluded_"
            + "whole_process_rss_deliberately_not_used_"
            + "real_minecraft_workload_required_separately";
    private static final String JVM_FLAGS =
        "xms256m_xmx256m_g1_xbatch_active_processor_count_1_"
            + "same_for_all_workers";
    private static final String RAW_HEADER =
        "sample,total_nanos,ns_per_frame,ns_per_entity,"
            + "thread_allocated_bytes,thread_allocated_bytes_per_frame,"
            + "thread_allocated_bytes_per_entity,gc_count_delta,"
            + "gc_pause_ms_delta,checksum";
    private static final String FINAL_HEADER = String.join(
        ",",
        "row_type",
        "backend",
        "storage_kind",
        "entity_count",
        "fork",
        "launch_order",
        "sample",
        "capacity",
        "occupancy_ratio",
        "warmup_batches",
        "warmup_entity_operations",
        "measurement_samples",
        "frames_per_sample",
        "entity_operations_per_sample",
        "total_nanos",
        "ns_per_frame",
        "ns_per_entity",
        "thread_allocated_bytes",
        "thread_allocated_bytes_per_frame",
        "thread_allocated_bytes_per_entity",
        "gc_count_delta",
        "gc_pause_ms_delta",
        "p50_ns_per_frame",
        "p95_ns_per_frame",
        "p99_ns_per_frame",
        "p50_ns_per_entity",
        "p95_ns_per_entity",
        "p99_ns_per_entity",
        "p50_thread_allocated_bytes_per_frame",
        "p95_thread_allocated_bytes_per_frame",
        "p99_thread_allocated_bytes_per_frame",
        "total_gc_count_delta",
        "total_gc_pause_ms_delta",
        "checksum",
        "storage_requested_ram_bytes",
        "storage_committed_ram_bytes",
        "budget_requested_ram_bytes",
        "budget_used_ram_bytes",
        "vram_bytes",
        "fork_count",
        "paired_native_over_heap_p50_ratio",
        "worker_pid",
        "java_version",
        "jvm_flags",
        "ram_observation_scope",
        "workload",
        "scope",
        "limitations"
    );

    private static volatile long blackhole;

    private Phase1a5ForkedEntityHistoryBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "--worker".equals(arguments[0])) {
            runWorker(arguments);
            return;
        }
        runController(arguments);
    }

    private static void runController(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2) {
            throw new IllegalArgumentException(
                "expected output CSV path and optional fork count"
            );
        }
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        int forks = arguments.length == 2
            ? Integer.parseInt(arguments[1])
            : DEFAULT_FORKS;
        if (forks < 2 || (forks & 1) != 0) {
            throw new IllegalArgumentException(
                "fork count must be a positive even number of at least two"
            );
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path workerDirectory = output.resolveSibling(
            output.getFileName() + ".workers-" + System.currentTimeMillis()
        );
        Files.createDirectories(workerDirectory);

        List<WorkerResult> results = new ArrayList<>(
            forks * ENTITY_COUNTS.length * Backend.values().length
        );
        for (int fork = 1; fork <= forks; fork++) {
            Backend[] backendOrder = (fork & 1) == 1
                ? new Backend[] {Backend.HEAP, Backend.NATIVE}
                : new Backend[] {Backend.NATIVE, Backend.HEAP};
            String launchOrder = (fork & 1) == 1
                ? "heap_then_native"
                : "native_then_heap";
            for (int entityCount : entityOrder(fork)) {
                for (Backend backend : backendOrder) {
                    WorkerResult result = launchWorker(
                        workerDirectory,
                        backend,
                        entityCount,
                        fork,
                        launchOrder
                    );
                    results.add(result);
                    System.out.printf(
                        Locale.ROOT,
                        "fork=%d order=%s entities=%d backend=%s "
                            + "p50/p95/p99=%.3f/%.3f/%.3f ns/entity%n",
                        fork,
                        launchOrder,
                        entityCount,
                        backend.label,
                        result.summary.p50NsPerEntity,
                        result.summary.p95NsPerEntity,
                        result.summary.p99NsPerEntity
                    );
                }
            }
        }

        verifyEquivalence(results, forks);
        String csv = createFinalCsv(results, forks);
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        printAggregateSummary(results, forks);
        System.out.println("CSV: " + output);
        System.out.println("Worker evidence: " + workerDirectory);
        System.out.println(
            "RSS comparison: deliberately omitted; fresh-process whole-RSS "
                + "is not storage attribution."
        );
    }

    private static int[] entityOrder(int fork) {
        int[] order = ENTITY_COUNTS.clone();
        switch ((fork - 1) % 3) {
            case 1 -> reverse(order);
            case 2 -> rotateLeft(order, 2);
            default -> {
            }
        }
        return order;
    }

    private static void reverse(int[] values) {
        for (int left = 0, right = values.length - 1;
            left < right;
            left++, right--) {
            int value = values[left];
            values[left] = values[right];
            values[right] = value;
        }
    }

    private static void rotateLeft(int[] values, int amount) {
        int[] copy = values.clone();
        for (int index = 0; index < values.length; index++) {
            values[index] = copy[(index + amount) % values.length];
        }
    }

    private static WorkerResult launchWorker(
        Path workerDirectory,
        Backend backend,
        int entityCount,
        int fork,
        String launchOrder
    ) throws Exception {
        String stem = String.format(
            Locale.ROOT,
            "fork-%02d-%s-%d",
            fork,
            backend.label,
            entityCount
        );
        Path rawOutput = workerDirectory.resolve(stem + ".csv");
        Path summaryOutput = workerDirectory.resolve(stem + ".properties");
        Path logOutput = workerDirectory.resolve(stem + ".log");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xms256m");
        command.add("-Xmx256m");
        command.add("-XX:+UseG1GC");
        command.add("-Xbatch");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(
            Phase1a5ForkedEntityHistoryBenchmark.class.getName()
        );
        command.add("--worker");
        command.add(rawOutput.toString());
        command.add(summaryOutput.toString());
        command.add(backend.label);
        command.add(Integer.toString(entityCount));
        command.add(Integer.toString(fork));
        command.add(launchOrder);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logOutput.toFile());
        Process process = builder.start();
        boolean exited = process.waitFor(
            WORKER_TIMEOUT_MINUTES,
            TimeUnit.MINUTES
        );
        if (!exited) {
            process.destroyForcibly();
            process.waitFor();
            throw new IllegalStateException(
                "worker timed out: " + stem
            );
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                "worker failed with exit "
                    + process.exitValue()
                    + ": "
                    + stem
                    + System.lineSeparator()
                    + Files.readString(logOutput, StandardCharsets.UTF_8)
            );
        }
        return readWorkerResult(rawOutput, summaryOutput);
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win")
            ? "java.exe"
            : "java";
        return Path.of(
            System.getProperty("java.home"),
            "bin",
            executable
        ).toString();
    }

    private static WorkerResult readWorkerResult(
        Path rawOutput,
        Path summaryOutput
    ) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(summaryOutput)) {
            properties.load(input);
        }
        WorkerSummary summary = WorkerSummary.from(properties);
        List<String> lines = Files.readAllLines(
            rawOutput,
            StandardCharsets.UTF_8
        );
        if (lines.isEmpty() || !RAW_HEADER.equals(lines.get(0))) {
            throw new IllegalStateException(
                "worker CSV header was unexpected: " + rawOutput
            );
        }
        List<Sample> samples = new ArrayList<>(SAMPLE_COUNT);
        for (int index = 1; index < lines.size(); index++) {
            String[] columns = lines.get(index).split(",", -1);
            if (columns.length != 10) {
                throw new IllegalStateException(
                    "worker sample column count was unexpected: "
                        + rawOutput
                        + " line "
                        + (index + 1)
                );
            }
            samples.add(
                new Sample(
                    Integer.parseInt(columns[0]),
                    Long.parseLong(columns[1]),
                    Double.parseDouble(columns[2]),
                    Double.parseDouble(columns[3]),
                    Long.parseLong(columns[4]),
                    Double.parseDouble(columns[5]),
                    Double.parseDouble(columns[6]),
                    Long.parseLong(columns[7]),
                    Long.parseLong(columns[8]),
                    Long.parseUnsignedLong(columns[9])
                )
            );
        }
        if (samples.size() != SAMPLE_COUNT) {
            throw new IllegalStateException(
                "worker did not emit exactly "
                    + SAMPLE_COUNT
                    + " samples: "
                    + rawOutput
            );
        }
        return new WorkerResult(summary, List.copyOf(samples));
    }

    private static void runWorker(String[] arguments) throws Exception {
        if (arguments.length != 7) {
            throw new IllegalArgumentException(
                "worker expected raw path, summary path, backend, "
                    + "entity count, fork and launch order"
            );
        }
        Path rawOutput = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path summaryOutput = Path.of(arguments[2])
            .toAbsolutePath()
            .normalize();
        Backend backend = Backend.byLabel(arguments[3]);
        int entityCount = Integer.parseInt(arguments[4]);
        int fork = Integer.parseInt(arguments[5]);
        String launchOrder = arguments[6];
        requireEntityCount(entityCount);
        int framesPerSample = ENTITY_OPERATIONS_PER_BATCH / entityCount;

        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = null;
        boolean historyClosed = false;
        boolean managerClosed = false;
        try {
            history = backend.create(budgets);
            if (history == null) {
                throw new IllegalStateException(
                    backend.label + " could not allocate fixed history"
                );
            }
            requireHistoryShape(backend, history);
            long requestedBytes = history.requestedBytes();
            long committedBytes = history.committedBytes();
            MemoryBudgetManager.Snapshot setup = budgets.snapshot();
            requireSetupFootprint(
                setup,
                requestedBytes,
                committedBytes
            );

            long warmupChecksum = 0L;
            for (int warmup = 0; warmup < WARMUP_BATCHES; warmup++) {
                resetAndPrime(history, entityCount);
                long checksum = runFrames(
                    history,
                    entityCount,
                    framesPerSample
                );
                if (warmup > 0 && checksum != warmupChecksum) {
                    throw new IllegalStateException(
                        "warmup checksum changed"
                    );
                }
                warmupChecksum = checksum;
                publish(checksum);
            }

            com.sun.management.ThreadMXBean allocationBean =
                optionalAllocationBean();
            long threadId = Thread.currentThread().threadId();
            GcProbe gcProbe = new GcProbe(
                ManagementFactory.getGarbageCollectorMXBeans()
            );
            Sample[] samples = new Sample[SAMPLE_COUNT];
            long stableChecksum = 0L;
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                resetAndPrime(history, entityCount);
                GcSnapshot gcBefore = gcProbe.read();
                long allocationBefore = allocatedBytes(
                    allocationBean,
                    threadId
                );
                long started = System.nanoTime();
                long checksum = runFrames(
                    history,
                    entityCount,
                    framesPerSample
                );
                long elapsed = System.nanoTime() - started;
                long allocationAfter = allocatedBytes(
                    allocationBean,
                    threadId
                );
                GcSnapshot gcAfter = gcProbe.read();
                long allocated = allocationDelta(
                    allocationBefore,
                    allocationAfter
                );
                if (sample > 0 && checksum != stableChecksum) {
                    throw new IllegalStateException(
                        "measurement checksum changed"
                    );
                }
                stableChecksum = checksum;
                samples[sample] = new Sample(
                    sample,
                    elapsed,
                    (double)elapsed / framesPerSample,
                    (double)elapsed
                        / ENTITY_OPERATIONS_PER_BATCH,
                    allocated,
                    (double)allocated / framesPerSample,
                    (double)allocated
                        / ENTITY_OPERATIONS_PER_BATCH,
                    counterDelta(gcBefore.count, gcAfter.count),
                    counterDelta(
                        gcBefore.collectionMillis,
                        gcAfter.collectionMillis
                    ),
                    checksum
                );
                publish(checksum);
            }
            if (stableChecksum != warmupChecksum) {
                throw new IllegalStateException(
                    "warmup and measurement checksums differ"
                );
            }

            WorkerSummary summary = WorkerSummary.create(
                backend,
                entityCount,
                fork,
                launchOrder,
                framesPerSample,
                requestedBytes,
                committedBytes,
                setup.requestedBytes(MemoryKind.RAM),
                setup.usedBytes(MemoryKind.RAM),
                setup.usedBytes(MemoryKind.VRAM),
                ProcessHandle.current().pid(),
                stableChecksum,
                samples
            );
            writeWorkerEvidence(
                rawOutput,
                summaryOutput,
                summary,
                samples
            );

            history.close();
            historyClosed = true;
            history = null;
            EntityMotionHistory.retryPendingCleanup();
            requireReleasedFootprint(budgets.snapshot());
            budgets.close();
            managerClosed = true;
            requireReleasedFootprint(budgets.snapshot());
            System.out.println(
                "worker complete blackhole="
                    + Long.toUnsignedString(blackhole)
            );
        } finally {
            if (!historyClosed && history != null) {
                history.close();
            }
            EntityMotionHistory.retryPendingCleanup();
            if (!managerClosed) {
                budgets.close();
            }
        }
    }

    private static void requireEntityCount(int entityCount) {
        if (
            Arrays.stream(ENTITY_COUNTS)
                .noneMatch(candidate -> candidate == entityCount)
        ) {
            throw new IllegalArgumentException(
                "unsupported entity count: " + entityCount
            );
        }
        if (ENTITY_OPERATIONS_PER_BATCH % entityCount != 0) {
            throw new IllegalArgumentException(
                "entity count must divide the fixed batch size"
            );
        }
    }

    private static void requireHistoryShape(
        Backend backend,
        EntityMotionHistory history
    ) {
        if (
            history.storageKind() != backend.expectedKind
                || history.capacity() != CAPACITY
                || history.maxEntries() != 49_152
        ) {
            throw new IllegalStateException(
                backend.label + " did not expose the fixed production shape"
            );
        }
    }

    private static void requireSetupFootprint(
        MemoryBudgetManager.Snapshot snapshot,
        long requestedBytes,
        long committedBytes
    ) {
        if (
            snapshot.requestedBytes(MemoryKind.RAM) != requestedBytes
                || snapshot.usedBytes(MemoryKind.RAM) != committedBytes
                || snapshot.requestedBytes(MemoryKind.VRAM) != 0L
                || snapshot.usedBytes(MemoryKind.VRAM) != 0L
                || snapshot.outstanding() != 1
                || snapshot.rejections() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "setup accounting was unexpected: " + snapshot
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
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "worker did not release cleanly: " + snapshot
            );
        }
    }

    private static void resetAndPrime(
        EntityMotionHistory history,
        int entityCount
    ) {
        history.clear();
        history.beginFrame();
        for (int entity = 0; entity < entityCount; entity++) {
            double base = valueBase(-1, entity);
            if (
                !history.putCurrent(
                    entityId(entity),
                    base,
                    base + 1.0D,
                    base + 2.0D,
                    (float)(base + 3.0D)
                )
            ) {
                throw new IllegalStateException(
                    "history could not be primed"
                );
            }
        }
        if (history.currentSize() != entityCount) {
            throw new IllegalStateException(
                "history priming size was incorrect"
            );
        }
    }

    private static long runFrames(
        EntityMotionHistory history,
        int entityCount,
        int frames
    ) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < frames; frame++) {
            history.beginFrame();
            for (int entity = 0; entity < entityCount; entity++) {
                int entityId = entityId(entity);
                if (!history.findPrevious(entityId)) {
                    throw new IllegalStateException(
                        "history lost entity " + entityId
                    );
                }
                checksum = consumePrevious(
                    checksum,
                    entityId,
                    history.previousX(),
                    history.previousY(),
                    history.previousZ(),
                    history.previousYaw()
                );
                double base = valueBase(frame, entity);
                if (
                    !history.putCurrent(
                        entityId,
                        base,
                        base + 1.0D,
                        base + 2.0D,
                        (float)(base + 3.0D)
                    )
                ) {
                    throw new IllegalStateException(
                        "history rejected entity " + entityId
                    );
                }
            }
            if (history.currentSize() != entityCount) {
                throw new IllegalStateException(
                    "history current size was incorrect"
                );
            }
            checksum = mix(checksum, history.currentSize());
        }
        return checksum;
    }

    private static int entityId(int entity) {
        return entity * 0x9E3779B9 ^ 0x4F1BBCDC;
    }

    private static double valueBase(int frame, int entity) {
        return frame * 0.03125D + entity * 0.0009765625D;
    }

    private static long consumePrevious(
        long checksum,
        int entityId,
        double x,
        double y,
        double z,
        float yaw
    ) {
        long observed = Double.doubleToRawLongBits(x);
        observed ^= Long.rotateLeft(
            Double.doubleToRawLongBits(y),
            11
        );
        observed ^= Long.rotateLeft(
            Double.doubleToRawLongBits(z),
            29
        );
        observed ^= Long.rotateLeft(
            Integer.toUnsignedLong(Float.floatToRawIntBits(yaw)),
            47
        );
        return mix(
            checksum,
            observed ^ Integer.toUnsignedLong(entityId)
        );
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

    private static com.sun.management.ThreadMXBean
        optionalAllocationBean() {
        java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation)) {
            return null;
        }
        if (!allocation.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        return allocation;
    }

    private static long allocatedBytes(
        com.sun.management.ThreadMXBean bean,
        long threadId
    ) {
        return bean == null ? -1L : bean.getThreadAllocatedBytes(threadId);
    }

    private static long allocationDelta(long before, long after) {
        return before < 0L || after < before ? -1L : after - before;
    }

    private static long counterDelta(long before, long after) {
        return before < 0L || after < before ? -1L : after - before;
    }

    private static void writeWorkerEvidence(
        Path rawOutput,
        Path summaryOutput,
        WorkerSummary summary,
        Sample[] samples
    ) throws IOException {
        Path parent = rawOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder csv = new StringBuilder();
        csv.append(RAW_HEADER).append('\n');
        for (Sample sample : samples) {
            csv.append(sample.sample).append(',')
                .append(sample.totalNanos).append(',')
                .append(decimal(sample.nsPerFrame)).append(',')
                .append(decimal(sample.nsPerEntity)).append(',')
                .append(sample.allocatedBytes).append(',')
                .append(decimal(sample.allocatedBytesPerFrame)).append(',')
                .append(decimal(sample.allocatedBytesPerEntity)).append(',')
                .append(sample.gcCountDelta).append(',')
                .append(sample.gcPauseMsDelta).append(',')
                .append(Long.toUnsignedString(sample.checksum))
                .append('\n');
        }
        Files.writeString(rawOutput, csv, StandardCharsets.UTF_8);
        Properties properties = summary.toProperties();
        try (OutputStream output = Files.newOutputStream(summaryOutput)) {
            properties.store(output, "Phase 1A.5 worker summary");
        }
    }

    private static void verifyEquivalence(
        List<WorkerResult> results,
        int forks
    ) {
        for (int entityCount : ENTITY_COUNTS) {
            long expectedChecksum = 0L;
            boolean checksumSet = false;
            for (int fork = 1; fork <= forks; fork++) {
                WorkerResult heap = find(
                    results,
                    Backend.HEAP,
                    entityCount,
                    fork
                );
                WorkerResult nativeResult = find(
                    results,
                    Backend.NATIVE,
                    entityCount,
                    fork
                );
                if (heap.summary.checksum != nativeResult.summary.checksum) {
                    throw new IllegalStateException(
                        "heap/native checksum mismatch for entities="
                            + entityCount
                            + " fork="
                            + fork
                    );
                }
                if (
                    checksumSet
                        && heap.summary.checksum != expectedChecksum
                ) {
                    throw new IllegalStateException(
                        "checksum changed across forks for entities="
                            + entityCount
                    );
                }
                expectedChecksum = heap.summary.checksum;
                checksumSet = true;
            }
        }
    }

    private static WorkerResult find(
        List<WorkerResult> results,
        Backend backend,
        int entityCount,
        int fork
    ) {
        return results.stream()
            .filter(result ->
                result.summary.backend == backend
                    && result.summary.entityCount == entityCount
                    && result.summary.fork == fork
            )
            .findFirst()
            .orElseThrow(() ->
                new IllegalStateException(
                    "missing worker result "
                        + backend.label
                        + "/"
                        + entityCount
                        + "/"
                        + fork
                )
            );
    }

    private static String createFinalCsv(
        List<WorkerResult> results,
        int forks
    ) {
        StringBuilder csv = new StringBuilder();
        csv.append(FINAL_HEADER).append('\n');
        for (WorkerResult result : results) {
            for (Sample sample : result.samples) {
                appendSampleRow(csv, result.summary, sample, forks);
            }
            appendSummaryRow(
                csv,
                "fork_summary",
                result.summary,
                result.samples,
                forks,
                ""
            );
        }
        for (int entityCount : ENTITY_COUNTS) {
            for (Backend backend : Backend.values()) {
                List<Sample> samples = results.stream()
                    .filter(result ->
                        result.summary.backend == backend
                            && result.summary.entityCount == entityCount
                    )
                    .flatMap(result -> result.samples.stream())
                    .toList();
                WorkerSummary representative = find(
                    results,
                    backend,
                    entityCount,
                    1
                ).summary;
                appendSummaryRow(
                    csv,
                    "aggregate",
                    representative.withAggregate(forks),
                    samples,
                    forks,
                    ""
                );
            }
            double[] ratios = new double[forks];
            for (int fork = 1; fork <= forks; fork++) {
                WorkerSummary heap = find(
                    results,
                    Backend.HEAP,
                    entityCount,
                    fork
                ).summary;
                WorkerSummary nativeResult = find(
                    results,
                    Backend.NATIVE,
                    entityCount,
                    fork
                ).summary;
                double ratio =
                    nativeResult.p50NsPerEntity / heap.p50NsPerEntity;
                ratios[fork - 1] = ratio;
                appendRatioRow(
                    csv,
                    "paired_ratio",
                    entityCount,
                    fork,
                    heap.launchOrder,
                    ratio,
                    forks
                );
            }
            Arrays.sort(ratios);
            appendRatioAggregateRow(
                csv,
                entityCount,
                ratios,
                forks
            );
        }
        return csv.toString();
    }

    private static void appendSampleRow(
        StringBuilder csv,
        WorkerSummary summary,
        Sample sample,
        int forks
    ) {
        appendCommonPrefix(
            csv,
            "sample",
            summary,
            Integer.toString(sample.sample),
            forks
        );
        csv.append(sample.totalNanos).append(',')
            .append(decimal(sample.nsPerFrame)).append(',')
            .append(decimal(sample.nsPerEntity)).append(',')
            .append(sample.allocatedBytes).append(',')
            .append(decimal(sample.allocatedBytesPerFrame)).append(',')
            .append(decimal(sample.allocatedBytesPerEntity)).append(',')
            .append(sample.gcCountDelta).append(',')
            .append(sample.gcPauseMsDelta).append(',');
        appendBlanks(csv, 11);
        appendCommonSuffix(
            csv,
            summary,
            forks,
            "",
            true
        );
    }

    private static void appendSummaryRow(
        StringBuilder csv,
        String rowType,
        WorkerSummary summary,
        List<Sample> samples,
        int forks,
        String ratio
    ) {
        appendCommonPrefix(csv, rowType, summary, "", forks);
        appendBlanks(csv, 8);
        double[] frame = samples.stream()
            .mapToDouble(sample -> sample.nsPerFrame)
            .sorted()
            .toArray();
        double[] entity = samples.stream()
            .mapToDouble(sample -> sample.nsPerEntity)
            .sorted()
            .toArray();
        double[] allocation = samples.stream()
            .mapToDouble(sample -> sample.allocatedBytesPerFrame)
            .sorted()
            .toArray();
        csv.append(decimal(percentile(frame, 50))).append(',')
            .append(decimal(percentile(frame, 95))).append(',')
            .append(decimal(percentile(frame, 99))).append(',')
            .append(decimal(percentile(entity, 50))).append(',')
            .append(decimal(percentile(entity, 95))).append(',')
            .append(decimal(percentile(entity, 99))).append(',')
            .append(decimal(percentile(allocation, 50))).append(',')
            .append(decimal(percentile(allocation, 95))).append(',')
            .append(decimal(percentile(allocation, 99))).append(',')
            .append(sumGcCount(samples)).append(',')
            .append(sumGcPause(samples)).append(',');
        appendCommonSuffix(csv, summary, forks, ratio, true);
    }

    private static void appendCommonPrefix(
        StringBuilder csv,
        String rowType,
        WorkerSummary summary,
        String sample,
        int forks
    ) {
        csv.append(rowType).append(',')
            .append(summary.backend.label).append(',')
            .append(summary.storageKind).append(',')
            .append(summary.entityCount).append(',')
            .append(summary.fork == 0 ? "" : summary.fork).append(',')
            .append(summary.launchOrder).append(',')
            .append(sample).append(',')
            .append(CAPACITY).append(',')
            .append(
                decimal((double)summary.entityCount / CAPACITY)
            ).append(',')
            .append(WARMUP_BATCHES).append(',')
            .append(
                (long)WARMUP_BATCHES
                    * ENTITY_OPERATIONS_PER_BATCH
            ).append(',')
            .append(
                summary.fork == 0 ? SAMPLE_COUNT * forks : SAMPLE_COUNT
            ).append(',')
            .append(summary.framesPerSample).append(',')
            .append(ENTITY_OPERATIONS_PER_BATCH).append(',');
    }

    private static void appendCommonSuffix(
        StringBuilder csv,
        WorkerSummary summary,
        int forks,
        String ratio,
        boolean newline
    ) {
        csv.append(Long.toUnsignedString(summary.checksum)).append(',')
            .append(summary.storageRequestedBytes).append(',')
            .append(summary.storageCommittedBytes).append(',')
            .append(summary.budgetRequestedBytes).append(',')
            .append(summary.budgetUsedBytes).append(',')
            .append(summary.vramBytes).append(',')
            .append(forks).append(',')
            .append(ratio).append(',')
            .append(summary.workerPid == 0L ? "" : summary.workerPid)
            .append(',')
            .append(summary.javaVersion).append(',')
            .append(JVM_FLAGS).append(',')
            .append(RAM_SCOPE).append(',')
            .append(WORKLOAD).append(',')
            .append(SCOPE).append(',')
            .append(LIMITATIONS);
        if (newline) {
            csv.append('\n');
        }
    }

    private static void appendRatioRow(
        StringBuilder csv,
        String rowType,
        int entityCount,
        int fork,
        String launchOrder,
        double ratio,
        int forks
    ) {
        WorkerSummary summary = WorkerSummary.ratioPlaceholder(
            entityCount,
            fork,
            launchOrder
        );
        appendCommonPrefix(csv, rowType, summary, "", forks);
        appendBlanks(csv, 19);
        appendCommonSuffix(
            csv,
            summary,
            forks,
            decimal(ratio),
            true
        );
    }

    private static void appendRatioAggregateRow(
        StringBuilder csv,
        int entityCount,
        double[] sortedRatios,
        int forks
    ) {
        WorkerSummary summary = WorkerSummary.ratioPlaceholder(
            entityCount,
            0,
            "three_heap_then_native_three_native_then_heap"
        );
        appendCommonPrefix(
            csv,
            "paired_ratio_aggregate",
            summary,
            "",
            forks
        );
        appendBlanks(csv, 8);
        appendBlanks(csv, 3);
        csv.append(decimal(percentile(sortedRatios, 50))).append(',')
            .append(decimal(percentile(sortedRatios, 95))).append(',')
            .append(decimal(percentile(sortedRatios, 99))).append(',');
        appendBlanks(csv, 5);
        appendCommonSuffix(
            csv,
            summary,
            forks,
            decimal(percentile(sortedRatios, 50)),
            true
        );
    }

    private static void appendBlanks(StringBuilder csv, int count) {
        for (int index = 0; index < count; index++) {
            csv.append(',');
        }
    }

    private static long sumGcCount(List<Sample> samples) {
        long sum = 0L;
        for (Sample sample : samples) {
            if (sample.gcCountDelta < 0L) {
                return -1L;
            }
            sum += sample.gcCountDelta;
        }
        return sum;
    }

    private static long sumGcPause(List<Sample> samples) {
        long sum = 0L;
        for (Sample sample : samples) {
            if (sample.gcPauseMsDelta < 0L) {
                return -1L;
            }
            sum += sample.gcPauseMsDelta;
        }
        return sum;
    }

    private static double percentile(double[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0.0D;
        }
        int rank = (int)Math.ceil(percentile / 100.0D * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static void printAggregateSummary(
        List<WorkerResult> results,
        int forks
    ) {
        for (int entityCount : ENTITY_COUNTS) {
            Map<Backend, double[]> values = new EnumMap<>(Backend.class);
            for (Backend backend : Backend.values()) {
                double[] samples = results.stream()
                    .filter(result ->
                        result.summary.backend == backend
                            && result.summary.entityCount == entityCount
                    )
                    .flatMap(result -> result.samples.stream())
                    .mapToDouble(sample -> sample.nsPerEntity)
                    .sorted()
                    .toArray();
                values.put(backend, samples);
            }
            double heap = percentile(values.get(Backend.HEAP), 50);
            double nativeResult = percentile(
                values.get(Backend.NATIVE),
                50
            );
            double[] paired = new double[forks];
            for (int fork = 1; fork <= forks; fork++) {
                paired[fork - 1] =
                    find(
                        results,
                        Backend.NATIVE,
                        entityCount,
                        fork
                    ).summary.p50NsPerEntity
                        / find(
                            results,
                            Backend.HEAP,
                            entityCount,
                            fork
                        ).summary.p50NsPerEntity;
            }
            Arrays.sort(paired);
            System.out.printf(
                Locale.ROOT,
                "AGG entities=%d heap=%.3f native=%.3f ns/entity "
                    + "raw_ratio=%.4fx paired_ratio_p50/min/max="
                    + "%.4f/%.4f/%.4f%n",
                entityCount,
                heap,
                nativeResult,
                nativeResult / heap,
                percentile(paired, 50),
                paired[0],
                paired[paired.length - 1]
            );
        }
    }

    private enum Backend {
        HEAP("heap", EntityMotionHistory.StorageKind.HEAP) {
            @Override
            EntityMotionHistory create(MemoryBudgetManager budgets) {
                return EntityMotionHistory.tryCreateHeap(
                    budgets,
                    CAPACITY
                );
            }
        },
        NATIVE("native", EntityMotionHistory.StorageKind.NATIVE) {
            @Override
            EntityMotionHistory create(MemoryBudgetManager budgets) {
                return EntityMotionHistory.tryCreateNative(
                    budgets,
                    CAPACITY
                );
            }
        };

        private final String label;
        private final EntityMotionHistory.StorageKind expectedKind;

        Backend(
            String label,
            EntityMotionHistory.StorageKind expectedKind
        ) {
            this.label = label;
            this.expectedKind = expectedKind;
        }

        abstract EntityMotionHistory create(
            MemoryBudgetManager budgets
        );

        static Backend byLabel(String label) {
            return Arrays.stream(values())
                .filter(value -> value.label.equals(label))
                .findFirst()
                .orElseThrow(() ->
                    new IllegalArgumentException(
                        "unknown backend: " + label
                    )
                );
        }
    }

    private record Sample(
        int sample,
        long totalNanos,
        double nsPerFrame,
        double nsPerEntity,
        long allocatedBytes,
        double allocatedBytesPerFrame,
        double allocatedBytesPerEntity,
        long gcCountDelta,
        long gcPauseMsDelta,
        long checksum
    ) {
    }

    private record WorkerResult(
        WorkerSummary summary,
        List<Sample> samples
    ) {
    }

    private record GcSnapshot(long count, long collectionMillis) {
    }

    private static final class GcProbe {
        private final List<GarbageCollectorMXBean> beans;

        private GcProbe(List<GarbageCollectorMXBean> beans) {
            this.beans = List.copyOf(beans);
        }

        private GcSnapshot read() {
            long count = 0L;
            long millis = 0L;
            for (GarbageCollectorMXBean bean : this.beans) {
                long beanCount = bean.getCollectionCount();
                long beanMillis = bean.getCollectionTime();
                if (beanCount < 0L || beanMillis < 0L) {
                    return new GcSnapshot(-1L, -1L);
                }
                count += beanCount;
                millis += beanMillis;
            }
            return new GcSnapshot(count, millis);
        }
    }

    private static final class WorkerSummary {
        private final Backend backend;
        private final String storageKind;
        private final int entityCount;
        private final int fork;
        private final String launchOrder;
        private final int framesPerSample;
        private final long storageRequestedBytes;
        private final long storageCommittedBytes;
        private final long budgetRequestedBytes;
        private final long budgetUsedBytes;
        private final long vramBytes;
        private final long workerPid;
        private final String javaVersion;
        private final long checksum;
        private final double p50NsPerFrame;
        private final double p95NsPerFrame;
        private final double p99NsPerFrame;
        private final double p50NsPerEntity;
        private final double p95NsPerEntity;
        private final double p99NsPerEntity;
        private final long totalGcCount;
        private final long totalGcPauseMs;

        private WorkerSummary(
            Backend backend,
            String storageKind,
            int entityCount,
            int fork,
            String launchOrder,
            int framesPerSample,
            long storageRequestedBytes,
            long storageCommittedBytes,
            long budgetRequestedBytes,
            long budgetUsedBytes,
            long vramBytes,
            long workerPid,
            String javaVersion,
            long checksum,
            double p50NsPerFrame,
            double p95NsPerFrame,
            double p99NsPerFrame,
            double p50NsPerEntity,
            double p95NsPerEntity,
            double p99NsPerEntity,
            long totalGcCount,
            long totalGcPauseMs
        ) {
            this.backend = backend;
            this.storageKind = storageKind;
            this.entityCount = entityCount;
            this.fork = fork;
            this.launchOrder = launchOrder;
            this.framesPerSample = framesPerSample;
            this.storageRequestedBytes = storageRequestedBytes;
            this.storageCommittedBytes = storageCommittedBytes;
            this.budgetRequestedBytes = budgetRequestedBytes;
            this.budgetUsedBytes = budgetUsedBytes;
            this.vramBytes = vramBytes;
            this.workerPid = workerPid;
            this.javaVersion = javaVersion;
            this.checksum = checksum;
            this.p50NsPerFrame = p50NsPerFrame;
            this.p95NsPerFrame = p95NsPerFrame;
            this.p99NsPerFrame = p99NsPerFrame;
            this.p50NsPerEntity = p50NsPerEntity;
            this.p95NsPerEntity = p95NsPerEntity;
            this.p99NsPerEntity = p99NsPerEntity;
            this.totalGcCount = totalGcCount;
            this.totalGcPauseMs = totalGcPauseMs;
        }

        private static WorkerSummary create(
            Backend backend,
            int entityCount,
            int fork,
            String launchOrder,
            int framesPerSample,
            long storageRequestedBytes,
            long storageCommittedBytes,
            long budgetRequestedBytes,
            long budgetUsedBytes,
            long vramBytes,
            long workerPid,
            long checksum,
            Sample[] samples
        ) {
            double[] frames = Arrays.stream(samples)
                .mapToDouble(sample -> sample.nsPerFrame)
                .sorted()
                .toArray();
            double[] entities = Arrays.stream(samples)
                .mapToDouble(sample -> sample.nsPerEntity)
                .sorted()
                .toArray();
            List<Sample> sampleList = Arrays.asList(samples);
            return new WorkerSummary(
                backend,
                backend.expectedKind.name(),
                entityCount,
                fork,
                launchOrder,
                framesPerSample,
                storageRequestedBytes,
                storageCommittedBytes,
                budgetRequestedBytes,
                budgetUsedBytes,
                vramBytes,
                workerPid,
                System.getProperty("java.version"),
                checksum,
                percentile(frames, 50),
                percentile(frames, 95),
                percentile(frames, 99),
                percentile(entities, 50),
                percentile(entities, 95),
                percentile(entities, 99),
                sumGcCount(sampleList),
                sumGcPause(sampleList)
            );
        }

        private static WorkerSummary from(Properties properties) {
            return new WorkerSummary(
                Backend.byLabel(required(properties, "backend")),
                required(properties, "storageKind"),
                integer(properties, "entityCount"),
                integer(properties, "fork"),
                required(properties, "launchOrder"),
                integer(properties, "framesPerSample"),
                number(properties, "storageRequestedBytes"),
                number(properties, "storageCommittedBytes"),
                number(properties, "budgetRequestedBytes"),
                number(properties, "budgetUsedBytes"),
                number(properties, "vramBytes"),
                number(properties, "workerPid"),
                required(properties, "javaVersion"),
                Long.parseUnsignedLong(required(properties, "checksum")),
                decimal(properties, "p50NsPerFrame"),
                decimal(properties, "p95NsPerFrame"),
                decimal(properties, "p99NsPerFrame"),
                decimal(properties, "p50NsPerEntity"),
                decimal(properties, "p95NsPerEntity"),
                decimal(properties, "p99NsPerEntity"),
                number(properties, "totalGcCount"),
                number(properties, "totalGcPauseMs")
            );
        }

        private Properties toProperties() {
            Properties properties = new Properties();
            properties.setProperty("backend", this.backend.label);
            properties.setProperty("storageKind", this.storageKind);
            properties.setProperty(
                "entityCount",
                Integer.toString(this.entityCount)
            );
            properties.setProperty("fork", Integer.toString(this.fork));
            properties.setProperty("launchOrder", this.launchOrder);
            properties.setProperty(
                "framesPerSample",
                Integer.toString(this.framesPerSample)
            );
            properties.setProperty(
                "storageRequestedBytes",
                Long.toString(this.storageRequestedBytes)
            );
            properties.setProperty(
                "storageCommittedBytes",
                Long.toString(this.storageCommittedBytes)
            );
            properties.setProperty(
                "budgetRequestedBytes",
                Long.toString(this.budgetRequestedBytes)
            );
            properties.setProperty(
                "budgetUsedBytes",
                Long.toString(this.budgetUsedBytes)
            );
            properties.setProperty(
                "vramBytes",
                Long.toString(this.vramBytes)
            );
            properties.setProperty(
                "workerPid",
                Long.toString(this.workerPid)
            );
            properties.setProperty("javaVersion", this.javaVersion);
            properties.setProperty(
                "checksum",
                Long.toUnsignedString(this.checksum)
            );
            properties.setProperty(
                "p50NsPerFrame",
                Phase1a5ForkedEntityHistoryBenchmark.decimal(
                    this.p50NsPerFrame
                )
            );
            properties.setProperty(
                "p95NsPerFrame",
                Phase1a5ForkedEntityHistoryBenchmark.decimal(
                    this.p95NsPerFrame
                )
            );
            properties.setProperty(
                "p99NsPerFrame",
                Phase1a5ForkedEntityHistoryBenchmark.decimal(
                    this.p99NsPerFrame
                )
            );
            properties.setProperty(
                "p50NsPerEntity",
                Phase1a5ForkedEntityHistoryBenchmark.decimal(
                    this.p50NsPerEntity
                )
            );
            properties.setProperty(
                "p95NsPerEntity",
                Phase1a5ForkedEntityHistoryBenchmark.decimal(
                    this.p95NsPerEntity
                )
            );
            properties.setProperty(
                "p99NsPerEntity",
                Phase1a5ForkedEntityHistoryBenchmark.decimal(
                    this.p99NsPerEntity
                )
            );
            properties.setProperty(
                "totalGcCount",
                Long.toString(this.totalGcCount)
            );
            properties.setProperty(
                "totalGcPauseMs",
                Long.toString(this.totalGcPauseMs)
            );
            return properties;
        }

        private WorkerSummary withAggregate(int forks) {
            return new WorkerSummary(
                this.backend,
                this.storageKind,
                this.entityCount,
                0,
                "three_heap_then_native_three_native_then_heap",
                this.framesPerSample,
                this.storageRequestedBytes,
                this.storageCommittedBytes,
                this.budgetRequestedBytes,
                this.budgetUsedBytes,
                this.vramBytes,
                0L,
                this.javaVersion,
                this.checksum,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0L,
                0L
            );
        }

        private static WorkerSummary ratioPlaceholder(
            int entityCount,
            int fork,
            String launchOrder
        ) {
            return new WorkerSummary(
                Backend.NATIVE,
                "NATIVE_OVER_HEAP",
                entityCount,
                fork,
                launchOrder,
                ENTITY_OPERATIONS_PER_BATCH / entityCount,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                System.getProperty("java.version"),
                0L,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0L,
                0L
            );
        }

        private static String required(
            Properties properties,
            String key
        ) {
            String value = properties.getProperty(key);
            if (value == null) {
                throw new IllegalStateException(
                    "missing worker property: " + key
                );
            }
            return value;
        }

        private static int integer(
            Properties properties,
            String key
        ) {
            return Integer.parseInt(required(properties, key));
        }

        private static long number(
            Properties properties,
            String key
        ) {
            return Long.parseLong(required(properties, key));
        }

        private static double decimal(
            Properties properties,
            String key
        ) {
            return Double.parseDouble(required(properties, key));
        }
    }
}
