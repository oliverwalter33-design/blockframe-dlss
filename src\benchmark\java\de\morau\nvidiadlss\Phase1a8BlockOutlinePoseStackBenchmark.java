package de.morau.nvidiadlss;

import com.mojang.blaze3d.vertex.PoseStack;
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
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.joml.Matrix4f;

/**
 * Fork-isolated comparison of a fresh block-outline {@link PoseStack} and one
 * prewarmed, persistently owned stack.
 *
 * <p>Each measured operation enforces the Minecraft 26.2 stack contract used
 * by native block-outline submission: empty and identity on entry, one push,
 * one camera translation, one pop, then empty and identity on return. Both
 * backends publish the selected stack through the same volatile escape so the
 * legacy allocation cannot be scalar-replaced.</p>
 *
 * <p>This is an isolated warmed CPU primitive benchmark. It does not launch
 * Minecraft, render a scene, submit Vulkan work, measure GPU or driver memory,
 * measure whole-process RSS, or prove an end-to-end frame-time change.</p>
 */
public final class Phase1a8BlockOutlinePoseStackBenchmark {
    private static final int DEFAULT_FORKS = 5;
    private static final int WARMUP_ROUNDS = 8;
    private static final int WARMUP_ITERATIONS = 50_000;
    private static final int SAMPLE_COUNT = 21;
    private static final int ITERATIONS_PER_SAMPLE = 50_000;
    private static final long CHECKSUM_SEED =
        0x6A09E667F3BCC909L;
    private static final long WORKER_TIMEOUT_MINUTES = 5L;
    private static final double MAX_CURRENT_TIME_RATIO = 1.02D;
    private static final String SCENARIO =
        "mc26_2_empty_identity_push_translate_pop_empty_identity";
    private static final String JVM_FLAGS =
        "xms128m_xmx128m_g1_xbatch_active_processor_count_1";
    private static final String SCOPE =
        "isolated_warmed_pose_stack_cpu_primitive";
    private static final String LIMITATIONS =
        "no_minecraft_no_scene_no_vulkan_no_gpu_no_driver_memory_"
            + "no_frame_time_whole_process_rss_not_measured";
    private static final String HEADER = String.join(
        ",",
        "row_type",
        "scenario",
        "backend",
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
        "init_allocated_bytes",
        "backend_owned_retained_pose_stack_count",
        "escape_sink_retained_latest_stack_count",
        "escape_publications",
        "incremental_vram_bytes",
        "whole_process_rss_bytes",
        "helper_status",
        "helper_reuse_uses",
        "helper_fresh_fallbacks",
        "helper_disable_count",
        "worker_pid",
        "jvm_flags",
        "measurement_scope",
        "limitations"
    );

    private static volatile PoseStack publishedPoseStack;
    private static volatile long blackhole;

