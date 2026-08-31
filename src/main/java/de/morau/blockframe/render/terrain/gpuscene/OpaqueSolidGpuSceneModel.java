package de.morau.blockframe.render.terrain.gpuscene;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded CPU mirror for the persistent opaque-solid GPU scene.
 *
 * <p>Only owner events mutate slots. The frame path looks up Mojang's final
 * visible section identities and appends them to fixed primitive arrays. It
 * never enumerates all scene slots.</p>
 */
public final class OpaqueSolidGpuSceneModel {
    public static final int DEFAULT_CAPACITY = 16_384;
    public static final int DEFAULT_BUCKET_CAPACITY = 16;
    private static final byte FREE = 0;
    private static final byte INVALIDATED = 1;
    private static final byte READY = 2;

    private final int capacity;
    private final int bucketCapacity;
    private final Map<Object, Integer> slotsBySection =
        new IdentityHashMap<>();
    private final Object[] sectionOwners;
    private final OpaqueSolidGpuGenerationToken[] tokens;
    private final byte[] states;
    private final int[] bucketBySlot;
    private final int[] freeSlots;
    private int freeCount;
    private final BucketKey[] buckets;
    private final int[] bucketRefCounts;
    private int bucketCount;
    private final int[][] dirtySlots;
    private final boolean[][] dirty;
    private final int[] dirtyCounts = new int[2];
    private final int[] visibleSlots;
    private final int[] visibilityBits;
    private int visibleCount;
    private long publishedCount;
    private long invalidatedCount;
    private long fallbackCount;

    public OpaqueSolidGpuSceneModel() {
        this(DEFAULT_CAPACITY, DEFAULT_BUCKET_CAPACITY);
    }

