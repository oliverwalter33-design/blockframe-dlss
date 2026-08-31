package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ThirdPersonDlaaDiagnosticSourceContractTest {
    @Test
    void sameFrameCaptureExposesEveryRequiredPipelineBoundary() throws Exception {
        String capture = source("src/main/java/de/morau/nvidiadlss/DlssDebugCapture.java");
        assertTrue(capture.contains("_A_pre_sl_color.png"));
        assertTrue(capture.contains("_B_pre_sl_depth_visual.png"));
        assertTrue(capture.contains("_B_pre_sl_depth_d32f.bin"));
        assertTrue(capture.contains("_C_pre_sl_motion_visual.png"));
        assertTrue(capture.contains("_C_pre_sl_motion_rg16f.bin"));
        assertTrue(capture.contains("_C_pre_sl_motion_validity_r8ui.png"));
        assertTrue(capture.contains("_C_pre_sl_motion_validity_r8ui.bin"));
        assertTrue(capture.contains("_D_transparency_hint.png"));
        assertTrue(capture.contains("_E_post_sl_dlaa.png"));
        assertTrue(capture.contains("_F0_post_renderlevel_before_entity_outline.png"));
        assertTrue(capture.contains("_F_backbuffer_source_after_all_passes.png"));
    }

    @Test
    void unsupportedHintOverridesCannotReachShaderOrStreamline() throws Exception {
        String audit = source("src/main/java/de/morau/nvidiadlss/TemporalHintAudit.java");
        String renderer = source("src/main/java/de/morau/nvidiadlss/DlssRenderer.java");
        String shader = source("native/shaders/motion_vectors.comp");
        String bridge = source("native/nvidia_dlss_bridge.cpp");

        assertTrue(!audit.contains("devBiasHint"));
        assertTrue(!audit.contains("devInvalidDepthMotionHint"));
        assertTrue(renderer.contains("TemporalHintAudit.secondaryHintMode"));
        assertTrue(!shader.contains("auditHintPolicy"));
        assertTrue(!shader.contains("HistoryBiasOutput"));
        assertTrue(!shader.contains("PreviousDepth"));
        assertTrue(!bridge.contains("sl::kBufferTypeBiasCurrentColorHint"));
        assertTrue(!bridge.contains("sl::kBufferTypeInvalidDepthMotionHint"));
        assertTrue(bridge.contains("sl::kBufferTypeTransparencyHint"));
        assertTrue(bridge.contains("sl::ResourceLifecycle::eOnlyValidNow"));
    }

    @Test
    void invalidReprojectionNeverBecomesAReservedHalfFloatMotionVector()
        throws Exception {
        String shader = source("native/shaders/motion_vectors.comp");

        assertTrue(shader.contains(
            "imageStore(MotionOutput, pixel, vec4(motion, 0.0, 0.0))"
        ));
        assertTrue(!shader.contains("const vec2 INVALID_MOTION"));
        assertTrue(!shader.contains("65504"));
        assertTrue(!shader.contains("invalidFallbackMotion"));
        assertTrue(shader.contains("if (!finiteVec2(motion))"));
        assertTrue(shader.contains("MOTION_CLASS_WORLD_OUTSIDE"));
        assertTrue(shader.contains("writeMotion(pixel, motionPixels, resolvedMotionClass)"));
        assertTrue(shader.contains("layout(set = 0, binding = 9, r8ui)"));
        assertTrue(!shader.contains("MOTION_CLASS_DEPTH_MISMATCH"));
    }

    @Test
    void articulatedPlayerMotionUsesExactRenderedCubesAndPreviousPoses()
        throws Exception {
        String geometry = source(
            "src/main/java/de/morau/nvidiadlss/ThirdPersonGeometryMotion.java"
        );
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/ModelFeatureRendererMixin.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String transport = source(
            "src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java"
        );
        String shader = source("native/shaders/motion_vectors.comp");

        assertTrue(mixin.contains("method = \"prepareModel\""));
        assertTrue(mixin.contains("Model;renderToBuffer"));
        assertTrue(mixin.contains("ThirdPersonGeometryMotion.capture"));
        assertTrue(geometry.contains("visitPart(model.root())"));
        assertTrue(geometry.contains("ModelPartAccessor"));
        assertTrue(geometry.contains("children.forEach(VISIT_CHILD)"));
        assertTrue(geometry.contains("!part.visible"));
        assertTrue(geometry.contains("!part.skipDraw"));
        assertTrue(geometry.contains("previousIndex(currentKeys[currentIndex])"));
        assertTrue(geometry.contains("previousMatrices"));
        assertTrue(geometry.contains("freezeForDispatch()"));
        assertTrue(geometry.contains("commitSuccessfulFrame()"));
        assertTrue(geometry.contains("discardFailedFrame()"));
        assertTrue(renderer.contains("ThirdPersonGeometryMotion.commitSuccessfulFrame()"));
        assertTrue(transport.contains("articulated.writeParts(bytes)"));
        assertTrue(shader.contains("reprojectArticulatedAvatar"));
        assertTrue(shader.contains("part.currentWorldToLocal"));
        assertTrue(shader.contains("part.previousLocalToWorld"));
        assertTrue(shader.contains("part.localBoundsMin"));
        assertTrue(shader.contains("part.localBoundsMax"));
        assertTrue(shader.contains("surfaceDistance > localTolerance"));
        assertTrue(renderer.contains("ThirdPersonMotionAudit"));
        assertTrue(renderer.contains(
            ".excludeLocalPlayerFromRigidTransport()"
        ));
        String audit = source(
            "src/main/java/de/morau/nvidiadlss/ThirdPersonMotionAudit.java"
        );
        assertTrue(audit.contains("EXACT_ARTICULATED"));
        assertTrue(audit.contains("MODE != Mode.LEGACY_RIGID"));
    }

    @Test
    void incompleteExactGeometryDisablesThatPathAndResetsHistory()
        throws Exception {
        String geometry = source(
            "src/main/java/de/morau/nvidiadlss/ThirdPersonGeometryMotion.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String shader = source("native/shaders/motion_vectors.comp");

        assertTrue(geometry.contains("matched > ThirdPersonGeometryBatch.MAX_PARTS"));
        assertTrue(renderer.contains("if (articulatedPlayer.overflow())"));
        assertTrue(renderer.contains(
            "requestReset(\"unvollstaendige Third-Person-Modellgeometrie\")"
        ));
        assertTrue(shader.contains("frame.articulatedFlags.y != 0"));
    }

    @Test
    void exactGeometryHotPathUsesFixedNumericSlots() throws Exception {
        String geometry = source(
            "src/main/java/de/morau/nvidiadlss/ThirdPersonGeometryMotion.java"
        );
        String batch = source(
            "src/main/java/de/morau/nvidiadlss/ThirdPersonGeometryBatch.java"
        );

        assertTrue(geometry.contains("new long[MAX_CAPTURED_CUBES]"));
        assertTrue(geometry.contains("new float[MAX_CAPTURED_CUBES * MATRIX_FLOATS]"));
        assertTrue(geometry.contains("new ModelPart.Cube[MAX_KNOWN_CUBES]"));
        assertTrue(geometry.contains("((long)visitingModelId << 32)"));
        assertTrue(!geometry.contains("new ArrayList"));
        assertTrue(!geometry.contains("new HashMap"));
        assertTrue(!geometry.contains("new IdentityHashMap"));
        assertTrue(!geometry.contains("new LinkedHashMap"));
        assertTrue(batch.contains("new float[MAX_PARTS * MATRIX_FLOATS]"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
