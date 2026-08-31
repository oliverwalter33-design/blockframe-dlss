package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceTransactionTest {
    @Test
    void rollsBackInReverseOrderAfterEveryAcquisitionStage() {
        int capacity = 16;
        for (int completedStages = 0; completedStages <= capacity; completedStages++) {
            List<Integer> closed = new ArrayList<>();
            ResourceTransaction transaction = new ResourceTransaction(capacity);
            for (int stage = 0; stage < completedStages; stage++) {
                TrackingResource resource = new TrackingResource(
                    stage,
                    closed,
                    null
                );
                assertSame(resource, transaction.own(resource));
            }

            transaction.close();
            transaction.close();

            List<Integer> expected = new ArrayList<>();
            for (int stage = completedStages - 1; stage >= 0; stage--) {
                expected.add(stage);
            }
            assertEquals(expected, closed, "completedStages=" + completedStages);
            assertTrue(transaction.rollbackSucceeded());
            assertEquals(0, transaction.rollbackFailures());
        }
    }

    @Test
    void rollbackContinuesPastCloseFailuresAndAttachesConcreteCauses() {
        List<Integer> closed = new ArrayList<>();
        Exception checkedFailure = new Exception("checked close failure");
        AssertionError assertionFailure =
            new AssertionError("assertion close failure");
        ResourceTransaction transaction = new ResourceTransaction(4);
        transaction.own(new TrackingResource(0, closed, null));
        transaction.own(new TrackingResource(1, closed, checkedFailure));
        transaction.own(new TrackingResource(2, closed, assertionFailure));
        transaction.own(new TrackingResource(3, closed, null));

        transaction.close();

        assertEquals(List.of(3, 2, 1, 0), closed);
        assertFalse(transaction.rollbackSucceeded());
        assertEquals(2, transaction.rollbackFailures());
        assertSame(assertionFailure, transaction.rollbackFailure());
        assertEquals(
            List.of(checkedFailure),
            List.of(transaction.rollbackFailure().getSuppressed())
        );

        RuntimeException allocationFailure =
            new RuntimeException("allocation failure");
        transaction.attachRollbackFailureTo(allocationFailure);
        assertEquals(
            List.of(assertionFailure),
            List.of(allocationFailure.getSuppressed())
        );
    }

    @Test
    void commitTransfersOwnershipAndCannotBeRepeatedOrExtended() {
        List<Integer> closed = new ArrayList<>();
        ResourceTransaction transaction = new ResourceTransaction(2);
        transaction.own(new TrackingResource(0, closed, null));
        transaction.own(new TrackingResource(1, closed, null));

        transaction.commit();
        transaction.close();

        assertEquals(List.of(), closed);
        assertFalse(transaction.rollbackSucceeded());
        assertEquals(0, transaction.rollbackFailures());
        assertThrows(IllegalStateException.class, transaction::commit);
        assertThrows(
            IllegalStateException.class,
            () -> transaction.own(new TrackingResource(2, closed, null))
        );
        assertEquals(List.of(2), closed);
    }

    @Test
    void rollbackIsTerminalAndClosesResourcesOfferedAfterward() {
        List<Integer> closed = new ArrayList<>();
        ResourceTransaction transaction = new ResourceTransaction(1);
        transaction.own(new TrackingResource(0, closed, null));
        transaction.close();

        IllegalStateException rejection = assertThrows(
            IllegalStateException.class,
            () -> transaction.own(new TrackingResource(1, closed, null))
        );
        assertEquals(
            "transaction is already rolled back",
            rejection.getMessage()
        );
        assertThrows(IllegalStateException.class, transaction::commit);
        transaction.close();

        assertEquals(List.of(0, 1), closed);
    }

    @Test
    void capacityRejectionClosesOfferedResourceAndSuppressesCloseFailure() {
        List<Integer> closed = new ArrayList<>();
        ResourceTransaction transaction = new ResourceTransaction(1);
        transaction.own(new TrackingResource(0, closed, null));
        AssertionError closeFailure =
            new AssertionError("rejected resource close failure");

        IllegalStateException rejection = assertThrows(
            IllegalStateException.class,
            () -> transaction.own(
                new TrackingResource(1, closed, closeFailure)
            )
        );

        assertEquals(
            "resource transaction capacity exceeded",
            rejection.getMessage()
        );
        assertEquals(
            List.of(closeFailure),
            List.of(rejection.getSuppressed())
        );
        transaction.close();
        assertEquals(List.of(1, 0), closed);
    }

    private static final class TrackingResource implements AutoCloseable {
        private final int id;
        private final List<Integer> closed;
        private final Throwable failure;

        private TrackingResource(
            int id,
            List<Integer> closed,
            Throwable failure
        ) {
            this.id = id;
            this.closed = closed;
            this.failure = failure;
        }

        @Override
        public void close() throws Exception {
            this.closed.add(this.id);
            if (this.failure instanceof Exception exception) {
                throw exception;
            }
            if (this.failure instanceof Error error) {
                throw error;
            }
        }
    }
}