    OpaqueSolidGpuSceneModel(int capacity, int bucketCapacity) {
        if (capacity <= 0 || bucketCapacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.bucketCapacity = bucketCapacity;
        this.sectionOwners = new Object[capacity];
        this.tokens = new OpaqueSolidGpuGenerationToken[capacity];
        this.states = new byte[capacity];
        this.bucketBySlot = new int[capacity];
        this.freeSlots = new int[capacity];
        this.buckets = new BucketKey[bucketCapacity];
        this.bucketRefCounts = new int[bucketCapacity];
        this.dirtySlots = new int[][] {
            new int[capacity],
            new int[capacity]
        };
        this.dirty = new boolean[][] {
            new boolean[capacity],
            new boolean[capacity]
        };
        this.visibleSlots = new int[capacity];
        this.visibilityBits = new int[capacity];
        for (int index = capacity - 1; index >= 0; index--) {
            this.freeSlots[this.freeCount++] = index;
        }
    }

    public synchronized PublishResult publish(
        Object sectionOwner,
        OpaqueSolidGpuGenerationToken token
    ) {
        Objects.requireNonNull(sectionOwner, "sectionOwner");
        Objects.requireNonNull(token, "token");
        Integer existing = this.slotsBySection.get(sectionOwner);
        int slot;
        if (existing == null) {
            if (this.freeCount == 0) {
                this.fallbackCount++;
                return PublishResult.CAPACITY_OVERFLOW;
            }
            slot = this.freeSlots[--this.freeCount];
            this.slotsBySection.put(sectionOwner, slot);
            this.sectionOwners[slot] = sectionOwner;
        } else {
            slot = existing;
            if (this.states[slot] == READY) {
                if (token.equals(this.tokens[slot])) {
                    return PublishResult.UNCHANGED;
                }
                throw new IllegalStateException(
                    "token replacement was not invalidated first"
                );
            }
        }
        int bucket = this.findOrCreateBucket(BucketKey.from(token));
        if (bucket < 0) {
            if (existing == null) {
                this.slotsBySection.remove(sectionOwner);
                this.sectionOwners[slot] = null;
                this.freeSlots[this.freeCount++] = slot;
            }
            this.fallbackCount++;
            return PublishResult.BUCKET_OVERFLOW;
        }
        this.tokens[slot] = token;
        this.bucketBySlot[slot] = bucket;
        this.bucketRefCounts[bucket]++;
        this.states[slot] = READY;
        this.markDirty(slot);
        this.publishedCount++;
        return PublishResult.PUBLISHED;
    }

    public synchronized boolean invalidateBeforeReplace(
        Object sectionOwner
    ) {
        Integer slotValue = this.slotsBySection.get(sectionOwner);
        if (slotValue == null) {
            return false;
        }
        int slot = slotValue;
        if (this.states[slot] != READY) {
            return false;
        }
        int bucket = this.bucketBySlot[slot];
        if (
            bucket < 0
                || bucket >= this.bucketCount
                || this.bucketRefCounts[bucket] <= 0
        ) {
            throw new IllegalStateException(
                "bucket reference accounting underflow"
            );
        }
        this.bucketRefCounts[bucket]--;
        this.states[slot] = INVALIDATED;
        this.markDirty(slot);
        this.invalidatedCount++;
        return true;
    }

    public synchronized boolean removeAfterInvalidation(
        Object sectionOwner
    ) {
        Integer slotValue = this.slotsBySection.get(sectionOwner);
        if (slotValue == null) {
            return false;
        }
        int slot = slotValue;
        if (this.states[slot] == READY) {
            throw new IllegalStateException(
                "section removal was not invalidated first"
            );
        }
        this.slotsBySection.remove(sectionOwner);
        this.sectionOwners[slot] = null;
        this.tokens[slot] = null;
        this.states[slot] = FREE;
        this.markDirty(slot);
        this.freeSlots[this.freeCount++] = slot;
        return true;
    }

    /**
     * Lifecycle-only owner event. This may visit occupied entries, but is
     * never called from a frame path. Every occupied slot is dirtied for both
     * submit-ring copies before ownership maps and bucket generations reset.
     */
    public synchronized void clearAfterOwnerInvalidation() {
        for (int slot = 0; slot < this.capacity; slot++) {
            if (this.states[slot] == FREE) {
                continue;
            }
            this.states[slot] = FREE;
            this.sectionOwners[slot] = null;
            this.tokens[slot] = null;
            this.bucketBySlot[slot] = 0;
            this.markDirty(slot);
        }
        this.slotsBySection.clear();
        this.freeCount = 0;
        for (int slot = this.capacity - 1; slot >= 0; slot--) {
            this.freeSlots[this.freeCount++] = slot;
        }
        for (int index = 0; index < this.bucketCount; index++) {
            this.buckets[index] = null;
            this.bucketRefCounts[index] = 0;
        }
        this.bucketCount = 0;
        this.visibleCount = 0;
    }

    public synchronized void beginVisibilityFrame() {
        this.visibleCount = 0;
    }

    public synchronized boolean appendVisible(
        Object sectionOwner,
        float visibility
    ) {
        Integer slotValue = this.slotsBySection.get(sectionOwner);
        if (slotValue == null) {
            this.fallbackCount++;
            return false;
        }
        int slot = slotValue;
        if (
            this.states[slot] != READY
                || this.visibleCount >= this.capacity
                || !Float.isFinite(visibility)
        ) {
            this.fallbackCount++;
            return false;
        }
        this.visibleSlots[this.visibleCount] = slot;
        this.visibilityBits[this.visibleCount] =
            Float.floatToRawIntBits(visibility);
        this.visibleCount++;
        return true;
    }

    public synchronized boolean drainDirty(
        int frameIndex,
        DirtyWriter writer
    ) {
        requireFrameIndex(frameIndex);
        Objects.requireNonNull(writer, "writer");
        int count = this.dirtyCounts[frameIndex];
        for (int index = 0; index < count; index++) {
            int slot = this.dirtySlots[frameIndex][index];
            OpaqueSolidGpuGenerationToken token =
                this.states[slot] == READY
                    ? this.tokens[slot]
                    : null;
            if (!writer.write(slot, this.bucketBySlot[slot], token)) {
                return false;
            }
        }
        for (int index = 0; index < count; index++) {
            int slot = this.dirtySlots[frameIndex][index];
            this.dirty[frameIndex][slot] = false;
        }
        this.dirtyCounts[frameIndex] = 0;
        return true;
    }

    public synchronized boolean writeVisibility(VisibilityWriter writer) {
        Objects.requireNonNull(writer, "writer");
        for (int index = 0; index < this.visibleCount; index++) {
            if (
                !writer.write(
                    index,
                    this.visibleSlots[index],
                    this.visibilityBits[index]
                )
            ) {
                return false;
            }
        }
        return true;
    }

    /**
     * CPU reference for tests only. Production compaction is a compute pass.
     */
    SyntheticCompaction compactSyntheticForTest() {
        int[] counts = new int[this.bucketCapacity];
        DrawCommand[] commands = new DrawCommand[this.visibleCount];
        int written = 0;
        for (int index = 0; index < this.visibleCount; index++) {
            int slot = this.visibleSlots[index];
            if (this.states[slot] != READY) {
                continue;
            }
            OpaqueSolidGpuGenerationToken token = this.tokens[slot];
            int bucket = this.bucketBySlot[slot];
            counts[bucket]++;
            commands[written++] = new DrawCommand(
                token.indexCount(),
                1,
                token.indexBindingKey()
                    == OpaqueSolidGpuGenerationToken
                        .INDEX_BINDING_CUSTOM
                    ? (int)(token.indexOffset() / indexBytes(
                        token.indexTypeKey()
                    ))
                    : 0,
                token.baseVertex(),
                slot,
                bucket,
                Float.intBitsToFloat(this.visibilityBits[index])
            );
        }
        return new SyntheticCompaction(counts, commands, written);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.capacity,
            this.slotsBySection.size(),
            this.bucketCount,
            this.visibleCount,
            this.dirtyCounts[0],
            this.dirtyCounts[1],
            this.publishedCount,
            this.invalidatedCount,
            this.fallbackCount
        );
    }

