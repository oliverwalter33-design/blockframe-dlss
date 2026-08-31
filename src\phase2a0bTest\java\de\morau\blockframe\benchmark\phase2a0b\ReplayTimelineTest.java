package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReplayTimelineTest {
    @Test
    void linearSamplingIsDeterministicAndHandlesWrappedYaw() {
        ReplayTimeline timeline = timeline(
            ReplayTimeline.Interpolation.LINEAR
        );
        MutableCameraPose first = new MutableCameraPose();
        MutableCameraPose second = new MutableCameraPose();
        timeline.sample(500_000_000L, first);
        timeline.sample(500_000_000L, second);

        assertEquals(5.0D, first.x(), 0.0D);
        assertEquals(65.0D, first.y(), 0.0D);
        assertEquals(-5.0D, first.z(), 0.0D);
        assertEquals(180.0F, first.yaw(), 0.0F);
        assertEquals(-5.0F, first.pitch(), 0.0F);
        assertEquals(75.0F, first.fov(), 0.0F);
        assertEquals(first.hash64(), second.hash64());
    }

    @Test
    void smoothstepUsesSamePoseAtSameReplayTime() {
        ReplayTimeline timeline = timeline(
            ReplayTimeline.Interpolation.SMOOTHSTEP
        );
        MutableCameraPose pose = new MutableCameraPose();
        timeline.sample(250_000_000L, pose);
        long first = pose.hash64();
        timeline.sample(250_000_000L, pose);
        assertEquals(first, pose.hash64());
        assertNotEquals(
            timeline(ReplayTimeline.Interpolation.LINEAR).hash64(),
            timeline.hash64()
        );
    }

    @Test
    void invalidKeyframeOrderFailsClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ReplayTimeline(
                    new long[]{0L, 0L},
                    new double[]{0, 1},
                    new double[]{0, 1},
                    new double[]{0, 1},
                    new float[]{0, 1},
                    new float[]{0, 1},
                    new float[]{70, 70},
                    ReplayTimeline.Interpolation.LINEAR
                )
        );
    }

    private static ReplayTimeline timeline(
        ReplayTimeline.Interpolation interpolation
    ) {
        return new ReplayTimeline(
            new long[]{0L, 1_000_000_000L},
            new double[]{0.0D, 10.0D},
            new double[]{64.0D, 66.0D},
            new double[]{0.0D, -10.0D},
            new float[]{170.0F, -170.0F},
            new float[]{0.0F, -10.0F},
            new float[]{70.0F, 80.0F},
            interpolation
        );
    }
}
