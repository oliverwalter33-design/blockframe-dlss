package de.morau.nvidiadlss;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Fixed-capacity render-thread transport for exact local-player model cubes.
 *
 * <p>The coarse world bounds are only an early reject. Pixel ownership is
 * decided in the shader against each cube's local bounds after applying the
 * exact current model-part matrix. All storage is allocated once.</p>
 */
final class ThirdPersonGeometryBatch {
    static final int MAX_PARTS = 96;
    static final int PART_FLOATS = 48;
    private static final int MATRIX_FLOATS = 16;
    private static final int BOUNDS_FLOATS = 6;
    private static final int RECT_FLOATS = 4;
    private static final float INVALID_RECT_MIN = 1.0F;
    private static final float INVALID_RECT_MAX = 0.0F;
    private static final Matrix4f INVERSE_SCRATCH = new Matrix4f();
    private static final Matrix4f CURRENT_VIEW_PROJECTION_SCRATCH =
        new Matrix4f();
    private static final Matrix4f CURRENT_LOCAL_TO_WORLD_SCRATCH =
        new Matrix4f();
    private static final Matrix4f PREVIOUS_LOCAL_TO_WORLD_SCRATCH =
        new Matrix4f();
    private static final Matrix4f CURRENT_CLIP_FROM_LOCAL_SCRATCH =
        new Matrix4f();
    private static final Matrix4f PREVIOUS_CLIP_FROM_LOCAL_SCRATCH =
        new Matrix4f();
    private static final Vector3f POSITION_SCRATCH = new Vector3f();
    private static final Vector4f CLIP_SCRATCH = new Vector4f();
    private static final float[] CURRENT_RECT_SCRATCH =
        new float[RECT_FLOATS];
    private static final float[] PREVIOUS_RECT_SCRATCH =
        new float[RECT_FLOATS];

    private final float[] currentWorldToLocal =
        new float[MAX_PARTS * MATRIX_FLOATS];
    private final float[] previousLocalToWorld =
        new float[MAX_PARTS * MATRIX_FLOATS];
    private final float[] localBounds =
        new float[MAX_PARTS * BOUNDS_FLOATS];
    private final float[] currentRectUv =
        new float[MAX_PARTS * RECT_FLOATS];
    private final float[] historyRejectRectUv =
        new float[MAX_PARTS * RECT_FLOATS];
    private int size;
    private float minX;
    private float minY;
    private float minZ;
    private float maxX;
    private float maxY;
    private float maxZ;
    private float historyMinU;
    private float historyMinV;
    private float historyMaxU;
    private float historyMaxV;
    private boolean overflow;

