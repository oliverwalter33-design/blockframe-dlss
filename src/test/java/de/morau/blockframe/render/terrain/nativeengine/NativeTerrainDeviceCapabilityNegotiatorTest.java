package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;

class NativeTerrainDeviceCapabilityNegotiatorTest {
    @Test
    void supportedVulkanPublishesExactCoreRequirements() {
        Set<String> extensions = new LinkedHashSet<>(
            Set.of("VK_KHR_swapchain")
        );
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var attestation =
            NativeTerrainDeviceCapabilityNegotiator.configure(
                7L,
                true,
                true,
                supportedProbe(true),
                extensions,
                features
            );

        assertTrue(attestation.baselineSupported());
        assertTrue(attestation.baselineReady());
        assertTrue(attestation.requirementsPublished());
        assertTrue(attestation.bufferDeviceAddressSupported());
        assertFalse(attestation.bufferDeviceAddressRequired());
        assertEquals(
            NativeTerrainDeviceCapabilityNegotiator.Status.PUBLISHED,
            attestation.status()
        );
        assertEquals(
            Set.of("VK_KHR_swapchain"),
            extensions,
            "Vulkan 1.2 baseline adds no extension name"
        );
        assertEquals(
            new LinkedHashSet<>(
                NativeTerrainDeviceCapabilityNegotiator
                    .BASELINE_FEATURES
            ),
            features
        );
        assertFalse(
            features.stream().anyMatch(
                feature ->
                    feature.name().equals("bufferDeviceAddress")
            )
        );
    }

    @Test
    void configurationOffAndOpenGlRequestNothing() {
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var off = NativeTerrainDeviceCapabilityNegotiator.configure(
            1L,
            false,
            true,
            supportedProbe(false),
            extensions,
            features
        );
        var openGl = NativeTerrainDeviceCapabilityNegotiator.configure(
            2L,
            true,
            false,
            supportedProbe(false),
            extensions,
            features
        );

        assertEquals(
            NativeTerrainDeviceCapabilityNegotiator.Status.NOT_REQUESTED,
            off.status()
        );
        assertEquals(
            "disabled-by-configuration",
            off.unavailableReason()
        );
        assertEquals(
            NativeTerrainDeviceCapabilityNegotiator.Status.UNAVAILABLE,
            openGl.status()
        );
        assertEquals("not-vulkan", openGl.unavailableReason());
        assertTrue(extensions.isEmpty());
        assertTrue(features.isEmpty());
    }

    @Test
    void unsupportedAndFailedProbesFailClosedWithoutMutation() {
        Set<String> extensions = new LinkedHashSet<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();

        var absent = NativeTerrainDeviceCapabilityNegotiator.configure(
            1L,
            true,
            true,
            null,
            extensions,
            features
        );
        var failed = NativeTerrainDeviceCapabilityNegotiator.configure(
            2L,
            true,
            true,
            NativeTerrainDeviceCapabilityNegotiator.Probe.unavailable(
                "injected-query-failure"
            ),
            extensions,
            features
        );

        assertFalse(absent.baselineReady());
        assertEquals("probe-unavailable", absent.unavailableReason());
        assertFalse(failed.baselineReady());
        assertEquals(
            "injected-query-failure",
            failed.unavailableReason()
        );
        assertTrue(extensions.isEmpty());
        assertTrue(features.isEmpty());
    }

    @Test
    void everyRequiredFeatureIsCheckedBeforePublication() {
        String[] features = {
            "computeShader",
            "storageBuffer",
            "indirectBuffer",
            "multiDrawIndirect",
            "drawIndirectCount",
            "shaderDrawParameters",
            "descriptorIndexing",
            "sampledImageNonUniformIndexing",
            "sampledImageUpdateAfterBind",
            "descriptorPartiallyBound",
            "variableDescriptorCount",
            "runtimeDescriptorArray"
        };
        for (int index = 0; index < features.length; index++) {
            String name = features[index];
            Set<VulkanFeature> enabled = new LinkedHashSet<>();
            var result = NativeTerrainDeviceCapabilityNegotiator.configure(
                index + 1L,
                true,
                true,
                withFeatureDisabled(name),
                new LinkedHashSet<>(),
                enabled
            );
            assertFalse(result.baselineReady(), name);
            assertFalse(result.unavailableReason().isBlank(), name);
            assertTrue(enabled.isEmpty(), name);
        }
    }

    @Test
    void relevantLimitFailuresAreTypedAndDoNotPublish() {
        assertLimitFailure(
            limits(
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_STORAGE_BUFFER_RANGE - 1L,
                256L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DRAW_INDIRECT_COUNT,
                256
            ),
            "maxStorageBufferRange-too-small"
        );
        assertLimitFailure(
            limits(
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_STORAGE_BUFFER_RANGE,
                512L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DRAW_INDIRECT_COUNT,
                256
            ),
            "minStorageBufferOffsetAlignment-unsupported"
        );
        assertLimitFailure(
            limits(
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_STORAGE_BUFFER_RANGE,
                256L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DRAW_INDIRECT_COUNT - 1L,
                256
            ),
            "maxDrawIndirectCount-too-small"
        );
        assertLimitFailure(
            limits(
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_STORAGE_BUFFER_RANGE,
                256L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DRAW_INDIRECT_COUNT,
                255
            ),
            "sampled-image-descriptor-limit-too-small"
        );
    }

