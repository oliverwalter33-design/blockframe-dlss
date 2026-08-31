package de.morau.nvidiadlss.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DlssMixinPluginEvidenceGateTest {
    @Test
    void recognizesOnlyBoundedNativeTerrainEvidenceMixins() {
        assertTrue(
            DlssMixinPlugin.isNativeTerrainEvidenceMixin(
                "de.morau.nvidiadlss.mixin."
                    + "NativeTerrainSectionCompilerEvidenceMixin"
            )
        );
        assertTrue(
            DlssMixinPlugin.isNativeTerrainEvidenceMixin(
                "de.morau.nvidiadlss.mixin."
                    + "NativeTerrainDispatcherEvidenceMixin"
            )
        );
        assertTrue(
            DlssMixinPlugin.isNativeTerrainEvidenceMixin(
                "de.morau.nvidiadlss.mixin."
                    + "NativeTerrainUberHeapEvidenceMixin"
            )
        );
        assertTrue(
            DlssMixinPlugin.isNativeTerrainEvidenceMixin(
                "de.morau.nvidiadlss.mixin."
                    + "NativeTerrainOpaqueSubmissionEvidenceMixin"
            )
        );
        assertFalse(
            DlssMixinPlugin.isNativeTerrainEvidenceMixin(
                "de.morau.nvidiadlss.mixin.LevelRendererMixin"
            )
        );
        assertFalse(
            DlssMixinPlugin.isNativeTerrainEvidenceMixin(null)
        );
    }

    @Test
    void evidenceTransformationIsDefaultOffAndExplicitlyOptIn() {
        String property =
            DlssMixinPlugin.NATIVE_TERRAIN_EVIDENCE_PROPERTY;
        String previous = System.getProperty(property);
        String evidence =
            "de.morau.nvidiadlss.mixin."
                + "NativeTerrainOpaqueSubmissionEvidenceMixin";
        try {
            System.clearProperty(property);
            assertFalse(
                DlssMixinPlugin
                    .nativeTerrainEvidenceMixinEnabled(evidence)
            );
            System.setProperty(property, "true");
            assertTrue(
                DlssMixinPlugin
                    .nativeTerrainEvidenceMixinEnabled(evidence)
            );
            assertTrue(
                DlssMixinPlugin
                    .nativeTerrainEvidenceMixinEnabled(
                        "de.morau.nvidiadlss.mixin.LevelRendererMixin"
                    )
            );
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
