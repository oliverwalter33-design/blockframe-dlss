package de.morau.blockframe.core.state;

import com.sun.management.ThreadMXBean;
import de.morau.blockframe.core.EngineConfig;
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

/**
 * Fork-isolated microbenchmark for the warmed Phase 1A.12 runtime-state
 * read paths.
 *
 * <p>Each scenario/fork pair runs in its own fresh JVM. The launch order
 * rotates between forks. The measured loops perform no file, network,
 * Minecraft, Vulkan, driver or persistence-store I/O. In particular, this
 * benchmark does not measure state transitions: the feature registry is
 * already published and the world-frame tracker is already stable before
 * measurement begins.</p>
 *
 * <p>This is an isolated current-thread CPU/allocation measurement. It cannot
 * prove Minecraft frame time, FPS, visual behavior, scheduling behavior,
 * process RSS, VRAM use, crash recovery or persistence durability.</p>
 */
public final class Phase1a12RuntimeStateBenchmark {
    private static final int DEFAULT_FORKS = 5;
    private static final int WARMUP_ROUNDS = 8;
    private static final int WARMUP_ITERATIONS = 250_000;
    private static final int SAMPLE_COUNT = 21;
    private static final int ITERATIONS_PER_SAMPLE = 500_000;
    private static final int STABILITY_FRAMES = 120;
    private static final long CHECKSUM_SEED =
        0xD1B54A32D192ED03L;
    private static final long WORKER_TIMEOUT_MINUTES = 5L;
    private static final String JVM_FLAGS =
        "xms128m_xmx128m_g1_xbatch_active_processor_count_1";
    private static final String MEASUREMENT_SCOPE =
        "fresh_jvm_warmed_current_thread_cached_runtime_state_reads";
    private static final String LIMITATIONS =
        "no_minecraft_scene_no_vulkan_no_gpu_no_persistence_io_"
            + "no_state_transition_no_frame_time_no_fps_claim_"
            + "no_rss_no_vram_no_durability_claim";
    private static final String HEADER = String.join(
        ",",
        "row_type",
        "scenario",
        "fork",
        "launch_order",
        "median_ns_per_operation",
        "p95_ns_per_operation",
        "p99_ns_per_operation",
        "allocated_bytes_per_operation",
        "median_allocated_bytes_per_operation",
        "p95_allocated_bytes_per_operation",
        "p99_allocated_bytes_per_operation",
        "measured_allocated_bytes",
        "gc_collections",
        "gc_pause_ms",
        "checksum",
        "operations",
        "sample_count",
        "worker_pid",
        "jvm_flags",
        "measurement_scope",
        "limitations"
    );
    private static final FeatureId[] FEATURES =
        FeatureId.all().toArray(FeatureId[]::new);
    private static final Object WORLD = new Object();

    private static volatile long blackhole;

    private Phase1a12RuntimeStateBenchmark() {
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
                "at least two rotating fresh-JVM forks are required"
            );
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path workerDirectory = Path.of(
            System.getProperty("blockframe.projectDir")
        ).resolve("build").resolve(
            "phase1a12-runtime-state-workers-" + System.nanoTime()
        );
        Files.createDirectories(workerDirectory);

        List<Result> results = new ArrayList<>(
            forks * Scenario.values().length
        );
        Scenario[] scenarios = Scenario.values();
        for (int fork = 1; fork <= forks; fork++) {
            int rotation = (fork - 1) % scenarios.length;
            String order = launchOrder(scenarios, rotation);
            for (int offset = 0; offset < scenarios.length; offset++) {
                Scenario scenario =
                    scenarios[(rotation + offset) % scenarios.length];
                results.add(
                    runFreshWorker(
                        workerDirectory,
                        fork,
                        order,
                        scenario
                    )
                );
            }
        }
        validate(results, forks);

        StringBuilder csv = new StringBuilder(48_000);
        csv.append(HEADER).append('\n');
        for (Result result : results) {
            appendResult(csv, "worker", result);
        }
        for (Scenario scenario : scenarios) {
            appendResult(
                csv,
                "aggregate",
                aggregate(results, scenario)
            );
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);

