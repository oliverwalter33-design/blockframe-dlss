package de.morau.nvidiadlss.nativebridge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable Java copy of the Vulkan requirements returned by Streamline 2.12.
 *
 * <p>The native payload is a private, versioned, big-endian transport. It
 * contains no native addresses: every SDK-owned array and string is copied by
 * the bridge before this decoder sees it.
 */
public final class StreamlineFeatureRequirements {
    public static final int TRANSPORT_MAGIC = 0x42465352; // "BFSR"
    public static final int TRANSPORT_VERSION = 1;
    public static final int SDK_MAJOR = 2;
    public static final int SDK_MINOR = 12;
    public static final int SDK_PATCH = 0;

    public static final long FEATURE_DLSS = 0L;
    public static final long FEATURE_NIS = 2L;

    public static final long BUFFER_TYPE_DEPTH = 0L;
    public static final long BUFFER_TYPE_MOTION_VECTORS = 1L;
    public static final long BUFFER_TYPE_SCALING_INPUT_COLOR = 3L;
    public static final long BUFFER_TYPE_SCALING_OUTPUT_COLOR = 4L;

    public static final long FLAG_D3D11_SUPPORTED = 1L << 0;
    public static final long FLAG_D3D12_SUPPORTED = 1L << 1;
    public static final long FLAG_VULKAN_SUPPORTED = 1L << 2;
    public static final long FLAG_VSYNC_OFF_REQUIRED = 1L << 3;
    public static final long FLAG_HARDWARE_SCHEDULING_REQUIRED = 1L << 4;
    private static final long KNOWN_FLAGS =
        FLAG_D3D11_SUPPORTED
            | FLAG_D3D12_SUPPORTED
            | FLAG_VULKAN_SUPPORTED
            | FLAG_VSYNC_OFF_REQUIRED
            | FLAG_HARDWARE_SCHEDULING_REQUIRED;

    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_FEATURES = 16;
    private static final int MAX_LIST_ENTRIES = 1024;
    private static final int MAX_STRING_BYTES = 4096;
    private static final Set<String> SUPPORTED_VULKAN_12_FEATURES = Set.of(
        "timelineSemaphore",
        "descriptorIndexing",
        "bufferDeviceAddress",
        "shaderFloat16"
    );
    private static final Map<Long, Set<Long>> PINNED_REQUIRED_TAGS = Map.of(
        FEATURE_DLSS,
        Set.of(
            BUFFER_TYPE_DEPTH,
            BUFFER_TYPE_MOTION_VECTORS,
            BUFFER_TYPE_SCALING_INPUT_COLOR,
            BUFFER_TYPE_SCALING_OUTPUT_COLOR
        ),
        FEATURE_NIS,
        Set.of(
            BUFFER_TYPE_SCALING_INPUT_COLOR,
            BUFFER_TYPE_SCALING_OUTPUT_COLOR
        )
    );

    private final Map<Long, Feature> features;

    private StreamlineFeatureRequirements(Map<Long, Feature> features) {
        this.features = Collections.unmodifiableMap(
            new LinkedHashMap<>(features)
        );
    }

