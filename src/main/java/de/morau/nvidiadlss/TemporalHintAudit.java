package de.morau.nvidiadlss;

import java.util.Map;

/**
 * Release contract for optional DLSS temporal inputs.
 *
 * <p>The pinned Streamline/DLSS runtime receives only the supported
 * transparency hint. Historical experimental hint and motion-fallback
 * switches are intentionally not parsed, even with developer diagnostics
 * enabled, so they cannot alter a game frame.</p>
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
        return "{\"optionalInput\":\"TransparencyHint\","
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
