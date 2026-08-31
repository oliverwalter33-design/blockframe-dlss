package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Properties;

/**
 * V16-independent device-create contract for the complete native terrain
 * backend.
 *
 * <p>The Vulkan-facing probe runs before {@code vkCreateDevice}. Selection is
 * pure until {@link #configure} transactionally publishes only feature bits
 * that the selected physical device reported. Mojang remains owner of the
 * extension set, feature set, Features2/pNext allocation and device call.
 * Vulkan 1.2 promotes the baseline features used here to core, so this
 * revision intentionally requests no additional device extension names.</p>
 *
 * <p>Compute pipelines, storage buffers and indirect buffers are Vulkan core
 * facilities rather than individual {@code VkPhysicalDeviceFeatures} bits.
 * Their availability is therefore attested from the API version, queue
 * topology and relevant limits. Buffer device address is recorded but is not
 * a Foundation-V1 requirement and is never enabled by this class.</p>
 */
public final class NativeTerrainDeviceCapabilityNegotiator {
    public static final long MIN_STORAGE_BUFFER_RANGE = 16L * 1024L * 1024L;
    public static final long MIN_DRAW_INDIRECT_COUNT = 16_384L;
    public static final long MIN_COMPUTE_WORK_GROUP_COUNT_X = 65_535L;
    public static final int MIN_COMPUTE_WORK_GROUP_INVOCATIONS = 128;
    public static final int MIN_COMPUTE_WORK_GROUP_SIZE_X = 128;
    public static final int MIN_DESCRIPTOR_STORAGE_BUFFERS = 8;
    public static final int MIN_DESCRIPTOR_SAMPLED_IMAGES = 256;
    public static final int MIN_BOUND_DESCRIPTOR_SETS = 4;
    public static final int MIN_PUSH_CONSTANT_BYTES = 128;
    public static final int MIN_MEMORY_ALLOCATION_COUNT = 4_096;
    public static final long MAX_STORAGE_BUFFER_OFFSET_ALIGNMENT = 256L;

