package de.morau.nvidiadlss;

/** Resolution-independent thresholds shared by the DLSS history-rejection compute pass. */
public final class TemporalHistoryPolicy {
    public static final float ABSOLUTE_DEPTH_EPSILON = 0.00002F;
    public static final float BACKGROUND_DEPTH_EPSILON = 1.0e-7F;
    public static final float RELATIVE_DEPTH_EPSILON = 0.0125F;
    public static final float EDGE_RELATIVE_DEPTH_THRESHOLD = 0.02F;
    public static final float EDGE_MOTION_UV_THRESHOLD = 1.0F / 2048.0F;
    public static final float DISOCCLUSION_HISTORY_BIAS_MIN = 0.15F;
    public static final float DISOCCLUSION_HISTORY_BIAS_MAX = 0.65F;
    public static final float DISOCCLUSION_HISTORY_BIAS_FULL_SPEED_MULTIPLIER = 8.0F;
    public static final int CUTOUT_HISTORY_PROTECTION_RADIUS = 3;

    private TemporalHistoryPolicy() {
    }

    public static float disocclusionThreshold(float expectedDepth) {
        return Math.max(ABSOLUTE_DEPTH_EPSILON, Math.abs(expectedDepth) * RELATIVE_DEPTH_EPSILON);
    }

    public static boolean isDisoccluded(float expectedDepth, float previousDepth) {
        return Math.abs(previousDepth - expectedDepth) > disocclusionThreshold(expectedDepth);
    }

    public static boolean depthMatches(float expectedDepth, float candidateDepth) {
        if (expectedDepth <= BACKGROUND_DEPTH_EPSILON) {
            return candidateDepth <= BACKGROUND_DEPTH_EPSILON;
        }
        return Math.abs(candidateDepth - expectedDepth) <= disocclusionThreshold(expectedDepth);
    }

    public static boolean hasMatchingPreviousDepth(float expectedDepth, float... candidates) {
        for (float candidate : candidates) {
            if (depthMatches(expectedDepth, candidate)) return true;
        }
        return false;
    }

    public static float currentColorBias(boolean reset, boolean invalid, boolean cutoutHistoryProtected, float requestedBias) {
        if (reset) return 1.0F;
        if (invalid) return cutoutHistoryProtected ? 0.0F : 1.0F;
        return Math.max(0.0F, Math.min(1.0F, requestedBias));
    }

    /**
     * Returns a soft history bias only for the newly revealed, farther side of
     * a moving reversed-Z edge. The caller must sample {@code occluderDepth}
     * opposite the current-to-previous motion direction.
     */
    public static float trailingDisocclusionBias(
            float motionPixelsX,
            float motionPixelsY,
            int width,
            int height,
            float revealedDepth,
            float occluderDepth
    ) {
        float motionUv = normalizedMotion(motionPixelsX, motionPixelsY, width, height);
        float scale = Math.max(ABSOLUTE_DEPTH_EPSILON, Math.max(Math.abs(revealedDepth), Math.abs(occluderDepth)));
        float relativeDepthDifference = (occluderDepth - revealedDepth) / scale;
        if (motionUv < EDGE_MOTION_UV_THRESHOLD || relativeDepthDifference < EDGE_RELATIVE_DEPTH_THRESHOLD) {
            return 0.0F;
        }
        float fullSpeed = EDGE_MOTION_UV_THRESHOLD * DISOCCLUSION_HISTORY_BIAS_FULL_SPEED_MULTIPLIER;
        float factor = Math.max(0.0F, Math.min(1.0F,
                (motionUv - EDGE_MOTION_UV_THRESHOLD) / (fullSpeed - EDGE_MOTION_UV_THRESHOLD)));
        return DISOCCLUSION_HISTORY_BIAS_MIN
                + (DISOCCLUSION_HISTORY_BIAS_MAX - DISOCCLUSION_HISTORY_BIAS_MIN) * factor;
    }

    private static float normalizedMotion(float motionPixelsX, float motionPixelsY, int width, int height) {
        return (float)Math.sqrt(
                Math.pow(motionPixelsX / Math.max(1, width), 2.0D)
                        + Math.pow(motionPixelsY / Math.max(1, height), 2.0D)
        );
    }
}
