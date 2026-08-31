package de.morau.blockframe.vulkan;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import de.morau.nvidiadlss.nativebridge.StreamlineFeatureRequirements;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.EXTMemoryBudget;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/**
 * Thin Vulkan-facing adapter for {@link DlssVulkanCapabilityNegotiator}.
 *
 * <p>The returned values come from the selected physical device. This class
 * does not inspect vendor names or infer support from a GPU model.
 */
public final class VulkanDeviceCapabilityProbe {
    private VulkanDeviceCapabilityProbe() {
    }

    public static DeviceAvailability query(VulkanPhysicalDevice physicalDevice) {
        return query(
            physicalDevice,
            DlssVulkanCapabilityNegotiator.DEVICE_EXTENSION_CANDIDATES
        );
    }

    public static DeviceAvailability query(
        VulkanPhysicalDevice physicalDevice,
        Collection<String> requiredDeviceExtensions
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(
            requiredDeviceExtensions,
            "requiredDeviceExtensions"
        );

        Set<String> extensions = new LinkedHashSet<>();
        Set<String> candidates = new LinkedHashSet<>(
            DlssVulkanCapabilityNegotiator.DEVICE_EXTENSION_CANDIDATES
        );
        candidates.addAll(requiredDeviceExtensions);
        for (String candidate : candidates) {
            if (physicalDevice.hasDeviceExtension(candidate)) {
                extensions.add(candidate);
            }
        }
        boolean memoryBudgetExtensionAdvertised = false;
        try {
            memoryBudgetExtensionAdvertised =
                physicalDevice.hasDeviceExtension(
                EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Optional diagnostics must not affect Vulkan device creation.
        }
        DeviceFaultAvailability deviceFaultAvailability =
            detectDeviceFaultExtension(physicalDevice);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features vulkan12Features =
                VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Features vulkan11Features =
                VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            vulkan11Features.pNext(vulkan12Features.address());
            VkPhysicalDeviceFeatures2 features =
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            features.pNext(vulkan11Features.address());
            VkPhysicalDeviceFaultFeaturesEXT deviceFaultFeatures = null;
            if (deviceFaultAvailability.extensionSupported()) {
                try {
                    deviceFaultFeatures =
                        VkPhysicalDeviceFaultFeaturesEXT
                            .calloc(stack)
                            .sType$Default();
                    deviceFaultFeatures.pNext(vulkan11Features.address());
                    features.pNext(deviceFaultFeatures.address());
                } catch (
                    RuntimeException | LinkageError | OutOfMemoryError error
                ) {
                    deviceFaultFeatures = null;
                    features.pNext(vulkan11Features.address());
                    deviceFaultAvailability =
                        DeviceFaultAvailability.queryFailed(
                            failureReason("feature-struct", error)
                        );
                }
            }

            try {
                queryFeatures(physicalDevice, features);
                if (deviceFaultFeatures != null) {
                    deviceFaultAvailability =
                        DeviceFaultAvailability.queried(
                            deviceFaultFeatures.deviceFault(),
                            deviceFaultFeatures.deviceFaultVendorBinary()
                        );
                }
            } catch (
                RuntimeException | LinkageError | OutOfMemoryError error
            ) {
                if (deviceFaultFeatures == null) {
                    throw error;
                }
                features.pNext(vulkan11Features.address());
                queryFeatures(physicalDevice, features);
                deviceFaultAvailability =
                    DeviceFaultAvailability.queryFailed(
                        failureReason("features2", error)
                    );
            }
            return new DeviceAvailability(
                extensions,
                new DlssVulkanCapabilityNegotiator.DeviceFeatureSupport(
                    vulkan12Features.timelineSemaphore(),
                    vulkan12Features.descriptorIndexing(),
                    vulkan12Features.bufferDeviceAddress(),
                    vulkan12Features.shaderFloat16(),
                    features.features()
                        .shaderStorageImageWriteWithoutFormat()
                ),
                physicalDevice.vkPhysicalDeviceProperties().apiVersion(),
                physicalDevice.vkPhysicalDeviceProperties().vendorID(),
                physicalDevice.vkPhysicalDeviceProperties().deviceID(),
                physicalDevice.vkPhysicalDeviceDriverProperties().driverID(),
                physicalDevice.vkPhysicalDeviceProperties().driverVersion(),
                physicalDevice.vkPhysicalDeviceDriverProperties().driverNameString(),
                physicalDevice.vkPhysicalDeviceDriverProperties().driverInfoString(),
                memoryBudgetExtensionAdvertised,
                deviceFaultAvailability,
                new OpaqueSolidIndirectAvailability(
                    features.features().multiDrawIndirect(),
                    vulkan11Features.shaderDrawParameters(),
                    vulkan12Features.drawIndirectCount(),
                    features.features().drawIndirectFirstInstance(),
                    physicalDevice.vkPhysicalDeviceProperties()
                        .limits()
                        .maxStorageBufferRange(),
                    physicalDevice.vkPhysicalDeviceProperties()
                        .limits()
                        .maxTexelBufferElements(),
                    physicalDevice.vkPhysicalDeviceProperties()
                        .limits()
                        .maxDrawIndirectCount(),
                    ""
                )
            );
        }
    }

