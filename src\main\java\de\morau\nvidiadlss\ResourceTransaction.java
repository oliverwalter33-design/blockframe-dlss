package de.morau.nvidiadlss;

import java.util.Objects;

/**
 * Reverse-order rollback for the rare resource creation/resize path.
 * Committed ownership is transferred to the final resource set.
 */
final class ResourceTransaction implements AutoCloseable {
    private final AutoCloseable[] resources;
    private int size;
    private boolean committed;
    private boolean rollbackAttempted;
    private int rollbackFailures;
    private Throwable rollbackFailure;

    ResourceTransaction(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.resources = new AutoCloseable[capacity];
    }

    <T extends AutoCloseable> T own(T resource) {
        Objects.requireNonNull(resource, "resource");
        if (this.committed) {
            throw reject(
                resource,
                "transaction is already committed"
            );
        }
        if (this.rollbackAttempted) {
            throw reject(
                resource,
                "transaction is already rolled back"
            );
        }
        if (this.size == this.resources.length) {
            throw reject(
                resource,
                "resource transaction capacity exceeded"
            );
        }
        this.resources[this.size++] = resource;
        return resource;
    }

    void commit() {
        if (this.committed) {
            throw new IllegalStateException("transaction is already committed");
        }
        if (this.rollbackAttempted) {
            throw new IllegalStateException(
                "transaction is already rolled back"
            );
        }
        this.committed = true;
    }

    boolean rollbackSucceeded() {
        return this.rollbackAttempted && this.rollbackFailures == 0;
    }

    int rollbackFailures() {
        return this.rollbackFailures;
    }

    Throwable rollbackFailure() {
        return this.rollbackFailure;
    }

    void attachRollbackFailureTo(Throwable primaryFailure) {
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        if (
            this.rollbackFailure != null
                && this.rollbackFailure != primaryFailure
        ) {
            primaryFailure.addSuppressed(this.rollbackFailure);
        }
    }

    @Override
    public void close() {
        if (this.committed || this.rollbackAttempted) {
            return;
        }
        this.rollbackAttempted = true;
        for (int index = this.size - 1; index >= 0; index--) {
            AutoCloseable resource = this.resources[index];
            this.resources[index] = null;
            try {
                resource.close();
            } catch (Throwable error) {
                this.rollbackFailures++;
                if (this.rollbackFailure == null) {
                    this.rollbackFailure = error;
                } else if (this.rollbackFailure != error) {
                    this.rollbackFailure.addSuppressed(error);
                }
            }
        }
        this.size = 0;
    }

    private static IllegalStateException reject(
        AutoCloseable resource,
        String message
    ) {
        IllegalStateException rejection = new IllegalStateException(message);
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            rejection.addSuppressed(closeFailure);
        }
        return rejection;
    }
}
