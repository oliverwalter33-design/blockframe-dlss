package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainGeometryOwnershipTransaction.FallbackAction;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainGeometryOwnershipTransaction.LifecycleInvalidationPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainGeometryOwnershipTransaction.NativeDrawPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainGeometryOwnershipTransaction.PayloadOwnershipPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainGeometryOwnershipTransaction.RetirementFence;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainGeometryOwnershipTransaction.Stage;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Layer;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MeshDescriptor;
import org.junit.jupiter.api.Test;

class TerrainGeometryOwnershipTransactionTest {
    @Test
    void everyPreCommitFailureKeepsTheOriginalMojangCallAvailable() {
        for (int completedSteps = 0; completedSteps <= 6; completedSteps++) {
            TerrainGeometryOwnershipTransaction transaction = transaction();
            advancePreCommit(transaction, completedSteps);

            assertEquals(
                FallbackAction.MOJANG_ORIGINAL_SAME_CALL,
                transaction.failBeforeNativeDraw(),
                "step " + completedSteps
            );
            assertEquals(Stage.FALLBACK_PENDING, transaction.stage());
        }
    }

    @Test
    void payloadOwnershipRequiresPublicationAndRollbackBridges() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        advancePreCommit(transaction, 5);

        assertThrows(
            IllegalStateException.class,
            () -> transaction.commitPayloadOwnership(generations())
        );

