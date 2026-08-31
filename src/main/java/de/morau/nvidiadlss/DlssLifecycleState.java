package de.morau.nvidiadlss;

/** Pure close-state decisions shared by the Vulkan lifecycle and unit tests. */
final class DlssLifecycleState {
    private DlssLifecycleState() {
    }

    static boolean allPrepareStagesComplete(
        boolean samplerPrepared,
        boolean targetPrepared,
        boolean auxiliaryPrepared,
        boolean auxiliaryRetainedItsLease,
        boolean motionPrepared
    ) {
        return samplerPrepared
            && targetPrepared
            && auxiliaryPrepared
            && !auxiliaryRetainedItsLease
            && motionPrepared;
    }

    static boolean mayCompleteGpuRetirements(
        boolean devicePrepared,
        boolean samplerFinished,
        boolean motionRawResourcesDestroyed,
        boolean streamlineClosed
    ) {
        return devicePrepared
            && samplerFinished
            && motionRawResourcesDestroyed
            && streamlineClosed;
    }

    static boolean allFinishStagesComplete(
        boolean devicePrepared,
        boolean samplerFinished,
        boolean motionRawResourcesDestroyed,
        boolean streamlineClosed,
        boolean retirementsCompleted
    ) {
        return mayCompleteGpuRetirements(
                devicePrepared,
                samplerFinished,
                motionRawResourcesDestroyed,
                streamlineClosed
            )
            && retirementsCompleted;
    }
}
