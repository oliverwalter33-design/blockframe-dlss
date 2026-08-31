package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reproducible Phase 1A.5 microbenchmark for the production-sized entity
 * motion-history backing stores.
 *
 * <p>The baseline heap and candidate native paths run the same fixed IDs,
 * values, load factor and previous-frame/current-frame operation sequence.
 * Storage construction, deterministic priming, snapshots and close are
 * outside every timing and thread-allocation window. This is an isolated CPU
 * microbenchmark: it does not run Minecraft, a renderer or a GPU backend.</p>
 */
public final class Phase1a5NativeEntityHistoryBenchmark {
    private static final int CAPACITY = 65_536;
    private static final int ENTITIES_PER_FRAME = CAPACITY * 3 / 4;
    private static final int WARMUP_ROUNDS = 5;
    private static final int WARMUP_FRAMES_PER_ROUND = 2;
    private static final int SAMPLE_COUNT = 15;
    private static final int MEASURED_FRAMES_PER_SAMPLE = 4;
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;
    private static final String WORKLOAD =
        "capacity_65536_49152_fixed_ids_previous_lookup_four_reads_"
            + "current_write_at_75_percent_load";
    private static final String RAM_SCOPE =
        "whole_jvm_and_process_sample_boundary_observations_no_forced_gc_"
            + "not_storage_attribution";
    private static final String SCOPE =
        "isolated_cpu_entity_history_no_minecraft_renderer_gpu_or_"
            + "end_to_end_speedup_claim";
    private static final String LIMITATIONS =
        "fixed_heap_then_native_order_setup_prime_reset_close_excluded_"
            + "rss_sampled_only_at_boundaries_gc_time_is_mxbean_"
            + "collection_time_p99_is_max_of_15_samples";
    private static final String[] CSV_COLUMNS = {
        "row_type",
        "path",
        "storage_kind",
        "sample",
        "capacity",
        "entities_per_frame",
        "occupancy_ratio",
        "warmup_rounds",
        "warmup_frames_per_round",
        "measurement_samples",
        "frames",
        "entity_operations",
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
        "p50_thread_allocated_bytes_per_entity",
        "p95_thread_allocated_bytes_per_entity",
        "p99_thread_allocated_bytes_per_entity",
        "total_gc_count_delta",
        "total_gc_pause_ms_delta",
        "checksum",
        "storage_requested_ram_bytes",
        "storage_committed_ram_bytes",
        "budget_requested_ram_bytes",
        "budget_used_ram_bytes",
        "vram_bytes",
        "jvm_heap_used_before_bytes",
        "jvm_heap_used_after_bytes",
        "jvm_heap_used_observed_max_bytes",
        "jvm_heap_committed_before_bytes",
        "jvm_heap_committed_after_bytes",
        "jvm_heap_committed_observed_max_bytes",
        "jvm_nonheap_used_before_bytes",
        "jvm_nonheap_used_after_bytes",
        "jvm_nonheap_used_observed_max_bytes",
        "jvm_nonheap_committed_before_bytes",
        "jvm_nonheap_committed_after_bytes",
        "jvm_nonheap_committed_observed_max_bytes",
        "process_rss_before_bytes",
        "process_rss_after_bytes",
        "process_rss_observed_max_bytes",
        "process_committed_virtual_before_bytes",
        "process_committed_virtual_after_bytes",
        "process_committed_virtual_observed_max_bytes",
        "process_rss_source",
        "ram_observation_scope",
        "workload",
        "scope",
        "limitations"
    };

    private static volatile long blackhole;

