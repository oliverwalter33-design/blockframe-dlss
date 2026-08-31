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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EntityMotionHistoryTest {
    @Test
    void rotatesPrimitiveCurrentAndPreviousFrames() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            8
        );
        assertNotNull(history);
        assertEquals(
            EntityMotionHistory.StorageKind.HEAP,
            history.storageKind()
        );

        history.beginFrame();
        assertFalse(history.findPrevious(7));
        assertTrue(history.putCurrent(7, 1.0, 2.0, 3.0, 4.0F));
        assertTrue(history.putCurrent(-19, 5.0, 6.0, 7.0, 8.0F));
        assertEquals(2, history.currentSize());

        history.beginFrame();
        assertTrue(history.findPrevious(7));
        assertEquals(1.0, history.previousX());
        assertEquals(2.0, history.previousY());
        assertEquals(3.0, history.previousZ());
        assertEquals(4.0F, history.previousYaw());
        assertTrue(history.findPrevious(-19));
        assertEquals(5.0, history.previousX());
        assertTrue(history.putCurrent(7, 10.0, 20.0, 30.0, 40.0F));
        assertEquals(1, history.currentSize());

        history.beginFrame();
        assertTrue(history.findPrevious(7));
        assertEquals(10.0, history.previousX());
        assertEquals(40.0F, history.previousYaw());
        assertFalse(history.findPrevious(-19));

        history.close();
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void updatesDuplicateIdWithoutConsumingAnotherSlot() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            4
        );
        assertNotNull(history);
        history.beginFrame();

        assertTrue(history.putCurrent(3, 1.0, 2.0, 3.0, 4.0F));
        assertTrue(history.putCurrent(3, 5.0, 6.0, 7.0, 8.0F));
        assertEquals(1, history.currentSize());

        history.beginFrame();
        assertTrue(history.findPrevious(3));
        assertEquals(5.0, history.previousX());
        assertEquals(8.0F, history.previousYaw());
        history.close();
    }

    @Test
    void reportsBoundedLoadOverflowWithoutDroppingAcceptedEntries() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            4
        );
        assertNotNull(history);
        assertEquals(3, history.maxEntries());
        history.beginFrame();

        assertTrue(history.putCurrent(1, 1.0, 1.0, 1.0, 1.0F));
        assertTrue(history.putCurrent(2, 2.0, 2.0, 2.0, 2.0F));
        assertTrue(history.putCurrent(3, 3.0, 3.0, 3.0, 3.0F));
        assertFalse(history.putCurrent(4, 4.0, 4.0, 4.0, 4.0F));
        assertEquals(3, history.currentSize());

        history.beginFrame();
        assertTrue(history.findPrevious(1));
        assertTrue(history.findPrevious(2));
        assertTrue(history.findPrevious(3));
        assertFalse(history.findPrevious(4));
        history.close();
    }

    @Test
    void smallestTableStillHonorsTheDocumentedLoadCeiling() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            2
        );
        assertNotNull(history);
        assertEquals(1, history.maxEntries());
        history.beginFrame();

        assertTrue(history.putCurrent(1, 1.0, 1.0, 1.0, 1.0F));
        assertFalse(history.putCurrent(2, 2.0, 2.0, 2.0, 2.0F));
        assertEquals(1, history.currentSize());

        history.close();
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void clearInvalidatesBothFramesAndRequiresANewFrame() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            8
        );
        assertNotNull(history);
        history.beginFrame();
        assertTrue(history.putCurrent(9, 1.0, 2.0, 3.0, 4.0F));
        history.beginFrame();
        assertTrue(history.findPrevious(9));

        history.clear();

        assertFalse(history.findPrevious(9));
        assertThrows(
            IllegalStateException.class,
            () -> history.putCurrent(9, 1.0, 2.0, 3.0, 4.0F)
        );
        assertThrows(IllegalStateException.class, history::previousX);
        history.beginFrame();
        assertFalse(history.findPrevious(9));
        history.close();
    }

    @Test
    void validatesCapacityAndBudgetRejection() {
        MemoryBudgetManager defaults = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> EntityMotionHistory.tryCreate(defaults, 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> EntityMotionHistory.tryCreate(defaults, 3)
        );
        assertThrows(
            ArithmeticException.class,
            () -> EntityMotionHistory.tryCreate(defaults, 1 << 30)
        );

        long[] ram = categories(128L);
        long[] vram = categories(1_024L);
        MemoryBudgetManager constrained = new MemoryBudgetManager(
            new MemoryBudgetSettings(
                1_024L,
                1_024L,
                0L,
                0L,
                ram,
                vram
            )
        );
        EntityMotionHistory rejected = EntityMotionHistory.tryCreate(
            constrained,
            2
        );
        assertNull(rejected);
        assertEquals(
            1L,
            constrained.snapshot().rejections(),
            "heap standard must reject once before legacy fallback"
        );
        assertEquals(0, constrained.snapshot().outstanding());
        assertEquals(
            0L,
            constrained
                .snapshot()
                .usedBytes(MemoryKind.RAM, MemoryCategory.ENTITIES)
        );

        MemoryBudgetManager constrainedExperimental =
            new MemoryBudgetManager(
                new MemoryBudgetSettings(
                    1_024L,
                    1_024L,
                    0L,
                    0L,
                    ram,
                    vram
                )
            );
        EntityMotionHistory experimentalRejected =
            EntityMotionHistory.tryCreate(
                constrainedExperimental,
                2,
                EntityMotionHistory.BackendPreference
                    .NATIVE_EXPERIMENTAL
            );
        assertNull(experimentalRejected);
        assertEquals(
            2L,
            constrainedExperimental.snapshot().rejections(),
            "explicit native experiment and its heap fallback must reject"
        );
        assertEquals(
            0,
            constrainedExperimental.snapshot().outstanding()
        );
    }

    @Test
    void productionCapacityUsesHeapByDefaultWithExactAccounting() {
        int capacity = 65_536;
        long expectedBytes = 5_242_880L;
        long expectedHeapCommittedBytes = 5_243_200L;
        assertEquals(
            expectedBytes,
            EntityMotionHistory.nativeRequestedBytes(capacity)
        );
        assertEquals(
            expectedBytes,
            EntityMotionHistory.nativeCommittedBytes(capacity)
        );

        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            capacity
        );
        assertNotNull(history);
        assertEquals(
            EntityMotionHistory.StorageKind.HEAP,
            history.storageKind()
        );
        assertEquals(expectedBytes, history.requestedBytes());
        assertEquals(
            expectedHeapCommittedBytes,
            history.committedBytes()
        );

        MemoryBudgetManager.Snapshot active = budgets.snapshot();
        assertEquals(1, active.outstanding());
        assertEquals(
            expectedHeapCommittedBytes,
            active.usedBytes(
                MemoryKind.RAM,
                MemoryCategory.ENTITIES
            )
        );
        assertEquals(0L, active.rejections());

        history.close();
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(
            0L,
            budgets.snapshot().usedBytes(
                MemoryKind.RAM,
                MemoryCategory.ENTITIES
            )
        );
    }

    @Test
    void explicitExperimentalPreferenceSelectsNative() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            8,
            EntityMotionHistory.BackendPreference.NATIVE_EXPERIMENTAL
        );

        assertNotNull(history);
        assertEquals(
            EntityMotionHistory.StorageKind.NATIVE,
            history.storageKind()
        );
        history.close();
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void backendPreferenceParsingFailsClosedToHeap() {
        assertEquals(
            EntityMotionHistory.BackendPreference.HEAP,
            EntityMotionHistory.BackendPreference.byId(null)
        );
        assertEquals(
            EntityMotionHistory.BackendPreference.HEAP,
            EntityMotionHistory.BackendPreference.byId("unknown")
        );
        assertEquals(
            EntityMotionHistory.BackendPreference.NATIVE_EXPERIMENTAL,
            EntityMotionHistory.BackendPreference.byId(
                "NATIVE-EXPERIMENTAL"
            )
        );
    }

    @Test
    void explicitNativeAllocationOomeFallsBackToHeapHistory() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history =
            EntityMotionHistory.tryCreateExperimentalNative(
                budgets,
                8,
                (arena, bytes, alignment) -> {
                    throw new OutOfMemoryError(
                        "synthetic native history allocation failure"
                    );
                }
            );

        assertNotNull(history);
        assertEquals(
            EntityMotionHistory.StorageKind.HEAP,
            history.storageKind()
        );
        assertEquals(640L, history.requestedBytes());
        assertEquals(960L, history.committedBytes());
        MemoryBudgetManager.Snapshot fallback = budgets.snapshot();
        assertEquals(1, fallback.outstanding());
        assertEquals(0L, fallback.rejections());
        assertEquals(
            960L,
            fallback.usedBytes(
                MemoryKind.RAM,
                MemoryCategory.ENTITIES
            )
        );

        history.beginFrame();
        assertTrue(
            history.putCurrent(41, 1.0D, 2.0D, 3.0D, 4.0F)
        );
        history.beginFrame();
        assertTrue(history.findPrevious(41));
        assertEquals(1.0D, history.previousX());

        history.close();
        EntityMotionHistory.retryPendingCleanup();
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().leaks());
    }

    @Test
    void nativeAndHeapBackendsProduceTheSameDeterministicTrace() {
        MemoryBudgetManager nativeBudgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        MemoryBudgetManager heapBudgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory nativeHistory =
            EntityMotionHistory.tryCreateNative(
                nativeBudgets,
                256
            );
        EntityMotionHistory heapHistory =
            EntityMotionHistory.tryCreateHeap(
                heapBudgets,
                256
            );
        assertNotNull(nativeHistory);
        assertNotNull(heapHistory);
        assertEquals(
            EntityMotionHistory.StorageKind.NATIVE,
            nativeHistory.storageKind()
        );
        assertEquals(
            EntityMotionHistory.StorageKind.HEAP,
            heapHistory.storageKind()
        );

        long nativeChecksum = deterministicTrace(nativeHistory);
        long heapChecksum = deterministicTrace(heapHistory);
        assertEquals(heapChecksum, nativeChecksum);
        assertTrue(nativeChecksum != 0L);

        nativeHistory.close();
        heapHistory.close();
        assertEquals(0, nativeBudgets.snapshot().outstanding());
        assertEquals(0, heapBudgets.snapshot().outstanding());
    }

    @Test
    void nativeHistoryRejectsWrongThreadWithoutPoisoningOwnerClose()
        throws Exception {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history =
            EntityMotionHistory.tryCreateNative(budgets, 8);
        assertNotNull(history);
        AtomicReference<Throwable> wrongThreadFailure =
            new AtomicReference<>();
        Thread wrongThread = new Thread(
            () -> {
                try {
                    history.beginFrame();
                } catch (Throwable error) {
                    wrongThreadFailure.set(error);
                }
            },
            "phase1a5-wrong-owner"
        );

        wrongThread.start();
        wrongThread.join();

        assertTrue(
            wrongThreadFailure.get() instanceof IllegalStateException,
            () -> "unexpected wrong-thread result: "
                + wrongThreadFailure.get()
        );
        assertEquals(
            EntityMotionHistory.StorageKind.NATIVE,
            history.storageKind()
        );
        history.close();
        assertEquals(0, budgets.snapshot().outstanding());

        AtomicReference<Throwable> closedWrongThreadFailure =
            new AtomicReference<>();
        Thread closedWrongThread = new Thread(
            () -> {
                try {
                    history.close();
                } catch (Throwable error) {
                    closedWrongThreadFailure.set(error);
                }
            },
            "phase1a5-closed-wrong-owner"
        );
        closedWrongThread.start();
        closedWrongThread.join();
        assertTrue(
            closedWrongThreadFailure.get()
                instanceof IllegalStateException,
            () -> "unexpected closed wrong-thread result: "
                + closedWrongThreadFailure.get()
        );
    }

    @Test
    void failedNativeClaimCleansItsLeaseAndAllowsANewNativeOwner() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> EntityMotionHistory.tryCreateNative(
                budgets,
                8,
                (arena, bytes, alignment) ->
                    arena.claim(bytes - 64L, alignment)
            )
        );
        assertTrue(
            failure.getMessage().contains("unexpected size")
        );
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(
            0L,
            budgets.snapshot().usedBytes(
                MemoryKind.RAM,
                MemoryCategory.ENTITIES
            )
        );

        EntityMotionHistory.retryPendingCleanup();
        EntityMotionHistory replacement =
            EntityMotionHistory.tryCreateNative(budgets, 8);
        assertNotNull(replacement);
        assertEquals(
            EntityMotionHistory.StorageKind.NATIVE,
            replacement.storageKind()
        );
        replacement.close();
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().leaks());
    }

    @Test
    void closeIsIdempotentAndRejectsUseAfterClose() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            8
        );
        assertNotNull(history);

        history.close();
        history.close();

        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(IllegalStateException.class, history::beginFrame);
        assertThrows(IllegalStateException.class, history::clear);
        assertThrows(IllegalStateException.class, history::capacity);
        assertThrows(
            IllegalStateException.class,
            () -> history.findPrevious(1)
        );
        assertThrows(
            IllegalStateException.class,
            history::storageKind
        );
        assertThrows(
            IllegalStateException.class,
            history::requestedBytes
        );
        assertThrows(
            IllegalStateException.class,
            history::committedBytes
        );
    }

    private static long deterministicTrace(
        EntityMotionHistory history
    ) {
        long checksum = 0x6A09E667F3BCC909L;
        for (int frame = 0; frame < 12; frame++) {
            history.beginFrame();
            for (int index = 0; index < 96; index++) {
                int entityId = (index & 1) == 0
                    ? index * 104_729
                    : -index * 13_007;
                boolean found = history.findPrevious(entityId);
                assertEquals(frame != 0, found);
                checksum = Long.rotateLeft(
                    checksum ^ (found ? entityId : ~entityId),
                    11
                );
                if (found) {
                    checksum = mix(
                        checksum,
                        Double.doubleToLongBits(
                            history.previousX()
                        )
                    );
                    checksum = mix(
                        checksum,
                        Double.doubleToLongBits(
                            history.previousY()
                        )
                    );
                    checksum = mix(
                        checksum,
                        Double.doubleToLongBits(
                            history.previousZ()
                        )
                    );
                    checksum = mix(
                        checksum,
                        Float.floatToIntBits(
                            history.previousYaw()
                        )
                    );
                }

                double x = frame * 0.25D + index * 1.5D;
                double y = frame * -0.5D + index * 0.125D;
                double z = frame * 2.0D - index * 0.75D;
                float yaw = frame * 0.03125F + index * 0.015625F;
                assertTrue(
                    history.putCurrent(
                        entityId,
                        x,
                        y,
                        z,
                        yaw
                    )
                );
                if (index % 17 == 0) {
                    assertTrue(
                        history.putCurrent(
                            entityId,
                            x + 0.25D,
                            y - 0.5D,
                            z + 0.75D,
                            yaw + 0.125F
                        )
                    );
                }
            }
            assertEquals(96, history.currentSize());
            checksum = mix(checksum, history.currentSize());
        }
        return checksum;
    }

    private static long mix(long checksum, long value) {
        return Long.rotateLeft(checksum ^ value, 17)
            * 0x9E3779B97F4A7C15L;
    }

    private static long[] categories(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }
}
