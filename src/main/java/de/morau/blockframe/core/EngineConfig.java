package de.morau.blockframe.core;

import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Configuration for the renderer-independent BlockFrame Engine paths.
 *
 * <p>Constructing or loading this class never creates a configuration file.
 * Persistence happens only when {@link #save()} or {@link #save(Settings)} is
 * called explicitly.
 */
public final class EngineConfig {
    public static final Path DEFAULT_PATH = Path.of("config", "blockframe-engine.properties");
    public static final String ENGINE_ENABLED_KEY = "engine.enabled";
    public static final String FRAME_RESOURCES_ENABLED_KEY = "frameResources.enabled";
    public static final String PROFILER_ENABLED_KEY = "profiler.enabled";
    public static final String GPU_BREADCRUMBS_ENABLED_KEY =
        "diagnostics.gpuBreadcrumbsEnabled";
    public static final String DEVICE_FAULT_ENABLED_KEY =
        "diagnostics.deviceFaultEnabled";
    public static final String ENTITY_MOTION_SCRATCH_ENABLED_KEY =
        "render.entityMotionScratchEnabled";
    public static final String TRANSFORM_SCRATCH_ENABLED_KEY =
        "render.transformScratchEnabled";
    public static final String SHADER_SETUP_POOL_ENABLED_KEY =
        "vulkan.shaderSetupPoolEnabled";
    public static final String MATERIAL_SAMPLER_CACHE_ENABLED_KEY =
        "vulkan.materialSamplerCacheEnabled";
    public static final String OUTLINE_POSE_REUSE_ENABLED_KEY =
        "render.outlinePoseReuseEnabled";
    public static final String PHYSICAL_MEMORY_TELEMETRY_ENABLED_KEY =
        "diagnostics.physicalMemoryTelemetryEnabled";
    public static final String DEBUG_LABELS_ENABLED_KEY =
        "diagnostics.debugLabelsEnabled";
    public static final String TRACY_CORRELATION_ENABLED_KEY =
        "diagnostics.tracyCorrelationEnabled";
    public static final String OPAQUE_SOLID_GPU_SCENE_INDIRECT_ENABLED_KEY =
        "experimental.opaqueSolidGpuSceneIndirectV1Enabled";
    public static final String TERRAIN_BACKEND_KEY = "terrainBackend";
    public static final String TERRAIN_BACKEND_MOJANG = "mojang";
    public static final String TERRAIN_BACKEND_NATIVE_EXPERIMENTAL =
        "native-experimental";
    public static final String CACHE_MAX_BYTES_KEY = "cache.maxBytes";
    public static final long DEFAULT_CACHE_MAX_BYTES = 256L * 1024L * 1024L;

    private final Path path;
    private volatile Settings settings = Settings.defaults();

    public EngineConfig() {
        this(DEFAULT_PATH);
    }

    public EngineConfig(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() {
        return this.path;
    }

    public Settings settings() {
        return this.settings;
    }

    /**
     * Loads the configured file, or restores safe enabled defaults when it does
     * not exist. A missing file is deliberately not created.
     */
    public synchronized Settings load() throws IOException {
        if (!Files.isRegularFile(this.path)) {
            this.settings = Settings.defaults();
            return this.settings;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(this.path)) {
            properties.load(input);
        }
        this.settings = Settings.from(properties);
        return this.settings;
    }

    public synchronized Settings reload() throws IOException {
        return this.load();
    }

    public synchronized void setSettings(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public synchronized void save() throws IOException {
        this.write(this.settings);
    }

    public synchronized void save(Settings settings) throws IOException {
        Settings replacement = Objects.requireNonNull(settings, "settings");
        this.write(replacement);
        this.settings = replacement;
    }

    private void write(Settings value) throws IOException {
        Path target = this.path.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);

        Properties properties = value.toProperties();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        boolean moved = false;
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "BlockFrame Engine");
            }

            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Immutable, independently testable view of all engine switches. */
    public record Settings(
        boolean engineEnabled,
        boolean frameResourcesEnabled,
        boolean profilerEnabled,
        long cacheMaxBytes,
        MemoryBudgetSettings memoryBudgets,
        boolean gpuBreadcrumbsEnabled,
        boolean deviceFaultEnabled,
        boolean entityMotionScratchEnabled,
        boolean transformScratchEnabled,
        boolean shaderSetupPoolEnabled,
        boolean materialSamplerCacheEnabled,
        boolean outlinePoseReuseEnabled,
        boolean physicalMemoryTelemetryEnabled,
        boolean debugLabelsEnabled,
        boolean tracyCorrelationEnabled,
        boolean opaqueSolidGpuSceneIndirectExperimentalEnabled,
        String terrainBackend
    ) {
        public Settings(
            boolean engineEnabled,
            boolean frameResourcesEnabled,
            boolean profilerEnabled,
            long cacheMaxBytes
        ) {
            this(
                engineEnabled,
                frameResourcesEnabled,
                profilerEnabled,
                cacheMaxBytes,
                MemoryBudgetSettings.defaults()
            );
        }

        public Settings(
            boolean engineEnabled,
            boolean frameResourcesEnabled,
            boolean profilerEnabled,
            long cacheMaxBytes,
            MemoryBudgetSettings memoryBudgets
        ) {
            this(
                engineEnabled,
                frameResourcesEnabled,
                profilerEnabled,
                cacheMaxBytes,
                memoryBudgets,
                true
            );
        }

        public Settings(
            boolean engineEnabled,
            boolean frameResourcesEnabled,
            boolean profilerEnabled,
            long cacheMaxBytes,
            MemoryBudgetSettings memoryBudgets,
            boolean gpuBreadcrumbsEnabled
        ) {
            this(
                engineEnabled,
                frameResourcesEnabled,
                profilerEnabled,
                cacheMaxBytes,
                memoryBudgets,
                gpuBreadcrumbsEnabled,
                true
            );
        }

        public Settings(
            boolean engineEnabled,
            boolean frameResourcesEnabled,
            boolean profilerEnabled,
            long cacheMaxBytes,
            MemoryBudgetSettings memoryBudgets,
            boolean gpuBreadcrumbsEnabled,
            boolean deviceFaultEnabled
        ) {
            this(
                engineEnabled,
                frameResourcesEnabled,
                profilerEnabled,
                cacheMaxBytes,
                memoryBudgets,
                gpuBreadcrumbsEnabled,
                deviceFaultEnabled,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                TERRAIN_BACKEND_MOJANG
            );
        }

        /**
         * Source-compatible constructor for the pre-native-backend settings
         * shape. Existing callers remain on the Mojang reference backend.
         */
        public Settings(
            boolean engineEnabled,
            boolean frameResourcesEnabled,
            boolean profilerEnabled,
            long cacheMaxBytes,
            MemoryBudgetSettings memoryBudgets,
            boolean gpuBreadcrumbsEnabled,
            boolean deviceFaultEnabled,
            boolean entityMotionScratchEnabled,
            boolean transformScratchEnabled,
            boolean shaderSetupPoolEnabled,
            boolean materialSamplerCacheEnabled,
            boolean outlinePoseReuseEnabled,
            boolean physicalMemoryTelemetryEnabled,
            boolean debugLabelsEnabled,
            boolean tracyCorrelationEnabled,
            boolean opaqueSolidGpuSceneIndirectExperimentalEnabled
        ) {
            this(
                engineEnabled,
                frameResourcesEnabled,
                profilerEnabled,
                cacheMaxBytes,
                memoryBudgets,
                gpuBreadcrumbsEnabled,
                deviceFaultEnabled,
                entityMotionScratchEnabled,
                transformScratchEnabled,
                shaderSetupPoolEnabled,
                materialSamplerCacheEnabled,
                outlinePoseReuseEnabled,
                physicalMemoryTelemetryEnabled,
                debugLabelsEnabled,
                tracyCorrelationEnabled,
                opaqueSolidGpuSceneIndirectExperimentalEnabled,
                TERRAIN_BACKEND_MOJANG
            );
        }

        public Settings {
            memoryBudgets = Objects.requireNonNull(
                memoryBudgets,
                "memoryBudgets"
            );
            terrainBackend = normalizeTerrainBackend(terrainBackend);
        }

        public static Settings defaults() {
            return new Settings(
                true,
                true,
                true,
                DEFAULT_CACHE_MAX_BYTES,
                MemoryBudgetSettings.defaults(),
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                TERRAIN_BACKEND_MOJANG
            );
        }

        public static Settings from(Properties properties) {
            Objects.requireNonNull(properties, "properties");
            return new Settings(
                parseBoolean(properties.getProperty(ENGINE_ENABLED_KEY)),
                parseBoolean(properties.getProperty(FRAME_RESOURCES_ENABLED_KEY)),
                parseBoolean(properties.getProperty(PROFILER_ENABLED_KEY)),
                parsePositiveLong(
                    properties.getProperty(CACHE_MAX_BYTES_KEY),
                    DEFAULT_CACHE_MAX_BYTES
                ),
                MemoryBudgetSettings.from(properties),
                parseBoolean(
                    properties.getProperty(
                        GPU_BREADCRUMBS_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        DEVICE_FAULT_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        ENTITY_MOTION_SCRATCH_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        TRANSFORM_SCRATCH_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        SHADER_SETUP_POOL_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        MATERIAL_SAMPLER_CACHE_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        OUTLINE_POSE_REUSE_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        PHYSICAL_MEMORY_TELEMETRY_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        DEBUG_LABELS_ENABLED_KEY
                    )
                ),
                parseBoolean(
                    properties.getProperty(
                        TRACY_CORRELATION_ENABLED_KEY
                    )
                ),
                parseBooleanDefaultFalse(
                    properties.getProperty(
                        OPAQUE_SOLID_GPU_SCENE_INDIRECT_ENABLED_KEY
                    )
                ),
                normalizeTerrainBackend(
                    properties.getProperty(TERRAIN_BACKEND_KEY)
                )
            );
        }

        public Properties toProperties() {
            Properties properties = new Properties();
            properties.setProperty(ENGINE_ENABLED_KEY, Boolean.toString(this.engineEnabled));
            properties.setProperty(FRAME_RESOURCES_ENABLED_KEY, Boolean.toString(this.frameResourcesEnabled));
            properties.setProperty(PROFILER_ENABLED_KEY, Boolean.toString(this.profilerEnabled));
            properties.setProperty(
                GPU_BREADCRUMBS_ENABLED_KEY,
                Boolean.toString(this.gpuBreadcrumbsEnabled)
            );
            properties.setProperty(
                DEVICE_FAULT_ENABLED_KEY,
                Boolean.toString(this.deviceFaultEnabled)
            );
            properties.setProperty(
                ENTITY_MOTION_SCRATCH_ENABLED_KEY,
                Boolean.toString(this.entityMotionScratchEnabled)
            );
            properties.setProperty(
                TRANSFORM_SCRATCH_ENABLED_KEY,
                Boolean.toString(this.transformScratchEnabled)
            );
            properties.setProperty(
                SHADER_SETUP_POOL_ENABLED_KEY,
                Boolean.toString(this.shaderSetupPoolEnabled)
            );
            properties.setProperty(
                MATERIAL_SAMPLER_CACHE_ENABLED_KEY,
                Boolean.toString(this.materialSamplerCacheEnabled)
            );
            properties.setProperty(
                OUTLINE_POSE_REUSE_ENABLED_KEY,
                Boolean.toString(this.outlinePoseReuseEnabled)
            );
            properties.setProperty(
                PHYSICAL_MEMORY_TELEMETRY_ENABLED_KEY,
                Boolean.toString(this.physicalMemoryTelemetryEnabled)
            );
            properties.setProperty(
                DEBUG_LABELS_ENABLED_KEY,
                Boolean.toString(this.debugLabelsEnabled)
            );
            properties.setProperty(
                TRACY_CORRELATION_ENABLED_KEY,
                Boolean.toString(this.tracyCorrelationEnabled)
            );
            properties.setProperty(
                OPAQUE_SOLID_GPU_SCENE_INDIRECT_ENABLED_KEY,
                Boolean.toString(
                    this.opaqueSolidGpuSceneIndirectExperimentalEnabled
                )
            );
            properties.setProperty(
                TERRAIN_BACKEND_KEY,
                this.terrainBackend
            );
            properties.setProperty(CACHE_MAX_BYTES_KEY, Long.toString(this.cacheMaxBytes));
            this.memoryBudgets.writeTo(properties);
            return properties;
        }

        private static boolean parseBoolean(String value) {
            if (value == null) {
                return true;
            }

            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> true;
            };
        }

        private static boolean parseBooleanDefaultFalse(String value) {
            if (value == null) {
                return false;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> false;
            };
        }

        private static String normalizeTerrainBackend(String value) {
            if (value == null) {
                return TERRAIN_BACKEND_MOJANG;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case TERRAIN_BACKEND_NATIVE_EXPERIMENTAL ->
                    TERRAIN_BACKEND_NATIVE_EXPERIMENTAL;
                case TERRAIN_BACKEND_MOJANG -> TERRAIN_BACKEND_MOJANG;
                default -> TERRAIN_BACKEND_MOJANG;
            };
        }

        private static long parsePositiveLong(String value, long fallback) {
            if (value == null) {
                return fallback;
            }

            try {
                long parsed = Long.parseLong(value.trim());
                return parsed > 0L ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
