package de.morau.blockframe.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

class VulkanDeviceFaultNegotiatorTest {
    @Test
    void configurationOffDoesNotPublishRequirements() {
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var selection = VulkanDeviceFaultNegotiator.configure(
            false,
            available(true),
            extensions,
            features
        );

        assertFalse(selection.requested());
        assertFalse(selection.enabled());
        assertFalse(selection.eligible());
        assertEquals(
            "disabled-by-configuration",
            selection.unavailableReason()
        );
        assertTrue(extensions.isEmpty());
        assertTrue(features.isEmpty());
    }

    @Test
    void missingFatalHookRetainsRequestedStateButPublishesNothing() {
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var selection = VulkanDeviceFaultNegotiator.configure(
            true,
            false,
            available(true),
            extensions,
            features
        );

        assertTrue(selection.requested());
        assertFalse(selection.captureHookReady());
        assertFalse(selection.enabled());
        assertEquals(
            VulkanDeviceFaultNegotiator.CAPTURE_HOOK_UNAVAILABLE,
            selection.unavailableReason()
        );
        assertTrue(extensions.isEmpty());
        assertTrue(features.isEmpty());
    }

    @Test
    void unsupportedExtensionFailsOpenWithoutMutation() {
        var availability =
            VulkanDeviceCapabilityProbe.DeviceFaultAvailability.unavailable(
                "extension-not-supported"
            );
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var selection = VulkanDeviceFaultNegotiator.configure(
            true,
            availability,
            extensions,
            features
        );

        assertTrue(selection.requested());
        assertFalse(selection.extensionSupported());
        assertFalse(selection.enabled());
        assertEquals(
            "extension-not-supported",
            selection.unavailableReason()
        );
        assertTrue(extensions.isEmpty());
        assertTrue(features.isEmpty());
    }

    @Test
    void unsupportedMainFeatureFailsOpenWithoutMutation() {
        var availability =
            new VulkanDeviceCapabilityProbe.DeviceFaultAvailability(
                true,
                false,
                true,
                "feature-not-supported"
            );
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var selection = VulkanDeviceFaultNegotiator.configure(
            true,
            availability,
            extensions,
            features
        );

        assertTrue(selection.extensionSupported());
        assertFalse(selection.featureSupported());
        assertTrue(selection.vendorBinarySupported());
        assertFalse(selection.enabled());
        assertTrue(extensions.isEmpty());
        assertTrue(features.isEmpty());
    }

    @Test
    void supportedMainFeaturePublishesOnlyMainFeature() {
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var selection = VulkanDeviceFaultNegotiator.configure(
            true,
            available(true),
            extensions,
            features
        );

        assertTrue(selection.eligible());
        assertTrue(selection.enabled());
        assertTrue(selection.vendorBinarySupported());
        assertEquals(
            Set.of(VulkanDeviceFaultNegotiator.DEVICE_FAULT_EXTENSION),
            extensions
        );
        assertEquals(
            Set.of(VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE),
            features
        );
        assertEquals(
            VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT,
            VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE.offset()
        );
        assertFalse(
            features.stream().anyMatch(
                feature ->
                    feature.offset()
                        == VkPhysicalDeviceFaultFeaturesEXT
                            .DEVICEFAULTVENDORBINARY
            )
        );
    }

    @Test
    void duplicateConfigurationKeepsOneExtensionAndFeature() {
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var first = VulkanDeviceFaultNegotiator.configure(
            true,
            available(false),
            extensions,
            features
        );
        var second = VulkanDeviceFaultNegotiator.configure(
            true,
            available(false),
            extensions,
            features
        );

        assertTrue(first.enabled());
        assertTrue(second.enabled());
        assertEquals(1, extensions.size());
        assertEquals(1, features.size());
        assertSame(
            VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE,
            features.iterator().next()
        );
    }

