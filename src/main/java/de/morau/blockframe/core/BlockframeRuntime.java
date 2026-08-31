package de.morau.blockframe.core;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.diagnostics.DeviceFaultDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.PhysicalMemoryFeatureAvailability;
import de.morau.blockframe.core.diagnostics.PhysicalMemoryTelemetry;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.memory.ReusableNativeBlockPool;
import de.morau.blockframe.core.state.ConfirmedRunError;
import de.morau.blockframe.core.state.FeatureConfigFingerprint;
import de.morau.blockframe.core.state.FeatureId;
import de.morau.blockframe.core.state.FeatureState;
import de.morau.blockframe.core.state.FeatureStateRegistry;
import de.morau.blockframe.core.state.RunBackend;
import de.morau.blockframe.core.state.RunCheckpoint;
import de.morau.blockframe.core.state.RunPhase;
import de.morau.blockframe.core.state.RunStateIdentity;
import de.morau.blockframe.core.state.RunStateRecord;
import de.morau.blockframe.core.state.RunStateStore;
import de.morau.blockframe.core.state.RuntimeFeaturePolicy;
import de.morau.blockframe.core.state.WorldFrameStabilityTracker;
import de.morau.nvidiadlss.DeveloperDiagnostics;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single client/runtime ownership boundary for BlockFrame Phase-1 services.
 *
 * <p>Normal user configuration is read before this owner is published and is
 * never written here. Run-state I/O is confined to explicit lifecycle
 * transitions. Warm-frame methods read cached/primitive state only.</p>
 */
public final class BlockframeRuntime {
    public static final Path RUN_STATE_DIRECTORY =
        Path.of("config", "blockframe-state");
    public static final int STABLE_WORLD_FRAME_COUNT = 120;

    private static final Logger LOGGER =
        LoggerFactory.getLogger("blockframe-runtime");
    private static final Object INITIALIZATION_LOCK = new Object();
    private static volatile ClientState client;

    private BlockframeRuntime() {
    }

    /**
     * Publishes the real per-process policy before Vulkan device creation.
     * Repeated mod-construction calls are harmless and do not reopen state.
     */
    public static void initializeClient(
        String modVersion,
        String minecraftVersion,
        String dlssMode,
        String sharpeningMode,
        int sharpeningAmount,
        String entityHistoryBackend
    ) {
        Objects.requireNonNull(modVersion, "modVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        synchronized (INITIALIZATION_LOCK) {
            ClientState previous = client;
            if (previous != null && previous.explicitInitialization) {
                return;
            }
            if (previous != null) {
                try {
                    previous.engine.close();
                } catch (Throwable error) {
                    LOGGER.warn(
                        "Implicit BlockFrame fallback owner could not be "
                            + "closed before client initialization",
                        error
                    );
                }
            }

            EngineConfig config = new EngineConfig();
            String configurationStatus = "loaded";
            try {
                config.load();
            } catch (IOException | SecurityException error) {
                configurationStatus =
                    "safe-defaults:"
                        + error.getClass().getSimpleName();
                config.setSettings(EngineConfig.Settings.defaults());
                LOGGER.warn(
                    "BlockFrame engine configuration is unavailable; "
                        + "documented safe defaults are used",
                    error
                );
            }

            EngineConfig.Settings settings = config.settings();
            String fingerprint = FeatureConfigFingerprint.compute(
                settings,
                dlssMode,
                sharpeningMode,
                sharpeningAmount,
                entityHistoryBackend
            );
            RuntimeFeaturePolicy normalPolicy =
                new RuntimeFeaturePolicy(
                    settings,
                    dlssMode,
                    entityHistoryBackend,
                    false
                );
            RunStateIdentity identity = new RunStateIdentity(
                modVersion,
                minecraftVersion,
                fingerprint,
                1,
                normalPolicy.requestedMask(),
                0L,
                0L
            );

            RunStateStore store;
            try {
                store = RunStateStore.open(
                    RUN_STATE_DIRECTORY,
                    identity
                );
            } catch (
                RuntimeException
                    | LinkageError persistenceFailure
            ) {
                store = null;
                LOGGER.warn(
                    "BlockFrame run-state persistence could not be "
                        + "initialized; renderer startup continues in-memory",
                    persistenceFailure
                );
            }
            boolean safeStart =
                store != null && store.safeStartActive();
            RuntimeFeaturePolicy effectivePolicy =
                safeStart
                    ? new RuntimeFeaturePolicy(
                        settings,
                        dlssMode,
                        entityHistoryBackend,
                        true
                    )
                    : normalPolicy;
            FeatureStateRegistry featureStates =
                new FeatureStateRegistry();
            long clientGeneration = store == null
                ? 1L
                : store.snapshot().runGeneration();
            effectivePolicy.publishInitial(
                featureStates,
                clientGeneration
            );
            if (DeveloperDiagnostics.ENABLED) {
                GpuPassDiagnostics.configure(
                    effectivePolicy.enabled(FeatureId.DEBUG_LABELS),
                    effectivePolicy.enabled(FeatureId.TRACY_CORRELATION)
                );
            }
            BlockframeEngine engine = new BlockframeEngine(
                config,
                effectivePolicy,
                featureStates
            );
            ClientState initialized = new ClientState(
                engine,
                effectivePolicy,
                featureStates,
                store,
                clientGeneration,
                true,
                configurationStatus
            );
            client = initialized;
            initialized.logInitialState();
        }
    }

    public static BlockframeEngine engine() {
        return state().engine;
    }

    public static boolean featureEnabled(FeatureId id) {
        return state().featurePolicy.enabled(
            Objects.requireNonNull(id, "id")
        );
    }

    /**
     * Cached process-policy lookup that never creates the implicit fallback
     * owner. Disabled mixin paths use this before loading their heavy runtime.
     */
    public static boolean featureEnabledIfInitialized(FeatureId id) {
        Objects.requireNonNull(id, "id");
        ClientState current = client;
        return current != null && current.featurePolicy.enabled(id);
    }

    public static boolean safeStartActive() {
        ClientState current = client;
        return current != null && current.safeStartActive();
    }

    /**
     * Fail-closed, immutable process-start permission for Streamline.
     * This is read before any native cache or native bootstrap work.
     */
    public static boolean streamlineBootstrapAllowed() {
        ClientState current = client;
        return current != null
            && current.featurePolicy.streamlineBootstrapAllowed();
    }

    public static boolean safeStartOfferAvailable() {
        ClientState current = client;
        return current != null && current.safeStartOfferAvailable();
    }

