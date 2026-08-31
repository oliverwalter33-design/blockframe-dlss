package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageQualityReleaseProfileTest {
    @Test
    void defaultProfileHasNoExperimentalHintOrMotionOverride() {
        TemporalHintAudit.Policy policy = TemporalHintAudit.policy(Map.of());

        assertFalse(policy.diagnosticHintsActive());
        assertTrue(policy.releaseSafe());
    }

    @Test
    void legacyPropertiesCannotReenableRemovedRuntimePaths() {
        TemporalHintAudit.Policy policy = TemporalHintAudit.policy(Map.of(
            DeveloperDiagnostics.PROPERTY,
            "true",
            "legacy.temporal.hint",
            "ONE_RECT",
            "legacy.motion.fallback",
            "ZERO"
        ));

        assertFalse(policy.diagnosticHintsActive());
        assertTrue(policy.releaseSafe());
    }
}
