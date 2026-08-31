package de.morau.nvidiadlss.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DlssMixinPluginFastStartGateTest {
    @Test
    void telemetryHooksRequireExplicitDeveloperMaster() {
        assertFalse(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.FastStartResourceReloadMixin",
                true,
                false
            )
        );
        assertTrue(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.FastStartLevelLoadingScreenMixin",
                true,
                true
            )
        );
        for (String suffix : new String[] {
            "LevelExtractorTelemetryMixin",
            "RenderPassTelemetryMixin",
            "StagingBufferUploaderTelemetryMixin",
            "StagingBufferInvoker",
            "VulkanUtilsDeviceFaultMixin",
            "GameRendererDiagnosticsMixin",
            "LevelRendererDiagnosticsMixin",
            "VulkanCommandEncoderDiagnosticsMixin",
            "VulkanDeviceDiagnosticsMixin",
            "GlRenderPassMixin",
            "MipmapGeneratorMixin",
            "SpriteContentsMixin"
        }) {
            assertFalse(
                DlssMixinPlugin.shouldApplyMixinForEnvironment(
                    "de.morau.nvidiadlss.mixin." + suffix,
                    false,
                    false
                ),
                suffix
            );
            assertTrue(
                DlssMixinPlugin.shouldApplyMixinForEnvironment(
                    "de.morau.nvidiadlss.mixin." + suffix,
                    false,
                    true
                ),
                suffix
            );
        }
    }

    @Test
    void dlssHooksRemainEnabledWithSodium() {
        assertTrue(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.GameRendererMixin",
                true
            )
        );
        assertTrue(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.LevelRendererMixin",
                true
            )
        );
        assertTrue(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin."
                    + "VulkanCommandEncoderLifecycleMixin",
                true,
                false
            )
        );
    }

    @Test
    void nativeTerrainOwnershipRemainsDisabledWithSodium() {
        assertFalse(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.NativeTerrainModelManagerMixin",
                true
            )
        );
        assertFalse(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.accessor."
                    + "NativeTerrainWeightedVariantsAccessor",
                true
            )
        );
    }

    @Test
    void sodiumShaderCompatibilityRunsOnlyWithSodium() {
        String mixin = "de.morau.nvidiadlss.mixin.SodiumShaderChunkRendererMixin";
        assertTrue(DlssMixinPlugin.shouldApplyMixinForEnvironment(mixin, true));
        assertFalse(DlssMixinPlugin.shouldApplyMixinForEnvironment(mixin, false));
    }

    @Test
    void sodiumOptionsIntegrationRunsOnlyWithSodium() {
        for (String mixin : new String[] {
            "de.morau.nvidiadlss.mixin.sodium.OptionBuilderAccessor",
            "de.morau.nvidiadlss.mixin.sodium.SodiumConfigBuilderMixin"
        }) {
            assertTrue(
                DlssMixinPlugin.shouldApplyMixinForEnvironment(
                    mixin,
                    true
                )
            );
            assertFalse(
                DlssMixinPlugin.shouldApplyMixinForEnvironment(
                    mixin,
                    false
                )
            );
            assertTrue(DlssMixinPlugin.isSodiumOptionsMixin(mixin));
        }
        assertFalse(DlssMixinPlugin.isSodiumOptionsMixin(null));
        assertFalse(
            DlssMixinPlugin.isSodiumOptionsMixin(
                "example.SodiumConfigBuilderMixinUnexpected"
            )
        );
    }

    @Test
    void telemetryAllowListDoesNotAcceptSimilarNames() {
        assertFalse(
            DlssMixinPlugin.isFastStartTelemetryMixin(
                "example.FastStartResourceReloadMixinUnexpected"
            )
        );
        assertFalse(
            DlssMixinPlugin.isFastStartTelemetryMixin(
                "example.UnrelatedFastStartMixin"
            )
        );
        assertFalse(DlssMixinPlugin.isFastStartTelemetryMixin(null));
    }

    @Test
    void nonSodiumEnvironmentRetainsExistingRendererPolicy() {
        assertTrue(
            DlssMixinPlugin.shouldApplyMixinForEnvironment(
                "de.morau.nvidiadlss.mixin.GameRendererMixin",
                false
            )
        );
    }
}
