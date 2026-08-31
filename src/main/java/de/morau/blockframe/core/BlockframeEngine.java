package de.morau.blockframe.core;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.diagnostics.GpuSubmissionBreadcrumbs;
import de.morau.blockframe.core.diagnostics.DeviceFaultDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.PhysicalMemoryTelemetry;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.memory.BudgetedNativeArena;
import de.morau.blockframe.core.memory.ReusableNativeBlockPool;
import de.morau.blockframe.core.state.FeatureId;
import de.morau.blockframe.core.state.FeatureStateRegistry;
import de.morau.blockframe.core.state.RuntimeFeaturePolicy;
import de.morau.blockframe.profiler.CacheStatistics;
import de.morau.blockframe.profiler.FrameProfiler;
import de.morau.blockframe.profiler.VulkanGpuFrameTimer;
import de.morau.blockframe.vulkan.VulkanMemoryBudgetProbe;
import de.morau.blockframe.vulkan.VulkanDeviceFaultCapture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Small lifecycle boundary shared by renderer optimizations. It observes the
 * Mojang-owned device but never closes it.
 */
public final class BlockframeEngine implements AutoCloseable {
    private static final int NATIVE_STAGING_BLOCK_COUNT = 1;
    private static final int NATIVE_STAGING_BLOCK_BYTES = 32 * 1024;

    private final EngineConfig config;
    private final FrameProfiler profiler;
    private final CacheStatistics cacheStatistics;
    private final MemoryBudgetManager memoryBudgets;
    private final ShaderResourceInventory shaderResources;
    private final PhysicalMemoryTelemetry physicalMemoryTelemetry;
    private final DeviceFaultDiagnostics deviceFaultDiagnostics;
    private final VulkanGpuFrameTimer gpuFrameTimer;
    private final RuntimeFeaturePolicy featurePolicy;
    private final FeatureStateRegistry featureStates;
    private GpuSubmissionBreadcrumbs gpuBreadcrumbs;
    private boolean gpuBreadcrumbsCreationAttempted;
    private boolean gpuBreadcrumbsFaulted;
    private volatile String gpuBreadcrumbsStatus = "not requested";
    private ReusableNativeBlockPool nativeStagingPool;
    private boolean nativeStagingPoolCreationAttempted;
    private boolean nativeStagingPoolEvictableRegistered;
    private volatile String nativeStagingPoolStatus = "not requested";

    private volatile GpuDevice cachedDevice;
    private volatile DeviceInfo deviceInfo;
    private volatile EngineCapabilities capabilities = EngineCapabilities.unknown();
    private volatile VulkanRuntimeInfo vulkanRuntimeInfo =
        VulkanRuntimeInfo.unavailable();
    private volatile boolean bufferDeviceAddressAvailable;
    private volatile boolean bufferDeviceAddressEnabled;
    private boolean pendingBufferDeviceAddressAvailable;
    private boolean pendingBufferDeviceAddressEnabled;
    private boolean pendingBufferDeviceAddressState;
    private volatile boolean profilerFrameOpen;
    private volatile long clientFrameId;
    private volatile boolean closing;
    private volatile boolean closed;
    private volatile boolean compatibilityDisabled;
    private volatile String configurationError;
    private volatile String compatibilityReason;

    public BlockframeEngine() {
        this(
            new EngineConfig(),
            new FrameProfiler(),
            true,
            true,
            null,
            null
        );
    }

    public BlockframeEngine(EngineConfig config) {
        this(config, new FrameProfiler(), false, false, null, null);
    }

    public BlockframeEngine(EngineConfig config, FrameProfiler profiler) {
        this(config, profiler, false, false, null, null);
    }

    BlockframeEngine(
        EngineConfig config,
        RuntimeFeaturePolicy featurePolicy,
        FeatureStateRegistry featureStates
    ) {
        this(
            config,
            configuredProfiler(featurePolicy),
            false,
            true,
            featurePolicy,
            featureStates
        );
    }

    private BlockframeEngine(
        EngineConfig config,
        FrameProfiler profiler,
        boolean loadConfiguration,
        boolean clientInstrumentation,
        RuntimeFeaturePolicy suppliedFeaturePolicy,
        FeatureStateRegistry suppliedFeatureStates
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.profiler = Objects.requireNonNull(profiler, "profiler");
        if (loadConfiguration) {
            try {
                this.config.load();
            } catch (IOException | SecurityException exception) {
                this.configurationError = exception.getMessage() == null
                    ? exception.toString()
                    : exception.getMessage();
            }
        }
        this.featurePolicy = suppliedFeaturePolicy == null
            ? new RuntimeFeaturePolicy(
                this.config.settings(),
                "off",
                "heap",
                false
            )
            : suppliedFeaturePolicy;
        this.featureStates = suppliedFeatureStates == null
            ? new FeatureStateRegistry()
            : suppliedFeatureStates;
        if (suppliedFeatureStates == null) {
            this.featurePolicy.publishInitial(this.featureStates, 0L);
        }
        this.cacheStatistics = new CacheStatistics();
        this.memoryBudgets = new MemoryBudgetManager(
            this.config.settings().memoryBudgets()
        );
        this.shaderResources = new ShaderResourceInventory();
        this.physicalMemoryTelemetry = this.featurePolicy.enabled(
                FeatureId.PHYSICAL_MEMORY
            )
            ? PhysicalMemoryTelemetry.createDefault()
            : PhysicalMemoryTelemetry.createDisabled();
        this.deviceFaultDiagnostics = new DeviceFaultDiagnostics();
        this.gpuFrameTimer = clientInstrumentation
                && this.featurePolicy.enabled(FeatureId.FRAME_PROFILER)
            ? new VulkanGpuFrameTimer(
                this.profiler,
                this.memoryBudgets
            )
            : null;
        this.applyGpuBreadcrumbConfiguration(this.config.settings());
    }

