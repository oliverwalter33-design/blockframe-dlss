package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.State;
import org.junit.jupiter.api.Test;

class NativeTerrainPayloadOwnerTest {
    @Test
    void solidAndCutoutPublishAndRetireWithoutMojangPayload() {
        var solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        var cutout = NativeTerrainCompilerTestFixtures.entry(
            Category.CUTOUT
        );
        var census = NativeTerrainCompilerTestFixtures.census(
            solid,
            cutout
        );
        var snapshot = NativeTerrainCompilerTestFixtures.snapshot(
            census,
            NativeTerrainCompilerTestFixtures.quad(1L, solid, 0.0F),
            NativeTerrainCompilerTestFixtures.quad(2L, cutout, 2.0F)
        );
        var result = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );
        var batch = result.batch().orElseThrow();
        var lifecycle = compiledLifecycle();
        var budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        var owner = new NativeTerrainPayloadOwner(
            budgets,
            1024L * 1024L
        );

        var publish = owner.publish(batch, lifecycle);
        assertTrue(publish.successful());
        var publication = publish.publicationOptional().orElseThrow();
        assertTrue(publication.ownedBytes() > 0L);
        assertEquals(State.PUBLISHED, lifecycle.state());
        assertEquals(
            publication.ownedBytes(),
            budgets.snapshot().usedBytes(MemoryKind.RAM)
        );

        lifecycle.activate(7L);
        var cleanup = lifecycle.shutdown(9L);
        assertThrows(
            IllegalStateException.class,
            () -> owner.retire(publication, cleanup, 8L)
        );
        owner.retire(publication, cleanup, 9L);
        lifecycle.close();

        assertTrue(publication.retired());
        assertEquals(State.CLOSED, lifecycle.state());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(1L, owner.snapshot().retirements());
        owner.close();
        assertTrue(owner.snapshot().closed());
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void missingSubmissionLaneFailsBeforeAnyPublication() {
        var translucent = NativeTerrainCompilerTestFixtures.entry(
            Category.TRANSLUCENT
        );
        var census = NativeTerrainCompilerTestFixtures.census(
            translucent
        );
        var snapshot = NativeTerrainCompilerTestFixtures.snapshot(
            census,
            NativeTerrainCompilerTestFixtures.quad(
                1L,
                translucent,
                0.0F
            )
        );
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = compiledLifecycle();
        var budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        var owner = new NativeTerrainPayloadOwner(
            budgets,
            1024L * 1024L
        );

        var publish = owner.publish(batch, lifecycle);
        assertFalse(publish.successful());
        assertEquals(
            NativeTerrainPayloadOwner.FailureReason
                .DEFERRED_OR_UNSUPPORTED_CHANNEL,
            publish.failureOptional().orElseThrow()
        );
        assertEquals(State.QUARANTINED, lifecycle.state());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertFalse(owner.snapshot().active());

        batch.close();
        lifecycle.close();
        owner.close();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void boundedOwnerRejectsBeforeUploadAndCloseDrainsPublication() {
        var solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        var census = NativeTerrainCompilerTestFixtures.census(solid);
        var snapshot = NativeTerrainCompilerTestFixtures.snapshot(
            census,
            NativeTerrainCompilerTestFixtures.quad(1L, solid, 0.0F)
        );

        var rejectedBatch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var rejectedLifecycle = compiledLifecycle();
        var budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        var bounded = new NativeTerrainPayloadOwner(budgets, 1L);
        var rejected = bounded.publish(
            rejectedBatch,
            rejectedLifecycle
        );
        assertEquals(
            NativeTerrainPayloadOwner.FailureReason
                .PAYLOAD_LIMIT_EXCEEDED,
            rejected.failureOptional().orElseThrow()
        );
        assertEquals(State.CANCELLED, rejectedLifecycle.state());
        rejectedBatch.close();
        rejectedLifecycle.close();
        bounded.close();

        var closeBatch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var closeLifecycle = compiledLifecycle();
        var owner = new NativeTerrainPayloadOwner(
            budgets,
            1024L * 1024L
        );
        var publication = owner.publish(
            closeBatch,
            closeLifecycle
        );
        assertTrue(publication.successful());
        closeLifecycle.activate(1L);

        owner.close();
        assertEquals(State.CLOSED, closeLifecycle.state());
        assertTrue(owner.snapshot().closed());
        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertTrue(budgets.closeAndReport());
    }

    private static NativeTerrainSectionLifecycle compiledLifecycle() {
        var lifecycle = new NativeTerrainSectionLifecycle(
            NativeTerrainCompilerTestFixtures.section(),
            NativeTerrainCompilerTestFixtures.generations()
        );
        var permit = lifecycle.beginCompilation();
        lifecycle.completeCompilation(permit);
        return lifecycle;
    }
}