    public static void markSafeStartOffered() {
        ClientState current = client;
        if (current == null || !current.markSafeStartOffered()) {
            throw new IllegalStateException(
                "Safe-Start offer could not be durably published"
            );
        }
    }

    public static boolean queueSafeStartForNextRun() {
        ClientState current = client;
        return current != null && current.queueSafeStartForNextRun();
    }

    public static void declineSafeStart() {
        ClientState current = client;
        if (current != null) {
            current.declineSafeStart();
        }
    }

    /** Cached F8 lines; no file, OS, Tracy or Vulkan query is performed. */
    public static List<String> runStateDebugLines() {
        ClientState current = client;
        return current == null
            ? List.of("Run state: runtime not initialized")
            : current.runStateDebugLines;
    }

    public static long clientGeneration() {
        ClientState current = client;
        return current == null ? 0L : current.clientGeneration;
    }

    public static long deviceGeneration() {
        ClientState current = client;
        return current == null ? 0L : current.deviceGeneration;
    }

    public static MemoryBudgetManager memoryBudgets() {
        return engine().memoryBudgets();
    }

    public static ShaderResourceInventory shaderResources() {
        return engine().shaderResources();
    }

    public static ReusableNativeBlockPool nativeStagingPoolOrNull() {
        ReusableNativeBlockPool pool =
            engine().nativeStagingPoolOrNull();
        ClientState current = client;
        if (current != null) {
            if (pool == null) {
                current.publishFeatureFallback(
                    FeatureId.SHADER_SETUP_POOL,
                    true,
                    false,
                    "direct-allocation-fallback"
                );
            } else {
                current.publishFeatureEffective(
                    FeatureId.SHADER_SETUP_POOL,
                    "evictable-native-block-active"
                );
            }
        }
        return pool;
    }

    public static void featureBecameEffective(
        FeatureId id,
        String reason
    ) {
        ClientState current = client;
        if (current != null) {
            current.publishFeatureEffective(id, reason);
        }
    }

    public static void featureUsedFallback(
        FeatureId id,
        boolean supported,
        boolean quarantined,
        String reason
    ) {
        ClientState current = client;
        if (current != null) {
            current.publishFeatureFallback(
                id,
                supported,
                quarantined,
                reason
            );
        }
    }

    /** Rebases run-state identity from the canonical live DLSS snapshot. */
    public static void dlssConfigurationChanged(
        String dlssMode,
        String sharpeningMode,
        int sharpeningAmount,
        String entityHistoryBackend
    ) {
        ClientState current = client;
        if (current == null) {
            return;
        }
        current.rebaseDlssConfiguration(
            dlssMode,
            sharpeningMode,
            sharpeningAmount,
            entityHistoryBackend
        );
    }

    public static void recordMotionComputePass() {
        if (!DeveloperDiagnostics.ENABLED) {
            return;
        }
        ClientState current = state();
        current.engine.recordMotionComputePass();
        current.observeBreadcrumbAfterRecord();
    }

    public static void recordDlssEvaluationPass() {
        ClientState current = state();
        if (DeveloperDiagnostics.ENABLED) {
            current.engine.recordDlssEvaluationPass();
            current.observeBreadcrumbAfterRecord();
        }
        current.publishFeatureEffective(
            FeatureId.DLSS_MODE,
            "dlss-evaluation-encoded"
        );
    }

    public static void recordVulkanSubmit(long submitIndex) {
        if (!DeveloperDiagnostics.ENABLED) {
            return;
        }
        engine().recordVulkanSubmit(submitIndex);
    }

    public static void recordVulkanCompletion(
        long completedSubmitIndex
    ) {
        if (!DeveloperDiagnostics.ENABLED) {
            return;
        }
        engine().recordVulkanCompletion(completedSubmitIndex);
    }

    public static void vulkanEncoderDestroyedWithoutCompletionProof() {
        if (!DeveloperDiagnostics.ENABLED) {
            return;
        }
        engine().vulkanEncoderDestroyedWithoutCompletionProof();
    }

    public static void completeVulkanRetirementsAfterEncoderDrain() {
        engine().completeVulkanRetirementsAfterEncoderDrain();
    }

    public static void beginFrame() {
        ClientState current = state();
        current.engine.beginFrame();
        current.observeDetectedBackend();
        if (DeveloperDiagnostics.ENABLED) {
            current.observeCachedDiagnosticStates();
        }
        if (current.engine.profilerFrameOpen()) {
            current.publishFeatureEffective(
                FeatureId.FRAME_PROFILER,
                "frame-profiler-active"
            );
        }
    }

    public static void endFrame() {
        engine().endFrame();
        de.morau.blockframe.render.terrain.nativeengine
            .NativeTerrainBackendFoundation.frameEnded();
    }

    public static void recordSuccessfulWorldFrame(
        Object worldIdentity
    ) {
        ClientState current = client;
        if (current != null) {
            current.recordSuccessfulWorldFrame(
                Objects.requireNonNull(worldIdentity, "worldIdentity")
            );
        }
    }

    public static void recordFailedWorldFrame() {
        ClientState current = client;
        if (current != null) {
            current.recordFailedWorldFrame();
        }
    }

    public static void worldUnavailable() {
        ClientState current = client;
        if (current != null) {
            current.worldUnavailable();
        }
    }

    public static void clientStarted() {
        ClientState current = client;
        if (current != null) {
            current.clientStartedObserved = true;
            LOGGER.info(
                "BlockFrame client lifecycle: CLIENT_STARTED runGen={}",
                current.clientGeneration
            );
        }
    }

    public static void resourceLoadFinished() {
        ClientState current = client;
        if (current != null) {
            current.resourceLoadFinished();
            LOGGER.info(
                "BlockFrame client lifecycle: "
                    + "RESOURCE_LOAD_FINISHED (initial or F3+t)"
            );
        }
    }

    public static void clientStopping() {
        ClientState current = client;
        if (current != null) {
            current.clientStoppingObserved = true;
            LOGGER.info("BlockFrame client lifecycle: CLIENT_STOPPING");
        }
    }

    public static void clientStopped() {
        ClientState current = client;
        if (current != null) {
            current.clientStoppedObserved = true;
            LOGGER.info("BlockFrame client lifecycle: CLIENT_STOPPED");
            current.tryFinalizeClientClose();
        }
    }

    public static void recordDrawCall() {
        BlockframeEngine currentEngine = engine();
        if (currentEngine.profilerFrameOpen()) {
            currentEngine.profiler().recordDrawCall();
        }
    }

