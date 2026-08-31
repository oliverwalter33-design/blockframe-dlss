package de.morau.blockframe.benchmark.phase2a0b;

import java.util.Objects;

/**
 * Pure external-launcher classification. It owns no thread and performs no
 * waiting; the launcher evaluates one cached snapshot at its global deadline.
 */
public final class ExternalReadinessDeadline {
    public enum Status {
        READY,
        WORLD_NEVER_OPENED,
        RENDER_CALLBACK_NEVER_SEEN,
        LEVEL_MISSING,
        PLAYER_MISSING,
        CAMERA_MISSING,
        WRONG_THREAD,
        OWNER_PUBLICATION_FAILED
    }

    public record Snapshot(
        boolean worldOpenRequested,
        long callbackCount,
        int readinessMask,
        long renderThreadId,
        long rejectedWrongThreadCallbacks,
        int ownerPublications
    ) {
        public Snapshot {
            if (
                callbackCount < 0L
                    || rejectedWrongThreadCallbacks < 0L
                    || ownerPublications < 0
            ) {
                throw new IllegalArgumentException(
                    "readiness counters cannot be negative"
                );
            }
        }
    }

    private ExternalReadinessDeadline() {
    }

    public static Status classify(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        int mask = snapshot.readinessMask();
        if (
            (mask & RenderReadinessState.REPLAY_ARMED) != 0
                && snapshot.ownerPublications() == 1
        ) {
            return Status.READY;
        }
        if (!snapshot.worldOpenRequested()) {
            return Status.WORLD_NEVER_OPENED;
        }
        if (snapshot.callbackCount() == 0L) {
            return Status.RENDER_CALLBACK_NEVER_SEEN;
        }
        if (
            snapshot.rejectedWrongThreadCallbacks() != 0L
                || snapshot.renderThreadId() < 0L
        ) {
            return Status.WRONG_THREAD;
        }
        if ((mask & RenderReadinessState.WORLD_PRESENT) == 0) {
            return Status.LEVEL_MISSING;
        }
        if ((mask & RenderReadinessState.PLAYER_PRESENT) == 0) {
            return Status.PLAYER_MISSING;
        }
        if ((mask & RenderReadinessState.CAMERA_READY) == 0) {
            return Status.CAMERA_MISSING;
        }
        return Status.OWNER_PUBLICATION_FAILED;
    }
}