    public static StreamlineFeatureRequirements decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw malformed("payload length " + payload.length);
        }

        try (
            ByteArrayInputStream bytes = new ByteArrayInputStream(
                payload.clone()
            );
            DataInputStream input = new DataInputStream(bytes)
        ) {
            int magic = input.readInt();
            if (magic != TRANSPORT_MAGIC) {
                throw malformed(
                    "transport magic 0x" + Integer.toHexString(magic)
                );
            }
            int transportVersion = input.readUnsignedShort();
            if (transportVersion != TRANSPORT_VERSION) {
                throw malformed(
                    "transport version " + transportVersion
                );
            }
            int sdkMajor = input.readUnsignedShort();
            int sdkMinor = input.readUnsignedShort();
            int sdkPatch = input.readUnsignedShort();
            if (
                sdkMajor != SDK_MAJOR
                    || sdkMinor != SDK_MINOR
                    || sdkPatch != SDK_PATCH
            ) {
                throw malformed(
                    "Streamline SDK "
                        + sdkMajor
                        + "."
                        + sdkMinor
                        + "."
                        + sdkPatch
                );
            }

            int featureCount = input.readUnsignedShort();
            if (featureCount == 0 || featureCount > MAX_FEATURES) {
                throw malformed("feature count " + featureCount);
            }
            Map<Long, Feature> decoded = new LinkedHashMap<>();
            for (int index = 0; index < featureCount; index++) {
                Feature feature = readFeature(input);
                if (decoded.putIfAbsent(feature.featureId(), feature) != null) {
                    throw malformed(
                        "duplicate feature " + feature.featureId()
                    );
                }
            }
            if (bytes.available() != 0) {
                throw malformed("trailing bytes " + bytes.available());
            }
            return new StreamlineFeatureRequirements(decoded);
        } catch (EOFException error) {
            throw malformed("truncated payload", error);
        } catch (IOException error) {
            throw malformed("payload read failed", error);
        }
    }

    public Map<Long, Feature> features() {
        return this.features;
    }

    public Validation validatePinnedDlssNis() {
        Set<Long> expected = Set.of(FEATURE_DLSS, FEATURE_NIS);
        if (!this.features.keySet().equals(expected)) {
            return Validation.rejected(
                "expected only Streamline features "
                    + expected
                    + ", got "
                    + this.features.keySet()
            );
        }

        for (Feature feature : this.features.values()) {
            Set<Long> reportedTags = new LinkedHashSet<>(
                feature.requiredTags()
            );
            if (reportedTags.size() != feature.requiredTags().size()) {
                return Validation.rejected(
                    feature.label()
                        + " reported duplicate required resource tags "
                        + feature.requiredTags()
                );
            }
            Set<Long> pinnedTags = PINNED_REQUIRED_TAGS.get(
                feature.featureId()
            );
            if (!reportedTags.equals(pinnedTags)) {
                return Validation.rejected(
                    feature.label()
                        + " required resource tags "
                        + reportedTags
                        + " do not match the tags supplied by the pinned 2.12 bridge "
                        + pinnedTags
                );
            }
            long unknownFlags = feature.flags() & ~KNOWN_FLAGS;
            if (unknownFlags != 0L) {
                return Validation.rejected(
                    feature.label()
                        + " reported unknown requirement flags 0x"
                        + Long.toHexString(unknownFlags)
                );
            }
            if ((feature.flags() & FLAG_VULKAN_SUPPORTED) == 0L) {
                return Validation.rejected(
                    feature.label() + " does not support Vulkan"
                );
            }
            if (
                (feature.flags() & FLAG_VSYNC_OFF_REQUIRED) != 0L
            ) {
                return Validation.rejected(
                    feature.label()
                        + " requires V-Sync off, which is not proven before Vulkan creation"
                );
            }
            if (
                (feature.flags() & FLAG_HARDWARE_SCHEDULING_REQUIRED) != 0L
            ) {
                return Validation.rejected(
                    feature.label()
                        + " requires GPU hardware scheduling, which is not proven before Vulkan creation"
                );
            }
            if (
                !feature.osVersionDetected()
                    .atLeast(feature.osVersionRequired())
            ) {
                return Validation.rejected(
                    feature.label()
                        + " requires OS "
                        + feature.osVersionRequired()
                        + ", detected "
                        + feature.osVersionDetected()
                );
            }
            if (
                !feature.driverVersionDetected()
                    .atLeast(feature.driverVersionRequired())
            ) {
                return Validation.rejected(
                    feature.label()
                        + " requires driver "
                        + feature.driverVersionRequired()
                        + ", detected "
                        + feature.driverVersionDetected()
                );
            }
            if (
                feature.vulkanComputeQueuesRequired() != 0L
                    || feature.vulkanGraphicsQueuesRequired() != 0L
                    || feature.vulkanOpticalFlowQueuesRequired() != 0L
            ) {
                return Validation.rejected(
                    feature.label()
                        + " requires extra Vulkan queues (compute="
                        + feature.vulkanComputeQueuesRequired()
                        + ", graphics="
                        + feature.vulkanGraphicsQueuesRequired()
                        + ", opticalFlow="
                        + feature.vulkanOpticalFlowQueuesRequired()
                        + "); the pinned Mojang queue selection is preserved"
                );
            }
            for (String featureName : feature.vulkanFeatures12()) {
                if (!SUPPORTED_VULKAN_12_FEATURES.contains(featureName)) {
                    return Validation.rejected(
                        feature.label()
                            + " reported unknown Vulkan 1.2 feature "
                            + featureName
                    );
                }
            }
            if (!feature.vulkanFeatures13().isEmpty()) {
                return Validation.rejected(
                    feature.label()
                        + " requires unsupported Vulkan 1.3 features "
                        + feature.vulkanFeatures13()
                );
            }
        }
        return Validation.accepted(
            "authoritative Streamline 2.12 DLSS+NIS requirements decoded"
        );
    }

    public RequirementUnion union() {
        Set<String> instanceExtensions = new TreeSet<>();
        Set<String> deviceExtensions = new TreeSet<>();
        Set<String> features12 = new TreeSet<>();
        Set<String> features13 = new TreeSet<>();
        for (Feature feature : this.features.values()) {
            instanceExtensions.addAll(feature.vulkanInstanceExtensions());
            deviceExtensions.addAll(feature.vulkanDeviceExtensions());
            features12.addAll(feature.vulkanFeatures12());
            features13.addAll(feature.vulkanFeatures13());
        }
        return new RequirementUnion(
            instanceExtensions,
            deviceExtensions,
            features12,
            features13
        );
    }

    public String deterministicSummary() {
        RequirementUnion union = this.union();
        return "features="
            + this.features.keySet()
            + ", instanceExtensions="
            + union.vulkanInstanceExtensions()
            + ", deviceExtensions="
            + union.vulkanDeviceExtensions()
            + ", features12="
            + union.vulkanFeatures12()
            + ", features13="
            + union.vulkanFeatures13();
    }

    private static Feature readFeature(DataInputStream input)
        throws IOException {
        long featureId = readUnsignedInt(input);
        long flags = readUnsignedInt(input);
        long maxNumCpuThreads = readUnsignedInt(input);
        long maxNumViewports = readUnsignedInt(input);
        Version osDetected = readVersion(input);
        Version osRequired = readVersion(input);
        Version driverDetected = readVersion(input);
        Version driverRequired = readVersion(input);
        long computeQueues = readUnsignedInt(input);
        long graphicsQueues = readUnsignedInt(input);
        long opticalFlowQueues = readUnsignedInt(input);
        List<Long> requiredTags = readUnsignedIntList(
            input,
            "required tags"
        );
        List<String> instanceExtensions = readStringList(
            input,
            "instance extensions"
        );
        List<String> deviceExtensions = readStringList(
            input,
            "device extensions"
        );
        List<String> features12 = readStringList(
            input,
            "Vulkan 1.2 features"
        );
        List<String> features13 = readStringList(
            input,
            "Vulkan 1.3 features"
        );
        return new Feature(
            featureId,
            flags,
            maxNumCpuThreads,
            maxNumViewports,
            requiredTags,
            osDetected,
            osRequired,
            driverDetected,
            driverRequired,
            computeQueues,
            graphicsQueues,
            opticalFlowQueues,
            instanceExtensions,
            deviceExtensions,
            features12,
            features13
        );
    }

    private static Version readVersion(DataInputStream input)
        throws IOException {
        return new Version(
            readUnsignedInt(input),
            readUnsignedInt(input),
            readUnsignedInt(input)
        );
    }

    private static List<Long> readUnsignedIntList(
        DataInputStream input,
        String label
    ) throws IOException {
        int count = readCount(input, label);
        List<Long> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readUnsignedInt(input));
        }
        return List.copyOf(values);
    }

    private static List<String> readStringList(
        DataInputStream input,
        String label
    ) throws IOException {
        int count = readCount(input, label);
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int length = readCount(input, label + " string bytes");
            if (length == 0 || length > MAX_STRING_BYTES) {
                throw malformed(label + " string length " + length);
            }
            byte[] utf8 = input.readNBytes(length);
            if (utf8.length != length) {
                throw new EOFException(label + " string");
            }
            String value = decodeUtf8(utf8, label);
            if (value.indexOf('\0') >= 0) {
                throw malformed(label + " contains NUL");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static int readCount(DataInputStream input, String label)
        throws IOException {
        long count = readUnsignedInt(input);
        if (count > MAX_LIST_ENTRIES) {
            throw malformed(label + " count " + count);
        }
        return (int) count;
    }

    private static long readUnsignedInt(DataInputStream input)
        throws IOException {
        return Integer.toUnsignedLong(input.readInt());
    }

    private static String decodeUtf8(byte[] value, String label) {
        try {
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException error) {
            throw malformed(label + " is not UTF-8", error);
        }
    }

    private static IllegalArgumentException malformed(String reason) {
        return new IllegalArgumentException(
            "Malformed Streamline requirements: " + reason
        );
    }

    private static IllegalArgumentException malformed(
        String reason,
        Throwable cause
    ) {
        return new IllegalArgumentException(
            "Malformed Streamline requirements: " + reason,
            cause
        );
    }

    public record Version(long major, long minor, long build) {
        public Version {
            requireUnsignedInt(major, "major");
            requireUnsignedInt(minor, "minor");
            requireUnsignedInt(build, "build");
        }

        public boolean atLeast(Version required) {
            Objects.requireNonNull(required, "required");
            int majorComparison = Long.compareUnsigned(
                this.major,
                required.major
            );
            if (majorComparison != 0) {
                return majorComparison > 0;
            }
            int minorComparison = Long.compareUnsigned(
                this.minor,
                required.minor
            );
            if (minorComparison != 0) {
                return minorComparison > 0;
            }
            return Long.compareUnsigned(this.build, required.build) >= 0;
        }

        @Override
        public String toString() {
            return Long.toUnsignedString(this.major)
                + "."
                + Long.toUnsignedString(this.minor)
                + "."
                + Long.toUnsignedString(this.build);
        }
    }

    public record Feature(
        long featureId,
        long flags,
        long maxNumCpuThreads,
        long maxNumViewports,
        List<Long> requiredTags,
        Version osVersionDetected,
        Version osVersionRequired,
        Version driverVersionDetected,
        Version driverVersionRequired,
        long vulkanComputeQueuesRequired,
        long vulkanGraphicsQueuesRequired,
        long vulkanOpticalFlowQueuesRequired,
        List<String> vulkanInstanceExtensions,
        List<String> vulkanDeviceExtensions,
        List<String> vulkanFeatures12,
        List<String> vulkanFeatures13
    ) {
        public Feature {
            requireUnsignedInt(featureId, "featureId");
            requireUnsignedInt(flags, "flags");
            requireUnsignedInt(maxNumCpuThreads, "maxNumCpuThreads");
            requireUnsignedInt(maxNumViewports, "maxNumViewports");
            requiredTags = List.copyOf(requiredTags);
            osVersionDetected = Objects.requireNonNull(
                osVersionDetected,
                "osVersionDetected"
            );
            osVersionRequired = Objects.requireNonNull(
                osVersionRequired,
                "osVersionRequired"
            );
            driverVersionDetected = Objects.requireNonNull(
                driverVersionDetected,
                "driverVersionDetected"
            );
            driverVersionRequired = Objects.requireNonNull(
                driverVersionRequired,
                "driverVersionRequired"
            );
            requireUnsignedInt(
                vulkanComputeQueuesRequired,
                "vulkanComputeQueuesRequired"
            );
            requireUnsignedInt(
                vulkanGraphicsQueuesRequired,
                "vulkanGraphicsQueuesRequired"
            );
            requireUnsignedInt(
                vulkanOpticalFlowQueuesRequired,
                "vulkanOpticalFlowQueuesRequired"
            );
            vulkanInstanceExtensions = immutableStrings(
                vulkanInstanceExtensions
            );
            vulkanDeviceExtensions = immutableStrings(
                vulkanDeviceExtensions
            );
            vulkanFeatures12 = immutableStrings(vulkanFeatures12);
            vulkanFeatures13 = immutableStrings(vulkanFeatures13);
        }

        public String label() {
            if (this.featureId == FEATURE_DLSS) {
                return "DLSS";
            }
            if (this.featureId == FEATURE_NIS) {
                return "NIS";
            }
            return "feature-" + Long.toUnsignedString(this.featureId);
        }
    }

    public record RequirementUnion(
        Set<String> vulkanInstanceExtensions,
        Set<String> vulkanDeviceExtensions,
        Set<String> vulkanFeatures12,
        Set<String> vulkanFeatures13
    ) {
        public RequirementUnion {
            vulkanInstanceExtensions = immutableOrderedSet(
                vulkanInstanceExtensions
            );
            vulkanDeviceExtensions = immutableOrderedSet(
                vulkanDeviceExtensions
            );
            vulkanFeatures12 = immutableOrderedSet(vulkanFeatures12);
            vulkanFeatures13 = immutableOrderedSet(vulkanFeatures13);
        }
    }

    public record Validation(boolean compatible, String reason) {
        public Validation {
            reason = Objects.requireNonNull(reason, "reason");
        }

        private static Validation accepted(String reason) {
            return new Validation(true, reason);
        }

        private static Validation rejected(String reason) {
            return new Validation(false, reason);
        }
    }

    private static List<String> immutableStrings(List<String> values) {
        Objects.requireNonNull(values, "values");
        for (String value : values) {
            if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
                throw malformed("invalid requirement name");
            }
        }
        return List.copyOf(values);
    }

    private static Set<String> immutableOrderedSet(Set<String> values) {
        Objects.requireNonNull(values, "values");
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static void requireUnsignedInt(long value, String label) {
        if (value < 0L || value > 0xffff_ffffL) {
            throw malformed(label + " is outside uint32");
        }
    }
}
