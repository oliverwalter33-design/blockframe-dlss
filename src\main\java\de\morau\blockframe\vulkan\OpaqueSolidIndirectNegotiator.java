package de.morau.blockframe.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/** Conditional Vulkan feature negotiation for the exact GPU-scene slice. */
public final class OpaqueSolidIndirectNegotiator {
    public static final VulkanFeature DRAW_INDIRECT_COUNT_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "drawIndirectCount",
            VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT
        );
    public static final VulkanFeature DRAW_INDIRECT_FIRST_INSTANCE_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "drawIndirectFirstInstance",
            VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE
        );

    private OpaqueSolidIndirectNegotiator() {
    }

    public static Selection configure(
        boolean requested,
        VulkanDeviceCapabilityProbe.OpaqueSolidIndirectAvailability
            availability,
        Set<VulkanFeature> enabledFeatures
    ) {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(enabledFeatures, "enabledFeatures");
        if (!requested) {
            return Selection.disabled(
                false,
                availability,
                "disabled-by-policy"
            );
        }
        if (!availability.complete()) {
            return Selection.disabled(
                true,
                availability,
                missingReason(availability)
            );
        }
        boolean countPresent = enabledFeatures.contains(
            DRAW_INDIRECT_COUNT_FEATURE
        );
        boolean firstInstancePresent = enabledFeatures.contains(
            DRAW_INDIRECT_FIRST_INSTANCE_FEATURE
        );
        try {
            enabledFeatures.add(DRAW_INDIRECT_COUNT_FEATURE);
            enabledFeatures.add(DRAW_INDIRECT_FIRST_INSTANCE_FEATURE);
            return new Selection(
                true,
                availability,
                true,
                countPresent,
                firstInstancePresent,
                "enabled"
            );
        } catch (RuntimeException | LinkageError error) {
            if (!countPresent) {
                enabledFeatures.remove(DRAW_INDIRECT_COUNT_FEATURE);
            }
            if (!firstInstancePresent) {
                enabledFeatures.remove(
                    DRAW_INDIRECT_FIRST_INSTANCE_FEATURE
                );
            }
            return Selection.disabled(
                true,
                availability,
                "feature-set-mutation-failed:"
                    + error.getClass().getSimpleName()
            );
        }
    }

    private static String missingReason(
        VulkanDeviceCapabilityProbe.OpaqueSolidIndirectAvailability
            availability
    ) {
        if (!availability.multiDrawIndirect()) {
            return "multiDrawIndirect-not-supported";
        }
        if (!availability.shaderDrawParameters()) {
            return "shaderDrawParameters-not-supported";
        }
        if (!availability.drawIndirectCount()) {
            return "drawIndirectCount-not-supported";
        }
        if (!availability.drawIndirectFirstInstance()) {
            return "drawIndirectFirstInstance-not-supported";
        }
        if (
            Integer.toUnsignedLong(
                availability.maxStorageBufferRange()
            ) < 5_242_880L
        ) {
            return "maxStorageBufferRange-too-small";
        }
        if (
            Integer.toUnsignedLong(
                availability.maxTexelBufferElements()
            ) < 32_768L
        ) {
            return "maxTexelBufferElements-too-small";
        }
        if (
            Integer.toUnsignedLong(
                availability.maxDrawIndirectCount()
            ) < 16_384L
        ) {
            return "maxDrawIndirectCount-too-small";
        }
        return availability.unavailableReason().isBlank()
            ? "capability-incomplete"
            : availability.unavailableReason();
    }

    public record Selection(
        boolean requested,
        VulkanDeviceCapabilityProbe.OpaqueSolidIndirectAvailability
            availability,
        boolean enabled,
        boolean countFeatureAlreadyPresent,
        boolean firstInstanceFeatureAlreadyPresent,
        String reason
    ) {
        public Selection {
            availability = Objects.requireNonNull(
                availability,
                "availability"
            );
            reason = Objects.requireNonNull(reason, "reason");
        }

        private static Selection disabled(
            boolean requested,
            VulkanDeviceCapabilityProbe.OpaqueSolidIndirectAvailability
                availability,
            String reason
        ) {
            return new Selection(
                requested,
                availability,
                false,
                false,
                false,
                reason
            );
        }
    }
}
