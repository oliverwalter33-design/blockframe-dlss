package de.morau.blockframe.render.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import de.morau.blockframe.core.EngineCapabilities;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-level routing and cached diagnostics for opaque-solid templates.
 */
public final class OpaqueSolidTerrainBatchRuntime {
    private static final Logger LOGGER =
        LoggerFactory.getLogger("blockframe-solid-batch");
    private static volatile OpaqueSolidTerrainBatchCache currentCache;
    private static volatile long reloadEpoch = 1L;
    private static volatile String gateStatus = "NOT_ATTEMPTED";

    private OpaqueSolidTerrainBatchRuntime() {
    }

    /** Lazy renderer-owner construction, never reached by the OpenGL gate. */
    public static OpaqueSolidTerrainBatchCache cacheOrCreate(
        OpaqueSolidTerrainBatchCache cache
    ) {
        return cache != null
            ? cache
            : new OpaqueSolidTerrainBatchCache();
    }

    public static boolean eligibleForPrepare() {
        try {
            if (
                !de.morau.blockframe.core.BlockframeRuntime
                    .engine()
                    .config()
                    .settings()
                    .engineEnabled()
                    || !de.morau.blockframe.core.BlockframeRuntime
                        .engine()
                        .config()
                        .settings()
                        .frameResourcesEnabled()
                    || de.morau.blockframe.core.BlockframeRuntime
                        .safeStartActive()
            ) {
                gateStatus = "MOJANG_ONLY_POLICY";
                return false;
            }
            if (
                EngineCapabilities.Backend.from(
                    RenderSystem.getDevice()
                        .getDeviceInfo()
                        .backendName()
                ) != EngineCapabilities.Backend.VULKAN
            ) {
                gateStatus = "MOJANG_ONLY_OPENGL";
                return false;
            }
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            gateStatus = "MOJANG_ONLY_GATE_UNAVAILABLE";
            return false;
        }
    }

    public static ChunkSectionsToRender tryPrepare(
        OpaqueSolidTerrainBatchCache cache,
        Object worldIdentity,
        List<SectionRenderDispatcher.RenderSection> visibleSections,
        SectionRenderDispatcher dispatcher,
        TextureManager textureManager,
        Matrix4fc modelViewMatrix
    ) {
        if (
            cache == null
                || worldIdentity == null
                || dispatcher == null
        ) {
            gateStatus = "MOJANG_ONLY_NO_WORLD_OR_DISPATCHER";
            return null;
        }
        if (!eligibleForPrepare()) {
            return null;
        }

        currentCache = cache;
        ChunkSectionsToRender result = cache.tryPrepare(
            worldIdentity,
            visibleSections,
            dispatcher,
            textureManager,
            modelViewMatrix,
            de.morau.blockframe.core.BlockframeRuntime
                .deviceGeneration(),
            reloadEpoch
        );
        gateStatus = result == null
            ? "MOJANG_FALLBACK"
            : "VULKAN_TEMPLATE_CACHE";
        return result;
    }

    public static boolean beginDrawSubmission(
        ChunkSectionsToRender marker,
        ChunkSectionLayerGroup group
    ) {
        OpaqueSolidTerrainBatchCache cache = currentCache;
        return group == ChunkSectionLayerGroup.OPAQUE
            && cache != null
            && cache.beginSolidSubmission(marker);
    }

    public static void recordDrawSubmission(
        boolean tracked,
        long nanos
    ) {
        if (!tracked) {
            return;
        }
        OpaqueSolidTerrainBatchCache cache = currentCache;
        if (cache != null) {
            cache.recordSubmissionNanos(nanos);
        }
    }

    public static void finishRenderGroup(
        ChunkSectionsToRender marker,
        ChunkSectionLayerGroup group,
        boolean completedNormally
    ) {
        if (group != ChunkSectionLayerGroup.OPAQUE) {
            return;
        }
        OpaqueSolidTerrainBatchCache cache = currentCache;
        if (cache != null) {
            cache.finishOpaqueGroup(marker, completedNormally);
        }
    }

    public static void resourceReloaded() {
        reloadEpoch = incrementSaturated(reloadEpoch);
        OpaqueSolidTerrainBatchCache cache = currentCache;
        if (cache != null) {
            logLifecycleSnapshot("RESOURCE_RELOAD", cache);
            cache.invalidate(
                PersistentDrawTemplateTable.Failure
                    .LIFECYCLE_INVALIDATION
            );
        }
        gateStatus = "INVALIDATED_RESOURCE_RELOAD";
    }

    public static void deviceClosing() {
        OpaqueSolidTerrainBatchCache cache = currentCache;
        if (cache != null) {
            logLifecycleSnapshot("DEVICE_CLOSE", cache);
            cache.invalidate(
                PersistentDrawTemplateTable.Failure
                    .LIFECYCLE_INVALIDATION
            );
        }
        gateStatus = "INVALIDATED_DEVICE_CLOSE";
    }

