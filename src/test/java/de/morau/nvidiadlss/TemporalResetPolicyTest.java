package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TemporalResetPolicyTest {
    @Test
    void exactMovingObjectCapacityMatrixRejectsOnlyIncompleteCoverage() {
        assertFalse(
            TemporalResetPolicy.motionObjectCapacityExceeded(1, 64)
        );
        assertFalse(
            TemporalResetPolicy.motionObjectCapacityExceeded(64, 64)
        );
        assertTrue(
            TemporalResetPolicy.motionObjectCapacityExceeded(65, 64)
        );
        assertTrue(
            TemporalResetPolicy.motionObjectCapacityExceeded(256, 64)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TemporalResetPolicy
                .motionObjectCapacityExceeded(-1, 64)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TemporalResetPolicy
                .motionObjectCapacityExceeded(1, 0)
        );
    }

    @Test
    void normalCameraMotionDoesNotLookLikeATeleportOrCut() {
        assertFalse(
            TemporalResetPolicy.cameraPositionCut(0.0D)
        );
        assertFalse(
            TemporalResetPolicy.cameraPositionCut(64.0D)
        );
        assertTrue(
            TemporalResetPolicy.cameraPositionCut(
                Math.nextUp(64.0D)
            )
        );
        assertTrue(
            TemporalResetPolicy.cameraPositionCut(Double.NaN)
        );

        assertFalse(
            TemporalResetPolicy.cameraOrientationCut(1.0F)
        );
        assertFalse(
            TemporalResetPolicy.cameraOrientationCut(0.92F)
        );
        assertTrue(
            TemporalResetPolicy.cameraOrientationCut(0.919F)
        );
        assertTrue(
            TemporalResetPolicy.cameraOrientationCut(Float.NaN)
        );
    }

    @Test
    void identityAndDeathTransitionsAreExactResetBoundaries() {
        Object first = new Object();
        Object second = new Object();
        assertFalse(
            TemporalResetPolicy.identityChanged(null, first)
        );
        assertFalse(
            TemporalResetPolicy.identityChanged(first, first)
        );
        assertTrue(
            TemporalResetPolicy.identityChanged(first, second)
        );
        assertTrue(
            TemporalResetPolicy.identityChanged(first, null)
        );

        assertFalse(
            TemporalResetPolicy.booleanStateChanged(
                false,
                false,
                true
            )
        );
        assertFalse(
            TemporalResetPolicy.booleanStateChanged(
                true,
                false,
                false
            )
        );
        assertTrue(
            TemporalResetPolicy.booleanStateChanged(
                true,
                false,
                true
            )
        );
        assertTrue(
            TemporalResetPolicy.booleanStateChanged(
                true,
                true,
                false
            )
        );
    }

    @Test
    void effectiveFovResetsOnlyOnAbruptProjectionChanges() {
        float seventyDegrees =
            TemporalResetPolicy.effectiveVerticalFovRadians(
                projectionM11(70.0F)
            );
        float gradualChange =
            TemporalResetPolicy.effectiveVerticalFovRadians(
                projectionM11(74.9F)
            );
        float abruptChange =
            TemporalResetPolicy.effectiveVerticalFovRadians(
                projectionM11(75.1F)
            );

        assertFalse(
            TemporalResetPolicy.effectiveFovCut(
                Float.NaN,
                seventyDegrees
            )
        );
        assertFalse(
            TemporalResetPolicy.effectiveFovCut(
                seventyDegrees,
                gradualChange
            )
        );
        assertTrue(
            TemporalResetPolicy.effectiveFovCut(
                seventyDegrees,
                abruptChange
            )
        );
        assertTrue(
            TemporalResetPolicy.effectiveFovCut(
                seventyDegrees,
                Float.NaN
            )
        );
        assertTrue(
            Float.isNaN(
                TemporalResetPolicy
                    .effectiveVerticalFovRadians(0.0F)
            )
        );
    }

    private static float projectionM11(float fovDegrees) {
        return 1.0F
            / (float)Math.tan(
                Math.toRadians(fovDegrees) * 0.5D
            );
    }
}
