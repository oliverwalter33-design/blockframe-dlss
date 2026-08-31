package de.morau.blockframe.vulkan;

import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.VkPhysicalDeviceFaultFeaturesEXT;

/**
 * Selects and transactionally publishes optional {@code VK_EXT_device_fault}
 * device-create requirements.
 *
 * <p>This class owns only the extension and main {@code deviceFault} feature
 * entries that it adds to Mojang's still-mutable device-create sets. It never
 * requests {@code deviceFaultVendorBinary}.
 */
public final class VulkanDeviceFaultNegotiator {
    public static final String DEVICE_FAULT_EXTENSION =
        EXTDeviceFault.VK_EXT_DEVICE_FAULT_EXTENSION_NAME;
    public static final String DEVICE_FAULT_FEATURE_NAME = "deviceFault";

    public static final VulkanPNextStruct DEVICE_FAULT_FEATURES_STRUCT =
        new VulkanPNextStruct(
            EXTDeviceFault
                .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT,
            VkPhysicalDeviceFaultFeaturesEXT.SIZEOF
        );
    public static final VulkanFeature DEVICE_FAULT_FEATURE =
        new VulkanFeature(
            DEVICE_FAULT_FEATURES_STRUCT,
            DEVICE_FAULT_FEATURE_NAME,
            VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT
        );

    private static final String CONFIG_DISABLED =
        "disabled-by-configuration";
    private static final String EXTENSION_UNSUPPORTED =
        "extension-not-supported";
    private static final String FEATURE_UNSUPPORTED =
        "feature-not-supported";
    public static final String CAPTURE_HOOK_UNAVAILABLE =
        "create-loss-hook-unavailable";

    private VulkanDeviceFaultNegotiator() {
    }

    /**
     * Pure capability selection. No caller-owned collection is touched.
     */
    public static Selection select(
        boolean requested,
        VulkanDeviceCapabilityProbe.DeviceFaultAvailability availability
    ) {
        return select(requested, true, availability);
    }

    /**
     * Pure capability selection including the source-contract health of the
     * fatal device-loss capture hook.
     */
    public static Selection select(
        boolean requested,
        boolean captureHookReady,
        VulkanDeviceCapabilityProbe.DeviceFaultAvailability availability
    ) {
        VulkanDeviceCapabilityProbe.DeviceFaultAvailability safeAvailability =
            availability == null
                ? VulkanDeviceCapabilityProbe.DeviceFaultAvailability
                    .unavailable("availability-unavailable")
                : availability;
        if (!requested) {
            return Selection.unavailable(
                false,
                captureHookReady,
                safeAvailability,
                CONFIG_DISABLED
            );
        }
        if (!captureHookReady) {
            return Selection.unavailable(
                true,
                false,
                safeAvailability,
                CAPTURE_HOOK_UNAVAILABLE
            );
        }
        if (!safeAvailability.extensionSupported()) {
            return Selection.unavailable(
                true,
                true,
                safeAvailability,
                reasonOr(
                    safeAvailability.unavailableReason(),
                    EXTENSION_UNSUPPORTED
                )
            );
        }
        if (!safeAvailability.deviceFault()) {
            return Selection.unavailable(
                true,
                true,
                safeAvailability,
                reasonOr(
                    safeAvailability.unavailableReason(),
                    FEATURE_UNSUPPORTED
                )
            );
        }
        return new Selection(
            true,
            true,
            true,
            safeAvailability.deviceFaultVendorBinary(),
            true,
            true,
            false,
            true,
            ""
        );
    }

    /**
     * Selects and publishes the optional requirements. Any supported mutation
     * failure disables Device Fault and rolls back only entries absent before
     * this call. No exception from optional negotiation escapes.
     */
    public static Selection configure(
        boolean requested,
        VulkanDeviceCapabilityProbe.DeviceFaultAvailability availability,
        Set<String> enabledExtensions,
        Set<VulkanFeature> enabledFeatures
    ) {
        return configure(
            requested,
            true,
            availability,
            enabledExtensions,
            enabledFeatures
        );
    }