    public static void recordVisibleSections(long count) {
        BlockframeEngine currentEngine = engine();
        if (currentEngine.profilerFrameOpen()) {
            currentEngine.profiler().setVisibleSections(count);
        }
    }

    public static void recordCpuCull(long nanos) {
        BlockframeEngine currentEngine = engine();
        if (currentEngine.profilerFrameOpen()) {
            currentEngine.profiler().recordCpuCull(nanos);
        }
    }

    public static void recordUpload(long bytes, long nanos) {
        BlockframeEngine currentEngine = engine();
        if (currentEngine.profilerFrameOpen()) {
            currentEngine.profiler().recordUpload(bytes, nanos);
        }
    }

    public static void vulkanDeviceClosing(VulkanDevice device) {
        ClientState current = state();
        current.engine.vulkanDeviceClosing(device);
        current.vulkanDeviceClosing(device);
    }

    public static void vulkanDeviceConnected(VulkanDevice device) {
        ClientState current = state();
        current.engine.vulkanDeviceConnected(device);
        current.vulkanDeviceConnected(device);
    }

    public static void publishDlssConnection(
        boolean connected,
        String reason
    ) {
        ClientState current = client;
        if (current == null) {
            return;
        }
        if (connected) {
            current.publishFeature(
                FeatureId.DLSS_MODE,
                true,
                false,
                false,
                false,
                "vulkan-streamline-ready"
            );
        } else {
            current.publishFeatureFallback(
                FeatureId.DLSS_MODE,
                current.backend == RunBackend.VULKAN,
                false,
                stableReason(reason, "vulkan-dlss-unavailable")
            );
        }
    }

    /**
     * Records and attempts to durably publish the confirmed loss before
     * optional vendor diagnostics run. Persistence failure remains fail-open
     * and is logged explicitly as in-memory-only.
     */
    public static DeviceFaultDiagnostics.Snapshot recordVulkanDeviceLost(
        VulkanDevice device,
        int result,
        String context
    ) {
        ClientState current = state();
        current.markConfirmedFailure(
            ConfirmedRunError.DEVICE_LOSS,
            "vk-error-device-lost"
        );
        return current.engine.recordVulkanResult(
            device,
            result,
            context
        );
    }

    public static void recordConfirmedBlockframeError(
        String stableContextCode
    ) {
        ClientState current = client;
        if (current != null) {
            current.markConfirmedFailure(
                ConfirmedRunError.BLOCKFRAME_ERROR,
                stableContextCode
            );
        }
    }

    /**
     * Called by the Minecraft.close wrapper after optional client resources
     * were attempted. A clean marker is possible only with all four proofs.
     */
    public static void clientCloseReturned(
        boolean originalReturnedNormally,
        boolean dlssCleanupSucceeded
    ) {
        ClientState current = client;
        if (current == null) {
            return;
        }
        current.originalCloseReturnedNormally = originalReturnedNormally;
        current.dlssCleanupSucceeded = dlssCleanupSucceeded;
        current.cleanupResultAvailable = true;
        try {
            current.engine.close();
            current.engineCleanupSucceeded = true;
        } catch (Throwable error) {
            current.engineCleanupSucceeded = false;
            current.markConfirmedFailure(
                ConfirmedRunError.BLOCKFRAME_ERROR,
                "engine-cleanup-failed"
            );
            LOGGER.warn(
                "BlockFrame runtime cleanup failed",
                error
            );
        }
        if (!dlssCleanupSucceeded) {
            current.markConfirmedFailure(
                ConfirmedRunError.BLOCKFRAME_ERROR,
                "dlss-cleanup-failed"
            );
        }
        current.tryFinalizeClientClose();
    }

    /**
     * Compatibility entry point. Without normal-lifecycle proof it closes the
     * owner but deliberately cannot mark the run clean.
     */
    public static void close() {
        clientCloseReturned(false, false);
    }

    private static ClientState state() {
        ClientState current = client;
        if (current != null) {
            return current;
        }
        synchronized (INITIALIZATION_LOCK) {
            current = client;
            if (current == null) {
                BlockframeEngine fallbackEngine =
                    new BlockframeEngine();
                current = new ClientState(
                    fallbackEngine,
                    fallbackEngine.featurePolicy(),
                    fallbackEngine.featureStates(),
                    null,
                    0L,
                    false,
                    "implicit-fallback"
                );
                client = current;
                LOGGER.warn(
                    "BlockFrame runtime was accessed before mod "
                        + "initialization; an in-memory safe fallback is used"
                );
            }
            return current;
        }
    }

    private static String stableReason(
        String value,
        String fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder bounded = new StringBuilder(64);
        for (
            int index = 0;
            index < value.length() && bounded.length() < 63;
            index++
        ) {
            char character = Character.toLowerCase(value.charAt(index));
            if (
                character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '_'
                    || character == '-'
            ) {
                bounded.append(character);
            } else if (
                bounded.length() > 0
                    && bounded.charAt(bounded.length() - 1) != '-'
            ) {
                bounded.append('-');
            }
        }
        return bounded.length() == 0 ? fallback : bounded.toString();
    }

    private static final class ClientState {
        private final BlockframeEngine engine;
        private final RuntimeFeaturePolicy featurePolicy;
        private final FeatureStateRegistry featureStates;
        private final RunStateStore runStateStore;
        private final long clientGeneration;
        private final boolean explicitInitialization;
        private final String configurationStatus;

        private volatile List<String> runStateDebugLines =
            List.of("Run state: STARTING");
        private volatile long deviceGeneration;
        private volatile boolean clientStartedObserved;
        private volatile boolean resourceLoadReady;
        private volatile boolean clientStoppingObserved;
        private volatile boolean clientStoppedObserved;
        private volatile boolean originalCloseReturnedNormally;
        private volatile boolean dlssCleanupSucceeded;
        private volatile boolean engineCleanupSucceeded;
        private volatile boolean cleanupResultAvailable;
        private volatile boolean configurationChanged;
        private volatile boolean closed;
        private GpuPassDiagnostics.Snapshot observedGpuDiagnostics;
        private DeviceFaultDiagnostics.Snapshot observedDeviceFault;
        private PhysicalMemoryTelemetry.RamStatus
            observedPhysicalRamStatus;
        private PhysicalMemoryTelemetry.DeviceStatus
            observedPhysicalDeviceStatus;
        private RunBackend backend = RunBackend.UNKNOWN;
        private Object vulkanDevice;
        private final WorldFrameStabilityTracker stability =
            new WorldFrameStabilityTracker(STABLE_WORLD_FRAME_COUNT);
        private boolean activeFeaturesPublished;

