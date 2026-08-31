package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeveloperDiagnosticsReleaseGateTest {
    @Test
    void allLegacySubordinateSwitchesAreInertWithoutExplicitMaster() {
        Map<String, String> oldProfile = Map.ofEntries(
            Map.entry("nvidia_dlss.devSequenceStartFrame", "600"),
            Map.entry("nvidia_dlss.devSequenceFrames", "240"),
            Map.entry("nvidia_dlss.devSequenceRoi", "768"),
            Map.entry("nvidia_dlss.devSequenceId", "iq-ab"),
            Map.entry("nvidia_dlss.devSequenceMotion", "COMPOSITE"),
            Map.entry("nvidia_dlss.devForceThirdPerson", "true"),
            Map.entry("legacy.temporal.hint", "ONE_RECT"),
            Map.entry("legacy.motion.fallback", "ZERO"),
            Map.entry("nvidia_dlss.auditAutoCapture", "true")
        );

        assertFalse(DeveloperDiagnostics.enabled(oldProfile, Map.of()));
        assertTrue(TemporalHintAudit.policy(oldProfile).releaseSafe());
    }

    @Test
    void onlyExplicitTrueMasterEnablesDevelopmentDiagnostics() {
        assertFalse(DeveloperDiagnostics.enabled(Map.of(), Map.of()));
        assertFalse(DeveloperDiagnostics.enabled(
            Map.of(DeveloperDiagnostics.PROPERTY, "false"),
            Map.of()
        ));
        assertTrue(DeveloperDiagnostics.enabled(
            Map.of(DeveloperDiagnostics.PROPERTY, "true"),
            Map.of()
        ));
        assertTrue(DeveloperDiagnostics.enabled(
            Map.of(),
            Map.of(DeveloperDiagnostics.ENVIRONMENT, "TRUE")
        ));
    }

    @Test
    void normalRuntimeCannotArmCapture() {
        assertFalse(DeveloperDiagnostics.enabled());
        DlssDebugCapture.request();
        assertFalse(DlssDebugCapture.requested());
        for (int frame = 0; frame < 1_000_000; frame++) {
            assertFalse(DlssDebugCapture.shouldCaptureFrame(frame));
        }
    }

    @Test
    void productionSourcesGateEveryAutomaticOrReadbackEntryPoint()
        throws Exception {
        String capture = source(
            "src/main/java/de/morau/nvidiadlss/DlssDebugCapture.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String sequence = source(
            "src/main/java/de/morau/nvidiadlss/ThirdPersonDlaaSequenceDriver.java"
        );
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/GameRendererMixin.java"
        );
        String diagnosticsMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "GameRendererDiagnosticsMixin.java"
        );
        String mod = source(
            "src/main/java/de/morau/nvidiadlss/NvidiaDlssMod.java"
        );
        String resources = source(
            "src/main/java/de/morau/nvidiadlss/DlssAuxiliaryResources.java"
        );
        String policy = source(
            "src/main/java/de/morau/blockframe/core/state/RuntimeFeaturePolicy.java"
        );
        String plugin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/DlssMixinPlugin.java"
        );
        String levelRendererDiagnostics = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "LevelRendererDiagnosticsMixin.java"
        );
        String shader = source("native/shaders/motion_vectors.comp");
        String nativeBuild = source("native/build-native.ps1");
        String gradle = source("build.gradle");

        assertTrue(capture.contains(
            "return DeveloperDiagnostics.enabled()\n"
                + "            && (requested || isSequenceFrame(frame));"
        ));
        assertTrue(capture.indexOf("if (!DeveloperDiagnostics.enabled())")
            < capture.indexOf("Files.createDirectories(directory)"));
        assertTrue(renderer.contains(
            "DeveloperDiagnostics.ENABLED\n"
                + "                    && DlssDebugCapture.shouldCaptureFrame"
        ));
        assertTrue(sequence.contains("!DeveloperDiagnostics.enabled()"));
        assertTrue(mixin.contains("BlockframeRuntime.beginFrame();"));
        assertTrue(mixin.contains("BlockframeRuntime.endFrame();"));
        assertFalse(mixin.contains("DlssDebugCapture"));
        assertFalse(mixin.contains("GpuPassDiagnostics"));
        assertTrue(diagnosticsMixin.contains("DlssDebugCapture"));
        assertTrue(diagnosticsMixin.contains("GpuPassDiagnostics"));
        assertTrue(mod.contains(
            "if (developerDiagnostics) {\n"
                + "            NeoForge.EVENT_BUS.addListener(NvidiaDlssMod::onKey);"
        ));
        assertTrue(resources.contains("GpuTexture depthDebugTexture = null;"));
        assertTrue(resources.contains("if (developerDiagnostics) {"));
        assertFalse(resources.contains("diagnosticWidth"));
        assertFalse(resources.contains("diagnosticHeight"));
        assertTrue(shader.contains("#if BLOCKFRAME_DEVELOPER_DIAGNOSTICS"));
        assertTrue(nativeBuild.contains(
            "-DBLOCKFRAME_DEVELOPER_DIAGNOSTICS=0"
        ));
        assertTrue(nativeBuild.contains(
            "-DBLOCKFRAME_DEVELOPER_DIAGNOSTICS=1"
        ));
        assertTrue(policy.contains(
            "this.developerDiagnostics\n"
                + "                    && this.engine.profilerEnabled()"
        ));
        assertTrue(plugin.contains(
            "if (isDeveloperDiagnosticsMixin(mixinClassName))"
        ));
        assertTrue(levelRendererDiagnostics.contains("System.nanoTime()"));
        assertTrue(mod.contains(
            "if (developerDiagnostics) {\n"
                + "            FastStartRuntime.initialize();"
        ));
        assertTrue(capture.contains("copyTextureToBuffer"));
        assertTrue(capture.contains("image.writeToFile(destination)"));
        assertFalse(gradle.contains("devBiasHint"));
        assertFalse(gradle.contains("devInvalidDepthMotionHint"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
