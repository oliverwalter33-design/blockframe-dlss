package de.morau.nvidiadlss;

import java.util.Locale;

/** Dev-only A/B switch for isolating the legacy rigid local-player transport. */
final class ThirdPersonMotionAudit {
    enum Mode {
        EXACT_ARTICULATED,
        LEGACY_RIGID,
        EXCLUDE_LOCAL_PLAYER
    }

    private static final Mode MODE = setting();

    private ThirdPersonMotionAudit() {}

    static boolean exactArticulatedGeometry() {
        return MODE == Mode.EXACT_ARTICULATED;
    }

    static boolean excludeLocalPlayerFromRigidTransport() {
        return MODE != Mode.LEGACY_RIGID;
    }

    static String metadataJson() {
        return "{\"localPlayerMotion\":\"" + MODE + "\"}";
    }

    private static Mode setting() {
        if (!DeveloperDiagnostics.enabled()) {
            return Mode.EXACT_ARTICULATED;
        }
        String value = System.getProperty(
            "nvidia_dlss.devLocalPlayerMotion",
            "EXACT_ARTICULATED"
        );
        try {
            if ("INCLUDE_RIGID_ENTITY".equalsIgnoreCase(value.trim())) {
                return Mode.LEGACY_RIGID;
            }
            return Mode.valueOf(
                value.trim().toUpperCase(Locale.ROOT).replace('-', '_')
            );
        } catch (IllegalArgumentException ignored) {
            NvidiaDlssMod.LOGGER.warn(
                "Unbekannter dev.3 Local-Player-Motion-Auditwert {}; verwende EXACT_ARTICULATED",
                value
            );
            return Mode.EXACT_ARTICULATED;
        }
    }
}
