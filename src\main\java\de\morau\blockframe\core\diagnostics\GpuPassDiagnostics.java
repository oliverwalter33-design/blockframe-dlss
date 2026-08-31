package de.morau.blockframe.core.diagnostics;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.TracyGpuProfiler;
import com.mojang.blaze3d.vulkan.VulkanDebug;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.jtracy.TracyClient;
import com.mojang.jtracy.Zone;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VK12;

/**
 * Fail-open adapters around diagnostic facilities owned by Minecraft.
 *
 * <p>No method stores a command buffer, profiler, device or zone. Disabled
 * Debug Utils and unavailable Tracy therefore remain allocation-free no-ops,
 * while an optional diagnostic failure cannot replace a render error or
 * select BlockFrame's DLSS safety fallback.</p>
 */
public final class GpuPassDiagnostics {
    public static final String FRAME_TIMESTAMP_QUERY_POOL =
        "BlockFrame / Frame GPU Timestamp Query Pool";
    public static final String LOW_RESOLUTION_COLOR_IMAGE =
        "BlockFrame / DLSS Low-Resolution Color Image";
    public static final String LOW_RESOLUTION_COLOR_VIEW =
        "BlockFrame / DLSS Low-Resolution Color View";
    public static final String LOW_RESOLUTION_DEPTH_IMAGE =
        "BlockFrame / DLSS Low-Resolution Depth Image";
    public static final String LOW_RESOLUTION_DEPTH_VIEW =
        "BlockFrame / DLSS Low-Resolution Depth View";
    public static final String GRAPHICS_QUEUE =
        "BlockFrame / Graphics Queue";
    public static final String COMPUTE_QUEUE =
        "BlockFrame / Compute Queue";
    public static final String GRAPHICS_COMPUTE_QUEUE =
        "BlockFrame / Graphics + Compute Queue";

    private static volatile boolean debugLabelsRequested;
    private static volatile boolean tracyRequested;
    private static volatile boolean debugLabelsSupported;
    private static volatile boolean debugLabelsEffective;
    private static volatile boolean tracySupported;
    private static volatile boolean tracyEffective;
    private static volatile Snapshot snapshot = publishSnapshot();

    private GpuPassDiagnostics() {
    }

    /**
     * Applies the process-bound diagnostic switches before productive use.
     * Tracy availability is probed exactly here rather than from F8 or every
     * annotated pass.
     */
    public static Snapshot configure(
        boolean requestDebugLabels,
        boolean requestTracy
    ) {
        return configure(
            requestDebugLabels,
            requestTracy,
            GpuPassDiagnostics::probeTracyAvailability
        );
    }

    static synchronized Snapshot configure(
        boolean requestDebugLabels,
        boolean requestTracy,
        BooleanSupplier tracyProbe
    ) {
        Objects.requireNonNull(tracyProbe, "tracyProbe");
        debugLabelsRequested = requestDebugLabels;
        tracyRequested = requestTracy;
        debugLabelsSupported = false;
        debugLabelsEffective = false;
        tracySupported =
            requestTracy && tracyProbe.getAsBoolean();
        tracyEffective = false;
        snapshot = publishSnapshot();
        return snapshot;
    }

    /** Clears device-scoped label facts without changing process requests. */
    public static synchronized void deviceGenerationChanged() {
        debugLabelsSupported = false;
        debugLabelsEffective = false;
        snapshot = publishSnapshot();
    }

    /** Returns an immutable cached state and performs no external probe. */
    public static Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Begins a command-buffer label only when Mojang exposes Debug Utils.
     *
     * @return true only when the begin call returned normally and therefore
     *         requires a matching best-effort end call
     */
    public static boolean beginDebugGroup(
        @Nullable VulkanDebug debug,
        VkCommandBuffer commandBuffer,
        GpuPassIdentity identity
    ) {
        if (!debugLabelsRequested || debug == null) {
            return false;
        }
        try {
            if (!debug.enabled()) {
                return false;
            }
            observeDebugLabelsSupported();
            debug.beginDebugGroup(commandBuffer, identity);
            observeDebugLabelsEffective();
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            return false;
        }
    }

    public static void endDebugGroup(
        @Nullable VulkanDebug debug,
        VkCommandBuffer commandBuffer,
        boolean begun
    ) {
        if (!begun || debug == null) {
            return;
        }
        try {
            debug.endDebugGroup(commandBuffer);
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Optional labels must not mask the productive command failure.
        }
    }

    public static @Nullable Zone beginCpuTracyZone(
        GpuPassIdentity identity
    ) {
        if (!tracyRequested || !tracySupported) {
            return null;
        }
        try {
            Zone zone = TracyClient.beginZone(identity.label(), false);
            if (zone != null) {
                observeTracyEffective();
            }
            return zone;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            return null;
        }
    }

