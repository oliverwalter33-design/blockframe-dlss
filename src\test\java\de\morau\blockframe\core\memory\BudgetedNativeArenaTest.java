package de.morau.blockframe.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BudgetedNativeArenaTest {
    @Test
    void accountsExactRequestedAndConservativeCommittedFootprint() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        BudgetedNativeArena.Layout layout =
            new BudgetedNativeArena.Layout(32L, 16L);

        assertEquals(32L, layout.requestedBytes());
        assertEquals(64L, layout.committedBytes());

        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            layout
        );

        assertNotNull(arena);
        assertEquals(32L, arena.capacityBytes());
        MemoryBudgetManager.Snapshot reserved = budgets.snapshot();
        assertEquals(32L, reserved.requestedBytes(MemoryKind.RAM));
        assertEquals(64L, reserved.usedBytes(MemoryKind.RAM));
        assertEquals(
            64L,
            reserved.usedBytes(MemoryKind.RAM, MemoryCategory.STAGING)
        );
        assertEquals(1, reserved.outstanding());

        arena.close();
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void reservesBudgetBeforeOpeningAndAllocatingPhysicalArena() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        List<String> events = new ArrayList<>();
        BudgetedNativeArena.NativeArenaFactory factory = () -> {
            MemoryBudgetManager.Snapshot atOpen = budgets.snapshot();
            assertEquals(1, atOpen.outstanding());
            assertEquals(64L, atOpen.usedBytes(MemoryKind.RAM));
            events.add("open");
            Arena physical = Arena.ofConfined();
            return new BudgetedNativeArena.NativeStorage() {
                @Override
                public MemorySegment allocate(
                    long byteSize,
                    long byteAlignment
                ) {
                    assertEquals(1, budgets.snapshot().outstanding());
                    events.add("allocate");
                    return physical.allocate(byteSize, byteAlignment);
                }

                @Override
                public void close() {
                    assertEquals(1, budgets.snapshot().outstanding());
                    events.add("close");
                    physical.close();
                }
            };
        };

        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new BudgetedNativeArena.Layout(32L, 16L),
            factory
        );

        assertNotNull(arena);
        assertEquals(List.of("open", "allocate"), events);
        arena.close();
        assertEquals(List.of("open", "allocate", "close"), events);
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void budgetRejectionReturnsNullBeforePhysicalArenaIsOpened() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(63L);
        AtomicReference<Boolean> opened = new AtomicReference<>(false);

        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L),
            () -> {
                opened.set(true);
                throw new AssertionError("must not open");
            }
        );

        assertNull(arena);
        assertFalse(opened.get());
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void outOfMemoryClosesPhysicalArenaThenReleasesLeaseAndReturnsNull() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        List<String> events = new ArrayList<>();
        BudgetedNativeArena.NativeArenaFactory factory = () -> {
            events.add("open");
            return new BudgetedNativeArena.NativeStorage() {
                @Override
                public MemorySegment allocate(
                    long byteSize,
                    long byteAlignment
                ) {
                    assertEquals(1, budgets.snapshot().outstanding());
                    events.add("allocate");
                    throw new OutOfMemoryError("injected");
                }

                @Override
                public void close() {
                    assertEquals(1, budgets.snapshot().outstanding());
                    events.add("close");
                }
            };
        };

        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L),
            factory
        );

        assertNull(arena);
        assertEquals(List.of("open", "allocate", "close"), events);
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void runtimeFailureRollsBackAndRethrowsOriginalFailure() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        RuntimeException injected = new RuntimeException("injected");
        AtomicReference<Boolean> closed = new AtomicReference<>(false);

        RuntimeException failure = assertThrows(
            RuntimeException.class,
            () -> BudgetedNativeArena.tryCreate(
                budgets,
                MemoryCategory.CACHES,
                new BudgetedNativeArena.Layout(32L, 16L),
                () -> new BudgetedNativeArena.NativeStorage() {
                    @Override
                    public MemorySegment allocate(
                        long byteSize,
                        long byteAlignment
                    ) {
                        throw injected;
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                }
            )
        );

        assertEquals(injected, failure);
        assertTrue(closed.get());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
    }

    @Test
    void creationCloseFailureKeepsLeaseAndRethrowsOutOfMemory() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets, 0);
        OutOfMemoryError allocationFailure =
            new OutOfMemoryError("injected allocation");
        IllegalStateException closeFailure =
            new IllegalStateException("injected close");
        AtomicInteger closeAttempts = new AtomicInteger();

        OutOfMemoryError thrown = assertThrows(
            OutOfMemoryError.class,
            () -> BudgetedNativeArena.tryCreate(
                leases,
                MemoryCategory.STAGING,
                new BudgetedNativeArena.Layout(32L, 16L),
                () -> new BudgetedNativeArena.NativeStorage() {
                    @Override
                    public MemorySegment allocate(
                        long byteSize,
                        long byteAlignment
                    ) {
                        throw allocationFailure;
                    }

                    @Override
                    public void close() {
                        if (closeAttempts.incrementAndGet() == 1) {
                            throw closeFailure;
                        }
                    }
                }
            )
        );

        assertSame(allocationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
        assertEquals(0, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());
        BudgetedNativeArena.retryPendingCleanup();
        assertEquals(2, closeAttempts.get());
        assertEquals(1, leases.releaseCalls());
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void creationLeaseReleaseFailureIsVisibleAfterPhysicalClose() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets, 1);
        OutOfMemoryError allocationFailure =
            new OutOfMemoryError("injected allocation");
        AtomicInteger physicalCloses = new AtomicInteger();

        OutOfMemoryError thrown = assertThrows(
            OutOfMemoryError.class,
            () -> BudgetedNativeArena.tryCreate(
                leases,
                MemoryCategory.STAGING,
                new BudgetedNativeArena.Layout(32L, 16L),
                () -> new BudgetedNativeArena.NativeStorage() {
                    @Override
                    public MemorySegment allocate(
                        long byteSize,
                        long byteAlignment
                    ) {
                        throw allocationFailure;
                    }

                    @Override
                    public void close() {
                        physicalCloses.incrementAndGet();
                    }
                }
            )
        );

        assertSame(allocationFailure, thrown);
        assertEquals(1, physicalCloses.get());
        assertEquals(1, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());
        assertEquals(1, thrown.getSuppressed().length);
        assertInstanceOf(
            IllegalStateException.class,
            thrown.getSuppressed()[0]
        );
        BudgetedNativeArena.retryPendingCleanup();
        assertEquals(1, physicalCloses.get());
        assertEquals(2, leases.releaseCalls());
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void newCreationFailsClosedWhilePendingPhysicalCleanupStillFails() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets, 0);
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicReference<Boolean> secondFactoryOpened =
            new AtomicReference<>(false);

        assertThrows(
            OutOfMemoryError.class,
            () -> BudgetedNativeArena.tryCreate(
                leases,
                MemoryCategory.STAGING,
                new BudgetedNativeArena.Layout(32L, 16L),
                () -> new BudgetedNativeArena.NativeStorage() {
                    @Override
                    public MemorySegment allocate(
                        long byteSize,
                        long byteAlignment
                    ) {
                        throw new OutOfMemoryError(
                            "injected allocation"
                        );
                    }

                    @Override
                    public void close() {
                        if (physicalCloses.incrementAndGet() <= 2) {
                            throw new IllegalStateException(
                                "injected pending close"
                            );
                        }
                    }
                }
            )
        );

        assertThrows(
            IllegalStateException.class,
            () -> BudgetedNativeArena.tryCreate(
                leases,
                MemoryCategory.STAGING,
                new BudgetedNativeArena.Layout(32L, 16L),
                () -> {
                    secondFactoryOpened.set(true);
                    throw new AssertionError("must not open");
                }
            )
        );
        assertFalse(secondFactoryOpened.get());
        assertEquals(1, leases.reserveCalls());
        assertEquals(0, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());

        BudgetedNativeArena.retryPendingCleanup();
        assertEquals(3, physicalCloses.get());
        assertEquals(1, leases.releaseCalls());
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void physicalCloseFailureQuarantinesArenaAndCloseRemainsRetryable() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        AtomicInteger physicalCloses = new AtomicInteger();
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L),
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
                        if (physicalCloses.incrementAndGet() == 1) {
                            throw new IllegalStateException(
                                "injected physical close"
                            );
                        }
                        physical.close();
                    }
                };
            }
        );
        assertNotNull(arena);
        assertNotNull(arena.claim(8L, 8L));

        assertThrows(IllegalStateException.class, arena::close);
        assertEquals(1, physicalCloses.get());
        assertEquals(1, budgets.snapshot().outstanding());
        assertThrows(
            IllegalStateException.class,
            () -> arena.claim(1L, 1L)
        );
        assertThrows(IllegalStateException.class, arena::reset);
        assertThrows(
            IllegalStateException.class,
            arena::capacityBytes
        );
        assertThrows(
            IllegalStateException.class,
            arena::claimedBytes
        );

        arena.close();
        arena.close();
        assertEquals(2, physicalCloses.get());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void closeRetriesOnlyLeaseAfterPhysicalStorageWasClosed() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets, 1);
        AtomicInteger physicalCloses = new AtomicInteger();
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            leases,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L),
            () -> {
                Arena physical = Arena.ofConfined();
                return new BudgetedNativeArena.NativeStorage() {
                    @Override
                    public MemorySegment allocate(
                        long byteSize,
                        long byteAlignment
                    ) {
                        return physical.allocate(byteSize, byteAlignment);
                    }

                    @Override
                    public void close() {
                        physicalCloses.incrementAndGet();
                        physical.close();
                    }
                };
            }
        );
        assertNotNull(arena);

        assertThrows(IllegalStateException.class, arena::close);
        assertEquals(1, physicalCloses.get());
        assertEquals(1, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());
        assertThrows(
            IllegalStateException.class,
            () -> arena.claim(1L, 1L)
        );

        arena.close();
        arena.close();
        assertEquals(1, physicalCloses.get());
        assertEquals(2, leases.releaseCalls());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void claimsAlignedBoundedSlicesAndResetRewindsByContract() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L)
        );
        assertNotNull(arena);

        MemorySegment first = arena.claim(3L, 1L);
        MemorySegment second = arena.claim(8L, 8L);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(3L, first.byteSize());
        assertEquals(8L, second.byteSize());
        assertEquals(16L, arena.claimedBytes());
        assertEquals(0L, second.address() & 7L);
        assertNull(arena.claim(17L, 1L));
        assertEquals(16L, arena.claimedBytes());

        first.set(ValueLayout.JAVA_BYTE, 0L, (byte)7);
        second.set(ValueLayout.JAVA_BYTE, 0L, (byte)9);
        first = null;
        second = null;
        arena.reset();
        assertEquals(0L, arena.claimedBytes());
        assertNotNull(arena.claim(32L, 16L));
        assertEquals(32L, arena.claimedBytes());

        arena.close();
    }

    @Test
    void rejectsInvalidAlignmentAndChecksArithmeticAndBounds() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new BudgetedNativeArena.Layout(0L, 16L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new BudgetedNativeArena.Layout(32L, 0L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new BudgetedNativeArena.Layout(32L, 3L)
        );
        assertThrows(
            ArithmeticException.class,
            () -> new BudgetedNativeArena.Layout(Long.MAX_VALUE, 64L)
        );

        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(16L, 16L)
        );
        assertNotNull(arena);

        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claim(-1L, 1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claim(1L, 0L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claim(1L, 3L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claim(1L, 32L)
        );
        assertNotNull(arena.claim(1L, 1L));
        assertThrows(
            ArithmeticException.class,
            () -> arena.claim(Long.MAX_VALUE, 1L)
        );
        assertNull(arena.claim(16L, 1L));

        arena.close();
    }

    @Test
    void rejectsCrossThreadAccessWithoutChangingOwnerState()
        throws InterruptedException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L)
        );
        assertNotNull(arena);
        AtomicReference<Throwable> claimFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread other = new Thread(
            () -> {
                try {
                    arena.claim(1L, 1L);
                } catch (Throwable error) {
                    claimFailure.set(error);
                }
                try {
                    arena.close();
                } catch (Throwable error) {
                    closeFailure.set(error);
                }
            },
            "native-arena-wrong-thread-test"
        );
        other.start();
        other.join();

        assertInstanceOf(IllegalStateException.class, claimFailure.get());
        assertInstanceOf(IllegalStateException.class, closeFailure.get());
        assertNotNull(arena.claim(32L, 16L));
        arena.close();
    }

    @Test
    void closeIsIdempotentInvalidatesSegmentsAndReleasesExactlyOnce() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new BudgetedNativeArena.Layout(32L, 16L)
        );
        assertNotNull(arena);
        MemorySegment retained = arena.claim(1L, 1L);
        assertNotNull(retained);

        arena.close();
        arena.close();

        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(
            IllegalStateException.class,
            () -> retained.get(ValueLayout.JAVA_BYTE, 0L)
        );
        assertThrows(
            IllegalStateException.class,
            () -> arena.claim(1L, 1L)
        );
        assertThrows(IllegalStateException.class, arena::reset);
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

    private static final class FailingReleaseController
        implements BudgetedNativeArena.LeaseController {
        private final MemoryBudgetManager budgets;
        private int remainingFailures;
        private int reserveCalls;
        private int releaseCalls;

        private FailingReleaseController(
            MemoryBudgetManager budgets,
            int remainingFailures
        ) {
            this.budgets = budgets;
            this.remainingFailures = remainingFailures;
        }

        @Override
        public long tryReserve(
            MemoryCategory category,
            long requestedBytes,
            long committedBytes
        ) {
            this.reserveCalls++;
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
            if (this.remainingFailures != 0) {
                this.remainingFailures--;
                return false;
            }
            return this.budgets.release(token);
        }

        private int releaseCalls() {
            return this.releaseCalls;
        }

        private int reserveCalls() {
            return this.reserveCalls;
        }
    }
}
