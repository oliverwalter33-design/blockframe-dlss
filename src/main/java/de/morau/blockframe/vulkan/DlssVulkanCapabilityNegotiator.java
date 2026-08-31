package de.morau.blockframe.vulkan;

import de.morau.nvidiadlss.nativebridge.StreamlineFeatureRequirements;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Negotiates the exact Vulkan requirements returned by Streamline 2.12.
 *
 * <p>Core promotion is intentionally an explicit allowlist. Unknown Vulkan
 * feature names fail closed; runtime-reported extension names are enabled only
 * when the selected implementation actually advertises them.
 */
public final class DlssVulkanCapabilityNegotiator {
    public static final int VULKAN_API_1_1 = apiVersion(1, 1, 0);
    public static final int VULKAN_API_1_2 = apiVersion(1, 2, 0);

    public static final String EXTERNAL_SEMAPHORE_CAPABILITIES =
        "VK_KHR_external_semaphore_capabilities";
    public static final String PHYSICAL_DEVICE_PROPERTIES_2 =
        "VK_KHR_get_physical_device_properties2";
    public static final String EXTERNAL_MEMORY_CAPABILITIES =
        "VK_KHR_external_memory_capabilities";

    public static final String PUSH_DESCRIPTOR = "VK_KHR_push_descriptor";
    public static final String NVX_BINARY_IMPORT = "VK_NVX_binary_import";
    public static final String NVX_IMAGE_VIEW_HANDLE =
        "VK_NVX_image_view_handle";
    public static final String EXT_BUFFER_DEVICE_ADDRESS =
        "VK_EXT_buffer_device_address";
    public static final String KHR_BUFFER_DEVICE_ADDRESS =
        "VK_KHR_buffer_device_address";
    public static final String EXT_DESCRIPTOR_INDEXING =
        "VK_EXT_descriptor_indexing";
    public static final String KHR_TIMELINE_SEMAPHORE =
        "VK_KHR_timeline_semaphore";
    public static final String KHR_SHADER_FLOAT16_INT8 =
        "VK_KHR_shader_float16_int8";

    public static final String TIMELINE_SEMAPHORE_FEATURE =
        "timelineSemaphore";
    public static final String DESCRIPTOR_INDEXING_FEATURE =
        "descriptorIndexing";
    public static final String BUFFER_DEVICE_ADDRESS_FEATURE =
        "bufferDeviceAddress";
    public static final String SHADER_FLOAT16_FEATURE = "shaderFloat16";
    public static final String
        SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE =
            "shaderStorageImageWriteWithoutFormat";

    public static final Set<String> DEVICE_EXTENSION_CANDIDATES = orderedSet(
        PUSH_DESCRIPTOR,
        NVX_BINARY_IMPORT,
        NVX_IMAGE_VIEW_HANDLE,
        EXT_BUFFER_DEVICE_ADDRESS,
        KHR_BUFFER_DEVICE_ADDRESS,
        EXT_DESCRIPTOR_INDEXING,
        KHR_TIMELINE_SEMAPHORE,
        KHR_SHADER_FLOAT16_INT8
    );

    private static final Map<String, Integer> INSTANCE_CORE_PROMOTIONS =
        promotionMap(
            EXTERNAL_SEMAPHORE_CAPABILITIES,
            VULKAN_API_1_1,
            PHYSICAL_DEVICE_PROPERTIES_2,
            VULKAN_API_1_1,
            EXTERNAL_MEMORY_CAPABILITIES,
            VULKAN_API_1_1
        );
    // VK_EXT_buffer_device_address was deprecated by the KHR replacement,
    // which was promoted to Vulkan 1.2.  When the Vulkan 1.2 core feature is
    // used, neither extension name is requested; in particular, enabling the
    // EXT name beside VkPhysicalDeviceVulkan12Features::bufferDeviceAddress is
    // forbidden by VUID-VkDeviceCreateInfo-pNext-04748.
    private static final Map<String, Integer> DEVICE_CORE_PROMOTIONS =
        promotionMap(
            EXT_BUFFER_DEVICE_ADDRESS,
            VULKAN_API_1_2,
            KHR_BUFFER_DEVICE_ADDRESS,
            VULKAN_API_1_2,
            EXT_DESCRIPTOR_INDEXING,
            VULKAN_API_1_2,
            KHR_TIMELINE_SEMAPHORE,
            VULKAN_API_1_2,
            KHR_SHADER_FLOAT16_INT8,
            VULKAN_API_1_2
        );

    private DlssVulkanCapabilityNegotiator() {
    }