    public synchronized BucketKey bucket(int index) {
        if (index < 0 || index >= this.bucketCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return this.buckets[index];
    }

    public synchronized boolean bucketActive(int index) {
        if (index < 0 || index >= this.bucketCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return this.bucketRefCounts[index] > 0;
    }

    private int findOrCreateBucket(BucketKey key) {
        for (int index = 0; index < this.bucketCount; index++) {
            if (key.equals(this.buckets[index])) {
                return index;
            }
        }
        for (int index = 0; index < this.bucketCount; index++) {
            if (this.bucketRefCounts[index] == 0) {
                this.buckets[index] = key;
                return index;
            }
        }
        if (this.bucketCount >= this.bucketCapacity) {
            return -1;
        }
        int index = this.bucketCount++;
        this.buckets[index] = key;
        return index;
    }

    private void markDirty(int slot) {
        for (int frame = 0; frame < 2; frame++) {
            if (this.dirty[frame][slot]) {
                continue;
            }
            int count = this.dirtyCounts[frame];
            if (count >= this.capacity) {
                throw new IllegalStateException("dirty queue overflow");
            }
            this.dirty[frame][slot] = true;
            this.dirtySlots[frame][count] = slot;
            this.dirtyCounts[frame] = count + 1;
        }
    }

    private static int indexBytes(int indexTypeKey) {
        return indexTypeKey == 1 ? 2 : 4;
    }

    private static void requireFrameIndex(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= 2) {
            throw new IllegalArgumentException("frameIndex");
        }
    }

    public enum PublishResult {
        PUBLISHED,
        UNCHANGED,
        CAPACITY_OVERFLOW,
        BUCKET_OVERFLOW
    }

    @FunctionalInterface
    public interface DirtyWriter {
        boolean write(
            int slot,
            int bucket,
            OpaqueSolidGpuGenerationToken token
        );
    }

    @FunctionalInterface
    public interface VisibilityWriter {
        boolean write(int ordinal, int slot, int visibilityBits);
    }

    public record BucketKey(
        long vertexBufferHandle,
        long vertexBufferGeneration,
        int indexBindingKey,
        long indexBufferHandle,
        long indexBufferGeneration,
        int indexTypeKey,
        int pipelineKey,
        int shaderAbiKey,
        int materialKey
    ) {
        private static BucketKey from(
            OpaqueSolidGpuGenerationToken token
        ) {
            return new BucketKey(
                token.vertexBufferHandle(),
                token.vertexBufferGeneration(),
                token.indexBindingKey(),
                token.indexBufferHandle(),
                token.indexBufferGeneration(),
                token.indexTypeKey(),
                token.pipelineKey(),
                token.shaderAbiKey(),
                token.materialKey()
            );
        }
    }

    record DrawCommand(
        int indexCount,
        int instanceCount,
        int firstIndex,
        int baseVertex,
        int firstInstance,
        int bucket,
        float visibility
    ) {
    }

    record SyntheticCompaction(
        int[] bucketCounts,
        DrawCommand[] commands,
        int commandCount
    ) {
    }

    public record Snapshot(
        int capacity,
        int entries,
        int buckets,
        int visible,
        int dirtyFrame0,
        int dirtyFrame1,
        long published,
        long invalidated,
        long fallbacks
    ) {
    }
}
