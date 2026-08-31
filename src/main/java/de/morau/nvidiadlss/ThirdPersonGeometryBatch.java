package de.morau.nvidiadlss;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Fixed-capacity render-thread transport for exact local-player model cubes.
 *
 * <p>The coarse world bounds are only an early reject. Pixel ownership is
 * decided in the shader against each cube's local bounds after applying the
 * exact current model-part matrix. All storage is allocated once.</p>
 */
final class ThirdPersonGeometryBatch {
    static final int MAX_PARTS = 96;
    static final int PART_FLOATS = 40;
    private static final int MATRIX_FLOATS = 16;
    private static final int BOUNDS_FLOATS = 6;
    private static final Matrix4f INVERSE_SCRATCH = new Matrix4f();
    private static final Vector3f POSITION_SCRATCH = new Vector3f();

    private final float[] currentWorldToLocal =
        new float[MAX_PARTS * MATRIX_FLOATS];
    private final float[] previousLocalToWorld =
        new float[MAX_PARTS * MATRIX_FLOATS];
    private final float[] localBounds =
        new float[MAX_PARTS * BOUNDS_FLOATS];
    private int size;
    private float minX;
    private float minY;
    private float minZ;
    private float maxX;
    private float maxY;
    private float maxZ;
    private boolean overflow;

    void clear() {
        this.size = 0;
        this.minX = 0.0F;
        this.minY = 0.0F;
        this.minZ = 0.0F;
        this.maxX = 0.0F;
        this.maxY = 0.0F;
        this.maxZ = 0.0F;
        this.overflow = false;
    }

    boolean add(
        Matrix4f currentLocalToWorld,
        Matrix4f previousPartLocalToWorld,
        float cubeMinX,
        float cubeMinY,
        float cubeMinZ,
        float cubeMaxX,
        float cubeMaxY,
        float cubeMaxZ
    ) {
        if (this.size >= MAX_PARTS) {
            this.overflow = true;
            return false;
        }
        INVERSE_SCRATCH.set(currentLocalToWorld).invert();
        if (!finite(INVERSE_SCRATCH)) {
            return false;
        }
        int matrixOffset = this.size * MATRIX_FLOATS;
        storeMatrix(
            INVERSE_SCRATCH,
            this.currentWorldToLocal,
            matrixOffset
        );
        storeMatrix(
            previousPartLocalToWorld,
            this.previousLocalToWorld,
            matrixOffset
        );
        int boundsOffset = this.size * BOUNDS_FLOATS;
        this.localBounds[boundsOffset] = cubeMinX;
        this.localBounds[boundsOffset + 1] = cubeMinY;
        this.localBounds[boundsOffset + 2] = cubeMinZ;
        this.localBounds[boundsOffset + 3] = cubeMaxX;
        this.localBounds[boundsOffset + 4] = cubeMaxY;
        this.localBounds[boundsOffset + 5] = cubeMaxZ;
        this.include(
            currentLocalToWorld,
            cubeMinX, cubeMinY, cubeMinZ,
            cubeMaxX, cubeMaxY, cubeMaxZ
        );
        this.size++;
        return true;
    }

    void markOverflow() {
        this.overflow = true;
    }

    int size() {
        return this.size;
    }

    boolean overflow() {
        return this.overflow;
    }

    float minX() { return this.minX; }
    float minY() { return this.minY; }
    float minZ() { return this.minZ; }
    float maxX() { return this.maxX; }
    float maxY() { return this.maxY; }
    float maxZ() { return this.maxZ; }

    void writeParts(ByteBuffer bytes) {
        for (int index = 0; index < this.size; index++) {
            int matrixOffset = index * MATRIX_FLOATS;
            putMatrix(bytes, this.currentWorldToLocal, matrixOffset);
            putMatrix(bytes, this.previousLocalToWorld, matrixOffset);
            int boundsOffset = index * BOUNDS_FLOATS;
            bytes.putFloat(this.localBounds[boundsOffset])
                .putFloat(this.localBounds[boundsOffset + 1])
                .putFloat(this.localBounds[boundsOffset + 2])
                .putFloat(0.0F);
            bytes.putFloat(this.localBounds[boundsOffset + 3])
                .putFloat(this.localBounds[boundsOffset + 4])
                .putFloat(this.localBounds[boundsOffset + 5])
                .putFloat(0.0F);
        }
    }