    public static InstanceSelection selectInstanceExtensions(
        Collection<String> availableExtensions,
        StreamlineFeatureRequirements.RequirementUnion requirements,
        int apiVersion
    ) {
        Objects.requireNonNull(availableExtensions, "availableExtensions");
        Objects.requireNonNull(requirements, "requirements");
        return selectExtensions(
            requirements.vulkanInstanceExtensions(),
            availableExtensions,
            INSTANCE_CORE_PROMOTIONS,
            apiVersion
        );
    }

    public static DeviceSelection selectDeviceCapabilities(
        Collection<String> availableExtensions,
        DeviceFeatureSupport availableFeatures,
        StreamlineFeatureRequirements.RequirementUnion requirements,
        int apiVersion
    ) {
        Objects.requireNonNull(availableExtensions, "availableExtensions");
        Objects.requireNonNull(availableFeatures, "availableFeatures");
        Objects.requireNonNull(requirements, "requirements");

        InstanceSelection extensionSelection = selectExtensions(
            requirements.vulkanDeviceExtensions(),
            availableExtensions,
            DEVICE_CORE_PROMOTIONS,
            apiVersion
        );
        Set<String> enabledFeatures = new LinkedHashSet<>();
        Set<String> missingFeatures = new LinkedHashSet<>();
        Set<String> unsupportedFeatures = new LinkedHashSet<>();
        for (String feature : requirements.vulkanFeatures12()) {
            Boolean supported = switch (feature) {
                case TIMELINE_SEMAPHORE_FEATURE ->
                    availableFeatures.timelineSemaphore();
                case DESCRIPTOR_INDEXING_FEATURE ->
                    availableFeatures.descriptorIndexing();
                case BUFFER_DEVICE_ADDRESS_FEATURE ->
                    availableFeatures.bufferDeviceAddress();
                case SHADER_FLOAT16_FEATURE ->
                    availableFeatures.shaderFloat16();
                default -> null;
            };
            if (supported == null) {
                unsupportedFeatures.add(feature);
                continue;
            }
            if (
                Integer.compareUnsigned(apiVersion, VULKAN_API_1_2) < 0
            ) {
                // The Mojang integration enables these through
                // VkPhysicalDeviceVulkan12Features.  Do not put that core
                // structure on a Vulkan 1.0/1.1 device-creation chain; an
                // extension-specific feature path would have to be negotiated
                // and represented separately.
                missingFeatures.add(feature);
                continue;
            }
            (supported ? enabledFeatures : missingFeatures).add(feature);
        }
        // Manual Streamline Vulkan integration leaves device creation to the
        // host. Streamline's setVulkanInfo path creates an internal shader
        // declaring StorageImageWriteWithoutFormat. The application targets
        // Vulkan 1.2, so publish the Vulkan 1.0 feature explicitly instead of
        // relying on the separate Vulkan 1.3 / format-feature-flags2 paths.
        // Requiring probed support here also makes an unsupported device fail
        // closed before Streamline can create that shader.
        (
            availableFeatures.shaderStorageImageWriteWithoutFormat()
                ? enabledFeatures
                : missingFeatures
        ).add(SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE);
        unsupportedFeatures.addAll(requirements.vulkanFeatures13());

        return new DeviceSelection(
            extensionSelection.enabledExtensions(),
            extensionSelection.coreSatisfiedExtensions(),
            extensionSelection.missingExtensions(),
            enabledFeatures,
            missingFeatures,
            unsupportedFeatures
        );
    }

    public static CapabilityReport report(
        InstanceSelection instanceSelection,
        DeviceSelection deviceSelection
    ) {
        Objects.requireNonNull(instanceSelection, "instanceSelection");
        Objects.requireNonNull(deviceSelection, "deviceSelection");

        boolean ready =
            instanceSelection.complete() && deviceSelection.complete();
        return new CapabilityReport(
            ready
                ? CapabilityStatus.RUNTIME_REQUIREMENTS_MET
                : CapabilityStatus.MISSING_RUNTIME_REQUIREMENTS,
            instanceSelection,
            deviceSelection,
            buildReason(ready, instanceSelection, deviceSelection)
        );
    }

    public enum CapabilityStatus {
        MISSING_RUNTIME_REQUIREMENTS,
        RUNTIME_REQUIREMENTS_MET
    }

    public record DeviceFeatureSupport(
        boolean timelineSemaphore,
        boolean descriptorIndexing,
        boolean bufferDeviceAddress,
        boolean shaderFloat16,
        boolean shaderStorageImageWriteWithoutFormat
    ) {
    }

