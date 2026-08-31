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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReusableObjectSlabTest {
    @Test
    void reservesExactConservativeFootprintBeforeArrayAndFactory() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableObjectSlab.Layout layout =
            new ReusableObjectSlab.Layout(12, 596L, 832L);
        AtomicInteger factories = new AtomicInteger();
        AtomicBoolean factoryObservedLease = new AtomicBoolean();

        assertEquals(692L, layout.requestedBytes());
        assertEquals(960L, layout.committedBytes());

        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.SHADER_RESOURCES,
            layout,
            index -> {
                MemoryBudgetManager.Snapshot snapshot =
                    budgets.snapshot();
                factoryObservedLease.set(
                    snapshot.outstanding() == 1
                        && snapshot.usedBytes(MemoryKind.RAM) == 960L
                );
                factories.incrementAndGet();
                return new Object();
            }
        );

        assertNotNull(slab);
        assertTrue(factoryObservedLease.get());
        assertEquals(12, factories.get());
        MemoryBudgetManager.Snapshot reserved = budgets.snapshot();
        assertEquals(692L, reserved.requestedBytes(MemoryKind.RAM));
        assertEquals(960L, reserved.usedBytes(MemoryKind.RAM));
        assertEquals(
            960L,
            reserved.usedBytes(
                MemoryKind.RAM,
                MemoryCategory.SHADER_RESOURCES
            )
        );
        assertEquals(1, reserved.outstanding());

        slab.close();
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void sequentialAcquireExhaustionAndResetReuseStableIdentities() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        Object[] expected = {new Object(), new Object(), new Object()};
        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new ReusableObjectSlab.Layout(3, 3L, 3L),
            index -> expected[index]
        );
        assertNotNull(slab);

        assertEquals(3, slab.capacity());
        assertEquals(0, slab.used());
        assertSame(expected[0], slab.tryAcquire());
        assertSame(expected[1], slab.tryAcquire());
        assertSame(expected[2], slab.tryAcquire());
        assertNull(slab.tryAcquire());
        assertEquals(3, slab.used());

        slab.reset();

        assertEquals(0, slab.used());
        assertSame(expected[0], slab.tryAcquire());
        assertSame(expected[1], slab.tryAcquire());
        assertSame(expected[2], slab.tryAcquire());
        assertNull(slab.tryAcquire());
        slab.close();
    }

    @Test
    void budgetRejectionDoesNotAllocateArrayOrInvokeFactory() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(959L);
        AtomicBoolean arrayInvoked = new AtomicBoolean();
        AtomicBoolean factoryInvoked = new AtomicBoolean();

        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.SHADER_RESOURCES,
            new ReusableObjectSlab.Layout(12, 596L, 832L),
            index -> {
                factoryInvoked.set(true);
                return new Object();
            },
            capacity -> {
                arrayInvoked.set(true);
                return new Object[capacity];
            }
        );

        assertNull(slab);
        assertFalse(arrayInvoked.get());
        assertFalse(factoryInvoked.get());
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
    }

    @Test
    void physicalArrayOutOfMemoryRollsBackWithoutInvokingFactory() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        AtomicBoolean factoryInvoked = new AtomicBoolean();
        AtomicBoolean arrayObservedLease = new AtomicBoolean();

        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new ReusableObjectSlab.Layout(4, 64L, 128L),
            index -> {
                factoryInvoked.set(true);
                return new Object();
            },
            capacity -> {
                arrayObservedLease.set(
                    budgets.snapshot().outstanding() == 1
                );
                throw new OutOfMemoryError("injected array OOME");
            }
        );

        assertNull(slab);
        assertTrue(arrayObservedLease.get());
        assertFalse(factoryInvoked.get());
        assertClean(budgets);
    }

    @Test
    void factoryOutOfMemoryReturnsNullAndRollsBackLease() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        AtomicInteger factories = new AtomicInteger();

        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new ReusableObjectSlab.Layout(4, 64L, 128L),
            index -> {
                factories.incrementAndGet();
                if (index == 2) {
                    throw new OutOfMemoryError("injected factory OOME");
                }
                return new Object();
            }
        );

        assertNull(slab);
        assertEquals(3, factories.get());
        assertClean(budgets);
    }

    @Test
    void creationReleaseFailureRetainsOwnerAndFailsClosedUntilRetry() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        FailingReleaseController leases =
            new FailingReleaseController(budgets, 2);
        Object[] backing = new Object[2];
        OutOfMemoryError injected =
            new OutOfMemoryError("injected factory OOME");

        OutOfMemoryError thrown = assertThrows(
            OutOfMemoryError.class,
            () -> ReusableObjectSlab.tryCreate(
                leases,
                MemoryCategory.CACHES,
                new ReusableObjectSlab.Layout(2, 16L, 16L),
                index -> {
                    if (index == 1) {
                        throw injected;
                    }
                    return new Object();
                },
                capacity -> backing
            )
        );

        assertSame(injected, thrown);
        assertNotNull(backing[0]);
        assertEquals(1, leases.reserveCalls());
        assertEquals(1, leases.releaseCalls());
        assertEquals(1, budgets.snapshot().outstanding());

        assertThrows(
            IllegalStateException.class,
            () -> ReusableObjectSlab.tryCreate(
                leases,
                MemoryCategory.CACHES,
                new ReusableObjectSlab.Layout(1, 8L, 8L),
                index -> new Object(),
                Object[]::new
            )
        );
        assertEquals(1, leases.reserveCalls());
        assertEquals(2, leases.releaseCalls());
        assertNotNull(backing[0]);
        assertEquals(1, budgets.snapshot().outstanding());

        ReusableObjectSlab.retryPendingCleanup();
        assertEquals(3, leases.releaseCalls());
        assertNull(backing[0]);
        assertEquals(0, budgets.snapshot().outstanding());

        ReusableObjectSlab<Object> recovered =
            ReusableObjectSlab.tryCreate(
                leases,
                MemoryCategory.CACHES,
                new ReusableObjectSlab.Layout(1, 8L, 8L),
                index -> new Object(),
                Object[]::new
            );
        assertNotNull(recovered);
        assertEquals(2, leases.reserveCalls());
        recovered.close();
        assertClean(budgets);
    }

    @Test
    void runtimeAndErrorFromFactoryRollbackThenRethrow() {
        MemoryBudgetManager runtimeBudgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        IllegalStateException runtime =
            new IllegalStateException("injected runtime");

        IllegalStateException observedRuntime = assertThrows(
            IllegalStateException.class,
            () -> ReusableObjectSlab.tryCreate(
                runtimeBudgets,
                MemoryCategory.CACHES,
                new ReusableObjectSlab.Layout(2, 16L, 16L),
                index -> {
                    throw runtime;
                }
            )
        );

        assertSame(runtime, observedRuntime);
        assertClean(runtimeBudgets);

        MemoryBudgetManager errorBudgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        AssertionError error = new AssertionError("injected error");

        AssertionError observedError = assertThrows(
            AssertionError.class,
            () -> ReusableObjectSlab.tryCreate(
                errorBudgets,
                MemoryCategory.CACHES,
                new ReusableObjectSlab.Layout(2, 16L, 16L),
                index -> {
                    throw error;
                }
            )
        );

        assertSame(error, observedError);
        assertClean(errorBudgets);
    }

    @Test
    void nullFactoryResultIsRejectedAfterRollingBackLease() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );

        NullPointerException failure = assertThrows(
            NullPointerException.class,
            () -> ReusableObjectSlab.tryCreate(
                budgets,
                MemoryCategory.CACHES,
                new ReusableObjectSlab.Layout(2, 16L, 16L),
                index -> index == 0 ? new Object() : null
            )
        );

        assertTrue(failure.getMessage().contains("index 1"));
        assertClean(budgets);
    }

    @Test
    void rejectsInvalidAndOverflowingLayouts() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ReusableObjectSlab.Layout(0, 0L, 0L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ReusableObjectSlab.Layout(1, -1L, 0L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ReusableObjectSlab.Layout(1, 2L, 1L)
        );
        assertThrows(
            ArithmeticException.class,
            () -> new ReusableObjectSlab.Layout(
                1,
                Long.MAX_VALUE,
                Long.MAX_VALUE
            )
        );
    }

    @Test
    void rejectsCrossThreadAccessWithoutChangingOwnerState()
        throws InterruptedException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new ReusableObjectSlab.Layout(2, 16L, 16L),
            index -> new Object()
        );
        assertNotNull(slab);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread other = new Thread(
            () -> {
                try {
                    slab.tryAcquire();
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            "object-slab-wrong-thread-test"
        );
        other.start();
        other.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(0, slab.used());
        assertNotNull(slab.tryAcquire());
        slab.close();
    }

    @Test
    void closeClearsReferencesIsIdempotentAndRejectsUseAfterClose() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        Object[] backing = new Object[2];
        ReusableObjectSlab<Object> slab = ReusableObjectSlab.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new ReusableObjectSlab.Layout(2, 16L, 16L),
            index -> new Object(),
            capacity -> backing
        );
        assertNotNull(slab);
        assertNotNull(backing[0]);
        assertNotNull(backing[1]);

        slab.close();
        slab.close();

        assertNull(backing[0]);
        assertNull(backing[1]);
        assertClean(budgets);
        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(IllegalStateException.class, slab::tryAcquire);
        assertThrows(IllegalStateException.class, slab::reset);
        assertThrows(IllegalStateException.class, slab::capacity);
        assertThrows(IllegalStateException.class, slab::used);
        assertThrows(IllegalStateException.class, slab::layout);
    }

    private static MemoryBudgetManager managerWithRamCategoryLimit(long limit) {
        long[] ram = categories(limit);
        long[] vram = categories(4_096L);
        return new MemoryBudgetManager(
            new MemoryBudgetSettings(
                4_096L,
                4_096L,
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

    private static void assertClean(MemoryBudgetManager budgets) {
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    private static final class FailingReleaseController
        implements ReusableObjectSlab.LeaseController {
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

        private int reserveCalls() {
            return this.reserveCalls;
        }

        private int releaseCalls() {
            return this.releaseCalls;
        }
    }
}
