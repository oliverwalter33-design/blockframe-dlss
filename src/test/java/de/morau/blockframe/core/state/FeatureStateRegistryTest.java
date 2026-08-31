package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.EngineConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureStateRegistryTest {
    @Test
    void inventoryHasExactStableIdsAndExplicitBitOrder() {
        List<String> expectedIds = List.of(
            "render.dlss_mode",
            "render.entity_motion_scratch",
            "render.entity_history_native_experimental",
            "render.transform_scratch",
            "vulkan.shader_setup_pool",
            "vulkan.material_sampler_cache",
            "render.outline_pose_reuse",
            "diagnostics.frame_profiler",
            "diagnostics.gpu_breadcrumbs",
            "diagnostics.physical_memory",
            "diagnostics.debug_labels",
            "diagnostics.tracy_correlation",
            "diagnostics.device_fault",
            "vulkan.opaque_solid_gpu_scene_indirect_experimental"
        );

        assertEquals(14, FeatureId.COUNT);
        assertEquals(FeatureId.COUNT, FeatureId.values().length);
        assertEquals(FeatureId.ALL_MASK, (1L << expectedIds.size()) - 1L);
        for (int index = 0; index < expectedIds.size(); index++) {
            FeatureId feature = FeatureId.fromBitIndex(index);
            assertEquals(index, feature.bitIndex());
            assertEquals(1L << index, feature.mask());
            assertEquals(expectedIds.get(index), feature.stableId());
            assertSame(feature, FeatureId.byStableId(feature.stableId()));
            assertSame(feature, FeatureId.all().get(index));
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> FeatureId.fromBitIndex(-1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FeatureId.fromBitIndex(FeatureId.COUNT)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FeatureId.byStableId("future.feature")
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> FeatureId.all().clear()
        );
    }

    @Test
    void metadataNamesRealConfigurationOwnersAndSafeApplyBoundaries() {
        assertEquals(
            FeatureId.ConfigSource.DLSS_PROPERTIES,
            FeatureId.DLSS_MODE.configSource()
        );
        assertEquals("mode", FeatureId.DLSS_MODE.configKey());
        assertEquals(
            FeatureId.ConfigSource.DLSS_PROPERTIES,
            FeatureId.ENTITY_HISTORY_NATIVE_EXPERIMENTAL.configSource()
        );
        assertEquals(
            "entityHistoryBackend",
            FeatureId.ENTITY_HISTORY_NATIVE_EXPERIMENTAL.configKey()
        );
        assertEquals(
            EngineConfig.ENTITY_MOTION_SCRATCH_ENABLED_KEY,
            FeatureId.ENTITY_MOTION_SCRATCH.configKey()
        );
        assertEquals(
            EngineConfig.TRANSFORM_SCRATCH_ENABLED_KEY,
            FeatureId.TRANSFORM_SCRATCH.configKey()
        );
        assertEquals(
            EngineConfig.SHADER_SETUP_POOL_ENABLED_KEY,
            FeatureId.SHADER_SETUP_POOL.configKey()
        );
        assertEquals(
            EngineConfig.MATERIAL_SAMPLER_CACHE_ENABLED_KEY,
            FeatureId.MATERIAL_SAMPLER_CACHE.configKey()
        );
        assertEquals(
            EngineConfig.OUTLINE_POSE_REUSE_ENABLED_KEY,
            FeatureId.OUTLINE_POSE_REUSE.configKey()
        );
        assertEquals(
            EngineConfig.PROFILER_ENABLED_KEY,
            FeatureId.FRAME_PROFILER.configKey()
        );
        assertEquals(
            EngineConfig.GPU_BREADCRUMBS_ENABLED_KEY,
            FeatureId.GPU_BREADCRUMBS.configKey()
        );
        assertEquals(
            EngineConfig.PHYSICAL_MEMORY_TELEMETRY_ENABLED_KEY,
            FeatureId.PHYSICAL_MEMORY.configKey()
        );
        assertEquals(
            EngineConfig.DEBUG_LABELS_ENABLED_KEY,
            FeatureId.DEBUG_LABELS.configKey()
        );
        assertEquals(
            EngineConfig.TRACY_CORRELATION_ENABLED_KEY,
            FeatureId.TRACY_CORRELATION.configKey()
        );
        assertEquals(
            EngineConfig.DEVICE_FAULT_ENABLED_KEY,
            FeatureId.DEVICE_FAULT.configKey()
        );
        assertEquals(
            EngineConfig.OPAQUE_SOLID_GPU_SCENE_INDIRECT_ENABLED_KEY,
            FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
                .configKey()
        );

        assertFalse(
            FeatureId.DLSS_MODE.reloadRequirement().requiresReload()
        );
        assertFalse(
            FeatureId.DLSS_MODE.reloadRequirement().requiresRestart()
        );
        assertFalse(
            FeatureId.SHADER_SETUP_POOL
                .reloadRequirement()
                .requiresReload()
        );
        assertTrue(
            FeatureId.SHADER_SETUP_POOL
                .reloadRequirement()
                .requiresRestart()
        );
        assertEquals(
            FeatureId.ReloadRequirement.PROCESS_RESTART,
            FeatureId.SHADER_SETUP_POOL.reloadRequirement()
        );
        assertEquals(
            FeatureId.ReloadRequirement.PROCESS_RESTART,
            FeatureId.FRAME_PROFILER.reloadRequirement()
        );
        assertEquals(
            FeatureId.ReloadRequirement.PROCESS_RESTART,
            FeatureId.GPU_BREADCRUMBS.reloadRequirement()
        );
        assertEquals(
            FeatureId.ReloadRequirement.PROCESS_RESTART,
            FeatureId.DEBUG_LABELS.reloadRequirement()
        );
        assertEquals(
            FeatureId.ReloadRequirement.PROCESS_RESTART,
            FeatureId.DEVICE_FAULT.reloadRequirement()
        );
        assertTrue(
            FeatureId.DEVICE_FAULT
                .reloadRequirement()
                .requiresRestart()
        );
        assertTrue(
            FeatureId.TRACY_CORRELATION
                .reloadRequirement()
                .requiresRestart()
        );
        assertTrue(
            FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
                .reloadRequirement()
                .requiresRestart()
        );
    }

    @Test
    void initialSnapshotIsFixedImmutableAndFullyCached() {
        FeatureStateRegistry registry = new FeatureStateRegistry();
        FeatureStateRegistry.Snapshot snapshot = registry.snapshot();

        assertEquals(FeatureId.COUNT, registry.size());
        assertEquals(0L, snapshot.revision());
        assertEquals(0L, snapshot.requestedMask());
        assertEquals(0L, snapshot.supportedMask());
        assertEquals(0L, snapshot.enabledMask());
        assertEquals(0L, snapshot.effectiveMask());
        assertEquals(0L, snapshot.fallbackMask());
        assertEquals(0L, snapshot.quarantinedMask());
        assertEquals(FeatureId.COUNT, snapshot.states().size());
        assertEquals(FeatureId.COUNT, snapshot.debugLines().size());

        for (FeatureId id : FeatureId.all()) {
            FeatureState state = snapshot.state(id);
            assertSame(id, state.id());
            assertEquals("not-evaluated", state.reason());
            assertEquals(0L, state.clientGeneration());
            assertEquals(0L, state.deviceGeneration());
            assertSame(state, registry.state(id));
        }

        assertSame(snapshot.debugLines(), registry.debugLines());
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.states().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.debugLines().clear()
        );
    }

    @Test
    void changedUpdatePublishesMasksAndNoOpKeepsSnapshotIdentity() {
        FeatureStateRegistry registry = new FeatureStateRegistry();
        FeatureStateRegistry.Snapshot before = registry.snapshot();
        FeatureId id = FeatureId.MATERIAL_SAMPLER_CACHE;

        assertTrue(
            registry.update(
                id,
                true,
                true,
                true,
                false,
                true,
                true,
                "runtime-quarantine",
                4L,
                9L
            )
        );

        FeatureStateRegistry.Snapshot changed = registry.snapshot();
        FeatureState state = changed.state(id);
        assertEquals(1L, changed.revision());
        assertEquals(id.mask(), changed.requestedMask());
        assertEquals(id.mask(), changed.supportedMask());
        assertEquals(id.mask(), changed.enabledMask());
        assertEquals(0L, changed.effectiveMask());
        assertEquals(id.mask(), changed.fallbackMask());
        assertEquals(id.mask(), changed.quarantinedMask());
        assertEquals("runtime-quarantine", state.reason());
        assertEquals(4L, state.clientGeneration());
        assertEquals(9L, state.deviceGeneration());
        assertSame(id.configSource(), state.configSource());
        assertEquals(id.configKey(), state.configKey());
        assertSame(id.reloadRequirement(), state.reloadRequirement());
        assertTrue(changed.debugLines().get(id.bitIndex()).contains(
            "reason=runtime-quarantine"
        ));
        assertTrue(changed.debugLines().get(id.bitIndex()).contains(
            "config-owner="
        ));

        assertFalse(before.state(id).requested());
        assertEquals("not-evaluated", before.state(id).reason());
        assertFalse(
            registry.update(
                id,
                true,
                true,
                true,
                false,
                true,
                true,
                "runtime-quarantine",
                4L,
                9L
            )
        );
        assertSame(changed, registry.snapshot());
        assertSame(state, registry.state(id));
    }

    @Test
    void coldDlssLiveRequestPublishesRealProcessRestartRequirement() {
        FeatureStateRegistry registry = new FeatureStateRegistry();
        registry.update(
            FeatureId.DLSS_MODE,
            true,
            true,
            false,
            false,
            true,
            false,
            RuntimeFeaturePolicy.DLSS_RESTART_REQUIRED_REASON,
            3L,
            1L
        );

        FeatureState state = registry.state(FeatureId.DLSS_MODE);
        assertEquals(
            FeatureId.ReloadRequirement.PROCESS_RESTART,
            state.reloadRequirement()
        );
        assertTrue(state.reloadRequirement().requiresRestart());
        assertTrue(
            registry.debugLines()
                .get(FeatureId.DLSS_MODE.bitIndex())
                .contains("apply=PROCESS_RESTART")
        );
        assertTrue(
            registry.debugLines()
                .get(FeatureId.DLSS_MODE.bitIndex())
                .contains("reason=restart-required")
        );
    }

    @Test
    void nullReasonIsCanonicalAndGenerationValidationFailsBeforePublish() {
        FeatureStateRegistry registry = new FeatureStateRegistry();

        assertTrue(
            registry.update(
                FeatureId.FRAME_PROFILER,
                true,
                true,
                true,
                true,
                false,
                false,
                null,
                1L,
                0L
            )
        );
        assertEquals(
            "",
            registry.state(FeatureId.FRAME_PROFILER).reason()
        );
        FeatureStateRegistry.Snapshot valid = registry.snapshot();

        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(
                FeatureId.FRAME_PROFILER,
                true,
                true,
                true,
                true,
                false,
                false,
                "",
                -1L,
                0L
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new FeatureState(
                FeatureId.FRAME_PROFILER,
                true,
                true,
                true,
                true,
                false,
                false,
                "",
                0L,
                -1L
            )
        );
        assertSame(valid, registry.snapshot());
    }

    @Test
    void effectiveAndFallbackCanBeStickyWithoutSnapshotOscillation() {
        FeatureStateRegistry registry = new FeatureStateRegistry();
        FeatureId id = FeatureId.MATERIAL_SAMPLER_CACHE;
        assertTrue(
            registry.update(
                id,
                true,
                true,
                true,
                true,
                true,
                false,
                "mixed-productive-outcomes",
                2L,
                4L
            )
        );
        FeatureStateRegistry.Snapshot mixed = registry.snapshot();
        assertEquals(id.mask(), mixed.effectiveMask());
        assertEquals(id.mask(), mixed.fallbackMask());
        assertTrue(mixed.state(id).effective());
        assertTrue(mixed.state(id).fallback());

        assertFalse(
            registry.update(
                id,
                true,
                true,
                true,
                true,
                true,
                false,
                "mixed-productive-outcomes",
                2L,
                4L
            )
        );
        assertSame(mixed, registry.snapshot());
    }
}
