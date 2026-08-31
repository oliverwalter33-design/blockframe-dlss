package de.morau.blockframe.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.morau.blockframe.core.diagnostics.PhysicalMemoryTelemetry;
import org.junit.jupiter.api.Test;

class VulkanMemoryBudgetProbeTest {
    /** Vulkan's stable VK_MEMORY_HEAP_DEVICE_LOCAL_BIT value. */
    private static final int DEVICE_LOCAL = 0x00000001;

    @Test
    void sumsOnlyDeviceLocalHeapsWithDriverHeadroomSemantics() {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.reset();
        accumulator.addHeap(10_000L, DEVICE_LOCAL, 8_000L, 3_000L);
        accumulator.addHeap(4_000L, 0, 3_000L, 2_000L);
        accumulator.addHeap(20_000L, DEVICE_LOCAL, 18_000L, 5_000L);

        PhysicalMemoryTelemetry.DeviceMeasurement measurement =
            accumulator.finish();

        assertEquals(30_000L, measurement.heapBytes());
        assertEquals(26_000L, measurement.budgetBytes());
        assertEquals(8_000L, measurement.usageBytes());
        assertEquals(18_000L, measurement.headroomBytes());
        assertEquals(2, measurement.deviceLocalHeapCount());
    }

    @Test
    void overBudgetUsageRemainsVisibleWithZeroDerivedHeadroom() {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.reset();
        accumulator.addHeap(10_000L, DEVICE_LOCAL, 8_000L, 9_000L);

        PhysicalMemoryTelemetry.DeviceMeasurement measurement =
            accumulator.finish();

        assertEquals(9_000L, measurement.usageBytes());
        assertEquals(0L, measurement.headroomBytes());
        assertEquals(1, measurement.deviceLocalHeapCount());
    }

    @Test
    void resetRemovesThePreviousDeviceGenerationValues() {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.addHeap(10_000L, DEVICE_LOCAL, 8_000L, 2_000L);

        accumulator.reset();
        PhysicalMemoryTelemetry.DeviceMeasurement measurement =
            accumulator.finish();

        assertEquals(0L, measurement.heapBytes());
        assertEquals(0L, measurement.budgetBytes());
        assertEquals(0L, measurement.usageBytes());
        assertEquals(0L, measurement.headroomBytes());
        assertEquals(0, measurement.deviceLocalHeapCount());
    }

    @Test
    void warmAggregationsReuseTheProbeOwnedMeasurement() {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.addHeap(10_000L, DEVICE_LOCAL, 8_000L, 2_000L);
        PhysicalMemoryTelemetry.DeviceMeasurement first =
            accumulator.finish();

        accumulator.reset();
        accumulator.addHeap(20_000L, DEVICE_LOCAL, 18_000L, 7_000L);
        PhysicalMemoryTelemetry.DeviceMeasurement second =
            accumulator.finish();

        assertSame(first, second);
        assertEquals(20_000L, second.heapBytes());
        assertEquals(18_000L, second.budgetBytes());
        assertEquals(7_000L, second.usageBytes());
        assertEquals(11_000L, second.headroomBytes());
    }

    @Test
    void invalidLocalDriverValuesFailBeforePublication() {
        assertInvalidLocal(0L, 1L, 0L);
        assertInvalidLocal(10L, 0L, 0L);
        assertInvalidLocal(10L, 11L, 0L);
        assertInvalidLocal(10L, 10L, -1L);
    }

    @Test
    void nonLocalHeapValuesAreIgnoredRatherThanValidatedAsVram() {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.reset();

        accumulator.addHeap(-1L, 0, -1L, -1L);

        assertEquals(0, accumulator.finish().deviceLocalHeapCount());
    }

    @Test
    void aggregateOverflowFailsClosed() {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.reset();
        accumulator.addHeap(
            Long.MAX_VALUE,
            DEVICE_LOCAL,
            Long.MAX_VALUE,
            0L
        );

        assertThrows(
            ArithmeticException.class,
            () -> accumulator.addHeap(
                1L,
                DEVICE_LOCAL,
                1L,
                0L
            )
        );
    }

    private static void assertInvalidLocal(
        long heap,
        long budget,
        long usage
    ) {
        VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator accumulator =
            new VulkanMemoryBudgetProbe.DeviceLocalHeapAccumulator();
        accumulator.reset();
        assertThrows(
            IllegalStateException.class,
            () -> accumulator.addHeap(
                heap,
                DEVICE_LOCAL,
                budget,
                usage
            )
        );
    }
}
