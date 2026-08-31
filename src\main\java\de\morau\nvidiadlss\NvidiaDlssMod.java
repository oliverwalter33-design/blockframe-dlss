package de.morau.nvidiadlss;

import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.faststart.FastStartRuntime;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainPipelines;
import de.morau.nvidiadlss.mixin.DlssMixinPlugin;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(NvidiaDlssMod.MOD_ID)
public final class NvidiaDlssMod {
    public static final String MOD_ID = "voxellift";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public NvidiaDlssMod(IEventBus modBus, ModContainer container) {
        boolean developerDiagnostics = DeveloperDiagnostics.enabled();
        if (developerDiagnostics) {
            FastStartRuntime.initialize();
        }
        DlssConfig.Snapshot dlss = DlssConfig.snapshot();
        BlockframeRuntime.initializeClient(
            container.getModInfo().getVersion().toString(),
            "26.2",
            dlss.mode().id(),
            dlss.sharpening().id(),
            dlss.sharpeningAmount(),
            dlss.entityHistoryBackend().id()
        );
        boolean sodiumPresent =
            DlssMixinPlugin.sodiumPresent() || ModList.get().isLoaded("sodium");
        if (developerDiagnostics) {
            modBus.addListener(NvidiaDlssMod::registerGuiLayers);
        }
        if (!sodiumPresent) {
            modBus.addListener(NativeTerrainPipelines::register);
        }
        if (developerDiagnostics) {
            modBus.addListener(NvidiaDlssMod::onLoadComplete);
        }
        if (developerDiagnostics) {
            NeoForge.EVENT_BUS.addListener(NvidiaDlssMod::onKey);
            LOGGER.warn(
                "BlockFrame developer diagnostics explicitly enabled; "
                    + "F8/F9 and opt-in capture/audit controls are available"
            );
        }
        NeoForge.EVENT_BUS.addListener(NvidiaDlssMod::onClientStarted);
        if (developerDiagnostics) {
            NeoForge.EVENT_BUS.addListener(
                NvidiaDlssMod::onRenderFrameFinished
            );
            NeoForge.EVENT_BUS.addListener(
                NvidiaDlssMod::onServerAboutToStart
            );
        }
        NeoForge.EVENT_BUS.addListener(
            NvidiaDlssMod::onResourceLoadFinished
        );
        NeoForge.EVENT_BUS.addListener(
            NvidiaDlssMod::onPlayerLoggingIn
        );
        NeoForge.EVENT_BUS.addListener(
            NvidiaDlssMod::onPlayerLoggingOut
        );
        NeoForge.EVENT_BUS.addListener(
            NvidiaDlssMod::onPlayerClone
        );
        NeoForge.EVENT_BUS.addListener(NvidiaDlssMod::onClientStopping);
        NeoForge.EVENT_BUS.addListener(NvidiaDlssMod::onClientStopped);
        if (sodiumPresent) {
            LOGGER.info(
                "Sodium detected: Sodium remains terrain owner; "
                    + "BlockFrame native-terrain mixins are disabled while "
                    + "DLSS/Vulkan/lifecycle mixins remain active"
            );
        }
        if (developerDiagnostics) {
            FoliageAudit.announce();
        }
        LOGGER.info("BlockFrame DLSS {} für Minecraft 26.2 geladen", container.getModInfo().getVersion());
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(MOD_ID, "debug_overlay"),
            (graphics, deltaTracker) -> DlssDebugOverlay.render(graphics));
    }

    private static void onKey(InputEvent.Key event) {
        if (event.getKey() == GLFW.GLFW_KEY_F8 && event.getAction() == GLFW.GLFW_PRESS) {
            DlssDebugOverlay.toggle();
        } else if (event.getKey() == GLFW.GLFW_KEY_F9 && event.getAction() == GLFW.GLFW_PRESS) {
            DlssDebugCapture.request();
        }
    }

    private static void onClientStarted(ClientStartedEvent event) {
        BlockframeRuntime.clientStarted();
        if (DeveloperDiagnostics.enabled()) {
            FastStartRuntime.clientConstructionFinished();
        }
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(FastStartRuntime::modLifecycleComplete);
    }

    private static void onRenderFrameFinished(RenderFrameEvent.Post event) {
        FastStartRuntime.renderFrameFinished(net.minecraft.client.Minecraft.getInstance());
    }

    private static void onServerAboutToStart(
        ServerAboutToStartEvent event
    ) {
        FastStartRuntime.serverDataReady();
    }

    private static void onResourceLoadFinished(
        ClientResourceLoadFinishedEvent event
    ) {
        BlockframeRuntime.resourceLoadFinished();
        if (DeveloperDiagnostics.enabled()) {
            FastStartRuntime.resourceReloadFinished();
        }
        DlssRenderer.requestOptimalSettingsRefresh("Resource-Reload");
    }

    private static void onPlayerLoggingIn(
        ClientPlayerNetworkEvent.LoggingIn event
    ) {
        DlssRenderer.requestWorldHistoryReset("Weltbeitritt");
        if (DeveloperDiagnostics.enabled()) {
            FastStartRuntime.playerLoginFinished();
        }
    }

    private static void onPlayerLoggingOut(
        ClientPlayerNetworkEvent.LoggingOut event
    ) {
        DlssRenderer.requestWorldHistoryReset("Weltverlassen");
        if (DeveloperDiagnostics.enabled()) {
            FastStartRuntime.worldUnavailable();
        }
    }

    private static void onPlayerClone(
        ClientPlayerNetworkEvent.Clone event
    ) {
        DlssRenderer.requestReset("Tod/Respawn");
    }

    private static void onClientStopping(ClientStoppingEvent event) {
        if (DeveloperDiagnostics.enabled()) {
            FastStartRuntime.flushTimeline();
        }
        BlockframeRuntime.clientStopping();
    }

    private static void onClientStopped(ClientStoppedEvent event) {
        BlockframeRuntime.clientStopped();
    }
}
