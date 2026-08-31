package de.morau.nvidiadlss;

import com.mojang.blaze3d.vertex.PoseStack;
import de.morau.nvidiadlss.mixin.accessor.ModelPartAccessor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Captures exact current/previous local-player model cube transforms. */
public final class ThirdPersonGeometryMotion {
    private static final int MAX_CAPTURED_CUBES = 256;
    private static final int MAX_KNOWN_MODELS = 64;
    private static final int MAX_KNOWN_CUBES = 1024;
    private static final int MATRIX_FLOATS = 16;
    private static final int BOUNDS_FLOATS = 6;
    private static final Model<?>[] KNOWN_MODELS =
        new Model<?>[MAX_KNOWN_MODELS];
    private static final ModelPart.Cube[] KNOWN_CUBES =
        new ModelPart.Cube[MAX_KNOWN_CUBES];
    private static final Model<?>[] CAPTURED_MODELS =
        new Model<?>[MAX_KNOWN_MODELS];
    private static long[] currentKeys = new long[MAX_CAPTURED_CUBES];
    private static long[] previousKeys = new long[MAX_CAPTURED_CUBES];
    private static float[] currentMatrices =
        new float[MAX_CAPTURED_CUBES * MATRIX_FLOATS];
    private static float[] previousMatrices =
        new float[MAX_CAPTURED_CUBES * MATRIX_FLOATS];
    private static float[] currentBounds =
        new float[MAX_CAPTURED_CUBES * BOUNDS_FLOATS];
    private static float[] previousBounds =
        new float[MAX_CAPTURED_CUBES * BOUNDS_FLOATS];
    private static final ThirdPersonGeometryBatch DISPATCH_BATCH =
        new ThirdPersonGeometryBatch();
    private static final PoseStack WALKER = new PoseStack();
    private static final Matrix4f WORLD_BASE = new Matrix4f();
    private static final Matrix4f CURRENT_MATRIX = new Matrix4f();
    private static final Matrix4f PREVIOUS_MATRIX = new Matrix4f();
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final BiConsumer<String, ModelPart> VISIT_CHILD =
        (ignored, child) -> visitPart(child);
    private static int knownModelCount;
    private static int knownCubeCount;
    private static int capturedModelCount;
    private static int currentCount;
    private static int previousCount;
    private static int visitingModelId;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static long frameId = -1L;
    private static boolean recording;
    private static boolean captureOverflow;
    private static boolean snapshotPending;

    private ThirdPersonGeometryMotion() {}

    public static void beginFrame(
        long newFrameId,
        double newCameraX,
        double newCameraY,
        double newCameraZ
    ) {
        if (snapshotPending) {
            discardFailedFrame();
        }
        if (
            DeveloperDiagnostics.ENABLED
                && !ThirdPersonMotionAudit.exactArticulatedGeometry()
        ) {
            suspend();
            return;
        }
        frameId = newFrameId;
        cameraX = newCameraX;
        cameraY = newCameraY;
        cameraZ = newCameraZ;
        currentCount = 0;
        capturedModelCount = 0;
        DISPATCH_BATCH.clear();
        captureOverflow = false;
        recording = true;
    }

    public static void suspend() {
        recording = false;
        currentCount = 0;
        capturedModelCount = 0;
        DISPATCH_BATCH.clear();
        captureOverflow = false;
        snapshotPending = false;
    }

    public static void resetHistory() {
        previousCount = 0;
        currentCount = 0;
        capturedModelCount = 0;
        Arrays.fill(KNOWN_MODELS, null);
        Arrays.fill(KNOWN_CUBES, null);
        knownModelCount = 0;
        knownCubeCount = 0;
        DISPATCH_BATCH.clear();
        recording = false;
        captureOverflow = false;
        snapshotPending = false;
    }

