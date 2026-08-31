package de.morau.nvidiadlss;

import com.sun.management.ThreadMXBean;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Properties;

/**
 * Fork-isolated comparison of the Phase 1A.7 fixed primitive sampler table
 * and the replaced HashMap/record lookup.
 *
 * <p>This measures warmed CPU lookup only. It does not launch Minecraft,
 * create a Vulkan sampler, measure driver memory, submit GPU work, or prove an
 * end-to-end frame-time change.</p>
 */
public final class Phase1a7MaterialSamplerBenchmark {
    private static final int DEFAULT_FORKS = 5;
    private static final int[] KEY_COUNTS = {1, 16, 64};
    private static final int WARMUP_ROUNDS = 5;
    private static final int WARMUP_ITERATIONS = 100_000;
    private static final int SAMPLE_COUNT = 21;
    private static final int ITERATIONS_PER_SAMPLE = 100_000;
    private static final OptionalDouble MAX_LOD =
        OptionalDouble.of(12.0D);
    private static final Object ADDRESS_U = new Object();
    private static final Object ADDRESS_V = new Object();
    private static final Object MIN_FILTER = new Object();
    private static final Object MAG_FILTER = new Object();
    private static final FixedMaterialSamplerCache.SamplerObserver
        NO_OBSERVER =
            (device, sampler, slot, bias, anisotropy) -> {
            };
    private static final FixedMaterialSamplerCache.SamplerFactory
        FAIL_ON_MISS =
            (
                device,
                descriptor,
                u,
                v,
                min,
                mag,
                anisotropy,
                maxLod,
                bias
            ) -> {
                throw new AssertionError("warmed lookup missed");
            };
    private static final String HEADER =
        "row,scenario,key_count,backend,fork,launch_order,"
            + "p50_ns_per_lookup,p95_ns_per_lookup,"
            + "p99_ns_per_lookup,allocated_bytes_per_lookup,"
            + "gc_collections,gc_pause_ms,checksum,operations,"
            + "sample_count,metadata_requested_ram_bytes,"
            + "metadata_committed_ram_bytes,"
            + "opaque_vulkan_driver_bytes,worker_pid,jvm_flags,"
            + "measurement_scope";

    private static volatile long blackhole;

