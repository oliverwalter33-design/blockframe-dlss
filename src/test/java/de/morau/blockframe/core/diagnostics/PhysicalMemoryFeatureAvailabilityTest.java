package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhysicalMemoryFeatureAvailabilityTest {
    @Test
    void unsupportedRamAndOpenGlHaveNoEffectiveMeasurement() {
        PhysicalMemoryFeatureAvailability state =
            PhysicalMemoryFeatureAvailability.from(
                PhysicalMemoryTelemetry.RamStatus.UNSUPPORTED,
                PhysicalMemoryTelemetry.DeviceStatus.NOT_VULKAN
            );

        assertFalse(state.supported());
        assertFalse(state.effective());
        assertTrue(state.fallback());
        assertEquals(
            "ram-unsupported_vram-not-vulkan",
            state.reason()
        );
    }

    @Test
    void failedRamQueryAndMissingVulkanExtensionAreExplicitFallbacks() {
        PhysicalMemoryFeatureAvailability state =
            PhysicalMemoryFeatureAvailability.from(
                PhysicalMemoryTelemetry.RamStatus.QUERY_FAILED,
                PhysicalMemoryTelemetry.DeviceStatus
                    .EXTENSION_NOT_ADVERTISED
            );

        assertTrue(state.supported());
        assertFalse(state.effective());
        assertTrue(state.fallback());
        assertEquals(
            "ram-query-failed_vram-extension-not-advertised",
            state.reason()
        );
    }

    @Test
    void RamOnlyIsCompleteOnOpenGlButPartialOnVulkan() {
        PhysicalMemoryFeatureAvailability openGl =
            PhysicalMemoryFeatureAvailability.from(
                PhysicalMemoryTelemetry.RamStatus.AVAILABLE,
                PhysicalMemoryTelemetry.DeviceStatus.NOT_VULKAN
            );
        PhysicalMemoryFeatureAvailability vulkan =
            PhysicalMemoryFeatureAvailability.from(
                PhysicalMemoryTelemetry.RamStatus.AVAILABLE,
                PhysicalMemoryTelemetry.DeviceStatus
                    .EXTENSION_NOT_ADVERTISED
            );

        assertTrue(openGl.supported());
        assertTrue(openGl.effective());
        assertFalse(openGl.fallback());
        assertTrue(vulkan.supported());
        assertTrue(vulkan.effective());
        assertTrue(vulkan.fallback());
    }

    @Test
    void VramOnlyIsEffectiveButKeepsTheMissingRamFallback() {
        PhysicalMemoryFeatureAvailability state =
            PhysicalMemoryFeatureAvailability.from(
                PhysicalMemoryTelemetry.RamStatus.UNSUPPORTED,
                PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE
            );

        assertTrue(state.supported());
        assertTrue(state.effective());
        assertTrue(state.fallback());
    }

    @Test
    void BothAvailableChannelsAreFullyEffective() {
        PhysicalMemoryFeatureAvailability state =
            PhysicalMemoryFeatureAvailability.from(
                PhysicalMemoryTelemetry.RamStatus.AVAILABLE,
                PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE
            );

        assertTrue(state.supported());
        assertTrue(state.effective());
        assertFalse(state.fallback());
    }
}
