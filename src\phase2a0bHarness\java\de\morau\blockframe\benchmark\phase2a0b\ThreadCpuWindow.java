package de.morau.blockframe.benchmark.phase2a0b;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Two-boundary ThreadMXBean measurement. Thread discovery and names occur
 * before and after MEASURE; there is no per-frame sampling API or sampler
 * thread.
 */
public final class ThreadCpuWindow implements AutoCloseable {
    public interface Access {
        boolean cpuTimeSupported();

        boolean cpuTimeEnabled();

        void cpuTimeEnabled(boolean enabled);

        long[] allThreadIds();

        Descriptor descriptor(long threadId);

        long cpuTime(long threadId);

        long userTime(long threadId);
    }

    public record Descriptor(String name, Thread.State state) {
    }

    public record Result(
        boolean enabled,
        String status,
        long wallNanos,
        long totalCpuNanos,
        long totalUserNanos,
        long renderCpuNanos,
        long serverCpuNanos,
        long mojangWorkerCpuNanos,
        long vulkanSubmissionCpuNanos,
        long blockframeWorkerCpuNanos,
        long fileIoCpuNanos,
        long workerCpuNanos,
        long gcCpuNanos,
        long jitCpuNanos,
        long unknownCpuNanos,
        long otherCpuNanos,
        int boundarySnapshotCount,
        Phase2a0bResultSchema.NumericValue renderShare,
        Phase2a0bResultSchema.NumericValue serverShare,
        Phase2a0bResultSchema.NumericValue averageUtilizedCores,
        Phase2a0bResultSchema.NumericValue normalizedPhysicalCoreLoad,
        boolean normalizedPhysicalCoreLoadAvailable,
        long largestWorkerImbalanceNanos,
        boolean workerImbalanceAvailable,
        int bornThreads,
        int endedThreads,
        boolean threadSetChanged,
        int parkedOrIdleAtStart,
        int parkedOrIdleAtEnd,
        String queueBacklogs,
        String contextSwitches,
        String cpuMigrations,
        String numaMigrations,
        String exactWaitDurations,
        long[] categoryCpuNanos,
        long[] threadIds,
        String[] threadNames,
        ThreadCategory[] threadCategories,
        String[] perThreadStatus
    ) {
    }

    private final Access access;
    private final boolean requested;
    private long[] threadIds = new long[0];
    private String[] names = new String[0];
    private ThreadCategory[] categories = new ThreadCategory[0];
    private byte[] startStates = new byte[0];
    private byte[] endStates = new byte[0];
    private long[] startCpu = new long[0];
    private long[] endCpu = new long[0];
    private long[] startUser = new long[0];
    private long[] endUser = new long[0];
    private String[] perThreadStatus = new String[0];
    private boolean prepared;
    private boolean started;
    private boolean previousEnabled;
    private boolean changedEnabled;
    private boolean cpuTimeUsable;
    private boolean threadDiscoveryUsable = true;
    private String diagnosticStatus = "AVAILABLE";
    private long startWallNanos;
    private int boundarySnapshotCount;

    public ThreadCpuWindow(boolean requested) {
        this(new ManagementAccess(), requested);
    }

    public ThreadCpuWindow(Access access, boolean requested) {
        this.access = Objects.requireNonNull(access, "access");
        this.requested = requested;
    }

    /**
     * Discovers and classifies the stable slots before MEASURE.
     */
    public void prepare() {
        if (this.started) {
            throw new IllegalStateException("measurement already started");
        }
        if (!this.requested) {
            this.boundarySnapshotCount = 0;
            this.prepared = true;
            return;
        }
        this.boundarySnapshotCount = 0;
        long[] discovered;
        try {
            discovered = this.access.allThreadIds();
        } catch (RuntimeException error) {
            this.threadDiscoveryUsable = false;
            this.diagnosticStatus =
                "ERROR: thread-discovery-before-measure";
            this.prepared = true;
            return;
        }
        Arrays.sort(discovered);
        this.threadIds = discovered;
        int count = discovered.length;
        this.names = new String[count];
        this.categories = new ThreadCategory[count];
        this.startStates = new byte[count];
        this.endStates = new byte[count];
        this.startCpu = filled(count, -1L);
        this.endCpu = filled(count, -1L);
        this.startUser = filled(count, -1L);
        this.endUser = filled(count, -1L);
        this.perThreadStatus = new String[count];
        for (int index = 0; index < count; index++) {
            Descriptor descriptor = safeDescriptor(discovered[index]);
            if (descriptor == null) {
                this.names[index] = "NOT_AVAILABLE";
                this.categories[index] = ThreadCategory.UNKNOWN;
                this.startStates[index] = -1;
                this.perThreadStatus[index] =
                    "NOT_AVAILABLE: descriptor-before-measure";
            } else {
                this.names[index] = descriptor.name();
                this.categories[index] = classify(descriptor.name());
                this.startStates[index] = stateCode(descriptor.state());
                this.perThreadStatus[index] = "AVAILABLE";
            }
        }
        this.prepared = true;
    }

