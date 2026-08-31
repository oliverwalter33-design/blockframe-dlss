package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssSamplerGenerationIntegrationSourceContractTest {
    @Test
    void rendererCommitsSamplerGenerationsForFastRefreshAndResizePaths()
        throws Exception {
        String renderer = source("DlssRenderer.java");
        String ensure = section(
            renderer,
            "private static void ensureResources(",
            "private static void logIntegrationState("
        );

        assertTrue(
            occurrences(
                ensure,
                "activateMaterialSamplerGeneration(backend, mode)"
            ) >= 3
        );
        assertTrue(
            ensure.indexOf("lowWidth = desiredWidth;")
                < ensure.lastIndexOf(
                    "activateMaterialSamplerGeneration(backend, mode)"
                )
        );
        assertTrue(
            ensure.indexOf("outputHeight = height;")
                < ensure.lastIndexOf(
                    "activateMaterialSamplerGeneration(backend, mode)"
                )
        );
    }

    @Test
    void reloadAndInactiveStatesCannotReuseAStaleGeneration()
        throws Exception {
        String renderer = source("DlssRenderer.java");
        String refresh = section(
            renderer,
            "static void requestOptimalSettingsRefresh(",
            "static void requestWorldHistoryReset("
        );
        String begin = section(
            renderer,
            "public static RenderTarget beginFrame(",
            "public static Matrix4f applyWorldJitter("
        );

        assertTrue(refresh.contains("samplerReloadEpoch++"));
        assertTrue(
            begin.indexOf("DlssSamplerPolicy.deactivateGeneration(")
                < begin.indexOf("return highTarget;")
        );
        assertTrue(begin.contains("boolean minimizedOutput"));
    }

    @Test
    void policyCreatesOnlyAtGenerationActivationAndUsesLifecycleForClose()
        throws Exception {
        String policy = source("DlssSamplerPolicy.java");
        String activation = section(
            policy,
            "public static synchronized boolean activateGeneration(",
            "public static synchronized boolean deactivateGeneration("
        );
        String selection = section(
            policy,
            "public static synchronized GpuSampler materialSampler(",
            "public static synchronized void deviceConnected("
        );

        assertTrue(activation.contains("cacheLifecycle.switchTo("));
        assertTrue(activation.contains("() -> createCache(device)"));
        assertFalse(selection.contains("createCache(device)"));
        assertFalse(selection.contains("|| cutoutTerrain"));
        assertTrue(selection.contains("float biasDelta ="));
        assertTrue(selection.contains("DlssRenderer.currentLodBias()"));
        assertTrue(
            policy.contains(
                "new FixedMaterialSamplerCacheLifecycle("
            )
        );
        assertTrue(
            policy.contains(
                "lifecycle.prepareDeviceClose(PRODUCTION_CLOSER)"
            )
        );
        assertTrue(
            policy.contains(
                "lifecycle.finishDeviceCloseAfterEncoderDrain()"
            )
        );
    }

    private static String source(String name) throws Exception {
        return Files.readString(
            Path.of("src/main/java/de/morau/nvidiadlss", name)
        );
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0, "missing start marker: " + startMarker);
        assertTrue(end > start, "missing end marker: " + endMarker);
        return source.substring(start, end);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
