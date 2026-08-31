package de.morau.blockframe.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void settingsUseEnabledDefaultsForMissingOrMalformedValues() {
        Properties properties = new Properties();
        properties.setProperty(EngineConfig.ENGINE_ENABLED_KEY, "false");
        properties.setProperty(EngineConfig.FRAME_RESOURCES_ENABLED_KEY, " not-a-boolean ");
        properties.setProperty(EngineConfig.PROFILER_ENABLED_KEY, " TRUE ");
        properties.setProperty(
            EngineConfig.GPU_BREADCRUMBS_ENABLED_KEY,
            " not-a-boolean "
        );
        properties.setProperty(
            EngineConfig.DEVICE_FAULT_ENABLED_KEY,
            " not-a-boolean "
        );
        for (String key : new String[] {
            EngineConfig.ENTITY_MOTION_SCRATCH_ENABLED_KEY,
            EngineConfig.TRANSFORM_SCRATCH_ENABLED_KEY,
            EngineConfig.SHADER_SETUP_POOL_ENABLED_KEY,
            EngineConfig.MATERIAL_SAMPLER_CACHE_ENABLED_KEY,
            EngineConfig.OUTLINE_POSE_REUSE_ENABLED_KEY,
            EngineConfig.PHYSICAL_MEMORY_TELEMETRY_ENABLED_KEY,
            EngineConfig.DEBUG_LABELS_ENABLED_KEY,
            EngineConfig.TRACY_CORRELATION_ENABLED_KEY
        }) {
            properties.setProperty(key, " not-a-boolean ");
        }

        EngineConfig.Settings settings = EngineConfig.Settings.from(properties);

        assertFalse(settings.engineEnabled());
        assertTrue(settings.frameResourcesEnabled());
        assertTrue(settings.profilerEnabled());
        assertTrue(settings.gpuBreadcrumbsEnabled());
        assertTrue(settings.deviceFaultEnabled());
        assertEquals(
            EngineConfig.TERRAIN_BACKEND_MOJANG,
            settings.terrainBackend()
        );
        assertAllNewFeatureSwitchesEnabled(settings);
        assertEquals(EngineConfig.DEFAULT_CACHE_MAX_BYTES, settings.cacheMaxBytes());
        assertEquals(
            new EngineConfig.Settings(true, true, true, EngineConfig.DEFAULT_CACHE_MAX_BYTES),
            EngineConfig.Settings.from(new Properties())
        );
    }

    @Test
    void cacheMaxBytesAcceptsOnlyPositiveLongValues() {
        Properties properties = new Properties();
        properties.setProperty(EngineConfig.CACHE_MAX_BYTES_KEY, " 67108864 ");

        assertEquals(67_108_864L, EngineConfig.Settings.from(properties).cacheMaxBytes());

        for (String invalid : new String[] {
            "",
            "0",
            "-1",
            "not-a-number",
            "9223372036854775808"
        }) {
            properties.setProperty(EngineConfig.CACHE_MAX_BYTES_KEY, invalid);
            assertEquals(
                EngineConfig.DEFAULT_CACHE_MAX_BYTES,
                EngineConfig.Settings.from(properties).cacheMaxBytes()
            );
        }
    }

    @Test
    void constructionAndMissingFileLoadDoNotWriteAFile() throws Exception {
        Path path = this.temporaryDirectory.resolve("config").resolve("blockframe-engine.properties");
        EngineConfig config = new EngineConfig(path);

        assertFalse(Files.exists(path));
        assertEquals(EngineConfig.Settings.defaults(), config.load());
        assertFalse(Files.exists(path));
    }

    @Test
    void saveAndReloadRoundTripAllIndependentSwitches() throws Exception {
        Path path = this.temporaryDirectory.resolve("config").resolve("blockframe-engine.properties");
        EngineConfig config = new EngineConfig(path);
        EngineConfig.Settings expected = new EngineConfig.Settings(
            false,
            true,
            false,
            134_217_728L,
            de.morau.blockframe.core.budget.MemoryBudgetSettings.defaults(),
            false,
            false,
            false,
            true,
            false,
            true,
            false,
            true,
            false,
            true,
            true,
            EngineConfig.TERRAIN_BACKEND_NATIVE_EXPERIMENTAL
        );

        config.save(expected);
        config.save(EngineConfig.Settings.defaults());
        config.save(expected);
        config.setSettings(EngineConfig.Settings.defaults());

        assertTrue(Files.isRegularFile(path));
        assertEquals(expected, config.reload());
        assertEquals(
            "134217728",
            expected.toProperties().getProperty(EngineConfig.CACHE_MAX_BYTES_KEY)
        );
        assertEquals(
            "false",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.GPU_BREADCRUMBS_ENABLED_KEY
                )
        );
        assertEquals(
            "true",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig
                        .OPAQUE_SOLID_GPU_SCENE_INDIRECT_ENABLED_KEY
                )
        );
        assertEquals(
            EngineConfig.TERRAIN_BACKEND_NATIVE_EXPERIMENTAL,
            expected
                .toProperties()
                .getProperty(EngineConfig.TERRAIN_BACKEND_KEY)
        );
        assertEquals(
            "false",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.ENTITY_MOTION_SCRATCH_ENABLED_KEY
                )
        );
        assertEquals(
            "true",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.TRANSFORM_SCRATCH_ENABLED_KEY
                )
        );
        assertEquals(
            "false",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.SHADER_SETUP_POOL_ENABLED_KEY
                )
        );
        assertEquals(
            "true",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.MATERIAL_SAMPLER_CACHE_ENABLED_KEY
                )
        );
        assertEquals(
            "false",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.OUTLINE_POSE_REUSE_ENABLED_KEY
                )
        );
        assertEquals(
            "true",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.PHYSICAL_MEMORY_TELEMETRY_ENABLED_KEY
                )
        );
        assertEquals(
            "false",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.DEBUG_LABELS_ENABLED_KEY
                )
        );
        assertEquals(
            "true",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.TRACY_CORRELATION_ENABLED_KEY
                )
        );
        assertFalse(
            expected.toProperties().containsKey("entityHistoryBackend")
        );
        assertEquals(
            "false",
            expected
                .toProperties()
                .getProperty(
                    EngineConfig.DEVICE_FAULT_ENABLED_KEY
                )
        );
        assertEquals(expected, EngineConfig.Settings.from(expected.toProperties()));
        assertEquals(Path.of("config", "blockframe-engine.properties"), EngineConfig.DEFAULT_PATH);
    }

    @Test
    void terrainBackendIsExplicitRestartConfigurationAndFailsClosed() {
        Properties properties = new Properties();
        properties.setProperty(
            EngineConfig.TERRAIN_BACKEND_KEY,
            " NATIVE-EXPERIMENTAL "
        );
        assertEquals(
            EngineConfig.TERRAIN_BACKEND_NATIVE_EXPERIMENTAL,
            EngineConfig.Settings.from(properties).terrainBackend()
        );

        properties.setProperty(
            EngineConfig.TERRAIN_BACKEND_KEY,
            "auto"
        );
        assertEquals(
            EngineConfig.TERRAIN_BACKEND_MOJANG,
            EngineConfig.Settings.from(properties).terrainBackend()
        );
        properties.setProperty(
            EngineConfig.TERRAIN_BACKEND_KEY,
            "unknown-backend"
        );
        assertEquals(
            EngineConfig.TERRAIN_BACKEND_MOJANG,
            EngineConfig.Settings.from(properties).terrainBackend()
        );
    }

    @Test
    void legacyConstructorsKeepEveryAddedSwitchSafelyEnabled() {
        var budgets =
            de.morau.blockframe.core.budget.MemoryBudgetSettings.defaults();

        EngineConfig.Settings four =
            new EngineConfig.Settings(true, true, true, 1L);
        EngineConfig.Settings five =
            new EngineConfig.Settings(true, true, true, 1L, budgets);
        EngineConfig.Settings six =
            new EngineConfig.Settings(
                true,
                true,
                true,
                1L,
                budgets,
                false
            );
        EngineConfig.Settings seven =
            new EngineConfig.Settings(
                true,
                true,
                true,
                1L,
                budgets,
                false,
                false
            );

        for (EngineConfig.Settings settings : new EngineConfig.Settings[] {
            four,
            five,
            six,
            seven
        }) {
            assertAllNewFeatureSwitchesEnabled(settings);
        }
        assertTrue(four.deviceFaultEnabled());
        assertTrue(five.deviceFaultEnabled());
        assertTrue(six.deviceFaultEnabled());
        assertFalse(seven.deviceFaultEnabled());
        assertFalse(six.gpuBreadcrumbsEnabled());
        assertFalse(seven.gpuBreadcrumbsEnabled());
    }

    private static void assertAllNewFeatureSwitchesEnabled(
        EngineConfig.Settings settings
    ) {
        assertTrue(settings.entityMotionScratchEnabled());
        assertTrue(settings.transformScratchEnabled());
        assertTrue(settings.shaderSetupPoolEnabled());
        assertTrue(settings.materialSamplerCacheEnabled());
        assertTrue(settings.outlinePoseReuseEnabled());
        assertTrue(settings.physicalMemoryTelemetryEnabled());
        assertTrue(settings.debugLabelsEnabled());
        assertTrue(settings.tracyCorrelationEnabled());
        assertFalse(
            settings.opaqueSolidGpuSceneIndirectExperimentalEnabled()
        );
        assertEquals(
            EngineConfig.TERRAIN_BACKEND_MOJANG,
            settings.terrainBackend()
        );
    }
}
