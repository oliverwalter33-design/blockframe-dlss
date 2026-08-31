package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldFrameStabilityTrackerTest {
    @Test
    void promotesOnlyOnTheExactBoundedWindow() {
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(120);
        Object world = new Object();

        assertEquals(
            WorldFrameStabilityTracker.Transition.FIRST_WORLD_FRAME,
            tracker.observeSuccessfulFrame(world)
        );
        for (int frame = 2; frame < 120; frame++) {
            assertEquals(
                WorldFrameStabilityTracker.Transition.NONE,
                tracker.observeSuccessfulFrame(world)
            );
        }
        assertEquals(119, tracker.consecutiveFrames());
        assertEquals(
            WorldFrameStabilityTracker.Transition
                .STABILITY_WINDOW_COMPLETE,
            tracker.observeSuccessfulFrame(world)
        );
        assertEquals(120, tracker.consecutiveFrames());
        assertEquals(
            WorldFrameStabilityTracker.Transition.NONE,
            tracker.observeSuccessfulFrame(world)
        );
        assertEquals(120, tracker.consecutiveFrames());
    }

    @Test
    void worldIdentityChangeStartsANewConsecutiveWindow() {
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(3);
        Object first = new Object();
        Object second = new Object();

        tracker.observeSuccessfulFrame(first);
        tracker.observeSuccessfulFrame(first);
        assertEquals(2, tracker.consecutiveFrames());
        assertTrue(tracker.tracksWorld(first));
        assertFalse(tracker.tracksWorld(second));
        assertEquals(
            WorldFrameStabilityTracker.Transition.FIRST_WORLD_FRAME,
            tracker.observeSuccessfulFrame(second)
        );
        assertEquals(1, tracker.consecutiveFrames());
        assertTrue(tracker.tracksWorld(second));
    }

    @Test
    void failedFrameReloadAndDeviceBoundariesResetWithoutOwnership() {
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(4);
        Object world = new Object();

        tracker.observeSuccessfulFrame(world);
        tracker.observeSuccessfulFrame(world);
        tracker.resetWindow();
        assertEquals(0, tracker.consecutiveFrames());
        assertEquals(
            WorldFrameStabilityTracker.Transition.FIRST_WORLD_FRAME,
            tracker.observeSuccessfulFrame(world)
        );

        tracker.clearWorld();
        assertEquals(0, tracker.consecutiveFrames());
        assertFalse(tracker.hasWorld());
        assertEquals(
            WorldFrameStabilityTracker.Transition.FIRST_WORLD_FRAME,
            tracker.observeSuccessfulFrame(new Object())
        );
        assertTrue(tracker.hasWorld());
    }

    @Test
    void invalidConstructionAndNullWorldFailBeforeStateChange() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorldFrameStabilityTracker(0)
        );
        WorldFrameStabilityTracker tracker =
            new WorldFrameStabilityTracker(2);
        assertThrows(
            NullPointerException.class,
            () -> tracker.observeSuccessfulFrame(null)
        );
        assertEquals(0, tracker.consecutiveFrames());
    }
}
