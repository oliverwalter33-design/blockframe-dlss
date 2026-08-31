package de.morau.blockframe.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EngineCapabilitiesTest {
    @Test
    void mapsVulkanFeaturesLimitsAndExplicitBdaState() {
        EngineCapabilities.DeviceSnapshot info = deviceInfo(
            "Vulkan",
            new EngineCapabilities.FeatureSnapshot(true, true, false, true, true, true, true)
        );

        EngineCapabilities capabilities = EngineCapabilities.fromSnapshot(info, true, true);

        assertEquals(EngineCapabilities.Backend.VULKAN, capabilities.backend());
        assertEquals("Test GPU", capabilities.deviceName());
        assertTrue(capabilities.drawIndirect());
        assertTrue(capabilities.multiDrawIndirect());
        assertTrue(capabilities.multiDrawDirect());
        assertTrue(capabilities.shaderDrawParameters());
        assertTrue(capabilities.nonZeroFirstInstance());
        assertTrue(capabilities.persistentMapping());
        assertFalse(capabilities.compute());
        assertTrue(capabilities.bufferDeviceAddressAvailable());
        assertTrue(capabilities.bufferDeviceAddressEnabled());
        assertEquals(16_384, capabilities.maxTextureSize());
        assertEquals(256, capabilities.minUniformOffsetAlignment());
        assertEquals(2_048, capabilities.maxMultiDrawDirectDrawCount());
        assertEquals(2.5F, capabilities.timestampPeriod());
    }

    @Test
    void openGlNeverClaimsComputeOrVulkanBda() {
        EngineCapabilities.DeviceSnapshot info = deviceInfo(
            "OpenGL",
            new EngineCapabilities.FeatureSnapshot(true, false, true, false, false, false, false)
        );

        EngineCapabilities capabilities = EngineCapabilities.fromSnapshot(info, true, true);

        assertEquals(EngineCapabilities.Backend.OPENGL, capabilities.backend());
        assertTrue(capabilities.multiDrawDirect());
        assertFalse(capabilities.compute());
        assertFalse(capabilities.bufferDeviceAddressAvailable());
        assertFalse(capabilities.bufferDeviceAddressEnabled());
    }

    @Test
    void enabledBdaRequiresAvailabilityAndUnknownBackendStaysConservative() {
        EngineCapabilities vulkan = EngineCapabilities.fromSnapshot(
            deviceInfo("Vulkan", EngineCapabilities.FeatureSnapshot.disabled()),
            false,
            true
        );
        EngineCapabilities unknown = EngineCapabilities.fromSnapshot(
            deviceInfo(
                "Experimental",
                new EngineCapabilities.FeatureSnapshot(true, true, true, true, true, true, true)
            ),
            true,
            true
        );

        assertFalse(vulkan.bufferDeviceAddressEnabled());
        assertEquals(EngineCapabilities.Backend.UNKNOWN, unknown.backend());
        assertFalse(unknown.compute());
        assertFalse(unknown.bufferDeviceAddressAvailable());
        assertEquals(EngineCapabilities.unknown(), EngineCapabilities.fromSnapshot(null, true, true));
    }

    private static EngineCapabilities.DeviceSnapshot deviceInfo(
        String backend,
        EngineCapabilities.FeatureSnapshot features
    ) {
        return new EngineCapabilities.DeviceSnapshot(
            backend,
            "Test GPU",
            "Test Vendor",
            "Test Driver",
            2.5F,
            features,
            new EngineCapabilities.LimitSnapshot(
                16,
                256,
                16_384,
                8L * 1024 * 1024 * 1024,
                2_048,
                8
            )
        );
    }
}
