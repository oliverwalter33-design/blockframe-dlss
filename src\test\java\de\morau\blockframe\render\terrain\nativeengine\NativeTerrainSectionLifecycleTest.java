package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.Cause;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.CleanupDecision;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.CompilationPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.GlobalFailureAction;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.RetirementPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.State;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.UploadPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import org.junit.jupiter.api.Test;

class NativeTerrainSectionLifecycleTest {
    @Test
    void completeNativeLifecyclePublishesOnlyAfterWholeUpload() {
        NativeTerrainSectionLifecycle lifecycle = lifecycle();

        assertEquals(State.SNAPSHOT, lifecycle.state());
        CompilationPermit compilation =
            lifecycle.beginCompilation();
        assertEquals(State.COMPILING, lifecycle.state());
        lifecycle.completeCompilation(compilation);
        assertEquals(State.COMPILED, lifecycle.state());

        UploadPermit upload = lifecycle.beginUpload();
        assertEquals(State.UPLOADING, lifecycle.state());
        lifecycle.publish(upload);
        assertEquals(State.PUBLISHED, lifecycle.state());
        lifecycle.activate(17L);

        assertEquals(State.ACTIVE, lifecycle.state());
        assertEquals(17L, lifecycle.activationFrame());
        assertTrue(lifecycle.everActive());
        assertEquals(
            GlobalFailureAction
                .PAUSE_QUARANTINE_AND_REBUILD_MOJANG_NO_SAME_FRAME_REPLAY,
            lifecycle.globalFailureAction()
        );
    }

    @Test
    void globalFallbackChangesOnlyAtAtomicNativeActivation() {
        NativeTerrainSectionLifecycle lifecycle = compiled();
        UploadPermit upload = lifecycle.beginUpload();
        lifecycle.publish(upload);

        assertEquals(
            GlobalFailureAction.ABORT_NATIVE_START_AND_BUILD_MOJANG,
            lifecycle.globalFailureAction()
        );

        lifecycle.activate(18L);
        assertEquals(
            GlobalFailureAction
                .PAUSE_QUARANTINE_AND_REBUILD_MOJANG_NO_SAME_FRAME_REPLAY,
            lifecycle.globalFailureAction()
        );
    }

    @Test
    void compilationFailureNeverPublishesPartialGeometry() {
        NativeTerrainSectionLifecycle lifecycle = lifecycle();
        CompilationPermit permit = lifecycle.beginCompilation();

        lifecycle.failCompilation(permit);

        assertEquals(State.CANCELLED, lifecycle.state());
        assertEquals(Cause.COMPILE_FAILURE, lifecycle.lastCause());
        assertEquals(
            GlobalFailureAction.ABORT_NATIVE_START_AND_BUILD_MOJANG,
            lifecycle.globalFailureAction()
        );
        assertThrows(
            IllegalStateException.class,
            () -> lifecycle.beginUpload()
        );
    }

    @Test
    void budgetFailureBeforeUploadNeedsNoFabricatedCleanupProof() {
        NativeTerrainSectionLifecycle lifecycle = compiled();

        lifecycle.rejectBudgetBeforeUpload();

        assertEquals(State.CANCELLED, lifecycle.state());
        assertEquals(Cause.BUDGET_FAILURE, lifecycle.lastCause());
        lifecycle.close();
        assertEquals(State.CLOSED, lifecycle.state());
    }

    @Test
    void uploadFailureRequiresCleanupBeforeTerminalCancellation() {
        NativeTerrainSectionLifecycle lifecycle = compiled();
        UploadPermit upload = lifecycle.beginUpload();

        CleanupDecision failure = lifecycle.failUpload(upload, 23L);

        assertTrue(failure.cleanupRequired());
        assertEquals(State.RETIRING, lifecycle.state());
        assertThrows(
            IllegalStateException.class,
            () -> lifecycle.completeRetirement(
                failure.retirementPermit(),
                22L
            )
        );
        lifecycle.completeRetirement(
            failure.retirementPermit(),
            23L
        );
        assertEquals(State.CANCELLED, lifecycle.state());
        assertEquals(Cause.UPLOAD_FAILURE, lifecycle.lastCause());
    }

