package de.morau.blockframe.core.budget;

/** Bounded, recoverable rejection before a physical BlockFrame allocation. */
public final class MemoryBudgetExceededException extends RuntimeException {
    public MemoryBudgetExceededException(String message) {
        super(message);
    }
}
