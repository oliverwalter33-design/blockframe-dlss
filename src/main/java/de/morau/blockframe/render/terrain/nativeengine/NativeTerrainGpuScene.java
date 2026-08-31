package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.BufferKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.GeometryHandle;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.Publication;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.ResourcePublication;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.ResourceRequest;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.ResourceWrite;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexMode;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Layer;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MeshDescriptor;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.SectionPos;

/**
 * Persistent logical and device-local Solid/Cutout scene owner.
 *
 * <p>Section publication and removal create bounded dirty mutations only.
 * Per-frame culling consumes the fixed device-local scene directly and never
 * scans this Java entry table. A mutation becomes visible to compute only
 * after the shared upload owner confirms its copy completion.</p>
 */
public final class NativeTerrainGpuScene implements AutoCloseable {
    public static final int ENTRY_WORDS = 20;
    public static final int ENTRY_BYTES =
        ENTRY_WORDS * Integer.BYTES;
    public static final int COMMAND_BYTES = 5 * Integer.BYTES;
    public static final int BUCKETS_PER_VERTEX_PAGE = 2;
    public static final int FLAG_ACTIVE = 1;
    public static final int FLAG_UNCERTAIN = 1 << 1;

    public enum MutationKind {
        PUBLISH,
        REMOVE
    }

    public record Configuration(
        int maximumEntries,
        int maximumVertexPages,
        long maximumCpuBytes
    ) {
        public Configuration {
            if (
                maximumEntries <= 0
                    || maximumVertexPages <= 0
                    || maximumCpuBytes <= 0L
            ) {
                throw new IllegalArgumentException(
                    "invalid persistent GPU-scene limits"
                );
            }
        }

        public int bucketCount() {
            return Math.multiplyExact(
                this.maximumVertexPages,
                BUCKETS_PER_VERTEX_PAGE
            );
        }
    }

    public record BindingTable(
        long generation,
        long[] vertexPageSerials,
        int bucketCount
    ) {
        public BindingTable {
            vertexPageSerials =
                Objects.requireNonNull(
                    vertexPageSerials,
                    "vertexPageSerials"
                ).clone();
        }

        @Override
        public long[] vertexPageSerials() {
            return this.vertexPageSerials.clone();
        }
    }

    public record Snapshot(
        int activeEntries,
        int highWaterEntries,
        int freeEntries,
        int vertexPages,
        int bucketCount,
        long sceneBytes,
        long indirectBytes,
        long countBytes,
        long cpuBytes,
        long dirtyBytesUploaded,
        long dirtyUploadCount,
        long publications,
        long removals,
        int pendingMutations,
        boolean resourcesConnected,
        boolean closed
    ) {
    }

    public static final class Mutation {
        private final NativeTerrainGpuScene owner;
        private final MutationKind kind;
        private final Publication geometry;
        private final int[] slots;
        private final Entry[] entries;
        private boolean terminal;

        private Mutation(
            NativeTerrainGpuScene owner,
            MutationKind kind,
            Publication geometry,
            int[] slots,
            Entry[] entries
        ) {
            this.owner = owner;
            this.kind = kind;
            this.geometry = geometry;
            this.slots = slots;
            this.entries = entries;
        }

        public MutationKind kind() {
            return this.kind;
        }

        public Publication geometry() {
            return this.geometry;
        }

        public int entryCount() {
            return this.slots.length;
        }
    }

    private static final class Entry {
        private final int slot;
        private final int bucket;
        private final int indexCount;
        private final int firstIndex;
        private final int vertexOffset;
        private final IndexType indexType;
        private final int sectionX;
        private final int sectionY;
        private final int sectionZ;
        private final Bounds bounds;
        private final int surfaceId;
        private final Layer layer;
        private final int shaderAbiId;
        private final long generationKey;
        private final Publication geometry;

