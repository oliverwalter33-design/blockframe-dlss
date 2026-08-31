package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DlssTransformScratchTest {
    private static final float EPSILON = 1.0E-5F;

    @Test
    void reservesExactShaderResourceFootprintAndReleasesIt() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );

        assertEquals(15, DlssTransformScratch.LAYOUT.capacity());
        assertEquals(
            800L,
            DlssTransformScratch.LAYOUT.requestedObjectBytes()
        );
        assertEquals(
            1_096L,
            DlssTransformScratch.LAYOUT.committedObjectBytes()
        );
        assertEquals(920L, DlssTransformScratch.LAYOUT.requestedBytes());
        assertEquals(1_288L, DlssTransformScratch.LAYOUT.committedBytes());

        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);

        assertNotNull(scratch);
        MemoryBudgetManager.Snapshot snapshot = budgets.snapshot();
        assertEquals(920L, snapshot.requestedBytes(MemoryKind.RAM));
        assertEquals(1_288L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(
            1_288L,
            snapshot.usedBytes(
                MemoryKind.RAM,
                MemoryCategory.SHADER_RESOURCES
            )
        );
        assertEquals(1, snapshot.outstanding());

        scratch.close();
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void rejectedBudgetReturnsNullWithoutOutstandingLease() {
        long[] ram = categories(1_287L);
        long[] vram = categories(4_096L);
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            new MemoryBudgetSettings(
                4_096L,
                4_096L,
                0L,
                0L,
                ram,
                vram
            )
        );

        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);

        assertNull(scratch);
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
    }

    @Test
    void captureAndOverlayCopyNeverModifyTheCaller() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);
        assertNotNull(scratch);
        Matrix4f source = projection(0.9F);
        Matrix4f sourceBefore = new Matrix4f(source);

        assertThrows(
            IllegalStateException.class,
            () -> scratch.captureUnjitteredProjection(source)
        );
        assertThrows(
            IllegalStateException.class,
            () -> scratch.prepareCurrentTransforms(
                source,
                new Matrix4f(),
                new Quaternionf(),
                0.0D,
                0.0D,
                0.0D,
                false
            )
        );
        scratch.beginFrame();
        assertNull(scratch.copyProjectionForOverlay());
        scratch.captureUnjitteredProjection(source);
        Matrix4f overlay = scratch.copyProjectionForOverlay();

        assertNotNull(overlay);
        assertMatrix(sourceBefore, source);
        assertMatrix(source, overlay);
        overlay.zero();
        assertSame(overlay, scratch.copyProjectionForOverlay());
        assertMatrix(source, overlay);
        assertMatrix(sourceBefore, source);
        scratch.close();
    }

    @Test
    void preparesNumericallyEquivalentTransformsInStableSlots() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);
        assertNotNull(scratch);
        Matrix4f captured = projection(1.05F);
        Matrix4f capturedBefore = new Matrix4f(captured);
        Matrix4f fallback = projection(0.72F);
        Matrix4f fallbackBefore = new Matrix4f(fallback);
        Matrix4f viewRotation = new Matrix4f()
            .rotateXYZ(0.17F, -0.31F, 0.09F);
        Matrix4f viewRotationBefore = new Matrix4f(viewRotation);
        Quaternionf orientation = new Quaternionf()
            .rotateXYZ(-0.12F, 0.26F, 0.07F);
        Quaternionf orientationBefore = new Quaternionf(orientation);
        double cameraX = 13.25D;
        double cameraY = -4.5D;
        double cameraZ = 81.75D;

        Matrix4f expectedProjection = new Matrix4f(captured);
        Matrix4f expectedViewProjection =
            new Matrix4f(expectedProjection)
                .mul(viewRotation)
                .translate(
                    (float)-cameraX,
                    (float)-cameraY,
                    (float)-cameraZ
                );
        Matrix4f expectedInverseViewProjection =
            new Matrix4f(expectedViewProjection).invert();
        Matrix4f expectedClipToPrevious =
            cameraRelativeClipToPrevious(
                expectedProjection,
                viewRotation,
                cameraX,
                cameraY,
                cameraZ,
                expectedProjection,
                viewRotation,
                cameraX,
                cameraY,
                cameraZ
            );
        Matrix4f expectedPreviousToClip =
            new Matrix4f(expectedClipToPrevious).invert();
        Matrix4f expectedInverseProjection =
            new Matrix4f(expectedProjection).invert();
        Vector3f expectedUp =
            new Vector3f(0.0F, 1.0F, 0.0F).rotate(orientation);
        Vector3f expectedRight =
            new Vector3f(1.0F, 0.0F, 0.0F).rotate(orientation);
        Vector3f expectedForward =
            new Vector3f(0.0F, 0.0F, -1.0F).rotate(orientation);

        scratch.beginFrame();
        scratch.captureUnjitteredProjection(captured);
        assertTrue(
            scratch.prepareCurrentTransforms(
                fallback,
                viewRotation,
                orientation,
                cameraX,
                cameraY,
                cameraZ,
                false
            )
        );

        Matrix4f projectionSlot = scratch.projection();
        Matrix4f viewProjectionSlot = scratch.viewProjection();
        Matrix4f previousSlot =
            scratch.previousViewProjectionForFrame();
        Matrix4f inverseViewProjectionSlot =
            scratch.inverseViewProjection();
        Matrix4f clipSlot = scratch.clipToPrevious();
        Matrix4f previousToClipSlot = scratch.previousToClip();
        Matrix4f inverseProjectionSlot =
            scratch.inverseProjection();
        Vector3f upSlot = scratch.up();
        Vector3f rightSlot = scratch.right();
        Vector3f forwardSlot = scratch.forward();

        assertTrue(scratch.effectiveReset());
        assertFalse(scratch.hasPreviousViewProjection());
        assertMatrix(expectedProjection, projectionSlot);
        assertMatrix(expectedViewProjection, viewProjectionSlot);
        assertSame(viewProjectionSlot, previousSlot);
        assertMatrix(
            expectedInverseViewProjection,
            inverseViewProjectionSlot
        );
        assertMatrix(expectedClipToPrevious, clipSlot);
        assertMatrix(expectedPreviousToClip, previousToClipSlot);
        assertMatrix(expectedInverseProjection, inverseProjectionSlot);
        assertVector(expectedUp, upSlot);
        assertVector(expectedRight, rightSlot);
        assertVector(expectedForward, forwardSlot);
        assertMatrix(capturedBefore, captured);
        assertMatrix(fallbackBefore, fallback);
        assertMatrix(viewRotationBefore, viewRotation);
        assertQuaternion(orientationBefore, orientation);

        scratch.beginFrame();
        scratch.prepareCurrentTransforms(
            fallback,
            viewRotation,
            orientation,
            0.0D,
            0.0D,
            0.0D,
            true
        );

        assertSame(projectionSlot, scratch.projection());
        assertSame(viewProjectionSlot, scratch.viewProjection());
        assertSame(
            inverseViewProjectionSlot,
            scratch.inverseViewProjection()
        );
        assertSame(clipSlot, scratch.clipToPrevious());
        assertSame(previousToClipSlot, scratch.previousToClip());
        assertSame(inverseProjectionSlot, scratch.inverseProjection());
        assertSame(upSlot, scratch.up());
        assertSame(rightSlot, scratch.right());
        assertSame(forwardSlot, scratch.forward());
        scratch.close();
    }

    @Test
    void previousViewProjectionChangesOnlyOnExplicitCommit() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);
        assertNotNull(scratch);
        Matrix4f view = new Matrix4f().rotateY(0.2F);
        Quaternionf orientation = new Quaternionf();
        Matrix4f firstProjection = projection(0.8F);

        scratch.beginFrame();
        assertTrue(
            scratch.prepareCurrentTransforms(
                firstProjection,
                view,
                orientation,
                1.0D,
                2.0D,
                3.0D,
                false
            )
        );
        Matrix4f firstCurrent = new Matrix4f(scratch.viewProjection());
        assertFalse(scratch.hasPreviousViewProjection());

        scratch.commitPreviousViewProjection();

        assertTrue(scratch.hasPreviousViewProjection());
        Matrix4f previousIdentity =
            scratch.previousViewProjectionForFrame();
        assertMatrix(firstCurrent, previousIdentity);

        Matrix4f secondProjection = projection(1.2F);
        scratch.beginFrame();
        assertFalse(
            scratch.prepareCurrentTransforms(
                secondProjection,
                view,
                orientation,
                -5.0D,
                7.0D,
                11.0D,
                false
            )
        );
        Matrix4f secondCurrent =
            new Matrix4f(scratch.viewProjection());
        Matrix4f expectedSecondInverse =
            new Matrix4f(secondCurrent).invert();
        Matrix4f expectedSecondClipToPrevious =
            cameraRelativeClipToPrevious(
                firstProjection,
                view,
                1.0D,
                2.0D,
                3.0D,
                secondProjection,
                view,
                -5.0D,
                7.0D,
                11.0D
            );
        Matrix4f expectedSecondPreviousToClip =
            new Matrix4f(expectedSecondClipToPrevious).invert();

        assertSame(
            previousIdentity,
            scratch.previousViewProjectionForFrame()
        );
        assertMatrix(firstCurrent, previousIdentity);
        assertFalse(matricesEqual(firstCurrent, secondCurrent));
        assertMatrix(
            expectedSecondInverse,
            scratch.inverseViewProjection()
        );
        assertMatrix(
            expectedSecondClipToPrevious,
            scratch.clipToPrevious()
        );
        assertMatrix(
            expectedSecondPreviousToClip,
            scratch.previousToClip()
        );

        scratch.commitPreviousViewProjection();

        assertSame(
            previousIdentity,
            scratch.previousViewProjectionForFrame()
        );
        assertMatrix(secondCurrent, previousIdentity);

        scratch.resetPreviousViewProjection();
        scratch.beginFrame();
        assertTrue(
            scratch.prepareCurrentTransforms(
                secondProjection,
                view,
                orientation,
                0.0D,
                0.0D,
                0.0D,
                false
            )
        );
        assertFalse(scratch.hasPreviousViewProjection());
        assertSame(
            scratch.viewProjection(),
            scratch.previousViewProjectionForFrame()
        );
        scratch.close();
    }

    @Test
    void cameraRelativeClipTransformRetainsSubFloatMotionAtLargeCoordinates() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);
        assertNotNull(scratch);
        Matrix4f projection = projection(0.91F);
        Matrix4f viewRotation = new Matrix4f()
            .rotateXYZ(0.11F, -0.23F, 0.04F);
        Quaternionf orientation = new Quaternionf();
        double previousX = 1_000_000_000.125D;
        double previousY = -1_000_000_000.25D;
        double previousZ = 1_000_000_000.375D;
        double currentX = previousX + 0.25D;
        double currentY = previousY - 0.5D;
        double currentZ = previousZ + 0.75D;

        scratch.beginFrame();
        scratch.prepareCurrentTransforms(
            projection,
            viewRotation,
            orientation,
            previousX,
            previousY,
            previousZ,
            false
        );
        Matrix4f previousFullViewProjection =
            new Matrix4f(scratch.viewProjection());
        scratch.commitPreviousViewProjection();

        scratch.beginFrame();
        assertFalse(
            scratch.prepareCurrentTransforms(
                projection,
                viewRotation,
                orientation,
                currentX,
                currentY,
                currentZ,
                false
            )
        );

        Matrix4f expected = cameraRelativeClipToPrevious(
            projection,
            viewRotation,
            previousX,
            previousY,
            previousZ,
            projection,
            viewRotation,
            currentX,
            currentY,
            currentZ
        );
        Matrix4f legacyAbsoluteFloatTransform =
            new Matrix4f(previousFullViewProjection)
                .mul(new Matrix4f(scratch.viewProjection()).invert());

        assertMatrix(expected, scratch.clipToPrevious());
        assertFalse(
            matricesEqual(expected, legacyAbsoluteFloatTransform),
            "double camera delta must not collapse to the legacy"
                + " absolute-float transform"
        );
        assertMatrix(
            previousFullViewProjection,
            scratch.previousViewProjectionForFrame()
        );
        scratch.close();
    }

    @Test
    void allocationFallbackUsesTheSameCameraRelativeLargeCoordinateMath()
        throws Exception {
        Matrix4f previousProjection = projection(0.87F);
        Matrix4f previousRotation = new Matrix4f()
            .rotateXYZ(0.08F, -0.19F, 0.03F);
        Matrix4f currentProjection = projection(0.91F);
        Matrix4f currentRotation = new Matrix4f()
            .rotateXYZ(0.11F, -0.23F, 0.04F);
        double previousX = 20_000_000.125D;
        double previousY = -20_000_000.25D;
        double previousZ = 20_000_000.375D;
        double currentX = previousX + 0.25D;
        double currentY = previousY - 0.5D;
        double currentZ = previousZ + 0.75D;
        Matrix4f clipToPrevious = new Matrix4f();
        Matrix4f previousToClip = new Matrix4f();

        DlssTransformScratch.setCameraRelativeClipTransforms(
            clipToPrevious,
            previousToClip,
            previousProjection,
            previousRotation,
            previousX,
            previousY,
            previousZ,
            currentProjection,
            currentRotation,
            currentX,
            currentY,
            currentZ
        );

        Matrix4f expected = cameraRelativeClipToPrevious(
            previousProjection,
            previousRotation,
            previousX,
            previousY,
            previousZ,
            currentProjection,
            currentRotation,
            currentX,
            currentY,
            currentZ
        );
        assertMatrix(expected, clipToPrevious);
        assertMatrix(new Matrix4f(expected).invert(), previousToClip);

        String renderer = java.nio.file.Files.readString(
            java.nio.file.Path.of(
                System.getProperty("blockframe.projectDir")
            ).resolve(
                "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
            )
        );
        assertTrue(renderer.contains(
            "DlssTransformScratch.setCameraRelativeClipTransforms("
        ));
        assertFalse(renderer.contains(
            "new Matrix4f(previousVp).mul("
        ));
    }

    @Test
    void orientationIsCopiedPersistentlyAndDeviceClearInvalidatesState() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);
        assertNotNull(scratch);
        Quaternionf orientation =
            new Quaternionf().rotateXYZ(0.2F, -0.4F, 0.1F);
        Quaternionf remembered = new Quaternionf(orientation);

        assertFalse(scratch.hasPreviousOrientation());
        assertThrows(
            IllegalStateException.class,
            () -> scratch.previousOrientationDot(orientation)
        );

        scratch.rememberOrientation(orientation);
        orientation.identity();

        assertTrue(scratch.hasPreviousOrientation());
        assertEquals(
            remembered.dot(remembered),
            scratch.previousOrientationDot(remembered),
            EPSILON
        );

        scratch.beginFrame();
        scratch.prepareCurrentTransforms(
            projection(0.9F),
            new Matrix4f(),
            remembered,
            0.0D,
            0.0D,
            0.0D,
            false
        );
        scratch.commitPreviousViewProjection();
        scratch.clearDeviceState();

        assertFalse(scratch.hasPreviousOrientation());
        assertFalse(scratch.hasPreviousViewProjection());
        assertThrows(
            IllegalStateException.class,
            scratch::effectiveReset
        );
        scratch.close();
    }

    @Test
    void wrongThreadAndUseAfterCloseAreRejectedAndCloseIsIdempotent()
        throws InterruptedException {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        DlssTransformScratch scratch =
            DlssTransformScratch.tryCreate(budgets);
        assertNotNull(scratch);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread other = new Thread(
            () -> {
                try {
                    scratch.beginFrame();
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            "transform-scratch-wrong-thread-test"
        );
        other.start();
        other.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        scratch.beginFrame();
        scratch.close();
        scratch.close();

        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().staleReleases());
        assertThrows(IllegalStateException.class, scratch::beginFrame);
        assertThrows(
            IllegalStateException.class,
            () -> scratch.captureUnjitteredProjection(new Matrix4f())
        );
        assertThrows(
            IllegalStateException.class,
            scratch::clearDeviceState
        );
    }

    private static Matrix4f projection(float fov) {
        return new Matrix4f().perspective(
            fov,
            16.0F / 9.0F,
            0.05F,
            1024.0F,
            true
        );
    }

    private static Matrix4f cameraRelativeClipToPrevious(
        Matrix4f previousProjection,
        Matrix4f previousViewRotation,
        double previousCameraX,
        double previousCameraY,
        double previousCameraZ,
        Matrix4f currentProjection,
        Matrix4f currentViewRotation,
        double currentCameraX,
        double currentCameraY,
        double currentCameraZ
    ) {
        return new Matrix4f(previousProjection)
            .mul(previousViewRotation)
            .translate(
                (float)(currentCameraX - previousCameraX),
                (float)(currentCameraY - previousCameraY),
                (float)(currentCameraZ - previousCameraZ)
            )
            .mul(
                new Matrix4f(currentProjection)
                    .mul(currentViewRotation)
                    .invert()
            );
    }

    private static void assertMatrix(
        Matrix4f expected,
        Matrix4f actual
    ) {
        float[] expectedValues = new float[16];
        float[] actualValues = new float[16];
        expected.get(expectedValues);
        actual.get(actualValues);
        assertArrayEquals(expectedValues, actualValues, EPSILON);
    }

    private static boolean matricesEqual(
        Matrix4f first,
        Matrix4f second
    ) {
        float[] firstValues = new float[16];
        float[] secondValues = new float[16];
        first.get(firstValues);
        second.get(secondValues);
        return Arrays.equals(firstValues, secondValues);
    }

    private static void assertVector(
        Vector3f expected,
        Vector3f actual
    ) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    private static void assertQuaternion(
        Quaternionf expected,
        Quaternionf actual
    ) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
        assertEquals(expected.w, actual.w, EPSILON);
    }

    private static long[] categories(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }
}