    @Test
    void dirtyRestartInvalidatesAnInFlightCompilationPermit() {
        NativeTerrainSectionLifecycle lifecycle = lifecycle();
        CompilationPermit stale = lifecycle.beginCompilation();

        CleanupDecision restart = lifecycle.restart(
            Cause.DIRTY,
            section(),
            generations(1L, 2L, 3L, 4L, 5L, 7L),
            0L
        );

        assertFalse(restart.cleanupRequired());
        assertEquals(State.SNAPSHOT, lifecycle.state());
        lifecycle.beginCompilation();
        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.completeCompilation(stale)
        );
    }

    @Test
    void activeDirtyRestartRetiresBeforePublishingSuccessor() {
        NativeTerrainSectionLifecycle lifecycle = active();

        CleanupDecision restart = lifecycle.restart(
            Cause.DIRTY,
            section(),
            generations(1L, 2L, 3L, 4L, 5L, 7L),
            81L
        );

        assertEquals(State.RETIRING, lifecycle.state());
        assertEquals(
            81L,
            restart.retirementPermit().minimumCompletedSubmission()
        );
        lifecycle.completeRetirement(
            restart.retirementPermit(),
            81L
        );
        assertEquals(State.SNAPSHOT, lifecycle.state());
        assertEquals(7L, lifecycle.generations().sectionMesh());
        assertEquals(Cause.DIRTY, lifecycle.lastCause());
    }

    @Test
    void failedCleanupMustUseANewRetryPermit() {
        NativeTerrainSectionLifecycle lifecycle = active();
        CleanupDecision removal = lifecycle.removeSection(91L);
        RetirementPermit first = removal.retirementPermit();

        lifecycle.cleanupFailed(first);
        assertEquals(State.CLEANUP_RETRY, lifecycle.state());
        RetirementPermit retry = lifecycle.retryCleanup();
        assertEquals(2, retry.cleanupAttempt());
        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.completeRetirement(first, 91L)
        );
        lifecycle.completeRetirement(retry, 91L);
        assertEquals(State.RETIRED, lifecycle.state());
    }

    @Test
    void activeBackendFailureCleansUpThenQuarantines() {
        NativeTerrainSectionLifecycle lifecycle = active();

        CleanupDecision decision = lifecycle.quarantine(
            Cause.BACKEND_FAILURE,
            101L
        );

        assertTrue(decision.cleanupRequired());
        assertEquals(State.RETIRING, lifecycle.state());
        lifecycle.completeRetirement(
            decision.retirementPermit(),
            101L
        );
        assertEquals(State.QUARANTINED, lifecycle.state());
        assertEquals(
            GlobalFailureAction
                .PAUSE_QUARANTINE_AND_REBUILD_MOJANG_NO_SAME_FRAME_REPLAY,
            lifecycle.globalFailureAction()
        );
    }

    @Test
    void cancelBeforeUploadIsImmediateButUploadingCancellationCleansUp() {
        NativeTerrainSectionLifecycle beforeUpload = compiled();
        CleanupDecision immediate =
            beforeUpload.cancelBeforePublish(Cause.SHUTDOWN, 0L);
        assertFalse(immediate.cleanupRequired());
        assertEquals(State.CANCELLED, beforeUpload.state());

        NativeTerrainSectionLifecycle uploading = compiled();
        uploading.beginUpload();
        CleanupDecision cleanup =
            uploading.cancelBeforePublish(Cause.RESOURCE_RELOAD, 3L);
        assertTrue(cleanup.cleanupRequired());
        uploading.completeRetirement(
            cleanup.retirementPermit(),
            3L
        );
        assertEquals(State.CANCELLED, uploading.state());
    }

    @Test
    void generationSpecificRestartCausesFailClosed() {
        NativeTerrainSectionLifecycle lifecycle = lifecycle();

        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.restart(
                Cause.DEVICE_CHANGE,
                section(),
                generations(1L, 2L, 3L, 4L, 5L, 7L),
                0L
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.restart(
                Cause.RESOURCE_RELOAD,
                section(),
                generations(1L, 3L, 3L, 4L, 5L, 6L),
                0L
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.restart(
                Cause.WORLD_CHANGE,
                section(),
                generations(1L, 2L, 4L, 4L, 5L, 6L),
                0L
            )
        );
    }

    @Test
    void reloadResizeDeviceAndWorldSuccessorsAreAccepted() {
        NativeTerrainSectionLifecycle resources = lifecycle();
        resources.restart(
            Cause.RESOURCE_RELOAD,
            section(),
            generations(1L, 2L, 3L, 5L, 5L, 6L),
            0L
        );
        assertEquals(5L, resources.generations().resources());

        NativeTerrainSectionLifecycle resize = lifecycle();
        resize.restart(
            Cause.RESIZE,
            section(),
            generations(1L, 3L, 3L, 4L, 5L, 6L),
            0L
        );
        assertEquals(3L, resize.generations().renderer());

        NativeTerrainSectionLifecycle device = lifecycle();
        device.restart(
            Cause.DEVICE_CHANGE,
            section(),
            generations(2L, 2L, 3L, 4L, 5L, 6L),
            0L
        );
        assertEquals(2L, device.generations().device());

        NativeTerrainSectionLifecycle world = lifecycle();
        SectionIdentity newWorld = new SectionIdentity(
            NativeTerrainTestFixtures.id(800L),
            section().sectionNode()
        );
        world.restart(
            Cause.WORLD_CHANGE,
            newWorld,
            generations(1L, 2L, 4L, 4L, 5L, 6L),
            0L
        );
        assertEquals(newWorld, world.section());
        assertEquals(4L, world.generations().world());
    }

    @Test
    void sectionRemovalAndShutdownCannotLeakActiveResources() {
        NativeTerrainSectionLifecycle removed = lifecycle();
        CleanupDecision noResources = removed.removeSection(0L);
        assertFalse(noResources.cleanupRequired());
        assertEquals(State.RETIRED, removed.state());
        removed.close();

        NativeTerrainSectionLifecycle shutdown = active();
        CleanupDecision resources = shutdown.shutdown(200L);
        assertTrue(resources.cleanupRequired());
        assertThrows(
            IllegalStateException.class,
            () -> shutdown.close()
        );
        shutdown.completeRetirement(
            resources.retirementPermit(),
            200L
        );
        shutdown.close();
        assertEquals(State.CLOSED, shutdown.state());
    }

    private static NativeTerrainSectionLifecycle lifecycle() {
        return new NativeTerrainSectionLifecycle(
            section(),
            NativeTerrainTestFixtures.generations()
        );
    }

    private static NativeTerrainSectionLifecycle compiled() {
        NativeTerrainSectionLifecycle lifecycle = lifecycle();
        CompilationPermit permit = lifecycle.beginCompilation();
        lifecycle.completeCompilation(permit);
        return lifecycle;
    }

    private static NativeTerrainSectionLifecycle active() {
        NativeTerrainSectionLifecycle lifecycle = compiled();
        UploadPermit upload = lifecycle.beginUpload();
        lifecycle.publish(upload);
        lifecycle.activate(10L);
        return lifecycle;
    }

    private static SectionIdentity section() {
        return NativeTerrainTestFixtures
            .mesh(TerrainMeshProducerABI.Layer.SOLID)
            .section();
    }

    private static GenerationStamp generations(
        long device,
        long renderer,
        long world,
        long resources,
        long producer,
        long sectionMesh
    ) {
        return new GenerationStamp(
            device,
            renderer,
            world,
            resources,
            producer,
            sectionMesh
        );
    }
}