    public static void closeCpuTracyZone(@Nullable Zone zone) {
        if (zone == null) {
            return;
        }
        try {
            zone.close();
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Tracy is optional and may not affect render or shutdown state.
        }
    }

    /**
     * Reuses Minecraft's Tracy GPU profiler and its existing timestamp pool.
     */
    public static boolean beginGpuTracyZone(
        @Nullable TracyGpuProfiler profiler,
        CommandEncoder encoder,
        GpuPassIdentity identity
    ) {
        if (!tracyRequested || !tracySupported || profiler == null) {
            return false;
        }
        try {
            profiler.pushZone(encoder, identity.label());
            observeTracyEffective();
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            return false;
        }
    }

    public static void endGpuTracyZone(
        @Nullable TracyGpuProfiler profiler,
        CommandEncoder encoder,
        boolean begun
    ) {
        if (!begun || profiler == null) {
            return;
        }
        try {
            profiler.popZone(encoder);
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // A Tracy failure cannot replace the productive render result.
        }
    }

    public static void setObjectName(
        VulkanDebug debug,
        VkDevice device,
        int objectType,
        long objectHandle,
        String label
    ) {
        if (!debugLabelsRequested || objectHandle == 0L) {
            return;
        }
        try {
            if (debug.enabled()) {
                observeDebugLabelsSupported();
                debug.setObjectName(
                    device,
                    objectType,
                    objectHandle,
                    label
                );
                observeDebugLabelsEffective();
            }
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Object publication and ownership remain authoritative.
        }
    }

    /**
     * Names borrowed queue roles without retaining or taking ownership of a
     * Minecraft queue.
     */
    public static void labelBorrowedQueues(VulkanDevice device) {
        if (!debugLabelsRequested) {
            return;
        }
        try {
            var debug = device.instance().debug();
            var vkDevice = device.vkDevice();
            long graphics =
                device.graphicsQueue().vkQueue().address();
            long compute =
                device.computeQueue().vkQueue().address();
            if (graphics == compute) {
                setObjectName(
                    debug,
                    vkDevice,
                    VK12.VK_OBJECT_TYPE_QUEUE,
                    graphics,
                    GRAPHICS_COMPUTE_QUEUE
                );
                return;
            }
            setObjectName(
                debug,
                vkDevice,
                VK12.VK_OBJECT_TYPE_QUEUE,
                graphics,
                GRAPHICS_QUEUE
            );
            setObjectName(
                debug,
                vkDevice,
                VK12.VK_OBJECT_TYPE_QUEUE,
                compute,
                COMPUTE_QUEUE
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Borrowed queue ownership and submission remain unchanged.
        }
    }

    public static void labelFrameTimestampQueryPool(
        VulkanDevice device,
        long queryPool
    ) {
        if (!debugLabelsRequested) {
            return;
        }
        try {
            setObjectName(
                device.instance().debug(),
                device.vkDevice(),
                VK12.VK_OBJECT_TYPE_QUERY_POOL,
                queryPool,
                FRAME_TIMESTAMP_QUERY_POOL
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // The existing timer remains authoritative and fully usable.
        }
    }

    private static boolean probeTracyAvailability() {
        try {
            return TracyClient.isAvailable();
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            return false;
        }
    }

    private static void observeDebugLabelsSupported() {
        if (debugLabelsSupported) {
            return;
        }
        synchronized (GpuPassDiagnostics.class) {
            if (!debugLabelsSupported) {
                debugLabelsSupported = true;
                snapshot = publishSnapshot();
            }
        }
    }

    private static void observeDebugLabelsEffective() {
        if (debugLabelsEffective) {
            return;
        }
        synchronized (GpuPassDiagnostics.class) {
            if (!debugLabelsEffective) {
                debugLabelsEffective = true;
                snapshot = publishSnapshot();
            }
        }
    }

    private static void observeTracyEffective() {
        if (tracyEffective) {
            return;
        }
        synchronized (GpuPassDiagnostics.class) {
            if (!tracyEffective) {
                tracyEffective = true;
                snapshot = publishSnapshot();
            }
        }
    }

    private static Snapshot publishSnapshot() {
        return new Snapshot(
            debugLabelsRequested,
            debugLabelsSupported,
            debugLabelsRequested && debugLabelsSupported,
            debugLabelsEffective,
            tracyRequested,
            tracySupported,
            tracyRequested && tracySupported,
            tracyEffective
        );
    }

    public record Snapshot(
        boolean debugLabelsRequested,
        boolean debugLabelsSupported,
        boolean debugLabelsEnabled,
        boolean debugLabelsEffective,
        boolean tracyRequested,
        boolean tracySupported,
        boolean tracyEnabled,
        boolean tracyEffective
    ) {
    }
}