    public static Selection configure(
        boolean requested,
        boolean captureHookReady,
        VulkanDeviceCapabilityProbe.DeviceFaultAvailability availability,
        Set<String> enabledExtensions,
        Set<VulkanFeature> enabledFeatures
    ) {
        VulkanDeviceCapabilityProbe.DeviceFaultAvailability safeAvailability =
            availability == null
                ? VulkanDeviceCapabilityProbe.DeviceFaultAvailability
                    .unavailable("availability-unavailable")
                : availability;
        Selection selection = select(
            requested,
            captureHookReady,
            safeAvailability
        );
        if (!selection.eligible()) {
            return selection;
        }

        boolean extensionPresentBefore = false;
        boolean featurePresentBefore = false;
        boolean extensionPresenceKnown = false;
        boolean featurePresenceKnown = false;
        boolean extensionAddAttempted = false;
        boolean featureAddAttempted = false;
        try {
            Objects.requireNonNull(
                enabledExtensions,
                "enabledExtensions"
            );
            Objects.requireNonNull(enabledFeatures, "enabledFeatures");

            extensionPresentBefore =
                enabledExtensions.contains(DEVICE_FAULT_EXTENSION);
            extensionPresenceKnown = true;
            featurePresentBefore =
                enabledFeatures.contains(DEVICE_FAULT_FEATURE);
            featurePresenceKnown = true;

            if (!extensionPresentBefore) {
                extensionAddAttempted = true;
                enabledExtensions.add(DEVICE_FAULT_EXTENSION);
            }
            if (!featurePresentBefore) {
                featureAddAttempted = true;
                enabledFeatures.add(DEVICE_FAULT_FEATURE);
            }
            if (
                !enabledExtensions.contains(DEVICE_FAULT_EXTENSION)
                    || !enabledFeatures.contains(DEVICE_FAULT_FEATURE)
            ) {
                throw new IllegalStateException(
                    "device-fault requirements were not retained"
                );
            }
            return selection.asEnabled();
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            boolean featureRollback = rollbackOwnedAdd(
                enabledFeatures,
                DEVICE_FAULT_FEATURE,
                featurePresenceKnown,
                featurePresentBefore,
                featureAddAttempted
            );
            boolean extensionRollback = rollbackOwnedAdd(
                enabledExtensions,
                DEVICE_FAULT_EXTENSION,
                extensionPresenceKnown,
                extensionPresentBefore,
                extensionAddAttempted
            );
            return selection.asMutationFailure(
                failureReason(error),
                featureRollback && extensionRollback
            );
        }
    }

    private static boolean rollbackOwnedAdd(
        Set<?> target,
        Object value,
        boolean presenceKnown,
        boolean presentBefore,
        boolean addAttempted
    ) {
        if (
            target == null
                || !presenceKnown
                || presentBefore
                || !addAttempted
        ) {
            return true;
        }
        try {
            target.remove(value);
            return !target.contains(value);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {
            return false;
        }
    }

    private static String failureReason(Throwable error) {
        return "device-create-set-mutation-failed:"
            + error.getClass().getSimpleName();
    }

    private static String reasonOr(String candidate, String fallback) {
        return candidate == null || candidate.isBlank()
            ? fallback
            : candidate;
    }

    public record Selection(
        boolean requested,
        boolean extensionSupported,
        boolean featureSupported,
        boolean vendorBinarySupported,
        boolean captureHookReady,
        boolean eligible,
        boolean enabled,
        boolean rollbackComplete,
        String unavailableReason
    ) {
        public Selection {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
        }

        private static Selection unavailable(
            boolean requested,
            boolean captureHookReady,
            VulkanDeviceCapabilityProbe.DeviceFaultAvailability availability,
            String reason
        ) {
            return new Selection(
                requested,
                availability.extensionSupported(),
                availability.deviceFault(),
                availability.deviceFaultVendorBinary(),
                captureHookReady,
                false,
                false,
                true,
                reason
            );
        }

        private Selection asEnabled() {
            return new Selection(
                this.requested,
                this.extensionSupported,
                this.featureSupported,
                this.vendorBinarySupported,
                this.captureHookReady,
                this.eligible,
                true,
                true,
                ""
            );
        }

        private Selection asMutationFailure(
            String reason,
            boolean rollbackComplete
        ) {
            return new Selection(
                this.requested,
                this.extensionSupported,
                this.featureSupported,
                this.vendorBinarySupported,
                this.captureHookReady,
                this.eligible,
                false,
                rollbackComplete,
                reason
            );
        }
    }
}
