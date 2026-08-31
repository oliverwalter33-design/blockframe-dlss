package de.morau.nvidiadlss;

import java.util.Map;

/**
 * Release contract for optional DLSS temporal inputs.
 *
 * <p>The pinned Streamline/DLSS runtime receives the supported transparency
 * hint plus a production-owned BiasCurrentColorHint mask restricted to exact
 * local-player disocclusions. Historical diagnostic overrides and
 * motion-fallback switches are intentionally not parsed, even with developer
 * diagnostics enabled, so they cannot broaden that mask.</p>
 */
final class TemporalHintAudit {
    record Policy() {
        boolean diagnosticHintsActive() {
            return false;
        }

        boolean releaseSafe() {
            return true;
        }
    }

    private static final Policy POLICY = new Policy();

    private TemporalHintAudit() {}

    static int secondaryHintMode(int ordinaryHintMode) {
        return ordinaryHintMode == FoliageAudit.HINT_TRANSPARENCY
            ? FoliageAudit.HINT_TRANSPARENCY
            : FoliageAudit.HINT_NONE;
    }

    static String metadataJson() {
        return "{\"optionalInputs\":[\"BiasCurrentColorHint(local-player)\","
            + "\"TransparencyHint\"],"
            + "\"historyBiasScope\":\"exact-articulated-player-disocclusion\","
            + "\"experimentalHintOverrides\":false,"
            + "\"motionFallbackOverride\":false}";
    }

    static boolean active() {
        return POLICY.diagnosticHintsActive();
    }

    static boolean releaseSafe() {
        return POLICY.releaseSafe();
    }

    static Policy policy(Map<?, ?> ignored) {
        return POLICY;
    }
}
