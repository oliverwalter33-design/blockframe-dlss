package de.morau.blockframe.faststart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FastStartStabilityWindowTest {
    @Test
    void requiresContinuousEligibilityForTheWholeWindow() {
        FastStartStabilityWindow window =
            new FastStartStabilityWindow(500L);

        assertFalse(window.observe(true, 7L, 1_000L));
        assertFalse(window.observe(true, 7L, 1_499L));
        assertTrue(window.observe(true, 7L, 1_500L));
        assertEquals(500L, window.stableForNanos(1_500L));
    }

    @Test
    void readinessLossRestartsTheWindow() {
        FastStartStabilityWindow window =
            new FastStartStabilityWindow(500L);

        assertFalse(window.observe(true, 7L, 1_000L));
        assertFalse(window.observe(false, 7L, 1_400L));
        assertEquals(0L, window.stableForNanos(1_400L));
        assertFalse(window.observe(true, 7L, 2_000L));
        assertFalse(window.observe(true, 7L, 2_499L));
        assertTrue(window.observe(true, 7L, 2_500L));
    }

    @Test
    void visibleMeshChangeRestartsTheWindow() {
        FastStartStabilityWindow window =
            new FastStartStabilityWindow(500L);

        assertFalse(window.observe(true, 7L, 1_000L));
        assertFalse(window.observe(true, 8L, 1_400L));
        assertFalse(window.observe(true, 8L, 1_899L));
        assertTrue(window.observe(true, 8L, 1_900L));
    }

    @Test
    void backwardsClockObservationCannotCompleteTheWindow() {
        FastStartStabilityWindow window =
            new FastStartStabilityWindow(500L);

        assertFalse(window.observe(true, 7L, 1_000L));
        assertFalse(window.observe(true, 7L, 900L));
        assertEquals(0L, window.stableForNanos(900L));
        assertTrue(window.observe(true, 7L, 1_400L));
    }
}
