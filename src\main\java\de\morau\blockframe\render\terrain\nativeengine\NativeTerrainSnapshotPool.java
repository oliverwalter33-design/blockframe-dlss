package de.morau.blockframe.render.terrain.nativeengine;

import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.model.data.ModelData;

/**
 * Bounded reusable primitive/reference storage for immutable section leases.
 *
 * <p>A storage object has exactly one snapshot owner while leased. Returning
 * it clears every reference lane before it can cross to another job or
 * generation. The pool never hands the same arrays to concurrent snapshots.</p>
 */
public final class NativeTerrainSnapshotPool implements AutoCloseable {
    public record Snapshot(
        long acquisitions,
        long pooledHits,
        long allocations,
        long returns,
        long discarded,
        int available,
        int outstanding,
        boolean closed
    ) {
    }

    static final class Storage {
        final BlockState[] blockStates =
            new BlockState[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final FluidState[] fluidStates =
            new FluidState[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final ModelData[] modelData =
            new ModelData[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final byte[] blockLight =
            new byte[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final byte[] skyLight =
            new byte[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final int[] grassTint =
            new int[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final int[] foliageTint =
            new int[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final int[] dryFoliageTint =
            new int[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
        final int[] waterTint =
            new int[
                MinecraftTerrainSectionSnapshot.CELL_COUNT
            ];
    }

    private final int capacity;
    private final ArrayDeque<Storage> free = new ArrayDeque<>();
    private long acquisitions;
    private long pooledHits;
    private long allocations;
    private long returns;
    private long discarded;
    private int outstanding;
    private boolean closed;

    public NativeTerrainSnapshotPool(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "snapshot pool capacity must be positive"
            );
        }
        this.capacity = capacity;
    }

    synchronized Storage acquire() {
        requireOpen();
        Storage storage = this.free.pollFirst();
        this.acquisitions++;
        if (storage == null) {
            storage = new Storage();
            this.allocations++;
        } else {
            this.pooledHits++;
        }
        this.outstanding++;
        return storage;
    }

    synchronized void release(Storage storage) {
        if (storage == null) {
            throw new IllegalArgumentException(
                "snapshot storage is missing"
            );
        }
        clearReferences(storage);
        this.outstanding--;
        if (this.closed || this.free.size() >= this.capacity) {
            this.discarded++;
            return;
        }
        this.free.addFirst(storage);
        this.returns++;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.acquisitions,
            this.pooledHits,
            this.allocations,
            this.returns,
            this.discarded,
            this.free.size(),
            this.outstanding,
            this.closed
        );
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        if (this.outstanding != 0) {
            throw new IllegalStateException(
                "snapshot pool still has outstanding leases"
            );
        }
        this.free.clear();
        this.closed = true;
    }

    private static void clearReferences(Storage storage) {
        Arrays.fill(storage.blockStates, null);
        Arrays.fill(storage.fluidStates, null);
        Arrays.fill(storage.modelData, null);
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                "snapshot pool is closed"
            );
        }
    }
}
