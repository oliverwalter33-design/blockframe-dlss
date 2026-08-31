package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generation-bound, collision-free surface IDs for the native frame ABI.
 *
 * <p>IDs are sequential in first-observation order. No hash is exposed as an
 * identity, and ID zero remains reserved for background or invalid pixels.</p>
 */
public final class NativeTerrainSurfaceIdRegistry
    implements AutoCloseable {
    public static final int INVALID_ID =
        NativeTerrainFrameOutputAbi.INVALID_SURFACE_ID;

    public record SurfaceKey(
        MaterialBinding material,
        ShaderContract shader
    ) {
        public SurfaceKey {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(shader, "shader");
        }
    }

    public record Entry(int id, SurfaceKey key) {
        public Entry {
            if (id <= INVALID_ID) {
                throw new IllegalArgumentException(
                    "surface ID must be positive"
                );
            }
            Objects.requireNonNull(key, "key");
        }
    }

    private final long generation;
    private final int capacity;
    private final Map<SurfaceKey, Integer> ids =
        new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private boolean closed;

    public NativeTerrainSurfaceIdRegistry(
        long generation,
        int capacity
    ) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "surface registry generation must be positive"
            );
        }
        if (capacity <= 0 || capacity == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "surface registry capacity is invalid"
            );
        }
        this.generation = generation;
        this.capacity = capacity;
    }

    public long generation() {
        return this.generation;
    }

    public int capacity() {
        return this.capacity;
    }

    public synchronized int size() {
        requireOpen();
        return this.entries.size();
    }

    public synchronized int idFor(
        MaterialBinding material,
        ShaderContract shader
    ) {
        requireOpen();
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(shader, "shader");
        requireGeneration(material.registryGeneration());
        SurfaceKey key = new SurfaceKey(material, shader);
        Integer existing = this.ids.get(key);
        if (existing != null) {
            return existing;
        }
        if (this.entries.size() >= this.capacity) {
            throw new IllegalStateException(
                "surface registry capacity is exhausted"
            );
        }
        int id = this.entries.size() + 1;
        this.ids.put(key, id);
        this.entries.add(new Entry(id, key));
        return id;
    }

    public synchronized SurfaceKey requireKey(
        long expectedGeneration,
        int id
    ) {
        requireOpen();
        requireGeneration(expectedGeneration);
        if (id <= INVALID_ID || id > this.entries.size()) {
            throw new IllegalArgumentException(
                "surface ID is invalid for this generation"
            );
        }
        return this.entries.get(id - 1).key();
    }

    public synchronized List<Entry> snapshot(
        long expectedGeneration
    ) {
        requireOpen();
        requireGeneration(expectedGeneration);
        return List.copyOf(this.entries);
    }

    /**
     * Closes this generation and returns an empty successor.
     */
    public synchronized NativeTerrainSurfaceIdRegistry reload(
        long nextGeneration
    ) {
        requireOpen();
        if (nextGeneration <= this.generation) {
            throw new IllegalArgumentException(
                "reload generation must increase"
            );
        }
        NativeTerrainSurfaceIdRegistry successor =
            new NativeTerrainSurfaceIdRegistry(
                nextGeneration,
                this.capacity
            );
        closeInternal();
        return successor;
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            closeInternal();
        }
    }

    private void requireGeneration(long expectedGeneration) {
        if (expectedGeneration != this.generation) {
            throw new IllegalArgumentException(
                "surface registry generation is stale"
            );
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                "surface registry is closed"
            );
        }
    }

    private void closeInternal() {
        this.closed = true;
        this.ids.clear();
        this.entries.clear();
    }
}