    public record InstanceSelection(
        Set<String> enabledExtensions,
        Set<String> coreSatisfiedExtensions,
        Set<String> missingExtensions
    ) {
        public InstanceSelection {
            enabledExtensions = immutableCopy(enabledExtensions);
            coreSatisfiedExtensions = immutableCopy(
                coreSatisfiedExtensions
            );
            missingExtensions = immutableCopy(missingExtensions);
        }

        public boolean complete() {
            return this.missingExtensions.isEmpty();
        }
    }

    public record DeviceSelection(
        Set<String> enabledExtensions,
        Set<String> coreSatisfiedExtensions,
        Set<String> missingExtensions,
        Set<String> enabledFeatures,
        Set<String> missingFeatures,
        Set<String> unsupportedFeatures
    ) {
        public DeviceSelection {
            enabledExtensions = immutableCopy(enabledExtensions);
            coreSatisfiedExtensions = immutableCopy(
                coreSatisfiedExtensions
            );
            missingExtensions = immutableCopy(missingExtensions);
            enabledFeatures = immutableCopy(enabledFeatures);
            missingFeatures = immutableCopy(missingFeatures);
            unsupportedFeatures = immutableCopy(unsupportedFeatures);
        }

        public boolean complete() {
            return this.missingExtensions.isEmpty()
                && this.missingFeatures.isEmpty()
                && this.unsupportedFeatures.isEmpty();
        }
    }

    public record CapabilityReport(
        CapabilityStatus status,
        InstanceSelection instance,
        DeviceSelection device,
        String reason
    ) {
        public CapabilityReport {
            status = Objects.requireNonNull(status, "status");
            instance = Objects.requireNonNull(instance, "instance");
            device = Objects.requireNonNull(device, "device");
            reason = Objects.requireNonNull(reason, "reason");
        }

        public boolean safeToEnableRuntimeRequirements() {
            return this.status == CapabilityStatus.RUNTIME_REQUIREMENTS_MET;
        }

        public boolean safeToEnableStaticRequirements() {
            return this.safeToEnableRuntimeRequirements();
        }

        public boolean streamlineRequirementsVerified() {
            return true;
        }
    }

    private static InstanceSelection selectExtensions(
        Collection<String> required,
        Collection<String> available,
        Map<String, Integer> promotions,
        int apiVersion
    ) {
        Set<String> selected = new LinkedHashSet<>();
        Set<String> coreSatisfied = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String extension : required) {
            Integer promotedIn = promotions.get(extension);
            if (
                promotedIn != null
                    && Integer.compareUnsigned(apiVersion, promotedIn) >= 0
            ) {
                coreSatisfied.add(extension);
            } else if (available.contains(extension)) {
                selected.add(extension);
            } else {
                missing.add(extension);
            }
        }
        return new InstanceSelection(selected, coreSatisfied, missing);
    }

    private static String buildReason(
        boolean ready,
        InstanceSelection instance,
        DeviceSelection device
    ) {
        if (ready) {
            return "Authoritative Streamline 2.12 DLSS+NIS Vulkan "
                + "requirements and manual-host shader capability met"
                + promotedReason(instance, device)
                + ".";
        }

        List<String> reasons = new ArrayList<>();
        if (!instance.missingExtensions().isEmpty()) {
            reasons.add(
                "missing instance extensions "
                    + sorted(instance.missingExtensions())
            );
        }
        if (!device.missingExtensions().isEmpty()) {
            reasons.add(
                "missing device extensions "
                    + sorted(device.missingExtensions())
            );
        }
        if (!device.missingFeatures().isEmpty()) {
            reasons.add(
                "missing required Vulkan device features "
                    + sorted(device.missingFeatures())
            );
        }
        if (!device.unsupportedFeatures().isEmpty()) {
            reasons.add(
                "unsupported Vulkan feature names "
                    + sorted(device.unsupportedFeatures())
            );
        }
        return "Authoritative Streamline 2.12 DLSS+NIS Vulkan requirements not met: "
            + String.join("; ", reasons)
            + promotedReason(instance, device)
            + ".";
    }

    private static String promotedReason(
        InstanceSelection instance,
        DeviceSelection device
    ) {
        Set<String> promoted = new LinkedHashSet<>(
            instance.coreSatisfiedExtensions()
        );
        promoted.addAll(device.coreSatisfiedExtensions());
        return promoted.isEmpty()
            ? ""
            : "; core-promoted extensions " + sorted(promoted);
    }

    private static int apiVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    private static Set<String> immutableCopy(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Set<String> orderedSet(String... values) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, Integer> promotionMap(Object... entries) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], (Integer) entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> sorted(Collection<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted;
    }
}
