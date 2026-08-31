package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeTerrainMemoryPoolsTest {
    @Test
    void payloadArenaReusesOnlyReturnedSizeClassAndClosesCleanly() {
        NativeTerrainPayloadArena arena =
            new NativeTerrainPayloadArena(64 * 1024, 2);
        NativeTerrainPayloadArena.Lease first =
            arena.acquire(5000);
        byte[] firstBytes = first.bytes();
        assertEquals(8192, first.capacity());
        first.close();
        NativeTerrainPayloadArena.Lease second =
            arena.acquire(7000);
        assertSame(firstBytes, second.bytes());
        assertEquals(1L, arena.snapshot().pooledHits());
        assertThrows(IllegalStateException.class, arena::close);
        second.close();
        arena.close();
        assertTrue(arena.snapshot().closed());
        assertEquals(0L, arena.snapshot().residentBytes());
    }

    @Test
    void snapshotPoolNeverSharesLiveArraysAndClearsReferences() {
        NativeTerrainSnapshotPool pool =
            new NativeTerrainSnapshotPool(1);
        NativeTerrainSnapshotPool.Storage first = pool.acquire();
        first.modelData[0] =
            net.neoforged.neoforge.model.data.ModelData.EMPTY;
        NativeTerrainSnapshotPool.Storage concurrent =
            pool.acquire();
        assertNotSame(first, concurrent);
        pool.release(first);
        pool.release(concurrent);
        NativeTerrainSnapshotPool.Storage reused = pool.acquire();
        assertSame(first, reused);
        assertEquals(null, reused.modelData[0]);
        pool.release(reused);
        pool.close();
        assertTrue(pool.snapshot().closed());
    }
}
