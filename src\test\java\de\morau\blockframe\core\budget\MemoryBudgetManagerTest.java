package de.morau.blockframe.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MemoryBudgetManagerTest {
    @Test
    void globalLimitsAreIndependentForRamAndVram() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(100L, 100L, 10L, 10L, 100L, 100L)
        );

        long ramTerrain = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.TERRAIN,
            50L
        );
        long ramEntities = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.ENTITIES,
            40L
        );
        assertNotEquals(0L, ramTerrain);
        assertNotEquals(0L, ramEntities);
        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.PARTICLES,
                1L
            )
        );

        long vram = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.PARTICLES,
            90L
        );
        assertNotEquals(0L, vram);
        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.VRAM,
                MemoryCategory.CACHES,
                1L
            )
        );

        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(90L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(90L, snapshot.usedBytes(MemoryKind.VRAM));
        assertEquals(0L, manager.availableBytes(MemoryKind.RAM));
        assertEquals(2L, snapshot.rejections());
    }

    @Test
    void categoryLimitRejectsWithoutConsumingOtherCategoryCapacity() {
        long[] ramCategories = categories(100L);
        ramCategories[MemoryCategory.TERRAIN.ordinal()] = 40L;
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(100L, 100L, 0L, 0L, ramCategories, categories(100L))
        );

        long terrain = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.TERRAIN,
            40L
        );
        assertNotEquals(0L, terrain);
        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.TERRAIN,
                1L
            )
        );
        long entities = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.ENTITIES,
            60L
        );
        assertNotEquals(0L, entities);

        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(100L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(
            40L,
            snapshot.usedBytes(MemoryKind.RAM, MemoryCategory.TERRAIN)
        );
        assertEquals(
            60L,
            snapshot.usedBytes(MemoryKind.RAM, MemoryCategory.ENTITIES)
        );
    }

    @Test
    void rejectionUsesCheckedBoundaryArithmeticAndLeavesAccountingUntouched() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0L,
                0L,
                Long.MAX_VALUE,
                Long.MAX_VALUE
            )
        );
        long maximum = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            Long.MAX_VALUE
        );
        assertNotEquals(0L, maximum);

        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                1L
            )
        );
        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(Long.MAX_VALUE, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(Long.MAX_VALUE, snapshot.requestedBytes(MemoryKind.RAM));
        assertEquals(1, snapshot.outstanding());
        assertEquals(1L, snapshot.rejections());
        assertEquals(0L, manager.availableBytes(MemoryKind.RAM));

        assertThrows(
            IllegalArgumentException.class,
            () -> manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                0L
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                2L,
                1L,
                null
            )
        );
    }

    @Test
    void highWaterMarksAndFragmentationTrackRequestedVersusCommittedBytes() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(256L, 256L, 0L, 0L, 256L, 256L)
        );
        long first = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.SHADER_RESOURCES,
            50L,
            64L,
            null
        );
        long second = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.SHADER_RESOURCES,
            20L,
            32L,
            null
        );
        assertNotEquals(0L, first);
        assertNotEquals(0L, second);

        MemoryBudgetManager.Snapshot peak = manager.snapshot();
        assertEquals(96L, peak.usedBytes(MemoryKind.VRAM));
        assertEquals(70L, peak.requestedBytes(MemoryKind.VRAM));
        assertEquals(26L, peak.fragmentationBytes(MemoryKind.VRAM));
        assertEquals(96L, peak.peakBytes(MemoryKind.VRAM));
        assertEquals(
            96L,
            peak.peakBytes(
                MemoryKind.VRAM,
                MemoryCategory.SHADER_RESOURCES
            )
        );

        assertTrue(manager.release(second));
        MemoryBudgetManager.Snapshot afterRelease = manager.snapshot();
        assertEquals(64L, afterRelease.usedBytes(MemoryKind.VRAM));
        assertEquals(50L, afterRelease.requestedBytes(MemoryKind.VRAM));
        assertEquals(14L, afterRelease.fragmentationBytes(MemoryKind.VRAM));
        assertEquals(96L, afterRelease.peakBytes(MemoryKind.VRAM));
        assertEquals(
            96L,
            afterRelease.peakBytes(
                MemoryKind.VRAM,
                MemoryCategory.SHADER_RESOURCES
            )
        );
    }

    @Test
    void deterministicLruEvictsTheUntouchedOldestLease() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(80L, 80L, 0L, 0L, 80L, 80L)
        );
        List<String> evicted = new ArrayList<>();
        long first = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                evicted.add("first");
                return true;
            }
        );
        long second = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                evicted.add("second");
                return true;
            }
        );
        assertTrue(manager.touch(first));

        long third = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                evicted.add("third");
                return true;
            }
        );

        assertNotEquals(0L, second);
        assertNotEquals(0L, third);
        assertEquals(List.of("second"), evicted);
        assertFalse(manager.touch(second));
        assertTrue(manager.touch(first));
        assertTrue(manager.touch(third));
        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(1L, snapshot.evictions());
        assertEquals(40L, snapshot.reclaimedBytes());
        assertEquals(80L, snapshot.usedBytes(MemoryKind.RAM));
    }

    @Test
    void lateRegistrationEvictsOnlyAfterPhysicalOwnerIsConstructed() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(64L, 64L, 0L, 0L, 64L, 64L)
        );
        List<String> events = new ArrayList<>();
        AtomicLong ownerLease = new AtomicLong(
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.STAGING,
                64L
            )
        );

        assertNotEquals(0L, ownerLease.get());
        assertTrue(
            manager.registerEvictable(
                ownerLease.get(),
                () -> {
                    events.add("physical-close");
                    boolean released = manager.release(
                        ownerLease.get()
                    );
                    events.add("lease-release");
                    return released;
                }
            )
        );

        long replacement = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            64L
        );

        assertNotEquals(0L, replacement);
        assertEquals(
            List.of("physical-close", "lease-release"),
            events
        );
        assertFalse(manager.touch(ownerLease.get()));
        assertTrue(manager.touch(replacement));
        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(1L, snapshot.evictions());
        assertEquals(64L, snapshot.reclaimedBytes());
        assertEquals(64L, snapshot.usedBytes(MemoryKind.RAM));
    }

    @Test
    void lateRegistrationRejectsDuplicatePinnedRetiringAndStaleLeases() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(256L, 256L, 0L, 0L, 256L, 256L)
        );
        long registered = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            32L
        );
        assertTrue(
            manager.registerEvictable(registered, () -> false)
        );
        assertFalse(
            manager.registerEvictable(registered, () -> true)
        );

        long pinned = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            32L
        );
        assertTrue(manager.pin(pinned));
        assertFalse(manager.registerEvictable(pinned, () -> true));
        assertTrue(manager.unpin(pinned));
        assertTrue(manager.registerEvictable(pinned, () -> false));

        long retiring = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.SHADER_RESOURCES,
            32L
        );
        assertTrue(manager.retireAfterGpuUse(retiring));
        assertFalse(
            manager.registerEvictable(retiring, () -> true)
        );

        long stale = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            32L
        );
        assertTrue(manager.release(stale));
        assertFalse(manager.registerEvictable(stale, () -> true));
        assertThrows(
            NullPointerException.class,
            () -> manager.registerEvictable(registered, null)
        );
    }

    @Test
    void managerCloseInvokesLateRegisteredOwnerAndReportsNoLeak() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(64L, 64L, 0L, 0L, 64L, 64L)
        );
        AtomicLong lease = new AtomicLong(
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.STAGING,
                64L
            )
        );
        AtomicInteger physicalCloses = new AtomicInteger();
        assertTrue(
            manager.registerEvictable(
                lease.get(),
                () -> {
                    physicalCloses.incrementAndGet();
                    return manager.release(lease.get());
                }
            )
        );

        manager.close();

        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(1, physicalCloses.get());
        assertEquals(1L, snapshot.evictions());
        assertEquals(64L, snapshot.reclaimedBytes());
        assertEquals(0, snapshot.outstanding());
        assertEquals(0L, snapshot.leaks());
        assertTrue(snapshot.closed());
    }

    @Test
    void failedOldestEvictionFallsThroughToYoungerCandidate() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(80L, 80L, 0L, 0L, 80L, 80L)
        );
        AtomicInteger oldestCalls = new AtomicInteger();
        AtomicInteger youngerCalls = new AtomicInteger();
        long oldest = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                oldestCalls.incrementAndGet();
                return false;
            }
        );
        long younger = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                youngerCalls.incrementAndGet();
                return true;
            }
        );

        long replacement = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L
        );

        assertNotEquals(0L, replacement);
        assertEquals(1, oldestCalls.get());
        assertEquals(1, youngerCalls.get());
        assertTrue(manager.touch(oldest));
        assertFalse(manager.touch(younger));
        assertTrue(manager.touch(replacement));
        assertEquals(1L, manager.snapshot().evictions());
    }

    @Test
    void throwingOldestEvictionFallsThroughToYoungerCandidate() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(80L, 80L, 0L, 0L, 80L, 80L)
        );
        AtomicInteger youngerCalls = new AtomicInteger();
        long throwing = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                throw new IllegalStateException("synthetic eviction failure");
            }
        );
        long younger = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L,
            40L,
            () -> {
                youngerCalls.incrementAndGet();
                return true;
            }
        );

        long replacement = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            40L
        );

        assertNotEquals(0L, replacement);
        assertEquals(1, youngerCalls.get());
        assertTrue(manager.touch(throwing));
        assertFalse(manager.touch(younger));
        assertEquals(1L, manager.snapshot().evictions());
    }

    @Test
    void evictionFinalizationCannotDeleteAReusedSlotGeneration() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(10L, 10L, 0L, 0L, 10L, 10L)
        );
        AtomicLong original = new AtomicLong();
        AtomicLong replacement = new AtomicLong();
        original.set(
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                10L,
                10L,
                () -> {
                    assertTrue(manager.release(original.get()));
                    replacement.set(
                        manager.tryReserve(
                            MemoryKind.RAM,
                            MemoryCategory.CACHES,
                            10L
                        )
                    );
                    return true;
                }
            )
        );

        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                1L
            )
        );

        assertNotEquals(0L, replacement.get());
        assertFalse(manager.touch(original.get()));
        assertTrue(manager.touch(replacement.get()));
        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(10L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(1, snapshot.outstanding());
        assertEquals(1L, snapshot.evictions());
    }

    @Test
    void evictionCallbackCanWaitForReleaseFromAnotherThread() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(10L, 10L, 0L, 0L, 10L, 10L)
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicLong victim = new AtomicLong();
        try {
            victim.set(
                manager.tryReserve(
                    MemoryKind.RAM,
                    MemoryCategory.CACHES,
                    10L,
                    10L,
                    () -> {
                        try {
                            return executor
                                .submit(() -> manager.release(victim.get()))
                                .get(1L, TimeUnit.SECONDS);
                        } catch (Exception error) {
                            throw new AssertionError(
                                "release could not acquire the manager",
                                error
                            );
                        }
                    }
                )
            );

            long replacement = assertTimeoutPreemptively(
                Duration.ofSeconds(2L),
                () -> manager.tryReserve(
                    MemoryKind.RAM,
                    MemoryCategory.CACHES,
                    10L
                )
            );

            assertNotEquals(0L, replacement);
            assertFalse(manager.touch(victim.get()));
            assertTrue(manager.touch(replacement));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inFlightEvictionRejectsPinTouchAndGpuRetirement() throws Exception {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(10L, 10L, 0L, 0L, 10L, 10L)
        );
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackToFinish = new CountDownLatch(1);
        AtomicLong victim = new AtomicLong();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            victim.set(
                manager.tryReserve(
                    MemoryKind.VRAM,
                    MemoryCategory.SHADER_RESOURCES,
                    10L,
                    10L,
                    () -> {
                        callbackEntered.countDown();
                        try {
                            if (
                                !allowCallbackToFinish.await(
                                    2L,
                                    TimeUnit.SECONDS
                                )
                            ) {
                                throw new AssertionError(
                                    "test did not release eviction callback"
                                );
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(
                                "eviction callback interrupted",
                                error
                            );
                        }
                        return true;
                    }
                )
            );

            Future<Long> replacement = executor.submit(
                () -> manager.tryReserve(
                    MemoryKind.VRAM,
                    MemoryCategory.SHADER_RESOURCES,
                    10L
                )
            );
            assertTrue(callbackEntered.await(1L, TimeUnit.SECONDS));
            assertFalse(manager.pin(victim.get()));
            assertFalse(manager.touch(victim.get()));
            assertFalse(manager.retireAfterGpuUse(victim.get()));
            assertEquals(
                1L,
                manager.snapshot().deniedInFlightReleases()
            );

            allowCallbackToFinish.countDown();
            assertNotEquals(
                0L,
                replacement.get(1L, TimeUnit.SECONDS)
            );
            assertFalse(manager.touch(victim.get()));
        } finally {
            allowCallbackToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWaitsForAnAlreadyRunningEvictionCallback() throws Exception {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(10L, 10L, 0L, 0L, 10L, 10L)
        );
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                10L,
                10L,
                () -> {
                    callbackEntered.countDown();
                    try {
                        if (
                            !allowCallbackToFinish.await(
                                2L,
                                TimeUnit.SECONDS
                            )
                        ) {
                            throw new AssertionError(
                                "test did not release eviction callback"
                            );
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(
                            "eviction callback interrupted",
                            error
                        );
                    }
                    return true;
                }
            );
            Future<Long> pressure = executor.submit(
                () -> manager.tryReserve(
                    MemoryKind.RAM,
                    MemoryCategory.CACHES,
                    10L
                )
            );
            assertTrue(callbackEntered.await(1L, TimeUnit.SECONDS));

            Future<?> close = executor.submit(manager::close);
            assertTimeoutPreemptively(
                Duration.ofSeconds(1L),
                () -> {
                    while (true) {
                        try {
                            manager.tryReserve(
                                MemoryKind.RAM,
                                MemoryCategory.CACHES,
                                1L
                            );
                        } catch (IllegalStateException expected) {
                            break;
                        }
                        Thread.onSpinWait();
                    }
                }
            );
            assertFalse(close.isDone());

            allowCallbackToFinish.countDown();
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> pressure.get(1L, TimeUnit.SECONDS)
            );
            close.get(1L, TimeUnit.SECONDS);

            MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
            assertTrue(snapshot.closed());
            assertEquals(0, snapshot.outstanding());
            assertEquals(0L, snapshot.leaks());
        } finally {
            allowCallbackToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void pinPreventsBothReleaseAndEvictionUntilUnpinned() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(100L, 100L, 0L, 0L, 100L, 100L)
        );
        AtomicInteger evictionCalls = new AtomicInteger();
        long pinned = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.STAGING,
            60L,
            60L,
            () -> {
                evictionCalls.incrementAndGet();
                return true;
            }
        );
        assertTrue(manager.pin(pinned));

        assertFalse(manager.release(pinned));
        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.VRAM,
                MemoryCategory.STAGING,
                50L
            )
        );
        assertEquals(0, evictionCalls.get());
        assertTrue(manager.unpin(pinned));

        long replacement = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.STAGING,
            50L
        );
        assertNotEquals(0L, replacement);
        assertEquals(1, evictionCalls.get());
        assertFalse(manager.touch(pinned));
        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(1L, snapshot.deniedInFlightReleases());
        assertEquals(1L, snapshot.rejections());
        assertEquals(1L, snapshot.evictions());
    }

    @Test
    void gpuRetirementKeepsLeaseForThreeCompletedFrames() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(64L, 64L, 0L, 0L, 64L, 64L)
        );
        long lease = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.SHADER_RESOURCES,
            64L
        );
        assertTrue(manager.retireAfterGpuUse(lease));
        assertFalse(manager.release(lease));
        assertFalse(manager.touch(lease));
        assertEquals(
            0L,
            manager.tryReserve(
                MemoryKind.VRAM,
                MemoryCategory.SHADER_RESOURCES,
                1L
            )
        );

        manager.advanceFrame();
        manager.advanceFrame();
        assertEquals(64L, manager.snapshot().usedBytes(MemoryKind.VRAM));
        assertEquals(1, manager.snapshot().outstanding());
        assertEquals(
            1L,
            manager.snapshot().deniedInFlightReleases()
        );

        manager.advanceFrame();
        assertEquals(0L, manager.snapshot().usedBytes(MemoryKind.VRAM));
        assertEquals(0, manager.snapshot().outstanding());
        assertNotEquals(
            0L,
            manager.tryReserve(
                MemoryKind.VRAM,
                MemoryCategory.SHADER_RESOURCES,
                64L
            )
        );
    }

    @Test
    void encoderDrainCompletesAllGpuRetirementsImmediately() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(64L, 64L, 0L, 0L, 64L, 64L)
        );
        long first = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.SHADER_RESOURCES,
            16L
        );
        long second = manager.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.STAGING,
            16L
        );
        long live = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            16L
        );
        assertTrue(manager.retireAfterGpuUse(first));
        assertTrue(manager.retireAfterGpuUse(second));

        assertEquals(2, manager.completeGpuRetirements());
        assertEquals(0, manager.completeGpuRetirements());

        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertEquals(0L, snapshot.usedBytes(MemoryKind.VRAM));
        assertEquals(16L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(1, snapshot.outstanding());
        assertFalse(manager.touch(first));
        assertFalse(manager.touch(second));
        assertTrue(manager.touch(live));
    }

    @Test
    void closeBlocksReservationsBeforeInvokingEvictionCallbacks()
        throws Exception {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(10L, 10L, 0L, 0L, 10L, 10L)
        );
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            10L,
            10L,
            () -> {
                callbackEntered.countDown();
                try {
                    if (
                        !allowCallbackToFinish.await(
                            2L,
                            TimeUnit.SECONDS
                        )
                    ) {
                        throw new AssertionError(
                            "test did not release eviction callback"
                        );
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "eviction callback interrupted",
                        error
                    );
                }
                return true;
            }
        );

        Future<?> close = executor.submit(manager::close);
        try {
            assertTrue(callbackEntered.await(1L, TimeUnit.SECONDS));
            Future<Throwable> reservation = executor.submit(() -> {
                try {
                    manager.tryReserve(
                        MemoryKind.RAM,
                        MemoryCategory.CACHES,
                        1L
                    );
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            Throwable failure;
            try {
                failure = reservation.get(1L, TimeUnit.SECONDS);
            } finally {
                allowCallbackToFinish.countDown();
            }
            assertTrue(failure instanceof IllegalStateException);
            close.get(1L, TimeUnit.SECONDS);
        } finally {
            allowCallbackToFinish.countDown();
            executor.shutdownNow();
        }

        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertTrue(snapshot.closed());
        assertEquals(0, snapshot.outstanding());
        assertEquals(0L, snapshot.leaks());
        assertEquals(1L, snapshot.evictions());
    }

    @Test
    void closeEvictsWhatItCanAndCountsOnlyOutstandingLeaksOnce() {
        MemoryBudgetManager manager = new MemoryBudgetManager(
            settings(100L, 100L, 0L, 0L, 100L, 100L)
        );
        AtomicInteger evictions = new AtomicInteger();
        long nonEvictable = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            20L
        );
        long pinnedEvictable = manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            20L,
            20L,
            () -> {
                evictions.incrementAndGet();
                return true;
            }
        );
        manager.pin(pinnedEvictable);
        manager.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            20L,
            20L,
            () -> {
                evictions.incrementAndGet();
                return true;
            }
        );

        manager.close();
        manager.close();

        MemoryBudgetManager.Snapshot snapshot = manager.snapshot();
        assertNotEquals(0L, nonEvictable);
        assertEquals(1, evictions.get());
        assertEquals(2, snapshot.outstanding());
        assertEquals(2L, snapshot.leaks());
        assertEquals(1L, snapshot.evictions());
        assertTrue(snapshot.closed());
        assertFalse(manager.touch(nonEvictable));
        assertFalse(manager.pin(nonEvictable));
        assertFalse(manager.retireAfterGpuUse(nonEvictable));
        assertThrows(
            IllegalStateException.class,
            () -> manager.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                1L
            )
        );
    }

    @Test
    void closeReportRejectsOutstandingLeaseWithoutChangingCloseContract() {
        MemoryBudgetManager clean = new MemoryBudgetManager(
            settings(100L, 100L, 0L, 0L, 100L, 100L)
        );
        long released = clean.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            20L
        );
        assertTrue(clean.release(released));
        assertTrue(clean.closeAndReport());
        assertTrue(clean.closeAndReport());

        MemoryBudgetManager retained = new MemoryBudgetManager(
            settings(100L, 100L, 0L, 0L, 100L, 100L)
        );
        assertNotEquals(
            0L,
            retained.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                20L
            )
        );

        assertFalse(retained.closeAndReport());
        assertFalse(retained.closeAndReport());
        retained.close();
        MemoryBudgetManager.Snapshot snapshot = retained.snapshot();
        assertTrue(snapshot.closed());
        assertEquals(1, snapshot.outstanding());
        assertEquals(1L, snapshot.leaks());
    }

    private static MemoryBudgetSettings settings(
        long ramMaximum,
        long vramMaximum,
        long ramSafety,
        long vramSafety,
        long categoryRam,
        long categoryVram
    ) {
        return settings(
            ramMaximum,
            vramMaximum,
            ramSafety,
            vramSafety,
            categories(categoryRam),
            categories(categoryVram)
        );
    }

    private static MemoryBudgetSettings settings(
        long ramMaximum,
        long vramMaximum,
        long ramSafety,
        long vramSafety,
        long[] ramCategories,
        long[] vramCategories
    ) {
        return new MemoryBudgetSettings(
            ramMaximum,
            vramMaximum,
            ramSafety,
            vramSafety,
            ramCategories,
            vramCategories
        );
    }

    private static long[] categories(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }
}