    private static FrameProfiler configuredProfiler(
        RuntimeFeaturePolicy featurePolicy
    ) {
        RuntimeFeaturePolicy policy = Objects.requireNonNull(
            featurePolicy,
            "featurePolicy"
        );
        return policy.enabled(FeatureId.FRAME_PROFILER)
            ? new FrameProfiler()
            : FrameProfiler.disabled();
    }

    public void beginFrame() {
        if (this.closed || this.closing) {
            return;
        }

        this.clientFrameId = incrementSaturated(this.clientFrameId);
        this.abortIncompleteFrame();
        this.detectDevice();
        if (this.featurePolicy.enabled(FeatureId.PHYSICAL_MEMORY)) {
            try {
                this.physicalMemoryTelemetry.sampleIfDue();
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError ignored
            ) {
                // Diagnostics remain fail-open and never select a render path.
            }
        }
        EngineConfig.Settings settings = this.config.settings();
        boolean measureFrame =
            !this.compatibilityDisabled
                && settings.engineEnabled()
                && settings.profilerEnabled()
                && this.featurePolicy.enabled(FeatureId.FRAME_PROFILER);
        if (measureFrame) {
            this.profiler.beginFrame();
            this.profilerFrameOpen = true;
            if (this.gpuFrameTimer != null) {
                this.gpuFrameTimer.beginFrame(this.cachedDevice);
            }
        }
    }

    public void endFrame() {
        try {
            if (this.profilerFrameOpen) {
                this.profilerFrameOpen = false;
                try {
                    if (this.gpuFrameTimer != null) {
                        this.gpuFrameTimer.endFrame();
                    }
                } finally {
                    this.profiler.endFrame();
                }
            }
        } finally {
            try {
                this.memoryBudgets.advanceFrame();
            } finally {
                this.shaderResources.advanceFrame();
            }
        }
    }

    public synchronized EngineConfig.Settings reloadConfiguration()
        throws IOException {
        EngineConfig.Settings settings = this.config.reload();
        this.memoryBudgets.applySettings(settings.memoryBudgets());
        if (this.gpuFrameTimer != null) {
            this.gpuFrameTimer.configurationReloaded();
        }
        if (this.nativeStagingPool == null) {
            this.nativeStagingPoolCreationAttempted = false;
            this.nativeStagingPoolStatus =
                "not requested after configuration reload";
        }
        this.applyGpuBreadcrumbConfiguration(settings);
        this.configurationError = null;
        return settings;
    }

    public void saveConfiguration() throws IOException {
        this.config.save();
        this.configurationError = null;
    }

    public EngineConfig config() {
        return this.config;
    }

    public FrameProfiler profiler() {
        return this.profiler;
    }

    public boolean profilerFrameOpen() {
        return this.profilerFrameOpen;
    }

    public CacheStatistics cacheStatistics() {
        return this.cacheStatistics;
    }

    public MemoryBudgetManager memoryBudgets() {
        return this.memoryBudgets;
    }

    public ShaderResourceInventory shaderResources() {
        return this.shaderResources;
    }

    public RuntimeFeaturePolicy featurePolicy() {
        return this.featurePolicy;
    }

    public FeatureStateRegistry featureStates() {
        return this.featureStates;
    }

    public synchronized boolean gpuBreadcrumbsEffective() {
        return this.gpuBreadcrumbs != null
            && !this.gpuBreadcrumbsFaulted;
    }

    public DeviceFaultDiagnostics.Snapshot deviceFaultSnapshot() {
        return this.deviceFaultDiagnostics.snapshot();
    }

    /** Cached only; performs no OS, driver, or Vulkan query. */
    public PhysicalMemoryTelemetry.Snapshot physicalMemorySnapshot() {
        return this.physicalMemoryTelemetry.snapshot();
    }

    public void updateDeviceFaultNegotiation(
        boolean requested,
        boolean extensionSupported,
        boolean featureSupported,
        boolean enabled,
        String unavailableReason
    ) {
        this.deviceFaultDiagnostics.publishNegotiation(
            requested,
            extensionSupported,
            featureSupported,
            enabled,
            unavailableReason
        );
    }

    public long clientFrameId() {
        return this.clientFrameId;
    }

    public void recordMotionComputePass() {
        this.recordGpuPass(
            GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
        );
    }

    public void recordDlssEvaluationPass() {
        this.recordGpuPass(
            GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
        );
    }

    public void recordVulkanSubmit(long submitIndex) {
        GpuSubmissionBreadcrumbs breadcrumbs =
            this.gpuBreadcrumbsOrNull();
        if (breadcrumbs == null) {
            return;
        }
        try {
            breadcrumbs.recordSubmit(
                this.clientFrameId,
                submitIndex
            );
            this.gpuBreadcrumbsStatus = "active";
        } catch (RuntimeException | LinkageError error) {
            this.disableGpuBreadcrumbs(error);
        }
    }

    public void recordVulkanCompletion(long completedSubmitIndex) {
        GpuSubmissionBreadcrumbs breadcrumbs = this.gpuBreadcrumbs;
        if (breadcrumbs == null || this.gpuBreadcrumbsFaulted) {
            return;
        }
        try {
            breadcrumbs.recordCompletion(completedSubmitIndex);
        } catch (RuntimeException | LinkageError error) {
            this.disableGpuBreadcrumbs(error);
        }
    }

    public void vulkanEncoderDestroyedWithoutCompletionProof() {
        GpuSubmissionBreadcrumbs breadcrumbs = this.gpuBreadcrumbs;
        if (breadcrumbs == null || this.gpuBreadcrumbsFaulted) {
            return;
        }
        try {
            int abandoned =
                breadcrumbs.encoderDestroyedWithoutCompletionProof();
            this.gpuBreadcrumbsStatus =
                "active: encoder destroyed, "
                    + abandoned
                    + " pending unproven";
        } catch (RuntimeException | LinkageError error) {
            this.disableGpuBreadcrumbs(error);
        }
    }

