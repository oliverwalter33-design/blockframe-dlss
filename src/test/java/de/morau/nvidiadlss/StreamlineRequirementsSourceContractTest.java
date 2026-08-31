package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlineRequirementsSourceContractTest {
    @Test
    void nativeQueriesBothPinnedFeaturesImmediatelyAfterSuccessfulInit()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String bootstrap = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_bootstrap",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_featureRequirements"
        );
        String query = methodBody(bridge, "queryPinnedRequirements");

        assertTrue(
            bridge.contains(
                "static_assert(SL_VERSION_MINOR == 12"
            )
        );
        assertOrdered(
            bootstrap,
            "const sl::Result result = gInit(",
            "queryPinnedRequirements(requirementsSnapshot",
            "gRequirementsSnapshot = std::move(requirementsSnapshot);",
            "gInitialized = true;"
        );
        assertOrdered(
            query,
            "copyFeatureRequirements(\n                sl::kFeatureDLSS",
            "copyFeatureRequirements(\n                sl::kFeatureNIS"
        );
        assertTrue(
            methodBody(bridge, "copyFeatureRequirements")
                .contains("gGetRequirements(feature, requirements)")
        );
    }

    @Test
    void transportDeepCopiesSdkOwnedArraysAndIsVersionedAndBounded()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String nativeApi = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/"
                + "NativeStreamline.java"
        );
        String decoder = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/"
                + "StreamlineFeatureRequirements.java"
        );

        assertTrue(
            bridge.contains(
                "constexpr uint16_t kRequirementsTransportVersion = 1;"
            )
        );
        assertTrue(
            bridge.contains(
                "kMaxRequirementsSnapshotBytes = 1024 * 1024"
            )
        );
        assertTrue(bridge.contains("std::vector<std::string>"));
        assertTrue(bridge.contains("std::sort(destination.begin()"));
        assertTrue(
            bridge.contains(
                "std::vector<uint8_t> gRequirementsSnapshot;"
            )
        );
        assertTrue(
            nativeApi.contains(
                "public static native byte[] featureRequirements();"
            )
        );
        assertTrue(decoder.contains("TRANSPORT_MAGIC = 0x42465352"));
        assertTrue(decoder.contains("payload.clone()"));
        assertTrue(decoder.contains("bytes.available() != 0"));
    }

    @Test
    void postInitRequirementFailureUsesShutdownAwareRollback()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String rollback = methodBody(
            bridge,
            "rollbackInitializedBootstrap"
        );
        String failedInitRollback = methodBody(
            bridge,
            "rollbackFailedBootstrap"
        );

        assertTrue(rollback.contains("const sl::Result shutdownResult = gShutdown();"));
        assertTrue(rollback.contains("releaseModuleAndReset(releaseError)"));
        assertTrue(rollback.contains("markCleanupUncertain();"));
        assertFalse(failedInitRollback.contains("gShutdown()"));
    }

    @Test
    void javaRejectsUnknownFeaturesQueuesAndUnsupportedEnvironment()
        throws Exception {
        String requirements = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/"
                + "StreamlineFeatureRequirements.java"
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String nativeLoad = methodBody(
            bootstrap,
            "public static synchronized boolean ensureNativeLoaded"
        );

        assertTrue(requirements.contains("validatePinnedDlssNis()"));
        assertTrue(requirements.contains("expected only Streamline features"));
        assertTrue(requirements.contains("does not support Vulkan"));
        assertTrue(requirements.contains("requires extra Vulkan queues"));
        assertTrue(requirements.contains("unknown Vulkan 1.2 feature"));
        assertTrue(requirements.contains("unsupported Vulkan 1.3 features"));
        assertTrue(requirements.contains("requires OS"));
        assertTrue(requirements.contains("requires driver"));
        assertOrdered(
            nativeLoad,
            "NativeStreamline.featureRequirements()",
            "requirements.validatePinnedDlssNis()",
            "streamlineRequirements = requirements;",
            "bootstrapped = true;"
        );
        assertTrue(
            nativeLoad.contains("rollbackRequirementsBootstrap()")
        );
    }

    @Test
    void runtimeUnionDrivesPromotionProbeAndAllFourVulkan12Bits()
        throws Exception {
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String negotiator = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "DlssVulkanCapabilityNegotiator.java"
        );
        String probe = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceCapabilityProbe.java"
        );

        assertTrue(
            bootstrap.contains(
                "requirements.union().vulkanDeviceExtensions()"
            )
        );
        assertTrue(
            bootstrap.contains(
                "VulkanDeviceCapabilityProbe.query("
            )
        );
        assertFalse(
            negotiator.contains(
                "EXT_BUFFER_DEVICE_ADDRESS,\n            VULKAN_API_1_2"
            )
        );
        assertTrue(
            negotiator.contains(
                "KHR_BUFFER_DEVICE_ADDRESS,\n            VULKAN_API_1_2"
            )
        );
        assertTrue(
            negotiator.contains(
                "coreSatisfiedExtensions"
            )
        );
        assertFalse(
            negotiator.contains(
                "Streamline requirements remain unverified"
            )
        );
        for (
            String feature : new String[] {
                "timelineSemaphore",
                "descriptorIndexing",
                "bufferDeviceAddress",
                "shaderFloat16"
            }
        ) {
            assertTrue(probe.contains("vulkan12Features." + feature + "()"));
        }
        for (
            String offset : new String[] {
                "TIMELINESEMAPHORE",
                "DESCRIPTORINDEXING",
                "BUFFERDEVICEADDRESS",
                "SHADERFLOAT16"
            }
        ) {
            assertTrue(
                bootstrap.contains(
                    "VkPhysicalDeviceVulkan12Features." + offset
                )
            );
        }
    }

    private static String methodBody(String source, String method) {
        int marker = source.indexOf(method + "(");
        assertTrue(marker >= 0, "missing method " + method);
        int open = source.indexOf('{', marker);
        int close = matchingBrace(source, open);
        assertTrue(close > open, "unclosed method " + method);
        return source.substring(open, close + 1);
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing start " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "missing end " + endMarker);
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... values) {
        int previous = -1;
        for (String value : values) {
            int current = source.indexOf(value, previous + 1);
            assertTrue(current > previous, "out of order: " + value);
            previous = current;
        }
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