    /** Called after Mojang has run setupAnim and immediately before drawing. */
    public static void capture(
        Model<?> model,
        AvatarRenderState state,
        PoseStack.Pose submittedPose
    ) {
        if (
            (DeveloperDiagnostics.ENABLED
                && !ThirdPersonMotionAudit.exactArticulatedGeometry())
                || !recording
                || captureOverflow
        ) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (
            minecraft.player == null
                || state.id != minecraft.player.getId()
                || minecraft.options.getCameraType().isFirstPerson()
                || alreadyCaptured(model)
        ) {
            return;
        }
        int modelId = modelId(model);
        if (modelId < 0 || capturedModelCount >= CAPTURED_MODELS.length) {
            captureOverflow = true;
            return;
        }
        CAPTURED_MODELS[capturedModelCount++] = model;
        visitingModelId = modelId;
        WORLD_BASE.translation((float)cameraX, (float)cameraY, (float)cameraZ)
            .mul(submittedPose.pose());
        WALKER.last().pose().set(WORLD_BASE);
        WALKER.last().normal().set(submittedPose.normal());
        visitPart(model.root());
    }

    /** Freezes this frame without publishing its geometry as history. */
    public static ThirdPersonGeometryBatch freezeForDispatch() {
        if (snapshotPending) {
            throw new IllegalStateException(
                "third-person geometry already has a pending snapshot"
            );
        }
        recording = false;
        DISPATCH_BATCH.clear();
        int matched = 0;
        for (int currentIndex = 0; currentIndex < currentCount; currentIndex++) {
            int previousIndex = previousIndex(currentKeys[currentIndex]);
            if (previousIndex < 0) {
                continue;
            }
            matched++;
            if (DISPATCH_BATCH.size() >= ThirdPersonGeometryBatch.MAX_PARTS) {
                continue;
            }
            int currentMatrixOffset = currentIndex * MATRIX_FLOATS;
            int previousMatrixOffset = previousIndex * MATRIX_FLOATS;
            ThirdPersonGeometryBatch.loadMatrix(
                currentMatrices,
                currentMatrixOffset,
                CURRENT_MATRIX
            );
            ThirdPersonGeometryBatch.loadMatrix(
                previousMatrices,
                previousMatrixOffset,
                PREVIOUS_MATRIX
            );
            int boundsOffset = currentIndex * BOUNDS_FLOATS;
            DISPATCH_BATCH.add(
                CURRENT_MATRIX,
                PREVIOUS_MATRIX,
                currentBounds[boundsOffset],
                currentBounds[boundsOffset + 1],
                currentBounds[boundsOffset + 2],
                currentBounds[boundsOffset + 3],
                currentBounds[boundsOffset + 4],
                currentBounds[boundsOffset + 5]
            );
        }
        if (
            captureOverflow
                || matched > ThirdPersonGeometryBatch.MAX_PARTS
        ) {
            DISPATCH_BATCH.markOverflow();
        }
        snapshotPending = true;
        return DISPATCH_BATCH;
    }

    /** Publishes the frozen geometry only after DLSS accepted the frame. */
    public static void commitSuccessfulFrame() {
        if (!snapshotPending) {
            return;
        }
        swapFrameStorage();
        capturedModelCount = 0;
        captureOverflow = false;
        snapshotPending = false;
    }

    /** Drops a failed frame while preserving the last successful history. */
    public static void discardFailedFrame() {
        recording = false;
        currentCount = 0;
        capturedModelCount = 0;
        captureOverflow = false;
        snapshotPending = false;
        DISPATCH_BATCH.clear();
    }

    static ThirdPersonGeometryBatch dispatchBatch() {
        return DISPATCH_BATCH;
    }

    static boolean hasReadyGeometry() {
        return DISPATCH_BATCH.size() > 0 && !DISPATCH_BATCH.overflow();
    }

    static String metadataJson() {
        return String.format(
            java.util.Locale.ROOT,
            "{\"frameId\":%d,\"mode\":\"exact-model-cubes\",\"parts\":%d,\"overflow\":%s,"
                + "\"allocationPolicy\":\"fixed-slots\"}",
            frameId,
            DISPATCH_BATCH.size(),
            DISPATCH_BATCH.overflow()
        );
    }

