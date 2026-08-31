package de.morau.blockframe.faststart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FastStartLandscapeSelectorTest {
    @Test
    void acceptsCompleteVanillaVisibleField() {
        FastStartLandscapeSelector.Decision decision = select(
            0,
            42,
            0,
            true,
            false,
            false,
            0,
            false
        );

        assertTrue(decision.ready());
        assertEquals(
            FastStartLandscapeSelector.RendererPath.VANILLA,
            decision.rendererPath()
        );
        assertEquals(42, decision.visibleSections());
    }

    @Test
    void acceptsSodiumWhenItOwnsTheEmptyVanillaVisibleList() {
        FastStartLandscapeSelector.Decision decision = select(
            0,
            0,
            0,
            true,
            true,
            true,
            37,
            true
        );

        assertTrue(decision.ready());
        assertEquals(
            FastStartLandscapeSelector.RendererPath.SODIUM,
            decision.rendererPath()
        );
        assertEquals(37, decision.visibleSections());
    }

    @Test
    void sodiumBuildQueueMustBeComplete() {
        FastStartLandscapeSelector.Decision decision = select(
            0,
            0,
            0,
            true,
            true,
            false,
            37,
            true
        );

        assertFalse(decision.ready());
        assertEquals(
            "Sodium-Buildqueue noch nicht leer",
            decision.reason()
        );
    }

    @Test
    void expectedChunksBlockEveryRendererPath() {
        FastStartLandscapeSelector.Decision decision = select(
            1,
            0,
            0,
            true,
            true,
            true,
            37,
            true
        );

        assertFalse(decision.ready());
        assertEquals("erwartete Chunks fehlen", decision.reason());
    }

    @Test
    void missingRendererEvidenceFailsClosed() {
        FastStartLandscapeSelector.Decision decision = select(
            0,
            0,
            0,
            true,
            false,
            false,
            0,
            false
        );

        assertFalse(decision.ready());
        assertEquals(
            FastStartLandscapeSelector.RendererPath.NONE,
            decision.rendererPath()
        );
    }

    private static FastStartLandscapeSelector.Decision select(
        int expectedChunks,
        int vanillaVisible,
        int vanillaUncompiled,
        boolean vanillaPlayerReady,
        boolean sodiumAvailable,
        boolean sodiumTerrainComplete,
        int sodiumVisible,
        boolean sodiumPlayerReady
    ) {
        return FastStartLandscapeSelector.select(
            new FastStartLandscapeSelector.Observation(
                expectedChunks,
                vanillaVisible,
                vanillaUncompiled,
                vanillaPlayerReady,
                11L,
                sodiumAvailable,
                sodiumTerrainComplete,
                sodiumVisible,
                sodiumPlayerReady,
                22L,
                "Sodium nicht verfügbar"
            )
        );
    }
}
