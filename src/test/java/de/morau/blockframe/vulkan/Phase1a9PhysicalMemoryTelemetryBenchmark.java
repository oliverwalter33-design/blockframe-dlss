package de.morau.blockframe.vulkan;

import com.sun.management.ThreadMXBean;
import de.morau.blockframe.core.diagnostics.PhysicalMemoryTelemetry;
import de.morau.blockframe.core.diagnostics.SystemPhysicalMemoryProbe;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Fork-isolated Phase 1A.9 physical-memory telemetry measurements.
 *
 * <p>The benchmark covers the cached-not-due overlay path, a real OS physical
 * RAM refresh, the production device-local heap accumulator with fixed inputs,
 * and a fixed device-local telemetry refresh. The last two are deliberately
 * driver-free: they do not create Vulkan objects or issue a Vulkan query.
 *
 * <p>This benchmark does not launch Minecraft, render a scene, submit GPU work,
 * measure frame time, measure whole-process RSS, measure incremental VRAM, or
 * prove an end-to-end Minecraft performance change.
 */
public final class Phase1a9PhysicalMemoryTelemetryBenchmark {
    private static final int DEFAULT_FORKS = 5;
    private static final int SAMPLE_COUNT = 21;
    private static final long WORKER_TIMEOUT_MINUTES = 5L;
    private static final long SAMPLE_INTERVAL_NANOS = 1_000_000L;
    private static final int DEVICE_LOCAL_HEAP_FLAG = 0x00000001;
    private static final long CHECKSUM_SEED =
        0x243F6A8885A308D3L;
    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    private static final long FIXED_RAM_TOTAL_BYTES = 64L * GIB;
    private static final long FIXED_RAM_AVAILABLE_BYTES = 32L * GIB;
    private static final long LOCAL_HEAP_ZERO_BYTES = 12L * GIB;
    private static final long LOCAL_BUDGET_ZERO_BYTES = 10L * GIB;
    private static final long LOCAL_USAGE_ZERO_BYTES = 6L * GIB;
    private static final long HOST_HEAP_BYTES = 4L * GIB;
    private static final long HOST_BUDGET_BYTES = 3L * GIB;
    private static final long HOST_USAGE_BYTES = 2L * GIB;
    private static final long LOCAL_HEAP_ONE_BYTES = 2L * GIB;
    private static final long LOCAL_BUDGET_ONE_BYTES = 1L * GIB;
    private static final long LOCAL_USAGE_ONE_BYTES = 1_536L * MIB;
    private static final long EXPECTED_DEVICE_HEAP_BYTES = 14L * GIB;
    private static final long EXPECTED_DEVICE_BUDGET_BYTES = 11L * GIB;
    private static final long EXPECTED_DEVICE_USAGE_BYTES =
        7L * GIB + 512L * MIB;
    private static final long EXPECTED_DEVICE_HEADROOM_BYTES = 4L * GIB;
    private static final int EXPECTED_DEVICE_LOCAL_HEAP_COUNT = 2;
    private static final String JVM_FLAGS =
        "xms128m_xmx128m_g1_xbatch_active_processor_count_1";
    private static final String LIMITATIONS =
        "no_minecraft_no_scene_no_vulkan_driver_query_no_gpu_work_"
            + "no_frame_time_no_end_to_end_fps_claim_"
            + "incremental_vram_not_measured_whole_process_rss_not_measured";
    private static final String HEADER = String.join(
        ",",
        "row_type",
        "scenario",
        "backend",
        "implementation",
        "fork",
        "launch_order",
        "p50_ns_per_operation",
        "p95_ns_per_operation",
        "p99_ns_per_operation",
        "allocated_bytes_per_operation",
        "p50_allocated_bytes_per_operation",
        "p95_allocated_bytes_per_operation",
        "p99_allocated_bytes_per_operation",
        "measured_allocated_bytes",
        "gc_collections",
        "gc_pause_ms",
        "checksum",
        "operations",
        "sample_count",
        "ram_status",
        "ram_total_bytes",
        "ram_available_min_bytes",
        "ram_available_max_bytes",
        "device_status",
        "device_heap_bytes",
        "device_budget_bytes",
        "device_usage_bytes",
        "device_headroom_bytes",
        "device_local_heap_count",
        "telemetry_refreshes",
        "ram_samples",
        "device_samples",
        "vulkan_driver_queries",
        "incremental_vram_bytes",
        "whole_process_rss_bytes",
        "worker_pid",
        "jvm_flags",
        "measurement_scope",
        "limitations"
    );

    private static final PhysicalMemoryTelemetry.RamMeasurement
        FIXED_RAM_MEASUREMENT =
            new PhysicalMemoryTelemetry.RamMeasurement(
                FIXED_RAM_TOTAL_BYTES,
                FIXED_RAM_AVAILABLE_BYTES
            );
    private static final PhysicalMemoryTelemetry.DeviceMeasurement
        EXPECTED_DEVICE_MEASUREMENT =
            new PhysicalMemoryTelemetry.DeviceMeasurement(
                EXPECTED_DEVICE_HEAP_BYTES,
                EXPECTED_DEVICE_BUDGET_BYTES,
                EXPECTED_DEVICE_USAGE_BYTES,
                EXPECTED_DEVICE_HEADROOM_BYTES,
                EXPECTED_DEVICE_LOCAL_HEAP_COUNT
            );

    private static volatile PhysicalMemoryTelemetry.Snapshot
        publishedSnapshot;
    private static volatile PhysicalMemoryTelemetry.DeviceMeasurement
        publishedDeviceMeasurement;
    private static volatile long blackhole;

