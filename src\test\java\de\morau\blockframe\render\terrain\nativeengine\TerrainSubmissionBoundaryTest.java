package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainSubmissionBoundary.FailureBoundary;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainSubmissionBoundary.Phase;
import org.junit.jupiter.api.Test;

class TerrainSubmissionBoundaryTest {
    @Test
    void oneGlobalTransitionProtectsEveryAdoptedSection() {
        TerrainSubmissionBoundary boundary = boundary();
        boundary.beginFrame(1L);

        assertEquals(
            FailureBoundary.SAME_FRAME_FALLBACK_ALLOWED,
            boundary.failureBoundary(1L)
        );
        boundary.beginNativeSubmission(1L, 10L);
        assertEquals(
            FailureBoundary.NEXT_FRAME_ONLY_NO_REPLAY,
            boundary.failureBoundary(1L)
        );
        assertEquals(10L, boundary.retirementSubmissionSerial());
        boundary.endFrame(1L);
        assertEquals(Phase.IDLE, boundary.phase());
    }

    @Test
    void frameAndSubmissionSerialsMustIncrease() {
        TerrainSubmissionBoundary boundary = boundary();
        boundary.beginFrame(2L);
        boundary.beginNativeSubmission(2L, 20L);
        boundary.endFrame(2L);

        assertThrows(
            IllegalArgumentException.class,
            () -> boundary.beginFrame(2L)
        );
        boundary.beginFrame(3L);
        assertThrows(
            IllegalArgumentException.class,
            () -> boundary.beginNativeSubmission(3L, 20L)
        );
        boundary.endFrame(3L);
    }

    @Test
    void wrongFrameCannotMoveOrQueryTheNoReplayBoundary() {
        TerrainSubmissionBoundary boundary = boundary();
        boundary.beginFrame(4L);

        assertThrows(
            IllegalArgumentException.class,
            () -> boundary.failureBoundary(5L)
        );
        assertThrows(
            IllegalStateException.class,
            () -> boundary.beginNativeSubmission(5L, 1L)
        );
        assertThrows(
            IllegalStateException.class,
            () -> boundary.endFrame(5L)
        );
        boundary.endFrame(4L);
    }

    @Test
    void closeRequiresTheLastGlobalCompletionProof() {
        TerrainSubmissionBoundary boundary = boundary();
        boundary.beginFrame(1L);
        boundary.beginNativeSubmission(1L, 30L);
        assertThrows(
            IllegalStateException.class,
            () -> boundary.closeAfterCompletion(30L)
        );
        boundary.endFrame(1L);
        assertThrows(
            IllegalStateException.class,
            () -> boundary.closeAfterCompletion(29L)
        );
        boundary.closeAfterCompletion(30L);
        assertEquals(Phase.CLOSED, boundary.phase());
        assertThrows(
            IllegalStateException.class,
            () -> boundary.beginFrame(2L)
        );
    }

    @Test
    void failureQueriesRequireAnOpenCurrentFrame() {
        TerrainSubmissionBoundary boundary = boundary();
        boundary.beginFrame(7L);
        boundary.endFrame(7L);
        assertThrows(
            IllegalArgumentException.class,
            () -> boundary.failureBoundary(7L)
        );
        boundary.closeAfterCompletion(0L);
        assertThrows(
            IllegalStateException.class,
            () -> boundary.failureBoundary(7L)
        );
    }

    private static TerrainSubmissionBoundary boundary() {
        return new TerrainSubmissionBoundary(
            new TerrainSubmissionBoundary.Generations(
                1L,
                2L,
                3L,
                4L
            )
        );
    }
}