    private static DeviceFaultAvailability detectDeviceFaultExtension(
        VulkanPhysicalDevice physicalDevice
    ) {
        try {
            return physicalDevice.hasDeviceExtension(
                    EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME
                )
                ? DeviceFaultAvailability.extensionAdvertised()
                : DeviceFaultAvailability.unavailable(
                    "extension-not-supported"
                );
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            return DeviceFaultAvailability.unavailable(
                failureReason("extension-query", error)
            );
        }
    }

    private static void queryFeatures(
        VulkanPhysicalDevice physicalDevice,
        VkPhysicalDeviceFeatures2 features
    ) {
        VK12.vkGetPhysicalDeviceFeatures2(
            physicalDevice.vkPhysicalDevice(),
            features
        );
    }

    private static String failureReason(String operation, Throwable error) {
        return operation
            + "-failed:"
            + error.getClass().getSimpleName();
    }

    public record DeviceAvailability(
        Set<String> extensions,
        DlssVulkanCapabilityNegotiator.DeviceFeatureSupport features,
        int apiVersion,
        int vendorId,
        int deviceId,
        int driverId,
        int driverVersion,
        String driverName,
        String driverInfo,
        boolean memoryBudgetExtensionAdvertised,
        DeviceFaultAvailability deviceFaultAvailability,
        OpaqueSolidIndirectAvailability opaqueSolidIndirectAvailability
    ) {
        public DeviceAvailability(
            Set<String> extensions,
            DlssVulkanCapabilityNegotiator.DeviceFeatureSupport features,
            int apiVersion,
            int vendorId,
            int deviceId,
            int driverId,
            int driverVersion,
            String driverName,
            String driverInfo,
            boolean memoryBudgetExtensionAdvertised
        ) {
            this(
                extensions,
                features,
                apiVersion,
                vendorId,
                deviceId,
                driverId,
                driverVersion,
                driverName,
                driverInfo,
                memoryBudgetExtensionAdvertised,
                DeviceFaultAvailability.unavailable("not-queried"),
                OpaqueSolidIndirectAvailability.unavailable("not-queried")
            );
        }

        public DeviceAvailability {
            Objects.requireNonNull(extensions, "extensions");
            extensions =
                Collections.unmodifiableSet(new LinkedHashSet<>(extensions));
            features = Objects.requireNonNull(features, "features");
            driverName = Objects.requireNonNull(driverName, "driverName");
            driverInfo = Objects.requireNonNull(driverInfo, "driverInfo");
            deviceFaultAvailability = Objects.requireNonNull(
                deviceFaultAvailability,
                "deviceFaultAvailability"
            );
            opaqueSolidIndirectAvailability = Objects.requireNonNull(
                opaqueSolidIndirectAvailability,
                "opaqueSolidIndirectAvailability"
            );
        }

        public DlssVulkanCapabilityNegotiator.DeviceSelection negotiate(
            StreamlineFeatureRequirements.RequirementUnion requirements,
            int apiVersion
        ) {
            return DlssVulkanCapabilityNegotiator.selectDeviceCapabilities(
                this.extensions,
                this.features,
                requirements,
                apiVersion
            );
        }
    }

    public record DeviceFaultAvailability(
        boolean extensionSupported,
        boolean deviceFault,
        boolean deviceFaultVendorBinary,
        String unavailableReason
    ) {
        public DeviceFaultAvailability {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
        }

        public static DeviceFaultAvailability unavailable(String reason) {
            return new DeviceFaultAvailability(false, false, false, reason);
        }

        private static DeviceFaultAvailability extensionAdvertised() {
            return new DeviceFaultAvailability(true, false, false, "");
        }

        private static DeviceFaultAvailability queryFailed(String reason) {
            return new DeviceFaultAvailability(true, false, false, reason);
        }

        private static DeviceFaultAvailability queried(
            boolean deviceFault,
            boolean deviceFaultVendorBinary
        ) {
            return new DeviceFaultAvailability(
                true,
                deviceFault,
                deviceFaultVendorBinary,
                deviceFault ? "" : "feature-not-supported"
            );
        }
    }

    public record OpaqueSolidIndirectAvailability(
        boolean multiDrawIndirect,
        boolean shaderDrawParameters,
        boolean drawIndirectCount,
        boolean drawIndirectFirstInstance,
        int maxStorageBufferRange,
        int maxTexelBufferElements,
        int maxDrawIndirectCount,
        String unavailableReason
    ) {
        public OpaqueSolidIndirectAvailability {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
        }

        public boolean complete() {
            return this.multiDrawIndirect
                && this.shaderDrawParameters
                && this.drawIndirectCount
                && this.drawIndirectFirstInstance
                && Integer.toUnsignedLong(
                    this.maxStorageBufferRange
                ) >= 5_242_880L
                && Integer.toUnsignedLong(
                    this.maxTexelBufferElements
                ) >= 32_768L
                && Integer.toUnsignedLong(
                    this.maxDrawIndirectCount
                ) >= 16_384L;
        }

        public static OpaqueSolidIndirectAvailability unavailable(
            String reason
        ) {
            return new OpaqueSolidIndirectAvailability(
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                reason
            );
        }
    }
}
