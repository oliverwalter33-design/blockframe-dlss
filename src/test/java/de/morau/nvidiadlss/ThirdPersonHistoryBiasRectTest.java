package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ThirdPersonHistoryBiasRectTest {
    @Test
    void movedCubeProjectsPreviousUvRectWithOneInputPixelBorder() {
        ThirdPersonGeometryBatch batch = movedCube();

        batch.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            50,
            false
        );

        assertEquals(0.39F, batch.historyRejectRectComponent(0, 0), 1.0e-6F);
        assertEquals(0.28F, batch.historyRejectRectComponent(0, 1), 1.0e-6F);
        assertEquals(0.61F, batch.historyRejectRectComponent(0, 2), 1.0e-6F);
        assertEquals(0.72F, batch.historyRejectRectComponent(0, 3), 1.0e-6F);
        assertEquals(0.5F, batch.currentRectComponent(0, 0), 1.0e-6F);
        assertEquals(0.3F, batch.currentRectComponent(0, 1), 1.0e-6F);
        assertEquals(0.7F, batch.currentRectComponent(0, 2), 1.0e-6F);
        assertEquals(0.7F, batch.currentRectComponent(0, 3), 1.0e-6F);
        assertEquals(0.39F, batch.historyMinU(), 1.0e-6F);
        assertEquals(0.28F, batch.historyMinV(), 1.0e-6F);
        assertEquals(0.61F, batch.historyMaxU(), 1.0e-6F);
        assertEquals(0.72F, batch.historyMaxV(), 1.0e-6F);
    }

    @Test
    void borderTracksActualInputDimensionsInsteadOfWindowPreset() {
        ThirdPersonGeometryBatch batch = movedCube();

        batch.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            200,
            100,
            false
        );

        assertEquals(0.395F, batch.historyRejectRectComponent(0, 0), 1.0e-6F);
        assertEquals(0.29F, batch.historyRejectRectComponent(0, 1), 1.0e-6F);
        assertEquals(0.605F, batch.historyRejectRectComponent(0, 2), 1.0e-6F);
        assertEquals(0.71F, batch.historyRejectRectComponent(0, 3), 1.0e-6F);
    }

    @Test
    void onePixelTranslationKeepsThePreviousRectActive() {
        ThirdPersonGeometryBatch batch = new ThirdPersonGeometryBatch();
        batch.add(
            // Identity projection maps 0.02 NDC to 0.01 UV = one pixel at 100 px.
            new Matrix4f().translation(0.02F, 0.0F, 0.0F),
            new Matrix4f(),
            -0.2F, -0.4F, -0.1F,
            0.2F, 0.4F, 0.1F
        );
        batch.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            50,
            false
        );

        float previousMinU = batch.historyRejectRectComponent(0, 0);
        float currentMinU = batch.currentRectComponent(0, 0);
        assertEquals(0.39F, previousMinU, 1.0e-6F);
        assertEquals(0.41F, currentMinU, 1.0e-6F);
        assertTrue(previousMinU < currentMinU);
    }

    @Test
    void oppositeSymmetricRotationsKeepHistoryActiveDespiteEqualAabbs() {
        ThirdPersonGeometryBatch batch = new ThirdPersonGeometryBatch();
        batch.add(
            new Matrix4f().rotateY(0.4F),
            new Matrix4f().rotateY(-0.4F),
            -0.2F, -0.4F, -0.2F,
            0.2F, 0.4F, 0.2F
        );
        batch.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            100,
            false
        );

        assertTrue(batch.historyMinU() <= batch.historyMaxU());
        assertEquals(
            batch.currentRectComponent(0, 0),
            batch.historyRejectRectComponent(0, 0) + 0.01F,
            1.0e-6F
        );
        assertEquals(
            batch.currentRectComponent(0, 2),
            batch.historyRejectRectComponent(0, 2) - 0.01F,
            1.0e-6F
        );
    }

    @Test
    void stationaryCubeAndResetFrameProduceAnEmptyMask() {
        ThirdPersonGeometryBatch stationary = new ThirdPersonGeometryBatch();
        Matrix4f identity = new Matrix4f();
        stationary.add(
            identity,
            identity,
            -0.2F, -0.4F, -0.1F,
            0.2F, 0.4F, 0.1F
        );
        stationary.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            50,
            false
        );
        assertEmpty(stationary);

        ThirdPersonGeometryBatch reset = movedCube();
        reset.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            50,
            true
        );
        assertEmpty(reset);
    }

    @Test
    void cameraOnlyMotionActivatesPreviousPlayerRect() {
        ThirdPersonGeometryBatch batch = new ThirdPersonGeometryBatch();
        Matrix4f identity = new Matrix4f();
        batch.add(
            identity,
            identity,
            -0.2F, -0.4F, -0.1F,
            0.2F, 0.4F, 0.1F
        );

        batch.prepareHistoryRejectRects(
            new Matrix4f().translation(-0.2F, 0.0F, 0.0F),
            new Matrix4f(),
            100,
            50,
            false
        );

        assertTrue(batch.historyMinU() <= batch.historyMaxU());
        assertTrue(batch.historyMinV() <= batch.historyMaxV());
    }

    @Test
    void noPartsOverflowAndBehindCameraProjectionFailClosed() {
        ThirdPersonGeometryBatch noParts = new ThirdPersonGeometryBatch();
        noParts.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            50,
            false
        );
        assertTrue(noParts.historyMinU() > noParts.historyMaxU());
        assertTrue(noParts.historyMinV() > noParts.historyMaxV());

        ThirdPersonGeometryBatch overflow = movedCube();
        overflow.markOverflow();
        overflow.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f(),
            100,
            50,
            false
        );
        assertEmpty(overflow);

        ThirdPersonGeometryBatch behindCamera = movedCube();
        behindCamera.prepareHistoryRejectRects(
            new Matrix4f(),
            new Matrix4f().m33(-1.0F),
            100,
            50,
            false
        );
        assertEmpty(behindCamera);
    }

    private static ThirdPersonGeometryBatch movedCube() {
        ThirdPersonGeometryBatch batch = new ThirdPersonGeometryBatch();
        batch.add(
            new Matrix4f().translation(0.2F, 0.0F, 0.0F),
            new Matrix4f(),
            -0.2F, -0.4F, -0.1F,
            0.2F, 0.4F, 0.1F
        );
        return batch;
    }

    private static void assertEmpty(ThirdPersonGeometryBatch batch) {
        assertTrue(batch.historyMinU() > batch.historyMaxU());
        assertTrue(batch.historyMinV() > batch.historyMaxV());
        assertTrue(
            batch.historyRejectRectComponent(0, 0)
                > batch.historyRejectRectComponent(0, 2)
        );
        assertTrue(
            batch.historyRejectRectComponent(0, 1)
                > batch.historyRejectRectComponent(0, 3)
        );
        assertTrue(
            batch.currentRectComponent(0, 0)
                > batch.currentRectComponent(0, 2)
        );
        assertTrue(
            batch.currentRectComponent(0, 1)
                > batch.currentRectComponent(0, 3)
        );
    }
}