    private Phase1a8BlockOutlinePoseStackBenchmark() {
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
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path workerDirectory = Path.of(
            System.getProperty("blockframe.projectDir")
        ).resolve("build").resolve(
            "phase1a8-pose-stack-workers-" + System.nanoTime()
        );
        Files.createDirectories(workerDirectory);

        List<Result> results = new ArrayList<>(forks * 2);
        for (int fork = 1; fork <= forks; fork++) {
            boolean freshFirst = (fork & 1) != 0;
            String order = freshFirst
                ? "fresh_then_warmed_persistent"
                : "warmed_persistent_then_fresh";
            Backend first = freshFirst
                ? Backend.FRESH
                : Backend.WARMED_PERSISTENT;
            Backend second = freshFirst
                ? Backend.WARMED_PERSISTENT
                : Backend.FRESH;
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
        validateEquivalence(results, forks);

        Result fresh = aggregate(results, Backend.FRESH);
        Result current = aggregate(
            results,
            Backend.WARMED_PERSISTENT
        );
        StringBuilder csv = new StringBuilder(16_384);
        csv.append(HEADER).append('\n');
        for (Result result : results) {
            appendResult(csv, "worker", result);
        }
        appendResult(csv, "aggregate", fresh);
        appendResult(csv, "aggregate", current);
        Files.writeString(output, csv, StandardCharsets.UTF_8);

        double ratio = current.p50NsPerOperation
            / fresh.p50NsPerOperation;
        System.out.printf(
            Locale.ROOT,
            "fresh/current p50 %.3f/%.3f ns/op "
                + "(current/fresh %.4fx), p95 %.3f/%.3f, "
                + "p99 %.3f/%.3f%n",
            fresh.p50NsPerOperation,
            current.p50NsPerOperation,
            ratio,
            fresh.p95NsPerOperation,
            current.p95NsPerOperation,
            fresh.p99NsPerOperation,
            current.p99NsPerOperation
        );
        System.out.printf(
            Locale.ROOT,
            "fresh/current allocations %.3f/%.3f B/op, "
                + "GC %d/%d collections and %.3f/%.3f ms pause%n",
            fresh.allocatedBytesPerOperation,
            current.allocatedBytesPerOperation,
            fresh.gcCollections,
            current.gcCollections,
            (double)fresh.gcPauseMillis,
            (double)current.gcPauseMillis
        );
        System.out.printf(
            Locale.ROOT,
            "persistent init allocation p50 %d bytes; "
                + "owned retained stacks %d; incremental VRAM 0 bytes%n",
            current.initAllocatedBytes,
            current.backendOwnedRetainedPoseStackCount
        );
        System.out.println(
            "Scope: isolated warmed PoseStack CPU primitive; "
                + "no Minecraft/Vulkan/GPU/frame-time claim"
        );
        System.out.println(
            "Whole-process RSS: NOT_MEASURED; the benchmark reports "
                + "thread allocations attributable to the measured operation"
        );
        System.out.println("CSV: " + output);
        System.out.println("Worker evidence: " + workerDirectory);

        validateAcceptance(fresh, current);
        System.out.printf(
            Locale.ROOT,
            "Acceptance: PASS (current steady allocation %.3f B/op; "
                + "current/fresh p50 %.4fx <= %.2fx)%n",
            current.allocatedBytesPerOperation,
            ratio,
            MAX_CURRENT_TIME_RATIO
        );
        blackhole ^= current.checksum;
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
            Phase1a8BlockOutlinePoseStackBenchmark.class.getName()
        );
        command.add("--worker");
        command.add(workerOutput.toString());
        command.add(Integer.toString(fork));
        command.add(order);
        command.add(backend.id);

        ProcessBuilder builder = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(workerLog.toFile());
        Process process = builder.start();
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

        warmProductionClasses();
        long initBefore = allocatedBytes(
            allocationBean,
            threadId
        );
        NativeBlockOutlinePoseStackScratch scratch =
            backend == Backend.WARMED_PERSISTENT
                ? NativeBlockOutlinePoseStackScratch
                    .createForCurrentThread()
                : null;
        long initAfter = allocatedBytes(
            allocationBean,
            threadId
        );
        long initAllocatedBytes = initAfter - initBefore;
        if (
            scratch != null
                && scratch.status()
                    != NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE
        ) {
            throw new IllegalStateException(
                "production scratch was not active after initialization"
            );
        }

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            blackhole ^= runOperations(
                backend,
                scratch,
                WARMUP_ITERATIONS
            );
        }

