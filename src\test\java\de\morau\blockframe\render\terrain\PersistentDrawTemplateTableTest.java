package de.morau.blockframe.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PersistentDrawTemplateTableTest {
    @Test
    void compatibleTemplateIsReusedWithoutRebuild() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 4, 4_096L);
        Key key = new Key();
        Object payload = new Object();

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        int slot = build(table, 7L, key, payload);
        finishNormally(table);

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        int reusedSlot = table.acquireSlot(7L);
        assertEquals(slot, reusedSlot);
        assertTrue(compatible(table, reusedSlot, 7L, key));
        assertSame(payload, table.reuse(reusedSlot));
        finishNormally(table);

        PersistentDrawTemplateTable.Snapshot snapshot =
            table.snapshot();
        assertEquals(1L, snapshot.reused());
        assertEquals(0L, snapshot.rebuilt());
        assertEquals(1L, snapshot.reusedTemplates());
        assertTrue(table.closeAndReport());
        assertEquals(0, budgets.snapshot().outstanding());
    }

    @Test
    void changedMeshBufferPipelineAndShaderKeysBecomeDirty() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 4, 4_096L);
        Key first = new Key();

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 9L, first, new Object());
        finishNormally(table);

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        int slot = table.acquireSlot(9L);
        Key changed = first.withChangedOwners();
        assertFalse(compatible(table, slot, 9L, changed));
        assertEquals(
            PersistentDrawTemplateTable.State.DIRTY,
            table.stateForSection(9L)
        );
        table.beginBuild(slot);
        publish(table, slot, 9L, changed, new Object());
        finishNormally(table);

        assertEquals(1L, table.snapshot().dirty());
        assertTrue(table.closeAndReport());
    }

    @Test
    void worldRendererDeviceAndReloadGenerationsRetireOldEntries() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 4, 4_096L);
        Key key = new Key();
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 11L, key, new Object());
        finishNormally(table);

        assertTrue(begin(table, 2L, 1L, 1L, 1L));
        assertEquals(0, table.snapshot().entries());
        table.abortBeforeSubmission(
            PersistentDrawTemplateTable.Failure
                .PRE_SUBMISSION_FAILURE
        );

        assertTrue(begin(table, 2L, 2L, 2L, 2L));
        build(table, 11L, key, new Object());
        finishNormally(table);
        assertTrue(table.closeAndReport());
    }

    @Test
    void boundedCapacityEvictsOnlyASectionNotSeenThisFrame() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        Key first = new Key();
        Key second = first.withChangedOwners();
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 1L, first, new Object());
        build(table, 2L, second, new Object());
        finishNormally(table);

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        int retained = table.acquireSlot(1L);
        assertTrue(compatible(table, retained, 1L, first));
        assertNotNull(table.reuse(retained));
        int replacement = table.acquireSlot(3L);
        assertTrue(replacement >= 0);
        table.beginBuild(replacement);
        publish(table, replacement, 3L, new Key(), new Object());
        finishNormally(table);

        assertEquals(
            PersistentDrawTemplateTable.State.RETIRED,
            table.stateForSection(2L)
        );
        assertEquals(1L, table.snapshot().evicted());
        assertTrue(table.closeAndReport());
    }

    @Test
    void capacityOverflowFailsBeforeSubmissionWhenEverythingIsVisible() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 1L, new Key(), new Object());
        build(table, 2L, new Key(), new Object());
        assertEquals(-1, table.acquireSlot(3L));
        assertEquals(
            PersistentDrawTemplateTable.Failure.CAPACITY_OVERFLOW,
            table.snapshot().lastFailure()
        );
        table.abortBeforeSubmission(
            PersistentDrawTemplateTable.Failure
                .CAPACITY_OVERFLOW
        );
        assertFalse(table.snapshot().submissionStarted());
        assertTrue(table.closeAndReport());
    }

    @Test
    void lowBudgetAndAllocationFailureUseMojangFallbackWithoutLease() {
        MemoryBudgetManager lowBudgets = lowCacheBudgets();
        PersistentDrawTemplateTable low =
            new PersistentDrawTemplateTable(
                lowBudgets,
                2,
                2L * 1024L * 1024L
            );
        assertFalse(begin(low, 1L, 1L, 1L, 1L));
        assertEquals(
            PersistentDrawTemplateTable.Failure.BUDGET_REJECTED,
            low.snapshot().lastFailure()
        );
        assertEquals(0, lowBudgets.snapshot().outstanding());

        MemoryBudgetManager allocationBudgets = budgets();
        PersistentDrawTemplateTable allocation =
            new PersistentDrawTemplateTable(
                allocationBudgets,
                2,
                4_096L
            );
        allocation.failAllocationForTest(true);
        assertFalse(begin(allocation, 1L, 1L, 1L, 1L));
        assertEquals(
            PersistentDrawTemplateTable.Failure.ALLOCATION_FAILED,
            allocation.snapshot().lastFailure()
        );
        assertEquals(0, allocationBudgets.snapshot().outstanding());
    }

    @Test
    void budgetEvictionPhysicallyDropsArraysAndCanRecreateThem() {
        MemoryBudgetManager budgets = constrainedBudgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(
                budgets,
                2,
                600L * 1024L
            );
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 1L, new Key(), new Object());
        finishNormally(table);

        long competing = budgets.tryReserve(
            de.morau.blockframe.core.budget.MemoryKind.RAM,
            de.morau.blockframe.core.budget.MemoryCategory.CACHES,
            600L * 1024L
        );
        assertTrue(competing != 0L);
        assertFalse(table.snapshot().budgetActive());
        assertEquals(0, table.snapshot().entries());
        assertTrue(budgets.release(competing));

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 2L, new Key(), new Object());
        finishNormally(table);
        assertTrue(table.snapshot().budgetActive());
        assertTrue(table.closeAndReport());
    }

    @Test
    void cleanupFailureRetainsAccountingForRetry() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 1L, new Key(), new Object());
        finishNormally(table);

        table.failNextCleanupForTest();
        assertFalse(table.closeAndReport());
        assertEquals(1, budgets.snapshot().outstanding());
        assertTrue(table.closeAndReport());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(1L, table.snapshot().cleanupRetries());
    }

    @Test
    void wrongThreadNeverMutatesAnOwnedFrame() throws Exception {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        table.abortBeforeSubmission(
            PersistentDrawTemplateTable.Failure
                .PRE_SUBMISSION_FAILURE
        );

        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread wrong = new Thread(
            () -> accepted.set(
                table.beginFrame(
                    Thread.currentThread(),
                    1L,
                    1L,
                    1L,
                    1L
                )
            ),
            "wrong-terrain-owner"
        );
        wrong.start();
        wrong.join();
        assertFalse(accepted.get());
        assertEquals(1L, table.snapshot().wrongThreadCount());
        assertTrue(table.closeAndReport());
    }

    @Test
    void postSubmissionFailureQuarantinesAndNeverRequestsReplay() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 5L, new Key(), new Object());
        Object marker = new Object();
        table.publishFrame(marker, 1, 1L, 2L, 1L, 0L);
        assertTrue(table.beginSolidSubmission(marker));
        table.finishOpaqueGroup(marker, false);

        assertEquals(
            PersistentDrawTemplateTable.State.QUARANTINED,
            table.stateForSection(5L)
        );
        assertEquals(
            PersistentDrawTemplateTable.Failure
                .POST_SUBMISSION_FAILURE,
            table.snapshot().lastFailure()
        );
        assertFalse(table.snapshot().frameActive());
        assertTrue(table.closeAndReport());
    }

    @Test
    void missingSubmissionIsFailClosedBeforeOrAfterTheReplayBoundary() {
        MemoryBudgetManager budgets = budgets();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        build(table, 5L, new Key(), new Object());
        Object beforeMarker = new Object();
        table.publishFrame(
            beforeMarker,
            1,
            1L,
            2L,
            1L,
            0L
        );
        table.finishOpaqueGroup(beforeMarker, true);
        assertEquals(
            PersistentDrawTemplateTable.Failure
                .PRE_SUBMISSION_FAILURE,
            table.snapshot().lastFailure()
        );

        assertTrue(begin(table, 1L, 1L, 1L, 1L));
        int slot = table.acquireSlot(5L);
        Key key = new Key();
        table.beginBuild(slot);
        publish(table, slot, 5L, key, new Object());
        Object afterMarker = new Object();
        table.publishFrame(afterMarker, 2, 1L, 2L, 1L, 0L);
        assertTrue(table.beginSolidSubmission(afterMarker));
        table.finishOpaqueGroup(afterMarker, true);
        assertEquals(
            PersistentDrawTemplateTable.State.QUARANTINED,
            table.stateForSection(5L)
        );
        assertEquals(
            PersistentDrawTemplateTable.Failure
                .POST_SUBMISSION_FAILURE,
            table.snapshot().lastFailure()
        );
        assertTrue(table.closeAndReport());
    }

    @Test
    void closedBudgetManagerCannotEscapeToTheRenderPath() {
        MemoryBudgetManager budgets = budgets();
        budgets.close();
        PersistentDrawTemplateTable table =
            new PersistentDrawTemplateTable(budgets, 2, 4_096L);
        assertFalse(begin(table, 1L, 1L, 1L, 1L));
        assertEquals(
            PersistentDrawTemplateTable.Failure.BUDGET_REJECTED,
            table.snapshot().lastFailure()
        );
        assertTrue(table.closeAndReport());
    }

    private static boolean begin(
        PersistentDrawTemplateTable table,
        long world,
        long renderer,
        long device,
        long reload
    ) {
        return table.beginFrame(
            Thread.currentThread(),
            world,
            renderer,
            device,
            reload
        );
    }

    private static int build(
        PersistentDrawTemplateTable table,
        long sectionNode,
        Key key,
        Object payload
    ) {
        int slot = table.acquireSlot(sectionNode);
        assertTrue(slot >= 0);
        table.beginBuild(slot);
        publish(table, slot, sectionNode, key, payload);
        return slot;
    }

    private static void publish(
        PersistentDrawTemplateTable table,
        int slot,
        long sectionNode,
        Key key,
        Object payload
    ) {
        table.publishReady(
            slot,
            1L,
            1L,
            1L,
            1L,
            key.mesh,
            key.meshRevision,
            key.vertexBuffer,
            key.vertexOffset,
            key.indexBuffer,
            key.indexOffset,
            key.firstIndex,
            key.indexCount,
            key.baseVertex,
            key.indexTypeKey,
            key.pipeline,
            key.pipelineKey,
            key.vertexFormat,
            key.descriptorKey,
            key.material,
            key.materialKey,
            (int)sectionNode,
            64,
            -32,
            payload
        );
    }

    private static boolean compatible(
        PersistentDrawTemplateTable table,
        int slot,
        long sectionNode,
        Key key
    ) {
        return table.compatible(
            slot,
            1L,
            1L,
            1L,
            1L,
            key.mesh,
            key.meshRevision,
            key.vertexBuffer,
            key.vertexOffset,
            key.indexBuffer,
            key.indexOffset,
            key.firstIndex,
            key.indexCount,
            key.baseVertex,
            key.indexTypeKey,
            key.pipeline,
            key.pipelineKey,
            key.vertexFormat,
            key.descriptorKey,
            key.material,
            key.materialKey,
            (int)sectionNode,
            64,
            -32
        );
    }

    private static void finishNormally(
        PersistentDrawTemplateTable table
    ) {
        Object marker = new Object();
        table.publishFrame(marker, 1, 1L, 2L, 1L, 0L);
        table.beginSolidSubmission(marker);
        table.recordSubmissionNanos(3L);
        table.finishOpaqueGroup(marker, true);
    }

    private static MemoryBudgetManager budgets() {
        return new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
    }

    private static MemoryBudgetManager lowCacheBudgets() {
        long mib = 1024L * 1024L;
        long[] ram = new long[7];
        long[] vram = new long[7];
        Arrays.fill(ram, mib);
        Arrays.fill(vram, mib);
        return new MemoryBudgetManager(
            new MemoryBudgetSettings(
                8L * mib,
                8L * mib,
                2L * mib,
                2L * mib,
                ram,
                vram
            )
        );
    }

    private static MemoryBudgetManager constrainedBudgets() {
        long mib = 1024L * 1024L;
        long[] ram = new long[7];
        long[] vram = new long[7];
        Arrays.fill(ram, mib);
        Arrays.fill(vram, mib);
        return new MemoryBudgetManager(
            new MemoryBudgetSettings(
                4L * mib,
                4L * mib,
                mib,
                mib,
                ram,
                vram
            )
        );
    }

    private static final class Key {
        private final Object mesh;
        private final long meshRevision;
        private final Object vertexBuffer;
        private final long vertexOffset;
        private final Object indexBuffer;
        private final long indexOffset;
        private final int firstIndex;
        private final int indexCount;
        private final int baseVertex;
        private final int indexTypeKey;
        private final Object pipeline;
        private final int pipelineKey;
        private final Object vertexFormat;
        private final int descriptorKey;
        private final Object material;
        private final int materialKey;

        private Key() {
            this(
                new Object(),
                1L,
                new Object(),
                128L,
                new Object(),
                64L,
                32,
                96,
                4,
                1,
                new Object(),
                11,
                new Object(),
                12,
                new Object(),
                13
            );
        }

        private Key(
            Object mesh,
            long meshRevision,
            Object vertexBuffer,
            long vertexOffset,
            Object indexBuffer,
            long indexOffset,
            int firstIndex,
            int indexCount,
            int baseVertex,
            int indexTypeKey,
            Object pipeline,
            int pipelineKey,
            Object vertexFormat,
            int descriptorKey,
            Object material,
            int materialKey
        ) {
            this.mesh = mesh;
            this.meshRevision = meshRevision;
            this.vertexBuffer = vertexBuffer;
            this.vertexOffset = vertexOffset;
            this.indexBuffer = indexBuffer;
            this.indexOffset = indexOffset;
            this.firstIndex = firstIndex;
            this.indexCount = indexCount;
            this.baseVertex = baseVertex;
            this.indexTypeKey = indexTypeKey;
            this.pipeline = pipeline;
            this.pipelineKey = pipelineKey;
            this.vertexFormat = vertexFormat;
            this.descriptorKey = descriptorKey;
            this.material = material;
            this.materialKey = materialKey;
        }

        private Key withChangedOwners() {
            return new Key(
                new Object(),
                this.meshRevision + 1L,
                new Object(),
                this.vertexOffset + 32L,
                new Object(),
                this.indexOffset + 16L,
                this.firstIndex + 1,
                this.indexCount + 1,
                this.baseVertex + 1,
                this.indexTypeKey + 1,
                new Object(),
                this.pipelineKey + 1,
                new Object(),
                this.descriptorKey + 1,
                new Object(),
                this.materialKey + 1
            );
        }
    }
}
