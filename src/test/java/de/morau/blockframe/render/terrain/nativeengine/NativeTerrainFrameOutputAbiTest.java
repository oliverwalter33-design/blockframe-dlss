package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.CameraState;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.Generations;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.Jitter;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.OutputFormat;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.Phase;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.ResetEpoch;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.ResetReason;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.ResourceGenerations;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainFrameOutputAbi.Semantic;
import java.util.EnumSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class NativeTerrainFrameOutputAbiTest {
    @Test
    void formatAndValidityContractIsExplicit() {
        assertEquals(1, NativeTerrainFrameOutputAbi.VERSION);
        assertTrue(NativeTerrainFrameOutputAbi.DEPTH_REVERSED_Z);
        assertEquals(
            0.0F,
            NativeTerrainFrameOutputAbi.DEPTH_CLEAR_VALUE
        );
        assertEquals(
            65_504.0F,
            NativeTerrainFrameOutputAbi
                .MOTION_INVALID_SENTINEL
        );
        assertEquals(
            0.5F,
            NativeTerrainFrameOutputAbi.CUTOUT_ALPHA_THRESHOLD
        );
        assertEquals(
            254.0F / 255.0F,
            NativeTerrainFrameOutputAbi.CUTOUT_ALPHA_MARKER
        );
        assertTrue(
            NativeTerrainFrameOutputAbi
                .MOTION_CURRENT_TO_PREVIOUS
        );
        assertTrue(NativeTerrainFrameOutputAbi.MOTION_UNJITTERED);
        assertEquals(
            NativeTerrainFrameOutputAbi.MotionConvention
                .CURRENT_TO_PREVIOUS_UNJITTERED_OUTPUT_PIXELS_TOP_LEFT,
            NativeTerrainFrameOutputAbi.MOTION_CONVENTION
        );
        assertEquals(
            NativeTerrainFrameOutputAbi.NormalConvention
                .NORMALIZED_WORLD_XYZ_VALIDITY_W,
            NativeTerrainFrameOutputAbi.NORMAL_CONVENTION
        );
        assertEquals(
            0.0F,
            NativeTerrainFrameOutputAbi
                .NORMAL_BACKGROUND_VALIDITY
        );
        assertEquals(
            1.0F,
            NativeTerrainFrameOutputAbi.NORMAL_TERRAIN_VALIDITY
        );
        assertEquals(
            OutputFormat.RGBA8_UNORM,
            Semantic.COLOR.format()
        );
        assertEquals(
            OutputFormat.D32_FLOAT,
            Semantic.DEPTH.format()
        );
        assertEquals(
            OutputFormat.RG16_FLOAT,
            Semantic.MOTION.format()
        );
        assertEquals(
            OutputFormat.RGBA16_SNORM,
            Semantic.WORLD_NORMAL.format()
        );
        assertEquals(
            OutputFormat.DERIVED_CAMERA_NORMAL,
            Semantic.CAMERA_NORMAL.format()
        );
        assertFalse(Semantic.CAMERA_NORMAL.format().stored());
        assertEquals(
            OutputFormat.FRAME_METADATA,
            Semantic.EXPOSURE_JITTER_METADATA.format()
        );
        assertEquals(
            OutputFormat.FRAME_METADATA,
            Semantic.GENERATION_RESET_METADATA.format()
        );
        assertEquals(
            OutputFormat.R32_UINT,
            Semantic.SURFACE.format()
        );
        assertEquals(
            TerrainMeshProducerABI.REQUIRED_NATIVE_OUTPUTS,
            NativeTerrainFrameOutputAbi
                .FRAME_METADATA_VALID_MASK
                | NativeTerrainFrameOutputAbi
                    .STORED_TERRAIN_OUTPUT_MASK
                | Semantic.MOTION.bit()
        );
        assertEquals(
            NativeTerrainFrameOutputAbi
                .FRAME_METADATA_VALID_MASK
                | NativeTerrainFrameOutputAbi
                    .STORED_TERRAIN_OUTPUT_MASK
                | Semantic.CAMERA_NORMAL.bit(),
            NativeTerrainFrameOutputAbi.TERRAIN_VALID_MASK
        );
        assertEquals(
            NativeTerrainFrameOutputAbi.TERRAIN_VALID_MASK
                | Semantic.MOTION.bit(),
            NativeTerrainFrameOutputAbi.COMPLETE_VALID_MASK
        );
    }

    @Test
    void completeLifecycleCommitsPreviousStateOnlyAtPublish() {
        NativeTerrainFrameOutputAbi abi = abi();
        CameraState firstCamera = camera(
            1.0F,
            new Jitter(0.25F, -0.25F)
        );
        var first = beginFrame(
            abi,
            generations(1L),
            firstCamera,
            ResetEpoch.initial(1L)
        );

        assertEquals(Phase.BEGUN, abi.phase());
        assertFalse(abi.frame(first).previousPublished());
        assertFalse(abi.frame(first).historyUsable());
        assertEquals(
            NativeTerrainFrameOutputAbi.ExposureMode
                .AUTO_TONEMAPPED_LDR,
            abi.frame(first).exposure().mode()
        );
        assertEquals(
            NativeTerrainFrameOutputAbi
                .FRAME_METADATA_VALID_MASK,
            abi.frame(first).validMask()
        );
        assertEquals(
            Phase.TERRAIN_DRAWN,
            abi.markTerrainDrawn(
                first,
                NativeTerrainFrameOutputAbi
                    .STORED_TERRAIN_OUTPUT_MASK
            ).phase()
        );
        assertEquals(
            NativeTerrainFrameOutputAbi.TERRAIN_VALID_MASK,
            abi.frame(first).validMask()
        );
        assertEquals(
            Phase.MOTION_RESOLVED,
            abi.markMotionResolved(first).phase()
        );
        assertEquals(
            Phase.PUBLISHED,
            abi.publish(first).phase()
        );
        assertTrue(abi.retire(first).published());
        assertEquals(Phase.RETIRED, abi.phase());

        CameraState secondCamera = camera(
            2.0F,
            new Jitter(-0.125F, 0.125F)
        );
        var second = beginFrame(
            abi,
            generations(2L),
            secondCamera,
            ResetEpoch.unchanged(1L)
        );
        var secondView = abi.frame(second);
        assertTrue(secondView.previousPublished());
        assertTrue(secondView.historyUsable());
        assertEquals(
            firstCamera.jitter(),
            secondView.previous().jitter()
        );
        assertEquals(
            firstCamera.unjitteredView().copy(),
            secondView.previous().unjitteredView().copy()
        );
        abi.retire(second);
        abi.close();
    }

    @Test
    void failedUnpublishedFrameCannotReplaceTemporalHistory() {
        NativeTerrainFrameOutputAbi abi = abi();
        CameraState published = camera(1.0F, Jitter.NONE);
        publish(
            abi,
            generations(1L),
            published,
            ResetEpoch.initial(1L)
        );

        var failed = beginFrame(
            abi,
            generations(2L),
            camera(2.0F, Jitter.NONE),
            new ResetEpoch(
                2L,
                EnumSet.of(ResetReason.RESIZE)
            )
        );
        abi.markTerrainDrawn(
            failed,
            NativeTerrainFrameOutputAbi
                .STORED_TERRAIN_OUTPUT_MASK
        );
        assertFalse(abi.retire(failed).published());

        var retry = beginFrame(
            abi,
            generations(3L),
            camera(3.0F, Jitter.NONE),
            new ResetEpoch(
                2L,
                EnumSet.of(ResetReason.RESIZE)
            )
        );
        var retryView = abi.frame(retry);
        assertEquals(
            published.unjitteredView().copy(),
            retryView.previous().unjitteredView().copy()
        );
        assertFalse(retryView.historyUsable());
        abi.retire(retry);
        abi.close();
    }

    @Test
    void invalidTransitionsMasksAndStaleTokensFailClosed() {
        NativeTerrainFrameOutputAbi abi = abi();
        var first = beginFrame(
            abi,
            generations(1L),
            camera(1.0F, Jitter.NONE),
            ResetEpoch.initial(1L)
        );
        assertThrows(
            IllegalStateException.class,
            () -> abi.markMotionResolved(first)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> abi.markTerrainDrawn(
                first,
                Semantic.COLOR.bit()
            )
        );
        assertThrows(
            IllegalStateException.class,
            () -> abi.publish(first)
        );
        assertThrows(
            IllegalStateException.class,
            () -> beginFrame(
                abi,
                generations(2L),
                camera(2.0F, Jitter.NONE),
                ResetEpoch.initial(1L)
            )
        );
        abi.retire(first);

        var second = beginFrame(
            abi,
            generations(2L),
            camera(2.0F, Jitter.NONE),
            ResetEpoch.initial(1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> abi.markTerrainDrawn(
                first,
                NativeTerrainFrameOutputAbi
                    .STORED_TERRAIN_OUTPUT_MASK
            )
        );
        abi.retire(second);
        abi.close();
    }

    @Test
    void resetEpochAndResourceGenerationsRejectStaleState() {
        NativeTerrainFrameOutputAbi abi = abi();
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                generations(1L),
                camera(1.0F, Jitter.NONE),
                ResetEpoch.unchanged(1L)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                new Generations(1L, 99L, 2L, 3L, 4L, 5L),
                camera(1.0F, Jitter.NONE),
                ResetEpoch.initial(1L)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                new Generations(1L, 1L, 2L, 3L, 4L, 99L),
                camera(1.0F, Jitter.NONE),
                ResetEpoch.initial(1L)
            )
        );
        publish(
            abi,
            generations(1L),
            camera(1.0F, Jitter.NONE),
            ResetEpoch.initial(1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                generations(2L),
                camera(2.0F, Jitter.NONE),
                new ResetEpoch(
                    1L,
                    EnumSet.of(ResetReason.TELEPORT)
                )
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                generations(2L),
                camera(2.0F, Jitter.NONE),
                ResetEpoch.unchanged(2L)
            )
        );

        var reset = beginFrame(
            abi,
            generations(2L),
            camera(2.0F, Jitter.NONE),
            new ResetEpoch(
                3L,
                EnumSet.of(ResetReason.WORLD_CHANGE)
            )
        );
        abi.retire(reset);
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                generations(3L),
                camera(3.0F, Jitter.NONE),
                new ResetEpoch(
                    2L,
                    EnumSet.of(ResetReason.RESIZE)
                )
            )
        );
        abi.close();
    }

    @Test
    void matricesJitterAndNormalInputsMustBeFiniteAndInvertible() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Jitter(Float.NaN, 0.0F)
        );
        NativeTerrainFrameOutputAbi abi = abi();
        assertThrows(
            NullPointerException.class,
            () -> abi.beginFrame(
                generations(1L),
                camera(1.0F, Jitter.NONE),
                null,
                ResetEpoch.initial(1L)
            )
        );
        abi.close();
        Matrix4f nonFinite =
            new Matrix4f().m20(Float.POSITIVE_INFINITY);
        assertThrows(
            IllegalArgumentException.class,
            () -> CameraState.capture(
                nonFinite,
                new Matrix4f(),
                Jitter.NONE,
                extent(),
                origin()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CameraState.capture(
                new Matrix4f(),
                new Matrix4f().zero(),
                Jitter.NONE,
                extent(),
                origin()
            )
        );

        Matrix4f projection = new Matrix4f();
        Matrix4f view = new Matrix4f().translation(4.0F, 5.0F, 6.0F);
        CameraState snapshot =
            CameraState.capture(
                projection,
                view,
                Jitter.NONE,
                extent(),
                origin()
            );
        projection.m00(2.0F);
        view.m30(99.0F);
        assertEquals(1.0F, snapshot.unjitteredProjection().copy().m00());
        assertEquals(4.0F, snapshot.unjitteredView().copy().m30());
        assertThrows(
            IllegalArgumentException.class,
            () -> snapshot.deriveCameraNormal(
                new Vector3f(),
                new Vector3f()
            )
        );
    }

    @Test
    void cameraNormalIsExactInverseTransposeDerivedSemantic() {
        CameraState state = CameraState.capture(
            new Matrix4f(),
            new Matrix4f().scaling(2.0F, 1.0F, 1.0F),
            Jitter.NONE,
            extent(),
            origin()
        );
        Vector3f cameraNormal = state.deriveCameraNormal(
            new Vector3f(1.0F, 1.0F, 0.0F),
            new Vector3f()
        );
        assertEquals(0.4472136F, cameraNormal.x(), 1.0E-6F);
        assertEquals(0.8944272F, cameraNormal.y(), 1.0E-6F);
        assertEquals(0.0F, cameraNormal.z(), 1.0E-6F);
    }

    @Test
    void closeRequiresRetirementAndRejectsFurtherFrames() {
        NativeTerrainFrameOutputAbi abi = abi();
        var token = beginFrame(
            abi,
            generations(1L),
            camera(1.0F, Jitter.NONE),
            ResetEpoch.initial(1L)
        );
        assertThrows(IllegalStateException.class, abi::close);
        abi.retire(token);
        abi.close();
        assertEquals(Phase.CLOSED, abi.phase());
        assertThrows(
            IllegalStateException.class,
            () -> beginFrame(
                abi,
                generations(2L),
                camera(2.0F, Jitter.NONE),
                ResetEpoch.initial(1L)
            )
        );
    }

    @Test
    void extentCameraOriginAndSceneGenerationAreExplicitHistory() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new NativeTerrainFrameOutputAbi.OutputExtent(0, 1080)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new NativeTerrainFrameOutputAbi.CameraOrigin(
                0,
                0,
                0,
                Float.NaN,
                0.0F,
                0.0F
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new NativeTerrainFrameOutputAbi.CameraOrigin(
                0,
                0,
                0,
                0.25F,
                0.0F,
                0.0F
            )
        );

        NativeTerrainFrameOutputAbi abi = abi();
        NativeTerrainFrameOutputAbi.CameraOrigin firstOrigin =
            new NativeTerrainFrameOutputAbi.CameraOrigin(
                31,
                64,
                -17,
                -0.25F,
                -0.75F,
                -0.5F
            );
        CameraState firstCamera = camera(
            1.0F,
            Jitter.NONE,
            extent(),
            firstOrigin
        );
        publish(
            abi,
            generations(1L),
            firstCamera,
            ResetEpoch.initial(1L)
        );

        CameraState resized = camera(
            2.0F,
            Jitter.NONE,
            new NativeTerrainFrameOutputAbi.OutputExtent(1280, 720),
            new NativeTerrainFrameOutputAbi.CameraOrigin(
                32,
                64,
                -17,
                -0.25F,
                -0.75F,
                -0.5F
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> beginFrame(
                abi,
                generations(2L),
                resized,
                ResetEpoch.unchanged(1L)
            )
        );
        var resizedToken = beginFrame(
            abi,
            generations(2L),
            resized,
            new ResetEpoch(
                2L,
                EnumSet.of(ResetReason.RESIZE)
            )
        );
        assertEquals(
            firstOrigin,
            abi.frame(resizedToken).previous().origin()
        );
        assertEquals(
            new NativeTerrainFrameOutputAbi.OutputExtent(1280, 720),
            abi.frame(resizedToken).current().outputExtent()
        );
        abi.retire(resizedToken);
        abi.close();
    }

    private static NativeTerrainFrameOutputAbi abi() {
        return new NativeTerrainFrameOutputAbi(resources());
    }

    private static ResourceGenerations resources() {
        return new ResourceGenerations(1L, 2L, 3L, 4L, 5L);
    }

    private static Generations generations(long frame) {
        return new Generations(frame, 1L, 2L, 3L, 4L, 5L);
    }

    private static CameraState camera(
        float x,
        Jitter jitter
    ) {
        return CameraState.capture(
            new Matrix4f(),
            new Matrix4f().translation(x, 0.0F, 0.0F),
            jitter,
            extent(),
            origin()
        );
    }

    private static CameraState camera(
        float x,
        Jitter jitter,
        NativeTerrainFrameOutputAbi.OutputExtent extent,
        NativeTerrainFrameOutputAbi.CameraOrigin origin
    ) {
        return CameraState.capture(
            new Matrix4f(),
            new Matrix4f().translation(x, 0.0F, 0.0F),
            jitter,
            extent,
            origin
        );
    }

    private static NativeTerrainFrameOutputAbi.OutputExtent extent() {
        return new NativeTerrainFrameOutputAbi.OutputExtent(1920, 1080);
    }

    private static NativeTerrainFrameOutputAbi.CameraOrigin origin() {
        return new NativeTerrainFrameOutputAbi.CameraOrigin(
            0,
            0,
            0,
            0.0F,
            0.0F,
            0.0F
        );
    }

    private static void publish(
        NativeTerrainFrameOutputAbi abi,
        Generations generations,
        CameraState camera,
        ResetEpoch reset
    ) {
        var token = beginFrame(abi, generations, camera, reset);
        abi.markTerrainDrawn(
            token,
            NativeTerrainFrameOutputAbi
                .STORED_TERRAIN_OUTPUT_MASK
        );
        abi.markMotionResolved(token);
        abi.publish(token);
        abi.retire(token);
    }

    private static NativeTerrainFrameOutputAbi.FrameToken beginFrame(
        NativeTerrainFrameOutputAbi abi,
        Generations generations,
        CameraState camera,
        ResetEpoch reset
    ) {
        return abi.beginFrame(
            generations,
            camera,
            NativeTerrainFrameOutputAbi.Exposure
                .autoTonemappedLdr(),
            reset
        );
    }
}
