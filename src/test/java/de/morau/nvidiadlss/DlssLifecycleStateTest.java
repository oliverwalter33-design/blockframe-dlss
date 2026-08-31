package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DlssLifecycleStateTest {
    @Test
    void retainedAuxiliaryLeasePreventsPreparedGeneration() {
        assertFalse(
            DlssLifecycleState.allPrepareStagesComplete(
                true,
                true,
                true,
                true,
                true
            )
        );
        assertTrue(
            DlssLifecycleState.allPrepareStagesComplete(
                true,
                true,
                true,
                false,
                true
            )
        );
    }

    @Test
    void rawDestroyFailurePreventsGlobalRetirementCompletion() {
        assertFalse(
            DlssLifecycleState.mayCompleteGpuRetirements(
                true,
                true,
                false,
                true
            )
        );
        assertFalse(
            DlssLifecycleState.allFinishStagesComplete(
                true,
                true,
                false,
                true,
                true
            )
        );
        assertTrue(
            DlssLifecycleState.mayCompleteGpuRetirements(
                true,
                true,
                true,
                true
            )
        );
    }

    @Test
    void everyFinishStageMustBeConfirmed() {
        assertFalse(
            DlssLifecycleState.allFinishStagesComplete(
                false,
                true,
                true,
                true,
                true
            )
        );
        assertFalse(
            DlssLifecycleState.allFinishStagesComplete(
                true,
                false,
                true,
                true,
                true
            )
        );
        assertFalse(
            DlssLifecycleState.allFinishStagesComplete(
                true,
                true,
                false,
                true,
                true
            )
        );
        assertFalse(
            DlssLifecycleState.allFinishStagesComplete(
                true,
                true,
                true,
                false,
                true
            )
        );
        assertFalse(
            DlssLifecycleState.allFinishStagesComplete(
                true,
                true,
                true,
                true,
                false
            )
        );
        assertTrue(
            DlssLifecycleState.allFinishStagesComplete(
                true,
                true,
                true,
                true,
                true
            )
        );
    }
}