        double[] samples = new double[SAMPLE_COUNT];
        double[] allocationSamples = new double[SAMPLE_COUNT];
        GcSnapshot gcBefore = gcSnapshot();
        long checksum = CHECKSUM_SEED;
        long measuredAllocatedBytes = 0L;
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long sampleAllocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long start = System.nanoTime();
            long sampleChecksum = runOperations(
                backend,
                scratch,
                ITERATIONS_PER_SAMPLE
            );
            long elapsed = System.nanoTime() - start;
            long sampleAllocatedAfter = allocatedBytes(
                allocationBean,
                threadId
            );
            long sampleAllocatedBytes =
                sampleAllocatedAfter - sampleAllocatedBefore;
            samples[sample] =
                (double)elapsed / ITERATIONS_PER_SAMPLE;
            allocationSamples[sample] =
                (double)sampleAllocatedBytes
                    / ITERATIONS_PER_SAMPLE;
            measuredAllocatedBytes += sampleAllocatedBytes;
            checksum = mix(checksum, sampleChecksum);
        }
        GcSnapshot gcAfter = gcSnapshot();
        blackhole ^= checksum;

        Arrays.sort(samples);
        Arrays.sort(allocationSamples);
        long operations =
            (long)SAMPLE_COUNT * ITERATIONS_PER_SAMPLE;
        long helperReuseUses =
            scratch == null ? 0L : scratch.reuseUses();
        long helperFreshFallbacks =
            scratch == null ? 0L : scratch.freshFallbacks();
        long helperDisableCount =
            scratch == null ? 0L : scratch.disableCount();
        int helperStatus =
            scratch == null ? 0 : scratch.status();
        if (scratch != null) {
            long expectedUses = operations
                + (long)WARMUP_ROUNDS * WARMUP_ITERATIONS;
            if (
                helperStatus
                    != NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE
                    || helperReuseUses != expectedUses
                    || helperFreshFallbacks != 0L
                    || helperDisableCount != 0L
            ) {
                throw new IllegalStateException(
                    "production helper left the normal-path contract: "
                        + "status="
                        + helperStatus
                        + " reuse="
                        + helperReuseUses
                        + "/"
                        + expectedUses
                        + " fallback="
                        + helperFreshFallbacks
                        + " disable="
                        + helperDisableCount
                );
            }
        }
        Result result = new Result(
            backend,
            fork,
            order,
            percentile(samples, 0.50D),
            percentile(samples, 0.95D),
            percentile(samples, 0.99D),
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
            initAllocatedBytes,
            backend.retainedStackCount,
            1,
            operations,
            0L,
            "NOT_MEASURED",
            helperStatus,
            helperReuseUses,
            helperFreshFallbacks,
            helperDisableCount,
            ProcessHandle.current().pid(),
            JVM_FLAGS,
            samples,
            allocationSamples
        );
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            result.toProperties().store(
                stream,
                "Phase 1A.8 block-outline PoseStack worker"
            );
        }
    }

    private static void warmProductionClasses() {
        NativeBlockOutlinePoseStackScratch scratch =
            NativeBlockOutlinePoseStackScratch
                .createForCurrentThread();
        if (
            scratch.status()
                != NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE
        ) {
            throw new IllegalStateException(
                "production scratch class warmup was not active"
            );
        }
        PoseStack stack = scratch.beginUse();
        boolean completed = false;
        try {
            blackhole ^= outlineSubmission(
                stack,
                0,
                CHECKSUM_SEED
            );
            completed = true;
        } finally {
            scratch.endUse(stack, completed);
        }
        if (
            scratch.status()
                != NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE
                || scratch.reuseUses() != 1L
                || scratch.freshFallbacks() != 0L
        ) {
            throw new IllegalStateException(
                "production scratch class warmup violated invariants"
            );
        }
        publishedPoseStack = stack;
        scratch.clear();
    }

    private static long runOperations(
        Backend backend,
        NativeBlockOutlinePoseStackScratch scratch,
        int iterations
    ) {
        return backend == Backend.FRESH
            ? runFreshOperations(iterations)
            : runPersistentOperations(scratch, iterations);
    }

    private static long runFreshOperations(int iterations) {
        long checksum = CHECKSUM_SEED;
        for (int index = 0; index < iterations; index++) {
            PoseStack stack = new PoseStack();
            checksum = outlineSubmission(
                stack,
                index,
                checksum
            );
            publishedPoseStack = stack;
        }
        return checksum;
    }

    private static long runPersistentOperations(
        NativeBlockOutlinePoseStackScratch scratch,
        int iterations
    ) {
        if (scratch == null) {
            throw new IllegalStateException(
                "persistent backend has no production helper"
            );
        }
        long checksum = CHECKSUM_SEED;
        for (int index = 0; index < iterations; index++) {
            PoseStack stack = scratch.beginUse();
            boolean completed = false;
            try {
                checksum = outlineSubmission(
                    stack,
                    index,
                    checksum
                );
                completed = true;
            } finally {
                scratch.endUse(stack, completed);
            }
            publishedPoseStack = stack;
        }
        return checksum;
    }

    private static long outlineSubmission(
        PoseStack stack,
        int index,
        long checksum
    ) {
        if (!stack.isEmpty()) {
            throw new IllegalStateException(
                "PoseStack was not empty on entry"
            );
        }
        stack.pushPose();
        if (stack.isEmpty()) {
            throw new IllegalStateException(
                "PoseStack push did not create a nested pose"
            );
        }

        double cameraX = ((index & 1023) - 512) * 0.03125D;
        double cameraY =
            (((index * 17) & 511) - 256) * 0.015625D;
        double cameraZ =
            (((index * 31) & 2047) - 1024) * 0.0078125D;
        stack.translate(-cameraX, -cameraY, -cameraZ);
        Matrix4f translated = stack.last().pose();
        checksum = mix(
            checksum,
            Integer.toUnsignedLong(
                Float.floatToRawIntBits(translated.m30())
            )
        );
        checksum = mix(
            checksum,
            Integer.toUnsignedLong(
                Float.floatToRawIntBits(translated.m31())
            )
        );
        checksum = mix(
            checksum,
            Integer.toUnsignedLong(
                Float.floatToRawIntBits(translated.m32())
            )
        );

        stack.popPose();
        if (!stack.isEmpty()) {
            throw new IllegalStateException(
                "PoseStack was not empty after pop"
            );
        }
        Matrix4f root = stack.last().pose();
        checksum = mix(
            checksum,
            Integer.toUnsignedLong(
                Float.floatToRawIntBits(root.m00())
            )
        );
        return checksum;
    }

    private static long mix(long checksum, long value) {
        return Long.rotateLeft(checksum ^ value, 17)
            + 0x9E3779B97F4A7C15L;
    }

    private static void validateEquivalence(
        List<Result> results,
        int forks
    ) {
        if (results.size() != forks * 2) {
            throw new IllegalStateException(
                "expected "
                    + (forks * 2)
                    + " workers, got "
                    + results.size()
            );
        }
        Long commonChecksum = null;
        for (int fork = 1; fork <= forks; fork++) {
            Result fresh = find(results, fork, Backend.FRESH);
            Result current = find(
                results,
                fork,
                Backend.WARMED_PERSISTENT
            );
            if (fresh.checksum != current.checksum) {
                throw new IllegalStateException(
                    "checksum mismatch for fork " + fork
                );
            }
            if (
                fresh.operations != current.operations
                    || fresh.escapePublications
                        != current.escapePublications
            ) {
                throw new IllegalStateException(
                    "operation or escape-publication mismatch for fork "
                        + fork
                );
            }
            if (
                commonChecksum != null
                    && commonChecksum.longValue() != fresh.checksum
            ) {
                throw new IllegalStateException(
                    "checksum changed across fresh JVM forks"
                );
            }
            commonChecksum = fresh.checksum;
        }
    }

    private static void validateAcceptance(
        Result fresh,
        Result current
    ) {
        if (
            current.allocatedBytesPerOperation != 0.0D
                || current.p99AllocatedBytesPerOperation != 0.0D
        ) {
            throw new IllegalStateException(
                "production-helper path did not reach steady 0 B/op: "
                    + current.allocatedBytesPerOperation
                    + " average, "
                    + current.p99AllocatedBytesPerOperation
                    + " p99"
            );
        }
        if (current.backendOwnedRetainedPoseStackCount != 1) {
            throw new IllegalStateException(
                "persistent path must own exactly one retained stack"
            );
        }
        if (
            current.helperStatus
                != NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE
                || current.helperFreshFallbacks != 0L
                || current.helperDisableCount != 0L
        ) {
            throw new IllegalStateException(
                "production helper was not active and fallback-free"
            );
        }
        double ratio = current.p50NsPerOperation
            / fresh.p50NsPerOperation;
        if (ratio > MAX_CURRENT_TIME_RATIO) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "persistent p50 regression %.4fx exceeds %.2fx",
                    ratio,
                    MAX_CURRENT_TIME_RATIO
                )
            );
        }
    }

    private static Result aggregate(
        List<Result> results,
        Backend backend
    ) {
        List<Result> selected = results.stream()
            .filter(result -> result.backend == backend)
            .toList();
        double[] samples = selected.stream()
            .flatMapToDouble(
                result -> Arrays.stream(result.samples)
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
        long allocatedBytes = selected.stream()
            .mapToLong(result -> result.measuredAllocatedBytes)
            .sum();
        long[] initAllocations = selected.stream()
            .mapToLong(result -> result.initAllocatedBytes)
            .sorted()
            .toArray();
        Result representative = selected.get(0);
        return new Result(
            backend,
            0,
            "alternating_fresh_jvms",
            percentile(samples, 0.50D),
            percentile(samples, 0.95D),
            percentile(samples, 0.99D),
            (double)allocatedBytes / operations,
            percentile(allocationSamples, 0.50D),
            percentile(allocationSamples, 0.95D),
            percentile(allocationSamples, 0.99D),
            allocatedBytes,
            selected.stream()
                .mapToLong(result -> result.gcCollections)
                .sum(),
            selected.stream()
                .mapToLong(result -> result.gcPauseMillis)
                .sum(),
            representative.checksum,
            operations,
            samples.length,
            percentile(initAllocations, 0.50D),
            backend.retainedStackCount,
            1,
            operations,
            0L,
            "NOT_MEASURED",
            backend == Backend.WARMED_PERSISTENT
                ? NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE
                : 0,
            selected.stream()
                .mapToLong(result -> result.helperReuseUses)
                .sum(),
            selected.stream()
                .mapToLong(
                    result -> result.helperFreshFallbacks
                )
                .sum(),
            selected.stream()
                .mapToLong(result -> result.helperDisableCount)
                .sum(),
            0L,
            "fresh_jvms_same_flags",
            samples,
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
            .append(result.initAllocatedBytes).append(',')
            .append(
                result.backendOwnedRetainedPoseStackCount
            )
            .append(',')
            .append(result.escapeSinkRetainedLatestStackCount)
            .append(',')
            .append(result.escapePublications).append(',')
            .append(result.incrementalVramBytes).append(',')
            .append(result.wholeProcessRssBytes).append(',')
            .append(result.helperStatus).append(',')
            .append(result.helperReuseUses).append(',')
            .append(result.helperFreshFallbacks).append(',')
            .append(result.helperDisableCount).append(',')
            .append(result.workerPid).append(',')
            .append(result.jvmFlags).append(',')
            .append(SCOPE).append(',')
            .append(LIMITATIONS)
            .append('\n');
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
        int index = (int)Math.ceil(
            quantile * sorted.length
        ) - 1;
        return sorted[
            Math.max(0, Math.min(index, sorted.length - 1))
        ];
    }

    private static long percentile(
        long[] sorted,
        double quantile
    ) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = (int)Math.ceil(
            quantile * sorted.length
        ) - 1;
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

    private enum Backend {
        FRESH("fresh_per_operation", 0),
        WARMED_PERSISTENT("production_helper", 1);

        private final String id;
        private final int retainedStackCount;

        Backend(String id, int retainedStackCount) {
            this.id = id;
            this.retainedStackCount = retainedStackCount;
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
        long initAllocatedBytes,
        int backendOwnedRetainedPoseStackCount,
        int escapeSinkRetainedLatestStackCount,
        long escapePublications,
        long incrementalVramBytes,
        String wholeProcessRssBytes,
        int helperStatus,
        long helperReuseUses,
        long helperFreshFallbacks,
        long helperDisableCount,
        long workerPid,
        String jvmFlags,
        double[] samples,
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
                "initAllocatedBytes",
                Long.toString(this.initAllocatedBytes)
            );
            properties.setProperty(
                "backendOwnedRetainedPoseStackCount",
                Integer.toString(
                    this.backendOwnedRetainedPoseStackCount
                )
            );
            properties.setProperty(
                "escapeSinkRetainedLatestStackCount",
                Integer.toString(
                    this.escapeSinkRetainedLatestStackCount
                )
            );
            properties.setProperty(
                "escapePublications",
                Long.toString(this.escapePublications)
            );
            properties.setProperty(
                "incrementalVramBytes",
                Long.toString(this.incrementalVramBytes)
            );
            properties.setProperty(
                "wholeProcessRssBytes",
                this.wholeProcessRssBytes
            );
            properties.setProperty(
                "helperStatus",
                Integer.toString(this.helperStatus)
            );
            properties.setProperty(
                "helperReuseUses",
                Long.toString(this.helperReuseUses)
            );
            properties.setProperty(
                "helperFreshFallbacks",
                Long.toString(this.helperFreshFallbacks)
            );
            properties.setProperty(
                "helperDisableCount",
                Long.toString(this.helperDisableCount)
            );
            properties.setProperty(
                "workerPid",
                Long.toString(this.workerPid)
            );
            properties.setProperty("jvmFlags", this.jvmFlags);
            properties.setProperty(
                "samples",
                join(this.samples)
            );
            properties.setProperty(
                "allocationSamples",
                join(this.allocationSamples)
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
                Long.parseLong(
                    properties.getProperty("initAllocatedBytes")
                ),
                Integer.parseInt(
                    properties.getProperty(
                        "backendOwnedRetainedPoseStackCount"
                    )
                ),
                Integer.parseInt(
                    properties.getProperty(
                        "escapeSinkRetainedLatestStackCount"
                    )
                ),
                Long.parseLong(
                    properties.getProperty("escapePublications")
                ),
                Long.parseLong(
                    properties.getProperty("incrementalVramBytes")
                ),
                properties.getProperty("wholeProcessRssBytes"),
                Integer.parseInt(
                    properties.getProperty("helperStatus")
                ),
                Long.parseLong(
                    properties.getProperty("helperReuseUses")
                ),
                Long.parseLong(
                    properties.getProperty(
                        "helperFreshFallbacks"
                    )
                ),
                Long.parseLong(
                    properties.getProperty("helperDisableCount")
                ),
                Long.parseLong(
                    properties.getProperty("workerPid")
                ),
                properties.getProperty("jvmFlags"),
                parseSamples(properties.getProperty("samples")),
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
