package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.core.scheduling.FrameBudgetController;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded platform-thread workers for independent section compile jobs.
 *
 * <p>Each worker owns three priority deques and may steal only complete jobs
 * from another worker. No Vulkan owner is exposed through this API. Workers
 * block on a semaphore when idle; no busy waiting, affinity or OS priority
 * manipulation is used.</p>
 */
public final class NativeTerrainJobSystem implements AutoCloseable {
    public enum Priority {
        VISIBLE,
        NEAR,
        FAR
    }

    @FunctionalInterface
    public interface GenerationValidity {
        boolean current();
    }

    public record Topology(
        int logicalProcessors,
        int physicalCores,
        boolean physicalCoreCountKnown
    ) {
        public Topology {
            if (
                logicalProcessors <= 0
                    || physicalCores <= 0
                    || physicalCores > logicalProcessors
                    || (
                        !physicalCoreCountKnown
                            && physicalCores
                                != conservativePhysicalEstimate(
                                    logicalProcessors
                                )
                    )
            ) {
                throw new IllegalArgumentException(
                    "invalid CPU topology"
                );
            }
        }

        public static Topology conservativeRuntimeTopology() {
            int logical = Runtime.getRuntime().availableProcessors();
            return new Topology(
                logical,
                conservativePhysicalEstimate(logical),
                false
            );
        }

        private static int conservativePhysicalEstimate(int logical) {
            return Math.max(1, (logical + 1) / 2);
        }
    }

    public record Job(
        Priority priority,
        int squaredSectionDistance,
        GenerationValidity generation,
        Runnable compile
    ) {
        public Job {
            Objects.requireNonNull(priority, "priority");
            if (squaredSectionDistance < 0) {
                throw new IllegalArgumentException(
                    "squaredSectionDistance must not be negative"
                );
            }
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(compile, "compile");
        }
    }

    public record Snapshot(
        int physicalWorkerLimit,
        int logicalWorkerLimit,
        int activeWorkerLimit,
        int queuedJobs,
        int runningJobs,
        long completedJobs,
        long cancelledJobs,
        long rejectedJobs,
        long steals,
        boolean closed
    ) {
    }

    private static final int RESERVED_FOREGROUND_CORES = 2;

    private final int queueCapacity;
    private final int physicalWorkerLimit;
    private final int logicalWorkerLimit;
    private final Worker[] workers;
    private final Semaphore queuedSignal = new Semaphore(0);
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong steals = new AtomicLong();
    private final Object activationMonitor = new Object();
    private volatile int activeWorkerLimit;
    private volatile boolean closed;
    private int nextTarget;

