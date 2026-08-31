package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlineNativeLifecycleSourceContractTest {
    @Test
    void operationResultsAndAsyncDiagnosticsHaveSeparateLockedStorage()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String nativeApi = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/NativeStreamline.java"
        );
        String callback = section(
            bridge,
            "void logCallback(",
            "std::wstring fromJava("
        );
        String operationSetter = section(
            bridge,
            "void setOperationMessage(",
            "std::string operationMessageSnapshot("
        );
        String operationSnapshot = section(
            bridge,
            "std::string operationMessageSnapshot(",
            "void setAsyncDiagnostic("
        );
        String diagnosticSetter = section(
            bridge,
            "void setAsyncDiagnostic(",
            "std::string asyncDiagnosticSnapshot("
        );
        String diagnosticSnapshot = section(
            bridge,
            "std::string asyncDiagnosticSnapshot(",
            "std::unordered_set<uint32_t>& activeViewports("
        );
        String lastMessage = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastMessage",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastDiagnostic"
        );
        String lastDiagnostic = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastDiagnostic",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_shutdown"
        );

        assertTrue(bridge.contains("std::mutex gMessageMutex;"));
        assertTrue(bridge.contains("std::string gOperationMessage"));
        assertTrue(bridge.contains("std::string gAsyncDiagnostic"));
        assertFalse(bridge.contains("gLastMessage"));
        assertTrue(operationSetter.contains("lock(gMessageMutex)"));
        assertTrue(operationSnapshot.contains("lock(gMessageMutex)"));
        assertTrue(diagnosticSetter.contains("lock(gMessageMutex)"));
        assertTrue(diagnosticSnapshot.contains("lock(gMessageMutex)"));
        assertTrue(callback.contains("setAsyncDiagnostic("));
        assertFalse(callback.contains("setOperationMessage("));
        assertTrue(lastMessage.contains("operationMessageSnapshot()"));
        assertTrue(lastDiagnostic.contains("asyncDiagnosticSnapshot()"));
        assertTrue(nativeApi.contains("public static native String lastDiagnostic();"));

        int finalMessageLock = bridge.lastIndexOf("lock(gMessageMutex)");
        int firstStreamlineCall = bridge.indexOf("gInit(preferences");
        assertTrue(finalMessageLock >= 0);
        assertTrue(firstStreamlineCall > finalMessageLock);
    }

    @Test
    void freeResourcesIsMandatoryAndViewportResetIsResultBearing()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String nativeApi = source(
            "src/main/java/de/morau/nvidiadlss/nativebridge/NativeStreamline.java"
        );
        String bootstrap = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_bootstrap",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_setVulkanInfo"
        );
        String reset = section(
            bridge,
            "extern \"C\" JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_resetViewport",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastMessage"
        );

        assertTrue(bootstrap.contains(
            "gFreeResources = resolve<PFun_slFreeResources>(\"slFreeResources\")"
        ));
        assertTrue(bootstrap.contains("!gFreeResources"));
        assertTrue(reset.contains("JNIEXPORT jint JNICALL"));
        assertTrue(reset.contains("return -1220;"));
        assertTrue(reset.contains("return -1221;"));
        assertTrue(nativeApi.contains(
            "public static native int resetViewport(int viewportId);"
        ));
    }

    @Test
    void normalShutdownFreesEveryTrackedFeatureBeforeSlShutdown()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String shutdown = bridge.substring(
            bridge.indexOf(
                "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_shutdown"
            )
        );

        int snapshot = shutdown.indexOf(
            "activeFeatureViewportsSnapshot()"
        );
        int free = shutdown.indexOf("gFreeResources(", snapshot);
        int record = shutdown.indexOf(
            "recordFreeResult(feature, viewportId, succeeded)",
            free
        );
        int nativeShutdown = shutdown.indexOf("gShutdown()", record);
        int failureGuard = shutdown.indexOf(
            "if (firstFreeFailure != 0)",
            nativeShutdown
        );

        assertTrue(snapshot >= 0);
        assertTrue(free > snapshot);
        assertTrue(record > free);
        assertTrue(nativeShutdown > record);
        assertTrue(failureGuard > nativeShutdown);
        assertTrue(shutdown.contains("markCleanupUncertain()"));
        assertTrue(shutdown.contains("return kCleanupUnconfirmed;"));
    }

    @Test
    void onlySuccessfulEvaluationsBecomeActiveAndFailedFreesStayActive()
        throws Exception {
        String bridge = source("native/nvidia_dlss_bridge.cpp");
        String evaluate = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_evaluate",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_resetViewport"
        );
        String recordEvaluate = section(
            bridge,
            "void recordEvaluateResult(",
            "void recordFreeResult("
        );
        String recordFree = section(
            bridge,
            "void recordFreeResult(",
            "void clearActiveViewports("
        );
        String reset = section(
            bridge,
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_resetViewport",
            "Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastMessage"
        );

        assertTrue(recordEvaluate.indexOf("if (!succeeded) return;")
            < recordEvaluate.indexOf(".insert(viewportId)"));
        assertTrue(recordFree.indexOf("if (!succeeded) return;")
            < recordFree.indexOf(".erase(viewportId)"));

        int dlssEvaluate = evaluate.indexOf(
            "gEvaluateFeature(sl::kFeatureDLSS"
        );
        int dlssRecord = evaluate.indexOf(
            "TrackedFeature::eDlss",
            dlssEvaluate
        );
        int nisEvaluate = evaluate.indexOf(
            "gEvaluateFeature(sl::kFeatureNIS"
        );
        int nisRecord = evaluate.indexOf(
            "TrackedFeature::eNis",
            nisEvaluate
        );
        assertTrue(dlssEvaluate >= 0);
        assertTrue(dlssRecord > dlssEvaluate);
        assertTrue(nisEvaluate > dlssRecord);
        assertTrue(nisRecord > nisEvaluate);

        int noActive = reset.indexOf("if (!dlssActive && !nisActive)");
        int noActiveSuccess = reset.indexOf("return 0;", noActive);
        int firstFree = reset.indexOf("gFreeResources(");
        assertTrue(noActive >= 0);
        assertTrue(noActiveSuccess > noActive);
        assertTrue(firstFree > noActiveSuccess);
        assertTrue(reset.contains(
            "recordFreeResult(TrackedFeature::eDlss, id, succeeded)"
        ));
        assertTrue(reset.contains(
            "recordFreeResult(TrackedFeature::eNis, id, succeeded)"
        ));
        assertTrue(reset.contains("if (firstFailure != 0)"));
        assertTrue(reset.contains("return firstFailure;"));
    }

    @Test
    void resizeDrainsAndConfirmsNativeCleanupBeforeAnyResourceMutation()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String ensure = section(
            renderer,
            "private static void ensureResources(",
            "private static void labelLowResolutionTarget("
        );

        int waitIdle = ensure.indexOf("backend.graphicsQueue().waitIdle();");
        int reset = ensure.indexOf(
            "int resetResult = NativeStreamline.resetViewport(0);"
        );
        int resetGuard = ensure.indexOf("if (resetResult != 0)");
        int create = ensure.indexOf("DlssAuxiliaryResources.create(");
        int resize = ensure.indexOf("lowTarget.resize(desiredWidth, desiredHeight)");
        int publish = ensure.indexOf("auxiliaryResources = replacement;");
        int oldClose = ensure.indexOf("previousResources.close();");
        String order = "wait=" + waitIdle
            + " reset=" + reset
            + " guard=" + resetGuard
            + " create=" + create
            + " resize=" + resize
            + " publish=" + publish
            + " close=" + oldClose;

        assertTrue(waitIdle >= 0, order);
        assertTrue(reset > waitIdle, order);
        assertTrue(resetGuard > reset, order);
        assertTrue(create > resetGuard, order);
        assertTrue(resize > create, order);
        assertTrue(publish > resize, order);
        assertTrue(oldClose > publish, order);
        assertFalse(
            ensure.substring(publish).contains(
                "NativeStreamline.resetViewport(0)"
            )
        );
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing start marker: " + startMarker);
        int end = source.indexOf(endMarker, start);
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