    /**
     * Completes accounting for every managed Vulkan object whose destruction
     * was synchronously drained with the device command encoder.
     *
     * <p>This device-owner boundary is independent of DLSS. Terrain GPU-scene
     * buffers, material samplers and other BlockFrame owners may enqueue
     * destruction even when no Streamline connection exists.</p>
     */
    public synchronized void completeVulkanRetirementsAfterEncoderDrain() {
        if (this.closed) {
            return;
        }
        this.shaderResources.completeGpuRetirements();
        this.memoryBudgets.completeGpuRetirements();
    }

    /**
     * Lazily creates the client-scoped native shader-staging block.
     *
     * <p>The only current consumer is Vulkan shader-module setup. OpenGL
     * never requests this pool, and rejection leaves that consumer's direct
     * allocation fallback in control.</p>
     */
    public synchronized ReusableNativeBlockPool nativeStagingPoolOrNull() {
        if (
            this.closed
                || this.closing
                || this.compatibilityDisabled
                || !this.config.settings().engineEnabled()
                || !this.featurePolicy.enabled(
                    FeatureId.SHADER_SETUP_POOL
                )
        ) {
            this.nativeStagingPoolStatus = "disabled";
            return null;
        }
        if (this.nativeStagingPool != null) {
            if (!this.nativeStagingPoolEvictableRegistered) {
                this.nativeStagingPoolStatus =
                    "direct fallback: eviction unavailable";
                return null;
            }
            try {
                if (!this.nativeStagingPool.touchLease()) {
                    this.nativeStagingPoolStatus =
                        "direct fallback: eviction in progress";
                    return null;
                }
                this.nativeStagingPoolStatus =
                    "active evictable: 1x32768 bytes";
                return this.nativeStagingPool;
            } catch (Throwable error) {
                this.nativeStagingPoolStatus =
                    "direct fallback: "
                        + error.getClass().getSimpleName();
                return null;
            }
        }
        if (this.nativeStagingPoolCreationAttempted) {
            return null;
        }

        this.nativeStagingPoolCreationAttempted = true;
        try {
            ReusableNativeBlockPool pool =
                ReusableNativeBlockPool.tryCreate(
                    this.memoryBudgets,
                    MemoryCategory.STAGING,
                    NATIVE_STAGING_BLOCK_COUNT,
                    NATIVE_STAGING_BLOCK_BYTES
                );
            if (pool == null) {
                this.nativeStagingPoolStatus =
                    "direct fallback: RAM/STAGING rejected";
                return null;
            }
            this.nativeStagingPool = pool;
            if (
                !pool.registerEvictable(
                    () -> this.evictNativeStagingPool(pool)
                )
            ) {
                this.nativeStagingPoolStatus =
                    "direct fallback: eviction registration rejected";
                try {
                    pool.close();
                    if (this.nativeStagingPool == pool) {
                        this.nativeStagingPool = null;
                    }
                } catch (Throwable closeFailure) {
                    // Ownership remains published for a later shutdown retry.
                    this.nativeStagingPoolStatus =
                        "direct fallback: registration cleanup "
                            + closeFailure
                                .getClass()
                                .getSimpleName();
                }
                return null;
            }
            this.nativeStagingPoolEvictableRegistered = true;
            this.nativeStagingPoolStatus =
                "active evictable: 1x32768 bytes";
            return pool;
        } catch (Throwable error) {
            this.nativeStagingPoolStatus =
                "direct fallback: "
                    + error.getClass().getSimpleName();
            return null;
        }
    }

    private synchronized boolean evictNativeStagingPool(
        ReusableNativeBlockPool pool
    ) {
        if (
            this.nativeStagingPool != pool
                || !this.nativeStagingPoolEvictableRegistered
        ) {
            return false;
        }
        try {
            pool.close();
        } catch (Throwable error) {
            this.nativeStagingPoolStatus =
                "eviction deferred: "
                    + error.getClass().getSimpleName();
            return false;
        }
        if (this.nativeStagingPool == pool) {
            this.nativeStagingPool = null;
            this.nativeStagingPoolEvictableRegistered = false;
            this.nativeStagingPoolStatus =
                "evicted: direct fallback until configuration reload";
        }
        return true;
    }

    public DeviceInfo deviceInfo() {
        return this.deviceInfo;
    }

    public EngineCapabilities capabilities() {
        return this.capabilities;
    }

    public VulkanRuntimeInfo vulkanRuntimeInfo() {
        return this.vulkanRuntimeInfo;
    }

    /**
     * Receives the Vulkan module's post-device-creation state without creating
     * a core dependency on Vulkan or LWJGL.
     */
    public synchronized void updateBufferDeviceAddressState(boolean available, boolean enabled) {
        this.pendingBufferDeviceAddressAvailable = available;
        this.pendingBufferDeviceAddressEnabled = available && enabled;
        this.pendingBufferDeviceAddressState = true;
    }

    public synchronized void updateVulkanRuntimeInfo(
        VulkanRuntimeInfo runtimeInfo
    ) {
        this.vulkanRuntimeInfo = Objects.requireNonNull(
            runtimeInfo,
            "runtimeInfo"
        );
    }

    /**
     * The managed upload path is safe only when configuration permits it and
     * Mojang reports persistent mapping for the active backend.
     */
    public boolean managedFrameResourcesEnabled() {
        EngineConfig.Settings settings = this.config.settings();
        return !this.closed
            && !this.closing
            && !this.compatibilityDisabled
            && this.cachedDevice != null
            && settings.engineEnabled()
            && settings.frameResourcesEnabled()
            && this.capabilities.persistentMapping();
    }