        private ClientState(
            BlockframeEngine engine,
            RuntimeFeaturePolicy featurePolicy,
            FeatureStateRegistry featureStates,
            RunStateStore runStateStore,
            long clientGeneration,
            boolean explicitInitialization,
            String configurationStatus
        ) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.featurePolicy = Objects.requireNonNull(
                featurePolicy,
                "featurePolicy"
            );
            this.featureStates = Objects.requireNonNull(
                featureStates,
                "featureStates"
            );
            this.runStateStore = runStateStore;
            this.clientGeneration = clientGeneration;
            this.explicitInitialization = explicitInitialization;
            this.configurationStatus = configurationStatus;
            this.refreshRunStateDebugLines();
        }

        private synchronized void rebaseDlssConfiguration(
            String dlssMode,
            String sharpeningMode,
            int sharpeningAmount,
            String entityHistoryBackend
        ) {
            if (this.closed) {
                return;
            }
            boolean requested =
                dlssMode != null
                    && !"off".equalsIgnoreCase(dlssMode.trim());
            long dlssMask = FeatureId.DLSS_MODE.mask();
            long requestedMask = requested
                ? this.featurePolicy.requestedMask() | dlssMask
                : this.featurePolicy.requestedMask() & ~dlssMask;
            long effectiveMask =
                this.featureStates.snapshot().effectiveMask()
                    & requestedMask;

            this.configurationChanged = true;
            this.resetStabilityWindow();
            try {
                boolean identityChanged = false;
                boolean durable = false;
                if (this.runStateStore != null) {
                    RunStateRecord previous =
                        this.runStateStore.snapshot();
                    String fingerprint =
                        FeatureConfigFingerprint.compute(
                            this.engine.config().settings(),
                            dlssMode,
                            sharpeningMode,
                            sharpeningAmount,
                            entityHistoryBackend
                        );
                    RunStateIdentity replacement =
                        new RunStateIdentity(
                            previous.modVersion(),
                            previous.minecraftVersion(),
                            fingerprint,
                            previous.featureSchemaVersion(),
                            requestedMask,
                            effectiveMask,
                            0L
                        );
                    identityChanged =
                        !previous.identityMatches(replacement);
                    if (identityChanged) {
                        durable = this.runStateStore.rebaseIdentity(
                            replacement,
                            effectiveMask
                        );
                        identityChanged =
                            this.runStateStore
                                .snapshot()
                                .identityMatches(replacement);
                    }
                }

                boolean requestChanged =
                    this.featurePolicy.updateLiveDlssMode(requested);
                if (requestChanged) {
                    boolean restartRequired =
                        this.featurePolicy.dlssRestartRequired();
                    this.publishFeature(
                        FeatureId.DLSS_MODE,
                        this.backend == RunBackend.VULKAN,
                        false,
                        requested,
                        false,
                        restartRequired
                            ? RuntimeFeaturePolicy
                                .DLSS_RESTART_REQUIRED_REASON
                            : requested
                            ? "live-request-awaiting-successful-dlss-frame"
                            : "disabled-by-configuration"
                    );
                    if (restartRequired) {
                        de.morau.nvidiadlss.DlssStatus
                            .restartRequired();
                    } else if (!requested) {
                        de.morau.nvidiadlss.DlssStatus
                            .clearRestartRequired();
                    }
                }
                if (identityChanged) {
                    LOGGER.info(
                        "BlockFrame live configuration identity rebased: "
                            + "requested=0x{} effective=0x{} durable={} "
                            + "persistence={}",
                        Long.toHexString(requestedMask),
                        Long.toHexString(effectiveMask),
                        durable,
                        this.runStateStore.persistenceStatus()
                    );
                }
            } finally {
                this.configurationChanged = false;
                this.refreshRunStateDebugLines();
            }
        }

        private void logInitialState() {
            if (this.runStateStore == null) {
                LOGGER.warn(
                    "BlockFrame run STARTING: in-memory fail-open state; "
                        + "Safe Start and LKG persistence unavailable"
                );
                return;
            }
            RunStateRecord snapshot = this.runStateStore.snapshot();
            LOGGER.info(
                "BlockFrame run STARTING: runGen={} requested=0x{} "
                    + "enabled=0x{} safeStart={} config={} persistence={} "
                    + "publication={}",
                snapshot.runGeneration(),
                Long.toHexString(this.featurePolicy.requestedMask()),
                Long.toHexString(this.featurePolicy.enabledMask()),
                snapshot.safeStart().active(),
                this.configurationStatus,
                this.runStateStore.persistenceStatus(),
                this.runStateStore.publicationMode()
            );
            RunStateRecord.PreviousRun previous = snapshot.previousRun();
            if (previous == null) {
                LOGGER.info("BlockFrame previous run: FIRST_START");
            } else if (previous.phase() == RunPhase.UNCLEAN) {
                LOGGER.warn(
                    "BlockFrame previous run: UNCLEAN_PREVIOUS_RUN "
                        + "(not a confirmed BlockFrame crash), runGen={}",
                    previous.runGeneration()
                );
            } else if (
                previous.error() == ConfirmedRunError.DEVICE_LOSS
            ) {
                LOGGER.error(
                    "BlockFrame previous run: CONFIRMED_DEVICE_LOSS "
                        + "context={} clean={}",
                    previous.errorContext(),
                    previous.cleanShutdown()
                );
            } else if (
                previous.error()
                    == ConfirmedRunError.BLOCKFRAME_ERROR
            ) {
                LOGGER.error(
                    "BlockFrame previous run: CONFIRMED_BLOCKFRAME_ERROR "
                        + "context={} clean={}",
                    previous.errorContext(),
                    previous.cleanShutdown()
                );
            } else {
                LOGGER.info(
                    "BlockFrame previous run: CLEAN_SHUTDOWN runGen={}",
                    previous.runGeneration()
                );
            }
        }

        private boolean safeStartActive() {
            return this.runStateStore != null
                && this.runStateStore.safeStartActive();
        }

        private boolean safeStartOfferAvailable() {
            return this.runStateStore != null
                && this.runStateStore.safeStartOfferAvailable();
        }

