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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReusablePrimitiveArenaTest {
    @Test
    void reservesConservativeFootprintBeforeAllocatingStableArrays() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusablePrimitiveArena.Layout layout =
            new ReusablePrimitiveArena.Layout(1, 1, 1, 1, 1);

        assertEquals(25L, layout.requestedBytes());
        assertEquals(320L, layout.committedBytes());

        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            layout
        );

        assertNotNull(arena);
        assertEquals(1, arena.bytes().length);
        assertEquals(1, arena.ints().length);
        assertEquals(1, arena.floats().length);
        assertEquals(1, arena.doubles().length);
        assertEquals(1, arena.references().length);
        MemoryBudgetManager.Snapshot reserved = budgets.snapshot();
        assertEquals(25L, reserved.requestedBytes(MemoryKind.RAM));
        assertEquals(320L, reserved.usedBytes(MemoryKind.RAM));
        assertEquals(
            320L,
            reserved.usedBytes(MemoryKind.RAM, MemoryCategory.ENTITIES)
        );
        assertEquals(1, reserved.outstanding());

        arena.close();
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void claimsAlignedElementOffsetsWithoutGrowing() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new ReusablePrimitiveArena.Layout(8, 8, 8, 8, 8)
        );
        assertNotNull(arena);

        byte[] bytes = arena.bytes();
        int[] ints = arena.ints();
        float[] floats = arena.floats();
        double[] doubles = arena.doubles();
        Object[] references = arena.references();

        assertEquals(0, arena.claimBytes(3, 1));
        assertEquals(4, arena.claimBytes(2, 4));
        assertEquals(0, arena.claimInts(1, 4));
        assertEquals(4, arena.claimInts(1, 16));
        assertEquals(0, arena.claimFloats(1, 4));
        assertEquals(4, arena.claimFloats(1, 16));
        assertEquals(0, arena.claimDoubles(1, 8));
        assertEquals(2, arena.claimDoubles(1, 16));
        assertEquals(0, arena.claimReferences(1, 8));
        assertEquals(2, arena.claimReferences(1, 16));

        assertEquals(-1, arena.claimBytes(3, 1));
        assertEquals(6, arena.claimBytes(2, 1));
        assertSame(bytes, arena.bytes());
        assertSame(ints, arena.ints());
        assertSame(floats, arena.floats());
        assertSame(doubles, arena.doubles());
        assertSame(references, arena.references());

        arena.close();
    }

    @Test
    void resetClearsClaimedReferencesAndRewindsEveryCursor() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            new ReusablePrimitiveArena.Layout(4, 4, 4, 4, 4)
        );
        assertNotNull(arena);

        assertEquals(0, arena.claimBytes(4, 1));
        assertEquals(0, arena.claimInts(4, 4));
        assertEquals(0, arena.claimFloats(4, 4));
        assertEquals(0, arena.claimDoubles(4, 8));
        assertEquals(0, arena.claimReferences(3, 8));
        Object retained = new Object();
        arena.references()[0] = retained;
        arena.references()[1] = retained;
        arena.references()[2] = retained;
        arena.references()[3] = retained;

        arena.reset();

        assertNull(arena.references()[0]);
        assertNull(arena.references()[1]);
        assertNull(arena.references()[2]);
        assertSame(retained, arena.references()[3]);
        assertEquals(0, arena.claimBytes(4, 1));
        assertEquals(0, arena.claimInts(4, 4));
        assertEquals(0, arena.claimFloats(4, 4));
        assertEquals(0, arena.claimDoubles(4, 8));
        assertEquals(0, arena.claimReferences(4, 8));

        arena.close();
    }

    @Test
    void rejectsInvalidLayoutsClaimsAndAlignment() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ReusablePrimitiveArena.Layout(-1, 0, 0, 0, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ReusablePrimitiveArena.Layout(0, 0, 0, 0, 0)
        );

        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new ReusablePrimitiveArena.Layout(8, 1, 1, 1, 1)
        );
        assertNotNull(arena);

        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claimBytes(-1, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claimBytes(1, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> arena.claimBytes(1, 3)
        );
        assertEquals(0, arena.claimBytes(1, 1));
        assertEquals(-1, arena.claimBytes(1, 1 << 30));
        assertEquals(1, arena.claimBytes(7, 1));

        arena.close();
    }

    @Test
    void budgetRejectionReturnsNullBeforeAnyPhysicalAllocation() {
        MemoryBudgetManager budgets = managerWithRamCategoryLimit(256L);
        AtomicReference<Boolean> allocatorInvoked =
            new AtomicReference<>(false);
        ReusablePrimitiveArena.ArrayAllocator allocator =
            recordingAllocator(allocatorInvoked);

        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            new ReusablePrimitiveArena.Layout(1, 1, 1, 1, 1),
            allocator
        );

        assertNull(arena);
        assertFalse(allocatorInvoked.get());
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
    }

    @Test
    void physicalOutOfMemoryReleasesThePreexistingLease() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        AtomicReference<Boolean> observedReservation =
            new AtomicReference<>(false);
        ReusablePrimitiveArena.ArrayAllocator allocator =
            new ReusablePrimitiveArena.ArrayAllocator() {
                @Override
                public byte[] bytes(int capacity) {
                    MemoryBudgetManager.Snapshot snapshot =
                        budgets.snapshot();
                    observedReservation.set(
                        snapshot.outstanding() == 1
                            && snapshot.usedBytes(MemoryKind.RAM) > 0L
                    );
                    throw new OutOfMemoryError("injected");
                }

                @Override
                public int[] ints(int capacity) {
                    throw new AssertionError("unreachable");
                }

                @Override
                public float[] floats(int capacity) {
                    throw new AssertionError("unreachable");
                }

                @Override
                public double[] doubles(int capacity) {
                    throw new AssertionError("unreachable");
                }

                @Override
                public Object[] references(int capacity) {
                    throw new AssertionError("unreachable");
                }
            };

        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.CACHES,
            new ReusablePrimitiveArena.Layout(64, 0, 0, 0, 0),
            allocator
        );

        assertNull(arena);
        assertTrue(observedReservation.get());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
    }

    @Test
    void rejectsCrossThreadAccessWithoutChangingOwnership()
        throws InterruptedException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            new ReusablePrimitiveArena.Layout(8, 0, 0, 0, 0)
        );
        assertNotNull(arena);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread other = new Thread(
            () -> {
                try {
                    arena.claimBytes(1, 1);
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            "arena-wrong-thread-test"
        );
        other.start();
        other.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(0, arena.claimBytes(8, 1));
        arena.close();
    }

    @Test
    void closeIsIdempotentReleasesExactlyOnceAndRejectsFurtherUse() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.STAGING,
            new ReusablePrimitiveArena.Layout(8, 0, 0, 0, 1)
        );
        assertNotNull(arena);
        arena.claimReferences(1, 8);
        arena.references()[0] = new Object();

        arena.close();
        arena.close();

        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(IllegalStateException.class, arena::reset);
        assertThrows(IllegalStateException.class, arena::bytes);
        assertThrows(
            IllegalStateException.class,
            () -> arena.claimBytes(1, 1)
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

    private static ReusablePrimitiveArena.ArrayAllocator recordingAllocator(
        AtomicReference<Boolean> invoked
    ) {
        return new ReusablePrimitiveArena.ArrayAllocator() {
            @Override
            public byte[] bytes(int capacity) {
                invoked.set(true);
                return new byte[capacity];
            }

            @Override
            public int[] ints(int capacity) {
                invoked.set(true);
                return new int[capacity];
            }

            @Override
            public float[] floats(int capacity) {
                invoked.set(true);
                return new float[capacity];
            }

            @Override
            public double[] doubles(int capacity) {
                invoked.set(true);
                return new double[capacity];
            }

            @Override
            public Object[] references(int capacity) {
                invoked.set(true);
                return new Object[capacity];
            }
        };
    }
}
