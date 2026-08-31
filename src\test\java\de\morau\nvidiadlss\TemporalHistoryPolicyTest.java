package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TemporalHistoryPolicyTest {
    @Test
    void rejectsStaleNearAndFarDepthAtEveryResolution() {
        int[][] resolutions = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        for (int[] resolution : resolutions) {
            assertTrue(TemporalHistoryPolicy.isDisoccluded(0.050F, 0.047F), label(resolution));
            assertTrue(TemporalHistoryPolicy.isDisoccluded(0.005F, 0.0048F), label(resolution));
            assertFalse(TemporalHistoryPolicy.isDisoccluded(0.050F, 0.0497F), label(resolution));
        }
    }

    @Test
    void trailingDisocclusionBiasUsesNormalizedScreenMotion() {
        int[][] resolutions = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        for (int[] resolution : resolutions) {
            float normalizedMovement = 0.001F;
            assertTrue(TemporalHistoryPolicy.trailingDisocclusionBias(
                    resolution[0] * normalizedMovement,
                    0.0F,
                    resolution[0],
                    resolution[1],
                    0.02F,
                    0.05F
            ) > 0.0F, label(resolution));
        }
    }

    @Test
    void stableSurfaceAndLeadingEdgeKeepTemporalHistory() {
        assertEquals(0.0F, TemporalHistoryPolicy.trailingDisocclusionBias(
                24.0F, 0.0F, 2560, 1440, 0.05F, 0.0498F));
        assertEquals(0.0F, TemporalHistoryPolicy.trailingDisocclusionBias(
                24.0F, 0.0F, 2560, 1440, 0.05F, 0.02F));
        assertEquals(0.0F, TemporalHistoryPolicy.trailingDisocclusionBias(
                0.2F, 0.0F, 1280, 720, 0.02F, 0.05F));
    }

    @Test
    void cutoutProtectionKeepsFoliageHistoryWhileSolidDisocclusionRejectsIt() {
        assertTrue(TemporalHistoryPolicy.currentColorBias(false, true, false, 0.0F) == 1.0F);
        assertTrue(TemporalHistoryPolicy.currentColorBias(false, true, true, 0.0F) == 0.0F);
        assertTrue(TemporalHistoryPolicy.currentColorBias(true, true, true, 0.0F) == 1.0F);
    }

    @Test
    void trailingDisocclusionBiasRampsWithNormalizedSpeedInsteadOfHardRejectingHistory() {
        int[][] resolutions = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        for (int[] resolution : resolutions) {
            float slow = TemporalHistoryPolicy.trailingDisocclusionBias(
                    resolution[0] * 0.0006F, 0.0F, resolution[0], resolution[1], 0.02F, 0.05F);
            float fast = TemporalHistoryPolicy.trailingDisocclusionBias(
                    resolution[0] * 0.004F, 0.0F, resolution[0], resolution[1], 0.02F, 0.05F);
            assertTrue(slow >= TemporalHistoryPolicy.DISOCCLUSION_HISTORY_BIAS_MIN
                    && slow < TemporalHistoryPolicy.DISOCCLUSION_HISTORY_BIAS_MAX, label(resolution));
            assertEquals(TemporalHistoryPolicy.DISOCCLUSION_HISTORY_BIAS_MAX, fast, 0.00001F, label(resolution));
        }
    }

    @Test
    void adjacentMatchingPreviousDepthPreventsPixelBoundaryFlicker() {
        assertTrue(TemporalHistoryPolicy.hasMatchingPreviousDepth(
                0.05F, 0.02F, 0.0498F, 0.019F, 0.018F));
        assertFalse(TemporalHistoryPolicy.hasMatchingPreviousDepth(
                0.05F, 0.02F, 0.019F, 0.018F, 0.017F));
        assertTrue(TemporalHistoryPolicy.hasMatchingPreviousDepth(
                0.0F, 0.05F, 0.0F, 0.02F));
    }

    private static String label(int[] resolution) {
        return resolution[0] + "x" + resolution[1];
    }
}
