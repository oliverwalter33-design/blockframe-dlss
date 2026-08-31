package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class NativeTerrainDeviceCapabilitySourceContractTest {
    @Test
    void existingHookRunsImmediatelyBeforeMojangsCreateHelper()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanBackendMixin.java"
        );
        String annotation = annotationBefore(
            mixin,
            "private void nvidiaDlss$enableDeviceRequirements("
        );
        assertTrue(
            annotation.contains(
                "VulkanBackend;createDevice(Ljava/util/Collection;"
                    + "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;"
                    + "Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"
            )
        );
        assertTrue(annotation.contains("shift = At.Shift.BEFORE"));
        assertTrue(annotation.contains("require = 0"));
    }

    @Test
    void mojangRetainsFeaturesAndPointersThroughVkCreateDevice()
        throws Exception {
        String backend = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanBackend.java"
        );
        String helper = section(
            backend,
            "private static VkDevice createDevice(",
            "\n    }\n}"
        );

        assertOrdered(
            helper,
            "MemoryStack.stackPush()",
            "VkPhysicalDeviceFeatures2.calloc(stack)",
            "requiredDeviceFeature.set(deviceFeatures, true, stack)",
            "deviceCreateInfo.pNext(deviceFeatures.pNext())",
            "VK12.vkCreateDevice(",
            "return new VkDevice("
        );
    }

    @Test
    void negotiatorIsIndependentOfV16AndDoesNotRequestBda()
        throws Exception {
        String negotiator = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/"
                + "NativeTerrainDeviceCapabilityNegotiator.java"
        );
        String featureList = section(
            negotiator,
            "public static final List<VulkanFeature> BASELINE_FEATURES",
            "public static final Set<String> "
                + "BASELINE_DEVICE_EXTENSIONS = Set.of();"
        );

        assertFalse(negotiator.contains("OpaqueSolid"));
        assertFalse(negotiator.contains("gpuscene"));
        assertFalse(featureList.contains("BUFFERDEVICEADDRESS"));
        assertFalse(featureList.contains("bufferDeviceAddress"));
        assertTrue(
            negotiator.contains(
                "VK12.vkGetPhysicalDeviceFeatures2("
            )
        );
        assertTrue(
            negotiator.contains(
                "VK12.vkGetPhysicalDeviceProperties2("
            )
        );
        assertTrue(
            negotiator.contains(
                "public boolean bufferDeviceAddressRequired()"
            )
        );
        assertTrue(
            section(
                negotiator,
                "public boolean bufferDeviceAddressRequired()",
                "\n        }"
            ).contains("return false;")
        );
    }

    @Test
    void openGlAndSelectionCannotTouchDeviceCreateCollections()
        throws Exception {
        String negotiator = source(
            "src/main/java/de/morau/blockframe/render/terrain/"
                + "nativeengine/"
                + "NativeTerrainDeviceCapabilityNegotiator.java"
        );
        String select = section(
            negotiator,
            "public static Attestation select(",
            "\n    }\n\n    /**\n     * Transactionally"
        );

        assertTrue(select.contains("if (!vulkan)"));
        assertTrue(select.contains("\"not-vulkan\""));
        assertFalse(select.contains(".add("));
        assertFalse(select.contains(".remove("));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(
            projectRoot().resolve(relative),
            StandardCharsets.UTF_8
        );
    }

    private static String mojangSource(String entryName)
        throws Exception {
        Path artifacts = projectRoot()
            .resolve("build")
            .resolve("moddev")
            .resolve("artifacts");
        Path sourceJar;
        try (var paths = Files.list(artifacts)) {
            sourceJar = paths
                .filter(
                    path ->
                        path.getFileName()
                            .toString()
                            .startsWith("minecraft-patched-")
                            && path.getFileName()
                                .toString()
                                .endsWith("-sources.jar")
                )
                .findFirst()
                .orElseThrow();
        }
        try (ZipFile zip = new ZipFile(sourceJar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException(
                    "missing Mojang source " + entryName
                );
            }
            return new String(
                zip.getInputStream(entry).readAllBytes(),
                StandardCharsets.UTF_8
            );
        }
    }

    private static String annotationBefore(
        String source,
        String methodMarker
    ) {
        int method = source.indexOf(methodMarker);
        assertTrue(method >= 0, "missing method " + methodMarker);
        int annotation = source.lastIndexOf("@Inject", method);
        assertTrue(annotation >= 0, "missing annotation " + methodMarker);
        return source.substring(annotation, method);
    }

    private static String section(
        String source,
        String start,
        String end
    ) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin);
        assertTrue(begin >= 0, "missing start " + start);
        assertTrue(finish > begin, "missing end " + end);
        return source.substring(begin, finish + end.length());
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int current = source.indexOf(needle, previous + 1);
            assertTrue(current > previous, "out of order: " + needle);
            previous = current;
        }
    }

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
    }
}
