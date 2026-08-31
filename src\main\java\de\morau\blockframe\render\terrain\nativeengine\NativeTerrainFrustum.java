package de.morau.blockframe.render.terrain.nativeengine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.joml.Matrix4fc;

/**
 * Reusable Vulkan zero-to-one clip-space frustum constants.
 *
 * <p>The near plane is row Z and the far plane is row W minus row Z. This is
 * correct for Minecraft 26.2's Vulkan projection even when its depth mapping
 * is reversed; no OpenGL minus-one-to-one near-plane assumption is used.
 * Camera offsets use Minecraft Globals exactly:
 * {@code floor(cameraPosition) - cameraPosition}. Normalized plane W values
 * include the conservative epsilon, so CPU and GPU consume one identical
 * culling contract without another push-constant field.</p>
 */
public final class NativeTerrainFrustum {
    public static final int PLANE_COUNT = 6;
    public static final int FLOATS_PER_PLANE = 4;
    public static final int PUSH_CONSTANT_BYTES = 128;
    public static final float DEFAULT_CONSERVATIVE_EPSILON =
        1.0F / 1024.0F;

    private final float[] planes =
        new float[PLANE_COUNT * FLOATS_PER_PLANE];
    private int cameraBlockX;
    private int cameraBlockY;
    private int cameraBlockZ;
    private float cameraOffsetX;
    private float cameraOffsetY;
    private float cameraOffsetZ;
    private int sceneCount;
    private int commandCapacity;
    private boolean initialized;

    public void update(
        Matrix4fc viewProjection,
        int cameraBlockX,
        int cameraBlockY,
        int cameraBlockZ,
        float cameraOffsetX,
        float cameraOffsetY,
        float cameraOffsetZ,
        int sceneCount,
        int commandCapacity,
        float epsilon
    ) {
        /*
         * viewProjection is exactly the frame's ProjMat * ModelViewMat for
         * camera-relative terrain coordinates. It must not contain another
         * world-to-camera translation, and its jitter state must match the
         * matrix used by the terrain draw in this frame.
         */
        Objects.requireNonNull(viewProjection, "viewProjection");
        if (
            !Float.isFinite(cameraOffsetX)
                || !Float.isFinite(cameraOffsetY)
                || !Float.isFinite(cameraOffsetZ)
                || !isMojangCameraOffset(cameraOffsetX)
                || !isMojangCameraOffset(cameraOffsetY)
                || !isMojangCameraOffset(cameraOffsetZ)
                || sceneCount < 0
                || commandCapacity <= 0
                || sceneCount > commandCapacity
                || !Float.isFinite(epsilon)
                || epsilon < 0.0F
        ) {
            throw new IllegalArgumentException(
                "invalid native frustum constants"
            );
        }
        setPlane(
            0,
            viewProjection.m03() + viewProjection.m00(),
            viewProjection.m13() + viewProjection.m10(),
            viewProjection.m23() + viewProjection.m20(),
            viewProjection.m33() + viewProjection.m30()
        );
        setPlane(
            1,
            viewProjection.m03() - viewProjection.m00(),
            viewProjection.m13() - viewProjection.m10(),
            viewProjection.m23() - viewProjection.m20(),
            viewProjection.m33() - viewProjection.m30()
        );
        setPlane(
            2,
            viewProjection.m03() + viewProjection.m01(),
            viewProjection.m13() + viewProjection.m11(),
            viewProjection.m23() + viewProjection.m21(),
            viewProjection.m33() + viewProjection.m31()
        );
        setPlane(
            3,
            viewProjection.m03() - viewProjection.m01(),
            viewProjection.m13() - viewProjection.m11(),
            viewProjection.m23() - viewProjection.m21(),
            viewProjection.m33() - viewProjection.m31()
        );
        /*
         * Vulkan clip volume: 0 <= z <= w. Reversed-Z changes which physical
         * distance maps to either boundary, not these homogeneous halfspaces.
         */
        setPlane(
            4,
            viewProjection.m02(),
            viewProjection.m12(),
            viewProjection.m22(),
            viewProjection.m32()
        );
        setPlane(
            5,
            viewProjection.m03() - viewProjection.m02(),
            viewProjection.m13() - viewProjection.m12(),
            viewProjection.m23() - viewProjection.m22(),
            viewProjection.m33() - viewProjection.m32()
        );
        for (int plane = 0; plane < PLANE_COUNT; plane++) {
            this.planes[
                plane * FLOATS_PER_PLANE + 3
            ] += epsilon;
        }
        this.cameraBlockX = cameraBlockX;
        this.cameraBlockY = cameraBlockY;
        this.cameraBlockZ = cameraBlockZ;
        this.cameraOffsetX = cameraOffsetX;
        this.cameraOffsetY = cameraOffsetY;
        this.cameraOffsetZ = cameraOffsetZ;
        this.sceneCount = sceneCount;
        this.commandCapacity = commandCapacity;
        this.initialized = true;
    }

