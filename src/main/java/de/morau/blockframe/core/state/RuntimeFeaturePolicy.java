package de.morau.blockframe.core.state;

import de.morau.blockframe.core.EngineConfig;
import de.morau.nvidiadlss.DeveloperDiagnostics;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable per-process feature request and Safe-Start override policy.
 *
 * <p>This policy never writes either user configuration file. A Safe-Start
 * override affects only the current process and always selects an already
 * existing fallback at the productive owner.</p>
 */
public final class RuntimeFeaturePolicy {
    public static final String DLSS_RESTART_REQUIRED_REASON =
        "restart-required";

    private final EngineConfig.Settings engine;
    private final String dlssMode;
    private final String entityHistoryBackend;
    private final boolean safeStart;
    private final boolean developerDiagnostics;
    private final boolean streamlineBootstrapAllowed;
    private volatile long requestedMask;
    private volatile long enabledMask;

    public RuntimeFeaturePolicy(
        EngineConfig.Settings engine,
        String dlssMode,
        String entityHistoryBackend,
        boolean safeStart
    ) {
        this(
            engine,
            dlssMode,
            entityHistoryBackend,
            safeStart,
            DeveloperDiagnostics.enabled()
        );
    }

    public RuntimeFeaturePolicy(
        EngineConfig.Settings engine,
        String dlssMode,
        String entityHistoryBackend,
        boolean safeStart,
        boolean developerDiagnostics
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.dlssMode = normalize(dlssMode, "off");
        this.entityHistoryBackend = normalize(
            entityHistoryBackend,
            "heap"
        );
        this.safeStart = safeStart;
        this.developerDiagnostics = developerDiagnostics;
        this.streamlineBootstrapAllowed =
            !safeStart && !"off".equals(this.dlssMode);

        long requested = 0L;
        long enabled = 0L;
        for (FeatureId id : FeatureId.all()) {
            if (this.requestedByConfiguration(id)) {
                requested |= id.mask();
            }
            if (this.enabledByPolicy(id)) {
                enabled |= id.mask();
            }
        }
        this.requestedMask = requested;
        this.enabledMask = enabled;
    }

    public boolean safeStart() {
        return this.safeStart;
    }

    /**
     * Immutable process-start decision. A process that started with DLSS off
     * or in Safe Start can never acquire Streamline later.
     */
    public boolean streamlineBootstrapAllowed() {
        return this.streamlineBootstrapAllowed;
    }

    public boolean dlssRestartRequired() {
        return !this.safeStart
            && !this.streamlineBootstrapAllowed
            && this.requested(FeatureId.DLSS_MODE);
    }

    public String disabledReason(FeatureId id) {
        Objects.requireNonNull(id, "id");
        if (this.safeStart) {
            return "safe-start-one-shot";
        }
        if (
            id == FeatureId.DLSS_MODE
                && this.dlssRestartRequired()
        ) {
            return DLSS_RESTART_REQUIRED_REASON;
        }
        return "disabled-by-process-policy";
    }

    public boolean requested(FeatureId id) {
        return (this.requestedMask & Objects.requireNonNull(id, "id").mask())
            != 0L;
    }

    public boolean enabled(FeatureId id) {
        return (this.enabledMask & Objects.requireNonNull(id, "id").mask())
            != 0L;
    }

    public long requestedMask() {
        return this.requestedMask;
    }

    public long enabledMask() {
        return this.enabledMask;
    }

    /**
     * Updates only the already-existing live DLSS mode request. Every other
     * Phase-1A.12 feature remains process-bound.
     */
    public synchronized boolean updateLiveDlssMode(boolean requested) {
        long bit = FeatureId.DLSS_MODE.mask();
        long nextRequested = requested
            ? this.requestedMask | bit
            : this.requestedMask & ~bit;
        long nextEnabled =
            requested && this.streamlineBootstrapAllowed
            ? this.enabledMask | bit
            : this.enabledMask & ~bit;
        if (
            nextRequested == this.requestedMask
                && nextEnabled == this.enabledMask
        ) {
            return false;
        }
        this.requestedMask = nextRequested;
        this.enabledMask = nextEnabled;
        return true;
    }

    public void publishInitial(
        FeatureStateRegistry registry,
        long clientGeneration
    ) {
        Objects.requireNonNull(registry, "registry");
        for (FeatureId id : FeatureId.all()) {
            boolean requested = this.requested(id);
            boolean enabled = this.enabled(id);
            boolean javaSupported = switch (id) {
                case ENTITY_MOTION_SCRATCH,
                    ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                    TRANSFORM_SCRATCH,
                    OUTLINE_POSE_REUSE,
                    FRAME_PROFILER -> true;
                default -> false;
            };
            String reason;
            if (requested && !enabled) {
                reason = this.disabledReason(id);
            } else if (!requested) {
                reason = "disabled-by-configuration";
            } else if (javaSupported) {
                reason = "awaiting-productive-consumer";
            } else {
                reason = "awaiting-backend";
            }
            registry.update(
                id,
                requested,
                javaSupported,
                enabled,
                false,
                requested && !enabled,
                false,
                reason,
                clientGeneration,
                0L
            );
        }
    }

    private boolean enabledByPolicy(FeatureId id) {
        if (this.safeStart) {
            return false;
        }
        if (!this.requestedByConfiguration(id)) {
            return false;
        }
        return switch (id) {
            case SHADER_SETUP_POOL,
                FRAME_PROFILER,
                GPU_BREADCRUMBS ->
                this.engine.engineEnabled();
            default -> true;
        };
    }

    private boolean requestedByConfiguration(FeatureId id) {
        return switch (id) {
            case DLSS_MODE -> !"off".equals(this.dlssMode);
            case ENTITY_MOTION_SCRATCH ->
                this.engine.entityMotionScratchEnabled();
            case ENTITY_HISTORY_NATIVE_EXPERIMENTAL ->
                "native-experimental".equals(this.entityHistoryBackend);
            case TRANSFORM_SCRATCH ->
                this.engine.transformScratchEnabled();
            case SHADER_SETUP_POOL ->
                this.engine.shaderSetupPoolEnabled();
            case MATERIAL_SAMPLER_CACHE ->
                this.engine.materialSamplerCacheEnabled();
            case OUTLINE_POSE_REUSE ->
                this.engine.outlinePoseReuseEnabled();
            case FRAME_PROFILER ->
                this.developerDiagnostics
                    && this.engine.profilerEnabled();
            case GPU_BREADCRUMBS ->
                this.developerDiagnostics
                    && this.engine.gpuBreadcrumbsEnabled();
            case PHYSICAL_MEMORY ->
                this.developerDiagnostics
                    && this.engine.physicalMemoryTelemetryEnabled();
            case DEBUG_LABELS ->
                this.developerDiagnostics
                    && this.engine.debugLabelsEnabled();
            case TRACY_CORRELATION ->
                this.developerDiagnostics
                    && this.engine.tracyCorrelationEnabled();
            case DEVICE_FAULT ->
                this.developerDiagnostics
                    && this.engine.deviceFaultEnabled();
            case OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL ->
                false;
        };
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
