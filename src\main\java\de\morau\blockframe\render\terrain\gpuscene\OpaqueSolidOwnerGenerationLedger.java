package de.morau.blockframe.render.terrain.gpuscene;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Event-driven generation ledger for Mojang-owned meshes and buffer ranges.
 *
 * <p>The ledger deliberately offers no enumeration API. Publication,
 * invalidation and validation are identity-keyed O(1) owner operations, so a
 * buffer replacement can never turn into a per-frame slot scan.</p>
 */
public final class OpaqueSolidOwnerGenerationLedger {
    private final Map<Object, MeshBinding> meshes =
        new IdentityHashMap<>();
    private final Map<Object, BufferBinding> buffers =
        new IdentityHashMap<>();
    private final Map<Object, Map<Object, RangeBinding>> ranges =
        new IdentityHashMap<>();
    private long nextGeneration = 1L;

    public synchronized long publishMesh(
        Object sectionOwner,
        Object meshIdentity
    ) {
        Objects.requireNonNull(sectionOwner, "sectionOwner");
        Objects.requireNonNull(meshIdentity, "meshIdentity");
        MeshBinding current = this.meshes.get(sectionOwner);
        if (current != null && current.meshIdentity() == meshIdentity) {
            return current.generation();
        }
        if (current != null && !current.invalidated()) {
            throw new IllegalStateException(
                "mesh replacement was not invalidated first"
            );
        }
        long generation = this.nextGeneration();
        this.meshes.put(
            sectionOwner,
            new MeshBinding(meshIdentity, generation, false)
        );
        return generation;
    }

    public synchronized boolean invalidateMeshBeforeReplace(
        Object sectionOwner,
        Object expectedMesh
    ) {
        MeshBinding current = this.meshes.get(sectionOwner);
        if (
            current == null
                || current.meshIdentity() != expectedMesh
                || current.invalidated()
        ) {
            return false;
        }
        this.meshes.put(
            sectionOwner,
            new MeshBinding(
                current.meshIdentity(),
                current.generation(),
                true
            )
        );
        return true;
    }

    public synchronized long publishBuffer(
        Object bufferIdentity,
        long nativeHandle
    ) {
        Objects.requireNonNull(bufferIdentity, "bufferIdentity");
        if (nativeHandle == 0L) {
            throw new IllegalArgumentException("nativeHandle is null");
        }
        BufferBinding current = this.buffers.get(bufferIdentity);
        if (
            current != null
                && current.nativeHandle() == nativeHandle
                && !current.invalidated()
        ) {
            return current.generation();
        }
        long generation = this.nextGeneration();
        this.buffers.put(
            bufferIdentity,
            new BufferBinding(nativeHandle, generation, false)
        );
        return generation;
    }

    public synchronized boolean invalidateBufferBeforeClose(
        Object bufferIdentity,
        long expectedGeneration
    ) {
        BufferBinding current = this.buffers.get(bufferIdentity);
        if (
            current == null
                || current.generation() != expectedGeneration
                || current.invalidated()
        ) {
            return false;
        }
        this.buffers.put(
            bufferIdentity,
            new BufferBinding(
                current.nativeHandle(),
                current.generation(),
                true
            )
        );
        return true;
    }

    public synchronized RangeBinding publishRange(
        Object allocationOwner,
        Object allocationKey,
        Object bufferIdentity,
        long bufferGeneration,
        long offset,
        long length
    ) {
        Objects.requireNonNull(allocationOwner, "allocationOwner");
        Objects.requireNonNull(allocationKey, "allocationKey");
        Objects.requireNonNull(bufferIdentity, "bufferIdentity");
        BufferBinding buffer = this.buffers.get(bufferIdentity);
        if (
            buffer == null
                || buffer.invalidated()
                || buffer.generation() != bufferGeneration
        ) {
            throw new IllegalStateException(
                "range references an unpublished buffer generation"
            );
        }
        requireRange(offset, length);
        Map<Object, RangeBinding> ownerRanges =
            this.ranges.computeIfAbsent(
                allocationOwner,
                ignored -> new IdentityHashMap<>()
            );
        RangeBinding current = ownerRanges.get(allocationKey);
        if (current != null && !current.invalidated()) {
            throw new IllegalStateException(
                "range replacement was not invalidated first"
            );
        }
        RangeBinding published = new RangeBinding(
            bufferIdentity,
            bufferGeneration,
            this.nextGeneration(),
            offset,
            length,
            false
        );
        ownerRanges.put(allocationKey, published);
        return published;
    }

    public synchronized boolean invalidateRangeBeforeFree(
        Object allocationOwner,
        Object allocationKey,
        long expectedGeneration
    ) {
        Map<Object, RangeBinding> ownerRanges =
            this.ranges.get(allocationOwner);
        RangeBinding current = ownerRanges == null
            ? null
            : ownerRanges.get(allocationKey);
        if (
            current == null
                || current.generation() != expectedGeneration
                || current.invalidated()
        ) {
            return false;
        }
        ownerRanges.put(
            allocationKey,
            new RangeBinding(
                current.bufferIdentity(),
                current.bufferGeneration(),
                current.generation(),
                current.offset(),
                current.length(),
                true
            )
        );
        return true;
    }

    public synchronized boolean meshGenerationCurrent(
        Object sectionOwner,
        Object meshIdentity,
        long generation
    ) {
        MeshBinding current = this.meshes.get(sectionOwner);
        return current != null
            && !current.invalidated()
            && current.meshIdentity() == meshIdentity
            && current.generation() == generation;
    }

    public synchronized boolean bufferGenerationCurrent(
        Object bufferIdentity,
        long generation
    ) {
        BufferBinding current = this.buffers.get(bufferIdentity);
        return current != null
            && !current.invalidated()
            && current.generation() == generation;
    }

    public synchronized boolean rangeGenerationCurrent(
        Object allocationOwner,
        Object allocationKey,
        long generation
    ) {
        Map<Object, RangeBinding> ownerRanges =
            this.ranges.get(allocationOwner);
        RangeBinding current = ownerRanges == null
            ? null
            : ownerRanges.get(allocationKey);
        return current != null
            && !current.invalidated()
            && current.generation() == generation;
    }

    public synchronized void clear() {
        this.meshes.clear();
        this.buffers.clear();
        this.ranges.clear();
        this.nextGeneration();
    }

    private long nextGeneration() {
        long result = this.nextGeneration;
        if (result == Long.MAX_VALUE) {
            throw new IllegalStateException("generation space exhausted");
        }
        this.nextGeneration = result + 1L;
        return result;
    }

    private static void requireRange(long offset, long length) {
        if (
            offset < 0L
                || length <= 0L
                || offset > Long.MAX_VALUE - length
        ) {
            throw new IllegalArgumentException("invalid buffer range");
        }
    }

    private record MeshBinding(
        Object meshIdentity,
        long generation,
        boolean invalidated
    ) {
    }

    private record BufferBinding(
        long nativeHandle,
        long generation,
        boolean invalidated
    ) {
    }

    public record RangeBinding(
        Object bufferIdentity,
        long bufferGeneration,
        long generation,
        long offset,
        long length,
        boolean invalidated
    ) {
    }
}