    private Phase1a7MaterialSamplerBenchmark() {
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
        Path workerDirectory = Path.of(
            System.getProperty("blockframe.projectDir")
        ).resolve("build").resolve(
            "phase1a7-workers-" + System.nanoTime()
        );
        Files.createDirectories(workerDirectory);

        List<Result> results = new ArrayList<>();
        for (int fork = 1; fork <= forks; fork++) {
            for (
                int scenarioIndex = 0;
                scenarioIndex < KEY_COUNTS.length;
                scenarioIndex++
            ) {
                int keyCount = KEY_COUNTS[scenarioIndex];
                boolean fixedFirst =
                    ((fork + scenarioIndex) & 1) == 0;
                String order = fixedFirst
                    ? "fixed_then_legacy"
                    : "legacy_then_fixed";
                Backend first = fixedFirst
                    ? Backend.FIXED
                    : Backend.LEGACY;
                Backend second = fixedFirst
                    ? Backend.LEGACY
                    : Backend.FIXED;
                results.add(
                    runFreshWorker(
                        workerDirectory,
                        fork,
                        order,
                        keyCount,
                        first
                    )
                );
                results.add(
                    runFreshWorker(
                        workerDirectory,
                        fork,
                        order,
                        keyCount,
                        second
                    )
                );
            }
        }
        validate(results, forks);

        StringBuilder csv = new StringBuilder(16_384);
        csv.append(HEADER).append('\n');
        for (Result result : results) {
            appendResult(csv, "worker", result);
        }
        for (int keyCount : KEY_COUNTS) {
            for (Backend backend : Backend.values()) {
                appendResult(
                    csv,
                    "aggregate",
                    aggregate(results, keyCount, backend)
                );
            }
        }
        Files.writeString(
            output,
            csv,
            StandardCharsets.UTF_8
        );

        for (int keyCount : KEY_COUNTS) {
            Result fixed = aggregate(
                results,
                keyCount,
                Backend.FIXED
            );
            Result legacy = aggregate(
                results,
                keyCount,
                Backend.LEGACY
            );
            System.out.printf(
                Locale.ROOT,
                "keys=%d fixed/legacy p50 %.3f/%.3f ns "
                    + "(%.4fx), p95 %.3f/%.3f, p99 %.3f/%.3f, "
                    + "alloc %.3f/%.3f B/op, GC %d/%d %.3f/%.3f ms%n",
                keyCount,
                fixed.p50,
                legacy.p50,
                fixed.p50 / legacy.p50,
                fixed.p95,
                legacy.p95,
                fixed.p99,
                legacy.p99,
                fixed.allocatedBytesPerLookup,
                legacy.allocatedBytesPerLookup,
                fixed.gcCollections,
                legacy.gcCollections,
                fixed.gcPauseMillis,
                legacy.gcPauseMillis
            );
        }
        System.out.println(
            "Scope: warmed isolated CPU lookup; fresh alternating JVMs; "
                + "no Minecraft/Vulkan/GPU/frame-time/driver-byte claim"
        );
        System.out.println(
            "Fixed RAM policy: "
                + DlssSamplerPolicy.CACHE_METADATA_REQUESTED_BYTES
                + "/"
                + DlssSamplerPolicy.CACHE_METADATA_COMMITTED_BYTES
                + " requested/committed bytes; opaque Vulkan bytes "
                + "NOT_AVAILABLE"
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
        int keyCount,
        Backend backend
    ) throws Exception {
        Path workerOutput = directory.resolve(
            String.format(
                Locale.ROOT,
                "fork-%02d-keys-%02d-%s.properties",
                fork,
                keyCount,
                backend.id
            )
        );
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
            Phase1a7MaterialSamplerBenchmark.class.getName()
        );
        command.add("--worker");
        command.add(workerOutput.toString());
        command.add(Integer.toString(fork));
        command.add(order);
        command.add(Integer.toString(keyCount));
        command.add(backend.id);

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
                "worker expects output, fork, order, keys and backend"
            );
        }
        Path output = Path.of(arguments[1])
            .toAbsolutePath()
            .normalize();
        int fork = Integer.parseInt(arguments[2]);
        String order = arguments[3];
        int keyCount = Integer.parseInt(arguments[4]);
        Backend backend = Backend.from(arguments[5]);
        Lookup lookup = backend.create(keyCount);

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            blackhole ^= runLookups(
                lookup,
                WARMUP_ITERATIONS,
                keyCount
            );
        }

        ThreadMXBean threadBean = threadBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocatedBytes(
            threadBean,
            threadId
        );
        GcSnapshot gcBefore = gcSnapshot();
        double[] samples = new double[SAMPLE_COUNT];
        long checksum = 0L;
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long start = System.nanoTime();
            checksum ^= runLookups(
                lookup,
                ITERATIONS_PER_SAMPLE,
                keyCount
            );
            long elapsed = System.nanoTime() - start;
            samples[sample] =
                (double)elapsed / ITERATIONS_PER_SAMPLE;
        }
        GcSnapshot gcAfter = gcSnapshot();
        long allocatedAfter = allocatedBytes(
            threadBean,
            threadId
        );
        blackhole ^= checksum;

        Arrays.sort(samples);
        long operations =
            (long)SAMPLE_COUNT * ITERATIONS_PER_SAMPLE;
        double allocatedPerLookup =
            allocatedBefore < 0L || allocatedAfter < allocatedBefore
                ? -1.0D
                : (double)(allocatedAfter - allocatedBefore)
                    / operations;
        Result result = new Result(
            "keys_" + keyCount + "_warmed_hit",
            keyCount,
            backend,
            fork,
            order,
            percentile(samples, 0.50D),
            percentile(samples, 0.95D),
            percentile(samples, 0.99D),
            allocatedPerLookup,
            gcAfter.collections - gcBefore.collections,
            Math.max(
                0L,
                gcAfter.collectionMillis
                    - gcBefore.collectionMillis
            ),
            checksum,
            operations,
            SAMPLE_COUNT,
            backend == Backend.FIXED
                ? DlssSamplerPolicy
                    .CACHE_METADATA_REQUESTED_BYTES
                : -1L,
            backend == Backend.FIXED
                ? DlssSamplerPolicy
                    .CACHE_METADATA_COMMITTED_BYTES
                : -1L,
            -1L,
            ProcessHandle.current().pid(),
            "xms128m_xmx128m_g1_xbatch_active_processor_count_1",
            samples
        );
        Properties properties = result.toProperties();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            properties.store(
                stream,
                "Phase 1A.7 sampler benchmark worker"
            );
        }
    }

    private static long runLookups(
        Lookup lookup,
        int iterations,
        int keyCount
    ) {
        long checksum = 0x9E3779B97F4A7C15L;
        int mask = keyCount - 1;
        for (int index = 0; index < iterations; index++) {
            int value = lookup.lookup(index & mask);
            checksum = Long.rotateLeft(
                checksum ^ value,
                7
            ) + 0xD1B54A32D192ED03L;
        }
        return checksum;
    }

    private static void validate(
        List<Result> results,
        int forks
    ) {
        int expected = forks * KEY_COUNTS.length * 2;
        if (results.size() != expected) {
            throw new IllegalStateException(
                "expected "
                    + expected
                    + " workers, got "
                    + results.size()
            );
        }
        for (int fork = 1; fork <= forks; fork++) {
            for (int keyCount : KEY_COUNTS) {
                Result fixed = find(
                    results,
                    fork,
                    keyCount,
                    Backend.FIXED
                );
                Result legacy = find(
                    results,
                    fork,
                    keyCount,
                    Backend.LEGACY
                );
                if (fixed.checksum != legacy.checksum) {
                    throw new IllegalStateException(
                        "checksum mismatch for fork="
                            + fork
                            + " keys="
                            + keyCount
                    );
                }
            }
        }
    }

    private static Result aggregate(
        List<Result> results,
        int keyCount,
        Backend backend
    ) {
        List<Result> selected = results.stream()
            .filter(
                result ->
                    result.keyCount == keyCount
                        && result.backend == backend
            )
            .toList();
        double[] samples = selected.stream()
            .flatMapToDouble(
                result -> Arrays.stream(result.samples)
            )
            .sorted()
            .toArray();
        double allocated = selected.stream()
            .mapToDouble(
                result -> result.allocatedBytesPerLookup
            )
            .average()
            .orElse(-1.0D);
        long collections = selected.stream()
            .mapToLong(result -> result.gcCollections)
            .sum();
        double pause = selected.stream()
            .mapToDouble(result -> result.gcPauseMillis)
            .sum();
        long operations = selected.stream()
            .mapToLong(result -> result.operations)
            .sum();
        long checksum = selected.stream()
            .mapToLong(result -> result.checksum)
            .reduce(0L, (left, right) -> left ^ right);
        return new Result(
            "keys_" + keyCount + "_warmed_hit",
            keyCount,
            backend,
            0,
            "alternating_fresh_jvms",
            percentile(samples, 0.50D),
            percentile(samples, 0.95D),
            percentile(samples, 0.99D),
            allocated,
            collections,
            pause,
            checksum,
            operations,
            samples.length,
            backend == Backend.FIXED
                ? DlssSamplerPolicy
                    .CACHE_METADATA_REQUESTED_BYTES
                : -1L,
            backend == Backend.FIXED
                ? DlssSamplerPolicy
                    .CACHE_METADATA_COMMITTED_BYTES
                : -1L,
            -1L,
            0L,
            "fresh_jvms_same_flags",
            samples
        );
    }

    private static Result find(
        List<Result> results,
        int fork,
        int keyCount,
        Backend backend
    ) {
        return results.stream()
            .filter(
                result ->
                    result.fork == fork
                        && result.keyCount == keyCount
                        && result.backend == backend
            )
            .findFirst()
            .orElseThrow();
    }

    private static void appendResult(
        StringBuilder csv,
        String row,
        Result result
    ) {
        csv.append(row).append(',')
            .append(result.scenario).append(',')
            .append(result.keyCount).append(',')
            .append(result.backend.id).append(',')
            .append(result.fork).append(',')
            .append(result.order).append(',')
            .append(decimal(result.p50)).append(',')
            .append(decimal(result.p95)).append(',')
            .append(decimal(result.p99)).append(',')
            .append(decimal(result.allocatedBytesPerLookup))
            .append(',')
            .append(result.gcCollections).append(',')
            .append(decimal(result.gcPauseMillis)).append(',')
            .append(Long.toUnsignedString(result.checksum))
            .append(',')
            .append(result.operations).append(',')
            .append(result.sampleCount).append(',')
            .append(result.metadataRequestedRamBytes).append(',')
            .append(result.metadataCommittedRamBytes).append(',')
            .append(result.opaqueVulkanDriverBytes).append(',')
            .append(result.workerPid).append(',')
            .append(result.jvmFlags).append(',')
            .append(
                "isolated_warmed_cpu_lookup_no_minecraft_vulkan_gpu_or_frame_claim"
            )
            .append('\n');
    }

    private static ThreadMXBean threadBean() {
        java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean extended) {
            if (
                extended.isThreadAllocatedMemorySupported()
                    && !extended.isThreadAllocatedMemoryEnabled()
            ) {
                extended.setThreadAllocatedMemoryEnabled(true);
            }
            return extended;
        }
        return null;
    }

    private static long allocatedBytes(
        ThreadMXBean bean,
        long threadId
    ) {
        return bean == null
                || !bean.isThreadAllocatedMemorySupported()
            ? -1L
            : bean.getThreadAllocatedBytes(threadId);
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
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
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

    private interface Lookup {
        int lookup(int keyIndex);
    }

    private enum Backend {
        FIXED("fixed") {
            @Override
            Lookup create(int keyCount) {
                float[] biases = biases(keyCount);
                BenchValue[] values =
                    Phase1a7MaterialSamplerBenchmark.values(
                        keyCount
                    );
                FixedMaterialSamplerCache cache =
                    new FixedMaterialSamplerCache(
                        new Object(),
                        new NoopLeaseController(),
                        1L,
                        new ShaderResourceInventory(),
                        64
                    );
                Object original = new BenchValue(-1);
                for (int index = 0; index < keyCount; index++) {
                    BenchValue value = values[index];
                    Object selected = cache.select(
                        original,
                        ADDRESS_U,
                        ADDRESS_V,
                        MIN_FILTER,
                        MAG_FILTER,
                        4,
                        MAX_LOD,
                        biases[index],
                        (
                            device,
                            descriptor,
                            u,
                            v,
                            min,
                            mag,
                            anisotropy,
                            maxLod,
                            bias
                        ) -> value,
                        NO_OBSERVER
                    );
                    if (selected != value) {
                        throw new IllegalStateException(
                            "fixed cache seed failed"
                        );
                    }
                }
                return keyIndex ->
                    ((BenchValue)cache.select(
                        original,
                        ADDRESS_U,
                        ADDRESS_V,
                        MIN_FILTER,
                        MAG_FILTER,
                        4,
                        MAX_LOD,
                        biases[keyIndex],
                        FAIL_ON_MISS,
                        NO_OBSERVER
                    )).id;
            }
        },
        LEGACY("legacy_map_record") {
            @Override
            Lookup create(int keyCount) {
                float[] biases = biases(keyCount);
                BenchValue[] values =
                    Phase1a7MaterialSamplerBenchmark.values(
                        keyCount
                    );
                Map<LegacyKey, BenchValue> cache =
                    new HashMap<>();
                for (int index = 0; index < keyCount; index++) {
                    cache.put(
                        key(biases[index]),
                        values[index]
                    );
                }
                return keyIndex -> {
                    BenchValue value = cache.get(
                        key(biases[keyIndex])
                    );
                    if (value == null) {
                        throw new AssertionError(
                            "legacy lookup missed"
                        );
                    }
                    return value.id;
                };
            }
        };

        private final String id;

        Backend(String id) {
            this.id = id;
        }

        abstract Lookup create(int keyCount);

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

    private static LegacyKey key(float bias) {
        return new LegacyKey(
            ADDRESS_U,
            ADDRESS_V,
            MIN_FILTER,
            MAG_FILTER,
            4,
            Double.doubleToLongBits(MAX_LOD.getAsDouble()),
            Float.floatToIntBits(bias)
        );
    }

    private static float[] biases(int keyCount) {
        float[] result = new float[keyCount];
        for (int index = 0; index < keyCount; index++) {
            result[index] = -0.5F - index * 0.03125F;
        }
        return result;
    }

    private static BenchValue[] values(int keyCount) {
        BenchValue[] result = new BenchValue[keyCount];
        for (int index = 0; index < keyCount; index++) {
            result[index] = new BenchValue(index + 1);
        }
        return result;
    }

    private record LegacyKey(
        Object addressModeU,
        Object addressModeV,
        Object minFilter,
        Object magFilter,
        int maxAnisotropy,
        long maxLodBits,
        int biasBits
    ) {
    }

    private record BenchValue(int id) {
    }

    private record GcSnapshot(
        long collections,
        long collectionMillis
    ) {
    }

    private static final class NoopLeaseController
        implements FixedMaterialSamplerCache.LeaseController {
        @Override
        public long tryReserve(long requested, long committed) {
            return 1L;
        }

        @Override
        public boolean release(long token) {
            return true;
        }

        @Override
        public boolean retireAfterGpuUse(long token) {
            return true;
        }
    }

    private record Result(
        String scenario,
        int keyCount,
        Backend backend,
        int fork,
        String order,
        double p50,
        double p95,
        double p99,
        double allocatedBytesPerLookup,
        long gcCollections,
        double gcPauseMillis,
        long checksum,
        long operations,
        int sampleCount,
        long metadataRequestedRamBytes,
        long metadataCommittedRamBytes,
        long opaqueVulkanDriverBytes,
        long workerPid,
        String jvmFlags,
        double[] samples
    ) {
        private Properties toProperties() {
            Properties properties = new Properties();
            properties.setProperty("scenario", this.scenario);
            properties.setProperty(
                "keyCount",
                Integer.toString(this.keyCount)
            );
            properties.setProperty("backend", this.backend.id);
            properties.setProperty(
                "fork",
                Integer.toString(this.fork)
            );
            properties.setProperty("order", this.order);
            properties.setProperty(
                "p50",
                Double.toString(this.p50)
            );
            properties.setProperty(
                "p95",
                Double.toString(this.p95)
            );
            properties.setProperty(
                "p99",
                Double.toString(this.p99)
            );
            properties.setProperty(
                "allocated",
                Double.toString(
                    this.allocatedBytesPerLookup
                )
            );
            properties.setProperty(
                "gcCollections",
                Long.toString(this.gcCollections)
            );
            properties.setProperty(
                "gcPauseMillis",
                Double.toString(this.gcPauseMillis)
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
                "metadataRequestedRamBytes",
                Long.toString(this.metadataRequestedRamBytes)
            );
            properties.setProperty(
                "metadataCommittedRamBytes",
                Long.toString(this.metadataCommittedRamBytes)
            );
            properties.setProperty(
                "opaqueVulkanDriverBytes",
                Long.toString(this.opaqueVulkanDriverBytes)
            );
            properties.setProperty(
                "workerPid",
                Long.toString(this.workerPid)
            );
            properties.setProperty(
                "jvmFlags",
                this.jvmFlags
            );
            properties.setProperty(
                "samples",
                join(this.samples)
            );
            return properties;
        }

        private static Result from(Properties properties) {
            return new Result(
                properties.getProperty("scenario"),
                Integer.parseInt(
                    properties.getProperty("keyCount")
                ),
                Backend.from(
                    properties.getProperty("backend")
                ),
                Integer.parseInt(
                    properties.getProperty("fork")
                ),
                properties.getProperty("order"),
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
                    properties.getProperty("allocated")
                ),
                Long.parseLong(
                    properties.getProperty("gcCollections")
                ),
                Double.parseDouble(
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
                    properties.getProperty(
                        "metadataRequestedRamBytes"
                    )
                ),
                Long.parseLong(
                    properties.getProperty(
                        "metadataCommittedRamBytes"
                    )
                ),
                Long.parseLong(
                    properties.getProperty(
                        "opaqueVulkanDriverBytes"
                    )
                ),
                Long.parseLong(
                    properties.getProperty("workerPid")
                ),
                properties.getProperty("jvmFlags"),
                parseSamples(
                    properties.getProperty("samples")
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