    public NativeTerrainJobSystem(
        Topology topology,
        int queueCapacity
    ) {
        Objects.requireNonNull(topology, "topology");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                "queueCapacity must be positive"
            );
        }
        this.queueCapacity = queueCapacity;
        int reservedPhysical = Math.max(
            1,
            topology.physicalCores() - RESERVED_FOREGROUND_CORES
        );
        this.physicalWorkerLimit = reservedPhysical;
        this.logicalWorkerLimit = topology.physicalCoreCountKnown()
            ? Math.max(
                reservedPhysical,
                topology.logicalProcessors()
                    - RESERVED_FOREGROUND_CORES
            )
            : reservedPhysical;
        this.workers = new Worker[this.logicalWorkerLimit];
        int localCapacity = Math.max(
            1,
            (queueCapacity + this.workers.length - 1)
                / this.workers.length
        );
        for (int index = 0; index < this.workers.length; index++) {
            this.workers[index] = new Worker(index, localCapacity);
        }
        this.activeWorkerLimit = Math.min(
            1,
            this.physicalWorkerLimit
        );
        for (Worker worker : this.workers) {
            worker.thread.start();
        }
    }

    public int physicalWorkerLimit() {
        return this.physicalWorkerLimit;
    }

    public int logicalWorkerLimit() {
        return this.logicalWorkerLimit;
    }

    public boolean submit(Job job) {
        Objects.requireNonNull(job, "job");
        if (this.closed || !job.generation().current()) {
            this.cancelled.incrementAndGet();
            return false;
        }
        while (true) {
            int current = this.queued.get();
            if (current >= this.queueCapacity) {
                this.rejected.incrementAndGet();
                return false;
            }
            if (this.queued.compareAndSet(current, current + 1)) {
                break;
            }
        }

        Worker target = selectTarget(job.priority());
        if (!target.offer(job)) {
            this.queued.decrementAndGet();
            this.rejected.incrementAndGet();
            return false;
        }
        this.queuedSignal.release();
        return true;
    }

    public void applyBudget(
        FrameBudgetController.Decision decision
    ) {
        Objects.requireNonNull(decision, "decision");
        int requested = decision.smtWorkersAllowed()
            ? decision.activeCompilerWorkers()
            : Math.min(
                decision.activeCompilerWorkers(),
                this.physicalWorkerLimit
            );
        this.activeWorkerLimit = Math.clamp(
            requested,
            0,
            this.logicalWorkerLimit
        );
        synchronized (this.activationMonitor) {
            this.activationMonitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.physicalWorkerLimit,
            this.logicalWorkerLimit,
            this.activeWorkerLimit,
            this.queued.get(),
            this.running.get(),
            this.completed.get(),
            this.cancelled.get(),
            this.rejected.get(),
            this.steals.get(),
            this.closed
        );
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        synchronized (this.activationMonitor) {
            this.activationMonitor.notifyAll();
        }
        this.queuedSignal.release(this.workers.length);
        for (Worker worker : this.workers) {
            worker.thread.interrupt();
        }
        boolean interrupted = false;
        for (Worker worker : this.workers) {
            while (worker.thread.isAlive()) {
                try {
                    worker.thread.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            Job discarded;
            while ((discarded = worker.pollLocal()) != null) {
                this.queued.decrementAndGet();
                this.cancelled.incrementAndGet();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized Worker selectTarget(Priority priority) {
        Worker selected = this.workers[this.nextTarget];
        int selectedSize = selected.size(priority);
        for (int offset = 1; offset < this.workers.length; offset++) {
            int index = (this.nextTarget + offset)
                % this.workers.length;
            Worker candidate = this.workers[index];
            int size = candidate.size(priority);
            if (size < selectedSize) {
                selected = candidate;
                selectedSize = size;
            }
        }
        this.nextTarget = (selected.index + 1) % this.workers.length;
        return selected;
    }

    private Job steal(int thief) {
        for (Priority priority : Priority.values()) {
            for (int offset = 1; offset < this.workers.length; offset++) {
                Worker victim = this.workers[
                    (thief + offset) % this.workers.length
                ];
                Job job = victim.pollLast(priority);
                if (job != null) {
                    this.steals.incrementAndGet();
                    return job;
                }
            }
        }
        return null;
    }

    private final class Worker implements Runnable {
        private final int index;
        private final LinkedBlockingDeque<Job>[] queues;
        private final Thread thread;

        @SuppressWarnings("unchecked")
        private Worker(int index, int localCapacity) {
            this.index = index;
            this.queues = new LinkedBlockingDeque[Priority.values().length];
            for (int priority = 0; priority < this.queues.length; priority++) {
                this.queues[priority] =
                    new LinkedBlockingDeque<>(localCapacity);
            }
            this.thread = new Thread(
                this,
                "BlockFrame-Terrain-Compiler-" + index
            );
            this.thread.setDaemon(true);
        }

        private boolean offer(Job job) {
            return this.queues[job.priority().ordinal()].offerLast(job);
        }

        private int size(Priority priority) {
            return this.queues[priority.ordinal()].size();
        }

        private Job pollLocal() {
            for (Priority priority : Priority.values()) {
                Job job = this.queues[priority.ordinal()].pollFirst();
                if (job != null) {
                    return job;
                }
            }
            return null;
        }

        private Job pollLast(Priority priority) {
            return this.queues[priority.ordinal()].pollLast();
        }

        @Override
        public void run() {
            try {
                while (!NativeTerrainJobSystem.this.closed) {
                    awaitActivation();
                    if (NativeTerrainJobSystem.this.closed) {
                        return;
                    }
                    NativeTerrainJobSystem.this.queuedSignal.acquire();
                    if (NativeTerrainJobSystem.this.closed) {
                        return;
                    }
                    Job job = this.pollLocal();
                    if (job == null) {
                        job = NativeTerrainJobSystem.this.steal(this.index);
                    }
                    if (job == null) {
                        /*
                         * Another active worker can steal the local job
                         * after this worker acquired a global signal. The
                         * stealing worker consumed its own signal, so this
                         * empty acquire must return one permit while queued
                         * work remains. Otherwise enough steals can strand
                         * jobs in bounded deques with no semaphore permit.
                         */
                        if (
                            NativeTerrainJobSystem.this.queued.get() > 0
                        ) {
                            NativeTerrainJobSystem.this.queuedSignal
                                .release();
                        }
                        continue;
                    }
                    NativeTerrainJobSystem.this.queued.decrementAndGet();
                    if (!job.generation().current()) {
                        NativeTerrainJobSystem.this.cancelled
                            .incrementAndGet();
                        continue;
                    }
                    NativeTerrainJobSystem.this.running.incrementAndGet();
                    try {
                        job.compile().run();
                        NativeTerrainJobSystem.this.completed
                            .incrementAndGet();
                    } catch (RuntimeException | LinkageError error) {
                        NativeTerrainJobSystem.this.cancelled
                            .incrementAndGet();
                    } finally {
                        NativeTerrainJobSystem.this.running
                            .decrementAndGet();
                    }
                }
            } catch (InterruptedException error) {
                if (!NativeTerrainJobSystem.this.closed) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void awaitActivation() throws InterruptedException {
            synchronized (NativeTerrainJobSystem.this.activationMonitor) {
                while (
                    !NativeTerrainJobSystem.this.closed
                        && this.index
                            >= NativeTerrainJobSystem.this.activeWorkerLimit
                ) {
                    NativeTerrainJobSystem.this.activationMonitor.wait();
                }
            }
        }
    }
}
