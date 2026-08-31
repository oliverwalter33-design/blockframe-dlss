package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExternalReadinessDeadlineTest {
    @Test
    void classifiesEveryMissingReadinessComponent() {
        assertStatus(
            ExternalReadinessDeadline.Status.WORLD_NEVER_OPENED,
            false,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN,
            4L,
            0L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.RENDER_CALLBACK_NEVER_SEEN,
            true,
            0L,
            0,
            -1L,
            0L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.LEVEL_MISSING,
            true,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN,
            4L,
            0L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.PLAYER_MISSING,
            true,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN
                | RenderReadinessState.WORLD_PRESENT,
            4L,
            0L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.CAMERA_MISSING,
            true,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN
                | RenderReadinessState.WORLD_PRESENT
                | RenderReadinessState.PLAYER_PRESENT,
            4L,
            0L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.WRONG_THREAD,
            true,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN,
            4L,
            1L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.OWNER_PUBLICATION_FAILED,
            true,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN
                | RenderReadinessState.WORLD_PRESENT
                | RenderReadinessState.PLAYER_PRESENT
                | RenderReadinessState.CAMERA_READY
                | RenderReadinessState.RENDER_OWNER_BOUND,
            4L,
            0L,
            0
        );
        assertStatus(
            ExternalReadinessDeadline.Status.READY,
            true,
            10L,
            RenderReadinessState.CLIENT_RENDER_CALLBACK_SEEN
                | RenderReadinessState.WORLD_PRESENT
                | RenderReadinessState.PLAYER_PRESENT
                | RenderReadinessState.CAMERA_READY
                | RenderReadinessState.RENDER_OWNER_BOUND
                | RenderReadinessState.REPLAY_ARMED,
            4L,
            0L,
            1
        );
    }

    private static void assertStatus(
        ExternalReadinessDeadline.Status expected,
        boolean worldOpenRequested,
        long callbackCount,
        int mask,
        long renderThreadId,
        long wrongThreadCallbacks,
        int owners
    ) {
        assertEquals(
            expected,
            ExternalReadinessDeadline.classify(
                new ExternalReadinessDeadline.Snapshot(
                    worldOpenRequested,
                    callbackCount,
                    mask,
                    renderThreadId,
                    wrongThreadCallbacks,
                    owners
                )
            )
        );
    }
}