    private Phase1a5NativeEntityHistoryBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                "expected output CSV path"
            );
        }

        com.sun.management.ThreadMXBean allocationBean =
            optionalAllocationBean();
        long threadId = Thread.currentThread().threadId();
        GcProbe gcProbe = new GcProbe(
            ManagementFactory.getGarbageCollectorMXBeans()
        );
        ProcessRssProbe processRssProbe = ProcessRssProbe.create();
        try {
            PathResult heap = runPath(
                BenchmarkPath.HEAP,
                allocationBean,
                threadId,
                gcProbe,
                processRssProbe
            );
            PathResult nativeResult = runPath(
                BenchmarkPath.NATIVE,
                allocationBean,
                threadId,
                gcProbe,
                processRssProbe
            );

            requireEquivalentResults(heap, nativeResult);

            String csv = createCsv(heap, nativeResult);
            Path output = Path.of(arguments[0])
                .toAbsolutePath()
                .normalize();
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, csv, StandardCharsets.UTF_8);
            System.out.print(csv);
            printSummary(heap);
            printSummary(nativeResult);
            System.out.println(
                "Thread allocation accounting supported: "
                    + (allocationBean != null)
            );
            System.out.println(
                "Process RSS source: " + processRssProbe.source()
            );
            System.out.println(
                "Benchmark limits: isolated CPU history only; no "
                    + "Minecraft, renderer, Vulkan/OpenGL, GPU, device "
                    + "recreation, world reload, frame pacing or "
                    + "end-to-end speedup proof. p99 is the maximum of "
                    + "15 samples."
            );
            System.out.println(
                "Blackhole: " + Long.toUnsignedString(blackhole)
            );
            System.out.println("CSV: " + output);
        } finally {
            processRssProbe.close();
        }
    }

    private static PathResult runPath(
        BenchmarkPath path,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId,
        GcProbe gcProbe,
        ProcessRssProbe processRssProbe
    ) {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = null;
        boolean historyClosed = false;
        boolean managerClosed = false;
        try {
            history = path.create(budgets);
            if (history == null) {
                throw new IllegalStateException(
                    path.label + " could not reserve its fixed storage"
                );
            }
            requireHistoryShape(path, history);

            long requestedBytes = history.requestedBytes();
            long committedBytes = history.committedBytes();
            MemoryBudgetManager.Snapshot setupSnapshot =
                budgets.snapshot();
            requireSetupFootprint(
                path,
                setupSnapshot,
                requestedBytes,
                committedBytes
            );

            long[] warmupChecksums = new long[WARMUP_ROUNDS];
            for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
                resetAndPrime(history);
                long checksum = runFrames(
                    history,
                    WARMUP_FRAMES_PER_ROUND
                );
                warmupChecksums[warmup] = checksum;
                publish(checksum);
            }
            requireStableChecksums(
                path.label + " warmup",
                warmupChecksums
            );

            Samples samples = new Samples();
            RamWindow ramWindow = new RamWindow(processRssProbe);
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                resetAndPrime(history);
                ramWindow.observe();
                Measurement measurement = measure(
                    history,
                    allocationBean,
                    threadId,
                    gcProbe
                );
                samples.record(sample, measurement);
                publish(measurement.checksum());
                ramWindow.observe();
            }
            samples.requireStableChecksums(path.label);
            RamSummary ram = ramWindow.summary();

            PathResult result = new PathResult(
                path,
                requestedBytes,
                committedBytes,
                setupSnapshot.requestedBytes(MemoryKind.RAM),
                setupSnapshot.usedBytes(MemoryKind.RAM),
                setupSnapshot.usedBytes(MemoryKind.VRAM),
                warmupChecksums,
                samples,
                ram
            );

            history.close();
            historyClosed = true;
            history = null;
            EntityMotionHistory.retryPendingCleanup();
            requireReleasedFootprint(path, budgets.snapshot());

            budgets.close();
            managerClosed = true;
            requireClosedManager(path, budgets.snapshot());
            return result;
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

    private static Measurement measure(
        EntityMotionHistory history,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId,
        GcProbe gcProbe
    ) {
        GcSnapshot gcBefore = gcProbe.read();
        long allocatedBefore = allocatedBytes(
            allocationBean,
            threadId
        );
        long started = System.nanoTime();
        long checksum = runFrames(
            history,
            MEASURED_FRAMES_PER_SAMPLE
        );
        long elapsed = System.nanoTime() - started;
        long allocatedAfter = allocatedBytes(
            allocationBean,
            threadId
        );
        GcSnapshot gcAfter = gcProbe.read();
        return new Measurement(
            elapsed,
            allocationDelta(allocatedBefore, allocatedAfter),
            checksum,
            counterDelta(gcBefore.count(), gcAfter.count()),
            counterDelta(
                gcBefore.collectionMillis(),
                gcAfter.collectionMillis()
            )
        );
    }

    private static long runFrames(
        EntityMotionHistory history,
        int frames
    ) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < frames; frame++) {
            history.beginFrame();
            for (int entity = 0; entity < ENTITIES_PER_FRAME; entity++) {
                int entityId = entityId(entity);
                if (!history.findPrevious(entityId)) {
                    throw new IllegalStateException(
                        "history lost entity " + entityId
                    );
                }

                double previousX = history.previousX();
                double previousY = history.previousY();
                double previousZ = history.previousZ();
                float previousYaw = history.previousYaw();
                checksum = consumePrevious(
                    checksum,
                    entityId,
                    previousX,
                    previousY,
                    previousZ,
                    previousYaw
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
            if (history.currentSize() != ENTITIES_PER_FRAME) {
                throw new IllegalStateException(
                    "history did not retain the full high-occupancy frame"
                );
            }
            checksum = mix(checksum, history.currentSize());
        }
        return checksum;
    }

    private static void resetAndPrime(EntityMotionHistory history) {
        history.clear();
        history.beginFrame();
        for (int entity = 0; entity < ENTITIES_PER_FRAME; entity++) {
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
                    "history could not be primed to 75 percent occupancy"
                );
            }
        }
        if (history.currentSize() != ENTITIES_PER_FRAME) {
            throw new IllegalStateException(
                "history priming ended below the requested occupancy"
            );
        }
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

    private static void requireHistoryShape(
        BenchmarkPath path,
        EntityMotionHistory history
    ) {
        if (
            history.storageKind() != path.expectedKind
                || history.capacity() != CAPACITY
                || history.maxEntries() != ENTITIES_PER_FRAME
        ) {
            throw new IllegalStateException(
                path.label
                    + " did not expose the production-sized fixed shape"
            );
        }
    }

    private static void requireSetupFootprint(
        BenchmarkPath path,
        MemoryBudgetManager.Snapshot snapshot,
        long requestedBytes,
        long committedBytes
    ) {
        if (
            snapshot.requestedBytes(MemoryKind.RAM)
                    != requestedBytes
                || snapshot.usedBytes(MemoryKind.RAM)
                    != committedBytes
                || snapshot.usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.ENTITIES
                ) != committedBytes
                || snapshot.requestedBytes(MemoryKind.VRAM) != 0L
                || snapshot.usedBytes(MemoryKind.VRAM) != 0L
                || snapshot.outstanding() != 1
                || snapshot.rejections() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                path.label
                    + " setup accounting was unexpected: "
                    + snapshot
            );
        }
    }

    private static void requireReleasedFootprint(
        BenchmarkPath path,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            snapshot.requestedBytes(MemoryKind.RAM) != 0L
                || snapshot.usedBytes(MemoryKind.RAM) != 0L
                || snapshot.requestedBytes(MemoryKind.VRAM) != 0L
                || snapshot.usedBytes(MemoryKind.VRAM) != 0L
                || snapshot.outstanding() != 0
                || snapshot.rejections() != 0L
                || snapshot.evictions() != 0L
                || snapshot.deniedInFlightReleases() != 0L
                || snapshot.staleReleases() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                path.label
                    + " did not release cleanly: "
                    + snapshot
            );
        }
    }

    private static void requireClosedManager(
        BenchmarkPath path,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            !snapshot.closed()
                || snapshot.outstanding() != 0
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                path.label
                    + " budget manager did not close cleanly: "
                    + snapshot
            );
        }
    }

    private static void requireEquivalentResults(
        PathResult heap,
        PathResult nativeResult
    ) {
        if (
            heap.path() != BenchmarkPath.HEAP
                || nativeResult.path() != BenchmarkPath.NATIVE
        ) {
            throw new IllegalStateException(
                "benchmark paths were returned in the wrong order"
            );
        }
        requireEquivalentChecksums(
            "warmups",
            heap.warmupChecksums(),
            nativeResult.warmupChecksums()
        );
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long heapChecksum =
                heap.samples().measurement(sample).checksum();
            long nativeChecksum =
                nativeResult.samples().measurement(sample).checksum();
            if (heapChecksum != nativeChecksum) {
                throw new IllegalStateException(
                    "sample "
                        + (sample + 1)
                        + " checksum mismatch: "
                        + Long.toUnsignedString(heapChecksum)
                        + " != "
                        + Long.toUnsignedString(nativeChecksum)
                );
            }
        }
    }

    private static void requireEquivalentChecksums(
        String phase,
        long[] expected,
        long[] actual
    ) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException(
                phase + " checksum mismatch between heap and native"
            );
        }
    }

    private static void requireStableChecksums(
        String phase,
        long[] checksums
    ) {
        long expected = checksums[0];
        for (int index = 1; index < checksums.length; index++) {
            if (checksums[index] != expected) {
                throw new IllegalStateException(
                    phase
                        + " checksum changed at iteration "
                        + (index + 1)
                );
            }
        }
    }

    private static String createCsv(
        PathResult heap,
        PathResult nativeResult
    ) {
        StringBuilder csv = new StringBuilder(32_768);
        CsvRow header = new CsvRow(csv);
        for (String column : CSV_COLUMNS) {
            header.add(column);
        }
        header.end();
        appendPath(csv, heap);
        appendPath(csv, nativeResult);
        return csv.toString();
    }

    private static void appendPath(
        StringBuilder csv,
        PathResult result
    ) {
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            Measurement measurement =
                result.samples().measurement(sample);
            CsvRow row = prefix(
                csv,
                "sample",
                result,
                Integer.toString(sample + 1)
            );
            row.add(measurement.elapsedNanos());
            row.add(decimal(measurement.nsPerFrame()));
            row.add(decimal(measurement.nsPerEntity()));
            row.add(measurement.allocatedBytes());
            row.add(
                decimal(measurement.allocatedBytesPerFrame())
            );
            row.add(
                decimal(measurement.allocatedBytesPerEntity())
            );
            row.add(measurement.gcCountDelta());
            row.add(measurement.gcPauseMillisDelta());
            for (int column = 0; column < 14; column++) {
                row.add("");
            }
            row.add(Long.toUnsignedString(measurement.checksum()));
            appendResultTail(row, result);
            row.end();
        }

        Samples samples = result.samples();
        CsvRow summary = prefix(csv, "summary", result, "summary");
        for (int column = 0; column < 8; column++) {
            summary.add("");
        }
        summary.add(decimal(samples.p50NanosPerFrame()));
        summary.add(decimal(samples.p95NanosPerFrame()));
        summary.add(decimal(samples.p99NanosPerFrame()));
        summary.add(decimal(samples.p50NanosPerEntity()));
        summary.add(decimal(samples.p95NanosPerEntity()));
        summary.add(decimal(samples.p99NanosPerEntity()));
        summary.add(
            decimal(samples.p50AllocatedBytesPerFrame())
        );
        summary.add(
            decimal(samples.p95AllocatedBytesPerFrame())
        );
        summary.add(
            decimal(samples.p99AllocatedBytesPerFrame())
        );
        summary.add(
            decimal(samples.p50AllocatedBytesPerEntity())
        );
        summary.add(
            decimal(samples.p95AllocatedBytesPerEntity())
        );
        summary.add(
            decimal(samples.p99AllocatedBytesPerEntity())
        );
        summary.add(samples.totalGcCountDelta());
        summary.add(samples.totalGcPauseMillisDelta());
        summary.add(
            Long.toUnsignedString(samples.stableChecksum())
        );
        appendResultTail(summary, result);
        summary.end();
    }

    private static CsvRow prefix(
        StringBuilder csv,
        String rowType,
        PathResult result,
        String sample
    ) {
        CsvRow row = new CsvRow(csv);
        row.add(rowType);
        row.add(result.path().label);
        row.add(result.path().expectedKind.name());
        row.add(sample);
        row.add(CAPACITY);
        row.add(ENTITIES_PER_FRAME);
        row.add(decimal(
            ENTITIES_PER_FRAME / (double)CAPACITY
        ));
        row.add(WARMUP_ROUNDS);
        row.add(WARMUP_FRAMES_PER_ROUND);
        row.add(SAMPLE_COUNT);
        row.add(MEASURED_FRAMES_PER_SAMPLE);
        row.add(
            Math.multiplyExact(
                MEASURED_FRAMES_PER_SAMPLE,
                ENTITIES_PER_FRAME
            )
        );
        return row;
    }

    private static void appendResultTail(
        CsvRow row,
        PathResult result
    ) {
        RamSummary ram = result.ram();
        row.add(result.requestedBytes());
        row.add(result.committedBytes());
        row.add(result.budgetRequestedBytes());
        row.add(result.budgetUsedBytes());
        row.add(result.vramBytes());
        row.add(ram.before().heapUsed());
        row.add(ram.after().heapUsed());
        row.add(ram.maximum().heapUsed());
        row.add(ram.before().heapCommitted());
        row.add(ram.after().heapCommitted());
        row.add(ram.maximum().heapCommitted());
        row.add(ram.before().nonHeapUsed());
        row.add(ram.after().nonHeapUsed());
        row.add(ram.maximum().nonHeapUsed());
        row.add(ram.before().nonHeapCommitted());
        row.add(ram.after().nonHeapCommitted());
        row.add(ram.maximum().nonHeapCommitted());
        row.add(ram.before().processRss());
        row.add(ram.after().processRss());
        row.add(ram.maximum().processRss());
        row.add(ram.before().processCommittedVirtual());
        row.add(ram.after().processCommittedVirtual());
        row.add(ram.maximum().processCommittedVirtual());
        row.add(ram.processRssSource());
        row.add(RAM_SCOPE);
        row.add(WORKLOAD);
        row.add(SCOPE);
        row.add(LIMITATIONS);
    }

    private static void printSummary(PathResult result) {
        Samples samples = result.samples();
        RamSummary ram = result.ram();
        System.out.printf(
            Locale.ROOT,
            "%s: p50/p95/p99 %.3f/%.3f/%.3f ns/frame, "
                + "%.6f/%.6f/%.6f ns/entity; allocation p50 "
                + "%.3f B/frame; GC delta %d collections/%d ms; "
                + "RAM requested/committed %d/%d B; VRAM %d B; "
                + "heap used before/after/max %d/%d/%d B; "
                + "process RSS before/after/max %d/%d/%d B; "
                + "checksum %s%n",
            result.path().label,
            samples.p50NanosPerFrame(),
            samples.p95NanosPerFrame(),
            samples.p99NanosPerFrame(),
            samples.p50NanosPerEntity(),
            samples.p95NanosPerEntity(),
            samples.p99NanosPerEntity(),
            samples.p50AllocatedBytesPerFrame(),
            samples.totalGcCountDelta(),
            samples.totalGcPauseMillisDelta(),
            result.requestedBytes(),
            result.committedBytes(),
            result.vramBytes(),
            ram.before().heapUsed(),
            ram.after().heapUsed(),
            ram.maximum().heapUsed(),
            ram.before().processRss(),
            ram.after().processRss(),
            ram.maximum().processRss(),
            Long.toUnsignedString(samples.stableChecksum())
        );
    }

    private static String decimal(double value) {
        if (value < 0.0D) {
            return "-1";
        }
        return String.format(Locale.ROOT, "%.6f", value);
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

    private static long counterDelta(long before, long after) {
        if (before < 0L || after < 0L) {
            return -1L;
        }
        return Math.max(0L, after - before);
    }

    private enum BenchmarkPath {
        HEAP(
            "baseline_heap_storage",
            EntityMotionHistory.StorageKind.HEAP
        ) {
            @Override
            EntityMotionHistory create(MemoryBudgetManager budgets) {
                return EntityMotionHistory.tryCreateHeap(
                    budgets,
                    CAPACITY
                );
            }
        },
        NATIVE(
            "candidate_native_storage",
            EntityMotionHistory.StorageKind.NATIVE
        ) {
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

        BenchmarkPath(
            String label,
            EntityMotionHistory.StorageKind expectedKind
        ) {
            this.label = label;
            this.expectedKind = expectedKind;
        }

        abstract EntityMotionHistory create(
            MemoryBudgetManager budgets
        );
    }

    private record Measurement(
        long elapsedNanos,
        long allocatedBytes,
        long checksum,
        long gcCountDelta,
        long gcPauseMillisDelta
    ) {
        private double nsPerFrame() {
            return this.elapsedNanos
                / (double)MEASURED_FRAMES_PER_SAMPLE;
        }

        private double nsPerEntity() {
            return this.elapsedNanos
                / (
                    (double)MEASURED_FRAMES_PER_SAMPLE
                        * ENTITIES_PER_FRAME
                );
        }

        private double allocatedBytesPerFrame() {
            return this.allocatedBytes < 0L
                ? -1.0D
                : this.allocatedBytes
                    / (double)MEASURED_FRAMES_PER_SAMPLE;
        }

        private double allocatedBytesPerEntity() {
            return this.allocatedBytes < 0L
                ? -1.0D
                : this.allocatedBytes
                    / (
                        (double)MEASURED_FRAMES_PER_SAMPLE
                            * ENTITIES_PER_FRAME
                    );
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

        private double p50NanosPerFrame() {
            return percentile(values(Metric.NS_PER_FRAME), 0.50D);
        }

        private double p95NanosPerFrame() {
            return percentile(values(Metric.NS_PER_FRAME), 0.95D);
        }

        private double p99NanosPerFrame() {
            return percentile(values(Metric.NS_PER_FRAME), 0.99D);
        }

        private double p50NanosPerEntity() {
            return percentile(values(Metric.NS_PER_ENTITY), 0.50D);
        }

        private double p95NanosPerEntity() {
            return percentile(values(Metric.NS_PER_ENTITY), 0.95D);
        }

        private double p99NanosPerEntity() {
            return percentile(values(Metric.NS_PER_ENTITY), 0.99D);
        }

        private double p50AllocatedBytesPerFrame() {
            return allocationPercentile(
                Metric.ALLOCATED_BYTES_PER_FRAME,
                0.50D
            );
        }

        private double p95AllocatedBytesPerFrame() {
            return allocationPercentile(
                Metric.ALLOCATED_BYTES_PER_FRAME,
                0.95D
            );
        }

        private double p99AllocatedBytesPerFrame() {
            return allocationPercentile(
                Metric.ALLOCATED_BYTES_PER_FRAME,
                0.99D
            );
        }

        private double p50AllocatedBytesPerEntity() {
            return allocationPercentile(
                Metric.ALLOCATED_BYTES_PER_ENTITY,
                0.50D
            );
        }

        private double p95AllocatedBytesPerEntity() {
            return allocationPercentile(
                Metric.ALLOCATED_BYTES_PER_ENTITY,
                0.95D
            );
        }

        private double p99AllocatedBytesPerEntity() {
            return allocationPercentile(
                Metric.ALLOCATED_BYTES_PER_ENTITY,
                0.99D
            );
        }

        private long totalGcCountDelta() {
            return totalCounter(true);
        }

        private long totalGcPauseMillisDelta() {
            return totalCounter(false);
        }

        private long totalCounter(boolean count) {
            long total = 0L;
            for (Measurement measurement : this.measurements) {
                long value = count
                    ? measurement.gcCountDelta()
                    : measurement.gcPauseMillisDelta();
                if (value < 0L) {
                    return -1L;
                }
                total = Math.addExact(total, value);
            }
            return total;
        }

        private double allocationPercentile(
            Metric metric,
            double percentile
        ) {
            double[] values = values(metric);
            for (double value : values) {
                if (value < 0.0D) {
                    return -1.0D;
                }
            }
            return percentile(values, percentile);
        }

        private double[] values(Metric metric) {
            double[] values = new double[SAMPLE_COUNT];
            for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
                Measurement measurement = this.measurements[sample];
                values[sample] = switch (metric) {
                    case NS_PER_FRAME -> measurement.nsPerFrame();
                    case NS_PER_ENTITY -> measurement.nsPerEntity();
                    case ALLOCATED_BYTES_PER_FRAME ->
                        measurement.allocatedBytesPerFrame();
                    case ALLOCATED_BYTES_PER_ENTITY ->
                        measurement.allocatedBytesPerEntity();
                };
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

    private enum Metric {
        NS_PER_FRAME,
        NS_PER_ENTITY,
        ALLOCATED_BYTES_PER_FRAME,
        ALLOCATED_BYTES_PER_ENTITY
    }

    private record PathResult(
        BenchmarkPath path,
        long requestedBytes,
        long committedBytes,
        long budgetRequestedBytes,
        long budgetUsedBytes,
        long vramBytes,
        long[] warmupChecksums,
        Samples samples,
        RamSummary ram
    ) {
    }

    private record GcSnapshot(
        long count,
        long collectionMillis
    ) {
    }

    private static final class GcProbe {
        private final List<GarbageCollectorMXBean> beans;

        private GcProbe(List<GarbageCollectorMXBean> beans) {
            this.beans = List.copyOf(beans);
        }

        private GcSnapshot read() {
            long count = 0L;
            long collectionMillis = 0L;
            boolean countAvailable = true;
            boolean timeAvailable = true;
            for (GarbageCollectorMXBean bean : this.beans) {
                long beanCount = bean.getCollectionCount();
                long beanTime = bean.getCollectionTime();
                if (beanCount < 0L) {
                    countAvailable = false;
                } else if (countAvailable) {
                    count = Math.addExact(count, beanCount);
                }
                if (beanTime < 0L) {
                    timeAvailable = false;
                } else if (timeAvailable) {
                    collectionMillis = Math.addExact(
                        collectionMillis,
                        beanTime
                    );
                }
            }
            return new GcSnapshot(
                countAvailable ? count : -1L,
                timeAvailable ? collectionMillis : -1L
            );
        }
    }

    private record RamState(
        long heapUsed,
        long heapCommitted,
        long nonHeapUsed,
        long nonHeapCommitted,
        long processRss,
        long processCommittedVirtual
    ) {
        private static RamState maximum(
            RamState left,
            RamState right
        ) {
            return new RamState(
                maxKnown(left.heapUsed, right.heapUsed),
                maxKnown(left.heapCommitted, right.heapCommitted),
                maxKnown(left.nonHeapUsed, right.nonHeapUsed),
                maxKnown(
                    left.nonHeapCommitted,
                    right.nonHeapCommitted
                ),
                maxKnown(left.processRss, right.processRss),
                maxKnown(
                    left.processCommittedVirtual,
                    right.processCommittedVirtual
                )
            );
        }

        private static long maxKnown(long left, long right) {
            if (left < 0L) {
                return right;
            }
            if (right < 0L) {
                return left;
            }
            return Math.max(left, right);
        }
    }

    private record RamSummary(
        RamState before,
        RamState after,
        RamState maximum,
        String processRssSource
    ) {
    }

    private static final class RamWindow {
        private final MemoryMXBean memoryBean =
            ManagementFactory.getMemoryMXBean();
        private final com.sun.management.OperatingSystemMXBean osBean;
        private final ProcessRssProbe processRssProbe;
        private RamState before;
        private RamState after;
        private RamState maximum;

        private RamWindow(ProcessRssProbe processRssProbe) {
            java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
            this.osBean =
                bean
                    instanceof com.sun.management.OperatingSystemMXBean
                        sunBean
                    ? sunBean
                    : null;
            this.processRssProbe = processRssProbe;
        }

        private void observe() {
            MemoryUsage heap = this.memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeap =
                this.memoryBean.getNonHeapMemoryUsage();
            RamState observed = new RamState(
                heap.getUsed(),
                heap.getCommitted(),
                nonHeap.getUsed(),
                nonHeap.getCommitted(),
                this.processRssProbe.read(),
                this.osBean == null
                    ? -1L
                    : this.osBean.getCommittedVirtualMemorySize()
            );
            if (this.before == null) {
                this.before = observed;
                this.maximum = observed;
            } else {
                this.maximum = RamState.maximum(
                    this.maximum,
                    observed
                );
            }
            this.after = observed;
        }

        private RamSummary summary() {
            if (
                this.before == null
                    || this.after == null
                    || this.maximum == null
            ) {
                throw new IllegalStateException(
                    "RAM observation window is empty"
                );
            }
            return new RamSummary(
                this.before,
                this.after,
                this.maximum,
                this.processRssProbe.source()
            );
        }
    }

    private static final class ProcessRssProbe
        implements AutoCloseable {
        private static final long WINDOWS_COUNTERS_BYTES = 72L;
        private static final long WINDOWS_WORKING_SET_OFFSET = 16L;

        private final Arena windowsArena;
        private final MethodHandle getCurrentProcess;
        private final MethodHandle getProcessMemoryInfo;
        private final MemorySegment windowsCounters;
        private final Object operatingSystem;
        private final Method getProcess;
        private final Method getResidentSetSize;
        private final String availableSource;
        private boolean readFailed;

        private ProcessRssProbe(
            Arena windowsArena,
            MethodHandle getCurrentProcess,
            MethodHandle getProcessMemoryInfo,
            MemorySegment windowsCounters,
            Object operatingSystem,
            Method getProcess,
            Method getResidentSetSize,
            String availableSource
        ) {
            this.windowsArena = windowsArena;
            this.getCurrentProcess = getCurrentProcess;
            this.getProcessMemoryInfo = getProcessMemoryInfo;
            this.windowsCounters = windowsCounters;
            this.operatingSystem = operatingSystem;
            this.getProcess = getProcess;
            this.getResidentSetSize = getResidentSetSize;
            this.availableSource = availableSource;
        }

        private static ProcessRssProbe create() {
            ProcessRssProbe windows = tryCreateWindowsFfm();
            if (windows != null) {
                return windows;
            }
            try {
                Class<?> systemInfoClass =
                    Class.forName("oshi.SystemInfo");
                Object systemInfo =
                    systemInfoClass.getConstructor().newInstance();
                Object operatingSystem = systemInfoClass
                    .getMethod("getOperatingSystem")
                    .invoke(systemInfo);
                Method getProcess = operatingSystem
                    .getClass()
                    .getMethod("getProcess", int.class);
                Method getResidentSetSize = Class
                    .forName("oshi.software.os.OSProcess")
                    .getMethod("getResidentSetSize");
                return new ProcessRssProbe(
                    null,
                    null,
                    null,
                    null,
                    operatingSystem,
                    getProcess,
                    getResidentSetSize,
                    "oshi_current_process_resident_set_size_"
                        + "sample_boundaries"
                );
            } catch (
                ReflectiveOperationException
                    | LinkageError
                    | RuntimeException unavailable
            ) {
                return new ProcessRssProbe(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "unavailable"
                );
            }
        }

        private static ProcessRssProbe tryCreateWindowsFfm() {
            if (
                !System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT)
                    .startsWith("windows")
            ) {
                return null;
            }
            Arena arena = null;
            try {
                arena = Arena.ofConfined();
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup(
                    "kernel32",
                    arena
                );
                MemorySegment currentProcessSymbol = kernel32
                    .find("GetCurrentProcess")
                    .orElseThrow();
                MemorySegment memoryInfoSymbol = kernel32
                    .find("K32GetProcessMemoryInfo")
                    .orElseThrow();
                MethodHandle getCurrentProcess =
                    linker.downcallHandle(
                        currentProcessSymbol,
                        FunctionDescriptor.of(ValueLayout.ADDRESS)
                    );
                MethodHandle getProcessMemoryInfo =
                    linker.downcallHandle(
                        memoryInfoSymbol,
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT
                        )
                    );
                MemorySegment counters = arena.allocate(
                    WINDOWS_COUNTERS_BYTES,
                    Long.BYTES
                );
                ProcessRssProbe probe = new ProcessRssProbe(
                    arena,
                    getCurrentProcess,
                    getProcessMemoryInfo,
                    counters,
                    null,
                    null,
                    null,
                    "windows_k32_get_process_memory_info_working_set_"
                        + "sample_boundaries"
                );
                if (probe.readWindows() < 0L) {
                    probe.close();
                    return null;
                }
                return probe;
            } catch (Throwable unavailable) {
                if (arena != null) {
                    arena.close();
                }
                return null;
            }
        }

        private long read() {
            if (this.windowsArena != null) {
                long residentSetSize = this.readWindows();
                if (residentSetSize < 0L) {
                    this.readFailed = true;
                }
                return residentSetSize;
            }
            if (
                this.operatingSystem == null
                    || this.getProcess == null
                    || this.getResidentSetSize == null
            ) {
                return -1L;
            }
            try {
                int pid = Math.toIntExact(
                    ProcessHandle.current().pid()
                );
                Object process = this.getProcess.invoke(
                    this.operatingSystem,
                    pid
                );
                if (process == null) {
                    this.readFailed = true;
                    return -1L;
                }
                long residentSetSize = (
                    (Number)this.getResidentSetSize.invoke(process)
                ).longValue();
                if (residentSetSize < 0L) {
                    this.readFailed = true;
                    return -1L;
                }
                return residentSetSize;
            } catch (
                ReflectiveOperationException
                    | RuntimeException unavailable
            ) {
                this.readFailed = true;
                return -1L;
            }
        }

        private long readWindows() {
            try {
                this.windowsCounters.fill((byte)0);
                this.windowsCounters.set(
                    ValueLayout.JAVA_INT,
                    0L,
                    Math.toIntExact(WINDOWS_COUNTERS_BYTES)
                );
                MemorySegment process = (MemorySegment)
                    this.getCurrentProcess.invoke();
                int success = (int)this.getProcessMemoryInfo.invoke(
                    process,
                    this.windowsCounters,
                    Math.toIntExact(WINDOWS_COUNTERS_BYTES)
                );
                if (success == 0) {
                    return -1L;
                }
                return this.windowsCounters.get(
                    ValueLayout.JAVA_LONG,
                    WINDOWS_WORKING_SET_OFFSET
                );
            } catch (Throwable unavailable) {
                return -1L;
            }
        }

        private String source() {
            if (this.readFailed) {
                return this.availableSource + "_partial_or_failed";
            }
            return this.availableSource;
        }

        @Override
        public void close() {
            if (this.windowsArena != null) {
                this.windowsArena.close();
            }
        }
    }

    private static final class CsvRow {
        private final StringBuilder target;
        private int columns;

        private CsvRow(StringBuilder target) {
            this.target = target;
        }

        private CsvRow add(Object value) {
            if (this.columns > 0) {
                this.target.append(',');
            }
            appendEscaped(this.target, String.valueOf(value));
            this.columns++;
            return this;
        }

        private void end() {
            if (this.columns != CSV_COLUMNS.length) {
                throw new IllegalStateException(
                    "CSV row has "
                        + this.columns
                        + " columns; expected "
                        + CSV_COLUMNS.length
                );
            }
            this.target.append('\n');
        }

        private static void appendEscaped(
            StringBuilder target,
            String value
        ) {
            boolean quoted =
                value.indexOf(',') >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0;
            if (!quoted) {
                target.append(value);
                return;
            }
            target.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '"') {
                    target.append('"');
                }
                target.append(character);
            }
            target.append('"');
        }
    }
}