        for (Scenario scenario : scenarios) {
            Result result = aggregate(results, scenario);
            System.out.printf(
                Locale.ROOT,
                "%s median/p95/p99 %.3f/%.3f/%.3f ns/op, "
                    + "%.6f B/op, GC %d collections / %d ms, "
                    + "checksum %s%n",
                scenario.id,
                result.medianNsPerOperation,
                result.p95NsPerOperation,
                result.p99NsPerOperation,
                result.allocatedBytesPerOperation,
                result.gcCollections,
                result.gcPauseMillis,
                Long.toUnsignedString(result.checksum)
            );
        }
        System.out.println(
            "Scope: warmed cached Phase 1A.12 reads in isolated fresh JVMs; "
                + "the measured loops perform no persistence I/O"
        );
        System.out.println(
            "Limits: no Minecraft/GPU/frame-time/FPS/RSS/VRAM/durability "
                + "claim"
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
        String launchOrder,
        Scenario scenario
    ) throws Exception {
        String stem = String.format(
            Locale.ROOT,
            "fork-%02d-%s",
            fork,
            scenario.id
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
        command.add(Phase1a12RuntimeStateBenchmark.class.getName());
        command.add("--worker");
        command.add(workerOutput.toString());
        command.add(Integer.toString(fork));
        command.add(launchOrder);
        command.add(scenario.id);

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
                "worker expects output, fork, launch order and scenario"
            );
        }
        Path output = Path.of(arguments[1])
            .toAbsolutePath()
            .normalize();
        int fork = Integer.parseInt(arguments[2]);
        String launchOrder = arguments[3];
        Scenario scenario = Scenario.from(arguments[4]);
        ThreadMXBean allocationBean = requiredAllocationBean();
        long threadId = Thread.currentThread().threadId();
        State state = State.create();
        state.validate();

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            blackhole ^= scenario.run(state, WARMUP_ITERATIONS);
        }
        state.validate();
        long clockWarmup = System.nanoTime();
        blackhole ^= System.nanoTime() - clockWarmup;

        double[] timeSamples = new double[SAMPLE_COUNT];
        double[] allocationSamples = new double[SAMPLE_COUNT];
        GcSnapshot gcBefore = gcSnapshot();
        long measuredAllocatedBytes = 0L;
        long stableChecksum = 0L;
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long start = System.nanoTime();
            long sampleChecksum = scenario.run(
                state,
                ITERATIONS_PER_SAMPLE
            );
            long elapsed = System.nanoTime() - start;
            long allocatedAfter = allocatedBytes(
                allocationBean,
                threadId
            );
            long sampleAllocated =
                allocatedAfter - allocatedBefore;
            if (sample == 0) {
                stableChecksum = sampleChecksum;
            } else if (sampleChecksum != stableChecksum) {
                throw new IllegalStateException(
                    scenario.id + " checksum changed between samples"
                );
            }
            timeSamples[sample] =
                (double)elapsed / ITERATIONS_PER_SAMPLE;
            allocationSamples[sample] =
                (double)sampleAllocated / ITERATIONS_PER_SAMPLE;
            measuredAllocatedBytes = Math.addExact(
                measuredAllocatedBytes,
                sampleAllocated
            );
        }
        GcSnapshot gcAfter = gcSnapshot();
        state.validate();
        blackhole ^= stableChecksum;

        Arrays.sort(timeSamples);
        Arrays.sort(allocationSamples);
        long operations =
            (long)SAMPLE_COUNT * ITERATIONS_PER_SAMPLE;
        Result result = new Result(
            scenario,
            fork,
            launchOrder,
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
            stableChecksum,
            operations,
            SAMPLE_COUNT,
            ProcessHandle.current().pid(),
            JVM_FLAGS,
            timeSamples,
            allocationSamples
        );

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            result.toProperties().store(
                stream,
                "Phase 1A.12 runtime-state worker"
            );
        }
    }

    private static void validate(List<Result> results, int forks) {
        int expected = forks * Scenario.values().length;
        if (results.size() != expected) {
            throw new IllegalStateException(
                "expected "
                    + expected
                    + " workers, got "
                    + results.size()
            );
        }
        Set<Long> pids = new HashSet<>();
        for (Result result : results) {
            if (
                !Double.isFinite(result.medianNsPerOperation)
                    || !Double.isFinite(result.p95NsPerOperation)
                    || !Double.isFinite(result.p99NsPerOperation)
                    || result.medianNsPerOperation <= 0.0D
                    || result.p95NsPerOperation
                        < result.medianNsPerOperation
                    || result.p99NsPerOperation
                        < result.p95NsPerOperation
                    || result.allocatedBytesPerOperation < 0.0D
                    || result.operations <= 0L
            ) {
                throw new IllegalStateException(
                    "invalid metrics for "
                        + result.scenario.id
                        + " fork="
                        + result.fork
                );
            }
            if (!pids.add(result.workerPid)) {
                throw new IllegalStateException(
                    "worker PID reused instead of a fresh JVM: "
                        + result.workerPid
                );
            }
        }
        for (Scenario scenario : Scenario.values()) {
            long checksum = results.stream()
                .filter(result -> result.scenario == scenario)
                .findFirst()
                .orElseThrow()
                .checksum;
            long count = results.stream()
                .filter(result -> result.scenario == scenario)
                .peek(result -> {
                    if (result.checksum != checksum) {
                        throw new IllegalStateException(
                            scenario.id
                                + " checksum differs between forks"
                        );
                    }
                })
                .count();
            if (count != forks) {
                throw new IllegalStateException(
                    scenario.id
                        + " has "
                        + count
                        + " workers, expected "
                        + forks
                );
            }
        }
    }

    private static Result aggregate(
        List<Result> results,
        Scenario scenario
    ) {
        List<Result> selected = results.stream()
            .filter(result -> result.scenario == scenario)
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
            scenario,
            0,
            "rotating_fresh_jvms",
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
            selected.getFirst().checksum,
            operations,
            timeSamples.length,
            0L,
            "fresh_jvms_same_flags",
            timeSamples,
            allocationSamples
        );
    }

    private static void appendResult(
        StringBuilder csv,
        String rowType,
        Result result
    ) {
        csv.append(rowType).append(',')
            .append(result.scenario.id).append(',')
            .append(result.fork).append(',')
            .append(result.launchOrder).append(',')
            .append(decimal(result.medianNsPerOperation)).append(',')
            .append(decimal(result.p95NsPerOperation)).append(',')
            .append(decimal(result.p99NsPerOperation)).append(',')
            .append(decimal(result.allocatedBytesPerOperation))
            .append(',')
            .append(
                decimal(
                    result.medianAllocatedBytesPerOperation
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
            .append(Long.toUnsignedString(result.checksum)).append(',')
            .append(result.operations).append(',')
            .append(result.sampleCount).append(',')
            .append(result.workerPid).append(',')
            .append(result.jvmFlags).append(',')
            .append(MEASUREMENT_SCOPE).append(',')
            .append(LIMITATIONS)
            .append('\n');
    }

    private static String launchOrder(
        Scenario[] scenarios,
        int rotation
    ) {
        StringBuilder order = new StringBuilder(128);
        for (int offset = 0; offset < scenarios.length; offset++) {
            if (offset > 0) {
                order.append("_then_");
            }
            order.append(
                scenarios[(rotation + offset) % scenarios.length].id
            );
        }
        return order.toString();
    }

    private static long runPolicyEnabled(
        State state,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        int cursor = 0;
        for (int operation = 0; operation < iterations; operation++) {
            FeatureId id = FEATURES[cursor];
            boolean enabled = state.policy.enabled(id);
            checksum = mix(
                checksum,
                id.mask() ^ (enabled ? 1L : 0L)
            );
            cursor++;
            if (cursor == FEATURES.length) {
                cursor = 0;
            }
        }
        return checksum;
    }

    private static long runRegistryState(
        State state,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        int cursor = 0;
        for (int operation = 0; operation < iterations; operation++) {
            FeatureState feature =
                state.registry.state(FEATURES[cursor]);
            long value = feature.id().mask();
            value ^= feature.requested() ? 1L << 20 : 0L;
            value ^= feature.enabled() ? 1L << 21 : 0L;
            value ^= feature.clientGeneration() << 22;
            checksum = mix(checksum, value);
            cursor++;
            if (cursor == FEATURES.length) {
                cursor = 0;
            }
        }
        return checksum;
    }

    private static long runRegistrySnapshot(
        State state,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int operation = 0; operation < iterations; operation++) {
            FeatureStateRegistry.Snapshot snapshot =
                state.registry.snapshot();
            checksum = mix(
                checksum,
                snapshot.revision()
                    ^ snapshot.requestedMask()
                    ^ Long.rotateLeft(snapshot.enabledMask(), 13)
            );
        }
        return checksum;
    }

    private static long runRegistryDebugLines(
        State state,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        int cursor = 0;
        for (int operation = 0; operation < iterations; operation++) {
            List<String> lines = state.registry.debugLines();
            String line = lines.get(cursor);
            checksum = mix(
                checksum,
                Integer.toUnsignedLong(line.hashCode())
            );
            cursor++;
            if (cursor == FEATURES.length) {
                cursor = 0;
            }
        }
        return checksum;
    }

    private static long runStableWorldFrame(
        State state,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int operation = 0; operation < iterations; operation++) {
            WorldFrameStabilityTracker.Transition transition =
                state.tracker.observeSuccessfulFrame(WORLD);
            checksum = mix(
                checksum,
                Integer.toUnsignedLong(transition.ordinal())
                    ^ ((long)state.tracker.consecutiveFrames() << 8)
            );
        }
        return checksum;
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
            collections += Math.max(0L, bean.getCollectionCount());
            millis += Math.max(0L, bean.getCollectionTime());
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

    private enum Scenario {
        POLICY_ENABLED("runtime_feature_policy_enabled") {
            @Override
            long run(State state, int iterations) {
                return runPolicyEnabled(state, iterations);
            }
        },
        REGISTRY_STATE("feature_state_registry_cached_state") {
            @Override
            long run(State state, int iterations) {
                return runRegistryState(state, iterations);
            }
        },
        REGISTRY_SNAPSHOT("feature_state_registry_cached_snapshot") {
            @Override
            long run(State state, int iterations) {
                return runRegistrySnapshot(state, iterations);
            }
        },
        REGISTRY_DEBUG_LINES(
            "feature_state_registry_cached_debug_lines"
        ) {
            @Override
            long run(State state, int iterations) {
                return runRegistryDebugLines(state, iterations);
            }
        },
        STABLE_WORLD_FRAME(
            "world_frame_stability_tracker_stable_same_world"
        ) {
            @Override
            long run(State state, int iterations) {
                return runStableWorldFrame(state, iterations);
            }
        };

        private final String id;

        Scenario(String id) {
            this.id = id;
        }

        abstract long run(State state, int iterations);

        private static Scenario from(String id) {
            return Arrays.stream(values())
                .filter(scenario -> scenario.id.equals(id))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException(
                        "unknown scenario " + id
                    )
                );
        }
    }

    private static final class State {
        private final RuntimeFeaturePolicy policy;
        private final FeatureStateRegistry registry;
        private final FeatureStateRegistry.Snapshot publishedSnapshot;
        private final List<String> publishedDebugLines;
        private final FeatureState[] publishedStates;
        private final WorldFrameStabilityTracker tracker;
        private final long requestedMask;
        private final long enabledMask;

        private State(
            RuntimeFeaturePolicy policy,
            FeatureStateRegistry registry,
            FeatureStateRegistry.Snapshot publishedSnapshot,
            List<String> publishedDebugLines,
            FeatureState[] publishedStates,
            WorldFrameStabilityTracker tracker,
            long requestedMask,
            long enabledMask
        ) {
            this.policy = policy;
            this.registry = registry;
            this.publishedSnapshot = publishedSnapshot;
            this.publishedDebugLines = publishedDebugLines;
            this.publishedStates = publishedStates;
            this.tracker = tracker;
            this.requestedMask = requestedMask;
            this.enabledMask = enabledMask;
        }

        private static State create() {
            RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
                EngineConfig.Settings.defaults(),
                "quality",
                "heap",
                false
            );
            FeatureStateRegistry registry =
                new FeatureStateRegistry();
            policy.publishInitial(registry, 7L);
            FeatureState[] states =
                new FeatureState[FeatureId.COUNT];
            for (FeatureId id : FEATURES) {
                states[id.bitIndex()] = registry.state(id);
            }
            WorldFrameStabilityTracker tracker =
                new WorldFrameStabilityTracker(STABILITY_FRAMES);
            for (int frame = 1; frame <= STABILITY_FRAMES; frame++) {
                WorldFrameStabilityTracker.Transition transition =
                    tracker.observeSuccessfulFrame(WORLD);
                if (
                    frame == STABILITY_FRAMES
                        && transition
                            != WorldFrameStabilityTracker.Transition
                                .STABILITY_WINDOW_COMPLETE
                ) {
                    throw new IllegalStateException(
                        "tracker did not enter its stable state"
                    );
                }
            }
            return new State(
                policy,
                registry,
                registry.snapshot(),
                registry.debugLines(),
                states,
                tracker,
                policy.requestedMask(),
                policy.enabledMask()
            );
        }

        private void validate() {
            if (
                this.policy.requestedMask() != this.requestedMask
                    || this.policy.enabledMask() != this.enabledMask
            ) {
                throw new IllegalStateException(
                    "policy changed during a read-only measurement"
                );
            }
            if (this.registry.snapshot() != this.publishedSnapshot) {
                throw new IllegalStateException(
                    "registry snapshot identity changed"
                );
            }
            if (
                this.registry.debugLines()
                    != this.publishedDebugLines
            ) {
                throw new IllegalStateException(
                    "registry debug-line cache identity changed"
                );
            }
            for (FeatureId id : FEATURES) {
                if (
                    this.registry.state(id)
                        != this.publishedStates[id.bitIndex()]
                ) {
                    throw new IllegalStateException(
                        "cached feature state identity changed: "
                            + id.stableId()
                    );
                }
            }
            if (
                this.tracker.consecutiveFrames()
                    != STABILITY_FRAMES
            ) {
                throw new IllegalStateException(
                    "stable frame counter changed"
                );
            }
        }
    }

    private record GcSnapshot(
        long collections,
        long collectionMillis
    ) {
    }

    private record Result(
        Scenario scenario,
        int fork,
        String launchOrder,
        double medianNsPerOperation,
        double p95NsPerOperation,
        double p99NsPerOperation,
        double allocatedBytesPerOperation,
        double medianAllocatedBytesPerOperation,
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
            properties.setProperty("scenario", this.scenario.id);
            properties.setProperty(
                "fork",
                Integer.toString(this.fork)
            );
            properties.setProperty("launchOrder", this.launchOrder);
            properties.setProperty(
                "medianNsPerOperation",
                Double.toString(this.medianNsPerOperation)
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
                "medianAllocatedBytesPerOperation",
                Double.toString(
                    this.medianAllocatedBytesPerOperation
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
                Scenario.from(properties.getProperty("scenario")),
                Integer.parseInt(properties.getProperty("fork")),
                properties.getProperty("launchOrder"),
                Double.parseDouble(
                    properties.getProperty(
                        "medianNsPerOperation"
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
                        "medianAllocatedBytesPerOperation"
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
                Long.parseLong(properties.getProperty("operations")),
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
                    properties.getProperty("allocationSamples")
                )
            );
        }
    }
}