    @Test
    void featureMutationFailureRollsBackOnlyOwnedExtension() {
        Set<String> extensions = new LinkedHashSet<>();
        VulkanFeature foreignFeature = new VulkanFeature(
            new VulkanPNextStruct(
                VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
                VkPhysicalDeviceVulkan12Features.SIZEOF
            ),
            "descriptorIndexing",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING
        );
        Set<VulkanFeature> features = new ThrowingAddSet<>(
            Set.of(foreignFeature)
        );

        var selection = VulkanDeviceFaultNegotiator.configure(
            true,
            available(false),
            extensions,
            features
        );

        assertFalse(selection.enabled());
        assertTrue(selection.rollbackComplete());
        assertTrue(
            selection
                .unavailableReason()
                .startsWith("device-create-set-mutation-failed:")
        );
        assertTrue(extensions.isEmpty());
        assertEquals(Set.of(foreignFeature), features);
    }

    @Test
    void mutationFailureDoesNotRemovePreexistingDeviceFaultExtension() {
        Set<String> extensions = new LinkedHashSet<>(
            Set.of(VulkanDeviceFaultNegotiator.DEVICE_FAULT_EXTENSION)
        );
        Set<VulkanFeature> features = new ThrowingAddSet<>(Set.of());

        var selection = VulkanDeviceFaultNegotiator.configure(
            true,
            available(false),
            extensions,
            features
        );

        assertFalse(selection.enabled());
        assertEquals(
            Set.of(VulkanDeviceFaultNegotiator.DEVICE_FAULT_EXTENSION),
            extensions
        );
        assertTrue(features.isEmpty());
    }

    @Test
    void pNextInsertionIsExactOnceAndPreservesForeignEntry() {
        VulkanPNextStruct foreignStruct = new VulkanPNextStruct(
            VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
            VkPhysicalDeviceVulkan12Features.SIZEOF
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 root =
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            long foreignAddress =
                foreignStruct.findOrCreateStructInPNextChain(root, stack);

            VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE.set(
                root,
                true,
                stack
            );
            long firstAddress =
                VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURES_STRUCT
                    .findStructInPNextChain(root.address());
            VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURE.set(
                root,
                true,
                stack
            );
            long secondAddress =
                VulkanDeviceFaultNegotiator.DEVICE_FAULT_FEATURES_STRUCT
                    .findStructInPNextChain(root.address());

            assertEquals(firstAddress, secondAddress);
            assertEquals(
                foreignAddress,
                foreignStruct.findStructInPNextChain(root.address())
            );
            assertEquals(
                1,
                countSType(
                    root.pNext(),
                    EXTDeviceFault
                        .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT
                )
            );
            assertEquals(
                1,
                MemoryUtil.memGetInt(
                    firstAddress
                        + VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT
                )
            );
            assertEquals(
                0,
                MemoryUtil.memGetInt(
                    firstAddress
                        + VkPhysicalDeviceFaultFeaturesEXT
                            .DEVICEFAULTVENDORBINARY
                )
            );
        }
    }

    private static int countSType(long first, int expected) {
        int count = 0;
        long current = first;
        while (current != 0L) {
            if (MemoryUtil.memGetInt(current) == expected) {
                count++;
            }
            current = MemoryUtil.memGetAddress(
                current + VkPhysicalDeviceFaultFeaturesEXT.PNEXT
            );
        }
        return count;
    }

    private static VulkanDeviceCapabilityProbe.DeviceFaultAvailability
        available(boolean vendorBinary) {
        return new VulkanDeviceCapabilityProbe.DeviceFaultAvailability(
            true,
            true,
            vendorBinary,
            ""
        );
    }

    private static final class ThrowingAddSet<T> extends AbstractSet<T> {
        private final Set<T> delegate;

        private ThrowingAddSet(Set<T> initial) {
            this.delegate = new LinkedHashSet<>(initial);
        }

        @Override
        public Iterator<T> iterator() {
            return this.delegate.iterator();
        }

        @Override
        public int size() {
            return this.delegate.size();
        }

        @Override
        public boolean contains(Object value) {
            return this.delegate.contains(value);
        }

        @Override
        public boolean add(T value) {
            throw new IllegalStateException("injected mutation failure");
        }

        @Override
        public boolean remove(Object value) {
            return this.delegate.remove(value);
        }
    }
}
