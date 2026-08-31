package de.morau.blockframe.core.state;

/** Observable fail-open state of the run-state persistence layer. */
public enum RunStatePersistenceStatus {
    READ_WRITE,
    READ_ONLY_LOCK_CONFLICT,
    READ_ONLY_FUTURE_FORMAT,
    READ_ONLY_AMBIGUOUS_GENERATION,
    READ_ONLY_GENERATION_OVERFLOW,
    READ_ONLY_CORRUPT_STATE,
    READ_ONLY_IO_FAILURE,
    CLOSED
}