        private boolean markSafeStartOffered() {
            if (
                this.runStateStore == null
                    || !this.runStateStore.offerSafeStart()
            ) {
                return false;
            }
            LOGGER.warn(
                "BlockFrame Safe Start offered once for the persisted "
                    + "previous-run event; current process remains unchanged"
            );
            this.refreshRunStateDebugLines();
            return true;
        }

        private boolean queueSafeStartForNextRun() {
            if (
                this.runStateStore == null
                    || !this.runStateStore.queueSafeStartForNextRun()
            ) {
                return false;
            }
            LOGGER.warn(
                "BlockFrame one-shot Safe Start queued for the next "
                    + "process; normal configuration was not changed"
            );
            this.refreshRunStateDebugLines();
            return true;
        }

        private void declineSafeStart() {
            if (
                this.runStateStore != null
                    && this.runStateStore.declineSafeStart()
            ) {
                LOGGER.info(
                    "BlockFrame Safe Start declined; normal operation "
                        + "continues unchanged"
                );
                this.refreshRunStateDebugLines();
            }
        }

        private void observeDetectedBackend() {
            EngineCapabilities.Backend detected =
                this.engine.capabilities().backend();
            if (
                detected == EngineCapabilities.Backend.OPENGL
                    && this.backend != RunBackend.OPENGL
            ) {
                this.backendChanged(RunBackend.OPENGL, null);
            } else if (
                detected == EngineCapabilities.Backend.VULKAN
                    && this.backend == RunBackend.UNKNOWN
            ) {
                this.backendChanged(RunBackend.VULKAN, this.vulkanDevice);
            }
        }

        private void vulkanDeviceConnected(VulkanDevice device) {
            if (
                this.backend == RunBackend.VULKAN
                    && this.vulkanDevice == device
            ) {
                return;
            }
            this.backendChanged(RunBackend.VULKAN, device);
        }

        private void vulkanDeviceClosing(VulkanDevice device) {
            if (this.vulkanDevice != device) {
                return;
            }
            this.vulkanDevice = null;
            this.stability.clearWorld();
            if (DeveloperDiagnostics.ENABLED) {
                GpuPassDiagnostics.deviceGenerationChanged();
            }
            for (FeatureId id : FeatureId.all()) {
                if (id == FeatureId.PHYSICAL_MEMORY) {
                    this.publishPhysicalMemoryState(true);
                    continue;
                }
                FeatureState state = this.featureStates.state(id);
                boolean processFeature = isProcessFeature(id);
                this.publishFeature(
                    id,
                    processFeature,
                    processFeature && state.effective(),
                    processFeature
                        ? state.fallback()
                        : state.requested(),
                    processFeature && state.quarantined(),
                    processFeature
                        ? state.reason()
                        : "vulkan-device-closing"
                );
            }
            this.refreshRunStateDebugLines();
        }

        private void backendChanged(
            RunBackend nextBackend,
            Object deviceOwner
        ) {
            if (
                nextBackend == this.backend
                    && (
                        nextBackend != RunBackend.VULKAN
                            || this.vulkanDevice == deviceOwner
                    )
            ) {
                return;
            }
            this.backend = nextBackend;
            if (nextBackend == RunBackend.VULKAN) {
                this.vulkanDevice = deviceOwner;
            }
            this.deviceGeneration =
                incrementSaturated(this.deviceGeneration);
            this.stability.clearWorld();
            if (DeveloperDiagnostics.ENABLED) {
                GpuPassDiagnostics.deviceGenerationChanged();
            }
            this.publishBackendBaseline();

            if (this.runStateStore != null) {
                long mask = this.featureStates
                    .snapshot()
                    .effectiveMask();
                RunPhase phase =
                    this.runStateStore.snapshot().phase();
                boolean initializing = phase == RunPhase.STARTING
                    ? this.runStateStore.markInitializing(
                        nextBackend,
                        mask
                    )
                    : this.runStateStore.markDeviceReinitializing(
                        nextBackend,
                        mask
                    );
                boolean features =
                    this.runStateStore.markActiveFeaturesPublished(mask);
                LOGGER.info(
                    "BlockFrame device-generation run-state reset: "
                        + "initializing-durable={} features-durable={}",
                    initializing,
                    features
                );
            }
            this.activeFeaturesPublished = true;
            this.refreshRunStateDebugLines();
            LOGGER.info(
                "BlockFrame backend initialized: {} deviceGen={} "
                    + "active-feature-state-published",
                nextBackend,
                this.deviceGeneration
            );
        }

        private void publishBackendBaseline() {
            boolean tracySupported = DeveloperDiagnostics.ENABLED
                && GpuPassDiagnostics.snapshot().tracySupported();
            for (FeatureId id : FeatureId.all()) {
                if (id == FeatureId.PHYSICAL_MEMORY) {
                    this.publishPhysicalMemoryState(true);
                    continue;
                }
                boolean supported = switch (id) {
                    case FRAME_PROFILER -> true;
                    case PHYSICAL_MEMORY ->
                        throw new IllegalStateException(
                            "physical memory is published from cached status"
                        );
                    case TRACY_CORRELATION -> tracySupported;
                    case DLSS_MODE,
                        ENTITY_MOTION_SCRATCH,
                        ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                        TRANSFORM_SCRATCH,
                        SHADER_SETUP_POOL,
                        MATERIAL_SAMPLER_CACHE,
                        OUTLINE_POSE_REUSE,
                        GPU_BREADCRUMBS,
                        DEBUG_LABELS,
                        DEVICE_FAULT,
                        OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL ->
                        this.backend == RunBackend.VULKAN;
                };
                FeatureState old = this.featureStates.state(id);
                boolean retainEffective =
                    old.effective()
                        && old.enabled()
                        && supported;
                boolean requested = this.featurePolicy.requested(id);
                boolean fallback =
                    requested
                        && !this.featurePolicy.enabled(id);
                String reason;
                if (!requested) {
                    reason = "disabled-by-configuration";
                } else if (!this.featurePolicy.enabled(id)) {
                    reason = this.featurePolicy.disabledReason(id);
                } else if (!supported) {
                    reason = this.backend == RunBackend.OPENGL
                        ? "unsupported-opengl"
                        : "unsupported-current-backend";
                    fallback = true;
                } else if (retainEffective) {
                    reason = old.reason();
                } else {
                    reason = "awaiting-productive-consumer";
                }
                this.publishFeature(
                    id,
                    supported,
                    retainEffective,
                    fallback,
                    old.quarantined(),
                    reason
                );
            }
        }

