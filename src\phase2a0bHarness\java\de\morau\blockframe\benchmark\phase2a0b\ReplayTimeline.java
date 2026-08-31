package de.morau.blockframe.benchmark.phase2a0b;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable primitive keyframe storage with allocation-free sampling.
 */
public final class ReplayTimeline {
    public enum Interpolation {
        LINEAR,
        SMOOTHSTEP
    }

    private final long[] timeNanos;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final float[] yaw;
    private final float[] pitch;
    private final float[] fov;
    private final Interpolation interpolation;
    private final long hash64;

    public ReplayTimeline(
        long[] timeNanos,
        double[] x,
        double[] y,
        double[] z,
        float[] yaw,
        float[] pitch,
        float[] fov,
        Interpolation interpolation
    ) {
        int count = Objects.requireNonNull(timeNanos, "timeNanos").length;
        requireLength(count, x, "x");
        requireLength(count, y, "y");
        requireLength(count, z, "z");
        requireLength(count, yaw, "yaw");
        requireLength(count, pitch, "pitch");
        requireLength(count, fov, "fov");
        if (count == 0) {
            throw new IllegalArgumentException("at least one keyframe is required");
        }
        long previous = -1L;
        for (long time : timeNanos) {
            if (time < 0L || time <= previous) {
                throw new IllegalArgumentException(
                    "keyframe times must be non-negative and strictly increasing"
                );
            }
            previous = time;
        }
        this.timeNanos = Arrays.copyOf(timeNanos, count);
        this.x = Arrays.copyOf(x, count);
        this.y = Arrays.copyOf(y, count);
        this.z = Arrays.copyOf(z, count);
        this.yaw = Arrays.copyOf(yaw, count);
        this.pitch = Arrays.copyOf(pitch, count);
        this.fov = Arrays.copyOf(fov, count);
        this.interpolation = Objects.requireNonNull(
            interpolation,
            "interpolation"
        );
        this.hash64 = this.computeHash64();
    }

    public long durationNanos() {
        return this.timeNanos[this.timeNanos.length - 1];
    }

    public int keyframeCount() {
        return this.timeNanos.length;
    }

    public long hash64() {
        return this.hash64;
    }

    /**
     * Samples only primitive arrays and the caller-owned output object.
     */
    public void sample(long replayNanos, MutableCameraPose output) {
        Objects.requireNonNull(output, "output");
        if (replayNanos <= this.timeNanos[0]) {
            this.copyPose(0, output);
            return;
        }
        int last = this.timeNanos.length - 1;
        if (replayNanos >= this.timeNanos[last]) {
            this.copyPose(last, output);
            return;
        }
        int lower = Arrays.binarySearch(this.timeNanos, replayNanos);
        if (lower >= 0) {
            this.copyPose(lower, output);
            return;
        }
        int upper = -lower - 1;
        lower = upper - 1;
        double alpha = (double)(replayNanos - this.timeNanos[lower])
            / (double)(this.timeNanos[upper] - this.timeNanos[lower]);
        if (this.interpolation == Interpolation.SMOOTHSTEP) {
            alpha = alpha * alpha * (3.0D - 2.0D * alpha);
        }
        output.set(
            lerp(this.x[lower], this.x[upper], alpha),
            lerp(this.y[lower], this.y[upper], alpha),
            lerp(this.z[lower], this.z[upper], alpha),
            lerpAngle(this.yaw[lower], this.yaw[upper], alpha),
            (float)lerp(this.pitch[lower], this.pitch[upper], alpha),
            (float)lerp(this.fov[lower], this.fov[upper], alpha)
        );
    }

    private void copyPose(int index, MutableCameraPose output) {
        output.set(
            this.x[index],
            this.y[index],
            this.z[index],
            this.yaw[index],
            this.pitch[index],
            this.fov[index]
        );
    }

    private long computeHash64() {
        MutableCameraPose pose = new MutableCameraPose();
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < this.timeNanos.length; index++) {
            this.copyPose(index, pose);
            hash ^= this.timeNanos[index];
            hash *= 0x100000001b3L;
            hash ^= pose.hash64();
            hash *= 0x100000001b3L;
        }
        hash ^= this.interpolation.ordinal();
        return hash * 0x100000001b3L;
    }

    private static double lerp(double start, double end, double alpha) {
        return start + (end - start) * alpha;
    }

    private static float lerpAngle(float start, float end, double alpha) {
        float delta = (end - start) % 360.0F;
        if (delta < -180.0F) {
            delta += 360.0F;
        } else if (delta >= 180.0F) {
            delta -= 360.0F;
        }
        return (float)(start + delta * alpha);
    }

    private static void requireLength(int expected, Object array, String name) {
        Objects.requireNonNull(array, name);
        int actual = java.lang.reflect.Array.getLength(array);
        if (actual != expected) {
            throw new IllegalArgumentException(
                name + " length " + actual + " != " + expected
            );
        }
    }
}