    public List<String> debugLines() {
        EngineConfig.Settings settings = this.config.settings();
        EngineCapabilities currentCapabilities = this.capabilities;
        FrameProfiler.Snapshot snapshot = this.profiler.snapshot();
        CacheStatistics.Snapshot cache = this.cacheStatistics.snapshot();
        MemoryBudgetManager.Snapshot memory = this.memoryBudgets.snapshot();
        PhysicalMemoryTelemetry.Snapshot physicalMemory =
            this.physicalMemoryTelemetry.snapshot();
        List<String> lines = new ArrayList<>(24);
        lines.add(
            "BlockFrame Engine: "
                + state(
                        settings.engineEnabled()
                            && !this.compatibilityDisabled
                            && !this.closing
                            && !this.closed
                )
        );
        if (this.compatibilityDisabled) {
            lines.add("Compatibility fallback: " + this.compatibilityReason);
        }
        lines.add(
            "Backend: "
                + currentCapabilities.backend()
                + " / "
                + currentCapabilities.vendorName()
                + " "
                + currentCapabilities.deviceName()
        );
        lines.add(
            "Frame resources: "
                + (this.managedFrameResourcesEnabled()
                    ? "eligible / NOT_ATTACHED (vanilla owner)"
                    : settings.frameResourcesEnabled() ? "vanilla fallback" : "disabled")
        );
        lines.add(
            "Indirect/direct multi-draw: "
                + currentCapabilities.drawIndirect()
                + "/"
                + currentCapabilities.multiDrawIndirect()
                + "/"
                + currentCapabilities.multiDrawDirect()
        );
        lines.add(
            "BDA available/enabled: "
                + currentCapabilities.bufferDeviceAddressAvailable()
                + "/"
                + currentCapabilities.bufferDeviceAddressEnabled()
        );
        VulkanRuntimeInfo vulkan = this.vulkanRuntimeInfo;
        lines.add(
            "Vulkan: "
                + (vulkan.active()
                    ? vulkan.apiVersionString()
                        + " device="
                        + vulkan.deviceKey()
                        + " driverId="
                        + vulkan.driverId()
                    : "N/A")
        );
        lines.add(
            "RenderPass submissions / visible sections: "
                + snapshot.drawCalls()
                + " / "
                + snapshot.visibleSections()
        );
        lines.add(
            String.format(
                Locale.ROOT,
                "CPU cull frontend/upload encode: %.3f / %.3f ms, copied: %d bytes",
                nanosToMillis(snapshot.cpuCullNanos()),
                nanosToMillis(snapshot.uploadNanos()),
                snapshot.uploadBytes()
            )
        );
        lines.add(
            String.format(
                Locale.ROOT,
                "GPU p50/p95/p99: %.3f / %.3f / %.3f ms (%d samples)",
                nanosToMillis(snapshot.p50GpuFrameNanos()),
                nanosToMillis(snapshot.p95GpuFrameNanos()),
                nanosToMillis(snapshot.p99GpuFrameNanos()),
                snapshot.rollingGpuSampleCount()
            )
        );
        lines.add(
            "GPU timer: "
                + (this.gpuFrameTimer == null
                    ? "not attached in this runtime"
                    : this.gpuFrameTimer.status())
        );
        lines.add(
            "GPU breadcrumbs: " + this.gpuBreadcrumbDebugStatus()
        );
        DeviceFaultDiagnostics.Snapshot deviceFault =
            this.deviceFaultDiagnostics.snapshot();
        lines.add(deviceFaultDebugLine(deviceFault));
        lines.add(
            "Native staging pool: "
                + this.nativeStagingPoolDebugStatus()
        );
        lines.add(
            "Tracy: "
                + (tracyAvailable()
                    ? "active"
                    : "inactive (launch with --tracy)")
        );
        lines.add(
            "Frames: real rendered="
                + snapshot.completedFrames()
                + " | generated=0 (no provider) | displayed=NOT_MEASURED"
        );
        lines.add("Simulation: TPS/MSPT=NOT_MEASURED (server-owned metrics)");
        if (!cache.attached()) {
            lines.add("Cache: NOT_ATTACHED / NOT_MEASURED");
        } else {
            lines.add(
                String.format(
                    Locale.ROOT,
                    "Cache: hit/miss/reject=%d/%d/%d, %.1f%%, entry-bytes=%d/%d B",
                    cache.hits(),
                    cache.misses(),
                    cache.rejectedEntries(),
                    cache.hitRate() * 100.0D,
                    cache.bytesOnDisk(),
                    settings.cacheMaxBytes()
                )
            );
            lines.add(
                String.format(
                    Locale.ROOT,
                    "Cache I/O totals: writes=%d, read/write=%d/%d bytes, load/save=%.3f/%.3f ms",
                    cache.writtenEntries(),
                    cache.loadedBytes(),
                    cache.writtenBytes(),
                    nanosToMillis(cache.loadNanos()),
                    nanosToMillis(cache.saveNanos())
                )
            );
        }
        lines.add(
            String.format(
                Locale.ROOT,
                "Frame p50/p95/p99: %.3f / %.3f / %.3f ms",
                nanosToMillis(snapshot.p50FrameNanos()),
                nanosToMillis(snapshot.p95FrameNanos()),
                nanosToMillis(snapshot.p99FrameNanos())
            )
        );
        lines.add(
            "Upload ring: "
                + snapshot.ringUsedBytes()
                + "/"
                + snapshot.ringCapacityBytes()
                + " bytes"
        );
        lines.add(
            String.format(
                Locale.ROOT,
                "RAM logical used/usable/peak: %.1f/%.1f/%.1f MiB, frag=%.1f MiB",
                bytesToMib(memory.usedBytes(MemoryKind.RAM)),
                bytesToMib(memory.usableBytes(MemoryKind.RAM)),
                bytesToMib(memory.peakBytes(MemoryKind.RAM)),
                bytesToMib(memory.fragmentationBytes(MemoryKind.RAM))
            )
        );
        lines.add(
            "RAM exact entities/shader/staging: "
                + memory.usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.ENTITIES
                )
                + "/"
                + memory.usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.SHADER_RESOURCES
                )
                + "/"
                + memory.usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.STAGING
                )
                + " bytes"
        );
        lines.add(
            "RAM exact diagnostics: "
                + memory.usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.DIAGNOSTICS
                )
                + " bytes"
        );
        lines.add(
            String.format(
                Locale.ROOT,
                "VRAM logical used/usable/peak: %.1f/%.1f/%.1f MiB, shader=%.1f/%.1f MiB",
                bytesToMib(memory.usedBytes(MemoryKind.VRAM)),
                bytesToMib(memory.usableBytes(MemoryKind.VRAM)),
                bytesToMib(memory.peakBytes(MemoryKind.VRAM)),
                bytesToMib(
                    memory.usedBytes(
                        MemoryKind.VRAM,
                        MemoryCategory.SHADER_RESOURCES
                    )
                ),
                bytesToMib(
                    memory.limitBytes(
                        MemoryKind.VRAM,
                        MemoryCategory.SHADER_RESOURCES
                    )
                )
            )
        );
        lines.add(physicalRamDebugLine(physicalMemory));
        lines.add(physicalVramDebugLine(physicalMemory));
        lines.add(
            "Memory leases/reject/evict/leaks: "
                + memory.outstanding()
                + "/"
                + memory.rejections()
                + "/"
                + memory.evictions()
                + "/"
                + memory.leaks()
        );
        lines.add(
            "Memory reclaimed: "
                + memory.reclaimedBytes()
                + " bytes"
        );
        ShaderResourceInventory.Snapshot shader =
            this.shaderResources.snapshot();
        lines.add(
            "Shader objects current/peak: module="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.SHADER_MODULE
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.SHADER_MODULE
                )
                + " dsl="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR_SET_LAYOUT
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR_SET_LAYOUT
                )
                + " pool="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR_POOL
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR_POOL
                )
                + " sets="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR_SET
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR_SET
                )
                + " descriptors="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.DESCRIPTOR
                )
        );
        lines.add(
            "Shader pipeline/buffer/sampler current/peak: layout="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.PIPELINE_LAYOUT
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.PIPELINE_LAYOUT
                )
                + " compute="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.COMPUTE_PIPELINE
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.COMPUTE_PIPELINE
                )
                + " ubo="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.MANAGED_UNIFORM_BUFFER
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.MANAGED_UNIFORM_BUFFER
                )
                + " depth="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.RAW_DEPTH_SAMPLER
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.RAW_DEPTH_SAMPLER
                )
                + " material="
                + shader.current(
                    ShaderResourceInventory.ResourceKind.MATERIAL_SAMPLER
                )
                + "/"
                + shader.peak(
                    ShaderResourceInventory.ResourceKind.MATERIAL_SAMPLER
                )
        );
        lines.add(
            "Shader allocation coverage: exact owned object/descriptor-slot counts; opaque Vulkan driver bytes=NOT_AVAILABLE; integrity="
                + (shader.integrityErrors() == 0L ? "OK" : shader.integrityErrors())
                + " create/cleanup failures="
                + shader.creationFailuresTotal()
                + "/"
                + shader.cleanupFailuresTotal()
                + " leaks="
                + shader.leaks()
        );
        if (this.configurationError != null && !this.configurationError.isBlank()) {
            lines.add("Config read error; enabled defaults are active: " + this.configurationError);
        }
        lines.add("--- Active feature state (cached) ---");
        lines.addAll(this.featureStates.debugLines());
        return List.copyOf(lines);
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closing = true;
        Throwable closeFailure = null;
        try {
            this.abortIncompleteFrame();
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        try {
            this.deviceFaultDiagnostics.close();
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        try {
            this.physicalMemoryTelemetry.close();
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        if (this.gpuFrameTimer != null) {
            try {
                this.gpuFrameTimer.close();
            } catch (Throwable error) {
                closeFailure = accumulateFailure(closeFailure, error);
            }
        }
        GpuSubmissionBreadcrumbs breadcrumbs =
            this.gpuBreadcrumbs;
        if (breadcrumbs != null) {
            try {
                breadcrumbs.close();
                if (this.gpuBreadcrumbs == breadcrumbs) {
                    this.gpuBreadcrumbs = null;
                    this.gpuBreadcrumbsStatus = "closed";
                }
            } catch (Throwable error) {
                closeFailure = accumulateFailure(closeFailure, error);
            }
        }
        ReusableNativeBlockPool stagingPool =
            this.nativeStagingPool;
        if (stagingPool != null) {
            try {
                stagingPool.close();
                if (this.nativeStagingPool == stagingPool) {
                    this.nativeStagingPool = null;
                    this.nativeStagingPoolEvictableRegistered = false;
                    this.nativeStagingPoolStatus = "closed";
                }
            } catch (Throwable error) {
                closeFailure = accumulateFailure(closeFailure, error);
            }
        }
        try {
            ReusableNativeBlockPool.retryPendingCleanup();
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        try {
            GpuSubmissionBreadcrumbs.retryPendingCleanup();
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        try {
            BudgetedNativeArena.retryPendingCleanup();
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        try {
            if (!this.memoryBudgets.closeAndReport()) {
                MemoryBudgetManager.Snapshot memory =
                    this.memoryBudgets.snapshot();
                closeFailure = accumulateFailure(
                    closeFailure,
                    new IllegalStateException(
                        "memory budget cleanup retained "
                            + memory.outstanding()
                            + " outstanding lease(s), leaks="
                            + memory.leaks()
                    )
                );
            }
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        try {
            if (!this.shaderResources.closeAndReport()) {
                ShaderResourceInventory.Snapshot shader =
                    this.shaderResources.snapshot();
                closeFailure = accumulateFailure(
                    closeFailure,
                    new IllegalStateException(
                        "shader resource cleanup retained "
                            + shader.currentTotal()
                            + " object(s), leaks="
                            + shader.leaks()
                            + ", cleanupFailures="
                            + shader.cleanupFailuresTotal()
                            + ", integrityErrors="
                            + shader.integrityErrors()
                    )
                );
            }
        } catch (Throwable error) {
            closeFailure = accumulateFailure(closeFailure, error);
        }
        if (closeFailure != null) {
            rethrowCloseFailure(closeFailure);
        }
        this.closed = true;
        this.closing = false;
        this.cachedDevice = null;
        this.deviceInfo = null;
        this.capabilities = EngineCapabilities.unknown();
        this.vulkanRuntimeInfo = VulkanRuntimeInfo.unavailable();
        this.bufferDeviceAddressAvailable = false;
        this.bufferDeviceAddressEnabled = false;
        this.pendingBufferDeviceAddressAvailable = false;
        this.pendingBufferDeviceAddressEnabled = false;
        this.pendingBufferDeviceAddressState = false;
        this.gpuBreadcrumbsCreationAttempted = false;
        this.gpuBreadcrumbsFaulted = false;
    }

    public synchronized void disableForCompatibility(String reason) {
        Objects.requireNonNull(reason, "reason");
        this.abortIncompleteFrame();
        this.compatibilityReason = reason.isBlank()
            ? "incompatible renderer detected"
            : reason;
        this.compatibilityDisabled = true;
    }

    public boolean compatibilityDisabled() {
        return this.compatibilityDisabled;
    }

    public String compatibilityReason() {
        return this.compatibilityReason;
    }

    private synchronized String nativeStagingPoolDebugStatus() {
        ReusableNativeBlockPool pool = this.nativeStagingPool;
        if (pool == null) {
            return this.nativeStagingPoolStatus;
        }
        try {
            return this.nativeStagingPoolStatus
                + " | borrowed="
                + pool.outstandingBorrows();
        } catch (RuntimeException error) {
            return this.nativeStagingPoolStatus
                + " | borrowed=unavailable";
        }
    }

    public synchronized void vulkanDeviceClosing(VulkanDevice device) {
        Objects.requireNonNull(device, "device");
        if (this.featurePolicy.enabled(FeatureId.DEVICE_FAULT)) {
            try {
                this.deviceFaultDiagnostics.vulkanDeviceClosing(device);
            } catch (Throwable ignored) {
                // Optional diagnostics must not block device cleanup.
            }
        }
        if (this.featurePolicy.enabled(FeatureId.PHYSICAL_MEMORY)) {
            try {
                this.physicalMemoryTelemetry.vulkanDeviceClosing(device);
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError ignored
            ) {
                // Optional telemetry must not block renderer/device cleanup.
            }
        }
        GpuSubmissionBreadcrumbs breadcrumbs = this.gpuBreadcrumbs;
        if (
            this.featurePolicy.enabled(FeatureId.GPU_BREADCRUMBS)
                && breadcrumbs != null
                && !this.gpuBreadcrumbsFaulted
        ) {
            try {
                breadcrumbs.deviceClosing();
                this.gpuBreadcrumbsStatus =
                    "active: Vulkan device closing";
            } catch (RuntimeException | LinkageError error) {
                this.disableGpuBreadcrumbs(error);
            }
        }
        if (this.gpuFrameTimer != null) {
            this.gpuFrameTimer.deviceClosing(device);
        }
        if (this.profilerFrameOpen) {
            this.profilerFrameOpen = false;
            this.profiler.abortFrame();
        }
        this.cachedDevice = null;
        this.deviceInfo = null;
        this.capabilities = EngineCapabilities.unknown();
        this.vulkanRuntimeInfo = VulkanRuntimeInfo.unavailable();
        this.bufferDeviceAddressAvailable = false;
        this.bufferDeviceAddressEnabled = false;
        this.pendingBufferDeviceAddressAvailable = false;
        this.pendingBufferDeviceAddressEnabled = false;
        this.pendingBufferDeviceAddressState = false;
    }

    public synchronized void vulkanDeviceConnected(VulkanDevice device) {
        Objects.requireNonNull(device, "device");
        if (this.closed || this.closing) {
            return;
        }
        if (this.featurePolicy.enabled(FeatureId.DEVICE_FAULT)) {
            DeviceFaultDiagnostics.Snapshot faultState =
                this.deviceFaultDiagnostics.snapshot();
            VulkanDeviceFaultCapture.Binding faultBinding =
                VulkanDeviceFaultCapture.bind(
                    device,
                    faultState.enabled()
                );
            this.deviceFaultDiagnostics.attachVulkanDevice(
                device,
                faultBinding.functionResolved(),
                faultBinding.capture(),
                faultState.enabled()
                    ? faultBinding.unavailableReason()
                    : faultState.unavailableReason()
            );
        }
        if (
            !this.featurePolicy.enabled(
                FeatureId.PHYSICAL_MEMORY
            )
        ) {
            return;
        }
        VulkanRuntimeInfo runtimeInfo = this.vulkanRuntimeInfo;
        boolean extensionAdvertised =
            runtimeInfo.active()
                && runtimeInfo.memoryBudgetExtensionAdvertised();
        PhysicalMemoryTelemetry.DeviceProbe probe = null;
        if (extensionAdvertised) {
            try {
                probe = new VulkanMemoryBudgetProbe(device);
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError ignored
            ) {
                // attachVulkanDevice publishes QUERY_FAILED without throwing.
            }
        }
        this.physicalMemoryTelemetry.attachVulkanDevice(
            device,
            extensionAdvertised,
            probe
        );
    }

    private void detectDevice() {
        GpuDevice current;
        try {
            current = RenderSystem.tryGetDevice();
        } catch (RuntimeException | LinkageError ignored) {
            current = null;
        }
        if (current == this.cachedDevice) {
            return;
        }

        synchronized (this) {
            if (current == this.cachedDevice) {
                return;
            }
            if (this.pendingBufferDeviceAddressState) {
                this.bufferDeviceAddressAvailable =
                    this.pendingBufferDeviceAddressAvailable;
                this.bufferDeviceAddressEnabled =
                    this.pendingBufferDeviceAddressEnabled;
                this.pendingBufferDeviceAddressState = false;
            } else {
                this.bufferDeviceAddressAvailable = false;
                this.bufferDeviceAddressEnabled = false;
            }
            DeviceInfo detectedInfo;
            try {
                detectedInfo = current == null ? null : current.getDeviceInfo();
            } catch (RuntimeException | LinkageError ignored) {
                current = null;
                detectedInfo = null;
            }
            this.cachedDevice = current;
            this.deviceInfo = detectedInfo;
            this.capabilities = EngineCapabilities.from(
                this.deviceInfo,
                this.bufferDeviceAddressAvailable,
                this.bufferDeviceAddressEnabled
            );
            if (this.capabilities.backend() != EngineCapabilities.Backend.VULKAN) {
                this.vulkanRuntimeInfo = VulkanRuntimeInfo.unavailable();
                if (this.featurePolicy.enabled(FeatureId.DEVICE_FAULT)) {
                    this.deviceFaultDiagnostics.notVulkanBackend();
                }
                if (
                    this.featurePolicy.enabled(
                        FeatureId.PHYSICAL_MEMORY
                    )
                ) {
                    this.physicalMemoryTelemetry.notVulkanBackend();
                }
            }
        }
    }

    public DeviceFaultDiagnostics.Snapshot recordVulkanResult(
        VulkanDevice device,
        int result,
        String context
    ) {
        return this.deviceFaultDiagnostics.recordResult(
            device,
            result,
            context
        );
    }

    private static String deviceFaultDebugLine(
        DeviceFaultDiagnostics.Snapshot snapshot
    ) {
        return "Vulkan device fault: requested="
            + snapshot.requested()
            + " extension-supported="
            + snapshot.extensionSupported()
            + " feature-supported="
            + snapshot.featureSupported()
            + " enabled="
            + snapshot.enabled()
            + " function-resolved="
            + snapshot.functionResolved()
            + " capture-status="
            + snapshot.captureStatus()
            + " unavailable-reason="
            + (snapshot.unavailableReason().isBlank()
                ? "none"
                : snapshot.unavailableReason())
            + " generation="
            + snapshot.generation()
            + " stale-events="
            + snapshot.staleDeviceEvents();
    }

    private void abortIncompleteFrame() {
        if (!this.profilerFrameOpen) {
            return;
        }
        this.profilerFrameOpen = false;
        if (this.gpuFrameTimer != null) {
            this.gpuFrameTimer.abortFrame();
        }
        this.profiler.abortFrame();
    }

    private void recordGpuPass(int passId) {
        GpuSubmissionBreadcrumbs breadcrumbs =
            this.gpuBreadcrumbsOrNull();
        if (breadcrumbs == null) {
            return;
        }
        try {
            breadcrumbs.recordEncoded(this.clientFrameId, passId);
            this.gpuBreadcrumbsStatus = "active";
        } catch (RuntimeException | LinkageError error) {
            this.disableGpuBreadcrumbs(error);
        }
    }

    private synchronized GpuSubmissionBreadcrumbs
        gpuBreadcrumbsOrNull() {
        EngineConfig.Settings settings = this.config.settings();
        if (
            this.closed
                || this.closing
                || this.compatibilityDisabled
                || !settings.engineEnabled()
                || !settings.gpuBreadcrumbsEnabled()
                || !this.featurePolicy.enabled(
                    FeatureId.GPU_BREADCRUMBS
                )
                || this.gpuBreadcrumbsFaulted
                || this.capabilities.backend()
                    != EngineCapabilities.Backend.VULKAN
        ) {
            return null;
        }
        if (this.gpuBreadcrumbs != null) {
            return this.gpuBreadcrumbs;
        }
        if (this.gpuBreadcrumbsCreationAttempted) {
            return null;
        }

        this.gpuBreadcrumbsCreationAttempted = true;
        try {
            GpuSubmissionBreadcrumbs created =
                GpuSubmissionBreadcrumbs.tryCreate(
                    this.memoryBudgets
                );
            if (created == null) {
                this.gpuBreadcrumbsStatus =
                    "unavailable: RAM/DIAGNOSTICS budget/allocation";
                return null;
            }
            this.gpuBreadcrumbs = created;
            this.gpuBreadcrumbsStatus =
                "active: 64x6 longs, 3072 bytes";
            return created;
        } catch (
            OutOfMemoryError
                | RuntimeException
                | LinkageError error
        ) {
            this.gpuBreadcrumbsStatus =
                "unavailable: " + error.getClass().getSimpleName();
            this.gpuBreadcrumbsFaulted = true;
            return null;
        }
    }

    private synchronized String gpuBreadcrumbDebugStatus() {
        GpuSubmissionBreadcrumbs breadcrumbs =
            this.gpuBreadcrumbs;
        if (breadcrumbs == null || this.gpuBreadcrumbsFaulted) {
            return this.gpuBreadcrumbsStatus;
        }
        try {
            GpuSubmissionBreadcrumbs.Snapshot snapshot =
                breadcrumbs.snapshot();
            return this.gpuBreadcrumbsStatus
                + " | e/s/c="
                + snapshot.encodedEntries()
                + "/"
                + snapshot.submittedEntries()
                + "/"
                + snapshot.completedEntries()
                + " | last-frame="
                + snapshot.lastEncodedFrame()
                + "/"
                + snapshot.lastSubmittedFrame()
                + "/"
                + snapshot.lastCompletedFrame()
                + " | submit="
                + snapshot.lastSubmittedIndex()
                + "/"
                + snapshot.lastCompletedIndex()
                + " | overwrite/abandon="
                + snapshot.overwritten()
                + "/"
                + snapshot.abandoned();
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            this.disableGpuBreadcrumbs(error);
            return this.gpuBreadcrumbsStatus;
        }
    }

    private synchronized void disableGpuBreadcrumbs(Throwable error) {
        this.gpuBreadcrumbsFaulted = true;
        this.gpuBreadcrumbsStatus =
            "unavailable: " + error.getClass().getSimpleName();
    }

    private synchronized void applyGpuBreadcrumbConfiguration(
        EngineConfig.Settings settings
    ) {
        if (
            !settings.gpuBreadcrumbsEnabled()
                || !this.featurePolicy.enabled(
                    FeatureId.GPU_BREADCRUMBS
                )
        ) {
            this.gpuBreadcrumbsStatus = "disabled by configuration";
            this.gpuBreadcrumbsCreationAttempted = true;
            this.gpuBreadcrumbsFaulted = false;
            GpuSubmissionBreadcrumbs breadcrumbs =
                this.gpuBreadcrumbs;
            if (breadcrumbs != null) {
                try {
                    breadcrumbs.close();
                    if (this.gpuBreadcrumbs == breadcrumbs) {
                        this.gpuBreadcrumbs = null;
                    }
                } catch (
                    RuntimeException
                        | LinkageError
                        | OutOfMemoryError error
                ) {
                    this.disableGpuBreadcrumbs(error);
                }
            }
            return;
        }
        this.gpuBreadcrumbsFaulted = false;
        GpuSubmissionBreadcrumbs breadcrumbs = this.gpuBreadcrumbs;
        if (breadcrumbs != null) {
            try {
                breadcrumbs.close();
                if (this.gpuBreadcrumbs == breadcrumbs) {
                    this.gpuBreadcrumbs = null;
                }
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError error
            ) {
                this.disableGpuBreadcrumbs(error);
                return;
            }
        }
        this.gpuBreadcrumbsCreationAttempted = false;
        this.gpuBreadcrumbsStatus =
            "not requested after configuration reload";
    }

    private static String physicalRamDebugLine(
        PhysicalMemoryTelemetry.Snapshot snapshot
    ) {
        if (
            snapshot.ramStatus()
                != PhysicalMemoryTelemetry.RamStatus.AVAILABLE
        ) {
            return "RAM physical OS: "
                + snapshot.ramStatus()
                + " (no numeric value) | samples/fail="
                + snapshot.ramSamples()
                + "/"
                + snapshot.ramFailures();
        }
        return String.format(
            Locale.ROOT,
            "RAM physical OS available/total: %d/%d bytes (%.2f/%.2f GiB) | samples/fail=%d/%d",
            snapshot.ramAvailableBytes(),
            snapshot.ramTotalBytes(),
            bytesToGib(snapshot.ramAvailableBytes()),
            bytesToGib(snapshot.ramTotalBytes()),
            snapshot.ramSamples(),
            snapshot.ramFailures()
        );
    }

    private static String physicalVramDebugLine(
        PhysicalMemoryTelemetry.Snapshot snapshot
    ) {
        if (
            snapshot.deviceStatus()
                != PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE
        ) {
            return "VRAM driver device-local: "
                + snapshot.deviceStatus()
                + " (no numeric value) | samples/fail="
                + snapshot.deviceSamples()
                + "/"
                + snapshot.deviceFailures()
                + " wrong/reentrant/owner/stale-close="
                + snapshot.wrongThreadSkips()
                + "/"
                + snapshot.reentrantSkips()
                + "/"
                + snapshot.ownerConflicts()
                + "/"
                + snapshot.staleCloseAttempts();
        }
        return String.format(
            Locale.ROOT,
            "VRAM driver device-local budget/usage/headroom/heap: %d/%d/%d/%d bytes (%.2f/%.2f/%.2f/%.2f GiB) | heaps=%d samples/fail=%d/%d | process estimate, may be shared",
            snapshot.deviceBudgetBytes(),
            snapshot.deviceUsageBytes(),
            snapshot.deviceHeadroomBytes(),
            snapshot.deviceHeapBytes(),
            bytesToGib(snapshot.deviceBudgetBytes()),
            bytesToGib(snapshot.deviceUsageBytes()),
            bytesToGib(snapshot.deviceHeadroomBytes()),
            bytesToGib(snapshot.deviceHeapBytes()),
            snapshot.deviceLocalHeapCount(),
            snapshot.deviceSamples(),
            snapshot.deviceFailures()
        );
    }

    private static String state(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static double bytesToMib(long bytes) {
        return bytes / (1024.0D * 1024.0D);
    }

    private static double bytesToGib(long bytes) {
        return bytes / (1024.0D * 1024.0D * 1024.0D);
    }

    private static boolean tracyAvailable() {
        return GpuPassDiagnostics.snapshot().tracySupported();
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static Throwable accumulateFailure(
        Throwable first,
        Throwable next
    ) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(
            "BlockFrame cleanup failed",
            failure
        );
    }
}
