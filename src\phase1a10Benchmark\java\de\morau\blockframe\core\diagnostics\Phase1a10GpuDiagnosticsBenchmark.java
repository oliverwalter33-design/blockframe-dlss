package de.morau.blockframe.core.diagnostics;

import com.mojang.blaze3d.vulkan.VulkanDebug;
import com.sun.management.ThreadMXBean;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fork-isolated benchmark for the warmed Phase 1A.10 diagnostic no-op path.
 *
 * <p>The production case exercises the three BlockFrame-owned GPU pass
 * identities through a disabled Minecraft {@link VulkanDebug} implementation,
 * unavailable CPU Tracy and a null Minecraft Tracy GPU profiler. It follows
 * the exact current production shape: command diagnostics around Motion and
 * Evaluate, and only a CPU zone around Graphics Submit. The control performs
 * the identical pass-identity checksum without calling the diagnostic
 * adapters. Neither case creates a Vulkan instance, command buffer, Tracy
 * profiler, Minecraft client, GPU query, or other productive resource.</p>
 *
 * <p>This is an isolated CPU microbenchmark. It cannot prove Minecraft frame
 * time, FPS, visual behavior, driver behavior, RSS, or VRAM behavior.</p>
 */
public final class Phase1a10GpuDiagnosticsBenchmark {
    private static final int DEFAULT_FORKS = 5;
    private static final int WARMUP_ROUNDS = 8;
    private static final int WARMUP_ITERATIONS = 100_000;
    private static final int SAMPLE_COUNT = 21;
    private static final int ITERATIONS_PER_SAMPLE = 250_000;
    private static final int PASSES_PER_OPERATION = 3;
    private static final long WORKER_TIMEOUT_MINUTES = 5L;
    private static final long CHECKSUM_SEED =
        0x243F6A8885A308D3L;
    private static final VulkanDebug DISABLED_DEBUG =
        new VulkanDebug.Disabled();
    private static final Supplier<String> MUST_NOT_BE_EVALUATED =
        () -> {
            throw new AssertionError(
                "VulkanDebug.Disabled evaluated a label supplier"
            );
        };
    private static final GpuPassIdentity[] PASSES = {
        GpuPassIdentity.MOTION_COMPUTE,
        GpuPassIdentity.DLSS_EVALUATE,
        GpuPassIdentity.GRAPHICS_SUBMIT
    };
    private static final String SCENARIO =
        "production_pass_shape_disabled_debug_unavailable_cpu_tracy_"
            + "null_gpu_profiler";
    private static final String JVM_FLAGS =
        "xms128m_xmx128m_g1_xbatch_active_processor_count_1";
    private static final String MEASUREMENT_SCOPE =
        "fresh_jvm_warmed_current_thread_cpu_noop_path";
    private static final String LIMITATIONS =
        "no_minecraft_client_no_scene_no_vulkan_instance_"
            + "no_command_buffer_no_debug_extension_calls_"
            + "no_tracy_gpu_profiler_no_gpu_work_no_frame_time_"
            + "no_fps_claim_no_rss_no_vram";
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
        "passes_per_operation",
        "debug_utils_state",
        "cpu_tracy_state",
        "gpu_profiler_state",
        "diagnostic_adapter_calls_per_operation",
        "vulkan_api_calls",
        "gpu_timestamp_queries",
        "incremental_vram_bytes",
        "whole_process_rss_bytes",
        "worker_pid",
        "jvm_flags",
        "measurement_scope",
        "limitations"
    );

    private static volatile long blackhole;

    private Phase1a10GpuDiagnosticsBenchmark() {
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
                "at least two alternating fresh JVM fork pairs are required"
            );
        }
        Path outputParent = output.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        Path workerDirectory = Path.of(
            System.getProperty("blockframe.projectDir")
        ).resolve("build").resolve(
            "phase1a10-gpu-diagnostics-workers-" + System.nanoTime()
        );
        Files.createDirectories(workerDirectory);

        List<Result> results = new ArrayList<>(
            forks * Backend.values().length
        );
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
                    fork,
                    order,
                    first
                )
            );
            results.add(
                runFreshWorker(
                    workerDirectory,
                    fork,
                    order,
                    second
                )
            );
        }
        validate(results, forks);

        StringBuilder csv = new StringBuilder(32_000);
        csv.append(HEADER).append('\n');
        for (Result result : results) {
            appendResult(csv, "worker", result);
        }
        Result production = aggregate(results, Backend.PRODUCTION);
        Result control = aggregate(results, Backend.CONTROL);
        appendResult(csv, "aggregate", production);
        appendResult(csv, "aggregate", control);
        Files.writeString(output, csv, StandardCharsets.UTF_8);

        System.out.printf(
            Locale.ROOT,
            "Phase 1A.10 production/control p50 %.3f/%.3f ns/op "
                + "(%.4fx), p95 %.3f/%.3f, p99 %.3f/%.3f%n",
            production.p50NsPerOperation,
            control.p50NsPerOperation,
            production.p50NsPerOperation
                / control.p50NsPerOperation,
            production.p95NsPerOperation,
            control.p95NsPerOperation,
            production.p99NsPerOperation,
            control.p99NsPerOperation
        );
        System.out.printf(
            Locale.ROOT,
            "Allocation production/control %.6f/%.6f B/op, "
                + "total %d/%d B; GC %d/%d collections, "
                + "%d/%d ms pause%n",
            production.allocatedBytesPerOperation,
            control.allocatedBytesPerOperation,
            production.measuredAllocatedBytes,
            control.measuredAllocatedBytes,
            production.gcCollections,
            control.gcCollections,
            production.gcPauseMillis,
            control.gcPauseMillis
        );
        System.out.println(
            "Checksum production/control: "
                + Long.toUnsignedString(production.checksum)
                + "/"
                + Long.toUnsignedString(control.checksum)
        );
        System.out.println(
            "Scope: exact current pass shape, three static identities, "
                + "disabled Debug Utils, unavailable CPU Tracy, null "
                + "Tracy GPU profiler, zero Vulkan calls"
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
        int fork,
        String order,
        Backend backend
    ) throws Exception {
        String stem = String.format(
            Locale.ROOT,
            "fork-%02d-%s",
            fork,
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
            Phase1a10GpuDiagnosticsBenchmark.class.getName()
        );
        command.add("--worker");
        command.add(workerOutput.toString());
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
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                "worker expects output, fork, order and backend"
            );
        }
        Path output = Path.of(arguments[1])
            .toAbsolutePath()
            .normalize();
        int fork = Integer.parseInt(arguments[2]);
        String order = arguments[3];
        Backend backend = Backend.from(arguments[4]);
        ThreadMXBean allocationBean = requiredAllocationBean();
        long threadId = Thread.currentThread().threadId();
        validateUnavailableFacilities();

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            blackhole ^= backend.run(WARMUP_ITERATIONS);
        }

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
            long sampleChecksum = backend.run(
                ITERATIONS_PER_SAMPLE
            );
            long elapsed = System.nanoTime() - start;
            long allocatedAfter = allocatedBytes(
                allocationBean,
                threadId
            );
            long sampleAllocatedBytes =
                allocatedAfter - allocatedBefore;
            timeSamples[sample] =
                (double)elapsed / ITERATIONS_PER_SAMPLE;
            allocationSamples[sample] =
                (double)sampleAllocatedBytes
                    / ITERATIONS_PER_SAMPLE;
            measuredAllocatedBytes = Math.addExact(
                measuredAllocatedBytes,
                sampleAllocatedBytes
            );
            checksum = mix(checksum, sampleChecksum);
        }
        GcSnapshot gcAfter = gcSnapshot();
        blackhole ^= checksum;

        Arrays.sort(timeSamples);
        Arrays.sort(allocationSamples);
        long operations =
            (long)SAMPLE_COUNT * ITERATIONS_PER_SAMPLE;
        Result result = new Result(
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
            ProcessHandle.current().pid(),
            JVM_FLAGS,
            timeSamples,
            allocationSamples
        );

        Path outputParent = output.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            result.toProperties().store(
                stream,
                "Phase 1A.10 GPU diagnostic no-op worker"
            );
        }
    }

    private static void validate(
        List<Result> results,
        int forks
    ) {
        int expected = forks * Backend.values().length;
        if (results.size() != expected) {
            throw new IllegalStateException(
                "expected "
                    + expected
                    + " workers, got "
                    + results.size()
            );
        }
        Set<Long> workerPids = new HashSet<>();
        for (Result result : results) {
            if (
                !Double.isFinite(result.p50NsPerOperation)
                    || !Double.isFinite(result.p95NsPerOperation)
                    || !Double.isFinite(result.p99NsPerOperation)
                    || result.p50NsPerOperation <= 0.0D
                    || result.p95NsPerOperation
                        < result.p50NsPerOperation
                    || result.p99NsPerOperation
                        < result.p95NsPerOperation
                    || result.allocatedBytesPerOperation < 0.0D
                    || result.operations <= 0L
            ) {
                throw new IllegalStateException(
                    "invalid worker metrics for "
                        + result.backend.id
                        + " fork="
                        + result.fork
                );
            }
            if (!workerPids.add(result.workerPid)) {
                throw new IllegalStateException(
                    "worker PID was reused instead of a fresh JVM: "
                        + result.workerPid
                );
            }
        }
        for (int fork = 1; fork <= forks; fork++) {
            Result production = find(
                results,
                fork,
                Backend.PRODUCTION
            );
            Result control = find(
                results,
                fork,
                Backend.CONTROL
            );
            String expectedOrder = (fork & 1) != 0
                ? "production_then_control"
                : "control_then_production";
            if (
                !expectedOrder.equals(production.launchOrder)
                    || !expectedOrder.equals(control.launchOrder)
            ) {
                throw new IllegalStateException(
                    "launch order mismatch for fork " + fork
                );
            }
            if (production.checksum != control.checksum) {
                throw new IllegalStateException(
                    "semantic checksum mismatch for fork " + fork
                );
            }
        }
    }

    private static Result aggregate(
        List<Result> results,
        Backend backend
    ) {
        List<Result> selected = results.stream()
            .filter(result -> result.backend == backend)
            .toList();
        double[] timeSamples = selected.stream()
            .flatMapToDouble(
                result -> Arrays.stream(result.timeSamples)
            )
            .sorted()
            .toArray();
        double[] allocationSamples = selected.stream()
            .flatMapToDouble(
                result -> Arrays.stream(result.allocationSamples)
            )
            .sorted()
            .toArray();
        long operations = selected.stream()
            .mapToLong(result -> result.operations)
            .sum();
        long allocated = selected.stream()
            .mapToLong(result -> result.measuredAllocatedBytes)
            .sum();
        return new Result(
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
            selected.get(0).checksum,
            operations,
            timeSamples.length,
            0L,
            "fresh_jvms_same_flags",
            timeSamples,
            allocationSamples
        );
    }

    private static Result find(
        List<Result> results,
        int fork,
        Backend backend
    ) {
        return results.stream()
            .filter(
                result ->
                    result.fork == fork
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
        csv.append(rowType).append(',')
            .append(SCENARIO).append(',')
            .append(result.backend.id).append(',')
            .append(result.backend.implementation).append(',')
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
            .append(result.gcPauseMillis).append(',')
            .append(Long.toUnsignedString(result.checksum))
            .append(',')
            .append(result.operations).append(',')
            .append(result.sampleCount).append(',')
            .append(PASSES_PER_OPERATION).append(',')
            .append(result.backend.debugUtilsState).append(',')
            .append(result.backend.cpuTracyState).append(',')
            .append(result.backend.gpuProfilerState).append(',')
            .append(
                result.backend == Backend.PRODUCTION ? 14 : 0
            )
            .append(',')
            .append(0).append(',')
            .append(0).append(',')
            .append("NOT_MEASURED").append(',')
            .append("NOT_MEASURED").append(',')
            .append(result.workerPid).append(',')
            .append(result.jvmFlags).append(',')
            .append(MEASUREMENT_SCOPE).append(',')
            .append(LIMITATIONS)
            .append('\n');
    }

    private static long runProduction(int iterations) {
        long checksum = CHECKSUM_SEED;
        for (int operation = 0; operation < iterations; operation++) {
            checksum = mix(
                checksum,
                Integer.toUnsignedLong(operation)
            );
            checksum = runCommandPass(
                checksum,
                GpuPassIdentity.MOTION_COMPUTE
            );
            checksum = runCommandPass(
                checksum,
                GpuPassIdentity.DLSS_EVALUATE
            );

            var submitCpuZone =
                GpuPassDiagnostics.beginCpuTracyZone(
                    GpuPassIdentity.GRAPHICS_SUBMIT
                );
            GpuPassDiagnostics.closeCpuTracyZone(submitCpuZone);
            if (submitCpuZone != null) {
                throw new AssertionError(
                    "CPU Tracy unexpectedly became active"
                );
            }
            checksum = checksumIdentity(
                checksum,
                GpuPassIdentity.GRAPHICS_SUBMIT,
                false,
                false
            );
        }
        return checksum;
    }

    private static long runCommandPass(
        long checksum,
        GpuPassIdentity identity
    ) {
        var cpuZone = GpuPassDiagnostics.beginCpuTracyZone(identity);
        boolean gpuBegun =
            GpuPassDiagnostics.beginGpuTracyZone(
                null,
                null,
                identity
            );
        boolean debugBegun =
            GpuPassDiagnostics.beginDebugGroup(
                DISABLED_DEBUG,
                null,
                identity
            );
        GpuPassDiagnostics.endDebugGroup(
            DISABLED_DEBUG,
            null,
            debugBegun
        );
        GpuPassDiagnostics.endGpuTracyZone(
            null,
            null,
            gpuBegun
        );
        GpuPassDiagnostics.closeCpuTracyZone(cpuZone);
        if (cpuZone != null || debugBegun || gpuBegun) {
            throw new AssertionError(
                "unavailable diagnostics unexpectedly began"
            );
        }
        return checksumIdentity(
            checksum,
            identity,
            debugBegun,
            gpuBegun
        );
    }

    private static void validateUnavailableFacilities() {
        if (DISABLED_DEBUG.enabled()) {
            throw new AssertionError(
                "VulkanDebug.Disabled reported enabled"
            );
        }
        DISABLED_DEBUG.beginDebugGroup(
            null,
            MUST_NOT_BE_EVALUATED
        );
        DISABLED_DEBUG.setObjectName(
            null,
            0,
            1L,
            MUST_NOT_BE_EVALUATED
        );
        DISABLED_DEBUG.endDebugGroup(null);
        var cpuZone = GpuPassDiagnostics.beginCpuTracyZone(
            GpuPassIdentity.FRAME
        );
        GpuPassDiagnostics.closeCpuTracyZone(cpuZone);
        if (cpuZone != null) {
            throw new AssertionError(
                "benchmark requires unavailable CPU Tracy"
            );
        }
    }

    private static long runControl(int iterations) {
        long checksum = CHECKSUM_SEED;
        for (int operation = 0; operation < iterations; operation++) {
            checksum = mix(
                checksum,
                Integer.toUnsignedLong(operation)
            );
            for (GpuPassIdentity identity : PASSES) {
                checksum = checksumIdentity(
                    checksum,
                    identity,
                    false,
                    false
                );
            }
        }
        return checksum;
    }

    private static long checksumIdentity(
        long checksum,
        GpuPassIdentity identity,
        boolean debugBegun,
        boolean gpuBegun
    ) {
        long value = Integer.toUnsignedLong(
            identity.breadcrumbId()
        );
        value = mix(
            value,
            Integer.toUnsignedLong(identity.label().hashCode())
        );
        value = mix(value, debugBegun ? 1L : 0L);
        value = mix(value, gpuBegun ? 1L : 0L);
        return mix(checksum, value);
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

    private static String encodeSamples(double[] samples) {
        StringBuilder encoded = new StringBuilder(
            samples.length * 16
        );
        for (int index = 0; index < samples.length; index++) {
            if (index > 0) {
                encoded.append(';');
            }
            encoded.append(
                String.format(Locale.ROOT, "%.9f", samples[index])
            );
        }
        return encoded.toString();
    }

    private static double[] decodeSamples(String encoded) {
        if (encoded.isEmpty()) {
            return new double[0];
        }
        String[] values = encoded.split(";");
        double[] samples = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            samples[index] = Double.parseDouble(values[index]);
        }
        return samples;
    }

    private enum Backend {
        PRODUCTION(
            "production_noop",
            "exact_pass_shape_disabled_debug_unavailable_cpu_tracy_"
                + "null_gpu_profiler",
            "VulkanDebug.Disabled",
            "unavailable",
            "null"
        ) {
            @Override
            long run(int iterations) {
                return runProduction(iterations);
            }
        },
        CONTROL(
            "control",
            "pass_identity_checksum_without_diagnostic_adapters",
            "not_called",
            "not_called",
            "not_called"
        ) {
            @Override
            long run(int iterations) {
                return runControl(iterations);
            }
        };

        private final String id;
        private final String implementation;
        private final String debugUtilsState;
        private final String cpuTracyState;
        private final String gpuProfilerState;

        Backend(
            String id,
            String implementation,
            String debugUtilsState,
            String cpuTracyState,
            String gpuProfilerState
        ) {
            this.id = id;
            this.implementation = implementation;
            this.debugUtilsState = debugUtilsState;
            this.cpuTracyState = cpuTracyState;
            this.gpuProfilerState = gpuProfilerState;
        }

        abstract long run(int iterations);

        private static Backend from(String id) {
            return Arrays.stream(values())
                .filter(backend -> backend.id.equals(id))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException(
                        "unknown backend " + id
                    )
                );
        }
    }

    private record GcSnapshot(
        long collections,
        long collectionMillis
    ) {
    }

    private record Result(
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
        long workerPid,
        String jvmFlags,
        double[] timeSamples,
        double[] allocationSamples
    ) {
        private Properties toProperties() {
            Properties properties = new Properties();
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
                "p50NsPerOperation",
                Double.toString(this.p50NsPerOperation)
            );
            properties.setProperty(
                "p95NsPerOperation",
                Double.toString(this.p95NsPerOperation)
            );
            properties.setProperty(
                "p99NsPerOperation",
                Double.toString(this.p99NsPerOperation)
            );
            properties.setProperty(
                "allocatedBytesPerOperation",
                Double.toString(this.allocatedBytesPerOperation)
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
                "workerPid",
                Long.toString(this.workerPid)
            );
            properties.setProperty("jvmFlags", this.jvmFlags);
            properties.setProperty(
                "timeSamples",
                encodeSamples(this.timeSamples)
            );
            properties.setProperty(
                "allocationSamples",
                encodeSamples(this.allocationSamples)
            );
            return properties;
        }

        private static Result from(Properties properties) {
            return new Result(
                Backend.from(properties.getProperty("backend")),
                Integer.parseInt(
                    properties.getProperty("fork")
                ),
                properties.getProperty("launchOrder"),
                Double.parseDouble(
                    properties.getProperty(
                        "p50NsPerOperation"
                    )
                ),
                Double.parseDouble(
                    properties.getProperty(
                        "p95NsPerOperation"
                    )
                ),
                Double.parseDouble(
                    properties.getProperty(
                        "p99NsPerOperation"
                    )
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
                Long.parseLong(
                    properties.getProperty("workerPid")
                ),
                properties.getProperty("jvmFlags"),
                decodeSamples(
                    properties.getProperty("timeSamples")
                ),
                decodeSamples(
                    properties.getProperty(
                        "allocationSamples"
                    )
                )
            );
        }
    }
}
