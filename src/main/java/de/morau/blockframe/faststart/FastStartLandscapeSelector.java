package de.morau.blockframe.faststart;

/**
 * Chooses the authoritative visible-terrain readiness source.
 *
 * <p>Sodium intentionally owns terrain rendering when active and can leave
 * Minecraft's vanilla visible-section list empty. In that case only Sodium's
 * public renderer API is accepted. No private field, loader internal or
 * version-unstable Mixin accessor is used.</p>
 */
final class FastStartLandscapeSelector {
    enum RendererPath {
        VANILLA("Vanilla"),
        SODIUM("Sodium"),
        NONE("kein Rendererpfad");

        private final String label;

        RendererPath(String label) {
            this.label = label;
        }

        String label() {
            return this.label;
        }
    }

    record Observation(
        int expectedChunks,
        int vanillaVisibleSections,
        int vanillaUncompiledSections,
        boolean vanillaPlayerSectionReady,
        long vanillaSignature,
        boolean sodiumAvailable,
        boolean sodiumTerrainRenderComplete,
        int sodiumVisibleChunks,
        boolean sodiumPlayerSectionReady,
        long sodiumSignature,
        String sodiumReason
    ) {}

    record Decision(
        boolean ready,
        RendererPath rendererPath,
        int visibleSections,
        boolean playerSectionReady,
        long signature,
        String reason
    ) {}

    private FastStartLandscapeSelector() {}

    static Decision select(Observation observation) {
        if (observation.expectedChunks() != 0) {
            return decision(
                false,
                selectedPath(observation),
                selectedVisibleCount(observation),
                selectedPlayerReady(observation),
                selectedSignature(observation),
                "erwartete Chunks fehlen"
            );
        }
        if (observation.vanillaVisibleSections() > 0) {
            if (observation.vanillaUncompiledSections() != 0) {
                return decision(
                    false,
                    RendererPath.VANILLA,
                    observation.vanillaVisibleSections(),
                    observation.vanillaPlayerSectionReady(),
                    observation.vanillaSignature(),
                    "sichtbare Vanilla-Meshes noch nicht veröffentlicht"
                );
            }
            if (!observation.vanillaPlayerSectionReady()) {
                return decision(
                    false,
                    RendererPath.VANILLA,
                    observation.vanillaVisibleSections(),
                    false,
                    observation.vanillaSignature(),
                    "Vanilla-Spielerabschnitt noch nicht sichtbar"
                );
            }
            return decision(
                true,
                RendererPath.VANILLA,
                observation.vanillaVisibleSections(),
                true,
                observation.vanillaSignature(),
                "sichtbares Vanilla-Feld bereit"
            );
        }
        if (!observation.sodiumAvailable()) {
            return decision(
                false,
                RendererPath.NONE,
                0,
                false,
                0L,
                observation.sodiumReason()
            );
        }
        if (observation.sodiumVisibleChunks() <= 0) {
            return decision(
                false,
                RendererPath.SODIUM,
                observation.sodiumVisibleChunks(),
                observation.sodiumPlayerSectionReady(),
                observation.sodiumSignature(),
                "Sodium meldet noch keine sichtbaren Chunks"
            );
        }
        if (!observation.sodiumTerrainRenderComplete()) {
            return decision(
                false,
                RendererPath.SODIUM,
                observation.sodiumVisibleChunks(),
                observation.sodiumPlayerSectionReady(),
                observation.sodiumSignature(),
                "Sodium-Buildqueue noch nicht leer"
            );
        }
        if (!observation.sodiumPlayerSectionReady()) {
            return decision(
                false,
                RendererPath.SODIUM,
                observation.sodiumVisibleChunks(),
                false,
                observation.sodiumSignature(),
                "Sodium-Spielerabschnitt noch nicht gebaut"
            );
        }
        return decision(
            true,
            RendererPath.SODIUM,
            observation.sodiumVisibleChunks(),
            true,
            observation.sodiumSignature(),
            "sichtbares Sodium-Feld bereit"
        );
    }

    private static Decision decision(
        boolean ready,
        RendererPath rendererPath,
        int visibleSections,
        boolean playerSectionReady,
        long signature,
        String reason
    ) {
        return new Decision(
            ready,
            rendererPath,
            visibleSections,
            playerSectionReady,
            signature,
            reason
        );
    }

    private static RendererPath selectedPath(Observation observation) {
        if (observation.vanillaVisibleSections() > 0) {
            return RendererPath.VANILLA;
        }
        return observation.sodiumAvailable()
            ? RendererPath.SODIUM
            : RendererPath.NONE;
    }

    private static int selectedVisibleCount(Observation observation) {
        return observation.vanillaVisibleSections() > 0
            ? observation.vanillaVisibleSections()
            : observation.sodiumVisibleChunks();
    }

    private static boolean selectedPlayerReady(Observation observation) {
        return observation.vanillaVisibleSections() > 0
            ? observation.vanillaPlayerSectionReady()
            : observation.sodiumPlayerSectionReady();
    }

    private static long selectedSignature(Observation observation) {
        return observation.vanillaVisibleSections() > 0
            ? observation.vanillaSignature()
            : observation.sodiumSignature();
    }
}