    public boolean intersects(
        int sectionX,
        int sectionY,
        int sectionZ,
        TerrainMeshProducerABI.Bounds localBounds
    ) {
        requireInitialized();
        Objects.requireNonNull(localBounds, "localBounds");
        float minimumX =
            (float)((long)sectionX - this.cameraBlockX)
                + localBounds.minimumX()
                + this.cameraOffsetX;
        float minimumY =
            (float)((long)sectionY - this.cameraBlockY)
                + localBounds.minimumY()
                + this.cameraOffsetY;
        float minimumZ =
            (float)((long)sectionZ - this.cameraBlockZ)
                + localBounds.minimumZ()
                + this.cameraOffsetZ;
        float maximumX =
            (float)((long)sectionX - this.cameraBlockX)
                + localBounds.maximumX()
                + this.cameraOffsetX;
        float maximumY =
            (float)((long)sectionY - this.cameraBlockY)
                + localBounds.maximumY()
                + this.cameraOffsetY;
        float maximumZ =
            (float)((long)sectionZ - this.cameraBlockZ)
                + localBounds.maximumZ()
                + this.cameraOffsetZ;
        for (int plane = 0; plane < PLANE_COUNT; plane++) {
            int offset = plane * FLOATS_PER_PLANE;
            float a = this.planes[offset];
            float b = this.planes[offset + 1];
            float c = this.planes[offset + 2];
            float x = a >= 0.0F ? maximumX : minimumX;
            float y = b >= 0.0F ? maximumY : minimumY;
            float z = c >= 0.0F ? maximumZ : minimumZ;
            if (
                a * x
                        + b * y
                        + c * z
                        + this.planes[offset + 3]
                    < 0.0F
            ) {
                return false;
            }
        }
        return true;
    }

    public void writePushConstants(ByteBuffer destination) {
        requireInitialized();
        if (destination.remaining() != PUSH_CONSTANT_BYTES) {
            throw new IllegalArgumentException(
                "native frustum push constant range must be 128 bytes"
            );
        }
        destination.order(ByteOrder.LITTLE_ENDIAN);
        for (float plane : this.planes) {
            destination.putFloat(plane);
        }
        destination.putInt(this.cameraBlockX);
        destination.putInt(this.cameraBlockY);
        destination.putInt(this.cameraBlockZ);
        destination.putInt(this.sceneCount);
        destination.putFloat(this.cameraOffsetX);
        destination.putFloat(this.cameraOffsetY);
        destination.putFloat(this.cameraOffsetZ);
        destination.putInt(this.commandCapacity);
    }

    public int sceneCount() {
        requireInitialized();
        return this.sceneCount;
    }

    private void setPlane(
        int plane,
        float a,
        float b,
        float c,
        float d
    ) {
        double length = Math.sqrt(
            (double)a * a + (double)b * b + (double)c * c
        );
        if (!Double.isFinite(length) || length == 0.0D) {
            throw new IllegalArgumentException(
                "projection produced a degenerate frustum plane"
            );
        }
        float inverseLength = (float)(1.0D / length);
        int offset = plane * FLOATS_PER_PLANE;
        this.planes[offset] = a * inverseLength;
        this.planes[offset + 1] = b * inverseLength;
        this.planes[offset + 2] = c * inverseLength;
        this.planes[offset + 3] = d * inverseLength;
    }

    private void requireInitialized() {
        if (!this.initialized) {
            throw new IllegalStateException(
                "native frustum constants are uninitialized"
            );
        }
    }

    private static boolean isMojangCameraOffset(float value) {
        return value >= -1.0F && value <= 0.0F;
    }
}
