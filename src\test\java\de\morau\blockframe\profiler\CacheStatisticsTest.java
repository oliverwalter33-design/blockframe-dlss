package de.morau.blockframe.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CacheStatisticsTest {
    @Test
    void recordsCacheOutcomesWithoutCombiningHitsAndRejections() {
        CacheStatistics statistics = new CacheStatistics();
        assertFalse(statistics.attached());
        statistics.markAttached();

        statistics.recordHit(128L, 10L);
        statistics.recordMiss(20L);
        statistics.recordRejectedEntry(30L);
        statistics.recordWrite(256L, 40L);
        statistics.setBytesOnDisk(512L);

        CacheStatistics.Snapshot snapshot = statistics.snapshot();
        assertEquals(1L, snapshot.hits());
        assertEquals(1L, snapshot.misses());
        assertEquals(1L, snapshot.rejectedEntries());
        assertEquals(1L, snapshot.writtenEntries());
        assertEquals(2L, snapshot.lookups());
        assertEquals(0.5D, snapshot.hitRate());
        assertEquals(128L, snapshot.loadedBytes());
        assertEquals(256L, snapshot.writtenBytes());
        assertEquals(60L, snapshot.loadNanos());
        assertEquals(40L, snapshot.saveNanos());
        assertEquals(512L, snapshot.bytesOnDisk());
        assertTrue(snapshot.attached());
    }

    @Test
    void rejectsNegativeMeasurements() {
        CacheStatistics statistics = new CacheStatistics();
        assertThrows(IllegalArgumentException.class, () -> statistics.recordHit(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> statistics.recordMiss(-1L));
        assertThrows(IllegalArgumentException.class, () -> statistics.setBytesOnDisk(-1L));
    }
}