    @Test
    void repeatedConfigurationDoesNotDuplicateFeaturesOrPNextStructs() {
        Set<VulkanFeature> features = new LinkedHashSet<>();
        Set<String> extensions = new LinkedHashSet<>();

        var first = NativeTerrainDeviceCapabilityNegotiator.configure(
            4L,
            true,
            true,
            supportedProbe(false),
            extensions,
            features
        );
        int firstSize = features.size();
        var second = NativeTerrainDeviceCapabilityNegotiator.configure(
            4L,
            true,
            true,
            supportedProbe(false),
            extensions,
            features
        );

        assertTrue(first.baselineReady());
        assertTrue(second.baselineReady());
        assertEquals(firstSize, features.size());
        assertEquals(
            NativeTerrainDeviceCapabilityNegotiator
                .BASELINE_FEATURES
                .size(),
            features.size()
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 root =
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (int pass = 0; pass < 2; pass++) {
                for (
                    VulkanFeature feature
                    : NativeTerrainDeviceCapabilityNegotiator
                        .BASELINE_FEATURES
                ) {
                    feature.set(root, true, stack);
                }
            }
            assertEquals(
                1,
                countSType(
                    root.pNext(),
                    VulkanBackend.VK11_FEATURES_STRUCT.sType()
                )
            );
            assertEquals(
                1,
                countSType(
                    root.pNext(),
                    VulkanBackend.VK12_FEATURES_STRUCT.sType()
                )
            );
            for (
                VulkanFeature feature
                : NativeTerrainDeviceCapabilityNegotiator
                    .BASELINE_FEATURES
            ) {
                assertTrue(feature.get(root), feature.name());
            }
        }
    }

    @Test
    void mutationFailureRollsBackOnlyFeaturesOwnedByThisCall() {
        VulkanFeature existing =
            NativeTerrainDeviceCapabilityNegotiator
                .MULTI_DRAW_INDIRECT_FEATURE;
        ThrowOnNthAddSet<VulkanFeature> features =
            new ThrowOnNthAddSet<>(Set.of(existing), 3);

        var result = NativeTerrainDeviceCapabilityNegotiator.configure(
            9L,
            true,
            true,
            supportedProbe(false),
            new LinkedHashSet<>(),
            features
        );

        assertFalse(result.baselineReady());
        assertTrue(result.rollbackComplete());
        assertTrue(
            result
                .unavailableReason()
                .startsWith("device-create-set-mutation-failed:")
        );
        assertEquals(Set.of(existing), features);
    }

    @Test
    void attestationOwnsImmutableCopies() {
        var result = NativeTerrainDeviceCapabilityNegotiator.select(
            1L,
            true,
            true,
            supportedProbe(false)
        );

        assertThrows(
            UnsupportedOperationException.class,
            () -> result.requiredFeatures().add("foreign")
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> result.requiredExtensions().add("foreign")
        );
    }

    @Test
    void registryRejectsStaleAndForeignGenerationsAcrossReload() {
        var registry =
            new NativeTerrainDeviceCapabilityNegotiator
                .GenerationRegistry();
        var foreignRegistry =
            new NativeTerrainDeviceCapabilityNegotiator
                .GenerationRegistry();
        var first = registry.begin(11L, true, true);
        var foreign = foreignRegistry.begin(11L, true, true);
        var firstResult = registry.configure(
            first,
            supportedProbe(false),
            new LinkedHashSet<>(),
            new LinkedHashSet<>()
        );
        var second = registry.begin(12L, true, true);

        assertTrue(firstResult.baselineReady());
        assertEquals(12L, registry.snapshot().deviceGeneration());
        assertEquals(
            NativeTerrainDeviceCapabilityNegotiator.Status
                .AWAITING_NEGOTIATION,
            registry.snapshot().status()
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry.configure(
                    first,
                    supportedProbe(false),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>()
                )
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry.configure(
                    foreign,
                    supportedProbe(false),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>()
                )
        );
        assertTrue(
            registry
                .configure(
                    second,
                    supportedProbe(false),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>()
                )
                .baselineReady()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.begin(12L, true, true)
        );
    }

    @Test
    void deviceGenerationAndRegistryCloseAreTerminal() {
        var registry =
            new NativeTerrainDeviceCapabilityNegotiator
                .GenerationRegistry();
        var token = registry.begin(20L, true, true);
        registry.configure(
            token,
            supportedProbe(false),
            new LinkedHashSet<>(),
            new LinkedHashSet<>()
        );

        var closed = registry.closeGeneration(token);
        assertTrue(closed.closed());
        assertFalse(closed.baselineReady());
        assertEquals(
            NativeTerrainDeviceCapabilityNegotiator.Status.CLOSED,
            closed.status()
        );
        registry.close();
        registry.close();
        assertThrows(
            IllegalStateException.class,
            () -> registry.begin(21L, true, true)
        );
    }

