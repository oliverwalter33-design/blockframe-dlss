package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.scheduling.FrameBudgetController;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NativeTerrainJobSystemTest {
    @Test
    void queueIsBoundedAndStaleGenerationsNeverRun() throws Exception {
        try (
            NativeTerrainJobSystem jobs =
                new NativeTerrainJobSystem(
                    new NativeTerrainJobSystem.Topology(8, 4, true),
                    2
                )
        ) {
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            assertTrue(
                jobs.submit(
                    new NativeTerrainJobSystem.Job(
                        NativeTerrainJobSystem.Priority.VISIBLE,
                        0,
                        () -> true,
                        () -> {
                            workerStarted.countDown();
                            try {
                                releaseWorker.await();
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    )
                )
            );
            assertTrue(workerStarted.await(5L, TimeUnit.SECONDS));
            jobs.applyBudget(decision(0, false));
            try {
                AtomicInteger ran = new AtomicInteger();
                assertTrue(jobs.submit(job(true, ran)));
                assertTrue(jobs.submit(job(true, ran)));
                assertFalse(jobs.submit(job(true, ran)));
                assertFalse(jobs.submit(job(false, ran)));
                assertEquals(0, ran.get());
                assertTrue(jobs.snapshot().rejectedJobs() >= 1L);
                assertTrue(jobs.snapshot().cancelledJobs() >= 1L);
            } finally {
                releaseWorker.countDown();
            }
        }
    }

    @Test
    void platformWorkersBlockThenExecuteIndependentJobs()
        throws Exception {
        CountDownLatch completed = new CountDownLatch(6);
        try (
            NativeTerrainJobSystem jobs =
                new NativeTerrainJobSystem(
                    new NativeTerrainJobSystem.Topology(8, 4, true),
                    16
                )
        ) {
            jobs.applyBudget(decision(2, false));
            for (int index = 0; index < 6; index++) {
                NativeTerrainJobSystem.Priority priority =
                    index == 0
                        ? NativeTerrainJobSystem.Priority.VISIBLE
                        : NativeTerrainJobSystem.Priority.NEAR;
                assertTrue(
                    jobs.submit(
                        new NativeTerrainJobSystem.Job(
                            priority,
                            index,
                            () -> true,
                            completed::countDown
                        )
                    )
                );
            }
            assertTrue(completed.await(5L, TimeUnit.SECONDS));
            assertEquals(6L, jobs.snapshot().completedJobs());
            assertEquals(0, jobs.snapshot().queuedJobs());
        }
    }

    @Test
    void smtWorkersRequireExplicitControllerPermission() {
        try (
            NativeTerrainJobSystem jobs =
                new NativeTerrainJobSystem(
                    new NativeTerrainJobSystem.Topology(16, 8, true),
                    16
                )
        ) {
            jobs.applyBudget(decision(12, false));
            assertEquals(
                jobs.physicalWorkerLimit(),
                jobs.snapshot().activeWorkerLimit()
            );
            jobs.applyBudget(decision(12, true));
            assertEquals(12, jobs.snapshot().activeWorkerLimit());
        }
    }

    @Test
    void stealingCannotConsumeSignalsAndStrandQueuedJobs()
        throws Exception {
        int jobCount = 10_000;
        CountDownLatch completed = new CountDownLatch(jobCount);
        try (
            NativeTerrainJobSystem jobs =
                new NativeTerrainJobSystem(
                    new NativeTerrainJobSystem.Topology(16, 8, true),
                    jobCount
                )
        ) {
            jobs.applyBudget(decision(6, false));
            for (int index = 0; index < jobCount; index++) {
                assertTrue(
                    jobs.submit(
                        new NativeTerrainJobSystem.Job(
                            NativeTerrainJobSystem.Priority.NEAR,
                            index,
                            () -> true,
                            completed::countDown
                        )
                    )
                );
            }
            assertTrue(
                completed.await(10L, TimeUnit.SECONDS),
                () -> "stranded scheduler state=" + jobs.snapshot()
            );
            assertEquals(0, jobs.snapshot().queuedJobs());
            assertEquals(jobCount, jobs.snapshot().completedJobs());
        }
    }

    private static NativeTerrainJobSystem.Job job(
        boolean current,
        AtomicInteger ran
    ) {
        return new NativeTerrainJobSystem.Job(
            NativeTerrainJobSystem.Priority.FAR,
            100,
            () -> current,
            ran::incrementAndGet
        );
    }

    private static FrameBudgetController.Decision decision(
        int workers,
        boolean smt
    ) {
        return new FrameBudgetController.Decision(
            workers,
            0,
            0L,
            0,
            smt,
            "test"
        );
    }
}
