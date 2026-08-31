package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MotionObjectBatchTest {
    @Test
    void exactOneSixtyFourSixtyFiveAndTwoFiftySixMatrixStaysBounded() {
        int[] movingObjectCounts = {1, 64, 65, 256};
        for (int movingObjectCount : movingObjectCounts) {
            MemoryBudgetManager budgets = new MemoryBudgetManager(
                MemoryBudgetSettings.defaults()
            );
            MotionObjectBatch batch = MotionObjectBatch.tryCreate(
                budgets,
                MotionVectorGenerator.MAX_OBJECTS
            );
            assertNotNull(batch);

            int accepted = 0;
            for (int index = 0; index < movingObjectCount; index++) {
                if (add(batch, index + 1.0D)) {
                    accepted++;
                }
            }

            assertEquals(
                Math.min(
                    movingObjectCount,
                    MotionVectorGenerator.MAX_OBJECTS
                ),
                accepted
            );
            assertEquals(accepted, batch.size());
            assertEquals(
                movingObjectCount
                    > MotionVectorGenerator.MAX_OBJECTS,
                TemporalResetPolicy
                    .motionObjectCapacityExceeded(
                        movingObjectCount,
                        MotionVectorGenerator.MAX_OBJECTS
                    )
            );
            if (
                movingObjectCount
                    > MotionVectorGenerator.MAX_OBJECTS
            ) {
                batch.clear();
                assertEquals(0, batch.size());
            }

            batch.close();
            assertEquals(0, budgets.snapshot().outstanding());
        }
    }

    @Test
    void storesFixedCapacityAndDoesNotGrowOnOverflow() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        MotionObjectBatch batch = MotionObjectBatch.tryCreate(budgets, 2);
        assertNotNull(batch);

        assertEquals(2, batch.capacity());
        assertEquals(0, batch.size());
        assertTrue(add(batch, 1.0));
        assertTrue(add(batch, 2.0));
        assertFalse(add(batch, 3.0));
        assertEquals(2, batch.size());

        batch.close();
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
    }

    @Test
    void writesExactFiveVecFourLayout() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        MotionObjectBatch batch = MotionObjectBatch.tryCreate(budgets, 1);
        assertNotNull(batch);
        assertTrue(
            batch.add(
                1.0,
                2.0,
                3.0,
                4.0,
                5.0,
                6.0,
                7.0,
                8.0,
                9.0,
                10.0,
                11.0,
                12.0,
                13.0F,
                14.0F
            )
        );
        ByteBuffer target = ByteBuffer
            .allocate(MotionObjectBatch.PACKED_BYTES)
            .order(ByteOrder.nativeOrder());

        batch.writeObject(0, target);

        assertEquals(MotionObjectBatch.PACKED_BYTES, target.position());
        target.flip();
        float[] expected = {
            1.0F, 2.0F, 3.0F, 0.0F,
            4.0F, 5.0F, 6.0F, 0.0F,
            7.0F, 8.0F, 9.0F, 0.0F,
            10.0F, 11.0F, 12.0F, 0.0F,
            13.0F, 14.0F, 0.0F, 0.0F
        };
        for (float value : expected) {
            assertEquals(value, target.getFloat());
        }

        batch.close();
    }

    @Test
    void clearReusesTheSameStorageFromIndexZero() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        MotionObjectBatch batch = MotionObjectBatch.tryCreate(budgets, 1);
        assertNotNull(batch);
        assertTrue(add(batch, 1.0));

        batch.clear();

        assertEquals(0, batch.size());
        assertTrue(add(batch, 20.0));
        ByteBuffer target = ByteBuffer
            .allocate(MotionObjectBatch.PACKED_BYTES)
            .order(ByteOrder.nativeOrder());
        batch.writeObject(0, target);
        target.flip();
        assertEquals(20.0F, target.getFloat());

        batch.close();
    }

    @Test
    void validatesCapacityIndexAndTargetSpace() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MotionObjectBatch.tryCreate(budgets, 0)
        );
        assertThrows(
            ArithmeticException.class,
            () -> MotionObjectBatch.tryCreate(budgets, Integer.MAX_VALUE)
        );

        MotionObjectBatch batch = MotionObjectBatch.tryCreate(budgets, 1);
        assertNotNull(batch);
        assertTrue(add(batch, 1.0));
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> batch.writeObject(-1, ByteBuffer.allocate(80))
        );
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> batch.writeObject(1, ByteBuffer.allocate(80))
        );
        ByteBuffer tooSmall = ByteBuffer.allocate(
            MotionObjectBatch.PACKED_BYTES - 1
        );
        assertThrows(
            BufferOverflowException.class,
            () -> batch.writeObject(0, tooSmall)
        );
        assertEquals(0, tooSmall.position());

        batch.close();
    }

    @Test
    void budgetRejectionReturnsNullWithoutOutstandingLease() {
        long[] ram = categories(256L);
        long[] vram = categories(1_024L);
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            new MemoryBudgetSettings(
                1_024L,
                1_024L,
                0L,
                0L,
                ram,
                vram
            )
        );

        MotionObjectBatch batch = MotionObjectBatch.tryCreate(budgets, 1);

        assertNull(batch);
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(
            0L,
            budgets
                .snapshot()
                .usedBytes(MemoryKind.RAM, MemoryCategory.ENTITIES)
        );
    }

    @Test
    void closeIsIdempotentAndRejectsUseAfterClose() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        MotionObjectBatch batch = MotionObjectBatch.tryCreate(budgets, 1);
        assertNotNull(batch);

        batch.close();
        batch.close();

        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(IllegalStateException.class, batch::clear);
        assertThrows(IllegalStateException.class, batch::size);
        assertThrows(IllegalStateException.class, batch::capacity);
        assertThrows(IllegalStateException.class, () -> add(batch, 1.0));
        assertThrows(
            IllegalStateException.class,
            () -> batch.writeObject(0, ByteBuffer.allocate(80))
        );
    }

    private static boolean add(MotionObjectBatch batch, double base) {
        return batch.add(
            base,
            base + 1.0,
            base + 2.0,
            base + 3.0,
            base + 4.0,
            base + 5.0,
            base + 6.0,
            base + 7.0,
            base + 8.0,
            base + 9.0,
            base + 10.0,
            base + 11.0,
            (float)(base + 12.0),
            (float)(base + 13.0)
        );
    }

    private static long[] categories(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }
}
