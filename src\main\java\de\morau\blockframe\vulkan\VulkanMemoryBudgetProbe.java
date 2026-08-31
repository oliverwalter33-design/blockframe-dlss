package de.morau.blockframe.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.diagnostics.PhysicalMemoryTelemetry;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;

/**
 * Borrows a live physical-device handle to query VK_EXT_memory_budget.
 *
 * <p>The probe owns no Vulkan object or persistent native allocation. Its
 * lifetime is bounded by the exact {@link VulkanDevice} generation. Minecraft
 * requests Vulkan 1.2; Vulkan permits enumerated physical-device-level
 * extension queries at 1.1+ without adding the extension to the logical
 * device's enabled-extension set.
 */
public final class VulkanMemoryBudgetProbe
    implements PhysicalMemoryTelemetry.DeviceProbe {
    private final VkPhysicalDevice physicalDevice;
    private final Thread ownerThread;
    private final DeviceLocalHeapAccumulator accumulator =
        new DeviceLocalHeapAccumulator();

    public VulkanMemoryBudgetProbe(VulkanDevice device) {
        Objects.requireNonNull(device, "device");
        this.physicalDevice = Objects.requireNonNull(
            device.vkDevice().getPhysicalDevice(),
            "physicalDevice"
        );
        this.ownerThread = Thread.currentThread();
    }

    @Override
    public PhysicalMemoryTelemetry.DeviceMeasurement query() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "Vulkan memory-budget query is render-thread confined"
            );
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT budget =
                VkPhysicalDeviceMemoryBudgetPropertiesEXT
                    .calloc(stack)
                    .sType$Default();
            VkPhysicalDeviceMemoryProperties2 properties =
                VkPhysicalDeviceMemoryProperties2
                    .calloc(stack)
                    .sType$Default()
                    .pNext(budget);
            VK12.vkGetPhysicalDeviceMemoryProperties2(
                this.physicalDevice,
                properties
            );
            return aggregate(
                properties.memoryProperties(),
                budget,
                this.accumulator
            );
        }
    }

    static PhysicalMemoryTelemetry.DeviceMeasurement aggregate(
        VkPhysicalDeviceMemoryProperties properties,
        VkPhysicalDeviceMemoryBudgetPropertiesEXT budget,
        DeviceLocalHeapAccumulator accumulator
    ) {
        int heapCount = properties.memoryHeapCount();
        if (heapCount < 0 || heapCount > VK10.VK_MAX_MEMORY_HEAPS) {
            throw new IllegalStateException(
                "invalid Vulkan memory heap count " + heapCount
            );
        }

        accumulator.reset();
        for (int index = 0; index < heapCount; index++) {
            accumulator.addHeap(
                properties.memoryHeaps(index).size(),
                properties.memoryHeaps(index).flags(),
                budget.heapBudget(index),
                budget.heapUsage(index)
            );
        }
        return accumulator.finish();
    }

    /**
     * Reusable primitive aggregator shared by the live query, tests and the
     * driver-free benchmark. It owns no native memory.
     */
    public static final class DeviceLocalHeapAccumulator {
        private final PhysicalMemoryTelemetry.DeviceMeasurement measurement =
            new PhysicalMemoryTelemetry.DeviceMeasurement(0L, 0L, 0L, 0L, 0);
        private long heapBytes;
        private long budgetBytes;
        private long usageBytes;
        private long headroomBytes;
        private int localHeapCount;

        public void reset() {
            this.heapBytes = 0L;
            this.budgetBytes = 0L;
            this.usageBytes = 0L;
            this.headroomBytes = 0L;
            this.localHeapCount = 0;
        }

        public void addHeap(
            long heap,
            int flags,
            long processBudget,
            long processUsage
        ) {
            if ((flags & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) {
                return;
            }
            if (
                heap <= 0L
                    || processBudget <= 0L
                    || processBudget > heap
                    || processUsage < 0L
            ) {
                throw new IllegalStateException(
                    "invalid device-local Vulkan memory values"
                );
            }
            this.heapBytes = Math.addExact(this.heapBytes, heap);
            this.budgetBytes = Math.addExact(
                this.budgetBytes,
                processBudget
            );
            this.usageBytes = Math.addExact(
                this.usageBytes,
                processUsage
            );
            this.headroomBytes = Math.addExact(
                this.headroomBytes,
                processUsage >= processBudget
                    ? 0L
                    : processBudget - processUsage
            );
            this.localHeapCount++;
        }

        public PhysicalMemoryTelemetry.DeviceMeasurement finish() {
            return this.measurement.update(
                this.heapBytes,
                this.budgetBytes,
                this.usageBytes,
                this.headroomBytes,
                this.localHeapCount
            );
        }
    }
}
