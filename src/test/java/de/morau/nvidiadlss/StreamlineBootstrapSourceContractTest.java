package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamlineBootstrapSourceContractTest {
    @Test
    void failedBootstrapTransactionReleasesItsModuleAndAllPointers()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String reset = section(
            bridge,
            "void clearResolvedFunctions()",
            "void markCleanupUncertain()"
        );
        String deviceReset = section(
            bridge,
            "void clearDeviceFunctions()",
            "void clearResolvedFunctions()"
        );
        String moduleRelease = section(
            bridge,
            "bool releaseModuleAndReset(DWORD& releaseError)",
            "jint rollbackFailedBootstrap(jint result)"
        );
        String rollback = section(
            bridge,
            "jint rollbackFailedBootstrap(jint result)",
            "void logCallback("
        );
        String bootstrap = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_bootstrap",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_setVulkanInfo"
        );

        for (
            String pointer : List.of(
                "gInit",
                "gShutdown",
                "gGetRequirements",
                "gSetVulkanInfo",
                "gIsFeatureSupported",
                "gGetFeatureFunction",
                "gGetNewFrameToken",
                "gSetConstants",
                "gEvaluateFeature",
                "gFreeResources",
                "gGetDeviceProcAddrProxy"
            )
        ) {
            assertTrue(reset.contains(pointer + " = nullptr;"));
        }
        assertTrue(reset.contains("clearDeviceFunctions();"));
        for (
            String pointer : List.of(
                "gGetOptimalSettings",
                "gSetOptions",
                "gNisSetOptions",
                "gQueuePresentProxy"
            )
        ) {
            assertTrue(deviceReset.contains(pointer + " = nullptr;"));
        }
        int freeLibrary = moduleRelease.indexOf("FreeLibrary(module)");
        int clearModule = moduleRelease.indexOf("gModule = nullptr;");
        assertTrue(freeLibrary >= 0);
        assertTrue(clearModule > freeLibrary);
        assertTrue(moduleRelease.contains("gCleanupUncertain = true;"));
        assertTrue(rollback.contains("releaseModuleAndReset(releaseError)"));
        assertTrue(!rollback.contains("gShutdown()"));
        assertTrue(bootstrap.contains("if (gCleanupUncertain)"));
        assertTrue(bootstrap.contains("if (!gModule)"));
        assertTrue(
            bootstrap.contains("return rollbackFailedBootstrap(-1001);")
        );
        assertTrue(
            bootstrap.contains(
                "return rollbackFailedBootstrap(static_cast<jint>(result));"
            )
        );
    }

    @Test
    void cleanupUncertaintyIsStickyAcrossNativeAndJavaRetryGates()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String nativeApi = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/NativeStreamline.java"
        );

        assertTrue(
            bridge.contains(
                "constexpr jint kCleanupUnconfirmed = INT32_MIN + 0xBF;"
            )
        );
        assertTrue(
            nativeApi.contains(
                "CLEANUP_UNCONFIRMED = Integer.MIN_VALUE + 0xBF"
            )
        );
        int resultCheck = bootstrap.indexOf(
            "result == NativeStreamline.CLEANUP_UNCONFIRMED"
        );
        int stickyState = bootstrap.indexOf(
            "nativeShutdownUncertain = true",
            resultCheck
        );
        int nativeMessage = bootstrap.indexOf(
            "NativeStreamline.lastMessage()",
            resultCheck
        );
        assertTrue(resultCheck >= 0);
        assertTrue(stickyState > resultCheck);
        assertTrue(nativeMessage > stickyState);
    }

    @Test
    void shutdownResultAndModuleReleaseReachTheJavaCleanupProof()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String nativeShutdown = section(
            bridge,
            "JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_shutdown",
            null
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String nativeApi = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/NativeStreamline.java"
        );

        int trackedSnapshot = nativeShutdown.indexOf(
            "activeFeatureViewportsSnapshot()"
        );
        int trackedFree = nativeShutdown.indexOf(
            "gFreeResources(",
            trackedSnapshot
        );
        int shutdown = nativeShutdown.indexOf(
            "const sl::Result shutdownResult = gShutdown();",
            trackedFree
        );
        int shutdownGuard = nativeShutdown.indexOf(
            "if (shutdownResult != sl::Result::eOk)",
            shutdown
        );
        assertTrue(trackedSnapshot >= 0);
        assertTrue(trackedFree > trackedSnapshot);
        assertTrue(shutdown > trackedFree);
        assertTrue(shutdownGuard > shutdown);
        assertTrue(nativeShutdown.contains("markCleanupUncertain();"));
        assertTrue(nativeShutdown.contains("releaseModuleAndReset(releaseError)"));
        assertTrue(nativeApi.contains("public static native int shutdown();"));
        assertTrue(bootstrap.contains("int result = NativeStreamline.shutdown();"));
        assertTrue(
            bootstrap.contains(
                "completed = runNativeShutdownAndReport("
            )
        );
    }

    @Test
    void eachVulkanDeviceBindingStartsWithoutPriorGenerationFunctions()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String deviceBinding = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_setVulkanInfo",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_queuePresent"
        );
        int reset = deviceBinding.indexOf("clearDeviceFunctions();");
        int deviceHandoff = deviceBinding.indexOf("gSetVulkanInfo(info)");
        int nisResolution = deviceBinding.indexOf(
            "gNisSetOptions = reinterpret_cast"
        );

        assertTrue(reset >= 0);
        assertTrue(deviceHandoff > reset);
        assertTrue(nisResolution > deviceHandoff);
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing start marker: " + startMarker);
        int end = endMarker == null
            ? source.length()
            : source.indexOf(endMarker, start);
        assertTrue(end > start, "missing end marker: " + endMarker);
        return source.substring(start, end);
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