    private Phase1a9PhysicalMemoryTelemetryBenchmark() {
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
                "at least two fresh JVM fork pairs are required"
            );
        }
        Path outputParent = output.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        Path workerDirectory = Path.of(
            System.getProperty("blockframe.projectDir")
        ).resolve("build").resolve(
            "phase1a9-physical-memory-workers-" + System.nanoTime()
        );
        Files.createDirectories(workerDirectory);

        List<Result> results = new ArrayList<>(
            forks * Scenario.values().length * Backend.values().length
        );
        for (Scenario scenario : Scenario.values()) {
            for (int fork = 1; fork <= forks; fork++) {
                boolean productionFirst = (fork & 1) != 0;
                String order = productionFirst
                    ? "production_then_control"
                    : "control_then_production";
                Backend first = productionFirst
                    ? Backend.PRODUCTION
                    : Backend.CONTROL;
                Backend second = productionFirst
                    ? Backend.CONTROL
                    : Backend.PRODUCTION;
                results.add(
                    runFreshWorker(
                        workerDirectory,
                        scenario,
                        fork,
                        order,
                        first
                    )
                );
                results.add(
                    runFreshWorker(
                        workerDirectory,
                        scenario,
                        fork,
                        order,
                        second
                    )
                );
            }
        }
        validate(results, forks);

        StringBuilder csv = new StringBuilder(64_000);
        csv.append(HEADER).append('\n');
        for (Result result : results) {
            appendResult(csv, "worker", result);
        }
        for (Scenario scenario : Scenario.values()) {
            for (Backend backend : Backend.values()) {
                appendResult(
                    csv,
                    "aggregate",
                    aggregate(results, scenario, backend)
                );
            }
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);

        for (Scenario scenario : Scenario.values()) {
            Result production = aggregate(
                results,
                scenario,
                Backend.PRODUCTION
            );
            Result control = aggregate(
                results,
                scenario,
                Backend.CONTROL
            );
            System.out.printf(
                Locale.ROOT,
                "%s production/control p50 %.3f/%.3f ns/op "
                    + "(%.4fx), p95 %.3f/%.3f, p99 %.3f/%.3f, "
                    + "alloc %.3f/%.3f B/op, total %d/%d B, "
                    + "GC %d/%d %.3f/%.3f ms%n",
                scenario.id,
                production.p50NsPerOperation,
                control.p50NsPerOperation,
                production.p50NsPerOperation
                    / control.p50NsPerOperation,
                production.p95NsPerOperation,
                control.p95NsPerOperation,
                production.p99NsPerOperation,
                control.p99NsPerOperation,
                production.allocatedBytesPerOperation,
                control.allocatedBytesPerOperation,
                production.measuredAllocatedBytes,
                control.measuredAllocatedBytes,
                production.gcCollections,
                control.gcCollections,
                (double)production.gcPauseMillis,
                (double)control.gcPauseMillis
            );
        }
        Result osRam = aggregate(
            results,
            Scenario.OS_RAM_REFRESH,
            Backend.PRODUCTION
        );
        System.out.printf(
            Locale.ROOT,
            "Observed OS RAM: total(last worker) %d B, "
                + "available range %d..%d B%n",
            osRam.observation.ramTotalBytes,
            osRam.observation.ramAvailableMinBytes,
            osRam.observation.ramAvailableMaxBytes
        );
        System.out.println(
            "Scope: fork-isolated CPU telemetry primitives; "
                + "device scenarios use fixed inputs and issue zero "
                + "Vulkan driver queries"
        );
        System.out.println(
            "Limits: no Minecraft/GPU/frame-time/FPS claim; "
                + "incremental VRAM and whole-process RSS NOT_MEASURED"
        );
        System.out.println(
            "Blackhole: " + Long.toUnsignedString(blackhole)
        );
        System.out.println("CSV: " + output);
        System.out.println("Worker evidence: " + workerDirectory);
    }

    private static Result runFreshWorker(
        Path directory,
        Scenario scenario,
        int fork,
        String order,
        Backend backend
    ) throws Exception {
        String stem = String.format(
            Locale.ROOT,
            "fork-%02d-%s-%s",
            fork,
            scenario.id,
            backend.id
        );
        Path workerOutput = directory.resolve(stem + ".properties");
        Path workerLog = directory.resolve(stem + ".log");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Xms128m");
        command.add("-Xmx128m");
        command.add("-XX:+UseG1GC");
        command.add("-Xbatch");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(
            Phase1a9PhysicalMemoryTelemetryBenchmark.class.getName()
        );
        command.add("--worker");
        command.add(workerOutput.toString());
        command.add(scenario.id);
        command.add(Integer.toString(fork));
        command.add(order);
        command.add(backend.id);

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(workerLog.toFile())
            .start();
        boolean exited = process.waitFor(
            WORKER_TIMEOUT_MINUTES,
            TimeUnit.MINUTES
        );
        if (!exited) {
            process.destroyForcibly();
            process.waitFor();
            throw new IllegalStateException(
                "benchmark worker timed out: " + stem
            );
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                "benchmark worker failed with exit "
                    + process.exitValue()
                    + ": "
                    + stem
                    + System.lineSeparator()
                    + Files.readString(
                        workerLog,
                        StandardCharsets.UTF_8
                    )
            );
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(workerOutput)) {
            properties.load(input);
        }
        return Result.from(properties);
    }

    private static void runWorker(String[] arguments)
        throws Exception {
        if (arguments.length != 6) {
            throw new IllegalArgumentException(
                "worker expects output, scenario, fork, order and backend"
            );
        }
        Path output = Path.of(arguments[1])
            .toAbsolutePath()
            .normalize();
        Scenario scenario = Scenario.from(arguments[2]);
        int fork = Integer.parseInt(arguments[3]);
        String order = arguments[4];
        Backend backend = Backend.from(arguments[5]);
        ThreadMXBean allocationBean = requiredAllocationBean();
        long threadId = Thread.currentThread().threadId();

        Result result;
        try (Runner runner = scenario.create(backend)) {
            for (
                int round = 0;
                round < scenario.warmupRounds;
                round++
            ) {
                blackhole ^= runner.run(
                    scenario.warmupIterations
                );
            }
            runner.beginMeasurement();

            double[] timeSamples = new double[SAMPLE_COUNT];
            double[] allocationSamples = new double[SAMPLE_COUNT];
            GcSnapshot gcBefore = gcSnapshot();
            long measuredAllocatedBytes = 0L;
            long checksum = CHECKSUM_SEED;
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                long allocatedBefore = allocatedBytes(
                    allocationBean,
                    threadId
                );
                long start = System.nanoTime();
                long sampleChecksum = runner.run(
                    scenario.iterationsPerSample
                );
                long elapsed = System.nanoTime() - start;
                long allocatedAfter = allocatedBytes(
                    allocationBean,
                    threadId
                );
                long sampleAllocatedBytes =
                    allocatedAfter - allocatedBefore;
                timeSamples[sample] =
                    (double)elapsed / scenario.iterationsPerSample;
                allocationSamples[sample] =
                    (double)sampleAllocatedBytes
                        / scenario.iterationsPerSample;
                measuredAllocatedBytes = Math.addExact(
                    measuredAllocatedBytes,
                    sampleAllocatedBytes
                );
                checksum = mix(checksum, sampleChecksum);
            }
            GcSnapshot gcAfter = gcSnapshot();
            Observation observation = runner.observation();
            blackhole ^= checksum;

            Arrays.sort(timeSamples);
            Arrays.sort(allocationSamples);
            long operations =
                (long)SAMPLE_COUNT * scenario.iterationsPerSample;
            result = new Result(
                scenario,
                backend,
                fork,
                order,
                percentile(timeSamples, 0.50D),
                percentile(timeSamples, 0.95D),
                percentile(timeSamples, 0.99D),
                (double)measuredAllocatedBytes / operations,
                percentile(allocationSamples, 0.50D),
                percentile(allocationSamples, 0.95D),
                percentile(allocationSamples, 0.99D),
                measuredAllocatedBytes,
                Math.max(
                    0L,
                    gcAfter.collections - gcBefore.collections
                ),
                Math.max(
                    0L,
                    gcAfter.collectionMillis
                        - gcBefore.collectionMillis
                ),
                checksum,
                operations,
                SAMPLE_COUNT,
                observation,
                0L,
                "NOT_MEASURED",
                "NOT_MEASURED",
                ProcessHandle.current().pid(),
                JVM_FLAGS,
                timeSamples,
                allocationSamples
            );
        }

        Path outputParent = output.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            result.toProperties().store(
                stream,
                "Phase 1A.9 physical-memory telemetry worker"
            );
        }
    }

    private static void validate(
        List<Result> results,
        int forks
    ) {
        int expected =
            forks * Scenario.values().length * Backend.values().length;
        if (results.size() != expected) {
            throw new IllegalStateException(
                "expected "
                    + expected
                    + " workers, got "
                    + results.size()
            );
        }
        for (Result result : results) {
            if (
                !Double.isFinite(result.p50NsPerOperation)
                    || !Double.isFinite(result.p95NsPerOperation)
                    || !Double.isFinite(result.p99NsPerOperation)
                    || result.p50NsPerOperation <= 0.0D
                    || result.allocatedBytesPerOperation < 0.0D
                    || result.vulkanDriverQueries != 0L
            ) {
                throw new IllegalStateException(
                    "invalid worker metrics: "
                        + result.scenario.id
                        + "/"
                        + result.backend.id
                        + " fork="
                        + result.fork
                );
            }
        }
        for (Scenario scenario : Scenario.values()) {
            for (int fork = 1; fork <= forks; fork++) {
                Result production = find(
                    results,
                    scenario,
                    fork,
                    Backend.PRODUCTION
                );
                Result control = find(
                    results,
                    scenario,
                    fork,
                    Backend.CONTROL
                );
                if (
                    scenario.equivalentChecksum
                        && production.checksum != control.checksum
                ) {
                    throw new IllegalStateException(
                        "semantic checksum mismatch for "
                            + scenario.id
                            + " fork="
                            + fork
                    );
                }
                validateObservation(production);
                validateObservation(control);
            }
        }
    }

    private static void validateObservation(Result result) {
        Observation observation = result.observation;
        switch (result.scenario) {
            case CACHED_NOT_DUE -> {
                if (
                    !"AVAILABLE".equals(observation.ramStatus)
                        || observation.ramTotalBytes
                            != FIXED_RAM_TOTAL_BYTES
                        || observation.ramAvailableMinBytes
                            != FIXED_RAM_AVAILABLE_BYTES
                        || observation.ramAvailableMaxBytes
                            != FIXED_RAM_AVAILABLE_BYTES
                        || observation.telemetryRefreshes != 1L
                        || observation.ramSamples != 1L
                        || !"NOT_REQUESTED".equals(
                            observation.deviceStatus
                        )
                ) {
                    throw new IllegalStateException(
                        "cached-not-due contract changed"
                    );
                }
            }
            case OS_RAM_REFRESH -> {
                if (
                    !"AVAILABLE".equals(observation.ramStatus)
                        || observation.ramTotalBytes <= 0L
                        || observation.ramAvailableMinBytes < 0L
                        || observation.ramAvailableMaxBytes
                            < observation.ramAvailableMinBytes
                        || observation.ramAvailableMaxBytes
                            > observation.ramTotalBytes
                ) {
                    throw new IllegalStateException(
                        "invalid OS RAM observation for "
                            + result.backend.id
                    );
                }
            }
            case FIXED_DEVICE_LOCAL_AGGREGATION -> {
                validateFixedDevice(observation, "AGGREGATED");
            }
            case FIXED_DEVICE_TELEMETRY_REFRESH -> {
                validateFixedDevice(observation, "AVAILABLE");
                if (observation.deviceSamples <= 0L) {
                    throw new IllegalStateException(
                        "device telemetry did not refresh"
                    );
                }
            }
        }
    }

    private static void validateFixedDevice(
        Observation observation,
        String expectedStatus
    ) {
        if (
            !expectedStatus.equals(observation.deviceStatus)
                || observation.deviceHeapBytes
                    != EXPECTED_DEVICE_HEAP_BYTES
                || observation.deviceBudgetBytes
                    != EXPECTED_DEVICE_BUDGET_BYTES
                || observation.deviceUsageBytes
                    != EXPECTED_DEVICE_USAGE_BYTES
                || observation.deviceHeadroomBytes
                    != EXPECTED_DEVICE_HEADROOM_BYTES
                || observation.deviceLocalHeapCount
                    != EXPECTED_DEVICE_LOCAL_HEAP_COUNT
        ) {
            throw new IllegalStateException(
                "fixed device-local aggregation contract changed"
            );
        }
    }

    private static Result aggregate(
        List<Result> results,
        Scenario scenario,
        Backend backend
    ) {
        List<Result> selected = results.stream()
            .filter(
                result ->
                    result.scenario == scenario
                        && result.backend == backend
            )
            .toList();
        double[] timeSamples = selected.stream()
            .flatMapToDouble(
                result -> Arrays.stream(result.timeSamples)
            )
            .sorted()
            .toArray();
        double[] allocationSamples = selected.stream()
            .flatMapToDouble(
                result -> Arrays.stream(
                    result.allocationSamples
                )
            )
            .sorted()
            .toArray();
        long operations = selected.stream()
            .mapToLong(result -> result.operations)
            .sum();
        long allocated = selected.stream()
            .mapToLong(result -> result.measuredAllocatedBytes)
            .sum();
        Observation observation = aggregateObservation(selected);
        long checksum = scenario.equivalentChecksum
            ? selected.get(0).checksum
            : selected.stream()
                .mapToLong(result -> result.checksum)
                .reduce(CHECKSUM_SEED, Phase1a9PhysicalMemoryTelemetryBenchmark::mix);
        return new Result(
            scenario,
            backend,
            0,
            "alternating_fresh_jvms",
            percentile(timeSamples, 0.50D),
            percentile(timeSamples, 0.95D),
            percentile(timeSamples, 0.99D),
            (double)allocated / operations,
            percentile(allocationSamples, 0.50D),
            percentile(allocationSamples, 0.95D),
            percentile(allocationSamples, 0.99D),
            allocated,
            selected.stream()
                .mapToLong(result -> result.gcCollections)
                .sum(),
            selected.stream()
                .mapToLong(result -> result.gcPauseMillis)
                .sum(),
            checksum,
            operations,
            timeSamples.length,
            observation,
            0L,
            "NOT_MEASURED",
            "NOT_MEASURED",
            0L,
            "fresh_jvms_same_flags",
            timeSamples,
            allocationSamples
        );
    }

    private static Observation aggregateObservation(
        List<Result> results
    ) {
        Observation representative = results.get(0).observation;
        long availableMin = results.stream()
            .mapToLong(
                result ->
                    result.observation.ramAvailableMinBytes
            )
            .filter(value -> value >= 0L)
            .min()
            .orElse(-1L);
        long availableMax = results.stream()
            .mapToLong(
                result ->
                    result.observation.ramAvailableMaxBytes
            )
            .filter(value -> value >= 0L)
            .max()
            .orElse(-1L);
        return new Observation(
            representative.ramStatus,
            representative.ramTotalBytes,
            availableMin,
            availableMax,
            representative.deviceStatus,
            representative.deviceHeapBytes,
            representative.deviceBudgetBytes,
            representative.deviceUsageBytes,
            representative.deviceHeadroomBytes,
            representative.deviceLocalHeapCount,
            results.stream()
                .mapToLong(
                    result ->
                        result.observation.telemetryRefreshes
                )
                .sum(),
            results.stream()
                .mapToLong(
                    result -> result.observation.ramSamples
                )
                .sum(),
            results.stream()
                .mapToLong(
                    result -> result.observation.deviceSamples
                )
                .sum()
        );
    }

    private static Result find(
        List<Result> results,
        Scenario scenario,
        int fork,
        Backend backend
    ) {
        return results.stream()
            .filter(
                result ->
                    result.scenario == scenario
                        && result.fork == fork
                        && result.backend == backend
            )
            .findFirst()
            .orElseThrow();
    }

    private static void appendResult(
        StringBuilder csv,
        String rowType,
        Result result
    ) {
        Observation observation = result.observation;
        csv.append(rowType).append(',')
            .append(result.scenario.id).append(',')
            .append(result.backend.id).append(',')
            .append(
                result.scenario.implementation(result.backend)
            )
            .append(',')
            .append(result.fork).append(',')
            .append(result.launchOrder).append(',')
            .append(decimal(result.p50NsPerOperation)).append(',')
            .append(decimal(result.p95NsPerOperation)).append(',')
            .append(decimal(result.p99NsPerOperation)).append(',')
            .append(
                decimal(result.allocatedBytesPerOperation)
            )
            .append(',')
            .append(
                decimal(
                    result.p50AllocatedBytesPerOperation
                )
            )
            .append(',')
            .append(
                decimal(
                    result.p95AllocatedBytesPerOperation
                )
            )
            .append(',')
            .append(
                decimal(
                    result.p99AllocatedBytesPerOperation
                )
            )
            .append(',')
            .append(result.measuredAllocatedBytes).append(',')
            .append(result.gcCollections).append(',')
            .append(decimal(result.gcPauseMillis)).append(',')
            .append(Long.toUnsignedString(result.checksum))
            .append(',')
            .append(result.operations).append(',')
            .append(result.sampleCount).append(',')
            .append(observation.ramStatus).append(',')
            .append(observation.ramTotalBytes).append(',')
            .append(observation.ramAvailableMinBytes).append(',')
            .append(observation.ramAvailableMaxBytes).append(',')
            .append(observation.deviceStatus).append(',')
            .append(observation.deviceHeapBytes).append(',')
            .append(observation.deviceBudgetBytes).append(',')
            .append(observation.deviceUsageBytes).append(',')
            .append(observation.deviceHeadroomBytes).append(',')
            .append(observation.deviceLocalHeapCount).append(',')
            .append(observation.telemetryRefreshes).append(',')
            .append(observation.ramSamples).append(',')
            .append(observation.deviceSamples).append(',')
            .append(result.vulkanDriverQueries).append(',')
            .append(result.incrementalVramBytes).append(',')
            .append(result.wholeProcessRssBytes).append(',')
            .append(result.workerPid).append(',')
            .append(result.jvmFlags).append(',')
            .append(result.scenario.scope).append(',')
            .append(LIMITATIONS)
            .append('\n');
    }

    private static PhysicalMemoryTelemetry.DeviceMeasurement
        aggregateFixedDeviceLocalHeaps(
            VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator
                accumulator
        ) {
        accumulator.reset();
        accumulator.addHeap(
            LOCAL_HEAP_ZERO_BYTES,
            DEVICE_LOCAL_HEAP_FLAG,
            LOCAL_BUDGET_ZERO_BYTES,
            LOCAL_USAGE_ZERO_BYTES
        );
        accumulator.addHeap(
            HOST_HEAP_BYTES,
            0,
            HOST_BUDGET_BYTES,
            HOST_USAGE_BYTES
        );
        accumulator.addHeap(
            LOCAL_HEAP_ONE_BYTES,
            DEVICE_LOCAL_HEAP_FLAG,
            LOCAL_BUDGET_ONE_BYTES,
            LOCAL_USAGE_ONE_BYTES
        );
        return accumulator.finish();
    }

    private static long checksumSnapshot(
        PhysicalMemoryTelemetry.Snapshot snapshot,
        int index,
        long checksum
    ) {
        long value = snapshot.ramStatus().ordinal();
        value = mix(value, snapshot.ramTotalBytes());
        value = mix(value, snapshot.ramAvailableBytes());
        value = mix(value, snapshot.deviceStatus().ordinal());
        value = mix(value, snapshot.deviceHeapBytes());
        value = mix(value, snapshot.deviceBudgetBytes());
        value = mix(value, snapshot.deviceUsageBytes());
        value = mix(value, snapshot.deviceHeadroomBytes());
        value = mix(value, snapshot.deviceLocalHeapCount());
        return mix(checksum, value ^ Integer.toUnsignedLong(index));
    }

    private static long checksumDevice(
        PhysicalMemoryTelemetry.DeviceMeasurement measurement,
        int index,
        long checksum
    ) {
        long value = measurement.heapBytes();
        value = mix(value, measurement.budgetBytes());
        value = mix(value, measurement.usageBytes());
        value = mix(value, measurement.headroomBytes());
        value = mix(value, measurement.deviceLocalHeapCount());
        return mix(checksum, value ^ Integer.toUnsignedLong(index));
    }

    private static long mix(long left, long right) {
        return Long.rotateLeft(
            left ^ right ^ 0x9E3779B97F4A7C15L,
            17
        ) * 0xD6E8FEB86659FD93L;
    }

    private static ThreadMXBean requiredAllocationBean() {
        java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean allocationBean)) {
            throw new IllegalStateException(
                "ThreadMXBean allocation counters are unavailable"
            );
        }
        if (!allocationBean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException(
                "thread allocation measurement is unsupported"
            );
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    private static long allocatedBytes(
        ThreadMXBean bean,
        long threadId
    ) {
        long value = bean.getThreadAllocatedBytes(threadId);
        if (value < 0L) {
            throw new IllegalStateException(
                "thread allocation counter returned " + value
            );
        }
        return value;
    }

    private static GcSnapshot gcSnapshot() {
        long collections = 0L;
        long millis = 0L;
        for (
            GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()
        ) {
            collections += Math.max(
                0L,
                bean.getCollectionCount()
            );
            millis += Math.max(
                0L,
                bean.getCollectionTime()
            );
        }
        return new GcSnapshot(collections, millis);
    }

    private static double percentile(
        double[] sorted,
        double quantile
    ) {
        if (sorted.length == 0) {
            return 0.0D;
        }
        int index = (int)Math.ceil(quantile * sorted.length) - 1;
        return sorted[
            Math.max(0, Math.min(index, sorted.length - 1))
        ];
    }

    private static Path javaExecutable() {
        return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name")
                    .toLowerCase(Locale.ROOT)
                    .contains("win")
                ? "java.exe"
                : "java"
        );
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private interface Runner extends AutoCloseable {
        long run(int iterations);

        default void beginMeasurement() {
        }

        Observation observation();

        @Override
        default void close() {
        }
    }

    private static final class CachedProductionRunner
        implements Runner {
        private final PhysicalMemoryTelemetry telemetry;

        private CachedProductionRunner() {
            this.telemetry = new PhysicalMemoryTelemetry(
                () -> FIXED_RAM_MEASUREMENT,
                () -> 17L,
                SAMPLE_INTERVAL_NANOS
            );
            PhysicalMemoryTelemetry.Snapshot primed =
                this.telemetry.sampleIfDue();
            if (
                primed.refreshes() != 1L
                    || primed.ramSamples() != 1L
            ) {
                throw new IllegalStateException(
                    "production cached runner did not prime"
                );
            }
        }

        @Override
        public long run(int iterations) {
            long checksum = CHECKSUM_SEED;
            for (int index = 0; index < iterations; index++) {
                PhysicalMemoryTelemetry.Snapshot snapshot =
                    this.telemetry.sampleIfDue();
                checksum = checksumSnapshot(
                    snapshot,
                    index,
                    checksum
                );
            }
            publishedSnapshot = this.telemetry.snapshot();
            return checksum;
        }

        @Override
        public Observation observation() {
            return Observation.fromSnapshot(
                this.telemetry.snapshot(),
                FIXED_RAM_AVAILABLE_BYTES,
                FIXED_RAM_AVAILABLE_BYTES
            );
        }

        @Override
        public void close() {
            this.telemetry.close();
        }
    }

    private static final class CachedControlRunner
        implements Runner {
        private final MinimalCachedGate gate =
            new MinimalCachedGate(expectedCachedSnapshot(), () -> 17L);

        @Override
        public long run(int iterations) {
            long checksum = CHECKSUM_SEED;
            for (int index = 0; index < iterations; index++) {
                PhysicalMemoryTelemetry.Snapshot snapshot =
                    this.gate.sampleIfDue();
                checksum = checksumSnapshot(
                    snapshot,
                    index,
                    checksum
                );
            }
            publishedSnapshot = this.gate.snapshot();
            return checksum;
        }

        @Override
        public Observation observation() {
            return Observation.fromSnapshot(
                this.gate.snapshot(),
                FIXED_RAM_AVAILABLE_BYTES,
                FIXED_RAM_AVAILABLE_BYTES
            );
        }
    }

    private static final class RamRefreshRunner implements Runner {
        private final PhysicalMemoryTelemetry telemetry;
        private long availableMin = Long.MAX_VALUE;
        private long availableMax = -1L;

        private RamRefreshRunner(boolean production) {
            PhysicalMemoryTelemetry.RamProbe probe;
            if (production) {
                probe = SystemPhysicalMemoryProbe.tryCreate();
                if (probe == null) {
                    throw new IllegalStateException(
                        "SystemPhysicalMemoryProbe is unsupported"
                    );
                }
            } else {
                probe = () -> FIXED_RAM_MEASUREMENT;
            }
            this.telemetry = new PhysicalMemoryTelemetry(
                probe,
                new StepClock(SAMPLE_INTERVAL_NANOS),
                SAMPLE_INTERVAL_NANOS
            );
        }

        @Override
        public void beginMeasurement() {
            this.availableMin = Long.MAX_VALUE;
            this.availableMax = -1L;
        }

        @Override
        public long run(int iterations) {
            long checksum = CHECKSUM_SEED;
            for (int index = 0; index < iterations; index++) {
                PhysicalMemoryTelemetry.Snapshot snapshot =
                    this.telemetry.sampleIfDue();
                if (
                    snapshot.ramStatus()
                        != PhysicalMemoryTelemetry.RamStatus.AVAILABLE
                ) {
                    throw new IllegalStateException(
                        "RAM refresh became unavailable: "
                            + snapshot.ramStatus()
                    );
                }
                long available = snapshot.ramAvailableBytes();
                this.availableMin = Math.min(
                    this.availableMin,
                    available
                );
                this.availableMax = Math.max(
                    this.availableMax,
                    available
                );
                checksum = checksumSnapshot(
                    snapshot,
                    index,
                    checksum
                );
            }
            publishedSnapshot = this.telemetry.snapshot();
            return checksum;
        }

        @Override
        public Observation observation() {
            PhysicalMemoryTelemetry.Snapshot snapshot =
                this.telemetry.snapshot();
            return Observation.fromSnapshot(
                snapshot,
                this.availableMin == Long.MAX_VALUE
                    ? snapshot.ramAvailableBytes()
                    : this.availableMin,
                this.availableMax < 0L
                    ? snapshot.ramAvailableBytes()
                    : this.availableMax
            );
        }

        @Override
        public void close() {
            this.telemetry.close();
        }
    }

    private static final class DeviceAggregationRunner
        implements Runner {
        private final boolean production;
        private final VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator
            accumulator;
        private PhysicalMemoryTelemetry.DeviceMeasurement last =
            EXPECTED_DEVICE_MEASUREMENT;

        private DeviceAggregationRunner(boolean production) {
            this.production = production;
            this.accumulator = production
                ? new VulkanMemoryBudgetProbe
                    .DeviceLocalHeapAccumulator()
                : null;
        }

        @Override
        public long run(int iterations) {
            long checksum = CHECKSUM_SEED;
            for (int index = 0; index < iterations; index++) {
                PhysicalMemoryTelemetry.DeviceMeasurement measurement =
                    this.production
                        ? aggregateFixedDeviceLocalHeaps(
                            this.accumulator
                        )
                        : EXPECTED_DEVICE_MEASUREMENT;
                checksum = checksumDevice(
                    measurement,
                    index,
                    checksum
                );
                this.last = measurement;
            }
            publishedDeviceMeasurement = this.last;
            return checksum;
        }

        @Override
        public Observation observation() {
            return Observation.fromDeviceMeasurement(
                this.last,
                "AGGREGATED"
            );
        }
    }

    private static final class DeviceTelemetryRefreshRunner
        implements Runner {
        private final Object owner = new Object();
        private final PhysicalMemoryTelemetry telemetry;

        private DeviceTelemetryRefreshRunner(boolean production) {
            VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator
                accumulator = production
                    ? new VulkanMemoryBudgetProbe
                        .DeviceLocalHeapAccumulator()
                    : null;
            PhysicalMemoryTelemetry.DeviceProbe probe = production
                ? () -> aggregateFixedDeviceLocalHeaps(accumulator)
                : () -> EXPECTED_DEVICE_MEASUREMENT;
            this.telemetry = new PhysicalMemoryTelemetry(
                null,
                new StepClock(SAMPLE_INTERVAL_NANOS),
                SAMPLE_INTERVAL_NANOS
            );
            if (
                !this.telemetry.attachVulkanDevice(
                    this.owner,
                    true,
                    probe
                )
            ) {
                throw new IllegalStateException(
                    "fixed device telemetry did not attach"
                );
            }
        }

        @Override
        public long run(int iterations) {
            long checksum = CHECKSUM_SEED;
            for (int index = 0; index < iterations; index++) {
                PhysicalMemoryTelemetry.Snapshot snapshot =
                    this.telemetry.sampleIfDue();
                if (
                    snapshot.deviceStatus()
                        != PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE
                ) {
                    throw new IllegalStateException(
                        "device refresh became unavailable: "
                            + snapshot.deviceStatus()
                    );
                }
                checksum = checksumSnapshot(
                    snapshot,
                    index,
                    checksum
                );
            }
            publishedSnapshot = this.telemetry.snapshot();
            return checksum;
        }

        @Override
        public Observation observation() {
            return Observation.fromSnapshot(
                this.telemetry.snapshot(),
                -1L,
                -1L
            );
        }

        @Override
        public void close() {
            if (!this.telemetry.vulkanDeviceClosing(this.owner)) {
                throw new IllegalStateException(
                    "fixed device telemetry owner did not close"
                );
            }
            this.telemetry.close();
        }
    }

    private static final class MinimalCachedGate {
        private final PhysicalMemoryTelemetry.Snapshot snapshot;
        private final LongSupplier clock;
        private final long lastSampleNanos;

        private MinimalCachedGate(
            PhysicalMemoryTelemetry.Snapshot snapshot,
            LongSupplier clock
        ) {
            this.snapshot = snapshot;
            this.clock = clock;
            this.lastSampleNanos = clock.getAsLong();
        }

        private synchronized PhysicalMemoryTelemetry.Snapshot
            sampleIfDue() {
            long now = this.clock.getAsLong();
            long elapsed = now - this.lastSampleNanos;
            if (
                elapsed >= 0L
                    && elapsed < SAMPLE_INTERVAL_NANOS
            ) {
                return this.snapshot;
            }
            throw new AssertionError(
                "minimal cached control unexpectedly became due"
            );
        }

        private PhysicalMemoryTelemetry.Snapshot snapshot() {
            return this.snapshot;
        }
    }

    private static final class StepClock implements LongSupplier {
        private final long step;
        private long current;

        private StepClock(long step) {
            this.step = step;
        }

        @Override
        public long getAsLong() {
            this.current = Math.addExact(this.current, this.step);
            return this.current;
        }
    }

    private static PhysicalMemoryTelemetry.Snapshot
        expectedCachedSnapshot() {
        return new PhysicalMemoryTelemetry.Snapshot(
            PhysicalMemoryTelemetry.RamStatus.AVAILABLE,
            FIXED_RAM_TOTAL_BYTES,
            FIXED_RAM_AVAILABLE_BYTES,
            PhysicalMemoryTelemetry.DeviceStatus.NOT_REQUESTED,
            0L,
            0L,
            0L,
            0L,
            0,
            1L,
            1L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L
        );
    }

    private enum Scenario {
        CACHED_NOT_DUE(
            "cached_not_due",
            5,
            100_000,
            100_000,
            true,
            "throttled_cached_overlay_cpu_path",
            "physical_memory_telemetry_sample_if_due",
            "minimal_synchronized_cached_gate"
        ) {
            @Override
            Runner create(Backend backend) {
                return backend == Backend.PRODUCTION
                    ? new CachedProductionRunner()
                    : new CachedControlRunner();
            }
        },
        OS_RAM_REFRESH(
            "os_ram_refresh",
            3,
            250,
            500,
            false,
            "due_refresh_cpu_path_real_os_ram_probe_vs_fixed_probe",
            "system_physical_memory_probe",
            "fixed_ram_measurement_refresh_control"
        ) {
            @Override
            Runner create(Backend backend) {
                return new RamRefreshRunner(
                    backend == Backend.PRODUCTION
                );
            }
        },
        FIXED_DEVICE_LOCAL_AGGREGATION(
            "fixed_device_local_aggregation",
            5,
            50_000,
            50_000,
            true,
            "driver_free_fixed_device_local_heap_cpu_aggregation",
            "production_device_local_heap_accumulator",
            "precomputed_device_measurement_control"
        ) {
            @Override
            Runner create(Backend backend) {
                return new DeviceAggregationRunner(
                    backend == Backend.PRODUCTION
                );
            }
        },
        FIXED_DEVICE_TELEMETRY_REFRESH(
            "fixed_device_telemetry_refresh",
            5,
            10_000,
            10_000,
            true,
            "driver_free_due_device_telemetry_cpu_refresh",
            "telemetry_plus_production_heap_accumulator",
            "telemetry_plus_precomputed_device_measurement"
        ) {
            @Override
            Runner create(Backend backend) {
                return new DeviceTelemetryRefreshRunner(
                    backend == Backend.PRODUCTION
                );
            }
        };

        private final String id;
        private final int warmupRounds;
        private final int warmupIterations;
        private final int iterationsPerSample;
        private final boolean equivalentChecksum;
        private final String scope;
        private final String productionImplementation;
        private final String controlImplementation;

        Scenario(
            String id,
            int warmupRounds,
            int warmupIterations,
            int iterationsPerSample,
            boolean equivalentChecksum,
            String scope,
            String productionImplementation,
            String controlImplementation
        ) {
            this.id = id;
            this.warmupRounds = warmupRounds;
            this.warmupIterations = warmupIterations;
            this.iterationsPerSample = iterationsPerSample;
            this.equivalentChecksum = equivalentChecksum;
            this.scope = scope;
            this.productionImplementation = productionImplementation;
            this.controlImplementation = controlImplementation;
        }

        abstract Runner create(Backend backend);

        private String implementation(Backend backend) {
            return backend == Backend.PRODUCTION
                ? this.productionImplementation
                : this.controlImplementation;
        }

        private static Scenario from(String id) {
            for (Scenario scenario : values()) {
                if (scenario.id.equals(id)) {
                    return scenario;
                }
            }
            throw new IllegalArgumentException(
                "unknown scenario " + id
            );
        }
    }

    private enum Backend {
        PRODUCTION("production"),
        CONTROL("control");

        private final String id;

        Backend(String id) {
            this.id = id;
        }

        private static Backend from(String id) {
            for (Backend backend : values()) {
                if (backend.id.equals(id)) {
                    return backend;
                }
            }
            throw new IllegalArgumentException(
                "unknown backend " + id
            );
        }
    }

    private record GcSnapshot(
        long collections,
        long collectionMillis
    ) {
    }

    private record Observation(
        String ramStatus,
        long ramTotalBytes,
        long ramAvailableMinBytes,
        long ramAvailableMaxBytes,
        String deviceStatus,
        long deviceHeapBytes,
        long deviceBudgetBytes,
        long deviceUsageBytes,
        long deviceHeadroomBytes,
        int deviceLocalHeapCount,
        long telemetryRefreshes,
        long ramSamples,
        long deviceSamples
    ) {
        private static Observation fromSnapshot(
            PhysicalMemoryTelemetry.Snapshot snapshot,
            long availableMin,
            long availableMax
        ) {
            return new Observation(
                snapshot.ramStatus().name(),
                snapshot.ramTotalBytes(),
                availableMin,
                availableMax,
                snapshot.deviceStatus().name(),
                snapshot.deviceHeapBytes(),
                snapshot.deviceBudgetBytes(),
                snapshot.deviceUsageBytes(),
                snapshot.deviceHeadroomBytes(),
                snapshot.deviceLocalHeapCount(),
                snapshot.refreshes(),
                snapshot.ramSamples(),
                snapshot.deviceSamples()
            );
        }

        private static Observation fromDeviceMeasurement(
            PhysicalMemoryTelemetry.DeviceMeasurement measurement,
            String status
        ) {
            return new Observation(
                "NOT_APPLICABLE",
                -1L,
                -1L,
                -1L,
                status,
                measurement.heapBytes(),
                measurement.budgetBytes(),
                measurement.usageBytes(),
                measurement.headroomBytes(),
                measurement.deviceLocalHeapCount(),
                0L,
                0L,
                0L
            );
        }
    }

    private record Result(
        Scenario scenario,
        Backend backend,
        int fork,
        String launchOrder,
        double p50NsPerOperation,
        double p95NsPerOperation,
        double p99NsPerOperation,
        double allocatedBytesPerOperation,
        double p50AllocatedBytesPerOperation,
        double p95AllocatedBytesPerOperation,
        double p99AllocatedBytesPerOperation,
        long measuredAllocatedBytes,
        long gcCollections,
        long gcPauseMillis,
        long checksum,
        long operations,
        int sampleCount,
        Observation observation,
        long vulkanDriverQueries,
        String incrementalVramBytes,
        String wholeProcessRssBytes,
        long workerPid,
        String jvmFlags,
        double[] timeSamples,
        double[] allocationSamples
    ) {
        private Properties toProperties() {
            Properties properties = new Properties();
            properties.setProperty("scenario", this.scenario.id);
            properties.setProperty("backend", this.backend.id);
            properties.setProperty(
                "fork",
                Integer.toString(this.fork)
            );
            properties.setProperty(
                "launchOrder",
                this.launchOrder
            );
            properties.setProperty(
                "p50",
                Double.toString(this.p50NsPerOperation)
            );
            properties.setProperty(
                "p95",
                Double.toString(this.p95NsPerOperation)
            );
            properties.setProperty(
                "p99",
                Double.toString(this.p99NsPerOperation)
            );
            properties.setProperty(
                "allocatedBytesPerOperation",
                Double.toString(
                    this.allocatedBytesPerOperation
                )
            );
            properties.setProperty(
                "p50AllocatedBytesPerOperation",
                Double.toString(
                    this.p50AllocatedBytesPerOperation
                )
            );
            properties.setProperty(
                "p95AllocatedBytesPerOperation",
                Double.toString(
                    this.p95AllocatedBytesPerOperation
                )
            );
            properties.setProperty(
                "p99AllocatedBytesPerOperation",
                Double.toString(
                    this.p99AllocatedBytesPerOperation
                )
            );
            properties.setProperty(
                "measuredAllocatedBytes",
                Long.toString(this.measuredAllocatedBytes)
            );
            properties.setProperty(
                "gcCollections",
                Long.toString(this.gcCollections)
            );
            properties.setProperty(
                "gcPauseMillis",
                Long.toString(this.gcPauseMillis)
            );
            properties.setProperty(
                "checksum",
                Long.toUnsignedString(this.checksum)
            );
            properties.setProperty(
                "operations",
                Long.toString(this.operations)
            );
            properties.setProperty(
                "sampleCount",
                Integer.toString(this.sampleCount)
            );
            properties.setProperty(
                "ramStatus",
                this.observation.ramStatus
            );
            properties.setProperty(
                "ramTotalBytes",
                Long.toString(this.observation.ramTotalBytes)
            );
            properties.setProperty(
                "ramAvailableMinBytes",
                Long.toString(
                    this.observation.ramAvailableMinBytes
                )
            );
            properties.setProperty(
                "ramAvailableMaxBytes",
                Long.toString(
                    this.observation.ramAvailableMaxBytes
                )
            );
            properties.setProperty(
                "deviceStatus",
                this.observation.deviceStatus
            );
            properties.setProperty(
                "deviceHeapBytes",
                Long.toString(this.observation.deviceHeapBytes)
            );
            properties.setProperty(
                "deviceBudgetBytes",
                Long.toString(this.observation.deviceBudgetBytes)
            );
            properties.setProperty(
                "deviceUsageBytes",
                Long.toString(this.observation.deviceUsageBytes)
            );
            properties.setProperty(
                "deviceHeadroomBytes",
                Long.toString(
                    this.observation.deviceHeadroomBytes
                )
            );
            properties.setProperty(
                "deviceLocalHeapCount",
                Integer.toString(
                    this.observation.deviceLocalHeapCount
                )
            );
            properties.setProperty(
                "telemetryRefreshes",
                Long.toString(
                    this.observation.telemetryRefreshes
                )
            );
            properties.setProperty(
                "ramSamples",
                Long.toString(this.observation.ramSamples)
            );
            properties.setProperty(
                "deviceSamples",
                Long.toString(this.observation.deviceSamples)
            );
            properties.setProperty(
                "vulkanDriverQueries",
                Long.toString(this.vulkanDriverQueries)
            );
            properties.setProperty(
                "incrementalVramBytes",
                this.incrementalVramBytes
            );
            properties.setProperty(
                "wholeProcessRssBytes",
                this.wholeProcessRssBytes
            );
            properties.setProperty(
                "workerPid",
                Long.toString(this.workerPid)
            );
            properties.setProperty("jvmFlags", this.jvmFlags);
            properties.setProperty(
                "timeSamples",
                join(this.timeSamples)
            );
            properties.setProperty(
                "allocationSamples",
                join(this.allocationSamples)
            );
            return properties;
        }

        private static Result from(Properties properties) {
            Observation observation = new Observation(
                properties.getProperty("ramStatus"),
                Long.parseLong(
                    properties.getProperty("ramTotalBytes")
                ),
                Long.parseLong(
                    properties.getProperty(
                        "ramAvailableMinBytes"
                    )
                ),
                Long.parseLong(
                    properties.getProperty(
                        "ramAvailableMaxBytes"
                    )
                ),
                properties.getProperty("deviceStatus"),
                Long.parseLong(
                    properties.getProperty("deviceHeapBytes")
                ),
                Long.parseLong(
                    properties.getProperty("deviceBudgetBytes")
                ),
                Long.parseLong(
                    properties.getProperty("deviceUsageBytes")
                ),
                Long.parseLong(
                    properties.getProperty(
                        "deviceHeadroomBytes"
                    )
                ),
                Integer.parseInt(
                    properties.getProperty(
                        "deviceLocalHeapCount"
                    )
                ),
                Long.parseLong(
                    properties.getProperty(
                        "telemetryRefreshes"
                    )
                ),
                Long.parseLong(
                    properties.getProperty("ramSamples")
                ),
                Long.parseLong(
                    properties.getProperty("deviceSamples")
                )
            );
            return new Result(
                Scenario.from(properties.getProperty("scenario")),
                Backend.from(properties.getProperty("backend")),
                Integer.parseInt(
                    properties.getProperty("fork")
                ),
                properties.getProperty("launchOrder"),
                Double.parseDouble(
                    properties.getProperty("p50")
                ),
                Double.parseDouble(
                    properties.getProperty("p95")
                ),
                Double.parseDouble(
                    properties.getProperty("p99")
                ),
                Double.parseDouble(
                    properties.getProperty(
                        "allocatedBytesPerOperation"
                    )
                ),
                Double.parseDouble(
                    properties.getProperty(
                        "p50AllocatedBytesPerOperation"
                    )
                ),
                Double.parseDouble(
                    properties.getProperty(
                        "p95AllocatedBytesPerOperation"
                    )
                ),
                Double.parseDouble(
                    properties.getProperty(
                        "p99AllocatedBytesPerOperation"
                    )
                ),
                Long.parseLong(
                    properties.getProperty(
                        "measuredAllocatedBytes"
                    )
                ),
                Long.parseLong(
                    properties.getProperty("gcCollections")
                ),
                Long.parseLong(
                    properties.getProperty("gcPauseMillis")
                ),
                Long.parseUnsignedLong(
                    properties.getProperty("checksum")
                ),
                Long.parseLong(
                    properties.getProperty("operations")
                ),
                Integer.parseInt(
                    properties.getProperty("sampleCount")
                ),
                observation,
                Long.parseLong(
                    properties.getProperty("vulkanDriverQueries")
                ),
                properties.getProperty("incrementalVramBytes"),
                properties.getProperty("wholeProcessRssBytes"),
                Long.parseLong(
                    properties.getProperty("workerPid")
                ),
                properties.getProperty("jvmFlags"),
                parseSamples(
                    properties.getProperty("timeSamples")
                ),
                parseSamples(
                    properties.getProperty("allocationSamples")
                )
            );
        }

        private static String join(double[] values) {
            StringBuilder result = new StringBuilder(
                values.length * 16
            );
            for (int index = 0; index < values.length; index++) {
                if (index != 0) {
                    result.append(';');
                }
                result.append(values[index]);
            }
            return result.toString();
        }

        private static double[] parseSamples(String value) {
            String[] parts = value.split(";");
            double[] result = new double[parts.length];
            for (int index = 0; index < parts.length; index++) {
                result[index] = Double.parseDouble(parts[index]);
            }
            return result;
        }
    }
}
