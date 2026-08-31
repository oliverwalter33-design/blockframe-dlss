package de.morau.blockframe.benchmark.phase2a0b;

/**
 * Ordered, fail-closed states of one explicit Phase 2A.0B replay run.
 */
public enum BenchmarkState {
    PREFLIGHT,
    WORLD_WAIT,
    CHUNK_WARMUP,
    WARMUP,
    MEASURE,
    REFERENCE_CAPTURE,
    COMPLETE,
    FAILED;

    public boolean terminal() {
        return this == COMPLETE || this == FAILED;
    }
}
