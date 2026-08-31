package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlineStorageImageFeatureSourceContractTest {
    @Test
    void physicalDeviceFeatureIsProbedAndPublishedOnTheVulkan10Struct()
        throws Exception {
        String probe = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceCapabilityProbe.java"
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );

        assertTrue(
            probe.contains(
                "features.features()\n"
                    + "                        "
                    + ".shaderStorageImageWriteWithoutFormat()"
            )
        );
        assertTrue(
            bootstrap.contains(
                "SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE"
            )
        );
        assertTrue(
            bootstrap.contains(
                "VkPhysicalDeviceFeatures\n"
                    + "                "
                    + ".SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT"
            )
        );
        assertTrue(
            methodBody(bootstrap, "private static void addVulkan10Feature")
                .contains("VulkanBackend.VK10_FEATURES_STRUCT")
        );
    }

    private static String source(String relative) throws Exception {
        return Files.readString(
            Path.of(System.getProperty("user.dir"))
                .resolve(relative),
            StandardCharsets.UTF_8
        ).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int signatureIndex = source.indexOf(signature);
        if (signatureIndex < 0) {
            throw new IllegalArgumentException(
                "Missing method signature: " + signature
            );
        }
        int open = source.indexOf('{', signatureIndex);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        throw new IllegalArgumentException(
            "Unterminated method: " + signature
        );
    }
}