        private void observeCachedDiagnosticStates() {
            GpuPassDiagnostics.Snapshot diagnostics =
                GpuPassDiagnostics.snapshot();
            if (diagnostics != this.observedGpuDiagnostics) {
                this.observedGpuDiagnostics = diagnostics;
                this.publishIfChanged(
                    FeatureId.DEBUG_LABELS,
                    diagnostics.debugLabelsSupported(),
                    diagnostics.debugLabelsEffective(),
                    this.featurePolicy.requested(
                        FeatureId.DEBUG_LABELS
                    ) && !diagnostics.debugLabelsEffective(),
                    false,
                    diagnostics.debugLabelsEffective()
                        ? "mojang-debug-utils-label-observed"
                        : diagnostics.debugLabelsSupported()
                            ? "debug-utils-supported-awaiting-label"
                            : "debug-utils-unavailable"
                );
                this.publishIfChanged(
                    FeatureId.TRACY_CORRELATION,
                    diagnostics.tracySupported(),
                    diagnostics.tracyEffective(),
                    this.featurePolicy.requested(
                        FeatureId.TRACY_CORRELATION
                    ) && !diagnostics.tracyEffective(),
                    false,
                    diagnostics.tracyEffective()
                        ? "mojang-tracy-zone-observed"
                        : diagnostics.tracySupported()
                            ? "tracy-supported-awaiting-zone"
                            : "tracy-unavailable"
                );
            }

            this.publishPhysicalMemoryState(false);

            DeviceFaultDiagnostics.Snapshot fault =
                this.engine.deviceFaultSnapshot();
            if (fault == this.observedDeviceFault) {
                return;
            }
            this.observedDeviceFault = fault;
            this.publishIfChanged(
                FeatureId.DEVICE_FAULT,
                fault.extensionSupported()
                    && fault.featureSupported(),
                fault.enabled() && fault.functionResolved(),
                fault.requested()
                    && (!fault.enabled() || !fault.functionResolved()),
                false,
                fault.enabled() && fault.functionResolved()
                    ? "device-fault-function-resolved"
                    : stableReason(
                        fault.unavailableReason(),
                        "device-fault-unavailable"
                    )
            );
        }

        /**
         * Projects only the already-cached telemetry status into the feature
         * registry. Counter/value-only samples deliberately do not republish
         * state or allocate diagnostic strings.
         */
        private void publishPhysicalMemoryState(boolean force) {
            if (!DeveloperDiagnostics.ENABLED) {
                this.publishIfChanged(
                    FeatureId.PHYSICAL_MEMORY,
                    false,
                    false,
                    false,
                    false,
                    "disabled-by-developer-diagnostics-gate"
                );
                return;
            }
            PhysicalMemoryTelemetry.Snapshot memory =
                this.engine.physicalMemorySnapshot();
            if (
                !force
                    && memory.ramStatus()
                        == this.observedPhysicalRamStatus
                    && memory.deviceStatus()
                        == this.observedPhysicalDeviceStatus
            ) {
                return;
            }
            this.observedPhysicalRamStatus = memory.ramStatus();
            this.observedPhysicalDeviceStatus = memory.deviceStatus();
            PhysicalMemoryFeatureAvailability availability =
                PhysicalMemoryFeatureAvailability.from(
                    memory.ramStatus(),
                    memory.deviceStatus()
                );
            this.publishIfChanged(
                FeatureId.PHYSICAL_MEMORY,
                availability.supported(),
                availability.effective(),
                availability.fallback(),
                false,
                availability.reason()
            );
        }

        private void observeBreadcrumbAfterRecord() {
            if (
                !this.featurePolicy.enabled(
                    FeatureId.GPU_BREADCRUMBS
                )
            ) {
                return;
            }
            FeatureState state =
                this.featureStates.state(FeatureId.GPU_BREADCRUMBS);
            if (state.effective() || state.fallback()) {
                return;
            }
            if (this.engine.gpuBreadcrumbsEffective()) {
                this.publishFeatureEffective(
                    FeatureId.GPU_BREADCRUMBS,
                    "fixed-native-submission-ring-active"
                );
            } else {
                this.publishFeatureFallback(
                    FeatureId.GPU_BREADCRUMBS,
                    true,
                    false,
                    "diagnostics-budget-or-allocation-fallback"
                );
            }
        }

        private void publishIfChanged(
            FeatureId id,
            boolean supported,
            boolean effective,
            boolean fallback,
            boolean quarantined,
            String reason
        ) {
            FeatureState old = this.featureStates.state(id);
            boolean requested = this.featurePolicy.requested(id);
            boolean enabled = this.featurePolicy.enabled(id);
            boolean actualEffective =
                requested && enabled && supported && effective;
            boolean actualFallback =
                requested && (fallback || !enabled || !supported);
            boolean actualQuarantined = requested && quarantined;
            String actualReason;
            if (!requested) {
                actualReason = "disabled-by-configuration";
            } else if (!enabled) {
                actualReason = this.featurePolicy.disabledReason(id);
            } else {
                actualReason = stableReason(reason, "none");
            }
            if (
                old.requested() == requested
                    && old.supported() == supported
                    && old.enabled() == enabled
                    && old.effective() == actualEffective
                    && old.fallback() == actualFallback
                    && old.quarantined() == actualQuarantined
                    && old.reason().equals(actualReason)
                    && old.clientGeneration() == this.clientGeneration
                    && old.deviceGeneration() == this.deviceGeneration
            ) {
                return;
            }
            this.publishFeature(
                id,
                supported,
                effective,
                fallback,
                quarantined,
                reason
            );
        }

        private void publishFeatureEffective(
            FeatureId id,
            String reason
        ) {
            FeatureState current = this.featureStates.state(id);
            if (current.quarantined()) {
                return;
            }
            if (
                current.effective()
                    && current.supported()
                    && !current.quarantined()
            ) {
                return;
            }
            this.publishFeature(
                id,
                true,
                true,
                current.fallback(),
                false,
                reason
            );
        }

        private void publishFeatureFallback(
            FeatureId id,
            boolean supported,
            boolean quarantined,
            String reason
        ) {
            FeatureState current = this.featureStates.state(id);
            if (
                current.fallback()
                    && current.quarantined() == quarantined
                    && current.supported() == supported
                    && current.reason().equals(reason)
            ) {
                return;
            }
            this.publishFeature(
                id,
                supported,
                quarantined ? false : current.effective(),
                true,
                quarantined || current.quarantined(),
                reason
            );
        }

