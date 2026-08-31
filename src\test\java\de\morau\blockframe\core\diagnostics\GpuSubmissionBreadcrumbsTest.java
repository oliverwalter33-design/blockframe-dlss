package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GpuSubmissionBreadcrumbsTest {
    @Test
    void reservesAndReleasesTheExactDiagnosticsFootprint() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            GpuSubmissionBreadcrumbs.tryCreate(budgets);

        assertNotNull(breadcrumbs);
        assertEquals(64, GpuSubmissionBreadcrumbs.CAPACITY);
        assertEquals(3_072L, GpuSubmissionBreadcrumbs.REQUESTED_BYTES);
        assertEquals(3_072L, GpuSubmissionBreadcrumbs.COMMITTED_BYTES);
        assertEquals(
            3_072L,
            budgets.snapshot().requestedBytes(MemoryKind.RAM)
        );
        assertEquals(
            3_072L,
            budgets.snapshot().usedBytes(
                MemoryKind.RAM,
                MemoryCategory.DIAGNOSTICS
            )
        );
        assertEquals(1, budgets.snapshot().outstanding());

        breadcrumbs.close();

        assertEquals(0L, budgets.snapshot().usedBytes(MemoryKind.RAM));
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(0L, budgets.snapshot().leaks());
        budgets.close();
    }

    @Test
    void diagnosticsBudgetRejectionReturnsNullWithoutAStandingLease() {
        MemoryBudgetManager budgets = managerWithDiagnosticsLimit(
            GpuSubmissionBreadcrumbs.COMMITTED_BYTES - 1L
        );

        assertNull(GpuSubmissionBreadcrumbs.tryCreate(budgets));
        assertEquals(1L, budgets.snapshot().rejections());
        assertEquals(0, budgets.snapshot().outstanding());
        assertEquals(
            0L,
            budgets.snapshot().usedBytes(
                MemoryKind.RAM,
                MemoryCategory.DIAGNOSTICS
            )
        );
        budgets.close();
    }

    @Test
    void encodedSubmittedAndCompletedRemainDistinctExplicitStates() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            requiredBreadcrumbs(budgets);

        breadcrumbs.recordEncoded(
            7L,
            GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
        );
        GpuSubmissionBreadcrumbs.Snapshot encoded =
            breadcrumbs.snapshot();
        assertEquals(1, encoded.encodedEntries());
        assertEquals(0, encoded.submittedEntries());
        assertEquals(0, encoded.completedEntries());
        assertEquals(7L, encoded.lastEncodedFrame());
        assertEquals(-1L, encoded.lastSubmittedIndex());
        assertEquals(-1L, encoded.lastCompletedIndex());

        assertEquals(2, breadcrumbs.recordSubmit(7L, 11L));
        GpuSubmissionBreadcrumbs.Snapshot submitted =
            breadcrumbs.snapshot();
        assertEquals(0, submitted.encodedEntries());
        assertEquals(2, submitted.submittedEntries());
        assertEquals(0, submitted.completedEntries());
        assertEquals(7L, submitted.lastSubmittedFrame());
        assertEquals(11L, submitted.lastSubmittedIndex());
        assertEquals(-1L, submitted.lastCompletedIndex());

        assertEquals(2, breadcrumbs.recordCompletion(11L));
        GpuSubmissionBreadcrumbs.Snapshot completed =
            breadcrumbs.snapshot();
        assertEquals(0, completed.encodedEntries());
        assertEquals(0, completed.submittedEntries());
        assertEquals(2, completed.completedEntries());
        assertEquals(7L, completed.lastCompletedFrame());
        assertEquals(11L, completed.lastCompletedIndex());

        breadcrumbs.close();
        budgets.close();
    }

    @Test
    void completionAdvancesOnlyThroughTheProvenCompletedSubmitIndex() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            requiredBreadcrumbs(budgets);

        breadcrumbs.recordEncoded(
            20L,
            GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
        );
        assertEquals(2, breadcrumbs.recordSubmit(20L, 5L));
        breadcrumbs.recordEncoded(
            21L,
            GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
        );
        assertEquals(2, breadcrumbs.recordSubmit(21L, 9L));

        assertEquals(2, breadcrumbs.recordCompletion(5L));
        GpuSubmissionBreadcrumbs.Snapshot firstCompletion =
            breadcrumbs.snapshot();
        assertEquals(2, firstCompletion.completedEntries());
        assertEquals(2, firstCompletion.submittedEntries());
        assertEquals(5L, firstCompletion.lastCompletedIndex());
        assertEquals(20L, firstCompletion.lastCompletedFrame());

        assertEquals(0, breadcrumbs.recordCompletion(8L));
        GpuSubmissionBreadcrumbs.Snapshot unprovenCompletion =
            breadcrumbs.snapshot();
        assertEquals(2, unprovenCompletion.completedEntries());
        assertEquals(2, unprovenCompletion.submittedEntries());
        assertEquals(8L, unprovenCompletion.lastCompletedIndex());
        assertEquals(20L, unprovenCompletion.lastCompletedFrame());

        assertEquals(2, breadcrumbs.recordCompletion(9L));
        GpuSubmissionBreadcrumbs.Snapshot finalCompletion =
            breadcrumbs.snapshot();
        assertEquals(4, finalCompletion.completedEntries());
        assertEquals(0, finalCompletion.submittedEntries());
        assertEquals(9L, finalCompletion.lastCompletedIndex());
        assertEquals(21L, finalCompletion.lastCompletedFrame());

        breadcrumbs.close();
        budgets.close();
    }

    @Test
    void encoderDestroyWithoutCompletionProofAbandonsButNeverCompletes() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            requiredBreadcrumbs(budgets);

        breadcrumbs.recordEncoded(
            30L,
            GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
        );
        breadcrumbs.recordSubmit(30L, 3L);
        breadcrumbs.recordCompletion(3L);

        breadcrumbs.recordEncoded(
            31L,
            GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
        );
        breadcrumbs.recordSubmit(31L, 4L);
        breadcrumbs.recordEncoded(
            32L,
            GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
        );

        GpuSubmissionBreadcrumbs.Snapshot beforeDestroy =
            breadcrumbs.snapshot();
        assertEquals(2, beforeDestroy.completedEntries());
        assertEquals(2, beforeDestroy.submittedEntries());
        assertEquals(1, beforeDestroy.encodedEntries());
        assertEquals(0, beforeDestroy.abandonedEntries());

        assertEquals(
            3,
            breadcrumbs.encoderDestroyedWithoutCompletionProof()
        );

        GpuSubmissionBreadcrumbs.Snapshot afterDestroy =
            breadcrumbs.snapshot();
        assertEquals(2, afterDestroy.completedEntries());
        assertEquals(0, afterDestroy.submittedEntries());
        assertEquals(0, afterDestroy.encodedEntries());
        assertEquals(3, afterDestroy.abandonedEntries());
        assertEquals(3L, afterDestroy.abandoned());
        assertEquals(-1L, afterDestroy.lastCompletedIndex());
        assertEquals(30L, afterDestroy.lastCompletedFrame());
        assertEquals(2L, afterDestroy.deviceGeneration());

        breadcrumbs.close();
        budgets.close();
    }

    @Test
    void ringIsBoundedAndReportsEveryOverwrite() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            requiredBreadcrumbs(budgets);

        for (int index = 0; index < GpuSubmissionBreadcrumbs.CAPACITY + 9;
             index++) {
            breadcrumbs.recordEncoded(
                index,
                GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
            );
        }

        GpuSubmissionBreadcrumbs.Snapshot snapshot =
            breadcrumbs.snapshot();
        assertEquals(
            GpuSubmissionBreadcrumbs.CAPACITY + 9L,
            snapshot.recorded()
        );
        assertEquals(9L, snapshot.overwritten());
        assertEquals(
            GpuSubmissionBreadcrumbs.CAPACITY,
            snapshot.encodedEntries()
        );
        assertEquals(0, snapshot.submittedEntries());
        assertEquals(0, snapshot.completedEntries());

        breadcrumbs.close();
        budgets.close();
    }

    @Test
    void everyOperationIsConfinedToTheCreatingThread() throws Exception {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            requiredBreadcrumbs(budgets);
        AtomicReference<Throwable> accessFailure =
            new AtomicReference<>();
        AtomicReference<Throwable> closeFailure =
            new AtomicReference<>();

        Thread accessThread = new Thread(
            () -> captureFailure(
                accessFailure,
                () -> breadcrumbs.recordEncoded(
                    1L,
                    GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
                )
            ),
            "gpu-breadcrumb-access-test"
        );
        accessThread.start();
        accessThread.join();

        Thread closeThread = new Thread(
            () -> captureFailure(closeFailure, breadcrumbs::close),
            "gpu-breadcrumb-close-test"
        );
        closeThread.start();
        closeThread.join();

        assertInstanceOf(
            IllegalStateException.class,
            accessFailure.get()
        );
        assertInstanceOf(
            IllegalStateException.class,
            closeFailure.get()
        );
        assertEquals(1, budgets.snapshot().outstanding());

        breadcrumbs.close();
        assertEquals(0, budgets.snapshot().outstanding());
        budgets.close();
    }

    @Test
    void invalidInputsFailClosedAndCloseIsIdempotent() {
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        GpuSubmissionBreadcrumbs breadcrumbs =
            requiredBreadcrumbs(budgets);

        assertThrows(
            IllegalArgumentException.class,
            () -> breadcrumbs.recordEncoded(
                -1L,
                GpuSubmissionBreadcrumbs.PASS_MOTION_COMPUTE
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> breadcrumbs.recordEncoded(0L, Integer.MIN_VALUE)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> breadcrumbs.recordSubmit(0L, -1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> breadcrumbs.recordCompletion(-1L)
        );

        breadcrumbs.close();
        breadcrumbs.close();
        assertEquals(0, budgets.snapshot().outstanding());
        assertThrows(
            IllegalStateException.class,
            breadcrumbs::snapshot
        );
        assertThrows(
            IllegalStateException.class,
            () -> breadcrumbs.recordEncoded(
                1L,
                GpuSubmissionBreadcrumbs.PASS_DLSS_EVALUATE
            )
        );
        budgets.close();
    }

    private static GpuSubmissionBreadcrumbs requiredBreadcrumbs(
        MemoryBudgetManager budgets
    ) {
        GpuSubmissionBreadcrumbs breadcrumbs =
            GpuSubmissionBreadcrumbs.tryCreate(budgets);
        assertNotNull(breadcrumbs);
        return breadcrumbs;
    }

    private static MemoryBudgetManager managerWithDiagnosticsLimit(
        long diagnosticsBytes
    ) {
        long[] ram = categoryLimits(1L << 20);
        long[] vram = categoryLimits(1L << 20);
        ram[MemoryCategory.DIAGNOSTICS.ordinal()] = diagnosticsBytes;
        return new MemoryBudgetManager(
            new MemoryBudgetSettings(
                1L << 20,
                1L << 20,
                0L,
                0L,
                ram,
                vram
            )
        );
    }

    private static long[] categoryLimits(long bytes) {
        long[] limits =
            new long[MemoryCategory.values().length];
        Arrays.fill(limits, bytes);
        return limits;
    }

    private static void captureFailure(
        AtomicReference<Throwable> target,
        ThrowingAction action
    ) {
        try {
            action.run();
        } catch (Throwable failure) {
            target.set(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
