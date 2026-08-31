package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.core.scheduling.FrameBudgetController;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ProducerIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fork-isolated scaling evidence for BlockFrame's V2 payload encoder and the
 * bounded production compiler job system.
 *
 * <p>The immutable input has the same 58-quad Solid/Cutout shape and 7,424
 * output bytes as the real Foundation-B fixture. It deliberately contains no
 * Minecraft model invocation; real BlockStateModel/NeoForge scaling and
 * client-thread impact therefore remain a separate live gate.</p>
 */
public final class RendererCCompilerScalingBenchmark {
    private static final int[] WORKERS = {1, 2, 4, 6};
    private static final int DEFAULT_FORKS = 3;
    private static final int WARMUP_SECTIONS = 512;
    private static final int MEASURE_SECTIONS = 4096;
    private static final int QUADS_PER_SECTION = 58;
    private static final long TIMEOUT_SECONDS = 120L;
    private static volatile long blackhole;

    private RendererCCompilerScalingBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "--worker".equals(arguments[0])) {
            runWorker(arguments);
            return;
        }
        runController(arguments);
    }

    private static void runController(String[] arguments)
        throws Exception {
        if (arguments.length < 1 || arguments.length > 2) {
            throw new IllegalArgumentException(
                "expected output CSV and optional fork count"
            );
        }
        Path output = Path.of(arguments[0])
            .toAbsolutePath()
            .normalize();
        int forks = arguments.length == 2
            ? Integer.parseInt(arguments[1])
            : DEFAULT_FORKS;
        if (forks <= 0) {
            throw new IllegalArgumentException(
                "fork count must be positive"
            );
        }
        Files.createDirectories(output.getParent());
        Path workersDirectory = output.resolveSibling(
            output.getFileName() + ".workers"
        );
        Files.createDirectories(workersDirectory);

        StringBuilder csv = new StringBuilder(
            "fork,worker_count,launch_order,sections,quads,"
                + "wall_nanos,sections_per_second,quads_per_second,"
                + "p50_section_nanos,p95_section_nanos,"
                + "p99_section_nanos,total_cpu_nanos,"
                + "allocated_bytes,allocated_bytes_per_section,"
                + "gc_collections,gc_millis,max_backlog,"
                + "completed_jobs,steals,checksum,scope,limitations\n"
        );
        for (int fork = 1; fork <= forks; fork++) {
            int[] order = WORKERS.clone();
            if ((fork & 1) == 0) {
                reverse(order);
            }
            String launchOrder = (fork & 1) == 0
                ? "6-4-2-1"
                : "1-2-4-6";
            for (int workers : order) {
                Path properties = workersDirectory.resolve(
                    String.format(
                        Locale.ROOT,
                        "fork-%02d-workers-%d.properties",
                        fork,
                        workers
                    )
                );
                Path log = properties.resolveSibling(
                    properties.getFileName() + ".log"
                );
                launchWorker(
                    properties,
                    log,
                    workers,
                    fork,
                    launchOrder
                );
                Properties result = readProperties(properties);
                csv.append(fork).append(',')
                    .append(workers).append(',')
                    .append(launchOrder).append(',')
                    .append(result.getProperty("sections")).append(',')
                    .append(result.getProperty("quads")).append(',')
                    .append(result.getProperty("wallNanos")).append(',')
                    .append(result.getProperty("sectionsPerSecond"))
                    .append(',')
                    .append(result.getProperty("quadsPerSecond"))
                    .append(',')
                    .append(result.getProperty("p50SectionNanos"))
                    .append(',')
                    .append(result.getProperty("p95SectionNanos"))
                    .append(',')
                    .append(result.getProperty("p99SectionNanos"))
                    .append(',')
                    .append(result.getProperty("totalCpuNanos"))
                    .append(',')
                    .append(result.getProperty("allocatedBytes"))
                    .append(',')
                    .append(
                        result.getProperty(
                            "allocatedBytesPerSection"
                        )
                    )
                    .append(',')
                    .append(result.getProperty("gcCollections"))
                    .append(',')
                    .append(result.getProperty("gcMillis")).append(',')
                    .append(result.getProperty("maxBacklog")).append(',')
                    .append(result.getProperty("completedJobs"))
                    .append(',')
                    .append(result.getProperty("steals")).append(',')
                    .append(result.getProperty("checksum")).append(',')
                    .append("blockframe_v2_payload_encoder").append(',')
                    .append(
                        "no_minecraft_model_tessellation_no_client_"
                            + "thread_no_vulkan_no_renderer_speedup_claim"
                    )
                    .append('\n');
            }
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        System.out.println("Renderer C scaling CSV: " + output);
        System.out.println("Worker evidence: " + workersDirectory);
    }

    private static void launchWorker(
        Path result,
        Path log,
        int workers,
        int fork,
        String order
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xms512m");
        command.add("-Xmx512m");
        command.add("-XX:+UseG1GC");
        command.add("-XX:ActiveProcessorCount=16");
        command.add("-Xbatch");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RendererCCompilerScalingBenchmark.class.getName());
        command.add("--worker");
        command.add(result.toString());
        command.add(Integer.toString(workers));
        command.add(Integer.toString(fork));
        command.add(order);
        ProcessBuilder builder = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new IllegalStateException(
                "Renderer C worker timed out: " + result
            );
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                "Renderer C worker failed: "
                    + result
                    + System.lineSeparator()
                    + Files.readString(log, StandardCharsets.UTF_8)
            );
        }
    }

    private static void runWorker(String[] arguments)
        throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                "worker requires result, worker count, fork and order"
            );
        }
        Path result = Path.of(arguments[1])
            .toAbsolutePath()
            .normalize();
        int workerCount = Integer.parseInt(arguments[2]);
        int fork = Integer.parseInt(arguments[3]);
        String order = arguments[4];
        if (Arrays.stream(WORKERS).noneMatch(v -> v == workerCount)) {
            throw new IllegalArgumentException("invalid worker count");
        }

        Fixture fixture = fixture();
        ConcurrentLinkedQueue<CompilerContext> contexts =
            new ConcurrentLinkedQueue<>();
        ThreadLocal<CompilerContext> local =
            ThreadLocal.withInitial(() -> {
                CompilerContext context = new CompilerContext();
                contexts.add(context);
                return context;
            });
        long[] elapsed = new long[MEASURE_SECTIONS];
        long[] allocated = new long[MEASURE_SECTIONS];
        long[] checksums = new long[MEASURE_SECTIONS];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        com.sun.management.ThreadMXBean bean = allocationBean();
        long gcCountBefore;
        long gcMillisBefore;
        long workerCpuBefore;
        long workerCpuAfter;
        int maximumBacklog = 0;
        NativeTerrainJobSystem.Snapshot completed;
        long wallNanos;

        try (
            NativeTerrainJobSystem jobs =
                new NativeTerrainJobSystem(
                    new NativeTerrainJobSystem.Topology(16, 8, true),
                    MEASURE_SECTIONS + WARMUP_SECTIONS
                )
        ) {
            jobs.applyBudget(
                new FrameBudgetController.Decision(
                    workerCount,
                    0,
                    0L,
                    0,
                    false,
                    "fork-isolated-renderer-c-scaling"
                )
            );
            runBatch(
                jobs,
                local,
                fixture,
                WARMUP_SECTIONS,
                null,
                null,
                null,
                failure
            );
            requireNoFailure(failure);

            gcCountBefore = gcCollections();
            gcMillisBefore = gcMillis();
            workerCpuBefore = workerCpuNanos(bean);
            long wallStarted = System.nanoTime();
            CountDownLatch latch = new CountDownLatch(MEASURE_SECTIONS);
            for (int index = 0; index < MEASURE_SECTIONS; index++) {
                int sample = index;
                if (
                    !jobs.submit(
                        new NativeTerrainJobSystem.Job(
                            NativeTerrainJobSystem.Priority.NEAR,
                            sample,
                            () -> true,
                            () -> {
                                try {
                                    measureOne(
                                        local.get(),
                                        fixture,
                                        bean,
                                        elapsed,
                                        allocated,
                                        checksums,
                                        sample
                                    );
                                } catch (Throwable error) {
                                    failure.compareAndSet(null, error);
                                } finally {
                                    latch.countDown();
                                }
                            }
                        )
                    )
                ) {
                    throw new IllegalStateException(
                        "bounded job queue rejected measurement sample "
                            + sample
                    );
                }
                if ((index & 63) == 0) {
                    maximumBacklog = Math.max(
                        maximumBacklog,
                        jobs.snapshot().queuedJobs()
                    );
                }
            }
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "measurement batch timed out"
                );
            }
            wallNanos = System.nanoTime() - wallStarted;
            workerCpuAfter = workerCpuNanos(bean);
            completed = jobs.snapshot();
            maximumBacklog = Math.max(
                maximumBacklog,
                completed.queuedJobs()
            );
            requireNoFailure(failure);
        } finally {
            local.remove();
            for (CompilerContext context : contexts) {
                context.close();
            }
        }

        long expectedChecksum = checksums[0];
        for (long checksum : checksums) {
            if (checksum != expectedChecksum) {
                throw new IllegalStateException(
                    "compiled payload checksum changed"
                );
            }
        }
        long totalAllocated = sum(allocated);
        long totalCpu = delta(workerCpuBefore, workerCpuAfter);
        long gcCount = delta(gcCountBefore, gcCollections());
        long gcMillis = delta(gcMillisBefore, gcMillis());
        Properties properties = new Properties();
        properties.setProperty("fork", Integer.toString(fork));
        properties.setProperty("launchOrder", order);
        properties.setProperty(
            "workers",
            Integer.toString(workerCount)
        );
        properties.setProperty(
            "sections",
            Integer.toString(MEASURE_SECTIONS)
        );
        properties.setProperty(
            "quads",
            Integer.toString(
                Math.multiplyExact(
                    MEASURE_SECTIONS,
                    QUADS_PER_SECTION
                )
            )
        );
        properties.setProperty(
            "wallNanos",
            Long.toString(wallNanos)
        );
        properties.setProperty(
            "sectionsPerSecond",
            formatRate(MEASURE_SECTIONS, wallNanos)
        );
        properties.setProperty(
            "quadsPerSecond",
            formatRate(
                (long)MEASURE_SECTIONS * QUADS_PER_SECTION,
                wallNanos
            )
        );
        properties.setProperty(
            "p50SectionNanos",
            Long.toString(percentile(elapsed, 0.50D))
        );
        properties.setProperty(
            "p95SectionNanos",
            Long.toString(percentile(elapsed, 0.95D))
        );
        properties.setProperty(
            "p99SectionNanos",
            Long.toString(percentile(elapsed, 0.99D))
        );
        properties.setProperty(
            "totalCpuNanos",
            Long.toString(totalCpu)
        );
        properties.setProperty(
            "allocatedBytes",
            Long.toString(totalAllocated)
        );
        properties.setProperty(
            "allocatedBytesPerSection",
            String.format(
                Locale.ROOT,
                "%.3f",
                (double)totalAllocated / MEASURE_SECTIONS
            )
        );
        properties.setProperty(
            "gcCollections",
            Long.toString(gcCount)
        );
        properties.setProperty(
            "gcMillis",
            Long.toString(gcMillis)
        );
        properties.setProperty(
            "maxBacklog",
            Integer.toString(maximumBacklog)
        );
        properties.setProperty(
            "completedJobs",
            Long.toString(completed.completedJobs())
        );
        properties.setProperty(
            "steals",
            Long.toString(completed.steals())
        );
        properties.setProperty(
            "checksum",
            Long.toUnsignedString(expectedChecksum)
        );
        try (OutputStream output = Files.newOutputStream(result)) {
            properties.store(
                output,
                "Renderer C compiler scaling worker"
            );
        }
        System.out.println(
            "complete blackhole=" + Long.toUnsignedString(blackhole)
        );
    }

    private static void runBatch(
        NativeTerrainJobSystem jobs,
        ThreadLocal<CompilerContext> local,
        Fixture fixture,
        int sections,
        long[] elapsed,
        long[] allocated,
        long[] checksums,
        AtomicReference<Throwable> failure
    ) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(sections);
        for (int index = 0; index < sections; index++) {
            int sample = index;
            if (
                !jobs.submit(
                    new NativeTerrainJobSystem.Job(
                        NativeTerrainJobSystem.Priority.NEAR,
                        sample,
                        () -> true,
                        () -> {
                            try {
                                long checksum =
                                    compileOne(local.get(), fixture);
                                blackhole ^= checksum;
                            } catch (Throwable error) {
                                failure.compareAndSet(null, error);
                            } finally {
                                latch.countDown();
                            }
                        }
                    )
                )
            ) {
                throw new IllegalStateException(
                    "warmup queue rejected a section"
                );
            }
        }
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("warmup timed out");
        }
    }

    private static void measureOne(
        CompilerContext context,
        Fixture fixture,
        com.sun.management.ThreadMXBean bean,
        long[] elapsed,
        long[] allocated,
        long[] checksums,
        int sample
    ) {
        long threadId = Thread.currentThread().threadId();
        long allocationBefore =
            bean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        long checksum = compileOne(context, fixture);
        elapsed[sample] = System.nanoTime() - started;
        allocated[sample] =
            bean.getThreadAllocatedBytes(threadId) - allocationBefore;
        checksums[sample] = checksum;
        blackhole ^= checksum;
    }

    private static long compileOne(
        CompilerContext context,
        Fixture fixture
    ) {
        var result = context.compiler.compile(
            fixture.snapshot,
            fixture.census,
            BlockFrameSectionCompiler.CancellationSignal.NEVER
        );
        CompiledPayloadBatch batch =
            result.batch().orElseThrow();
        long checksum = 0x6A09E667F3BCC909L;
        for (var entry : batch.channels().entrySet()) {
            var channel = entry.getValue();
            checksum = Long.rotateLeft(
                checksum ^ entry.getKey().ordinal(),
                7
            );
            checksum = Long.rotateLeft(
                checksum ^ channel.byteLength(),
                13
            );
            checksum = Long.rotateLeft(
                checksum ^ channel.primitiveCount(),
                17
            );
        }
        batch.close();
        return checksum;
    }

    private static Fixture fixture() {
        var solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        var cutout = NativeTerrainCompilerTestFixtures.entry(
            Category.CUTOUT
        );
        var census = NativeTerrainCompilerTestFixtures.census(
            solid,
            cutout
        );
        List<Primitive> primitives =
            new ArrayList<>(QUADS_PER_SECTION);
        for (int index = 0; index < QUADS_PER_SECTION; index++) {
            primitives.add(
                NativeTerrainCompilerTestFixtures.quad(
                    index + 1L,
                    (index & 1) == 0 ? solid : cutout,
                    index % 15
                )
            );
        }
        return new Fixture(
            census,
            NativeTerrainCompilerTestFixtures.snapshot(
                census,
                primitives.toArray(Primitive[]::new)
            )
        );
    }

    private static final class CompilerContext implements AutoCloseable {
        private final NativeTerrainPayloadArena arena =
            new NativeTerrainPayloadArena(1024 * 1024, 2);
        private final BlockFrameSectionCompiler compiler =
            new BlockFrameSectionCompiler(
                new BlockFrameSectionCompiler.CompilerContract(
                    new ProducerIdentity(
                        NativeTerrainCompilerTestFixtures.id(40L),
                        1
                    ),
                    NativeTerrainCompilerTestFixtures.id(41L),
                    1L,
                    100L
                ),
                this.arena
            );

        @Override
        public void close() {
            this.arena.close();
        }
    }

    private record Fixture(
        NativeTerrainAssetCensus.Result census,
        NativeTerrainSectionSnapshot snapshot
    ) {
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = (com.sun.management.ThreadMXBean)
            ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        if (!bean.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException(
                "thread CPU time is unavailable"
            );
        }
        if (!bean.isThreadCpuTimeEnabled()) {
            bean.setThreadCpuTimeEnabled(true);
        }
        return bean;
    }

    private static void requireNoFailure(
        AtomicReference<Throwable> failure
    ) {
        Throwable error = failure.get();
        if (error != null) {
            throw new IllegalStateException(
                "compiler worker failed",
                error
            );
        }
    }

    private static long percentile(long[] source, double quantile) {
        long[] values = source.clone();
        Arrays.sort(values);
        int rank = Math.max(
            0,
            Math.min(
                values.length - 1,
                (int)Math.ceil(values.length * quantile) - 1
            )
        );
        return values[rank];
    }

    private static long sum(long[] values) {
        long result = 0L;
        for (long value : values) {
            result = Math.addExact(result, value);
        }
        return result;
    }

    private static long gcCollections() {
        long total = 0L;
        for (var collector :
            ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = collector.getCollectionCount();
            if (value < 0L) {
                return -1L;
            }
            total = Math.addExact(total, value);
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0L;
        for (var collector :
            ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = collector.getCollectionTime();
            if (value < 0L) {
                return -1L;
            }
            total = Math.addExact(total, value);
        }
        return total;
    }

    private static long delta(long before, long after) {
        return before < 0L || after < 0L
            ? -1L
            : Math.max(0L, after - before);
    }

    private static long workerCpuNanos(
        com.sun.management.ThreadMXBean bean
    ) {
        long total = 0L;
        boolean found = false;
        for (long threadId : bean.getAllThreadIds()) {
            var info = bean.getThreadInfo(threadId);
            if (
                info == null
                    || !info.getThreadName().startsWith(
                        "BlockFrame-Terrain-Compiler-"
                    )
            ) {
                continue;
            }
            long value = bean.getThreadCpuTime(threadId);
            if (value >= 0L) {
                total = Math.addExact(total, value);
                found = true;
            }
        }
        return found ? total : -1L;
    }

    private static String formatRate(long units, long nanos) {
        return String.format(
            Locale.ROOT,
            "%.3f",
            units * 1_000_000_000.0D / nanos
        );
    }

    private static String javaExecutable() {
        String executable =
            System.getProperty("os.name", "")
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

    private static Properties readProperties(Path path)
        throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void reverse(int[] values) {
        for (
            int left = 0, right = values.length - 1;
            left < right;
            left++, right--
        ) {
            int value = values[left];
            values[left] = values[right];
            values[right] = value;
        }
    }
}
