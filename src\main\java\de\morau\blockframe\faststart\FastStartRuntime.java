package de.morau.blockframe.faststart;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Client-facing FastStart phase bridge.
 *
 * <p>All optimization switches are intentionally absent from this first
 * telemetry build. Once T16 is recorded, the per-frame path becomes a single
 * volatile read and return.</p>
 */
public final class FastStartRuntime {
    private static final long LANDSCAPE_STABLE_NANOS = 500_000_000L;
    private static final FastStartStabilityWindow LANDSCAPE_STABILITY =
        new FastStartStabilityWindow(LANDSCAPE_STABLE_NANOS);
    private static volatile FastStartTimeline timeline;
    private static volatile boolean landscapeComplete;
    private static volatile LandscapeSnapshot latestLandscapeSnapshot =
        LandscapeSnapshot.unavailable("Welt noch nicht bereit");
    private static long reloadStartedAt = Long.MIN_VALUE;
    private static volatile long latestResourceReloadNanos = Long.MIN_VALUE;

    private FastStartRuntime() {}

    public static synchronized void initialize() {
        if (timeline != null) {
            return;
        }
        Path gameDirectory = FMLPaths.GAMEDIR.get();
        timeline = new FastStartTimeline(
            gameDirectory,
            System.getProperty("blockframe.faststart.session"),
            System.getProperty(
                "blockframe.faststart.profile",
                "C_TELEMETRY_ONLY"
            )
        );
    }

    public static void modLifecycleComplete() {
        record(
            FastStartPhase.T4,
            "FMLLoadCompleteEvent deferred work reached"
        );
    }

    public static void clientConstructionFinished() {
        record(
            FastStartPhase.T5,
            "ClientStartedEvent before first client tick"
        );
    }

    public static void resourceReloadStarted() {
        FastStartTimeline current = timeline;
        if (current == null) {
            return;
        }
        reloadStartedAt = System.nanoTime();
        current.record(
            FastStartPhase.T6,
            "ReloadableResourceManager.createReload HEAD"
        );
    }

    public static void resourceReloadFinished() {
        long started = reloadStartedAt;
        if (started != Long.MIN_VALUE) {
            latestResourceReloadNanos = Math.max(
                0L,
                System.nanoTime() - started
            );
        }
        record(
            FastStartPhase.T7,
            "ClientResourceLoadFinishedEvent"
        );
    }

    public static void serverDataReady() {
        record(
            FastStartPhase.T11,
            "ServerAboutToStartEvent after WorldStem/datapack construction"
        );
    }

    public static void startChunksReady() {
        record(
            FastStartPhase.T13,
            "LevelLoadingScreen closed after LevelLoadTracker.isLevelReady"
        );
    }

    public static void playerLoginFinished() {
        LANDSCAPE_STABILITY.reset();
        landscapeComplete = false;
        latestLandscapeSnapshot =
            LandscapeSnapshot.unavailable("Spielerlogin läuft");
        record(
            FastStartPhase.T14,
            "ClientPlayerNetworkEvent.LoggingIn"
        );
    }

    public static void flushTimeline() {
        FastStartTimeline current = timeline;
        if (current != null) {
            current.flush();
        }
    }

    public static void worldUnavailable() {
        LANDSCAPE_STABILITY.reset();
        landscapeComplete = false;
        latestLandscapeSnapshot =
            LandscapeSnapshot.unavailable("Keine aktive Welt");
    }