        private Entry(
            int slot,
            int bucket,
            int indexCount,
            int firstIndex,
            int vertexOffset,
            IndexType indexType,
            int sectionX,
            int sectionY,
            int sectionZ,
            Bounds bounds,
            int surfaceId,
            Layer layer,
            int shaderAbiId,
            long generationKey,
            Publication geometry
        ) {
            this.slot = slot;
            this.bucket = bucket;
            this.indexCount = indexCount;
            this.firstIndex = firstIndex;
            this.vertexOffset = vertexOffset;
            this.indexType = indexType;
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
            this.bounds = bounds;
            this.surfaceId = surfaceId;
            this.layer = layer;
            this.shaderAbiId = shaderAbiId;
            this.generationKey = generationKey;
            this.geometry = geometry;
        }
    }

    private final long deviceGeneration;
    private final Configuration configuration;
    private final NativeTerrainSharedQuadIndexBuffer sharedIndices;
    private final MemoryBudgetManager budgets;
    private final NativeTerrainOwnershipEvidence.GenerationToken
        evidenceToken;
    private final long cpuLease;
    private final long cpuBytes;
    private final Entry[] entries;
    private final int[] freeSlots;
    private final long[] vertexPageSerials;
    private NativeTerrainSurfaceIdRegistry surfaceIds;
    private int freeCount;
    private int activeEntries;
    private int highWaterEntries;
    private int vertexPageCount;
    private int pendingMutations;
    private long dirtyBytesUploaded;
    private long dirtyUploadCount;
    private long publications;
    private long removals;
    private ResourcePublication resources;
    private GeometryHandle sceneHandle;
    private boolean closed;

    public NativeTerrainGpuScene(
        long deviceGeneration,
        Configuration configuration,
        NativeTerrainSharedQuadIndexBuffer sharedIndices,
        MemoryBudgetManager budgets
    ) {
        this(
            deviceGeneration,
            configuration,
            sharedIndices,
            budgets,
            null
        );
    }