    private static void assertLimitFailure(
        NativeTerrainDeviceCapabilityNegotiator.Limits limits,
        String reason
    ) {
        Set<VulkanFeature> enabled = new LinkedHashSet<>();
        var result = NativeTerrainDeviceCapabilityNegotiator.configure(
            1L,
            true,
            true,
            probeWithLimits(limits),
            new LinkedHashSet<>(),
            enabled
        );
        assertFalse(result.baselineReady());
        assertEquals(reason, result.unavailableReason());
        assertTrue(enabled.isEmpty());
    }

    private static NativeTerrainDeviceCapabilityNegotiator.Probe
        supportedProbe(boolean bufferDeviceAddress) {
        return new NativeTerrainDeviceCapabilityNegotiator.Probe(
            VK12.VK_API_VERSION_1_2,
            new NativeTerrainDeviceCapabilityNegotiator.QueueCapabilities(
                0,
                0,
                0,
                1
            ),
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            bufferDeviceAddress,
            limits(
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_STORAGE_BUFFER_RANGE,
                256L,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DRAW_INDIRECT_COUNT,
                NativeTerrainDeviceCapabilityNegotiator
                    .MIN_DESCRIPTOR_SAMPLED_IMAGES
            ),
            ""
        );
    }

    private static NativeTerrainDeviceCapabilityNegotiator.Probe
        probeWithLimits(
            NativeTerrainDeviceCapabilityNegotiator.Limits limits
        ) {
        var supported = supportedProbe(false);
        return new NativeTerrainDeviceCapabilityNegotiator.Probe(
            supported.apiVersion(),
            supported.queues(),
            supported.computeShader(),
            supported.storageBuffer(),
            supported.indirectBuffer(),
            supported.multiDrawIndirect(),
            supported.drawIndirectCount(),
            supported.shaderDrawParameters(),
            supported.descriptorIndexing(),
            supported.sampledImageNonUniformIndexing(),
            supported.sampledImageUpdateAfterBind(),
            supported.descriptorPartiallyBound(),
            supported.variableDescriptorCount(),
            supported.runtimeDescriptorArray(),
            supported.bufferDeviceAddress(),
            limits,
            ""
        );
    }

    private static NativeTerrainDeviceCapabilityNegotiator.Probe
        withFeatureDisabled(String feature) {
        var supported = supportedProbe(false);
        return new NativeTerrainDeviceCapabilityNegotiator.Probe(
            supported.apiVersion(),
            supported.queues(),
            !feature.equals("computeShader"),
            !feature.equals("storageBuffer"),
            !feature.equals("indirectBuffer"),
            !feature.equals("multiDrawIndirect"),
            !feature.equals("drawIndirectCount"),
            !feature.equals("shaderDrawParameters"),
            !feature.equals("descriptorIndexing"),
            !feature.equals("sampledImageNonUniformIndexing"),
            !feature.equals("sampledImageUpdateAfterBind"),
            !feature.equals("descriptorPartiallyBound"),
            !feature.equals("variableDescriptorCount"),
            !feature.equals("runtimeDescriptorArray"),
            false,
            supported.limits(),
            ""
        );
    }

    private static NativeTerrainDeviceCapabilityNegotiator.Limits limits(
        long storageRange,
        long storageAlignment,
        long drawCount,
        int sampledImageCount
    ) {
        return new NativeTerrainDeviceCapabilityNegotiator.Limits(
            storageRange,
            storageAlignment,
            drawCount,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_COMPUTE_WORK_GROUP_COUNT_X,
            65_535L,
            65_535L,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_COMPUTE_WORK_GROUP_INVOCATIONS,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_COMPUTE_WORK_GROUP_SIZE_X,
            128,
            64,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_DESCRIPTOR_STORAGE_BUFFERS,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_DESCRIPTOR_STORAGE_BUFFERS,
            sampledImageCount,
            sampledImageCount,
            sampledImageCount,
            sampledImageCount,
            sampledImageCount,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_BOUND_DESCRIPTOR_SETS,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_PUSH_CONSTANT_BYTES,
            NativeTerrainDeviceCapabilityNegotiator
                .MIN_MEMORY_ALLOCATION_COUNT,
            256L
        );
    }

    private static int countSType(long first, int expected) {
        int count = 0;
        long current = first;
        while (current != 0L) {
            if (MemoryUtil.memGetInt(current) == expected) {
                count++;
            }
            current = MemoryUtil.memGetAddress(
                current + VkDeviceCreateInfo.PNEXT
            );
        }
        return count;
    }

    private static final class ThrowOnNthAddSet<T>
        extends AbstractSet<T> {
        private final Set<T> delegate;
        private final int failOn;
        private int additions;

        private ThrowOnNthAddSet(Set<T> initial, int failOn) {
            this.delegate = new LinkedHashSet<>(initial);
            this.failOn = failOn;
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
            this.additions++;
            if (this.additions == this.failOn) {
                throw new IllegalStateException(
                    "injected mutation failure"
                );
            }
            return this.delegate.add(value);
        }

        @Override
        public boolean remove(Object value) {
            return this.delegate.remove(value);
        }
    }
}