    public static void renderFrameFinished(Minecraft minecraft) {
        if (landscapeComplete) {
            return;
        }
        FastStartTimeline current = timeline;
        if (current == null) {
            return;
        }
        if (
            !current.recorded(FastStartPhase.T8)
                && current.recorded(FastStartPhase.T7)
                && minecraft.isGameLoadFinished()
                && minecraft.gui.screen() instanceof TitleScreen
        ) {
            current.record(
                FastStartPhase.T8,
                "RenderFrameEvent.Post with interactive TitleScreen"
            );
        }
        if (
            minecraft.level == null
                || minecraft.player == null
                || minecraft.gameMode == null
                || !minecraft.isGameLoadFinished()
                || minecraft.gui.screen() instanceof LevelLoadingScreen
                || !minecraft.windowSurface().isAcquired()
        ) {
            LANDSCAPE_STABILITY.reset();
            latestLandscapeSnapshot =
                LandscapeSnapshot.unavailable(
                    "Client, Welt oder Fenstersurface noch nicht bereit"
                );
            return;
        }
        if (minecraft.levelRenderer == null) {
            LANDSCAPE_STABILITY.reset();
            latestLandscapeSnapshot =
                LandscapeSnapshot.unavailable("LevelRenderer fehlt");
            return;
        }

        VisibleMeshState visible = visibleMeshState(minecraft);
        int expectedChunks = minecraft.levelRenderer.expectedChunks().size();
        boolean playerSectionVisible =
            minecraft.levelRenderer.isSectionCompiledAndVisible(
                minecraft.player.blockPosition()
            );
        FastStartSodiumLandscapeBridge.Snapshot sodium =
            visible.total() == 0
                ? FastStartSodiumLandscapeBridge.observe(
                    minecraft.player.blockPosition()
                )
                : FastStartSodiumLandscapeBridge.Snapshot.notObserved();
        SectionRenderDispatcher dispatcher =
            minecraft.levelRenderer.sectionRenderDispatcher();
        int backgroundCompileQueueSize =
            dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
        boolean backgroundDispatcherIdle =
            dispatcher == null || minecraft.levelRenderer.hasRenderedAllSections();
        FastStartLandscapeSelector.Decision decision =
            FastStartLandscapeSelector.select(
                new FastStartLandscapeSelector.Observation(
                    expectedChunks,
                    visible.total(),
                    visible.uncompiled(),
                    playerSectionVisible,
                    visible.signature(),
                    sodium.available(),
                    sodium.terrainRenderComplete(),
                    sodium.visibleChunks(),
                    sodium.playerSectionReady(),
                    sodium.signature(),
                    sodium.reason()
                )
            );
        long now = System.nanoTime();
        boolean stable = LANDSCAPE_STABILITY.observe(
            decision.ready(),
            decision.signature(),
            now
        );
        latestLandscapeSnapshot = new LandscapeSnapshot(
            true,
            expectedChunks,
            decision.visibleSections(),
            visible.uncompiled(),
            decision.playerSectionReady(),
            backgroundCompileQueueSize,
            backgroundDispatcherIdle,
            LANDSCAPE_STABILITY.stableForNanos(now),
            decision.rendererPath().label(),
            decision.reason()
        );
        if (stable) {
            landscapeComplete = current.record(
                FastStartPhase.T16,
                decision.rendererPath().label()
                    + ": no expected chunks waiting; visible terrain "
                    + "published after completed mesh/GPU work; player "
                    + "section ready; renderer state stable for >=500 ms; "
                    + "visible="
                    + decision.visibleSections()
                    + "; vanilla background compile queue="
                    + backgroundCompileQueueSize
            ) || current.recorded(FastStartPhase.T16);
            if (landscapeComplete) {
                current.persistAsync();
            }
        }
    }

    private static VisibleMeshState visibleMeshState(Minecraft minecraft) {
        var sections = minecraft.levelRenderer.visibleSections();
        int uncompiled = 0;
        long signature = 0xcbf29ce484222325L;
        for (int index = 0; index < sections.size(); index++) {
            SectionRenderDispatcher.RenderSection section = sections.get(index);
            SectionMesh mesh = section.getSectionMesh();
            if (mesh == CompiledSectionMesh.UNCOMPILED) {
                uncompiled++;
            }
            signature ^= section.getSectionNode();
            signature *= 0x100000001b3L;
            signature ^= System.identityHashCode(mesh);
            signature *= 0x100000001b3L;
        }
        signature ^= sections.size();
        return new VisibleMeshState(
            sections.size(),
            uncompiled,
            signature
        );
    }

    public static List<String> debugLines() {
        FastStartTimeline current = timeline;
        if (current == null) {
            return List.of(
                "BlockFrame FastStart [F8 Seite 2/2]",
                "Telemetrie noch nicht initialisiert"
            );
        }
        List<String> base = current.debugLines();
        if (latestResourceReloadNanos == Long.MIN_VALUE) {
            return base;
        }
        java.util.ArrayList<String> lines =
            new java.util.ArrayList<>(base.size() + 1);
        lines.addAll(base);
        lines.add(
            String.format(
                java.util.Locale.ROOT,
                "Letzter Ressourcenreload: %.3f ms",
                latestResourceReloadNanos / 1_000_000.0
            )
        );
        LandscapeSnapshot snapshot = latestLandscapeSnapshot;
        lines.add(
            "T16 "
                + snapshot.rendererPath()
                + ": expected="
                + snapshot.expectedChunks()
                + " visible="
                + snapshot.visibleSections()
                + " uncompiled="
                + snapshot.uncompiledVisibleSections()
                + " player="
                + snapshot.playerSectionVisible()
        );
        lines.add(
            String.format(
                java.util.Locale.ROOT,
                "T16 stabil: %.1f/500.0 ms | Hintergrundqueue=%d idle=%s | %s",
                snapshot.stableForNanos() / 1_000_000.0,
                snapshot.backgroundCompileQueueSize(),
                snapshot.backgroundDispatcherIdle(),
                snapshot.reason()
            )
        );
        return List.copyOf(lines);
    }

    private record VisibleMeshState(
        int total,
        int uncompiled,
        long signature
    ) {}

    private record LandscapeSnapshot(
        boolean worldAvailable,
        int expectedChunks,
        int visibleSections,
        int uncompiledVisibleSections,
        boolean playerSectionVisible,
        int backgroundCompileQueueSize,
        boolean backgroundDispatcherIdle,
        long stableForNanos,
        String rendererPath,
        String reason
    ) {
        private static LandscapeSnapshot unavailable(String reason) {
            return new LandscapeSnapshot(
                false,
                -1,
                0,
                0,
                false,
                -1,
                false,
                0L,
                "noch unbekannt",
                reason
            );
        }
    }

    static FastStartTimeline timelineForTests() {
        return timeline;
    }

    private static void record(FastStartPhase phase, String detail) {
        FastStartTimeline current = timeline;
        if (current != null) {
            current.record(phase, detail);
        }
    }
}