    public NativeTerrainGpuScene(
        long deviceGeneration,
        Configuration configuration,
        NativeTerrainSharedQuadIndexBuffer sharedIndices,
        MemoryBudgetManager budgets,
        NativeTerrainOwnershipEvidence.GenerationToken evidenceToken
    ) {
        if (deviceGeneration <= 0L) {
            throw new IllegalArgumentException(
                "device generation must be positive"
            );
        }
        this.deviceGeneration = deviceGeneration;
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration"
        );
        this.sharedIndices = Objects.requireNonNull(
            sharedIndices,
            "sharedIndices"
        );
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.evidenceToken = evidenceToken;
        this.cpuBytes = estimateCpuBytes(configuration);
        if (this.cpuBytes > configuration.maximumCpuBytes()) {
            throw new IllegalArgumentException(
                "scene CPU storage exceeds configured maximum"
            );
        }
        this.cpuLease = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.TERRAIN,
            this.cpuBytes
        );
        if (this.cpuLease == 0L) {
            throw new IllegalStateException(
                "scene CPU RAM budget rejected"
            );
        }
        try {
            this.entries = new Entry[configuration.maximumEntries()];
            this.freeSlots =
                new int[configuration.maximumEntries()];
            this.vertexPageSerials =
                new long[configuration.maximumVertexPages()];
            for (
                int index = 0;
                index < configuration.maximumEntries();
                index++
            ) {
                this.freeSlots[index] =
                    configuration.maximumEntries() - index - 1;
            }
            this.freeCount = configuration.maximumEntries();
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            budgets.release(this.cpuLease);
            throw error;
        }
    }

    public List<ResourceRequest> resourceRequests() {
        requireOpen();
        long sceneBytes = sceneBytes();
        long commandBytes = indirectBytes();
        long countBytes = countBytes();
        return List.of(
            this.sharedIndices.resourceRequest(),
            new ResourceRequest(
                BufferKind.STORAGE_SCENE,
                sceneBytes,
                NativeTerrainGpuScene::writeZeros
            ),
            new ResourceRequest(
                BufferKind.INDIRECT_COMMAND,
                commandBytes,
                null
            ),
            new ResourceRequest(
                BufferKind.INDIRECT_COUNT,
                countBytes,
                null
            )
        );
    }

    public synchronized void connectResources(
        ResourcePublication publication
    ) {
        requireOpen();
        Objects.requireNonNull(publication, "publication");
        if (this.resources != null) {
            throw new IllegalStateException(
                "scene resources are already connected"
            );
        }
        if (publication.generation() != this.deviceGeneration) {
            throw new IllegalArgumentException(
                "scene resource generation mismatch"
            );
        }
        requireResourceSize(
            publication.require(BufferKind.SHARED_INDEX),
            this.sharedIndices.metrics().totalBytes()
        );
        this.sceneHandle =
            publication.require(BufferKind.STORAGE_SCENE);
        requireResourceSize(this.sceneHandle, sceneBytes());
        requireResourceSize(
            publication.require(BufferKind.INDIRECT_COMMAND),
            indirectBytes()
        );
        requireResourceSize(
            publication.require(BufferKind.INDIRECT_COUNT),
            countBytes()
        );
        this.resources = publication;
    }

    public synchronized Mutation preparePublication(
        Publication geometry
    ) {
        requireReady();
        requireNoPendingMutation();
        Objects.requireNonNull(geometry, "geometry");
        requireGeometryGeneration(geometry);
        List<EntrySeed> seeds = new ArrayList<>();
        appendSeeds(geometry, Category.SOLID, seeds);
        appendSeeds(geometry, Category.CUTOUT, seeds);
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                "geometry publication has no Solid/Cutout draws"
            );
        }
        if (seeds.size() > this.freeCount) {
            throw new IllegalStateException(
                "persistent GPU-scene capacity exceeded"
            );
        }
        int[] slots = new int[seeds.size()];
        Entry[] prepared = new Entry[seeds.size()];
        try {
            for (int index = 0; index < seeds.size(); index++) {
                int slot = this.freeSlots[--this.freeCount];
                slots[index] = slot;
                prepared[index] = seeds.get(index).entry(slot);
            }
        } catch (RuntimeException error) {
            returnSlots(slots, prepared);
            throw error;
        }
        this.pendingMutations++;
        return new Mutation(
            this,
            MutationKind.PUBLISH,
            geometry,
            slots,
            prepared
        );
    }

    public synchronized Mutation prepareRemoval(
        Publication geometry
    ) {
        requireReady();
        requireNoPendingMutation();
        Objects.requireNonNull(geometry, "geometry");
        int count = 0;
        for (Entry entry : this.entries) {
            if (entry != null && entry.geometry == geometry) {
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(
                "geometry publication is absent from the scene"
            );
        }
        int[] slots = new int[count];
        Entry[] removed = new Entry[count];
        int cursor = 0;
        for (int slot = 0; slot < this.entries.length; slot++) {
            Entry entry = this.entries[slot];
            if (entry != null && entry.geometry == geometry) {
                slots[cursor] = slot;
                removed[cursor] = entry;
                cursor++;
            }
        }
        this.pendingMutations++;
        return new Mutation(
            this,
            MutationKind.REMOVE,
            geometry,
            slots,
            removed
        );
    }

    public synchronized List<ResourceWrite> writes(
        Mutation mutation
    ) {
        requireMutation(mutation);
        List<ResourceWrite> writes =
            new ArrayList<>(mutation.slots.length);
        for (int index = 0; index < mutation.slots.length; index++) {
            int slot = mutation.slots[index];
            Entry entry = mutation.entries[index];
            writes.add(
                new ResourceWrite(
                    this.sceneHandle,
                    Math.multiplyExact((long)slot, ENTRY_BYTES),
                    ENTRY_BYTES,
                    mutation.kind == MutationKind.PUBLISH
                        ? destination ->
                            writeEntry(destination, entry)
                        : NativeTerrainGpuScene::writeZeros
                )
            );
        }
        return writes;
    }

    public synchronized void commit(Mutation mutation) {
        requireMutation(mutation);
        if (mutation.kind == MutationKind.PUBLISH) {
            for (int index = 0; index < mutation.slots.length; index++) {
                int slot = mutation.slots[index];
                if (this.entries[slot] != null) {
                    throw new IllegalStateException(
                        "scene slot changed during publication"
                    );
                }
                this.entries[slot] = mutation.entries[index];
                this.activeEntries++;
                this.highWaterEntries = Math.max(
                    this.highWaterEntries,
                    slot + 1
                );
            }
            this.publications++;
            NativeTerrainOwnershipEvidence
                .blockFrameSceneEntriesPublished(
                    this.evidenceToken,
                    mutation.entryCount()
                );
        } else {
            for (int slot : mutation.slots) {
                Entry entry = this.entries[slot];
                if (
                    entry == null
                        || entry.geometry != mutation.geometry
                ) {
                    throw new IllegalStateException(
                        "scene entry changed during removal"
                    );
                }
                this.entries[slot] = null;
                this.freeSlots[this.freeCount++] = slot;
                this.activeEntries--;
            }
            while (
                this.highWaterEntries > 0
                    && this.entries[this.highWaterEntries - 1] == null
            ) {
                this.highWaterEntries--;
            }
            this.removals++;
        }
        finishMutation(mutation);
    }

    public synchronized void rollback(Mutation mutation) {
        requireMutation(mutation);
        if (mutation.kind == MutationKind.PUBLISH) {
            returnSlots(mutation.slots, mutation.entries);
        }
        finishMutation(mutation);
    }

    public synchronized void recordDirtyUpload(Mutation mutation) {
        requireMutation(mutation);
        this.dirtyBytesUploaded = Math.addExact(
            this.dirtyBytesUploaded,
            Math.multiplyExact(
                (long)mutation.entryCount(),
                ENTRY_BYTES
            )
        );
        this.dirtyUploadCount++;
    }

    public synchronized BindingTable bindingTable() {
        requireReady();
        long[] pages = new long[this.vertexPageCount];
        System.arraycopy(
            this.vertexPageSerials,
            0,
            pages,
            0,
            this.vertexPageCount
        );
        return new BindingTable(
            this.deviceGeneration,
            pages,
            this.configuration.bucketCount()
        );
    }

    NativeTerrainOwnershipEvidence.GenerationToken evidenceToken() {
        return this.evidenceToken;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.activeEntries,
            this.highWaterEntries,
            this.freeCount,
            this.vertexPageCount,
            this.configuration.bucketCount(),
            sceneBytes(),
            indirectBytes(),
            countBytes(),
            this.cpuBytes,
            this.dirtyBytesUploaded,
            this.dirtyUploadCount,
            this.publications,
            this.removals,
            this.pendingMutations,
            this.resources != null,
            this.closed
        );
    }

    /**
     * Returns the exact collision-free surface table for diagnostics and
     * frame-output binding. The table is created lazily from the first
     * accepted material registry generation and never queried per frame.
     */
    public synchronized List<NativeTerrainSurfaceIdRegistry.Entry>
        surfaceRegistrySnapshot() {
        requireOpen();
        if (this.surfaceIds == null) {
            return List.of();
        }
        return this.surfaceIds.snapshot(
            this.surfaceIds.generation()
        );
    }

    public synchronized ResourcePublication resources() {
        requireReady();
        return this.resources;
    }

    public synchronized int sceneCountForDispatch() {
        requireReady();
        return this.highWaterEntries;
    }

    public int maximumEntries() {
        return this.configuration.maximumEntries();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        if (
            this.pendingMutations != 0
                || this.activeEntries != 0
        ) {
            throw new IllegalStateException(
                "scene still has active or pending entries"
            );
        }
        if (!this.budgets.release(this.cpuLease)) {
            throw new IllegalStateException(
                "scene CPU budget lease could not be released"
            );
        }
        if (this.surfaceIds != null) {
            this.surfaceIds.close();
        }
        this.closed = true;
    }

    private record EntrySeed(
        int bucket,
        int indexCount,
        int firstIndex,
        int vertexOffset,
        IndexType indexType,
        int sectionX,
        int sectionY,
        int sectionZ,
        Bounds bounds,
        int surfaceId,
        Layer layer,
        int shaderAbiId,
        long generationKey,
        Publication geometry
    ) {
        private Entry entry(int slot) {
            return new Entry(
                slot,
                this.bucket,
                this.indexCount,
                this.firstIndex,
                this.vertexOffset,
                this.indexType,
                this.sectionX,
                this.sectionY,
                this.sectionZ,
                this.bounds,
                this.surfaceId,
                this.layer,
                this.shaderAbiId,
                this.generationKey,
                this.geometry
            );
        }
    }

    private void appendSeeds(
        Publication geometry,
        Category category,
        List<EntrySeed> destination
    ) {
        GeometryHandle handle = geometry.handles().get(category);
        if (handle == null) {
            return;
        }
        if (handle.kind() != BufferKind.VERTEX) {
            throw new IllegalArgumentException(
                "scene geometry is not a vertex allocation"
            );
        }
        int pageOrdinal = pageOrdinal(handle.pageSerial());
        List<MeshDescriptor> descriptors =
            geometry.descriptors(category);
        if (descriptors.isEmpty()) {
            throw new IllegalArgumentException(
                "non-empty geometry handle has no descriptors"
            );
        }
        Layer expectedLayer = category == Category.SOLID
            ? Layer.SOLID
            : Layer.CUTOUT;
        int sectionX = SectionPos.sectionToBlockCoord(
            SectionPos.x(geometry.section().sectionNode())
        );
        int sectionY = SectionPos.sectionToBlockCoord(
            SectionPos.y(geometry.section().sectionNode())
        );
        int sectionZ = SectionPos.sectionToBlockCoord(
            SectionPos.z(geometry.section().sectionNode())
        );

        long segmentByteOffset = -1L;
        int segmentVertices = 0;
        Bounds segmentBounds = null;
        MeshDescriptor first = null;
        long expectedByteOffset = 0L;
        for (MeshDescriptor descriptor : descriptors) {
            validateDescriptor(
                geometry,
                descriptor,
                expectedLayer,
                expectedByteOffset
            );
            if (
                first != null
                    && (
                        descriptor.vertexCount()
                            > MAX_VERTICES_PER_DRAW - segmentVertices
                            || !compatible(first, descriptor)
                    )
            ) {
                destination.add(
                    seed(
                        geometry,
                        handle,
                        pageOrdinal,
                        category,
                        sectionX,
                        sectionY,
                        sectionZ,
                        segmentByteOffset,
                        segmentVertices,
                        segmentBounds,
                        first
                    )
                );
                segmentByteOffset = -1L;
                segmentVertices = 0;
                segmentBounds = null;
                first = null;
            }
            if (first == null) {
                first = descriptor;
                segmentByteOffset =
                    descriptor.vertexPayload().byteOffset();
            }
            segmentVertices = Math.addExact(
                segmentVertices,
                descriptor.vertexCount()
            );
            segmentBounds = union(
                segmentBounds,
                descriptor.bounds()
            );
            expectedByteOffset =
                descriptor.vertexPayload().endExclusive();
        }
        if (first != null) {
            destination.add(
                seed(
                    geometry,
                    handle,
                    pageOrdinal,
                    category,
                    sectionX,
                    sectionY,
                    sectionZ,
                    segmentByteOffset,
                    segmentVertices,
                    segmentBounds,
                    first
                )
            );
        }
        if (expectedByteOffset != handle.byteLength()) {
            throw new IllegalArgumentException(
                "descriptor bytes do not cover the geometry allocation"
            );
        }
    }

    private EntrySeed seed(
        Publication geometry,
        GeometryHandle handle,
        int pageOrdinal,
        Category category,
        int sectionX,
        int sectionY,
        int sectionZ,
        long segmentByteOffset,
        int segmentVertices,
        Bounds bounds,
        MeshDescriptor descriptor
    ) {
        long absoluteByteOffset = Math.addExact(
            handle.byteOffset(),
            segmentByteOffset
        );
        if (
            absoluteByteOffset
                % TerrainMeshProducerABI
                    .BLOCK_PAYLOAD_V2_STRIDE_BYTES
                != 0L
        ) {
            throw new IllegalArgumentException(
                "vertex allocation is not V2-stride aligned"
            );
        }
        int vertexOffset = Math.toIntExact(
            absoluteByteOffset
                / TerrainMeshProducerABI
                    .BLOCK_PAYLOAD_V2_STRIDE_BYTES
        );
        NativeTerrainSharedQuadIndexBuffer.DrawRange indices =
            this.sharedIndices.select(segmentVertices);
        if (indices.indexType() != IndexType.UINT16) {
            throw new IllegalArgumentException(
                "Renderer C V1 splits draws at the UINT16 boundary"
            );
        }
        int bucket = pageOrdinal * BUCKETS_PER_VERTEX_PAGE
            + (category == Category.SOLID ? 0 : 1);
        return new EntrySeed(
            bucket,
            indices.indexCount(),
            0,
            vertexOffset,
            indices.indexType(),
            sectionX,
            sectionY,
            sectionZ,
            Objects.requireNonNull(bounds, "bounds"),
            surfaceId(descriptor),
            descriptor.layer(),
            stableHash(
                descriptor.shader().abiDigest().part0(),
                descriptor.shader().abiDigest().part1()
            ),
            generationKey(descriptor),
            geometry
        );
    }

    private static final int MAX_VERTICES_PER_DRAW =
        NativeTerrainSharedQuadIndexBuffer
            .MAXIMUM_UINT16_VERTICES;

    private static void validateDescriptor(
        Publication geometry,
        MeshDescriptor descriptor,
        Layer expectedLayer,
        long expectedByteOffset
    ) {
        if (
            descriptor.abiVersion() != TerrainMeshProducerABI.VERSION
                || !descriptor.generations().equals(
                    geometry.generations()
                )
                || !descriptor.section().equals(geometry.section())
                || descriptor.layer() != expectedLayer
                || descriptor.vertexLayout().strideBytes()
                    != TerrainMeshProducerABI
                        .BLOCK_PAYLOAD_V2_STRIDE_BYTES
                || descriptor.vertexPayload().byteOffset()
                    != expectedByteOffset
                || descriptor.indexLayout().mode()
                    != IndexMode.SHARED_SEQUENTIAL_QUADS
                || descriptor.vertexCount() % 4 != 0
        ) {
            throw new IllegalArgumentException(
                "descriptor is not compatible with persistent scene V1"
            );
        }
    }

    private static boolean compatible(
        MeshDescriptor first,
        MeshDescriptor next
    ) {
        return first.layer() == next.layer()
            && first.vertexLayout().equals(next.vertexLayout())
            && first.material().equals(next.material())
            && first.shader().equals(next.shader());
    }

    private int surfaceId(MeshDescriptor descriptor) {
        long generation =
            descriptor.material().registryGeneration();
        if (this.surfaceIds == null) {
            this.surfaceIds =
                new NativeTerrainSurfaceIdRegistry(
                    generation,
                    this.configuration.maximumEntries()
                );
        }
        return this.surfaceIds.idFor(
            descriptor.material(),
            descriptor.shader()
        );
    }

    private int pageOrdinal(long pageSerial) {
        for (int index = 0; index < this.vertexPageCount; index++) {
            if (this.vertexPageSerials[index] == pageSerial) {
                return index;
            }
        }
        if (
            this.vertexPageCount
                >= this.vertexPageSerials.length
        ) {
            throw new IllegalStateException(
                "vertex-page bucket capacity exceeded"
            );
        }
        int ordinal = this.vertexPageCount++;
        this.vertexPageSerials[ordinal] = pageSerial;
        return ordinal;
    }

    private void requireGeometryGeneration(Publication geometry) {
        if (
            geometry.generations().device()
                != this.deviceGeneration
        ) {
            throw new IllegalArgumentException(
                "geometry belongs to another device generation"
            );
        }
    }

    private void requireMutation(Mutation mutation) {
        requireReady();
        Objects.requireNonNull(mutation, "mutation");
        if (mutation.owner != this || mutation.terminal) {
            throw new IllegalArgumentException(
                "mutation is foreign or terminal"
            );
        }
    }

    private void requireNoPendingMutation() {
        if (this.pendingMutations != 0) {
            throw new IllegalStateException(
                "persistent GPU-scene mutation is already in flight"
            );
        }
    }

    private void finishMutation(Mutation mutation) {
        mutation.terminal = true;
        this.pendingMutations--;
    }

    private void returnSlots(int[] slots, Entry[] prepared) {
        for (int index = 0; index < slots.length; index++) {
            if (prepared[index] != null) {
                this.freeSlots[this.freeCount++] = slots[index];
            }
        }
    }

    private static Bounds union(Bounds left, Bounds right) {
        if (left == null) {
            return right;
        }
        return new Bounds(
            Math.min(left.minimumX(), right.minimumX()),
            Math.min(left.minimumY(), right.minimumY()),
            Math.min(left.minimumZ(), right.minimumZ()),
            Math.max(left.maximumX(), right.maximumX()),
            Math.max(left.maximumY(), right.maximumY()),
            Math.max(left.maximumZ(), right.maximumZ())
        );
    }

    private static long generationKey(MeshDescriptor descriptor) {
        long value = descriptor.generations().device();
        value = 31L * value + descriptor.generations().renderer();
        value = 31L * value + descriptor.generations().world();
        value = 31L * value + descriptor.generations().resources();
        value = 31L * value + descriptor.generations().producer();
        value = 31L * value + descriptor.generations().sectionMesh();
        return value;
    }

    private static int stableHash(long high, long low) {
        long mixed = high ^ Long.rotateLeft(low, 23);
        return (int)(mixed ^ (mixed >>> 32));
    }

    private static void writeEntry(
        ByteBuffer destination,
        Entry entry
    ) {
        if (destination.remaining() != ENTRY_BYTES) {
            throw new IllegalArgumentException(
                "scene entry destination has the wrong size"
            );
        }
        destination.order(ByteOrder.LITTLE_ENDIAN);
        destination.putInt(FLAG_ACTIVE | FLAG_UNCERTAIN);
        destination.putInt(entry.bucket);
        destination.putInt(entry.indexCount);
        destination.putInt(entry.firstIndex);
        destination.putInt(entry.vertexOffset);
        destination.putInt(
            entry.indexType == IndexType.UINT16 ? 0 : 1
        );
        destination.putInt(entry.sectionX);
        destination.putInt(entry.sectionY);
        destination.putInt(entry.sectionZ);
        destination.putInt(
            Float.floatToRawIntBits(entry.bounds.minimumX())
        );
        destination.putInt(
            Float.floatToRawIntBits(entry.bounds.minimumY())
        );
        destination.putInt(
            Float.floatToRawIntBits(entry.bounds.minimumZ())
        );
        destination.putInt(
            Float.floatToRawIntBits(entry.bounds.maximumX())
        );
        destination.putInt(
            Float.floatToRawIntBits(entry.bounds.maximumY())
        );
        destination.putInt(
            Float.floatToRawIntBits(entry.bounds.maximumZ())
        );
        destination.putInt(entry.surfaceId);
        destination.putInt(entry.layer.ordinal());
        destination.putInt(entry.shaderAbiId);
        destination.putInt((int)entry.generationKey);
        destination.putInt((int)(entry.generationKey >>> 32));
    }

    private static void writeZeros(ByteBuffer destination) {
        while (destination.hasRemaining()) {
            destination.put((byte)0);
        }
    }

    private long sceneBytes() {
        return Math.multiplyExact(
            (long)this.configuration.maximumEntries(),
            ENTRY_BYTES
        );
    }

    private long indirectBytes() {
        return Math.multiplyExact(
            Math.multiplyExact(
                (long)this.configuration.bucketCount(),
                this.configuration.maximumEntries()
            ),
            COMMAND_BYTES
        );
    }

    private long countBytes() {
        return Math.multiplyExact(
            (long)this.configuration.bucketCount(),
            Integer.BYTES
        );
    }

    private static long estimateCpuBytes(
        Configuration configuration
    ) {
        return Math.addExact(
            1024L,
            Math.addExact(
                Math.multiplyExact(
                    (long)configuration.maximumEntries(),
                    8L + Integer.BYTES
                ),
                Math.multiplyExact(
                    (long)configuration.maximumVertexPages(),
                    Long.BYTES
                )
            )
        );
    }

    private static void requireResourceSize(
        GeometryHandle handle,
        long expected
    ) {
        if (handle.byteLength() != expected) {
            throw new IllegalArgumentException(
                "native scene resource size mismatch"
            );
        }
    }

    private void requireReady() {
        requireOpen();
        if (this.resources == null || this.sceneHandle == null) {
            throw new IllegalStateException(
                "native scene resources are not connected"
            );
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                "native GPU scene is closed"
            );
        }
    }
}
