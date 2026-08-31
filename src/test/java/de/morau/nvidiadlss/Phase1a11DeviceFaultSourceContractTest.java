package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class Phase1a11DeviceFaultSourceContractTest {
    @Test
    void negotiationUsesTheExistingPrePublicationMojangHook()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanBackendMixin.java"
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
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
        assertTrue(
            methodBody(mixin, "nvidiaDlss$enableDeviceRequirements")
                .contains("DlssBootstrap.configureDeviceCapabilities(")
        );
        assertTrue(
            methodBody(bootstrap, "configureDeviceCapabilities")
                .contains("configureOptionalDeviceFault(")
        );
        assertTrue(
            section(
                bootstrap,
                "private static void configureOptionalDeviceFault(",
                "private static void rollbackOptionalDeviceFault("
            )
                .contains("VulkanDeviceFaultNegotiator.configure(")
        );
        assertTrue(
            section(
                bootstrap,
                "private static void configureOptionalDeviceFault(",
                "private static void rollbackOptionalDeviceFault("
            )
                .contains(
                    "VulkanDeviceFaultHookHealth.ensureFatalHookReady()"
                )
        );
    }

    @Test
    void pinnedMojangCreateStackOwnsAllPointersThroughVkDeviceConstruction()
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
            "stack.callocPointer(deviceExtensions.size())",
            "deviceCreateInfo.ppEnabledExtensionNames(",
            "VK12.vkCreateDevice(",
            "return new VkDevice(",
            "\n        }"
        );
        assertTrue(
            backend.indexOf(
                "device = createDevice(deviceExtensions, physicalDevice, "
                    + "enabledFeatures);"
            ) > backend.indexOf(
                "Set<VulkanFeature> enabledFeatures"
            )
        );
    }

    @Test
    void featureUsesMojangsExactOncePNextOwnerAndNeverVendorBinary()
        throws Exception {
        String negotiator = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceFaultNegotiator.java"
        );
        String probe = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceCapabilityProbe.java"
        );
        String pnext = mojangSource(
            "com/mojang/blaze3d/vulkan/init/VulkanPNextStruct.java"
        );

        assertTrue(
            negotiator.contains(
                "VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT"
            )
        );
        assertTrue(
            negotiator.contains(
                "VkPhysicalDeviceFaultFeaturesEXT.DEVICEFAULT"
            )
        );
        assertFalse(
            negotiator.contains(
                "VkPhysicalDeviceFaultFeaturesEXT"
                    + ".DEVICEFAULTVENDORBINARY"
            )
        );
        assertTrue(
            section(
                probe,
                "if (deviceFaultAvailability.extensionSupported())",
                "\n\n            try {\n                queryFeatures("
            ).contains(
                "VkPhysicalDeviceFaultFeaturesEXT"
            )
        );
        assertTrue(
            probe.contains(
                "features.pNext(deviceFaultFeatures.address())"
            )
        );
        assertTrue(probe.contains("deviceFaultFeatures = null;"));
        assertTrue(
            pnext.contains(
                "findOrCreateStructInPNextChain"
            )
        );
        assertTrue(
            pnext.contains(
                "if (foundStruct != 0L)"
            )
        );
        assertTrue(
            pnext.contains(
                "MemoryUtil.memGetAddress("
            )
        );
    }

    @Test
    void fatalHookIsOptionalHealthCheckedAndDoesNotReplaceMojang()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanUtilsDeviceFaultMixin.java"
        );
        String plugin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "DlssMixinPlugin.java"
        );
        String hook = methodBody(mixin, "blockframe$captureDeviceFault");
        String mojang = mojangSource(
            "com/mojang/blaze3d/vulkan/VulkanUtils.java"
        );

        assertTrue(
            annotationBefore(
                mixin,
                "private static void blockframe$captureDeviceFault("
            ).contains("at = @At(\"HEAD\")")
        );
        assertTrue(mixin.contains("require = 0"));
        assertTrue(
            hook.indexOf("result != VK10.VK_ERROR_DEVICE_LOST")
                < hook.indexOf(
                    "BlockframeRuntime.recordVulkanDeviceLost("
                )
        );
        assertTrue(plugin.contains("hasDeviceFaultCall(targetClass)"));
        assertTrue(
            plugin.contains(
                "publishFatalHookApplied("
            )
        );
        assertTrue(
            mojang.contains(
                "if (result == -4)"
            )
        );
        assertTrue(
            mojang.indexOf(
                "retrieveCheckpoints(true)"
            ) < mojang.indexOf(
                "throw new GpuDeviceLossException("
            )
        );
        assertFalse(mixin.contains("CallbackInfoReturnable"));
        assertFalse(mixin.contains("ci.cancel()"));
    }

    @Test
    void functionAndGenerationAreBoundAtTailAndClearedAtHead()
        throws Exception {
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanDeviceMixin.java"
        );
        String capture = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceFaultCapture.java"
        );
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );

        assertTrue(
            annotationBefore(
                deviceMixin,
                "private void nvidiaDlss$connectStreamline("
            ).contains("at = @At(\"TAIL\")")
        );
        assertTrue(
            annotationBefore(
                deviceMixin,
                "private void blockframe$prepareDeviceClose("
            ).contains("at = @At(\"HEAD\")")
        );
        assertTrue(
            methodBody(engine, "vulkanDeviceClosing")
                .indexOf(
                    "this.deviceFaultDiagnostics"
                        + ".vulkanDeviceClosing(device);"
                ) < methodBody(engine, "vulkanDeviceClosing")
                    .indexOf(
                        "this.physicalMemoryTelemetry"
                    )
        );
        assertTrue(capture.contains(".VK_EXT_device_fault"));
        assertTrue(
            capture.contains(
                ".vkGetDeviceFaultInfoEXT != 0L"
            )
        );
        assertFalse(capture.contains("long functionAddress"));
        assertFalse(capture.contains("vkDestroyDevice"));
    }

    @Test
    void captureIsTwoCallBoundedAndNeverPersistsVendorBinary()
        throws Exception {
        String capture = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceFaultCapture.java"
        );

        assertTrue(capture.contains("MAX_ADDRESS_INFOS = 32"));
        assertTrue(capture.contains("MAX_VENDOR_INFOS = 32"));
        assertTrue(capture.contains("MAX_VENDOR_BINARY_BYTES = 0L"));
        assertEquals(2, occurrences(capture, "this.query.invoke("));
        assertTrue(
            capture.contains(
                "VkDeviceFaultInfoEXT.PADDRESSINFOS"
            )
        );
        assertTrue(
            capture.contains(
                "VkDeviceFaultInfoEXT.PVENDORINFOS"
            )
        );
        assertFalse(capture.contains("PVENDORBINARYDATA,"));
        assertFalse(capture.contains("Files."));
        assertFalse(capture.contains("FileOutputStream"));
        assertFalse(capture.contains("new Thread"));
        assertFalse(capture.contains("Executor"));
        assertFalse(capture.contains("Unsafe"));
        assertFalse(capture.contains("waitIdle"));
    }

    @Test
    void f8ReadsOnlyCachedFaultStateAndOpenGlRequestsNothing()
        throws Exception {
        String engine = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeEngine.java"
        );
        String debug = methodBody(engine, "debugLines");
        String begin = methodBody(engine, "beginFrame");
        String end = methodBody(engine, "endFrame");

        assertTrue(
            debug.contains(
                "this.deviceFaultDiagnostics.snapshot()"
            )
        );
        assertTrue(debug.contains("deviceFaultDebugLine(deviceFault)"));
        assertFalse(debug.contains("vkGetDeviceFaultInfoEXT"));
        assertFalse(debug.contains(".capture()"));
        assertFalse(begin.contains("deviceFaultDiagnostics"));
        assertFalse(end.contains("deviceFaultDiagnostics"));
        assertTrue(
            section(
                engine,
                "private void detectDevice()",
                "private void abortIncompleteFrame()"
            )
                .contains(
                    "this.deviceFaultDiagnostics.notVulkanBackend();"
                )
        );
    }

    @Test
    void noParallelOwnerOrIntentionalLossWasAdded() throws Exception {
        StringBuilder all = new StringBuilder();
        Path root = projectRoot().resolve("src/main/java");
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        all.append(
                            Files.readString(path, StandardCharsets.UTF_8)
                        );
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                });
        }
        String production = all.toString();
        assertFalse(production.contains("vkCreateDevice("));
        assertFalse(production.contains("VK_EXT_device_fault\", true"));
        assertFalse(production.contains("vkDeviceWaitIdle"));
        assertFalse(production.contains("DeviceFaultPoll"));
        assertFalse(production.contains("simulateDeviceLoss"));
        assertFalse(production.contains("triggerDeviceLoss"));
        assertEquals(
            1,
            occurrences(
                production,
                "BlockframeRuntime.recordVulkanDeviceLost("
            )
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
                throw new IOException("missing Mojang source " + entryName);
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

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String source(String relative) throws Exception {
        return Files.readString(
            projectRoot().resolve(relative),
            StandardCharsets.UTF_8
        );
    }

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir", ".")
        );
    }
}
