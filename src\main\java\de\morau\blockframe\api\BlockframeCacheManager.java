package de.morau.blockframe.api;

import java.util.Objects;

/**
 * Cache safety and observability contract. It intentionally does not expose
 * storage methods in Phase 1; atomic writer and lookup handles belong to the
 * dedicated cache phase once their on-disk schema has been validated.
 */
public interface BlockframeCacheManager
    extends BlockframeProvider<BlockframeCacheManager.Capabilities> {

    Statistics statistics();

    record Capabilities(
        boolean atomicReplacement,
        boolean checksums,
        boolean schemaValidation,
        boolean targetedInvalidation,
        boolean configurableSizeLimit,
        boolean leastRecentlyUsedCleanup,
        boolean rejectsMutableWorldState
    ) {
    }

    record Statistics(
        long hits,
        long misses,
        long rejectedEntries,
        long writtenEntries,
        long bytesOnDisk
    ) {
        public Statistics {
            hits = nonNegative(hits, "hits");
            misses = nonNegative(misses, "misses");
            rejectedEntries = nonNegative(rejectedEntries, "rejectedEntries");
            writtenEntries = nonNegative(writtenEntries, "writtenEntries");
            bytesOnDisk = nonNegative(bytesOnDisk, "bytesOnDisk");
        }

        public long lookups() {
            return this.hits + this.misses;
        }

        public double hitRate() {
            long lookups = this.lookups();
            return lookups == 0L ? 0.0D : (double) this.hits / lookups;
        }

        private static long nonNegative(long value, String name) {
            if (value < 0L) {
                throw new IllegalArgumentException(Objects.requireNonNull(name, "name") + " must be non-negative");
            }
            return value;
        }
    }
}