    /**
     * Takes the first of exactly two CPU-time snapshots.
     */
    public void begin() {
        if (!this.prepared) {
            throw new IllegalStateException("prepare must run before begin");
        }
        if (this.started) {
            throw new IllegalStateException("measurement already started");
        }
        this.started = true;
        this.startWallNanos = System.nanoTime();
        if (!this.requested || !this.threadDiscoveryUsable) {
            return;
        }
        try {
            if (!this.access.cpuTimeSupported()) {
                return;
            }
            this.previousEnabled = this.access.cpuTimeEnabled();
            if (!this.previousEnabled) {
                this.access.cpuTimeEnabled(true);
                this.changedEnabled = true;
            }
            this.cpuTimeUsable = this.access.cpuTimeEnabled();
        } catch (RuntimeException error) {
            this.cpuTimeUsable = false;
        }
        if (this.cpuTimeUsable) {
            captureCpu(this.startCpu, this.startUser, "start");
            this.boundarySnapshotCount++;
        }
    }

    /**
     * Takes the second boundary snapshot and performs all result allocation
     * after MEASURE.
     */
    public Result end(int physicalCoreCount) {
        if (!this.started) {
            throw new IllegalStateException("begin must run before end");
        }
        long endWallNanos = System.nanoTime();
        if (this.cpuTimeUsable) {
            captureCpu(this.endCpu, this.endUser, "end");
            this.boundarySnapshotCount++;
        }
        int ended = 0;
        int idleEnd = 0;
        for (int index = 0; index < this.threadIds.length; index++) {
            Descriptor descriptor = safeDescriptor(this.threadIds[index]);
            if (descriptor == null) {
                this.endStates[index] = -1;
                ended++;
            } else {
                this.endStates[index] = stateCode(descriptor.state());
                if (isParkedOrIdle(descriptor.state())) {
                    idleEnd++;
                }
            }
        }
        long[] finalIds = this.requested && this.threadDiscoveryUsable
            ? safeAllThreadIds()
            : new long[0];
        Arrays.sort(finalIds);
        int born = countMissing(finalIds, this.threadIds);
        long wall = Math.max(0L, endWallNanos - this.startWallNanos);
        long[] categoryTotals = new long[ThreadCategory.values().length];
        long totalCpu = 0L;
        long totalUser = 0L;
        int idleStart = 0;
        long workerMin = Long.MAX_VALUE;
        long workerMax = Long.MIN_VALUE;
        int workerSamples = 0;
        for (int index = 0; index < this.threadIds.length; index++) {
            long cpu = delta(this.startCpu[index], this.endCpu[index]);
            long user = delta(this.startUser[index], this.endUser[index]);
            if (cpu >= 0L) {
                totalCpu += cpu;
                categoryTotals[this.categories[index].ordinal()] += cpu;
                if (
                    this.categories[index] == ThreadCategory.MOJANG_WORKER
                        || this.categories[index]
                            == ThreadCategory.BLOCKFRAME_WORKER
                ) {
                    workerMin = Math.min(workerMin, cpu);
                    workerMax = Math.max(workerMax, cpu);
                    workerSamples++;
                }
            }
            if (user >= 0L) {
                totalUser += user;
            }
            if (isParkedOrIdle(state(this.startStates[index]))) {
                idleStart++;
            }
        }
        restoreEnabledState();
        this.started = false;
        boolean available =
            this.requested
                && this.threadDiscoveryUsable
                && this.cpuTimeUsable;
        Phase2a0bResultSchema.NumericValue averageCores =
            Phase2a0bResultSchema.NumericValue.optional(
                available && wall > 0L
                    ? (double)totalCpu / (double)wall
                    : Double.NaN,
                Phase2a0bResultSchema.NumericStatus.NOT_AVAILABLE,
                "CPU_WINDOW_OR_WALL_UNAVAILABLE"
            );
        boolean normalizedAvailable =
            available && wall > 0L && physicalCoreCount > 0;
        Phase2a0bResultSchema.NumericValue normalized =
            Phase2a0bResultSchema.NumericValue.optional(
                normalizedAvailable
                    ? (double)totalCpu
                        / ((double)wall * physicalCoreCount)
                    : Double.NaN,
                Phase2a0bResultSchema.NumericStatus.NOT_AVAILABLE,
                "CPU_WINDOW_WALL_OR_CORE_COUNT_UNAVAILABLE"
            );
        long render = categoryTotals[ThreadCategory.RENDER.ordinal()]
            + categoryTotals[ThreadCategory.CLIENT_MAIN.ordinal()];
        long server =
            categoryTotals[ThreadCategory.INTEGRATED_SERVER.ordinal()];
        return new Result(
            available,
            status(),
            wall,
            available ? totalCpu : -1L,
            available ? totalUser : -1L,
            available ? render : -1L,
            available ? server : -1L,
            available
                ? categoryTotals[ThreadCategory.MOJANG_WORKER.ordinal()]
                : -1L,
            available
                ? categoryTotals[
                    ThreadCategory.VULKAN_SUBMISSION.ordinal()
                ]
                : -1L,
            available
                ? categoryTotals[ThreadCategory.BLOCKFRAME_WORKER.ordinal()]
                : -1L,
            available
                ? categoryTotals[ThreadCategory.FILE_IO.ordinal()]
                : -1L,
            available
                ? categoryTotals[ThreadCategory.MOJANG_WORKER.ordinal()]
                    + categoryTotals[
                        ThreadCategory.BLOCKFRAME_WORKER.ordinal()
                    ]
                : -1L,
            available ? categoryTotals[ThreadCategory.GC.ordinal()] : -1L,
            available ? categoryTotals[ThreadCategory.JIT.ordinal()] : -1L,
            available
                ? categoryTotals[ThreadCategory.UNKNOWN.ordinal()]
                : -1L,
            available ? otherCpu(categoryTotals) : -1L,
            this.boundarySnapshotCount,
            share(render, totalCpu, available),
            share(server, totalCpu, available),
            averageCores,
            normalized,
            normalizedAvailable,
            workerSamples >= 2 ? workerMax - workerMin : -1L,
            available && workerSamples >= 2,
            born,
            ended,
            born != 0 || ended != 0,
            idleStart,
            idleEnd,
            "NOT_AVAILABLE: no existing read-only queue source",
            "NOT_AVAILABLE: no non-intrusive JVM counter",
            "NOT_AVAILABLE: no non-intrusive JVM counter",
            "NOT_AVAILABLE: no non-intrusive JVM counter",
            "NOT_AVAILABLE: boundary states are not durations",
            categoryTotals,
            Arrays.copyOf(this.threadIds, this.threadIds.length),
            Arrays.copyOf(this.names, this.names.length),
            Arrays.copyOf(this.categories, this.categories.length),
            Arrays.copyOf(
                this.perThreadStatus,
                this.perThreadStatus.length
            )
        );
    }

