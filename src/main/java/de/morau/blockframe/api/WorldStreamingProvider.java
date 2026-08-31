package de.morau.blockframe.api;

/**
 * Capability boundary for ordered, backpressured world-entry and chunk
 * streaming. It does not grant permission to mutate live world state from
 * worker threads.
 */
public interface WorldStreamingProvider
    extends BlockframeProvider<WorldStreamingProvider.Capabilities> {

    record Capabilities(
        boolean spawnChunkPriority,
        boolean visibleChunkPriority,
        boolean boundedBackpressure,
        boolean overlappedIoAndDecompression,
        boolean immutableWorkerSnapshots,
        boolean mainThreadCommit,
        boolean dedicatedServerCompatible
    ) {
        public boolean safeWorkerExecution() {
            return this.immutableWorkerSnapshots && this.mainThreadCommit;
        }
    }
}