        private void publishFeature(
            FeatureId id,
            boolean supported,
            boolean effective,
            boolean fallback,
            boolean quarantined,
            String reason
        ) {
            boolean requested = this.featurePolicy.requested(id);
            boolean enabled = this.featurePolicy.enabled(id);
            boolean actualEffective =
                requested && enabled && supported && effective;
            boolean actualFallback =
                requested
                    && (
                        fallback
                            || !enabled
                            || !supported
                    );
            String actualReason;
            if (!requested) {
                actualReason = "disabled-by-configuration";
            } else if (!enabled) {
                actualReason = this.featurePolicy.disabledReason(id);
            } else {
                actualReason = stableReason(reason, "none");
            }
            boolean changed = this.featureStates.update(
                id,
                requested,
                supported,
                enabled,
                actualEffective,
                actualFallback,
                requested && quarantined,
                actualReason,
                this.clientGeneration,
                this.deviceGeneration
            );
            if (!changed) {
                return;
            }
            this.refreshRunStateDebugLines();
            boolean actualQuarantined =
                requested && quarantined;
            if (actualQuarantined) {
                LOGGER.warn(
                    "BlockFrame feature {}: RUNTIME_QUARANTINE reason={}",
                    id.stableId(),
                    actualReason
                );
            } else if (actualFallback) {
                LOGGER.info(
                    "BlockFrame feature {}: FALLBACK reason={}",
                    id.stableId(),
                    actualReason
                );
            } else {
                LOGGER.info(
                    "BlockFrame feature {}: requested={} supported={} "
                        + "enabled={} effective={} reason={}",
                    id.stableId(),
                    requested,
                    supported,
                    enabled,
                    actualEffective,
                    actualReason
                );
            }
        }

        private void recordSuccessfulWorldFrame(Object currentWorld) {
            if (
                this.closed
                    || !this.resourceLoadReady
                    || !this.activeFeaturesPublished
                    || this.backend == RunBackend.UNKNOWN
                    || this.configurationChanged
            ) {
                this.resetStabilityWindow();
                return;
            }
            if (this.runStateStore != null) {
                RunStateRecord persisted =
                    this.runStateStore.snapshot();
                if (persisted.phase() == RunPhase.FAILED) {
                    return;
                }
                boolean tracksCurrentWorld =
                    this.stability.tracksWorld(currentWorld);
                if (
                    persisted.phase() == RunPhase.STABLE
                        && tracksCurrentWorld
                ) {
                    return;
                }
                boolean priorWindow =
                    persisted.phase() == RunPhase.STABLE
                        || persisted.checkpoint()
                            == RunCheckpoint.FIRST_WORLD_FRAME;
                if (!tracksCurrentWorld && priorWindow) {
                    boolean durable =
                        this.runStateStore.markStabilityRevalidating(
                            this.featureStates
                                .snapshot()
                                .effectiveMask()
                        );
                    this.refreshRunStateDebugLines();
                    LOGGER.info(
                        "BlockFrame world identity changed: "
                            + "stability revalidation durable={}",
                        durable
                    );
                }
            }
            WorldFrameStabilityTracker.Transition transition =
                this.stability.observeSuccessfulFrame(currentWorld);
            if (this.runStateStore == null) {
                if (
                    transition
                        != WorldFrameStabilityTracker.Transition.NONE
                ) {
                    this.refreshRunStateDebugLines();
                }
                return;
            }
            if (
                transition
                    == WorldFrameStabilityTracker.Transition
                        .FIRST_WORLD_FRAME
            ) {
                boolean durable =
                    this.runStateStore.markFirstWorldFrame(
                    this.featureStates.snapshot().effectiveMask()
                );
                this.refreshRunStateDebugLines();
                LOGGER.info(
                    "BlockFrame run checkpoint: FIRST_WORLD_FRAME "
                        + "durable={}",
                    durable
                );
            } else if (
                transition
                    == WorldFrameStabilityTracker.Transition
                        .STABILITY_WINDOW_COMPLETE
            ) {
                boolean durable = this.runStateStore.markStable(
                    this.featureStates.snapshot().effectiveMask()
                );
                this.refreshRunStateDebugLines();
                LOGGER.info(
                    "BlockFrame run STABLE after {} consecutive "
                        + "successful world frames; safeStart={} "
                        + "durable={} lkgPromoted={}",
                    STABLE_WORLD_FRAME_COUNT,
                    this.safeStartActive(),
                    durable,
                    durable && !this.safeStartActive()
                );
            }
        }

        private void resourceLoadFinished() {
            boolean priorWindow = false;
            if (this.runStateStore != null) {
                RunStateRecord persisted =
                    this.runStateStore.snapshot();
                priorWindow =
                    persisted.phase() == RunPhase.STABLE
                        || persisted.checkpoint()
                            == RunCheckpoint.FIRST_WORLD_FRAME;
            }
            this.resourceLoadReady = true;
            this.stability.clearWorld();
            if (
                priorWindow
                    && this.runStateStore != null
                    && this.activeFeaturesPublished
                    && this.backend != RunBackend.UNKNOWN
            ) {
                boolean durable =
                    this.runStateStore.markStabilityRevalidating(
                        this.featureStates.snapshot().effectiveMask()
                    );
                LOGGER.info(
                    "BlockFrame resource lifecycle changed: "
                        + "stability revalidation durable={}",
                    durable
                );
            }
            this.refreshRunStateDebugLines();
        }

        private void worldUnavailable() {
            boolean priorWindow = false;
            boolean hadWorld = this.stability.hasWorld();
            if (this.runStateStore != null) {
                RunStateRecord persisted =
                    this.runStateStore.snapshot();
                priorWindow =
                    persisted.phase() == RunPhase.STABLE
                        || persisted.checkpoint()
                            == RunCheckpoint.FIRST_WORLD_FRAME;
            }
            if (
                priorWindow
                    && this.runStateStore != null
                    && this.activeFeaturesPublished
                    && this.backend != RunBackend.UNKNOWN
            ) {
                boolean durable =
                    this.runStateStore.markStabilityRevalidating(
                        this.featureStates.snapshot().effectiveMask()
                    );
                LOGGER.info(
                    "BlockFrame world unavailable: "
                        + "stability revalidation durable={}",
                    durable
                );
            }
            this.stability.clearWorld();
            if (priorWindow || hadWorld) {
                this.refreshRunStateDebugLines();
            }
        }

