package de.morau.blockframe.core.state;

import java.util.List;
import java.util.Objects;

/**
 * Fixed inventory of optional BlockFrame features that exist in production.
 *
 * <p>The explicit bit indices and stable IDs are persistence contracts. They
 * must not be derived from {@link #ordinal()} or reordered when a later schema
 * is introduced.
 */
public enum FeatureId {
    DLSS_MODE(
        0,
        "render.dlss_mode",
        ConfigSource.DLSS_PROPERTIES,
        "mode",
        ReloadRequirement.NONE
    ),
    ENTITY_MOTION_SCRATCH(
        1,
        "render.entity_motion_scratch",
        ConfigSource.ENGINE_PROPERTIES,
        "render.entityMotionScratchEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    ENTITY_HISTORY_NATIVE_EXPERIMENTAL(
        2,
        "render.entity_history_native_experimental",
        ConfigSource.DLSS_PROPERTIES,
        "entityHistoryBackend",
        ReloadRequirement.PROCESS_RESTART
    ),
    TRANSFORM_SCRATCH(
        3,
        "render.transform_scratch",
        ConfigSource.ENGINE_PROPERTIES,
        "render.transformScratchEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    SHADER_SETUP_POOL(
        4,
        "vulkan.shader_setup_pool",
        ConfigSource.ENGINE_PROPERTIES,
        "vulkan.shaderSetupPoolEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    MATERIAL_SAMPLER_CACHE(
        5,
        "vulkan.material_sampler_cache",
        ConfigSource.ENGINE_PROPERTIES,
        "vulkan.materialSamplerCacheEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    OUTLINE_POSE_REUSE(
        6,
        "render.outline_pose_reuse",
        ConfigSource.ENGINE_PROPERTIES,
        "render.outlinePoseReuseEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    FRAME_PROFILER(
        7,
        "diagnostics.frame_profiler",
        ConfigSource.ENGINE_PROPERTIES,
        "profiler.enabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    GPU_BREADCRUMBS(
        8,
        "diagnostics.gpu_breadcrumbs",
        ConfigSource.ENGINE_PROPERTIES,
        "diagnostics.gpuBreadcrumbsEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    PHYSICAL_MEMORY(
        9,
        "diagnostics.physical_memory",
        ConfigSource.ENGINE_PROPERTIES,
        "diagnostics.physicalMemoryTelemetryEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    DEBUG_LABELS(
        10,
        "diagnostics.debug_labels",
        ConfigSource.ENGINE_PROPERTIES,
        "diagnostics.debugLabelsEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    TRACY_CORRELATION(
        11,
        "diagnostics.tracy_correlation",
        ConfigSource.ENGINE_PROPERTIES,
        "diagnostics.tracyCorrelationEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    DEVICE_FAULT(
        12,
        "diagnostics.device_fault",
        ConfigSource.ENGINE_PROPERTIES,
        "diagnostics.deviceFaultEnabled",
        ReloadRequirement.PROCESS_RESTART
    ),
    OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL(
        13,
        "vulkan.opaque_solid_gpu_scene_indirect_experimental",
        ConfigSource.ENGINE_PROPERTIES,
        "experimental.opaqueSolidGpuSceneIndirectV1Enabled",
        ReloadRequirement.PROCESS_RESTART
    );

    public static final int COUNT = 14;
    public static final long ALL_MASK = (1L << COUNT) - 1L;

    private static final FeatureId[] BY_BIT_INDEX = new FeatureId[COUNT];
    private static final List<FeatureId> ALL;

    static {
        FeatureId[] values = values();
        if (values.length != COUNT) {
            throw new ExceptionInInitializerError(
                "Feature inventory must contain exactly " + COUNT + " entries"
            );
        }

        for (FeatureId feature : values) {
            int index = feature.bitIndex;
            if (index < 0 || index >= COUNT || BY_BIT_INDEX[index] != null) {
                throw new ExceptionInInitializerError(
                    "Invalid or duplicate feature bit index " + index
                );
            }
            BY_BIT_INDEX[index] = feature;
        }
        for (int index = 0; index < COUNT; index++) {
            if (BY_BIT_INDEX[index] == null) {
                throw new ExceptionInInitializerError(
                    "Missing feature bit index " + index
                );
            }
        }
        ALL = List.of(BY_BIT_INDEX);
    }

    private final int bitIndex;
    private final String stableId;
    private final ConfigSource configSource;
    private final String configKey;
    private final ReloadRequirement reloadRequirement;

    FeatureId(
        int bitIndex,
        String stableId,
        ConfigSource configSource,
        String configKey,
        ReloadRequirement reloadRequirement
    ) {
        this.bitIndex = bitIndex;
        this.stableId = stableId;
        this.configSource = configSource;
        this.configKey = configKey;
        this.reloadRequirement = reloadRequirement;
    }

    public int bitIndex() {
        return this.bitIndex;
    }

    public long mask() {
        return 1L << this.bitIndex;
    }

    public String stableId() {
        return this.stableId;
    }

    public ConfigSource configSource() {
        return this.configSource;
    }

    public String configKey() {
        return this.configKey;
    }

    public ReloadRequirement reloadRequirement() {
        return this.reloadRequirement;
    }

    /** Returns the inventory in stable bit-index order without cloning it. */
    public static List<FeatureId> all() {
        return ALL;
    }

    public static FeatureId fromBitIndex(int bitIndex) {
        if (bitIndex < 0 || bitIndex >= COUNT) {
            throw new IllegalArgumentException(
                "Feature bit index out of range: " + bitIndex
            );
        }
        return BY_BIT_INDEX[bitIndex];
    }

    public static FeatureId byStableId(String stableId) {
        Objects.requireNonNull(stableId, "stableId");
        for (FeatureId feature : BY_BIT_INDEX) {
            if (feature.stableId.equals(stableId)) {
                return feature;
            }
        }
        throw new IllegalArgumentException("Unknown feature ID: " + stableId);
    }

    /** Configuration files that own the persisted user request. */
    public enum ConfigSource {
        DLSS_PROPERTIES("config/voxellift.properties"),
        ENGINE_PROPERTIES("config/blockframe-engine.properties");

        private final String relativePath;

        ConfigSource(String relativePath) {
            this.relativePath = relativePath;
        }

        public String relativePath() {
            return this.relativePath;
        }
    }

    /** Earliest safe boundary at which a changed request can take effect. */
    public enum ReloadRequirement {
        NONE(false, false),
        CONFIG_RELOAD(true, false),
        RENDERER_RELOAD(true, false),
        DEVICE_RECREATION(false, true),
        CLIENT_RESTART(false, true),
        PROCESS_RESTART(false, true);

        private final boolean reload;
        private final boolean restart;

        ReloadRequirement(boolean reload, boolean restart) {
            this.reload = reload;
            this.restart = restart;
        }

        public boolean requiresReload() {
            return this.reload;
        }

        public boolean requiresRestart() {
            return this.restart;
        }
    }
}
