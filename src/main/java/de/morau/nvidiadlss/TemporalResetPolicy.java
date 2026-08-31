package de.morau.nvidiadlss;

/**
 * Allocation-free discontinuity policy for temporal reconstruction.
 *
 * <p>The renderer owns the observed state. This class only classifies exact
 * transitions so boundary behavior can be tested without a running client.</p>
 */
final class TemporalResetPolicy {
    static final double CAMERA_TELEPORT_DISTANCE_SQUARED = 64.0D;
    static final float CAMERA_CUT_DOT_THRESHOLD = 0.92F;
    static final float EFFECTIVE_FOV_CUT_RADIANS =
        (float)Math.toRadians(5.0D);

    private TemporalResetPolicy() {
    }

    static boolean motionObjectCapacityExceeded(
        int observedMovingObjects,
        int encodedCapacity
    ) {
        if (observedMovingObjects < 0) {
            throw new IllegalArgumentException(
                "observed moving-object count must not be negative"
            );
        }
        if (encodedCapacity <= 0) {
            throw new IllegalArgumentException(
                "encoded moving-object capacity must be positive"
            );
        }
        return observedMovingObjects > encodedCapacity;
    }

    static boolean cameraPositionCut(double squaredDistance) {
        return !Double.isFinite(squaredDistance)
            || squaredDistance > CAMERA_TELEPORT_DISTANCE_SQUARED;
    }

    static boolean cameraOrientationCut(float orientationDot) {
        return !Float.isFinite(orientationDot)
            || Math.abs(orientationDot) < CAMERA_CUT_DOT_THRESHOLD;
    }

    static boolean identityChanged(Object previous, Object current) {
        return previous != null && previous != current;
    }

    static boolean booleanStateChanged(
        boolean previousStateValid,
        boolean previousState,
        boolean currentState
    ) {
        return previousStateValid && previousState != currentState;
    }

    static float effectiveVerticalFovRadians(float projectionM11) {
        float magnitude = Math.abs(projectionM11);
        if (!Float.isFinite(magnitude) || magnitude <= 1.0E-6F) {
            return Float.NaN;
        }
        float fov =
            2.0F * (float)Math.atan(1.0F / magnitude);
        return Float.isFinite(fov) && fov > 0.0F
            ? fov
            : Float.NaN;
    }

    static boolean effectiveFovCut(
        float previousFovRadians,
        float currentFovRadians
    ) {
        if (!Float.isFinite(currentFovRadians)) {
            return true;
        }
        return Float.isFinite(previousFovRadians)
            && Math.abs(currentFovRadians - previousFovRadians)
                > EFFECTIVE_FOV_CUT_RADIANS;
    }
}
