package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.EngineConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RuntimeFeaturePolicyTest {
    @Test
    void normalPolicyReflectsIndependentRequests() {
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "quality",
            "heap",
            false
        );

        assertTrue(policy.requested(FeatureId.DLSS_MODE));
        assertTrue(policy.enabled(FeatureId.DLSS_MODE));
        assertTrue(policy.enabled(FeatureId.ENTITY_MOTION_SCRATCH));
        assertFalse(
            policy.requested(
                FeatureId.ENTITY_HISTORY_NATIVE_EXPERIMENTAL
            )
        );
        assertFalse(
            policy.enabled(
                FeatureId.ENTITY_HISTORY_NATIVE_EXPERIMENTAL
            )
        );
        assertFalse(
            policy.requested(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
            )
        );
        assertFalse(
            policy.enabled(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
            )
        );
        assertFalse(policy.requested(FeatureId.FRAME_PROFILER));
        assertFalse(policy.requested(FeatureId.GPU_BREADCRUMBS));
        assertFalse(policy.requested(FeatureId.PHYSICAL_MEMORY));
        assertFalse(policy.requested(FeatureId.DEBUG_LABELS));
        assertFalse(policy.requested(FeatureId.TRACY_CORRELATION));
        assertFalse(policy.requested(FeatureId.DEVICE_FAULT));
    }

    @Test
    void explicitDeveloperMasterEnablesConfiguredDiagnostics() {
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "dlaa",
            "heap",
            false,
            true
        );

        assertTrue(policy.enabled(FeatureId.FRAME_PROFILER));
        assertTrue(policy.enabled(FeatureId.GPU_BREADCRUMBS));
        assertTrue(policy.enabled(FeatureId.PHYSICAL_MEMORY));
        assertTrue(policy.enabled(FeatureId.DEBUG_LABELS));
        assertTrue(policy.enabled(FeatureId.TRACY_CORRELATION));
        assertTrue(policy.enabled(FeatureId.DEVICE_FAULT));
    }

    @Test
    void safeStartDisablesEveryOptionalFeatureWithoutChangingRequests() {
        RuntimeFeaturePolicy normal = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "dlaa",
            "native-experimental",
            false
        );
        RuntimeFeaturePolicy safe = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "dlaa",
            "native-experimental",
            true
        );

        assertEquals(normal.requestedMask(), safe.requestedMask());
        assertEquals(0L, safe.enabledMask());
        for (FeatureId id : FeatureId.all()) {
            assertFalse(safe.enabled(id));
        }
    }

    @Test
    void initialPublicationIsCachedAndGenerationScoped() {
        FeatureStateRegistry registry = new FeatureStateRegistry();
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "off",
            "heap",
            false
        );
        policy.publishInitial(registry, 7L);

        assertEquals(
            7L,
            registry.state(FeatureId.TRANSFORM_SCRATCH)
                .clientGeneration()
        );
        assertTrue(
            registry.state(FeatureId.TRANSFORM_SCRATCH).supported()
        );
        assertFalse(registry.state(FeatureId.DLSS_MODE).requested());
        assertEquals(
            policy.requestedMask(),
            registry.snapshot().requestedMask()
        );
    }

    @Test
    void coldOffRequiresRestartWhileStartupEnabledRetainsLiveSwitching() {
        RuntimeFeaturePolicy coldOff = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "off",
            "heap",
            false
        );
        long otherRequested =
            coldOff.requestedMask() & ~FeatureId.DLSS_MODE.mask();
        long otherEnabled =
            coldOff.enabledMask() & ~FeatureId.DLSS_MODE.mask();

        assertFalse(coldOff.streamlineBootstrapAllowed());
        assertTrue(coldOff.updateLiveDlssMode(true));
        assertTrue(coldOff.requested(FeatureId.DLSS_MODE));
        assertFalse(coldOff.enabled(FeatureId.DLSS_MODE));
        assertTrue(coldOff.dlssRestartRequired());
        assertEquals(
            RuntimeFeaturePolicy.DLSS_RESTART_REQUIRED_REASON,
            coldOff.disabledReason(FeatureId.DLSS_MODE)
        );
        assertEquals(
            otherRequested,
            coldOff.requestedMask() & ~FeatureId.DLSS_MODE.mask()
        );
        assertEquals(
            otherEnabled,
            coldOff.enabledMask() & ~FeatureId.DLSS_MODE.mask()
        );
        assertFalse(coldOff.updateLiveDlssMode(true));
        assertTrue(coldOff.updateLiveDlssMode(false));
        assertFalse(coldOff.requested(FeatureId.DLSS_MODE));
        assertFalse(coldOff.enabled(FeatureId.DLSS_MODE));
        assertFalse(coldOff.dlssRestartRequired());

        RuntimeFeaturePolicy startupEnabled = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "quality",
            "heap",
            false
        );
        assertTrue(startupEnabled.streamlineBootstrapAllowed());
        assertTrue(startupEnabled.updateLiveDlssMode(false));
        assertFalse(startupEnabled.enabled(FeatureId.DLSS_MODE));
        assertTrue(startupEnabled.updateLiveDlssMode(true));
        assertTrue(startupEnabled.enabled(FeatureId.DLSS_MODE));
        assertFalse(startupEnabled.dlssRestartRequired());

        RuntimeFeaturePolicy safe = new RuntimeFeaturePolicy(
            EngineConfig.Settings.defaults(),
            "off",
            "heap",
            true
        );
        assertTrue(safe.updateLiveDlssMode(true));
        assertTrue(safe.requested(FeatureId.DLSS_MODE));
        assertFalse(safe.enabled(FeatureId.DLSS_MODE));
        assertFalse(safe.streamlineBootstrapAllowed());
        assertFalse(safe.dlssRestartRequired());
        assertEquals(
            "safe-start-one-shot",
            safe.disabledReason(FeatureId.DLSS_MODE)
        );
    }

    @Test
    void everyEngineOwnedSwitchDisablesOnlyItsCanonicalFeature() {
        for (FeatureId disabled : FeatureId.all()) {
            if (
                disabled.configSource()
                    != FeatureId.ConfigSource.ENGINE_PROPERTIES
            ) {
                continue;
            }
            Properties properties = new Properties();
            properties.setProperty(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
                    .configKey(),
                "true"
            );
            properties.setProperty(disabled.configKey(), "false");
            RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
                EngineConfig.Settings.from(properties),
                "quality",
                "native-experimental",
                false,
                true
            );

            for (FeatureId feature : FeatureId.all()) {
                if (
                    feature.configSource()
                        == FeatureId.ConfigSource.ENGINE_PROPERTIES
                ) {
                    assertEquals(
                        feature
                                != FeatureId
                                    .OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
                            && feature != disabled,
                        policy.requested(feature),
                        disabled.stableId()
                            + " must not change "
                            + feature.stableId()
                    );
                } else {
                    assertTrue(policy.requested(feature));
                }
            }
        }
    }

    @Test
    void archivedV16RequestCanNoLongerActivateTheNoGoRenderer() {
        Properties properties = new Properties();
        properties.setProperty(
            EngineConfig
                .OPAQUE_SOLID_GPU_SCENE_INDIRECT_ENABLED_KEY,
            "true"
        );
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            EngineConfig.Settings.from(properties),
            "quality",
            "heap",
            false
        );
        assertFalse(
            policy.requested(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
            )
        );
        assertFalse(
            policy.enabled(
                FeatureId.OPAQUE_SOLID_GPU_SCENE_INDIRECT_EXPERIMENTAL
            )
        );
    }
}
