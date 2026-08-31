package de.morau.blockframe.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.memory.BudgetedNativeArena;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VulkanGpuFrameTimerTest {
    private static final int VK_ERROR_DEVICE_LOST = -4;

    @Test
    void computesNormalAndWrappedTimestampDeltas() {
        assertEquals(50L, VulkanGpuFrameTimer.timestampDelta(100L, 150L, 64));
        assertEquals(11L, VulkanGpuFrameTimer.timestampDelta(250L, 5L, 8));
    }

    @Test
    void rejectsInvalidTimestampWidths() {
        assertThrows(
            IllegalArgumentException.class,
            () -> VulkanGpuFrameTimer.timestampDelta(0L, 0L, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> VulkanGpuFrameTimer.timestampDelta(0L, 0L, 65)
        );
    }

    @Test
    void rejectedDeviceRemainsRejectedUntilIdentityChanges() {
        VulkanGpuFrameTimer.RejectedDeviceGate<Object> gate =
            new VulkanGpuFrameTimer.RejectedDeviceGate<>();
        Object rejected = new Object();
        Object replacement = new Object();

        gate.reject(rejected);
        gate.observe(rejected);
        assertTrue(gate.isRejected(rejected));

        gate.observe(replacement);
        assertFalse(gate.isRejected(rejected));
        assertFalse(gate.isRejected(replacement));
        assertFalse(gate.clear());
    }

    @Test
    void explicitReloadGateClearAllowsTheSameDeviceToBeRetried() {
        VulkanGpuFrameTimer.RejectedDeviceGate<Object> gate =
            new VulkanGpuFrameTimer.RejectedDeviceGate<>();
        Object rejected = new Object();

        gate.reject(rejected);
        gate.observe(rejected);
        assertTrue(gate.isRejected(rejected));
        assertTrue(gate.clear());
        assertFalse(gate.isRejected(rejected));
        assertFalse(gate.clear());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void configurationReloadClearsRejectedOwnerWithoutAllocatingScratch()
        throws ReflectiveOperationException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        VulkanGpuFrameTimer timer = new VulkanGpuFrameTimer(
            new FrameProfiler(),
            budgets
        );
        VulkanGpuFrameTimer.RejectedDeviceGate gate =
            (VulkanGpuFrameTimer.RejectedDeviceGate)field(
                timer,
                "rejectedDevices"
            );
        Object rejected = new Object();
        gate.reject(rejected);
        assertTrue(gate.isRejected(rejected));

        timer.configurationReloaded();

        assertFalse(gate.isRejected(rejected));
        assertEquals(
            "unavailable: awaiting Vulkan reconfiguration",
            timer.status()
        );
        assertEquals(0, budgets.snapshot().outstanding());
        timer.close();
    }

    @Test
    void constructorAndUnconfiguredLifecycleNeverAllocateNativeScratch() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        VulkanGpuFrameTimer timer = new VulkanGpuFrameTimer(
            new FrameProfiler(),
            budgets
        );

        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));

        timer.configurationReloaded();
        assertEquals(0, budgets.snapshot().outstanding());

        timer.close();
        assertEquals("closed", timer.status());
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void nativeScratchUsesOneExactBudgetedStagingView() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        VulkanGpuFrameTimer timer = new VulkanGpuFrameTimer(
            new FrameProfiler(),
            budgets
        );

        LongBuffer first = timer.ensureQueryResultsScratch();
        LongBuffer second = timer.ensureQueryResultsScratch();

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(4, first.capacity());
        assertEquals(ByteOrder.nativeOrder(), first.order());
        assertEquals(
            4L * Long.BYTES,
            VulkanGpuFrameTimer.QUERY_RESULT_REQUESTED_BYTES
        );
        assertEquals(
            64L,
            VulkanGpuFrameTimer.QUERY_RESULT_COMMITTED_BYTES
        );
        MemoryBudgetManager.Snapshot reserved = budgets.snapshot();
        assertEquals(
            VulkanGpuFrameTimer.QUERY_RESULT_REQUESTED_BYTES,
            reserved.requestedBytes(MemoryKind.RAM)
        );
        assertEquals(
            VulkanGpuFrameTimer.QUERY_RESULT_COMMITTED_BYTES,
            reserved.usedBytes(MemoryKind.RAM)
        );
        assertEquals(
            VulkanGpuFrameTimer.QUERY_RESULT_COMMITTED_BYTES,
            reserved.usedBytes(MemoryKind.RAM, MemoryCategory.STAGING)
        );
        assertEquals(1, reserved.outstanding());

        timer.configurationReloaded();
        assertSame(first, timer.ensureQueryResultsScratch());

        timer.close();
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void budgetRejectionAllocatesNothingAndLeavesOnlyTimerUnavailable() {
        MemoryBudgetManager budgets = managerWithStagingLimit(63L);
        VulkanGpuFrameTimer timer = new VulkanGpuFrameTimer(
            new FrameProfiler(),
            budgets
        );

        assertNull(timer.ensureQueryResultsScratch());
        MemoryBudgetManager.Snapshot rejected = budgets.snapshot();
        assertEquals(1L, rejected.rejections());
        assertEquals(0L, rejected.usedBytes(MemoryKind.RAM));
        assertEquals(0, rejected.outstanding());

        timer.close();
        assertEquals("closed", timer.status());
    }

    @Test
    void closeRetainsArenaOwnerUntilLeaseReleaseCanBeRetried()
        throws ReflectiveOperationException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        VulkanGpuFrameTimer timer = new VulkanGpuFrameTimer(
            new FrameProfiler(),
            budgets
        );
        assertNotNull(timer.ensureQueryResultsScratch());

        BudgetedNativeArena arena = (BudgetedNativeArena)field(
            timer,
            "queryResultsArena"
        );
        assertNotNull(arena);
        long lease = (long)field(arena, "budgetLease");
        assertTrue(budgets.pin(lease));

        assertThrows(IllegalStateException.class, timer::close);
        assertSame(arena, field(timer, "queryResultsArena"));
        assertNull(field(timer, "queryResults"));
        assertFalse("closed".equals(timer.status()));
        assertEquals(1, budgets.snapshot().outstanding());

        timer.configurationReloaded();
        assertFalse("closed".equals(timer.status()));
        assertTrue(budgets.unpin(lease));
        timer.close();

        assertEquals("closed", timer.status());
        assertNull(field(timer, "queryResultsArena"));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void hardQueryErrorsDisableImmediately() {
        assertEquals(
            VulkanGpuFrameTimer.QueryPollDecision.DISABLE,
            VulkanGpuFrameTimer.queryPollDecision(
                VK_ERROR_DEVICE_LOST,
                false,
                1
            )
        );
    }

    @Test
    void unavailableQueriesRetryOnlyUpToTheConservativeLimit() {
        assertEquals(
            VulkanGpuFrameTimer.QueryPollDecision.RETRY,
            VulkanGpuFrameTimer.queryPollDecision(
                VulkanGpuFrameTimer.QUERY_RESULT_NOT_READY,
                false,
                VulkanGpuFrameTimer.MAX_UNAVAILABLE_POLLS - 1
            )
        );
        assertEquals(
            VulkanGpuFrameTimer.QueryPollDecision.DISABLE,
            VulkanGpuFrameTimer.queryPollDecision(
                VulkanGpuFrameTimer.QUERY_RESULT_NOT_READY,
                false,
                VulkanGpuFrameTimer.MAX_UNAVAILABLE_POLLS
            )
        );
        assertEquals(
            VulkanGpuFrameTimer.QueryPollDecision.READY,
            VulkanGpuFrameTimer.queryPollDecision(
                VulkanGpuFrameTimer.QUERY_RESULT_SUCCESS,
                true,
                VulkanGpuFrameTimer.MAX_UNAVAILABLE_POLLS
            )
        );
    }

    @Test
    void incompleteSuccessfulResultsUseTheSameBoundedPollPolicy() {
        assertEquals(
            VulkanGpuFrameTimer.QueryPollDecision.RETRY,
            VulkanGpuFrameTimer.queryPollDecision(
                VulkanGpuFrameTimer.QUERY_RESULT_SUCCESS,
                false,
                VulkanGpuFrameTimer.MAX_UNAVAILABLE_POLLS - 1
            )
        );
        assertEquals(
            VulkanGpuFrameTimer.QueryPollDecision.DISABLE,
            VulkanGpuFrameTimer.queryPollDecision(
                VulkanGpuFrameTimer.QUERY_RESULT_SUCCESS,
                false,
                VulkanGpuFrameTimer.MAX_UNAVAILABLE_POLLS
            )
        );
    }

    @Test
    void abortedOpenSlotCanBeResetWithoutBeingReused() {
        VulkanGpuFrameTimer.QueryRingState state =
            new VulkanGpuFrameTimer.QueryRingState(4);

        state.begin(0);
        assertTrue(state.hasActiveFrame());
        assertTrue(state.isPending(0));
        assertTrue(state.abortActive());
        assertFalse(state.hasActiveFrame());
        assertTrue(state.isPending(0));
        assertFalse(state.abortActive());

        state.reset();
        assertFalse(state.isPending(0));
        assertEquals(0, state.nextSlot());
    }

    @Test
    void ringTracksUnavailablePollsAndClearsResolvedSlots() {
        VulkanGpuFrameTimer.QueryRingState state =
            new VulkanGpuFrameTimer.QueryRingState(2);

        state.begin(0);
        assertEquals(0, state.finishActive());
        assertEquals(1, state.noteUnavailable(0));
        assertEquals(2, state.noteUnavailable(0));

        state.resolve(0);
        assertFalse(state.isPending(0));
        state.begin(1);
        assertEquals(1, state.finishActive());
    }

    private static MemoryBudgetManager managerWithStagingLimit(long limit) {
        long[] ram = categories(1_024L);
        long[] vram = categories(1_024L);
        ram[MemoryCategory.STAGING.ordinal()] = limit;
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

    private static Object field(Object owner, String name)
        throws ReflectiveOperationException {
        Class<?> fieldType = switch (name) {
            case "queryResultsArena" -> BudgetedNativeArena.class;
            case "queryResults" -> LongBuffer.class;
            case "rejectedDevices" ->
                VulkanGpuFrameTimer.RejectedDeviceGate.class;
            case "budgetLease" -> long.class;
            default -> throw new NoSuchFieldException(name);
        };
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
            owner.getClass(),
            MethodHandles.lookup()
        );
        VarHandle field = lookup.findVarHandle(
            owner.getClass(),
            name,
            fieldType
        );
        return field.get(owner);
    }
}
