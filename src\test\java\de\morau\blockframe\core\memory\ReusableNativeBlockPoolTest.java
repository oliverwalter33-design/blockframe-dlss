package de.morau.blockframe.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReusableNativeBlockPoolTest {
    @Test
    void ownsOneLeaseAndReusesStableDirectNativeOrderViews() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                2,
                32,
                16L
            );

        assertNotNull(pool);
        assertEquals(2, pool.blockCount());
        assertEquals(32, pool.blockBytes());
        assertEquals(64L, budgets.snapshot().requestedBytes(MemoryKind.RAM));
        assertEquals(64L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(1, budgets.snapshot().outstanding());

        long firstToken = pool.tryBorrow(20);
        assertNotEquals(0L, firstToken);
        ByteBuffer first = pool.buffer(firstToken, 20);
        assertTrue(first.isDirect());
        assertEquals(ByteOrder.nativeOrder(), first.order());
        assertEquals(0, first.position());
        assertEquals(20, first.limit());
        first.order(nonNativeOrder());
        first.position(7);
        assertSame(first, pool.buffer(firstToken, 12));
        assertEquals(ByteOrder.nativeOrder(), first.order());
        assertEquals(0, first.position());
        assertEquals(12, first.limit());
        first.order(nonNativeOrder());
        pool.release(firstToken);

        long secondGeneration = pool.tryBorrow(32);
        assertNotEquals(firstToken, secondGeneration);
        assertSame(first, pool.buffer(secondGeneration, 32));
        assertEquals(ByteOrder.nativeOrder(), first.order());
        assertThrows(
            IllegalStateException.class,
            () -> pool.buffer(firstToken, 1)
        );
        pool.release(secondGeneration);
        pool.close();

        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void registeredIdlePoolIsPhysicallyClosedAndExactlyReclaimed() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(64L);
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32,
                16L
            );
        assertNotNull(pool);
        assertTrue(
            pool.registerEvictable(
                () -> {
                    pool.close();
                    return true;
                }
            )
        );

        long replacement = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            64L
        );

        assertNotEquals(0L, replacement);
        assertThrows(
            IllegalStateException.class,
            () -> pool.tryBorrow(1)
        );
        MemoryBudgetManager.Snapshot snapshot = budgets.snapshot();
        assertEquals(1L, snapshot.evictions());
        assertEquals(64L, snapshot.reclaimedBytes());
        assertEquals(64L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(1, snapshot.outstanding());
        assertTrue(budgets.release(replacement));
    }

    @Test
    void borrowedPoolDefersEvictionUntilItsOwnerReleasesTheBlock() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(64L);
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32,
                16L
            );
        assertNotNull(pool);
        assertTrue(
            pool.registerEvictable(
                () -> {
                    pool.close();
                    return true;
                }
            )
        );
        long borrowed = pool.tryBorrow(16);

        assertEquals(
            0L,
            budgets.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.STAGING,
                64L
            )
        );
        assertEquals(0L, budgets.snapshot().evictions());
        assertEquals(64L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(1, pool.outstandingBorrows());

        pool.release(borrowed);
        long replacement = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            64L
        );
        assertNotEquals(0L, replacement);
        assertEquals(1L, budgets.snapshot().evictions());
        assertEquals(64L, budgets.snapshot().reclaimedBytes());
        assertTrue(budgets.release(replacement));
    }

    @Test
    void wrongThreadEvictionFailsClosedAndOwnerCanRetry()
        throws InterruptedException {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(64L);
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32,
                16L
            );
        assertNotNull(pool);
        assertTrue(
            pool.registerEvictable(
                () -> {
                    pool.close();
                    return true;
                }
            )
        );
        AtomicReference<Long> crossThreadReservation =
            new AtomicReference<>();
        Thread other = new Thread(
            () -> crossThreadReservation.set(
                budgets.tryReserve(
                    MemoryKind.RAM,
                    MemoryCategory.STAGING,
                    64L
                )
            ),
            "native-pool-wrong-thread-eviction-test"
        );

        other.start();
        other.join();

        assertEquals(0L, crossThreadReservation.get());
        assertEquals(0L, budgets.snapshot().evictions());
        long stillUsable = pool.tryBorrow(1);
        assertNotEquals(0L, stillUsable);
        pool.release(stillUsable);

        long ownerReservation = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            64L
        );
        assertNotEquals(0L, ownerReservation);
        assertEquals(1L, budgets.snapshot().evictions());
        assertTrue(budgets.release(ownerReservation));
    }

    @Test
    void failedPhysicalCloseRetainsLeaseAndRetriesWithoutDoubleRelease() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(64L);
        AtomicInteger physicalCloses = new AtomicInteger();
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                1,
                32,
                16L,
                layout -> arena(
                    new DirectReleaseController(budgets),
                    layout,
                    physicalCloses,
                    1
                )
            );
        assertNotNull(pool);
        assertTrue(
            pool.registerEvictable(
                () -> {
                    try {
                        pool.close();
                        return true;
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
            )
        );

        assertEquals(
            0L,
            budgets.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.STAGING,
                64L
            )
        );
        assertEquals(1, physicalCloses.get());
        assertEquals(1, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().evictions());

        long replacement = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            64L
        );
        assertNotEquals(0L, replacement);
        assertEquals(2, physicalCloses.get());
        assertEquals(1L, budgets.snapshot().evictions());
        assertEquals(64L, budgets.snapshot().reclaimedBytes());
        assertTrue(budgets.release(replacement));
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void budgetRejectionReturnsNullWithoutCreatingNativePool() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(63L);

        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32,
                16L
            );

        assertNull(pool);
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
    }

    @Test
    void postArenaOutOfMemoryRetainsFailedReleaseForFinalRetry() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets);
        AtomicInteger physicalCloses = new AtomicInteger();
        OutOfMemoryError injected =
            new OutOfMemoryError("injected metadata OOME");

        OutOfMemoryError thrown = assertThrows(
            OutOfMemoryError.class,
            () -> ReusableNativeBlockPool.tryCreate(
                1,
                32,
                16L,
                layout -> arena(
                    leases,
                    layout,
                    physicalCloses,
                    0
                ),
                (arena, blockCount, blockBytes, alignment) -> {
                    throw injected;
                }
            )
        );

        assertSame(injected, thrown);
        assertEquals(1, physicalCloses.get());
        assertEquals(1, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());
        assertEquals(1, thrown.getSuppressed().length);

        ReusableNativeBlockPool.retryPendingCleanup();
        assertEquals(1, physicalCloses.get());
        assertEquals(2, leases.releaseCalls());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void postArenaRuntimeRetainsFailedPhysicalCloseForFinalRetry() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicBoolean secondAllocatorCalled = new AtomicBoolean();
        IllegalStateException injected =
            new IllegalStateException("injected metadata runtime");

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> ReusableNativeBlockPool.tryCreate(
                1,
                32,
                16L,
                layout -> arena(
                    new DirectReleaseController(budgets),
                    layout,
                    physicalCloses,
                    2
                ),
                (arena, blockCount, blockBytes, alignment) -> {
                    throw injected;
                }
            )
        );

        assertSame(injected, thrown);
        assertEquals(1, physicalCloses.get());
        assertEquals(1, budgets.snapshot().outstanding());
        assertEquals(1, thrown.getSuppressed().length);

        assertThrows(
            IllegalStateException.class,
            () -> ReusableNativeBlockPool.tryCreate(
                1,
                32,
                16L,
                layout -> {
                    secondAllocatorCalled.set(true);
                    throw new AssertionError("must not allocate");
                }
            )
        );
        assertFalse(secondAllocatorCalled.get());
        assertEquals(2, physicalCloses.get());
        assertEquals(1, budgets.snapshot().outstanding());

        ReusableNativeBlockPool.retryPendingCleanup();
        assertEquals(3, physicalCloses.get());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void exhaustionAndOversizedRequestsNeverGrowThePool() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                2,
                32
            );
        assertNotNull(pool);
        long committed = budgets.snapshot().usedBytes(MemoryKind.RAM);

        assertEquals(0L, pool.tryBorrow(33));
        assertEquals(96L, budgets.snapshot().requestedBytes(MemoryKind.RAM));
        assertEquals(128L, committed);
        long first = pool.tryBorrow(0);
        long second = pool.tryBorrow(32);
        assertNotEquals(0L, first);
        assertNotEquals(0L, second);
        assertEquals(0L, pool.tryBorrow(1));
        assertEquals(2, pool.outstandingBorrows());
        assertEquals(committed, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertThrows(
            IllegalArgumentException.class,
            () -> pool.tryBorrow(-1)
        );

        pool.release(first);
        pool.release(second);
        assertEquals(0, pool.outstandingBorrows());
        pool.close();
    }

    @Test
    void validatesStaleDoubleAndMalformedTokens() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32
            );
        assertNotNull(pool);

        long first = pool.tryBorrow(16);
        pool.release(first);
        assertThrows(
            IllegalStateException.class,
            () -> pool.release(first)
        );
        assertThrows(
            IllegalStateException.class,
            () -> pool.buffer(first, 16)
        );
        assertThrows(
            IllegalStateException.class,
            () -> pool.buffer(0L, 16)
        );

        long next = pool.tryBorrow(16);
        assertNotEquals(first, next);
        assertThrows(
            IllegalStateException.class,
            () -> pool.release(first)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> pool.buffer(next, 33)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> pool.buffer(next, -1)
        );
        pool.release(next);
        pool.close();
    }

    @Test
    void closeRejectsAndRetainsPoolWhileBorrowedThenClosesAtZero() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32
            );
        assertNotNull(pool);
        long token = pool.tryBorrow(16);
        ByteBuffer buffer = pool.buffer(token, 16);

        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(1, pool.outstandingBorrows());
        assertEquals(1, budgets.snapshot().outstanding());
        assertSame(buffer, pool.buffer(token, 8));

        pool.release(token);
        assertEquals(0, pool.outstandingBorrows());
        pool.close();
        assertEquals(0, budgets.snapshot().outstanding());
        assertThrows(
            IllegalStateException.class,
            () -> buffer.get(0)
        );
    }

    @Test
    void poolCloseRetriesArenaLeaseWithoutClosingPhysicalStorageTwice() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets);
        AtomicInteger physicalCloses = new AtomicInteger();
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                1,
                32,
                16L,
                layout -> BudgetedNativeArena.tryCreate(
                    leases,
                    MemoryCategory.STAGING,
                    layout,
                    () -> {
                        Arena physical = Arena.ofConfined();
                        return new BudgetedNativeArena.NativeStorage() {
                            @Override
                            public MemorySegment allocate(
                                long byteSize,
                                long byteAlignment
                            ) {
                                return physical.allocate(
                                    byteSize,
                                    byteAlignment
                                );
                            }

                            @Override
                            public void close() {
                                physicalCloses.incrementAndGet();
                                physical.close();
                            }
                        };
                    }
                )
            );
        assertNotNull(pool);

        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(1, physicalCloses.get());
        assertEquals(1, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());
        assertThrows(
            IllegalStateException.class,
            () -> pool.tryBorrow(1)
        );

        pool.close();
        pool.close();
        assertEquals(1, physicalCloses.get());
        assertEquals(2, leases.releaseCalls());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void rejectsCrossThreadAndPostCloseUseAndCloseIsIdempotent()
        throws InterruptedException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableNativeBlockPool pool =
            ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32
            );
        assertNotNull(pool);
        AtomicReference<Throwable> borrowFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread other = new Thread(
            () -> {
                try {
                    pool.tryBorrow(1);
                } catch (Throwable error) {
                    borrowFailure.set(error);
                }
                try {
                    pool.close();
                } catch (Throwable error) {
                    closeFailure.set(error);
                }
            },
            "native-pool-wrong-thread-test"
        );
        other.start();
        other.join();

        assertInstanceOf(IllegalStateException.class, borrowFailure.get());
        assertInstanceOf(IllegalStateException.class, closeFailure.get());
        assertEquals(0, pool.outstandingBorrows());
        pool.close();
        pool.close();

        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(
            IllegalStateException.class,
            () -> pool.tryBorrow(1)
        );
        assertThrows(
            IllegalStateException.class,
            pool::outstandingBorrows
        );
    }

    @Test
    void rejectsInvalidFixedLayout() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                0,
                32
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                0
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                32,
                3L
            )
        );
        assertEquals(0, budgets.snapshot().outstanding());
    }

    private static BudgetedNativeArena arena(
        BudgetedNativeArena.LeaseController leases,
        BudgetedNativeArena.Layout layout,
        AtomicInteger physicalCloses,
        int failingPhysicalCloses
    ) {
        return BudgetedNativeArena.tryCreate(
            leases,
            MemoryCategory.STAGING,
            layout,
            () -> {
                Arena physical = Arena.ofConfined();
                return new BudgetedNativeArena.NativeStorage() {
                    @Override
                    public MemorySegment allocate(
                        long byteSize,
                        long byteAlignment
                    ) {
                        return physical.allocate(
                            byteSize,
                            byteAlignment
                        );
                    }

                    @Override
                    public void close() {
                        int attempt = physicalCloses.incrementAndGet();
                        if (attempt <= failingPhysicalCloses) {
                            throw new IllegalStateException(
                                "injected physical close"
                            );
                        }
                        physical.close();
                    }
                };
            }
        );
    }

    private static MemoryBudgetManager managerWithRamCategoryLimit(long limit) {
        long[] ram = categories(limit);
        long[] vram = categories(1_024L);
        return new MemoryBudgetManager(
            new MemoryBudgetSettings(
                1_024L,
                1_024L,
                0L,
                0L,
                ram,
                vram
            )
        );
    }

    private static long[] categories(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }

    private static ByteOrder nonNativeOrder() {
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            ? ByteOrder.BIG_ENDIAN
            : ByteOrder.LITTLE_ENDIAN;
    }

    private static final class FailingReleaseController
        implements BudgetedNativeArena.LeaseController {
        private final MemoryBudgetManager budgets;
        private int releaseCalls;

        private FailingReleaseController(MemoryBudgetManager budgets) {
            this.budgets = budgets;
        }

        @Override
        public long tryReserve(
            MemoryCategory category,
            long requestedBytes,
            long committedBytes
        ) {
            return this.budgets.tryReserve(
                MemoryKind.RAM,
                category,
                requestedBytes,
                committedBytes,
                null
            );
        }

        @Override
        public boolean release(long token) {
            this.releaseCalls++;
            if (this.releaseCalls == 1) {
                return false;
            }
            return this.budgets.release(token);
        }

        private int releaseCalls() {
            return this.releaseCalls;
        }
    }

    private record DirectReleaseController(
        MemoryBudgetManager budgets
    ) implements BudgetedNativeArena.LeaseController {
        @Override
        public long tryReserve(
            MemoryCategory category,
            long requestedBytes,
            long committedBytes
        ) {
            return this.budgets.tryReserve(
                MemoryKind.RAM,
                category,
                requestedBytes,
                committedBytes,
                null
            );
        }

        @Override
        public boolean registerEvictable(
            long token,
            MemoryBudgetManager.Evictable evictable
        ) {
            return this.budgets.registerEvictable(token, evictable);
        }

        @Override
        public boolean touch(long token) {
            return this.budgets.touch(token);
        }

        @Override
        public boolean release(long token) {
            return this.budgets.release(token);
        }
    }
}
