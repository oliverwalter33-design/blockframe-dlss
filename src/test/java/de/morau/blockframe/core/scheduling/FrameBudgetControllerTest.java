package de.morau.blockframe.core.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameBudgetControllerTest {
    private static final long FRAME = 16_666_667L;

    @Test
    void p99PressureThrottlesWorkWithoutChangingContent() {
        FrameBudgetController controller = controller();
        var decision = controller.decide(
            inputs(FRAME, FRAME, FRAME, FRAME * 2L, 32)
        );
        assertEquals(1, decision.activeCompilerWorkers());
        assertEquals(0, decision.snapshotsToStart());
        assertFalse(decision.smtWorkersAllowed());
        assertEquals("frame-p99-pressure", decision.pressureReason());
    }

    @Test
    void memoryPressureStopsAdmissionButAllowsRetirementProgress() {
        FrameBudgetController controller = controller();
        var decision = controller.decide(
            new FrameBudgetController.Inputs(
                FRAME / 2L,
                FRAME / 2L,
                FRAME / 2L,
                FRAME / 2L,
                10,
                10,
                10,
                1024L,
                1024L,
                1000L,
                1000L,
                1000L
            )
        );
        assertEquals(0, decision.activeCompilerWorkers());
        assertEquals(0L, decision.uploadBytesToRecord());
        assertEquals(1, decision.publicationsToComplete());
    }

    @Test
    void gpuSlackAndRealBacklogMayAdmitBoundedSmtWorkers() {
        FrameBudgetController controller = controller();
        var decision = controller.decide(
            inputs(
                FRAME / 2L,
                FRAME * 2L,
                FRAME / 2L,
                FRAME / 2L,
                64
            )
        );
        assertTrue(decision.smtWorkersAllowed());
        assertEquals(8, decision.activeCompilerWorkers());
    }

    private static FrameBudgetController controller() {
        return new FrameBudgetController(
            FRAME,
            4,
            8,
            4L * 1024L * 1024L,
            64L * 1024L * 1024L
        );
    }

    private static FrameBudgetController.Inputs inputs(
        long cpu,
        long gpu,
        long p95,
        long p99,
        int backlog
    ) {
        return new FrameBudgetController.Inputs(
            cpu,
            gpu,
            p95,
            p99,
            backlog / 3,
            backlog - backlog / 3,
            0,
            512L * 1024L * 1024L,
            512L * 1024L * 1024L,
            1000L,
            1000L,
            1000L
        );
    }
}
