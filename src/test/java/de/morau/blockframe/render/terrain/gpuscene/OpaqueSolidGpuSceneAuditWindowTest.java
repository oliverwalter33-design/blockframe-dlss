package de.morau.blockframe.render.terrain.gpuscene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OpaqueSolidGpuSceneAuditWindowTest {
    @Test
    void fixedWindowPublishesCpuPercentilesAndTypedGpuLimitation() {
        OpaqueSolidGpuSceneAuditWindow window =
            new OpaqueSolidGpuSceneAuditWindow();
        window.beginFrame();
        window.record(
            OpaqueSolidGpuSceneAuditWindow.VISIBLE_AND_LAYER_SCAN,
            100L
        );
        window.record(
            OpaqueSolidGpuSceneAuditWindow
                .STAGING_AND_VISIBILITY_UPLOAD,
            20L
        );
        window.record(
            OpaqueSolidGpuSceneAuditWindow.COMPUTE_AND_BARRIERS,
            30L
        );
        window.record(
            OpaqueSolidGpuSceneAuditWindow
                .GENERATION_TOKEN_PREFLIGHT,
            15L
        );
        window.recordUploadBytes(64L);
        window.recordBarriers(2L);
        window.finishFrame(10L, 8L);

        assertEquals(1, window.sampleCount());
        assertFalse(window.overflow());
        assertEquals(
            (long) OpaqueSolidGpuSceneAuditWindow.STAGE_COUNT
                * OpaqueSolidGpuSceneAuditWindow.MAX_SAMPLES
                * Long.BYTES
                + (long) OpaqueSolidGpuSceneAuditWindow.STAGE_COUNT
                    * Long.BYTES,
            window.allocatedBytes()
        );
        String summary = window.summary();
        assertTrue(summary.contains("visibleAndLayerScanP50=100"));
        assertTrue(summary.contains("uploadBytes=64"));
        assertTrue(summary.contains("barriers=2"));
        assertTrue(
            summary.contains("generationTokenPreflightP50=15")
        );
        assertTrue(
            summary.contains(
                "tokenValidationStatus="
                    + "AVAILABLE_AGGREGATE_BUCKET_PREFLIGHT"
            )
        );
        assertTrue(
            summary.contains(
                "gpuTimestampStatus="
                    + "NOT_AVAILABLE_NO_SAFE_GENERATION_BOUND_QUERY_POOL"
            )
        );
    }

    @Test
    void lifecycleOutsideFrameIsCarriedWithoutOverflow() {
        OpaqueSolidGpuSceneAuditWindow window =
            new OpaqueSolidGpuSceneAuditWindow();
        window.record(
            OpaqueSolidGpuSceneAuditWindow
                .LIFECYCLE_AND_RETIREMENT,
            12L
        );
        window.beginFrame();
        window.finishFrame(1L, 1L);
        assertFalse(window.overflow());
        assertTrue(
            window.summary().contains(
                "lifecycleAndRetirementP50=12"
            )
        );
    }
}
