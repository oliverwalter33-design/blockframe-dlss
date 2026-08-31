package de.morau.blockframe.benchmark.phase2a0b;

/**
 * Reused replay output. Sampling mutates this object instead of allocating
 * one pose per rendered frame.
 */
public final class MutableCameraPose {
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private float fov;

    public void set(
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov
    ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.fov = fov;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public float fov() {
        return this.fov;
    }

    public long hash64() {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, Double.doubleToLongBits(this.x));
        hash = mix(hash, Double.doubleToLongBits(this.y));
        hash = mix(hash, Double.doubleToLongBits(this.z));
        hash = mix(hash, Float.floatToIntBits(this.yaw));
        hash = mix(hash, Float.floatToIntBits(this.pitch));
        return mix(hash, Float.floatToIntBits(this.fov));
    }

    private static long mix(long hash, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash ^= (value >>> shift) & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
