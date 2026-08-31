package de.morau.blockframe.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import org.junit.jupiter.api.Test;

class VulkanRuntimeInfoTest {
    @Test
    void preservesActualIdsAndFormatsVulkanVersion() {
        VulkanRuntimeInfo info = new VulkanRuntimeInfo(
            true,
            (1 << 22) | (4 << 12) | 341,
            0x10de,
            0x2684,
            4,
            123,
            "NVIDIA",
            "driver",
            Set.of("VK_KHR_buffer_device_address"),
            true,
            true,
            true
        );

        assertEquals("1.4.341", info.apiVersionString());
        assertEquals("10de:2684", info.deviceKey());
        assertEquals(4, info.driverId());
        assertEquals(Set.of("VK_KHR_buffer_device_address"), info.observedDlssExtensionCandidates());
        assertEquals(true, info.memoryBudgetExtensionAdvertised());
    }

    @Test
    void unavailableStateCannotRetainStaleVulkanCapabilities() {
        VulkanRuntimeInfo info = new VulkanRuntimeInfo(
            false,
            1,
            2,
            3,
            4,
            5,
            "stale",
            "stale",
            Set.of("stale"),
            true,
            true,
            true
        );

        assertEquals("N/A", info.apiVersionString());
        assertFalse(info.descriptorIndexing());
        assertFalse(info.bufferDeviceAddress());
        assertFalse(info.memoryBudgetExtensionAdvertised());
        assertEquals(Set.of(), info.observedDlssExtensionCandidates());
    }
}