        private void recordFailedWorldFrame() {
            boolean priorWindow = false;
            boolean hadFrames =
                this.stability.consecutiveFrames() != 0;
            if (this.runStateStore != null) {
                RunStateRecord persisted =
                    this.runStateStore.snapshot();
                priorWindow =
                    persisted.phase() == RunPhase.STABLE
                        || persisted.checkpoint()
                            == RunCheckpoint.FIRST_WORLD_FRAME;
            }
            if (
                priorWindow
                    && this.runStateStore != null
                    && this.activeFeaturesPublished
                    && this.backend != RunBackend.UNKNOWN
            ) {
                boolean durable =
                    this.runStateStore.markStabilityRevalidating(
                        this.featureStates.snapshot().effectiveMask()
                    );
                LOGGER.info(
                    "BlockFrame failed world frame: "
                        + "stability revalidation durable={}",
                    durable
                );
            }
            this.resetStabilityWindow();
            if (priorWindow || hadFrames) {
                this.refreshRunStateDebugLines();
            }
        }

        private void resetStabilityWindow() {
            this.stability.resetWindow();
        }

        private void markConfirmedFailure(
            ConfirmedRunError error,
            String stableContextCode
        ) {
            if (this.runStateStore == null || this.closed) {
                return;
            }
            String context = stableReason(
                stableContextCode,
                "blockframe-error"
            );
            boolean durable =
                this.runStateStore.markFailed(
                    error,
                    context,
                    this.featureStates.snapshot().effectiveMask()
                );
            this.resetStabilityWindow();
            this.refreshRunStateDebugLines();
            if (error == ConfirmedRunError.DEVICE_LOSS) {
                LOGGER.error(
                    "BlockFrame run FAILED: CONFIRMED_DEVICE_LOSS "
                        + "context={} durable={}",
                    context,
                    durable
                );
            } else {
                LOGGER.error(
                    "BlockFrame run FAILED: CONFIRMED_BLOCKFRAME_ERROR "
                        + "context={} durable={}",
                    context,
                    durable
                );
            }
        }

        private void tryFinalizeClientClose() {
            if (
                this.closed
                    || !this.cleanupResultAvailable
            ) {
                return;
            }
            boolean cleanupSucceeded =
                this.dlssCleanupSucceeded
                    && this.engineCleanupSucceeded;
            boolean cleanProof =
                this.clientStoppingObserved
                    && this.clientStoppedObserved
                    && this.originalCloseReturnedNormally
                    && cleanupSucceeded;
            boolean proofCanStillArrive =
                this.originalCloseReturnedNormally
                    && cleanupSucceeded
                    && !this.clientStoppedObserved;
            if (!cleanProof && proofCanStillArrive) {
                return;
            }

            if (this.runStateStore != null) {
                if (cleanProof) {
                    RunStateRecord persisted =
                        this.runStateStore.snapshot();
                    long effectiveMask =
                        persisted.phase() == RunPhase.FAILED
                            ? persisted.effectiveFeatureMask()
                            : this.featureStates
                                .snapshot()
                                .effectiveMask();
                    boolean durable =
                        this.runStateStore.markCleanShutdown(
                            effectiveMask
                        );
                    LOGGER.info(
                        "BlockFrame run shutdown: CLEAN_SHUTDOWN "
                            + "(stopping+stopped+close-return+cleanup) "
                            + "durable={}",
                        durable
                    );
                } else {
                    LOGGER.warn(
                        "BlockFrame run shutdown remains UNCLEAN/FAILED: "
                            + "stopping={} stopped={} closeReturn={} "
                            + "dlssCleanup={} engineCleanup={}",
                        this.clientStoppingObserved,
                        this.clientStoppedObserved,
                        this.originalCloseReturnedNormally,
                        this.dlssCleanupSucceeded,
                        this.engineCleanupSucceeded
                    );
                }
                this.runStateStore.close();
            }
            this.closed = true;
            this.refreshRunStateDebugLines();
        }

        private void refreshRunStateDebugLines() {
            if (this.runStateStore == null) {
                this.runStateDebugLines = List.of(
                    "Run state: in-memory fallback (persistence unavailable)",
                    "Safe Start: inactive",
                    "Stability (last state publication): "
                        + this.stability.consecutiveFrames()
                        + "/"
                        + STABLE_WORLD_FRAME_COUNT
                );
                return;
            }
            RunStateRecord snapshot = this.runStateStore.snapshot();
            RunStateRecord.SafeStartState safe = snapshot.safeStart();
            this.runStateDebugLines = List.of(
                "Run state: phase="
                    + snapshot.phase()
                    + " checkpoint="
                    + snapshot.checkpoint()
                    + " clean="
                    + snapshot.cleanShutdown()
                    + " backend="
                    + snapshot.backend(),
                "Run identity: runGen="
                    + snapshot.runGeneration()
                    + " commitGen="
                    + snapshot.commitGeneration()
                    + " clientGen="
                    + this.clientGeneration
                    + " deviceGen="
                    + this.deviceGeneration,
                "Run features: requested=0x"
                    + Long.toHexString(snapshot.requestedFeatureMask())
                    + " effective=0x"
                    + Long.toHexString(snapshot.effectiveFeatureMask())
                    + " current=0x"
                    + Long.toHexString(
                        this.featureStates.snapshot().effectiveMask()
                    ),
                "Run persistence: "
                    + this.runStateStore.persistenceStatus()
                    + " publication="
                    + this.runStateStore.publicationMode()
                    + " files=2x64KiB-max",
                "Safe Start: active="
                    + safe.active()
                    + " offer="
                    + this.runStateStore.safeStartOfferAvailable()
                    + " queued="
                    + safe.hasQueuedOneShot()
                    + " config-mutated=false",
                "Stability (last state publication): worldFrames="
                    + this.stability.consecutiveFrames()
                    + "/"
                    + STABLE_WORLD_FRAME_COUNT
                    + " resources="
                    + this.resourceLoadReady
                    + " features="
                    + this.activeFeaturesPublished
                    + " configChanged="
                    + this.configurationChanged
            );
        }

        private static boolean isProcessFeature(FeatureId id) {
            return switch (id) {
                case ENTITY_MOTION_SCRATCH,
                    ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                    TRANSFORM_SCRATCH,
                    OUTLINE_POSE_REUSE,
                    FRAME_PROFILER,
                    PHYSICAL_MEMORY,
                    TRACY_CORRELATION -> true;
                default -> false;
            };
        }

        private static long incrementSaturated(long value) {
            return value == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : value + 1L;
        }
    }
}