    public int stableSlotCount() {
        return this.threadIds.length;
    }

    public static ThreadCategory classify(String threadName) {
        if (threadName == null || threadName.isBlank()) {
            return ThreadCategory.UNKNOWN;
        }
        String name = threadName.toLowerCase(Locale.ROOT);
        if (name.equals("render thread") || name.contains("renderthread")) {
            return ThreadCategory.RENDER;
        }
        if (
            name.equals("client thread")
                || name.equals("main")
                || name.equals("minecraft main thread")
        ) {
            return ThreadCategory.CLIENT_MAIN;
        }
        if (name.contains("server thread")) {
            return ThreadCategory.INTEGRATED_SERVER;
        }
        if (
            name.contains("blockframe")
                && (name.contains("worker") || name.contains("job"))
        ) {
            return ThreadCategory.BLOCKFRAME_WORKER;
        }
        if (
            name.contains("vulkan")
                || name.contains("submission")
                || name.contains("present thread")
        ) {
            return ThreadCategory.VULKAN_SUBMISSION;
        }
        if (
            name.contains("worker-main")
                || name.contains("chunk")
                || name.contains("mesher")
                || name.contains("render worker")
                || name.contains("forkjoinpool")
        ) {
            return ThreadCategory.MOJANG_WORKER;
        }
        if (
            name.contains("file")
                || name.contains("io-worker")
                || name.contains("download")
                || name.contains("resource reload")
        ) {
            return ThreadCategory.FILE_IO;
        }
        if (
            name.contains("gc thread")
                || name.contains("g1 ")
                || name.contains("zgc")
        ) {
            return ThreadCategory.GC;
        }
        if (
            name.contains("compilerthread")
                || name.contains("c1 compiler")
                || name.contains("c2 compiler")
        ) {
            return ThreadCategory.JIT;
        }
        return ThreadCategory.OTHER;
    }