    private static void visitPart(ModelPart part) {
        if (!part.visible || captureOverflow) {
            return;
        }
        ModelPartAccessor accessor = (ModelPartAccessor)(Object)part;
        List<ModelPart.Cube> cubes = accessor.nvidiaDlss$cubes();
        Map<String, ModelPart> children = accessor.nvidiaDlss$children();
        if (cubes.isEmpty() && children.isEmpty()) {
            return;
        }
        WALKER.pushPose();
        translateAndRotate(part);
        if (!part.skipDraw) {
            PoseStack.Pose pose = WALKER.last();
            for (int index = 0; index < cubes.size(); index++) {
                captureCube(pose, cubes.get(index));
            }
        }
        children.forEach(VISIT_CHILD);
        WALKER.popPose();
    }

    private static void translateAndRotate(ModelPart part) {
        WALKER.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
            ROTATION.rotationZYX(part.zRot, part.yRot, part.xRot);
            WALKER.mulPose(ROTATION);
        }
        if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
            WALKER.scale(part.xScale, part.yScale, part.zScale);
        }
    }

    private static void captureCube(
        PoseStack.Pose pose,
        ModelPart.Cube cube
    ) {
        if (currentCount >= MAX_CAPTURED_CUBES) {
            captureOverflow = true;
            return;
        }
        int cubeId = cubeId(cube);
        if (cubeId < 0) {
            captureOverflow = true;
            return;
        }
        currentKeys[currentCount] = ((long)visitingModelId << 32)
            | (cubeId & 0xffffffffL);
        ThirdPersonGeometryBatch.storeMatrix(
            pose.pose(),
            currentMatrices,
            currentCount * MATRIX_FLOATS
        );
        int boundsOffset = currentCount * BOUNDS_FLOATS;
        currentBounds[boundsOffset] = cube.minX / 16.0F;
        currentBounds[boundsOffset + 1] = cube.minY / 16.0F;
        currentBounds[boundsOffset + 2] = cube.minZ / 16.0F;
        currentBounds[boundsOffset + 3] = cube.maxX / 16.0F;
        currentBounds[boundsOffset + 4] = cube.maxY / 16.0F;
        currentBounds[boundsOffset + 5] = cube.maxZ / 16.0F;
        currentCount++;
    }

    private static boolean alreadyCaptured(Model<?> model) {
        for (int index = 0; index < capturedModelCount; index++) {
            if (CAPTURED_MODELS[index] == model) {
                return true;
            }
        }
        return false;
    }

    private static int modelId(Model<?> model) {
        for (int index = 0; index < knownModelCount; index++) {
            if (KNOWN_MODELS[index] == model) {
                return index + 1;
            }
        }
        if (knownModelCount >= KNOWN_MODELS.length) {
            return -1;
        }
        KNOWN_MODELS[knownModelCount] = model;
        return ++knownModelCount;
    }

    private static int cubeId(ModelPart.Cube cube) {
        for (int index = 0; index < knownCubeCount; index++) {
            if (KNOWN_CUBES[index] == cube) {
                return index + 1;
            }
        }
        if (knownCubeCount >= KNOWN_CUBES.length) {
            return -1;
        }
        KNOWN_CUBES[knownCubeCount] = cube;
        return ++knownCubeCount;
    }

    private static int previousIndex(long key) {
        for (int index = 0; index < previousCount; index++) {
            if (previousKeys[index] == key) {
                return index;
            }
        }
        return -1;
    }

    private static void swapFrameStorage() {
        long[] keySwap = previousKeys;
        previousKeys = currentKeys;
        currentKeys = keySwap;
        float[] matrixSwap = previousMatrices;
        previousMatrices = currentMatrices;
        currentMatrices = matrixSwap;
        float[] boundsSwap = previousBounds;
        previousBounds = currentBounds;
        currentBounds = boundsSwap;
        previousCount = currentCount;
        currentCount = 0;
    }
}
