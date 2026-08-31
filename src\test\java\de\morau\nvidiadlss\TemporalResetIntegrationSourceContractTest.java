package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TemporalResetIntegrationSourceContractTest {
    @Test
    void networkLifecycleUsesAuthoritativeNeoForgeTwentySixTwoEvents()
        throws Exception {
        String mod = source(
            "src/main/java/de/morau/nvidiadlss/NvidiaDlssMod.java"
        );
        assertTrue(
            mod.contains(
                "ClientPlayerNetworkEvent.LoggingIn event"
            )
        );
        assertTrue(
            mod.contains(
                "ClientPlayerNetworkEvent.LoggingOut event"
            )
        );
        assertTrue(
            mod.contains("ClientPlayerNetworkEvent.Clone event")
        );
        assertTrue(
            mod.contains(
                "requestWorldHistoryReset(\"Weltbeitritt\")"
            )
        );
        assertTrue(
            mod.contains(
                "requestWorldHistoryReset(\"Weltverlassen\")"
            )
        );
        assertTrue(
            mod.contains("requestReset(\"Tod/Respawn\")")
        );
        assertTrue(
            mod.contains(
                "requestOptimalSettingsRefresh(\"Resource-Reload\")"
            )
        );
    }

    @Test
    void exactPlayerAndEntityTeleportPacketsResetAtClientThreadReturn()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "ClientPacketListenerMixin.java"
        );
        String mixinJson = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );

        assertTrue(
            mixin.contains(
                "@Mixin(ClientPacketListener.class)"
            )
        );
        assertTrue(
            mixin.contains(
                "method = \"handleMovePlayer\", "
                    + "at = @At(\"RETURN\")"
            )
        );
        assertTrue(
            mixin.contains(
                "ClientboundPlayerPositionPacket packet"
            )
        );
        assertTrue(
            mixin.contains(
                "method = \"handleTeleportEntity\", "
                    + "at = @At(\"RETURN\")"
            )
        );
        assertTrue(
            mixin.contains(
                "ClientboundTeleportEntityPacket packet"
            )
        );
        assertTrue(
            mixin.contains(
                "requestReset(\"Spieler-Teleport\")"
            )
        );
        assertTrue(
            mixin.contains(
                "requestReset(\"Entity-Teleport\")"
            )
        );
        assertTrue(
            mixinJson.contains(
                "\"ClientPacketListenerMixin\""
            )
        );
    }

    @Test
    void renderObservationMatrixCoversEveryLocalDiscontinuity()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String detection = section(
            renderer,
            "private static void detectHistoryBreaks(",
            "private static void beginTransformFrame()"
        );
        String worldReset = section(
            renderer,
            "static void requestWorldHistoryReset(",
            "public static boolean isWorldPass()"
        );
        String jitter = section(
            renderer,
            "public static Matrix4f applyWorldJitter(",
            "private static void ensureResources("
        );

        assertTrue(
            detection.contains(
                "identityChanged(previousLevel, level)"
            )
        );
        assertTrue(
            detection.contains(
                "identityChanged("
            )
                && detection.contains(
                    "previousCameraEntity"
                )
        );
        assertTrue(
            detection.contains(
                "cameraPositionCut("
            )
        );
        assertTrue(
            detection.contains(
                "cameraOrientationCut("
            )
        );
        assertTrue(
            detection.contains(
                "previousCameraDeadOrDyingValid"
            )
        );
        assertTrue(
            detection.contains(
                "state.entityRenderState.isDeadOrDying"
            )
        );
        assertTrue(jitter.contains("observeEffectiveWorldFov("));
        assertTrue(
            jitter.contains(
                "effectiveVerticalFovRadians("
            )
        );
        assertTrue(jitter.contains("projection.m11()"));
        assertTrue(
            jitter.indexOf("observeEffectiveWorldFov(")
                < jitter.indexOf("scratch.captureUnjitteredProjection(")
        );
        assertTrue(
            jitter.indexOf("observeEffectiveWorldFov(")
                < jitter.indexOf("projection.m20(")
        );
        assertTrue(
            !detection.contains(
                "state.projectionMatrix.m11()"
            )
        );
        assertTrue(detection.contains("\"FOV-Wechsel\""));
        assertTrue(detection.contains("\"Perspektivwechsel\""));
        assertTrue(detection.contains("\"GUI-Skalierung\""));
        assertTrue(detection.contains("\"Desktop-/VR-Wechsel\""));
        assertTrue(worldReset.contains("clearMotionObjectHistory();"));
    }

    @Test
    void existingRendererAndDeviceBoundariesRemainResetSources()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String gameRendererMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "GameRendererMixin.java"
        );

        assertTrue(
            gameRendererMixin.contains(
                "@Inject(method = {\"resetData\", \"resize\"}, "
                    + "at = @At(\"HEAD\"))"
            )
        );
        assertTrue(
            gameRendererMixin.contains(
                "requestReset("
                    + "\"Ressourcen-/Pipeline-Neuladen\")"
            )
        );
        assertTrue(
            renderer.contains(
                "requestReset(\"Renderziel/Modus geändert\")"
            )
        );
        assertTrue(
            renderer.contains(
                "requestReset(\"neue Vulkan-Gerätegeneration\")"
            )
        );
        assertTrue(
            renderer.contains(
                "requestReset(\"DLSS-Framefehler\")"
            )
        );
    }

    private static String section(
        String source,
        String start,
        String end
    ) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0, () -> "missing start marker: " + start);
        assertTrue(to > from, () -> "missing end marker: " + end);
        return source.substring(from, to);
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(
            System.getProperty("blockframe.projectDir")
        );
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
