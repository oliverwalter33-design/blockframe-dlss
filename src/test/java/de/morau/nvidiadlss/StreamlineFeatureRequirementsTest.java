package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.nvidiadlss.nativebridge.StreamlineFeatureRequirements;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StreamlineFeatureRequirementsTest {
    @Test
    void validPinnedPairDecodesEveryRequirementAndBuildsExactUnion()
        throws Exception {
        byte[] payload = encode(validDlss(), validNis());
        var requirements = StreamlineFeatureRequirements.decode(payload);
        payload[0] = 0;

        var validation = requirements.validatePinnedDlssNis();
        var union = requirements.union();

        assertTrue(validation.compatible(), validation.reason());
        assertEquals(Set.of(0L, 2L), requirements.features().keySet());
        assertEquals(
            Set.of(
                "VK_KHR_external_memory_capabilities",
                "VK_KHR_external_semaphore_capabilities",
                "VK_KHR_get_physical_device_properties2"
            ),
            union.vulkanInstanceExtensions()
        );
        assertEquals(
            Set.of(
                "timelineSemaphore",
                "descriptorIndexing",
                "bufferDeviceAddress",
                "shaderFloat16"
            ),
            union.vulkanFeatures12()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> requirements.features().clear()
        );
    }

    @Test
    void unknownFeatureIdAndUnknownVulkanFeatureFailClosed()
        throws Exception {
        FeatureSpec unknownId = copy(validNis());
        unknownId.featureId = 77L;
        var unknownFeatureValidation = StreamlineFeatureRequirements
            .decode(encode(validDlss(), unknownId))
            .validatePinnedDlssNis();

        FeatureSpec unknownVkFeature = copy(validNis());
        unknownVkFeature.features12 = List.of("futureFeature");
        var unknownVkValidation = StreamlineFeatureRequirements
            .decode(encode(validDlss(), unknownVkFeature))
            .validatePinnedDlssNis();

        assertFalse(unknownFeatureValidation.compatible());
        assertTrue(unknownFeatureValidation.reason().contains("expected only"));
        assertFalse(unknownVkValidation.compatible());
        assertTrue(
            unknownVkValidation.reason().contains(
                "unknown Vulkan 1.2 feature"
            )
        );
    }

    @Test
    void missingVulkanAndUnsupportedVulkan13FailClosed()
        throws Exception {
        FeatureSpec noVulkan = copy(validDlss());
        noVulkan.flags = 0L;
        var noVulkanValidation = StreamlineFeatureRequirements
            .decode(encode(noVulkan, validNis()))
            .validatePinnedDlssNis();

        FeatureSpec vulkan13 = copy(validNis());
        vulkan13.features13 = List.of("dynamicRendering");
        var vulkan13Validation = StreamlineFeatureRequirements
            .decode(encode(validDlss(), vulkan13))
            .validatePinnedDlssNis();

        assertFalse(noVulkanValidation.compatible());
        assertTrue(noVulkanValidation.reason().contains("does not support Vulkan"));
        assertFalse(vulkan13Validation.compatible());
        assertTrue(
            vulkan13Validation.reason().contains(
                "unsupported Vulkan 1.3 features"
            )
        );
    }

    @Test
    void anyAdditionalQueueRequirementFailsClosed() throws Exception {
        for (int queueKind = 0; queueKind < 3; queueKind++) {
            FeatureSpec nis = copy(validNis());
            if (queueKind == 0) {
                nis.computeQueues = 1L;
            } else if (queueKind == 1) {
                nis.graphicsQueues = 1L;
            } else {
                nis.opticalFlowQueues = 1L;
            }
            var validation = StreamlineFeatureRequirements
                .decode(encode(validDlss(), nis))
                .validatePinnedDlssNis();
            assertFalse(validation.compatible(), "queue kind " + queueKind);
            assertTrue(validation.reason().contains("requires extra Vulkan queues"));
        }
    }

    @Test
    void requiredTagsMustExactlyMatchThePinnedBridgeCoverage()
        throws Exception {
        FeatureSpec unknownDlssTag = copy(validDlss());
        unknownDlssTag.tags = List.of(0L, 1L, 2L, 3L, 4L);
        FeatureSpec missingDlssTag = copy(validDlss());
        missingDlssTag.tags = List.of(0L, 1L, 3L);
        FeatureSpec duplicateNisTag = copy(validNis());
        duplicateNisTag.tags = List.of(3L, 3L, 4L);

        for (
            FeatureSpec invalid : List.of(
                unknownDlssTag,
                missingDlssTag,
                duplicateNisTag
            )
        ) {
            FeatureSpec dlss =
                invalid.featureId
                        == StreamlineFeatureRequirements.FEATURE_DLSS
                    ? invalid
                    : validDlss();
            FeatureSpec nis =
                invalid.featureId
                        == StreamlineFeatureRequirements.FEATURE_NIS
                    ? invalid
                    : validNis();
            var validation = StreamlineFeatureRequirements
                .decode(encode(dlss, nis))
                .validatePinnedDlssNis();
            assertFalse(validation.compatible(), validation.reason());
            assertTrue(
                validation.reason().contains("required resource tags"),
                validation.reason()
            );
        }
    }

    @Test
    void osAndDriverMinimumsUseTheDetectedRuntimeVersions()
        throws Exception {
        FeatureSpec oldOs = copy(validDlss());
        oldOs.osDetected = new VersionSpec(10, 0, 1);
        FeatureSpec oldDriver = copy(validDlss());
        oldDriver.driverDetected = new VersionSpec(500, 0, 0);

        var osValidation = StreamlineFeatureRequirements
            .decode(encode(oldOs, validNis()))
            .validatePinnedDlssNis();
        var driverValidation = StreamlineFeatureRequirements
            .decode(encode(oldDriver, validNis()))
            .validatePinnedDlssNis();

        assertFalse(osValidation.compatible());
        assertTrue(osValidation.reason().contains("requires OS"));
        assertFalse(driverValidation.compatible());
        assertTrue(driverValidation.reason().contains("requires driver"));
    }

    @Test
    void transportVersionAndTrailingBytesAreRejected() throws Exception {
        byte[] wrongVersion = encode(validDlss(), validNis());
        wrongVersion[5] = 2;
        byte[] valid = encode(validDlss(), validNis());
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

        assertThrows(
            IllegalArgumentException.class,
            () -> StreamlineFeatureRequirements.decode(wrongVersion)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> StreamlineFeatureRequirements.decode(trailing)
        );
    }

    private static FeatureSpec validDlss() {
        FeatureSpec feature = new FeatureSpec();
        feature.featureId = StreamlineFeatureRequirements.FEATURE_DLSS;
        feature.flags =
            StreamlineFeatureRequirements.FLAG_VULKAN_SUPPORTED;
        feature.maxCpuThreads = 4;
        feature.maxViewports = 8;
        feature.tags = List.of(0L, 1L, 3L, 4L);
        feature.osDetected = new VersionSpec(10, 0, 22631);
        feature.osRequired = new VersionSpec(10, 0, 19041);
        feature.driverDetected = new VersionSpec(572, 83, 0);
        feature.driverRequired = new VersionSpec(512, 15, 0);
        feature.instanceExtensions = List.of(
            "VK_KHR_get_physical_device_properties2",
            "VK_KHR_external_memory_capabilities",
            "VK_KHR_external_semaphore_capabilities"
        );
        feature.deviceExtensions = List.of(
            "VK_NVX_binary_import",
            "VK_NVX_image_view_handle",
            "VK_EXT_buffer_device_address",
            "VK_KHR_push_descriptor"
        );
        feature.features12 = List.of(
            "timelineSemaphore",
            "descriptorIndexing",
            "bufferDeviceAddress"
        );
        return feature;
    }

    private static FeatureSpec validNis() {
        FeatureSpec feature = new FeatureSpec();
        feature.featureId = StreamlineFeatureRequirements.FEATURE_NIS;
        feature.flags =
            StreamlineFeatureRequirements.FLAG_VULKAN_SUPPORTED;
        feature.tags = List.of(3L, 4L);
        feature.features12 = List.of("shaderFloat16");
        return feature;
    }

    private static FeatureSpec copy(FeatureSpec source) {
        FeatureSpec copy = new FeatureSpec();
        copy.featureId = source.featureId;
        copy.flags = source.flags;
        copy.maxCpuThreads = source.maxCpuThreads;
        copy.maxViewports = source.maxViewports;
        copy.tags = List.copyOf(source.tags);
        copy.osDetected = source.osDetected;
        copy.osRequired = source.osRequired;
        copy.driverDetected = source.driverDetected;
        copy.driverRequired = source.driverRequired;
        copy.computeQueues = source.computeQueues;
        copy.graphicsQueues = source.graphicsQueues;
        copy.opticalFlowQueues = source.opticalFlowQueues;
        copy.instanceExtensions = List.copyOf(source.instanceExtensions);
        copy.deviceExtensions = List.copyOf(source.deviceExtensions);
        copy.features12 = List.copyOf(source.features12);
        copy.features13 = List.copyOf(source.features13);
        return copy;
    }

    private static byte[] encode(FeatureSpec... features) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(StreamlineFeatureRequirements.TRANSPORT_MAGIC);
            output.writeShort(
                StreamlineFeatureRequirements.TRANSPORT_VERSION
            );
            output.writeShort(StreamlineFeatureRequirements.SDK_MAJOR);
            output.writeShort(StreamlineFeatureRequirements.SDK_MINOR);
            output.writeShort(StreamlineFeatureRequirements.SDK_PATCH);
            output.writeShort(features.length);
            for (FeatureSpec feature : features) {
                writeFeature(output, feature);
            }
        }
        return bytes.toByteArray();
    }

    private static void writeFeature(
        DataOutputStream output,
        FeatureSpec feature
    ) throws Exception {
        writeU32(output, feature.featureId);
        writeU32(output, feature.flags);
        writeU32(output, feature.maxCpuThreads);
        writeU32(output, feature.maxViewports);
        writeVersion(output, feature.osDetected);
        writeVersion(output, feature.osRequired);
        writeVersion(output, feature.driverDetected);
        writeVersion(output, feature.driverRequired);
        writeU32(output, feature.computeQueues);
        writeU32(output, feature.graphicsQueues);
        writeU32(output, feature.opticalFlowQueues);
        writeU32(output, feature.tags.size());
        for (long tag : feature.tags) {
            writeU32(output, tag);
        }
        writeStrings(output, feature.instanceExtensions);
        writeStrings(output, feature.deviceExtensions);
        writeStrings(output, feature.features12);
        writeStrings(output, feature.features13);
    }

    private static void writeVersion(
        DataOutputStream output,
        VersionSpec version
    ) throws Exception {
        writeU32(output, version.major());
        writeU32(output, version.minor());
        writeU32(output, version.build());
    }

    private static void writeStrings(
        DataOutputStream output,
        List<String> values
    ) throws Exception {
        writeU32(output, values.size());
        for (String value : values) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            writeU32(output, utf8.length);
            output.write(utf8);
        }
    }

    private static void writeU32(DataOutputStream output, long value)
        throws Exception {
        output.writeInt((int) value);
    }

    private record VersionSpec(long major, long minor, long build) {
        private static final VersionSpec ZERO = new VersionSpec(0, 0, 0);
    }

    private static final class FeatureSpec {
        private long featureId;
        private long flags;
        private long maxCpuThreads;
        private long maxViewports;
        private List<Long> tags = new ArrayList<>();
        private VersionSpec osDetected = VersionSpec.ZERO;
        private VersionSpec osRequired = VersionSpec.ZERO;
        private VersionSpec driverDetected = VersionSpec.ZERO;
        private VersionSpec driverRequired = VersionSpec.ZERO;
        private long computeQueues;
        private long graphicsQueues;
        private long opticalFlowQueues;
        private List<String> instanceExtensions = new ArrayList<>();
        private List<String> deviceExtensions = new ArrayList<>();
        private List<String> features12 = new ArrayList<>();
        private List<String> features13 = new ArrayList<>();
    }
}
