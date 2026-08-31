package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Phase1a9PhysicalMemoryTelemetrySourceContractTest {
    @Test
    void beginFrameIsTheOnlyProductionSamplerAndF8ReadsCachedState()
        throws Exception {
        String engine = source(
            "src/main/java/de/morau/blockframe/core/BlockframeEngine.java"
        );
        String overlay = source(
            "src/main/java/de/morau/nvidiadlss/DlssDebugOverlay.java"
        );

        String beginFrame = methodBody(engine, "beginFrame");
        String debugLines = methodBody(engine, "debugLines");
        assertTrue(
            beginFrame.contains(
                "this.physicalMemoryTelemetry.sampleIfDue();"
            )
        );
        assertFalse(
            debugLines.contains("sampleIfDue")
        );
        assertTrue(
            debugLines.contains(
                "this.physicalMemoryTelemetry.snapshot();"
            )
        );
        assertFalse(
            methodBody(engine, "endFrame").contains("sampleIfDue")
        );
        assertFalse(overlay.contains("sampleIfDue"));
        assertTrue(
            overlay.indexOf("if (!visible) return;")
                < overlay.indexOf(
                    "BlockframeRuntime.engine().debugLines()"
                )
        );

        List<String> callers = new ArrayList<>();
        Path root = projectRoot().resolve("src/main/java");
        try (var files = Files.walk(root)) {
            files
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String text = Files.readString(
                            path,
                            StandardCharsets.UTF_8
                        );
                        if (text.contains(".sampleIfDue()")) {
                            callers.add(
                                root.relativize(path)
                                    .toString()
                                    .replace('\\', '/')
                            );
                        }
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
        }
        assertEquals(
            List.of(
                "de/morau/blockframe/core/BlockframeEngine.java"
            ),
            callers
        );
    }

    @Test
    void memoryBudgetExtensionIsObservedButNeverMadeADeviceRequirement()
        throws Exception {
        String capabilityProbe = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanDeviceCapabilityProbe.java"
        );
        String negotiator = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "DlssVulkanCapabilityNegotiator.java"
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );

        assertTrue(
            capabilityProbe.contains(
                "EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME"
            )
        );
        assertTrue(
            capabilityProbe.contains(
                "physicalDevice.hasDeviceExtension("
            )
        );
        assertFalse(negotiator.contains("VK_EXT_memory_budget"));
        assertFalse(negotiator.contains("EXTMemoryBudget"));
        assertFalse(bootstrap.contains("VK_EXT_memory_budget"));
        assertFalse(bootstrap.contains("EXTMemoryBudget"));
    }

    @Test
    void deviceAttachUsesConstructorTailAndDetachUsesCloseHead()
        throws Exception {
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanDeviceMixin.java"
        );
        String constructorAnnotation = annotationBefore(
            deviceMixin,
            "private void nvidiaDlss$connectStreamline("
        );
        String closeAnnotation = annotationBefore(
            deviceMixin,
            "private void blockframe$prepareDeviceClose("
        );
        String constructor = methodBody(
            deviceMixin,
            "nvidiaDlss$connectStreamline"
        );
        String close = methodBody(
            deviceMixin,
            "blockframe$prepareDeviceClose"
        );

        assertTrue(constructorAnnotation.contains("method = \"<init>\""));
        assertTrue(constructorAnnotation.contains("at = @At(\"TAIL\")"));
        assertTrue(closeAnnotation.contains("method = \"close\""));
        assertTrue(closeAnnotation.contains("at = @At(\"HEAD\")"));
        assertTrue(
            constructor.indexOf(
                "BlockframeRuntime.vulkanDeviceConnected(device);"
            ) < constructor.indexOf("DlssBootstrap.connectDevice(device);")
        );
        assertTrue(
            constructor.indexOf(
                "BlockframeRuntime.vulkanDeviceConnected(device);"
            ) < constructor.indexOf(
                "if (DlssBootstrap.connectedTo(device))"
            )
        );
        assertTrue(
            close.indexOf(
                "BlockframeRuntime.vulkanDeviceClosing(device);"
            ) < close.indexOf("DlssRenderer.prepareDeviceClose()")
        );

        String engine = source(
            "src/main/java/de/morau/blockframe/core/BlockframeEngine.java"
        );
        String engineClose = methodBody(engine, "vulkanDeviceClosing");
        assertTrue(
            engineClose.indexOf(
                "this.physicalMemoryTelemetry.vulkanDeviceClosing(device);"
            ) < engineClose.indexOf(
                "GpuSubmissionBreadcrumbs breadcrumbs"
            )
        );
        assertTrue(
            engineClose.indexOf(
                "this.physicalMemoryTelemetry.vulkanDeviceClosing(device);"
            ) < engineClose.indexOf("this.cachedDevice = null;")
        );
    }

    @Test
    void driverQueryIsStackScopedHostOnlyAndBudgetIndependent()
        throws Exception {
        String probe = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanMemoryBudgetProbe.java"
        );
        String telemetry = source(
            "src/main/java/de/morau/blockframe/core/diagnostics/"
                + "PhysicalMemoryTelemetry.java"
        );

        assertTrue(probe.contains("MemoryStack.stackPush()"));
        assertTrue(
            probe.contains(
                "VK12.vkGetPhysicalDeviceMemoryProperties2("
            )
        );
        assertTrue(
            probe.contains("VK_MEMORY_HEAP_DEVICE_LOCAL_BIT")
        );
        assertFalse(probe.contains("waitIdle"));
        assertFalse(probe.contains("vkDeviceWaitIdle"));
        assertFalse(probe.contains("Fence"));
        assertFalse(probe.contains("Semaphore"));
        assertFalse(probe.contains("MemoryBudgetManager"));
        assertFalse(probe.contains("memAlloc"));
        assertFalse(probe.contains("memCalloc"));
        assertFalse(telemetry.contains("MemoryBudgetManager"));
        assertFalse(telemetry.contains("new Thread"));
        assertFalse(telemetry.contains("Executor"));
        assertFalse(telemetry.contains("Scheduler"));
    }

    @Test
    void unavailableDebugStatesNeverPrintNumericZeros()
        throws Exception {
        String engine = source(
            "src/main/java/de/morau/blockframe/core/BlockframeEngine.java"
        );
        String ram = methodBody(engine, "physicalRamDebugLine");
        String vram = methodBody(engine, "physicalVramDebugLine");

        assertTrue(
            ram.contains(
                "!= PhysicalMemoryTelemetry.RamStatus.AVAILABLE"
            )
        );
        assertTrue(ram.contains("(no numeric value)"));
        assertTrue(
            vram.contains(
                "!= PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE"
            )
        );
        assertTrue(vram.contains("(no numeric value)"));
        assertTrue(vram.contains("process estimate, may be shared"));
    }

    @Test
    void telemetryDoesNotCrossTheAcceptedOutlineScratchBoundary()
        throws Exception {
        for (
            String relative
            : List.of(
                "src/main/java/de/morau/nvidiadlss/mixin/"
                    + "GameRendererMixin.java",
                "src/main/java/de/morau/nvidiadlss/"
                    + "NativeBlockOutlinePoseStackScratch.java",
                "src/main/java/de/morau/nvidiadlss/"
                    + "RenderThreadPoseStackScratch.java",
                "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
            )
        ) {
            String text = source(relative);
            assertFalse(
                text.contains("PhysicalMemoryTelemetry"),
                relative
            );
            assertFalse(text.contains("VulkanMemoryBudgetProbe"), relative);
            assertFalse(text.contains("sampleIfDue"), relative);
        }
    }

    @Test
    void finalEngineCloseSealsTelemetryBeforeLogicalBudgets()
        throws Exception {
        String engine = source(
            "src/main/java/de/morau/blockframe/core/BlockframeEngine.java"
        );
        String close = methodBody(engine, "close");

        assertTrue(
            close.indexOf("this.physicalMemoryTelemetry.close();")
                < close.indexOf("this.memoryBudgets.closeAndReport()")
        );
    }

    private static String annotationBefore(
        String source,
        String methodMarker
    ) {
        int method = source.indexOf(methodMarker);
        assertTrue(method >= 0, "missing method: " + methodMarker);
        int annotation = source.lastIndexOf("@Inject", method);
        assertTrue(annotation >= 0, "missing @Inject: " + methodMarker);
        return source.substring(annotation, method);
    }

    private static String methodBody(String source, String method) {
        Pattern declaration = Pattern.compile(
            "(?m)^\\s*(?:(?:private|protected|public|static|final"
                + "|synchronized|abstract|native)\\s+)*"
                + "(?:<[^>\\r\\n]+>\\s+)?"
                + "[A-Za-z_$@][A-Za-z0-9_.$<>?,\\[\\]@]*\\s+"
                + Pattern.quote(method)
                + "\\s*\\("
        );
        Matcher matcher = declaration.matcher(source);
        assertTrue(matcher.find(), "missing method: " + method);
        int open = source.indexOf('{', matcher.start());
        assertTrue(open >= 0, "missing body: " + method);
        int close = matchingBrace(source, open);
        assertTrue(close > open, "unclosed body: " + method);
        return source.substring(open, close + 1);
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        int state = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length()
                ? source.charAt(index + 1)
                : '\0';
            if (state == 1) {
                if (current == '\n' || current == '\r') {
                    state = 0;
                }
                continue;
            }
            if (state == 2) {
                if (current == '*' && next == '/') {
                    state = 0;
                    index++;
                }
                continue;
            }
            if (state == 3 || state == 4) {
                if (current == '\\') {
                    index++;
                } else if (
                    (state == 3 && current == '"')
                        || (state == 4 && current == '\'')
                ) {
                    state = 0;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                state = 1;
                index++;
            } else if (current == '/' && next == '*') {
                state = 2;
                index++;
            } else if (current == '"') {
                state = 3;
            } else if (current == '\'') {
                state = 4;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(
            projectRoot().resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }

    private static Path projectRoot() {
        return Path.of(
            System.getProperty("blockframe.projectDir")
        );
    }
}
