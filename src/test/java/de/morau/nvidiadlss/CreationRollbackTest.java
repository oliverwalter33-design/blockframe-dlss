package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CreationRollbackTest {
    @Test
    void reportsSuccessfulAndAbsentCleanupAsClosed() {
        AtomicBoolean closed = new AtomicBoolean();
        Throwable creationFailure = new IllegalStateException("creation");

        assertTrue(
            CreationRollback.close(
                () -> closed.set(true),
                creationFailure
            )
        );
        assertTrue(closed.get());
        assertTrue(
            CreationRollback.close(
                null,
                creationFailure
            )
        );
        assertEquals(0, creationFailure.getSuppressed().length);
    }

    @Test
    void failedCleanupIsReportedAndAttachedToTheCreationFailure() {
        Throwable creationFailure = new IllegalStateException("creation");
        RuntimeException closeFailure = new IllegalStateException("close");

        boolean closed = CreationRollback.close(
            () -> {
                throw closeFailure;
            },
            creationFailure
        );

        assertFalse(closed);
        assertEquals(1, creationFailure.getSuppressed().length);
        assertSame(closeFailure, creationFailure.getSuppressed()[0]);
    }

    @Test
    void selfSuppressionCannotInterruptConservativeRetention() {
        RuntimeException sharedFailure = new IllegalStateException("shared");

        boolean closed = CreationRollback.close(
            () -> {
                throw sharedFailure;
            },
            sharedFailure
        );

        assertFalse(closed);
        assertEquals(0, sharedFailure.getSuppressed().length);
    }
}