    void clear() {
        this.size = 0;
        this.minX = 0.0F;
        this.minY = 0.0F;
        this.minZ = 0.0F;
        this.maxX = 0.0F;
        this.maxY = 0.0F;
        this.maxZ = 0.0F;
        this.invalidateHistoryRect();
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
        this.invalidatePartCurrentRect(this.size);
        this.invalidatePartHistoryRect(this.size);
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
    float historyMinU() { return this.historyMinU; }
    float historyMinV() { return this.historyMinV; }
    float historyMaxU() { return this.historyMaxU; }
    float historyMaxV() { return this.historyMaxV; }

    /**
     * Builds a conservative previous-frame local-player silhouette proxy.
     *
     * <p>Each moved rendered cube contributes its exact previous rectangle,
     * expanded by one input pixel. The shader later accepts only current world
     * pixels whose reprojection lands in that rectangle. Exact articulated
     * ownership already excludes current player pixels; subtracting the
     * current projected AABB would erase real limb disocclusions whenever two
     * articulated poses have the same coarse screen rectangle.</p>
     */
    void prepareHistoryRejectRects(
        Matrix4f inverseCurrentViewProjection,
        Matrix4f previousViewProjection,
        int width,
        int height,
        boolean reset
    ) {
        this.invalidateHistoryRect();
        for (int index = 0; index < this.size; index++) {
            this.invalidatePartCurrentRect(index);
            this.invalidatePartHistoryRect(index);
        }
        if (
            reset
                || this.overflow
                || this.size == 0
                || width <= 0
                || height <= 0
                || inverseCurrentViewProjection == null
                || previousViewProjection == null
        ) {
            return;
        }

        CURRENT_VIEW_PROJECTION_SCRATCH
            .set(inverseCurrentViewProjection)
            .invert();
        if (!finite(CURRENT_VIEW_PROJECTION_SCRATCH)) {
            return;
        }

        float padU = 1.0F / width;
        float padV = 1.0F / height;
        for (int index = 0; index < this.size; index++) {
            int matrixOffset = index * MATRIX_FLOATS;
            loadMatrix(
                this.currentWorldToLocal,
                matrixOffset,
                CURRENT_LOCAL_TO_WORLD_SCRATCH
            );
            CURRENT_LOCAL_TO_WORLD_SCRATCH.invert();
            loadMatrix(
                this.previousLocalToWorld,
                matrixOffset,
                PREVIOUS_LOCAL_TO_WORLD_SCRATCH
            );
            if (
                !finite(CURRENT_LOCAL_TO_WORLD_SCRATCH)
                    || !finite(PREVIOUS_LOCAL_TO_WORLD_SCRATCH)
            ) {
                continue;
            }

            int boundsOffset = index * BOUNDS_FLOATS;
            boolean currentProjected = projectRectUv(
                CURRENT_VIEW_PROJECTION_SCRATCH,
                CURRENT_LOCAL_TO_WORLD_SCRATCH,
                this.localBounds,
                boundsOffset,
                CURRENT_CLIP_FROM_LOCAL_SCRATCH,
                CURRENT_RECT_SCRATCH
            );
            boolean previousProjected = projectRectUv(
                previousViewProjection,
                PREVIOUS_LOCAL_TO_WORLD_SCRATCH,
                this.localBounds,
                boundsOffset,
                PREVIOUS_CLIP_FROM_LOCAL_SCRATCH,
                PREVIOUS_RECT_SCRATCH
            );
            if (!currentProjected || !previousProjected) {
                continue;
            }

            boolean moved = matricesDiffer(
                CURRENT_LOCAL_TO_WORLD_SCRATCH,
                PREVIOUS_LOCAL_TO_WORLD_SCRATCH
            ) || rectsDiffer(CURRENT_RECT_SCRATCH, PREVIOUS_RECT_SCRATCH);
            if (!moved) {
                continue;
            }

            float currentMinU = Math.max(0.0F, CURRENT_RECT_SCRATCH[0]);
            float currentMinV = Math.max(0.0F, CURRENT_RECT_SCRATCH[1]);
            float currentMaxU = Math.min(1.0F, CURRENT_RECT_SCRATCH[2]);
            float currentMaxV = Math.min(1.0F, CURRENT_RECT_SCRATCH[3]);
            float minU = Math.max(0.0F, PREVIOUS_RECT_SCRATCH[0] - padU);
            float minV = Math.max(0.0F, PREVIOUS_RECT_SCRATCH[1] - padV);
            float maxU = Math.min(1.0F, PREVIOUS_RECT_SCRATCH[2] + padU);
            float maxV = Math.min(1.0F, PREVIOUS_RECT_SCRATCH[3] + padV);
            if (
                currentMinU >= currentMaxU
                    || currentMinV >= currentMaxV
                    || minU >= maxU
                    || minV >= maxV
            ) {
                continue;
            }
            int rectOffset = index * RECT_FLOATS;
            this.currentRectUv[rectOffset] = currentMinU;
            this.currentRectUv[rectOffset + 1] = currentMinV;
            this.currentRectUv[rectOffset + 2] = currentMaxU;
            this.currentRectUv[rectOffset + 3] = currentMaxV;
            this.historyRejectRectUv[rectOffset] = minU;
            this.historyRejectRectUv[rectOffset + 1] = minV;
            this.historyRejectRectUv[rectOffset + 2] = maxU;
            this.historyRejectRectUv[rectOffset + 3] = maxV;
            if (this.historyMinU > this.historyMaxU) {
                this.historyMinU = minU;
                this.historyMinV = minV;
                this.historyMaxU = maxU;
                this.historyMaxV = maxV;
            } else {
                this.historyMinU = Math.min(this.historyMinU, minU);
                this.historyMinV = Math.min(this.historyMinV, minV);
                this.historyMaxU = Math.max(this.historyMaxU, maxU);
                this.historyMaxV = Math.max(this.historyMaxV, maxV);
            }
        }
    }

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
            int rectOffset = index * RECT_FLOATS;
            bytes.putFloat(this.currentRectUv[rectOffset])
                .putFloat(this.currentRectUv[rectOffset + 1])
                .putFloat(this.currentRectUv[rectOffset + 2])
                .putFloat(this.currentRectUv[rectOffset + 3]);
            bytes.putFloat(this.historyRejectRectUv[rectOffset])
                .putFloat(this.historyRejectRectUv[rectOffset + 1])
                .putFloat(this.historyRejectRectUv[rectOffset + 2])
                .putFloat(this.historyRejectRectUv[rectOffset + 3]);
        }
    }

    float historyRejectRectComponent(int part, int component) {
        if (
            part < 0
                || part >= this.size
                || component < 0
                || component >= RECT_FLOATS
        ) {
            throw new IndexOutOfBoundsException();
        }
        return this.historyRejectRectUv[part * RECT_FLOATS + component];
    }

    float currentRectComponent(int part, int component) {
        if (
            part < 0
                || part >= this.size
                || component < 0
                || component >= RECT_FLOATS
        ) {
            throw new IndexOutOfBoundsException();
        }
        return this.currentRectUv[part * RECT_FLOATS + component];
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

    private void invalidateHistoryRect() {
        this.historyMinU = INVALID_RECT_MIN;
        this.historyMinV = INVALID_RECT_MIN;
        this.historyMaxU = INVALID_RECT_MAX;
        this.historyMaxV = INVALID_RECT_MAX;
    }

    private void invalidatePartHistoryRect(int index) {
        int offset = index * RECT_FLOATS;
        this.historyRejectRectUv[offset] = INVALID_RECT_MIN;
        this.historyRejectRectUv[offset + 1] = INVALID_RECT_MIN;
        this.historyRejectRectUv[offset + 2] = INVALID_RECT_MAX;
        this.historyRejectRectUv[offset + 3] = INVALID_RECT_MAX;
    }

    private void invalidatePartCurrentRect(int index) {
        int offset = index * RECT_FLOATS;
        this.currentRectUv[offset] = INVALID_RECT_MIN;
        this.currentRectUv[offset + 1] = INVALID_RECT_MIN;
        this.currentRectUv[offset + 2] = INVALID_RECT_MAX;
        this.currentRectUv[offset + 3] = INVALID_RECT_MAX;
    }

    private static boolean projectRectUv(
        Matrix4f viewProjection,
        Matrix4f localToWorld,
        float[] bounds,
        int boundsOffset,
        Matrix4f clipFromLocal,
        float[] target
    ) {
        clipFromLocal.set(viewProjection).mul(localToWorld);
        target[0] = Float.POSITIVE_INFINITY;
        target[1] = Float.POSITIVE_INFINITY;
        target[2] = Float.NEGATIVE_INFINITY;
        target[3] = Float.NEGATIVE_INFINITY;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    CLIP_SCRATCH.set(
                        bounds[boundsOffset + (x == 0 ? 0 : 3)],
                        bounds[boundsOffset + (y == 0 ? 1 : 4)],
                        bounds[boundsOffset + (z == 0 ? 2 : 5)],
                        1.0F
                    );
                    clipFromLocal.transform(CLIP_SCRATCH);
                    if (
                        !Float.isFinite(CLIP_SCRATCH.x)
                            || !Float.isFinite(CLIP_SCRATCH.y)
                            || !Float.isFinite(CLIP_SCRATCH.w)
                            || CLIP_SCRATCH.w <= 1.0e-5F
                    ) {
                        return false;
                    }
                    float u = CLIP_SCRATCH.x / CLIP_SCRATCH.w
                        * 0.5F + 0.5F;
                    float v = CLIP_SCRATCH.y / CLIP_SCRATCH.w
                        * 0.5F + 0.5F;
                    target[0] = Math.min(target[0], u);
                    target[1] = Math.min(target[1], v);
                    target[2] = Math.max(target[2], u);
                    target[3] = Math.max(target[3], v);
                }
            }
        }
        return target[2] > 0.0F
            && target[3] > 0.0F
            && target[0] < 1.0F
            && target[1] < 1.0F;
    }

    private static boolean matricesDiffer(Matrix4f current, Matrix4f previous) {
        return Math.abs(current.m00() - previous.m00()) > 1.0e-5F
            || Math.abs(current.m01() - previous.m01()) > 1.0e-5F
            || Math.abs(current.m02() - previous.m02()) > 1.0e-5F
            || Math.abs(current.m03() - previous.m03()) > 1.0e-5F
            || Math.abs(current.m10() - previous.m10()) > 1.0e-5F
            || Math.abs(current.m11() - previous.m11()) > 1.0e-5F
            || Math.abs(current.m12() - previous.m12()) > 1.0e-5F
            || Math.abs(current.m13() - previous.m13()) > 1.0e-5F
            || Math.abs(current.m20() - previous.m20()) > 1.0e-5F
            || Math.abs(current.m21() - previous.m21()) > 1.0e-5F
            || Math.abs(current.m22() - previous.m22()) > 1.0e-5F
            || Math.abs(current.m23() - previous.m23()) > 1.0e-5F
            || Math.abs(current.m30() - previous.m30()) > 1.0e-5F
            || Math.abs(current.m31() - previous.m31()) > 1.0e-5F
            || Math.abs(current.m32() - previous.m32()) > 1.0e-5F
            || Math.abs(current.m33() - previous.m33()) > 1.0e-5F;
    }

    private static boolean rectsDiffer(float[] current, float[] previous) {
        for (int component = 0; component < RECT_FLOATS; component++) {
            if (Math.abs(current[component] - previous[component]) > 1.0e-4F) {
                return true;
            }
        }
        return false;
    }
}