        transaction.confirmMojangPublicationBridge(generations());
        PayloadOwnershipPermit permit =
            transaction.commitPayloadOwnership(generations());
        assertEquals(Stage.PAYLOAD_OWNED, transaction.stage());
        assertEquals(20L, permit.retirementSerial());
    }

    @Test
    void structuralValidityCannotReplaceAnExactCompatibilityProof() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        var solid = NativeTerrainTestFixtures.mesh(Layer.SOLID);
        var cutout = NativeTerrainTestFixtures.mesh(Layer.CUTOUT);

        assertTrue(
            solid.structurallyCompatibleWithFirstMilestone()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> transaction.capture(
                solid,
                NativeTerrainTestFixtures.proof(cutout)
            )
        );
        assertEquals(Stage.MOJANG_OWNED, transaction.stage());
    }

    @Test
    void exactProofAlsoBindsRangesBoundsAndInstancingMetadata() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        MeshDescriptor source =
            NativeTerrainTestFixtures.mesh(Layer.SOLID);
        MeshDescriptor changedBounds = new MeshDescriptor(
            source.abiVersion(),
            source.producer(),
            source.generations(),
            source.section(),
            source.layer(),
            source.vertexLayout(),
            source.vertexPayload(),
            source.vertexCount(),
            source.indexLayout(),
            source.material(),
            source.shader(),
            new Bounds(-2.0F, -1.0F, -1.0F, 1.0F, 1.0F, 1.0F),
            source.provenance(),
            source.geometryDigest(),
            source.instancing(),
            source.retirement()
        );

        assertTrue(
            changedBounds.structurallyCompatibleWithFirstMilestone()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> transaction.capture(
                changedBounds,
                NativeTerrainTestFixtures.proof(source)
            )
        );
    }

    @Test
    void sourceAndHookContextAreNotSelfAttestedByTheProof() {
        var solid = NativeTerrainTestFixtures.mesh(Layer.SOLID);
        TerrainGeometryOwnershipTransaction wrongSource =
            new TerrainGeometryOwnershipTransaction(
                generations(),
                99L,
                new TerrainMeshProducerABI.Digest(91L, 92L, 93L, 94L),
                NativeTerrainTestFixtures.hookContract()
            );

        assertThrows(
            IllegalArgumentException.class,
            () -> wrongSource.capture(
                solid,
                NativeTerrainTestFixtures.proof(solid)
            )
        );
    }

    @Test
    void retainedPayloadRestagesAfterOwnershipButBeforePublication() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        PayloadOwnershipPermit permit = ownPayload(transaction);
        transaction.recordTransfer(permit);

        assertTrue(transaction.retainedPayload());
        assertEquals(
            FallbackAction.MOJANG_RESTAGE_RETAINED_PAYLOAD,
            transaction.failBeforeNativeDraw()
        );
    }

    @Test
    void globalBoundaryAloneSelectsSameFrameOrNoReplay() {
        TerrainSubmissionBoundary boundary = submissionBoundary();
        boundary.beginFrame(1L);

        TerrainGeometryOwnershipTransaction before = transaction();
        NativeDrawPermit beforeDraw = publish(before);
        before.releaseRetainedPayload(beforeDraw);
        assertThrows(
            IllegalStateException.class,
            () -> before.releaseRetainedPayload(beforeDraw)
        );
        assertEquals(
            FallbackAction
                .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION,
            before.failDuringFrame(boundary, 1L)
        );

        TerrainGeometryOwnershipTransaction after = transaction();
        NativeDrawPermit afterDraw = publish(after);
        after.releaseRetainedPayload(afterDraw);
        boundary.beginNativeSubmission(1L, 41L);
        assertEquals(
            FallbackAction.MOJANG_NEXT_FRAME_NO_REPLAY,
            after.failDuringFrame(boundary, 1L)
        );
        boundary.endFrame(1L);
    }

    @Test
    void encodedNativeSliceFallbackCarriesItsCurrentSubmissionFence() {
        TerrainSubmissionBoundary boundary = submissionBoundary();
        boundary.beginFrame(1L);
        TerrainGeometryOwnershipTransaction transaction = transaction();
        NativeDrawPermit draw = publish(transaction);
        transaction.releaseRetainedPayload(draw);
        FallbackAction action =
            transaction.failDuringFrame(boundary, 1L);

        assertEquals(
            FallbackAction
                .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION,
            action
        );
        assertThrows(
            IllegalStateException.class,
            () -> transaction.confirmFallbackCompleted(action)
        );
        assertThrows(
            IllegalStateException.class,
            () -> transaction.confirmEncodedSliceFallbackSubmitted(
                boundary,
                1L
            )
        );

        boundary.beginNativeSubmission(1L, 71L);
        transaction.confirmEncodedSliceFallbackSubmitted(boundary, 1L);
        assertEquals(Stage.DEMOTION_PENDING, transaction.stage());
        RetirementFence fence =
            transaction.requestRetirementAfterDemotion(boundary);
        assertEquals(71L, fence.minimumCompletedSubmission());
        assertThrows(
            IllegalStateException.class,
            () -> transaction.completeRetirement(fence, 70L)
        );
        transaction.completeRetirement(fence, 71L);
        boundary.endFrame(1L);
    }

    @Test
    void retirementCannotDestroyThePromisedFallbackSource() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        ownPayload(transaction);
        FallbackAction action = transaction.failBeforeNativeDraw();
        TerrainSubmissionBoundary boundary = submissionBoundary();

        assertEquals(
            FallbackAction.MOJANG_RESTAGE_RETAINED_PAYLOAD,
            action
        );
        assertThrows(
            IllegalStateException.class,
            () -> transaction.requestRetirementAfterDemotion(boundary)
        );
        assertThrows(
            IllegalStateException.class,
            () -> transaction.invalidateLifecycle(
                successorGenerations()
            )
        );

        transaction.confirmFallbackCompleted(action);
        assertEquals(Stage.DEMOTION_PENDING, transaction.stage());
        RetirementFence fence =
            transaction.requestRetirementAfterDemotion(boundary);
        transaction.completeRetirement(fence, 0L);
        assertEquals(Stage.CLOSED, transaction.stage());
    }

    @Test
    void permitsFromEqualValuedForeignTransactionsAreRejectedByIdentity() {
        TerrainGeometryOwnershipTransaction first = transaction();
        TerrainGeometryOwnershipTransaction second = transaction();
        PayloadOwnershipPermit firstPermit = ownPayload(first);
        ownPayload(second);

        assertThrows(
            IllegalArgumentException.class,
            () -> second.recordTransfer(firstPermit)
        );
        assertEquals(Stage.PAYLOAD_OWNED, second.stage());
    }

    @Test
    void stalePermitAfterCloseFailsControlledInsteadOfDereferencingState() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        NativeDrawPermit draw = publish(transaction);
        TerrainSubmissionBoundary boundary = submissionBoundary();
        LifecycleInvalidationPermit invalidation =
            transaction.invalidateLifecycle(successorGenerations());
        RetirementFence fence =
            transaction.requestLifecycleRetirement(
                boundary,
                invalidation
            );
        transaction.completeRetirement(fence, 0L);

        assertThrows(
            IllegalStateException.class,
            () -> transaction.releaseRetainedPayload(draw)
        );
    }

    @Test
    void corruptOnlyCopyStaysQuarantinedWithoutARealReplacementProof() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        NativeDrawPermit draw = publish(transaction);
        transaction.releaseRetainedPayload(draw);
        transaction.invalidateNativeSlice();
        TerrainSubmissionBoundary boundary = submissionBoundary();
        boundary.beginFrame(1L);

        FallbackAction action =
            transaction.failDuringFrame(boundary, 1L);
        assertEquals(
            FallbackAction.REMESH_REQUIRED_FAIL_CLOSED,
            action
        );
        assertFalse(transaction.retainedPayload());
        assertThrows(
            IllegalStateException.class,
            () -> transaction.requestRetirementAfterDemotion(boundary)
        );
        transaction.confirmFallbackCompleted(action);
        assertEquals(Stage.QUARANTINED, transaction.stage());
        assertThrows(
            IllegalStateException.class,
            () -> transaction.requestRetirementAfterDemotion(boundary)
        );
        assertThrows(
            IllegalStateException.class,
            () -> transaction.invalidateLifecycle(
                successorGenerations()
            )
        );
        boundary.endFrame(1L);
    }

    @Test
    void lifecycleRetirementUsesOneConservativeGlobalCompletionFence() {
        TerrainSubmissionBoundary boundary = submissionBoundary();
        boundary.beginFrame(1L);
        boundary.beginNativeSubmission(1L, 52L);
        boundary.endFrame(1L);

        TerrainGeometryOwnershipTransaction transaction = transaction();
        publish(transaction);
        LifecycleInvalidationPermit invalidation =
            transaction.invalidateLifecycle(successorGenerations());
        RetirementFence fence =
            transaction.requestLifecycleRetirement(
                boundary,
                invalidation
            );
        assertEquals(52L, fence.minimumCompletedSubmission());
        assertThrows(
            IllegalStateException.class,
            () -> transaction.completeRetirement(fence, 51L)
        );
        transaction.completeRetirement(fence, 52L);
    }

    @Test
    void lifecycleRetirementRequiresAnOwnerBoundStrictSuccessor() {
        TerrainSubmissionBoundary boundary = submissionBoundary();
        TerrainGeometryOwnershipTransaction first = transaction();
        TerrainGeometryOwnershipTransaction second = transaction();
        publish(first);
        publish(second);

        assertThrows(
            IllegalArgumentException.class,
            () -> first.invalidateLifecycle(generations())
        );
        LifecycleInvalidationPermit firstInvalidation =
            first.invalidateLifecycle(successorGenerations());
        second.invalidateLifecycle(successorGenerations());
        assertThrows(
            IllegalArgumentException.class,
            () -> second.requestLifecycleRetirement(
                boundary,
                firstInvalidation
            )
        );
        assertEquals(Stage.LIFECYCLE_INVALIDATED, second.stage());
    }

    @Test
    void foreignRetirementFenceCannotCloseAnEqualValuedOwner() {
        TerrainSubmissionBoundary boundary = submissionBoundary();
        TerrainGeometryOwnershipTransaction first = transaction();
        TerrainGeometryOwnershipTransaction second = transaction();
        publish(first);
        publish(second);
        RetirementFence firstFence =
            first.requestLifecycleRetirement(
                boundary,
                first.invalidateLifecycle(successorGenerations())
            );
        RetirementFence secondFence =
            second.requestLifecycleRetirement(
                boundary,
                second.invalidateLifecycle(successorGenerations())
            );

        assertThrows(
            IllegalArgumentException.class,
            () -> second.completeRetirement(firstFence, 0L)
        );
        assertEquals(Stage.RETIRING, second.stage());
        second.completeRetirement(secondFence, 0L);
        first.completeRetirement(firstFence, 0L);
    }

    @Test
    void mismatchedGlobalBoundaryCannotMutateAnActiveOwner() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        publish(transaction);
        GenerationStamp current = generations();
        TerrainSubmissionBoundary wrongBoundary =
            new TerrainSubmissionBoundary(
                new TerrainSubmissionBoundary.Generations(
                    current.device() + 1L,
                    current.renderer(),
                    current.world(),
                    current.resources()
                )
            );
        wrongBoundary.beginFrame(1L);

        assertThrows(
            IllegalArgumentException.class,
            () -> transaction.failDuringFrame(wrongBoundary, 1L)
        );
        assertEquals(Stage.ACTIVE, transaction.stage());
        wrongBoundary.endFrame(1L);
    }

    @Test
    void activeFailureCannotBypassTheGlobalNoReplayBoundary() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        publish(transaction);

        assertThrows(
            IllegalStateException.class,
            transaction::failBeforeNativeDraw
        );
        assertEquals(Stage.ACTIVE, transaction.stage());
    }

    @Test
    void nextFrameNoReplayRemainsPendingUntilFallbackCompletion() {
        TerrainSubmissionBoundary boundary = submissionBoundary();
        boundary.beginFrame(1L);
        boundary.beginNativeSubmission(1L, 61L);
        TerrainGeometryOwnershipTransaction transaction = transaction();
        publish(transaction);

        FallbackAction action =
            transaction.failDuringFrame(boundary, 1L);
        assertEquals(
            FallbackAction.MOJANG_NEXT_FRAME_NO_REPLAY,
            action
        );
        assertEquals(Stage.FALLBACK_PENDING, transaction.stage());
        assertThrows(
            IllegalStateException.class,
            () -> transaction.requestRetirementAfterDemotion(boundary)
        );
        transaction.confirmFallbackCompleted(action);
        assertEquals(Stage.DEMOTION_PENDING, transaction.stage());
        RetirementFence fence =
            transaction.requestRetirementAfterDemotion(boundary);
        assertEquals(61L, fence.minimumCompletedSubmission());
        assertThrows(
            IllegalStateException.class,
            () -> transaction.completeRetirement(fence, 60L)
        );
        transaction.completeRetirement(fence, 61L);
        boundary.endFrame(1L);
    }

    @Test
    void closeWithoutResourcesCannotBypassOwnerRetirement() {
        TerrainGeometryOwnershipTransaction transaction = transaction();
        publish(transaction);

        assertThrows(
            IllegalStateException.class,
            transaction::closeWithoutResources
        );
    }

    private static TerrainGeometryOwnershipTransaction transaction() {
        return new TerrainGeometryOwnershipTransaction(
            generations(),
            99L,
            NativeTerrainTestFixtures.sourceContract(),
            NativeTerrainTestFixtures.hookContract()
        );
    }

    private static TerrainSubmissionBoundary submissionBoundary() {
        GenerationStamp generations = generations();
        return new TerrainSubmissionBoundary(
            new TerrainSubmissionBoundary.Generations(
                generations.device(),
                generations.renderer(),
                generations.world(),
                generations.resources()
            )
        );
    }

    private static GenerationStamp generations() {
        return NativeTerrainTestFixtures.generations();
    }

    private static GenerationStamp successorGenerations() {
        GenerationStamp current = generations();
        return new GenerationStamp(
            current.device(),
            current.renderer(),
            current.world(),
            current.resources(),
            current.producer(),
            current.sectionMesh() + 1L
        );
    }

    private static void advancePreCommit(
        TerrainGeometryOwnershipTransaction transaction,
        int completedSteps
    ) {
        if (completedSteps >= 1) {
            var mesh = NativeTerrainTestFixtures.mesh(Layer.SOLID);
            transaction.capture(
                mesh,
                NativeTerrainTestFixtures.proof(mesh)
            );
        }
        if (completedSteps >= 2) {
            transaction.reserveGeometry(generations());
        }
        if (completedSteps >= 3) {
            transaction.retainPayload(generations());
        }
        if (completedSteps >= 4) {
            transaction.reserveSceneSlot(generations());
        }
        if (completedSteps >= 5) {
            transaction.confirmFallbackBridge(generations());
        }
        if (completedSteps >= 6) {
            transaction.confirmMojangPublicationBridge(generations());
        }
    }

    private static PayloadOwnershipPermit ownPayload(
        TerrainGeometryOwnershipTransaction transaction
    ) {
        advancePreCommit(transaction, 6);
        return transaction.commitPayloadOwnership(generations());
    }

    private static NativeDrawPermit publish(
        TerrainGeometryOwnershipTransaction transaction
    ) {
        PayloadOwnershipPermit permit = ownPayload(transaction);
        transaction.recordTransfer(permit);
        transaction.publishScene(permit);
        return transaction.armNativeDraw(permit);
    }
}