    @Override
    public void close() {
        restoreEnabledState();
        this.started = false;
    }

    private void captureCpu(long[] cpu, long[] user, String boundary) {
        for (int index = 0; index < this.threadIds.length; index++) {
            try {
                cpu[index] = this.access.cpuTime(this.threadIds[index]);
            } catch (RuntimeException error) {
                cpu[index] = -1L;
                this.perThreadStatus[index] =
                    "ERROR: cpu-time-" + boundary;
            }
            try {
                user[index] = this.access.userTime(this.threadIds[index]);
            } catch (RuntimeException error) {
                user[index] = -1L;
                if ("AVAILABLE".equals(this.perThreadStatus[index])) {
                    this.perThreadStatus[index] =
                        "ERROR: user-time-" + boundary;
                }
            }
        }
    }

    private void restoreEnabledState() {
        if (!this.changedEnabled) {
            return;
        }
        this.changedEnabled = false;
        try {
            this.access.cpuTimeEnabled(this.previousEnabled);
        } catch (RuntimeException ignored) {
            // Diagnostics cannot affect Minecraft or renderer lifetime.
        }
    }

    private Descriptor safeDescriptor(long id) {
        try {
            return this.access.descriptor(id);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private long[] safeAllThreadIds() {
        try {
            return this.access.allThreadIds();
        } catch (RuntimeException error) {
            return new long[0];
        }
    }

    private String status() {
        if (!this.requested) {
            return "NOT_AVAILABLE: cpu measurement disabled";
        }
        if (!this.threadDiscoveryUsable) {
            return this.diagnosticStatus;
        }
        if (!this.cpuTimeUsable) {
            return "NOT_AVAILABLE: ThreadMXBean CPU time unsupported or not activatable";
        }
        return "AVAILABLE: two boundary snapshots";
    }

    private static long[] filled(int count, long value) {
        long[] values = new long[count];
        Arrays.fill(values, value);
        return values;
    }

    private static long delta(long start, long end) {
        return start < 0L || end < 0L ? -1L : Math.max(0L, end - start);
    }

    private static int countMissing(long[] candidates, long[] known) {
        int count = 0;
        for (long candidate : candidates) {
            if (Arrays.binarySearch(known, candidate) < 0) {
                count++;
            }
        }
        return count;
    }

    private static byte stateCode(Thread.State state) {
        return state == null ? -1 : (byte)state.ordinal();
    }

    private static Thread.State state(byte code) {
        Thread.State[] values = Thread.State.values();
        return code < 0 || code >= values.length ? null : values[code];
    }

    private static boolean isParkedOrIdle(Thread.State state) {
        return state == Thread.State.WAITING
            || state == Thread.State.TIMED_WAITING;
    }

    private static Phase2a0bResultSchema.NumericValue share(
        long category,
        long total,
        boolean available
    ) {
        return Phase2a0bResultSchema.NumericValue.optional(
            available && total > 0L
                ? (double)category / (double)total
                : Double.NaN,
            Phase2a0bResultSchema.NumericStatus.NOT_AVAILABLE,
            "CPU_TOTAL_UNAVAILABLE_OR_ZERO"
        );
    }

    private static long otherCpu(long[] totals) {
        return totals[ThreadCategory.VULKAN_SUBMISSION.ordinal()]
            + totals[ThreadCategory.FILE_IO.ordinal()]
            + totals[ThreadCategory.OTHER.ordinal()]
            + totals[ThreadCategory.UNKNOWN.ordinal()];
    }

    private static final class ManagementAccess implements Access {
        private final java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();

        @Override
        public boolean cpuTimeSupported() {
            return this.bean.isThreadCpuTimeSupported();
        }

        @Override
        public boolean cpuTimeEnabled() {
            return this.bean.isThreadCpuTimeEnabled();
        }

        @Override
        public void cpuTimeEnabled(boolean enabled) {
            this.bean.setThreadCpuTimeEnabled(enabled);
        }

        @Override
        public long[] allThreadIds() {
            return this.bean.getAllThreadIds();
        }

        @Override
        public Descriptor descriptor(long threadId) {
            ThreadInfo info = this.bean.getThreadInfo(threadId, 0);
            return info == null
                ? null
                : new Descriptor(info.getThreadName(), info.getThreadState());
        }

        @Override
        public long cpuTime(long threadId) {
            return this.bean.getThreadCpuTime(threadId);
        }

        @Override
        public long userTime(long threadId) {
            return this.bean.getThreadUserTime(threadId);
        }
    }
}