    public static final VulkanFeature MULTI_DRAW_INDIRECT_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "multiDrawIndirect",
            VkPhysicalDeviceFeatures.MULTIDRAWINDIRECT
        );
    public static final VulkanFeature SHADER_DRAW_PARAMETERS_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK11_FEATURES_STRUCT,
            "shaderDrawParameters",
            VkPhysicalDeviceVulkan11Features.SHADERDRAWPARAMETERS
        );
    public static final VulkanFeature DRAW_INDIRECT_COUNT_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "drawIndirectCount",
            VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT
        );
    public static final VulkanFeature DESCRIPTOR_INDEXING_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "descriptorIndexing",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING
        );
    public static final VulkanFeature
        SAMPLED_IMAGE_NON_UNIFORM_INDEXING_FEATURE =
            new VulkanFeature(
                VulkanBackend.VK12_FEATURES_STRUCT,
                "shaderSampledImageArrayNonUniformIndexing",
                VkPhysicalDeviceVulkan12Features
                    .SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING
            );
    public static final VulkanFeature
        SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE =
            new VulkanFeature(
                VulkanBackend.VK12_FEATURES_STRUCT,
                "descriptorBindingSampledImageUpdateAfterBind",
                VkPhysicalDeviceVulkan12Features
                    .DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND
            );
    public static final VulkanFeature DESCRIPTOR_PARTIALLY_BOUND_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "descriptorBindingPartiallyBound",
            VkPhysicalDeviceVulkan12Features
                .DESCRIPTORBINDINGPARTIALLYBOUND
        );
    public static final VulkanFeature
        VARIABLE_DESCRIPTOR_COUNT_FEATURE =
            new VulkanFeature(
                VulkanBackend.VK12_FEATURES_STRUCT,
                "descriptorBindingVariableDescriptorCount",
                VkPhysicalDeviceVulkan12Features
                    .DESCRIPTORBINDINGVARIABLEDESCRIPTORCOUNT
            );
    public static final VulkanFeature RUNTIME_DESCRIPTOR_ARRAY_FEATURE =
        new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "runtimeDescriptorArray",
            VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY
        );

    /**
     * Stable publication order makes rollback and source-contract evidence
     * deterministic. The list does not reference any former V16 class.
     */
    public static final List<VulkanFeature> BASELINE_FEATURES = List.of(
        MULTI_DRAW_INDIRECT_FEATURE,
        SHADER_DRAW_PARAMETERS_FEATURE,
        DRAW_INDIRECT_COUNT_FEATURE,
        DESCRIPTOR_INDEXING_FEATURE,
        SAMPLED_IMAGE_NON_UNIFORM_INDEXING_FEATURE,
        SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE,
        DESCRIPTOR_PARTIALLY_BOUND_FEATURE,
        VARIABLE_DESCRIPTOR_COUNT_FEATURE,
        RUNTIME_DESCRIPTOR_ARRAY_FEATURE
    );
    public static final Set<String> BASELINE_DEVICE_EXTENSIONS = Set.of();

    private NativeTerrainDeviceCapabilityNegotiator() {
    }

    /**
     * Queries only immutable physical-device state. Any uncertainty is
     * represented as an unavailable probe instead of escaping into Mojang's
     * device creation.
     */
    public static Probe probe(VulkanPhysicalDevice physicalDevice) {
        if (physicalDevice == null) {
            return Probe.unavailable("physical-device-unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features vulkan12 =
                VkPhysicalDeviceVulkan12Features
                    .calloc(stack)
                    .sType$Default();
            VkPhysicalDeviceVulkan11Features vulkan11 =
                VkPhysicalDeviceVulkan11Features
                    .calloc(stack)
                    .sType$Default();
            vulkan11.pNext(vulkan12.address());
            VkPhysicalDeviceFeatures2 features =
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            features.pNext(vulkan11.address());
            VK12.vkGetPhysicalDeviceFeatures2(
                physicalDevice.vkPhysicalDevice(),
                features
            );

            VkPhysicalDeviceVulkan12Properties vulkan12Properties =
                VkPhysicalDeviceVulkan12Properties
                    .calloc(stack)
                    .sType$Default();
            VkPhysicalDeviceProperties2 properties =
                VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            properties.pNext(vulkan12Properties.address());
            VK12.vkGetPhysicalDeviceProperties2(
                physicalDevice.vkPhysicalDevice(),
                properties
            );

            QueueCapabilities queues = queueCapabilities(physicalDevice);
            VkPhysicalDeviceLimits limits = properties.properties().limits();
            int apiVersion = properties.properties().apiVersion();
            boolean coreBufferFacilities =
                apiVersion >= VK12.VK_API_VERSION_1_2;
            return new Probe(
                apiVersion,
                queues,
                coreBufferFacilities && queues.computeAvailable(),
                coreBufferFacilities,
                coreBufferFacilities,
                features.features().multiDrawIndirect(),
                vulkan12.drawIndirectCount(),
                vulkan11.shaderDrawParameters(),
                vulkan12.descriptorIndexing(),
                vulkan12.shaderSampledImageArrayNonUniformIndexing(),
                vulkan12.descriptorBindingSampledImageUpdateAfterBind(),
                vulkan12.descriptorBindingPartiallyBound(),
                vulkan12.descriptorBindingVariableDescriptorCount(),
                vulkan12.runtimeDescriptorArray(),
                vulkan12.bufferDeviceAddress(),
                new Limits(
                    Integer.toUnsignedLong(
                        limits.maxStorageBufferRange()
                    ),
                    limits.minStorageBufferOffsetAlignment(),
                    Integer.toUnsignedLong(limits.maxDrawIndirectCount()),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupCount(0)
                    ),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupCount(1)
                    ),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupCount(2)
                    ),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupInvocations()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupSize(0)
                    ),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupSize(1)
                    ),
                    Integer.toUnsignedLong(
                        limits.maxComputeWorkGroupSize(2)
                    ),
                    Integer.toUnsignedLong(
                        limits.maxPerStageDescriptorStorageBuffers()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxDescriptorSetStorageBuffers()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxPerStageDescriptorSampledImages()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxDescriptorSetSampledImages()
                    ),
                    Integer.toUnsignedLong(
                        vulkan12Properties
                            .maxUpdateAfterBindDescriptorsInAllPools()
                    ),
                    Integer.toUnsignedLong(
                        vulkan12Properties
                            .maxPerStageDescriptorUpdateAfterBindSampledImages()
                    ),
                    Integer.toUnsignedLong(
                        vulkan12Properties
                            .maxDescriptorSetUpdateAfterBindSampledImages()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxBoundDescriptorSets()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxPushConstantsSize()
                    ),
                    Integer.toUnsignedLong(
                        limits.maxMemoryAllocationCount()
                    ),
                    limits.nonCoherentAtomSize()
                ),
                ""
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            return Probe.unavailable(
                "capability-probe-failed:"
                    + error.getClass().getSimpleName()
            );
        }
    }

    /**
     * Pure selection; caller-owned device-create collections remain untouched.
     */
    public static Attestation select(
        long deviceGeneration,
        boolean requested,
        boolean vulkan,
        Probe probe
    ) {
        requirePositive(deviceGeneration, "deviceGeneration");
        Probe safeProbe = probe == null
            ? Probe.unavailable("probe-unavailable")
            : probe;
        if (!requested) {
            return Attestation.unavailable(
                deviceGeneration,
                false,
                vulkan,
                safeProbe,
                Status.NOT_REQUESTED,
                "disabled-by-configuration",
                true
            );
        }
        if (!vulkan) {
            return Attestation.unavailable(
                deviceGeneration,
                true,
                false,
                safeProbe,
                Status.UNAVAILABLE,
                "not-vulkan",
                true
            );
        }
        String reason = baselineUnavailableReason(safeProbe);
        if (!reason.isEmpty()) {
            return Attestation.unavailable(
                deviceGeneration,
                true,
                true,
                safeProbe,
                Status.UNAVAILABLE,
                reason,
                true
            );
        }
        return new Attestation(
            deviceGeneration,
            true,
            true,
            safeProbe,
            Status.ELIGIBLE,
            false,
            true,
            false,
            BASELINE_DEVICE_EXTENSIONS,
            featureNames(BASELINE_FEATURES),
            ""
        );
    }

    /**
     * Transactionally adds the exact baseline feature requirements to
     * Mojang's still-private mutable set. Entries that predated this call are
     * never removed. This method does not publish a device or retain native
     * pointers.
     */
    public static Attestation configure(
        long deviceGeneration,
        boolean requested,
        boolean vulkan,
        Probe probe,
        Set<String> enabledExtensions,
        Set<VulkanFeature> enabledFeatures
    ) {
        Attestation selected = select(
            deviceGeneration,
            requested,
            vulkan,
            probe
        );
        if (selected.status() != Status.ELIGIBLE) {
            return selected;
        }

        List<VulkanFeature> ownedAdds = new ArrayList<>();
        try {
            Objects.requireNonNull(
                enabledExtensions,
                "enabledExtensions"
            );
            Objects.requireNonNull(enabledFeatures, "enabledFeatures");
            if (
                !enabledExtensions.containsAll(
                    BASELINE_DEVICE_EXTENSIONS
                )
            ) {
                throw new IllegalStateException(
                    "core baseline unexpectedly requires extensions"
                );
            }
            for (VulkanFeature feature : BASELINE_FEATURES) {
                boolean present = enabledFeatures.contains(feature);
                if (!present) {
                    enabledFeatures.add(feature);
                    if (!enabledFeatures.contains(feature)) {
                        throw new IllegalStateException(
                            "feature-set-rejected-" + feature.name()
                        );
                    }
                    ownedAdds.add(feature);
                }
            }
            return selected.asPublished();
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            boolean rollbackComplete = rollback(
                enabledFeatures,
                ownedAdds
            );
            return selected.asMutationFailure(
                "device-create-set-mutation-failed:"
                    + error.getClass().getSimpleName(),
                rollbackComplete
            );
        }
    }

    private static boolean rollback(
        Set<VulkanFeature> enabledFeatures,
        List<VulkanFeature> ownedAdds
    ) {
        if (enabledFeatures == null) {
            return ownedAdds.isEmpty();
        }
        boolean complete = true;
        for (int index = ownedAdds.size() - 1; index >= 0; index--) {
            VulkanFeature feature = ownedAdds.get(index);
            try {
                enabledFeatures.remove(feature);
                complete &= !enabledFeatures.contains(feature);
            } catch (
                RuntimeException | LinkageError | OutOfMemoryError ignored
            ) {
                complete = false;
            }
        }
        return complete;
    }

    private static QueueCapabilities queueCapabilities(
        VulkanPhysicalDevice physicalDevice
    ) {
        IntIntPair graphics =
            physicalDevice.graphicsQueueFamilyAndIndex();
        IntIntPair compute =
            physicalDevice.computeQueueFamilyAndIndex();
        // VulkanDevice aliases compute to the combined graphics queue when
        // Mojang did not reserve a second queue. Attest that exact contract
        // rather than rejecting a valid single-queue implementation.
        if (compute == null) {
            compute = graphics;
        }
        return new QueueCapabilities(
            graphics == null ? -1 : graphics.firstInt(),
            graphics == null ? -1 : graphics.secondInt(),
            compute == null ? -1 : compute.firstInt(),
            compute == null ? -1 : compute.secondInt()
        );
    }

    private static String baselineUnavailableReason(Probe probe) {
        if (!probe.queryFailure().isEmpty()) {
            return probe.queryFailure();
        }
        if (probe.apiVersion() < VK12.VK_API_VERSION_1_2) {
            return "vulkan-1.2-not-supported";
        }
        if (!probe.queues().graphicsAvailable()) {
            return "graphics-queue-unavailable";
        }
        if (!probe.queues().computeAvailable()) {
            return "compute-queue-unavailable";
        }
        if (!probe.computeShader()) {
            return "compute-shader-unavailable";
        }
        if (!probe.storageBuffer()) {
            return "storage-buffer-unavailable";
        }
        if (!probe.indirectBuffer()) {
            return "indirect-buffer-unavailable";
        }
        if (!probe.multiDrawIndirect()) {
            return "multiDrawIndirect-not-supported";
        }
        if (!probe.drawIndirectCount()) {
            return "drawIndirectCount-not-supported";
        }
        if (!probe.shaderDrawParameters()) {
            return "shaderDrawParameters-not-supported";
        }
        if (!probe.descriptorIndexing()) {
            return "descriptorIndexing-not-supported";
        }
        if (!probe.sampledImageNonUniformIndexing()) {
            return "sampled-image-non-uniform-indexing-not-supported";
        }
        if (!probe.sampledImageUpdateAfterBind()) {
            return "sampled-image-update-after-bind-not-supported";
        }
        if (!probe.descriptorPartiallyBound()) {
            return "descriptor-partially-bound-not-supported";
        }
        if (!probe.variableDescriptorCount()) {
            return "variable-descriptor-count-not-supported";
        }
        if (!probe.runtimeDescriptorArray()) {
            return "runtime-descriptor-array-not-supported";
        }
        return probe.limits().baselineUnavailableReason();
    }

    private static Set<String> featureNames(
        List<VulkanFeature> features
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (VulkanFeature feature : features) {
            names.add(feature.name());
        }
        return Collections.unmodifiableSet(names);
    }

    private static boolean isPositivePowerOfTwo(long value) {
        return value > 0L && (value & (value - 1L)) == 0L;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public enum Status {
        AWAITING_NEGOTIATION,
        NOT_REQUESTED,
        ELIGIBLE,
        PUBLISHED,
        UNAVAILABLE,
        CLOSED
    }

    public record QueueCapabilities(
        int graphicsFamily,
        int graphicsIndex,
        int computeFamily,
        int computeIndex
    ) {
        public QueueCapabilities {
            validateQueuePair(
                graphicsFamily,
                graphicsIndex,
                "graphics"
            );
            validateQueuePair(computeFamily, computeIndex, "compute");
        }

        public boolean graphicsAvailable() {
            return this.graphicsFamily >= 0;
        }

        public boolean computeAvailable() {
            return this.computeFamily >= 0;
        }

        public boolean sharedGraphicsComputeFamily() {
            return this.graphicsAvailable()
                && this.computeAvailable()
                && this.graphicsFamily == this.computeFamily;
        }

        private static void validateQueuePair(
            int family,
            int index,
            String name
        ) {
            if (
                (family < 0 && index != -1)
                    || (family >= 0 && index < 0)
            ) {
                throw new IllegalArgumentException(
                    name + " queue identity is incomplete"
                );
            }
        }

        public static QueueCapabilities unavailable() {
            return new QueueCapabilities(-1, -1, -1, -1);
        }
    }

    public record Limits(
        long maxStorageBufferRange,
        long minStorageBufferOffsetAlignment,
        long maxDrawIndirectCount,
        long maxComputeWorkGroupCountX,
        long maxComputeWorkGroupCountY,
        long maxComputeWorkGroupCountZ,
        long maxComputeWorkGroupInvocations,
        long maxComputeWorkGroupSizeX,
        long maxComputeWorkGroupSizeY,
        long maxComputeWorkGroupSizeZ,
        long maxPerStageDescriptorStorageBuffers,
        long maxDescriptorSetStorageBuffers,
        long maxPerStageDescriptorSampledImages,
        long maxDescriptorSetSampledImages,
        long maxUpdateAfterBindDescriptorsInAllPools,
        long maxPerStageDescriptorUpdateAfterBindSampledImages,
        long maxDescriptorSetUpdateAfterBindSampledImages,
        long maxBoundDescriptorSets,
        long maxPushConstantsSize,
        long maxMemoryAllocationCount,
        long nonCoherentAtomSize
    ) {
        public Limits {
            if (
                maxStorageBufferRange < 0L
                    || minStorageBufferOffsetAlignment < 0L
                    || maxDrawIndirectCount < 0L
                    || maxComputeWorkGroupCountX < 0L
                    || maxComputeWorkGroupCountY < 0L
                    || maxComputeWorkGroupCountZ < 0L
                    || maxComputeWorkGroupInvocations < 0L
                    || maxComputeWorkGroupSizeX < 0L
                    || maxComputeWorkGroupSizeY < 0L
                    || maxComputeWorkGroupSizeZ < 0L
                    || maxPerStageDescriptorStorageBuffers < 0L
                    || maxDescriptorSetStorageBuffers < 0L
                    || maxPerStageDescriptorSampledImages < 0L
                    || maxDescriptorSetSampledImages < 0L
                    || maxUpdateAfterBindDescriptorsInAllPools < 0L
                    || maxPerStageDescriptorUpdateAfterBindSampledImages < 0
                    || maxDescriptorSetUpdateAfterBindSampledImages < 0
                    || maxBoundDescriptorSets < 0L
                    || maxPushConstantsSize < 0L
                    || maxMemoryAllocationCount < 0L
                    || nonCoherentAtomSize < 0L
            ) {
                throw new IllegalArgumentException(
                    "Vulkan limits must be unsigned"
                );
            }
        }

        public String baselineUnavailableReason() {
            if (this.maxStorageBufferRange < MIN_STORAGE_BUFFER_RANGE) {
                return "maxStorageBufferRange-too-small";
            }
            if (
                !isPositivePowerOfTwo(
                    this.minStorageBufferOffsetAlignment
                )
                    || this.minStorageBufferOffsetAlignment
                        > MAX_STORAGE_BUFFER_OFFSET_ALIGNMENT
            ) {
                return "minStorageBufferOffsetAlignment-unsupported";
            }
            if (this.maxDrawIndirectCount < MIN_DRAW_INDIRECT_COUNT) {
                return "maxDrawIndirectCount-too-small";
            }
            if (
                this.maxComputeWorkGroupCountX
                    < MIN_COMPUTE_WORK_GROUP_COUNT_X
                    || this.maxComputeWorkGroupCountY < 1L
                    || this.maxComputeWorkGroupCountZ < 1L
            ) {
                return "maxComputeWorkGroupCount-too-small";
            }
            if (
                this.maxComputeWorkGroupInvocations
                    < MIN_COMPUTE_WORK_GROUP_INVOCATIONS
                    || this.maxComputeWorkGroupSizeX
                        < MIN_COMPUTE_WORK_GROUP_SIZE_X
                    || this.maxComputeWorkGroupSizeY < 1
                    || this.maxComputeWorkGroupSizeZ < 1
            ) {
                return "maxComputeWorkGroupSize-too-small";
            }
            if (
                this.maxPerStageDescriptorStorageBuffers
                    < MIN_DESCRIPTOR_STORAGE_BUFFERS
                    || this.maxDescriptorSetStorageBuffers
                        < MIN_DESCRIPTOR_STORAGE_BUFFERS
            ) {
                return "storage-buffer-descriptor-limit-too-small";
            }
            if (
                this.maxPerStageDescriptorSampledImages
                    < MIN_DESCRIPTOR_SAMPLED_IMAGES
                    || this.maxDescriptorSetSampledImages
                        < MIN_DESCRIPTOR_SAMPLED_IMAGES
                    || this.maxUpdateAfterBindDescriptorsInAllPools
                        < MIN_DESCRIPTOR_SAMPLED_IMAGES
                    || this
                        .maxPerStageDescriptorUpdateAfterBindSampledImages
                        < MIN_DESCRIPTOR_SAMPLED_IMAGES
                    || this
                        .maxDescriptorSetUpdateAfterBindSampledImages
                        < MIN_DESCRIPTOR_SAMPLED_IMAGES
            ) {
                return "sampled-image-descriptor-limit-too-small";
            }
            if (this.maxBoundDescriptorSets < MIN_BOUND_DESCRIPTOR_SETS) {
                return "maxBoundDescriptorSets-too-small";
            }
            if (this.maxPushConstantsSize < MIN_PUSH_CONSTANT_BYTES) {
                return "maxPushConstantsSize-too-small";
            }
            if (
                this.maxMemoryAllocationCount
                    < MIN_MEMORY_ALLOCATION_COUNT
            ) {
                return "maxMemoryAllocationCount-too-small";
            }
            if (!isPositivePowerOfTwo(this.nonCoherentAtomSize)) {
                return "nonCoherentAtomSize-unsupported";
            }
            return "";
        }

        public static Limits unavailable() {
            return new Limits(
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0L
            );
        }
    }

    public record Probe(
        int apiVersion,
        QueueCapabilities queues,
        boolean computeShader,
        boolean storageBuffer,
        boolean indirectBuffer,
        boolean multiDrawIndirect,
        boolean drawIndirectCount,
        boolean shaderDrawParameters,
        boolean descriptorIndexing,
        boolean sampledImageNonUniformIndexing,
        boolean sampledImageUpdateAfterBind,
        boolean descriptorPartiallyBound,
        boolean variableDescriptorCount,
        boolean runtimeDescriptorArray,
        boolean bufferDeviceAddress,
        Limits limits,
        String queryFailure
    ) {
        public Probe {
            queues = Objects.requireNonNull(queues, "queues");
            limits = Objects.requireNonNull(limits, "limits");
            queryFailure = Objects.requireNonNull(
                queryFailure,
                "queryFailure"
            );
        }

        public static Probe unavailable(String reason) {
            return new Probe(
                0,
                QueueCapabilities.unavailable(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Limits.unavailable(),
                Objects.requireNonNull(reason, "reason")
            );
        }
    }

    /**
     * Immutable result consumed by backend selection. A true
     * {@link #baselineReady()} means that both support and publication into
     * the exact device generation were proven.
     */
    public record Attestation(
        long deviceGeneration,
        boolean requested,
        boolean vulkan,
        Probe probe,
        Status status,
        boolean requirementsPublished,
        boolean rollbackComplete,
        boolean closed,
        Set<String> requiredExtensions,
        Set<String> requiredFeatures,
        String unavailableReason
    ) {
        public Attestation {
            requirePositive(deviceGeneration, "deviceGeneration");
            probe = Objects.requireNonNull(probe, "probe");
            status = Objects.requireNonNull(status, "status");
            requiredExtensions = immutableCopy(
                requiredExtensions,
                "requiredExtensions"
            );
            requiredFeatures = immutableCopy(
                requiredFeatures,
                "requiredFeatures"
            );
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            if (
                requirementsPublished
                    != (status == Status.PUBLISHED)
            ) {
                throw new IllegalArgumentException(
                    "publication status is inconsistent"
                );
            }
            if (closed != (status == Status.CLOSED)) {
                throw new IllegalArgumentException(
                    "closed status is inconsistent"
                );
            }
        }

        public boolean baselineSupported() {
            return this.requested
                && this.vulkan
                && (
                    this.status == Status.ELIGIBLE
                        || this.status == Status.PUBLISHED
                );
        }

        public boolean baselineReady() {
            return this.status == Status.PUBLISHED
                && this.requirementsPublished
                && !this.closed;
        }

        public boolean bufferDeviceAddressSupported() {
            return this.probe.bufferDeviceAddress();
        }

        public boolean bufferDeviceAddressRequired() {
            return false;
        }

        private Attestation asPublished() {
            return new Attestation(
                this.deviceGeneration,
                this.requested,
                this.vulkan,
                this.probe,
                Status.PUBLISHED,
                true,
                true,
                false,
                this.requiredExtensions,
                this.requiredFeatures,
                ""
            );
        }

        private Attestation asMutationFailure(
            String reason,
            boolean rollbackComplete
        ) {
            return unavailable(
                this.deviceGeneration,
                this.requested,
                this.vulkan,
                this.probe,
                Status.UNAVAILABLE,
                reason,
                rollbackComplete
            );
        }

        private Attestation asClosed() {
            return new Attestation(
                this.deviceGeneration,
                this.requested,
                this.vulkan,
                this.probe,
                Status.CLOSED,
                false,
                this.rollbackComplete,
                true,
                this.requiredExtensions,
                this.requiredFeatures,
                "device-generation-closed"
            );
        }

        private static Attestation awaiting(
            long deviceGeneration,
            boolean requested,
            boolean vulkan
        ) {
            return new Attestation(
                deviceGeneration,
                requested,
                vulkan,
                Probe.unavailable("negotiation-not-run"),
                Status.AWAITING_NEGOTIATION,
                false,
                true,
                false,
                BASELINE_DEVICE_EXTENSIONS,
                featureNames(BASELINE_FEATURES),
                "negotiation-not-run"
            );
        }

        private static Attestation unavailable(
            long deviceGeneration,
            boolean requested,
            boolean vulkan,
            Probe probe,
            Status status,
            String reason,
            boolean rollbackComplete
        ) {
            return new Attestation(
                deviceGeneration,
                requested,
                vulkan,
                probe,
                status,
                false,
                rollbackComplete,
                false,
                BASELINE_DEVICE_EXTENSIONS,
                featureNames(BASELINE_FEATURES),
                reason
            );
        }

        private static Set<String> immutableCopy(
            Set<String> source,
            String name
        ) {
            Objects.requireNonNull(source, name);
            return Collections.unmodifiableSet(
                new LinkedHashSet<>(source)
            );
        }
    }

    /**
     * Small owner for exact device-generation transitions. It retains no
     * Vulkan pointer and rejects stale or foreign generation tokens.
     */
    public static final class GenerationRegistry
        implements AutoCloseable {
        private long lastDeviceGeneration;
        private GenerationToken activeToken;
        private Attestation current;
        private boolean closed;

        public synchronized GenerationToken begin(
            long deviceGeneration,
            boolean requested,
            boolean vulkan
        ) {
            requireOpen();
            if (deviceGeneration <= this.lastDeviceGeneration) {
                throw new IllegalArgumentException(
                    "device generation must increase"
                );
            }
            this.lastDeviceGeneration = deviceGeneration;
            this.activeToken = new GenerationToken(
                this,
                deviceGeneration
            );
            this.current = Attestation.awaiting(
                deviceGeneration,
                requested,
                vulkan
            );
            return this.activeToken;
        }

        public synchronized Attestation configure(
            GenerationToken token,
            Probe probe,
            Set<String> enabledExtensions,
            Set<VulkanFeature> enabledFeatures
        ) {
            requireActive(token);
            if (
                this.current.status()
                    != Status.AWAITING_NEGOTIATION
            ) {
                return this.current;
            }
            this.current =
                NativeTerrainDeviceCapabilityNegotiator.configure(
                    token.deviceGeneration(),
                    this.current.requested(),
                    this.current.vulkan(),
                    probe,
                    enabledExtensions,
                    enabledFeatures
                );
            return this.current;
        }

        public synchronized Attestation snapshot() {
            return this.current;
        }

        public synchronized Attestation closeGeneration(
            GenerationToken token
        ) {
            requireActive(token);
            this.current = this.current.asClosed();
            this.activeToken = null;
            return this.current;
        }

        @Override
        public synchronized void close() {
            if (this.closed) {
                return;
            }
            if (this.current != null && !this.current.closed()) {
                this.current = this.current.asClosed();
            }
            this.activeToken = null;
            this.closed = true;
        }

        private void requireActive(GenerationToken token) {
            requireOpen();
            if (
                token == null
                    || token.owner != this
                    || token != this.activeToken
            ) {
                throw new IllegalArgumentException(
                    "stale or foreign device generation"
                );
            }
        }

        private void requireOpen() {
            if (this.closed) {
                throw new IllegalStateException(
                    "capability registry is closed"
                );
            }
        }
    }

    public static final class GenerationToken {
        private final GenerationRegistry owner;
        private final long deviceGeneration;

        private GenerationToken(
            GenerationRegistry owner,
            long deviceGeneration
        ) {
            this.owner = owner;
            this.deviceGeneration = deviceGeneration;
        }

        public long deviceGeneration() {
            return this.deviceGeneration;
        }
    }
}