    private void include(
        Matrix4f localToWorld,
        float cubeMinX,
        float cubeMinY,
        float cubeMinZ,
        float cubeMaxX,
        float cubeMaxY,
        float cubeMaxZ
    ) {
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    localToWorld.transformPosition(
                        x == 0 ? cubeMinX : cubeMaxX,
                        y == 0 ? cubeMinY : cubeMaxY,
                        z == 0 ? cubeMinZ : cubeMaxZ,
                        POSITION_SCRATCH
                    );
                    if (this.size == 0 && x == 0 && y == 0 && z == 0) {
                        this.minX = POSITION_SCRATCH.x;
                        this.minY = POSITION_SCRATCH.y;
                        this.minZ = POSITION_SCRATCH.z;
                        this.maxX = POSITION_SCRATCH.x;
                        this.maxY = POSITION_SCRATCH.y;
                        this.maxZ = POSITION_SCRATCH.z;
                    } else {
                        this.minX = Math.min(this.minX, POSITION_SCRATCH.x);
                        this.minY = Math.min(this.minY, POSITION_SCRATCH.y);
                        this.minZ = Math.min(this.minZ, POSITION_SCRATCH.z);
                        this.maxX = Math.max(this.maxX, POSITION_SCRATCH.x);
                        this.maxY = Math.max(this.maxY, POSITION_SCRATCH.y);
                        this.maxZ = Math.max(this.maxZ, POSITION_SCRATCH.z);
                    }
                }
            }
        }
    }

    private static void putMatrix(
        ByteBuffer bytes,
        float[] values,
        int offset
    ) {
        for (int index = 0; index < MATRIX_FLOATS; index++) {
            bytes.putFloat(values[offset + index]);
        }
    }

    static void storeMatrix(Matrix4f matrix, float[] target, int offset) {
        target[offset] = matrix.m00();
        target[offset + 1] = matrix.m01();
        target[offset + 2] = matrix.m02();
        target[offset + 3] = matrix.m03();
        target[offset + 4] = matrix.m10();
        target[offset + 5] = matrix.m11();
        target[offset + 6] = matrix.m12();
        target[offset + 7] = matrix.m13();
        target[offset + 8] = matrix.m20();
        target[offset + 9] = matrix.m21();
        target[offset + 10] = matrix.m22();
        target[offset + 11] = matrix.m23();
        target[offset + 12] = matrix.m30();
        target[offset + 13] = matrix.m31();
        target[offset + 14] = matrix.m32();
        target[offset + 15] = matrix.m33();
    }

    static void loadMatrix(float[] source, int offset, Matrix4f target) {
        target.m00(source[offset]);
        target.m01(source[offset + 1]);
        target.m02(source[offset + 2]);
        target.m03(source[offset + 3]);
        target.m10(source[offset + 4]);
        target.m11(source[offset + 5]);
        target.m12(source[offset + 6]);
        target.m13(source[offset + 7]);
        target.m20(source[offset + 8]);
        target.m21(source[offset + 9]);
        target.m22(source[offset + 10]);
        target.m23(source[offset + 11]);
        target.m30(source[offset + 12]);
        target.m31(source[offset + 13]);
        target.m32(source[offset + 14]);
        target.m33(source[offset + 15]);
    }

    private static boolean finite(Matrix4f matrix) {
        return Float.isFinite(matrix.m00()) && Float.isFinite(matrix.m01())
            && Float.isFinite(matrix.m02()) && Float.isFinite(matrix.m03())
            && Float.isFinite(matrix.m10()) && Float.isFinite(matrix.m11())
            && Float.isFinite(matrix.m12()) && Float.isFinite(matrix.m13())
            && Float.isFinite(matrix.m20()) && Float.isFinite(matrix.m21())
            && Float.isFinite(matrix.m22()) && Float.isFinite(matrix.m23())
            && Float.isFinite(matrix.m30()) && Float.isFinite(matrix.m31())
            && Float.isFinite(matrix.m32()) && Float.isFinite(matrix.m33());
    }
}