    public static void worldUnavailable() {
        if (
            "INVALIDATED_WORLD_UNAVAILABLE".equals(gateStatus)
        ) {
            return;
        }
        OpaqueSolidTerrainBatchCache cache = currentCache;
        if (cache != null) {
            logLifecycleSnapshot("WORLD_UNAVAILABLE", cache);
            cache.invalidate(
                PersistentDrawTemplateTable.Failure
                    .LIFECYCLE_INVALIDATION
            );
        }
        gateStatus = "INVALIDATED_WORLD_UNAVAILABLE";
    }

    public static void rendererInvalidated(
        OpaqueSolidTerrainBatchCache cache
    ) {
        if (cache == null) {
            return;
        }
        logLifecycleSnapshot("RENDERER_INVALIDATED", cache);
        cache.invalidate(
            PersistentDrawTemplateTable.Failure
                .LIFECYCLE_INVALIDATION
        );
        if (currentCache == cache) {
            gateStatus = "INVALIDATED_RENDERER";
        }
    }

    public static void rendererClosed(
        OpaqueSolidTerrainBatchCache cache
    ) {
        if (cache == null) {
            return;
        }
        logLifecycleSnapshot("RENDERER_CLOSE", cache);
        boolean clean = cache.closeAndReport();
        if (currentCache == cache) {
            currentCache = null;
            gateStatus = clean
                ? "CLOSED"
                : "CLEANUP_RETRY_REQUIRED";
        }
    }

    /** F8-only formatting over cached primitive state; no Vulkan query. */
    public static List<String> debugLines() {
        OpaqueSolidTerrainBatchCache cache = currentCache;
        if (cache == null) {
            return List.of(
                "Opaque-solid templates: " + gateStatus
            );
        }
        PersistentDrawTemplateTable.Snapshot state =
            cache.snapshot();
        if (state == null) {
            return List.of(
                "Opaque-solid templates: " + gateStatus
            );
        }
        return List.of(
            "Opaque-solid templates: "
                + gateStatus
                + " entries="
                + state.entries()
                + "/"
                + state.capacity(),
            "visible/mojang/ready/reuse/rebuild: "
                + state.visible()
                + " / "
                + state.mojangOnly()
                + " / "
                + state.ready()
                + " / "
                + state.reused()
                + " / "
                + state.rebuilt(),
            "dirty/evicted/retired/quarantined: "
                + state.dirty()
                + " / "
                + state.evicted()
                + " / "
                + state.retired()
                + " / "
                + state.quarantined(),
            "records full/reused/submits: "
                + state.fullRecordBuilds()
                + " / "
                + state.reusedTemplates()
                + " / "
                + state.encodedSolidSubmissions(),
            String.format(
                java.util.Locale.ROOT,
                "CPU visibility/build/submit: %.3f / %.3f / %.3f ms",
                nanosToMillis(state.visibilityNanos()),
                nanosToMillis(state.buildNanos()),
                nanosToMillis(state.submissionNanos())
            ),
            "cache RAM/VRAM/upload: "
                + state.ramBytes()
                + " / "
                + state.vramBytes()
                + " / "
                + state.uploadedBytes()
                + " bytes",
            "fallbacks/cleanup-retries/last: "
                + state.fallbackFrames()
                + " / "
                + state.cleanupRetries()
                + " / "
                + state.lastFailure()
        );
    }

    static long reloadEpochForTest() {
        return reloadEpoch;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * Emits evidence only at existing lifecycle boundaries. The per-frame
     * prepare and submit paths never log or format diagnostics.
     */
    private static void logLifecycleSnapshot(
        String event,
        OpaqueSolidTerrainBatchCache cache
    ) {
        PersistentDrawTemplateTable.Snapshot state =
            cache.snapshot();
        if (state == null) {
            LOGGER.info(
                "OPAQUE_SOLID_BATCH event={} status={} snapshot=UNAVAILABLE",
                event,
                gateStatus
            );
            return;
        }
        LOGGER.info(
            "OPAQUE_SOLID_BATCH event={} status={} entries={}/{} "
                + "visible={} mojangOnly={} candidate={} ready={} "
                + "reused={} rebuilt={} dirty={} evicted={} retired={} "
                + "quarantined={} fullRecordBuilds={} reusedTemplates={} "
                + "drawRecords={} solidSubmissions={} encodedSubmissions={} "
                + "visibilityNanos={} buildNanos={} submissionNanos={} "
                + "uploadedBytes={} ramBytes={} vramBytes={} "
                + "fallbackFrames={} cleanupRetries={} wrongThreads={} "
                + "lastFailure={}",
            event,
            gateStatus,
            state.entries(),
            state.capacity(),
            state.visible(),
            state.mojangOnly(),
            state.candidates(),
            state.ready(),
            state.reused(),
            state.rebuilt(),
            state.dirty(),
            state.evicted(),
            state.retired(),
            state.quarantined(),
            state.fullRecordBuilds(),
            state.reusedTemplates(),
            state.drawRecords(),
            state.solidSubmissionCount(),
            state.encodedSolidSubmissions(),
            state.visibilityNanos(),
            state.buildNanos(),
            state.submissionNanos(),
            state.uploadedBytes(),
            state.ramBytes(),
            state.vramBytes(),
            state.fallbackFrames(),
            state.cleanupRetries(),
            state.wrongThreadCount(),
            state.lastFailure()
        );
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
