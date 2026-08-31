package de.morau.blockframe.render.terrain.gpuscene;

/**
 * Permanent fail-closed tombstone for the archived Phase-2A.1D NO_GO
 * renderer.
 *
 * <p>The four former Terrain MixinExtras wrappers are no longer registered.
 * Keeping the historical sources and this constant gate preserves the
 * experiment as evidence without transforming Mojang's terrain warm path.
 * The old developer configuration bit is intentionally not consulted.</p>
 */
public final class OpaqueSolidGpuScenePolicy {
    private OpaqueSolidGpuScenePolicy() {
    }

    public static boolean ownerHooksEnabled() {
        return false;
    }
}
