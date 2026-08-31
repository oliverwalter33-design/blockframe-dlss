package de.morau.blockframe.vulkan;

import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.BUFFER_DEVICE_ADDRESS_FEATURE;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.DESCRIPTOR_INDEXING_FEATURE;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.EXT_BUFFER_DEVICE_ADDRESS;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.EXT_DESCRIPTOR_INDEXING;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.KHR_BUFFER_DEVICE_ADDRESS;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.KHR_SHADER_FLOAT16_INT8;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.KHR_TIMELINE_SEMAPHORE;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.NVX_BINARY_IMPORT;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.NVX_IMAGE_VIEW_HANDLE;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.PUSH_DESCRIPTOR;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.SHADER_FLOAT16_FEATURE;
import static de.morau.blockframe.vulkan.DlssVulkanCapabilityNegotiator.TIMELINE_SEMAPHORE_FEATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.nvidiadlss.nativebridge.StreamlineFeatureRequirements;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DlssVulkanCapabilityNegotiatorTest {
    @Test
    void vulkan12CorePromotionAvoidsEnablingPromotedInstanceNames() {
        var requirements = union(
            Set.of(
                DlssVulkanCapabilityNegotiator
                    .EXTERNAL_SEMAPHORE_CAPABILITIES,
                DlssVulkanCapabilityNegotiator
                    .PHYSICAL_DEVICE_PROPERTIES_2,
                DlssVulkanCapabilityNegotiator
                    .EXTERNAL_MEMORY_CAPABILITIES,
                "VK_EXT_debug_utils"
            ),
            Set.of(),
            Set.of()
        );

        var selection =
            DlssVulkanCapabilityNegotiator.selectInstanceExtensions(
                Set.of("VK_EXT_debug_utils"),
                requirements,
                DlssVulkanCapabilityNegotiator.VULKAN_API_1_2
            );

        assertTrue(selection.complete());
        assertEquals(
            Set.of("VK_EXT_debug_utils"),
            selection.enabledExtensions()
        );
        assertEquals(
            Set.of(
                DlssVulkanCapabilityNegotiator
                    .EXTERNAL_SEMAPHORE_CAPABILITIES,
                DlssVulkanCapabilityNegotiator
                    .PHYSICAL_DEVICE_PROPERTIES_2,
                DlssVulkanCapabilityNegotiator
                    .EXTERNAL_MEMORY_CAPABILITIES
            ),
            selection.coreSatisfiedExtensions()
        );
    }

    @Test
    void exactDlssNisUnionKeepsExtBdaAndPromotesOnlyKhrAlias() {
        var requirements = union(
            Set.of(),
            Set.of(
                PUSH_DESCRIPTOR,
                NVX_BINARY_IMPORT,
                NVX_IMAGE_VIEW_HANDLE,
                EXT_BUFFER_DEVICE_ADDRESS,
                KHR_BUFFER_DEVICE_ADDRESS,
                EXT_DESCRIPTOR_INDEXING,
                KHR_TIMELINE_SEMAPHORE,
                KHR_SHADER_FLOAT16_INT8
            ),
            Set.of(
                TIMELINE_SEMAPHORE_FEATURE,
                DESCRIPTOR_INDEXING_FEATURE,
                BUFFER_DEVICE_ADDRESS_FEATURE,
                SHADER_FLOAT16_FEATURE
            )
        );

        var device =
            DlssVulkanCapabilityNegotiator.selectDeviceCapabilities(
                Set.of(
                    PUSH_DESCRIPTOR,
                    NVX_BINARY_IMPORT,
                    NVX_IMAGE_VIEW_HANDLE,
                    EXT_BUFFER_DEVICE_ADDRESS
                ),
                new DlssVulkanCapabilityNegotiator.DeviceFeatureSupport(
                    true,
                    true,
                    true,
                    true
                ),
                requirements,
                DlssVulkanCapabilityNegotiator.VULKAN_API_1_2
            );
        var report = DlssVulkanCapabilityNegotiator.report(
            new DlssVulkanCapabilityNegotiator.InstanceSelection(
                Set.of(),
                Set.of(),
                Set.of()
            ),
            device
        );

        assertTrue(device.complete());
        assertEquals(
            Set.of(
                PUSH_DESCRIPTOR,
                NVX_BINARY_IMPORT,
                NVX_IMAGE_VIEW_HANDLE,
                EXT_BUFFER_DEVICE_ADDRESS
            ),
            device.enabledExtensions()
        );
        assertTrue(
            device.enabledExtensions().contains(EXT_BUFFER_DEVICE_ADDRESS)
        );
        assertFalse(
            device.enabledExtensions().contains(KHR_BUFFER_DEVICE_ADDRESS)
        );
        assertEquals(
            Set.of(
                TIMELINE_SEMAPHORE_FEATURE,
                DESCRIPTOR_INDEXING_FEATURE,
                BUFFER_DEVICE_ADDRESS_FEATURE,
                SHADER_FLOAT16_FEATURE
            ),
            device.enabledFeatures()
        );
        assertTrue(report.safeToEnableRuntimeRequirements());
        assertTrue(report.streamlineRequirementsVerified());
        assertEquals(
            DlssVulkanCapabilityNegotiator.CapabilityStatus
                .RUNTIME_REQUIREMENTS_MET,
            report.status()
        );
    }

    @Test
    void dynamicExtensionAndMissingFeatureFailClosedExplicitly() {
        var requirements = union(
            Set.of(),
            Set.of(PUSH_DESCRIPTOR, "VK_NV_runtime_added"),
            Set.of(SHADER_FLOAT16_FEATURE)
        );
        var device =
            DlssVulkanCapabilityNegotiator.selectDeviceCapabilities(
                Set.of(PUSH_DESCRIPTOR),
                new DlssVulkanCapabilityNegotiator.DeviceFeatureSupport(
                    true,
                    true,
                    true,
                    false
                ),
                requirements,
                DlssVulkanCapabilityNegotiator.VULKAN_API_1_2
            );

        assertFalse(device.complete());
        assertEquals(
            Set.of("VK_NV_runtime_added"),
            device.missingExtensions()
        );
        assertEquals(
            Set.of(SHADER_FLOAT16_FEATURE),
            device.missingFeatures()
        );
    }

    @Test
    void unknownVulkanFeatureNameIsNeverInferred() {
        var requirements = union(
            Set.of(),
            Set.of(),
            Set.of("futureFeature")
        );
        var device =
            DlssVulkanCapabilityNegotiator.selectDeviceCapabilities(
                Set.of(),
                new DlssVulkanCapabilityNegotiator.DeviceFeatureSupport(
                    true,
                    true,
                    true,
                    true
                ),
                requirements,
                DlssVulkanCapabilityNegotiator.VULKAN_API_1_2
            );

        assertFalse(device.complete());
        assertEquals(
            Set.of("futureFeature"),
            device.unsupportedFeatures()
        );
    }

    private static StreamlineFeatureRequirements.RequirementUnion union(
        Set<String> instanceExtensions,
        Set<String> deviceExtensions,
        Set<String> features12
    ) {
        return new StreamlineFeatureRequirements.RequirementUnion(
            instanceExtensions,
            deviceExtensions,
            features12,
            Set.of()
        );
    }
}
