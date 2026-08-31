package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.ChannelPayload;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.PublishedPayload;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MeshDescriptor;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.CleanupDecision;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.UploadPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Budgeted device-local terrain pages and one bounded mapped upload pool.
 *
 * <p>The production adapter borrows Mojang's VulkanDevice, VMA allocator,
 * command encoder and submit cadence. It creates no VkDevice, allocator,
 * queue, thread or submission. Publication is possible only after a
 * zero-timeout fence poll confirms the existing submit completed.</p>
 */
public final class NativeTerrainGeometryOwner
    implements AutoCloseable {
    public enum BufferKind {
        VERTEX,
        INDEX,
        SHARED_INDEX,
        STORAGE_SCENE,
        INDIRECT_COMMAND,
        INDIRECT_COUNT
    }

    public enum UploadFailure {
        BACKPRESSURE,
        GENERATION_MISMATCH,
        DEFERRED_CHANNEL,
        PAYLOAD_SIZE_OVERFLOW,
        STAGING_CAPACITY_EXCEEDED,
        VRAM_BUDGET_REJECTED,
        DEVICE_ALLOCATION_FAILED,
        STAGING_WRITE_FAILED,
        COPY_RECORD_FAILED,
        PUBLICATION_FAILED,
        OWNER_CLOSED
    }

    public interface OwnedBuffer extends AutoCloseable {
        long size();

        @Override
        void close();
    }

    public interface MappedStagingBuffer extends OwnedBuffer {
        void copyFrom(
            long destinationOffset,
            ChannelPayload payload
        );

        void write(
            long destinationOffset,
            long length,
            BufferWriter writer
        );
    }

    @FunctionalInterface
    public interface BufferWriter {
        void write(ByteBuffer destination);
    }

    public interface Completion extends AutoCloseable {
        boolean completed();

        @Override
        void close();
    }

    public record CopyRegion(
        MappedStagingBuffer source,
        long sourceOffset,
        OwnedBuffer destination,
        long destinationOffset,
        long length
    ) {
        public CopyRegion {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            requireRange(sourceOffset, length, source.size(), "source");
            requireRange(
                destinationOffset,
                length,
                destination.size(),
                "destination"
            );
        }
    }

    public record RecordResult(
        Completion completion,
        Throwable failure
    ) {
        public RecordResult {
            Objects.requireNonNull(completion, "completion");
        }

        public boolean successful() {
            return this.failure == null;
        }
    }

    public interface DeviceAccess {
        OwnedBuffer createDeviceBuffer(
            BufferKind kind,
            long bytes
        );

        MappedStagingBuffer createMappedStaging(long bytes);

        RecordResult recordCopies(List<CopyRegion> copies);
    }

    /**
     * Fixed native-renderer resource created through the same page allocator,
     * staging map and encoder cadence as section geometry.
     */
    public record ResourceRequest(
        BufferKind kind,
        long bytes,
        BufferWriter initialContents
    ) {
        public ResourceRequest {
            Objects.requireNonNull(kind, "kind");
            if (kind == BufferKind.VERTEX || bytes <= 0L) {
                throw new IllegalArgumentException(
                    "invalid infrastructure resource request"
                );
            }
        }

        public boolean hasInitialContents() {
            return this.initialContents != null;
        }
    }

    /**
     * Dirty update into an already published fixed resource.
     */
    public record ResourceWrite(
        GeometryHandle target,
        long targetOffset,
        long length,
        BufferWriter contents
    ) {
        public ResourceWrite {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(contents, "contents");
            requireRange(
                targetOffset,
                length,
                target.byteLength(),
                "resource update"
            );
        }
    }

    /**
     * Page size is derived from the observed p95 section payload and the
     * device/configuration allocation ceilings. No fixed arbitrary page size
     * is embedded in the owner.
     */
    public record PagePolicy(
        long observedP95PayloadBytes,
        long pageBytes,
        long maximumPageBytes,
        long alignmentBytes
    ) {
        public PagePolicy {
            if (
                observedP95PayloadBytes <= 0L
                    || pageBytes < observedP95PayloadBytes
                    || maximumPageBytes < pageBytes
                    || alignmentBytes <= 0L
                    || !isPowerOfTwo(alignmentBytes)
                    || pageBytes % alignmentBytes != 0L
            ) {
                throw new IllegalArgumentException(
                    "invalid derived terrain page policy"
                );
            }
        }

        public static PagePolicy derive(
            Collection<Long> observedPayloadBytes,
            long deviceMaximumAllocationBytes,
            long configuredMaximumPageBytes,
            long alignmentBytes
        ) {
            Objects.requireNonNull(
                observedPayloadBytes,
                "observedPayloadBytes"
            );
            if (
                observedPayloadBytes.isEmpty()
                    || deviceMaximumAllocationBytes <= 0L
                    || configuredMaximumPageBytes <= 0L
                    || alignmentBytes <= 0L
                    || !isPowerOfTwo(alignmentBytes)
            ) {
                throw new IllegalArgumentException(
                    "page derivation requires observations and limits"
                );
            }
            List<Long> sorted = observedPayloadBytes.stream()
                .peek(value -> {
                    if (value == null || value <= 0L) {
                        throw new IllegalArgumentException(
                            "observed payload bytes must be positive"
                        );
                    }
                })
                .sorted()
                .toList();
            int p95Index = Math.min(
                sorted.size() - 1,
                (int)Math.ceil(sorted.size() * 0.95D) - 1
            );
            long p95 = sorted.get(p95Index);
            long ceiling = Math.min(
                deviceMaximumAllocationBytes,
                configuredMaximumPageBytes
            );
            long aligned = alignUp(p95, alignmentBytes);
            if (aligned > ceiling) {
                throw new IllegalArgumentException(
                    "observed p95 payload exceeds allocation ceiling"
                );
            }
            return new PagePolicy(
                p95,
                aligned,
                ceiling,
                alignmentBytes
            );
        }

        long bytesFor(long requestedBytes) {
            long aligned = alignUp(requestedBytes, this.alignmentBytes);
            long selected = Math.max(this.pageBytes, aligned);
            if (selected > this.maximumPageBytes) {
                throw new IllegalArgumentException(
                    "geometry allocation exceeds maximum page"
                );
            }
            return selected;
        }
    }

    public record GeometryHandle(
        BufferKind kind,
        long deviceGeneration,
        long pageSerial,
        long byteOffset,
        long byteLength
    ) {
        public GeometryHandle {
            Objects.requireNonNull(kind, "kind");
            if (
                deviceGeneration <= 0L
                    || pageSerial <= 0L
                    || byteOffset < 0L
                    || byteLength <= 0L
            ) {
                throw new IllegalArgumentException(
                    "invalid geometry handle"
                );
            }
        }
    }

    public static final class UploadTicket {
        private final NativeTerrainGeometryOwner owner;
        private final CompiledPayloadBatch batch;
        private final NativeTerrainSectionLifecycle lifecycle;
        private final UploadPermit uploadPermit;
        private final GenerationStamp generations;
        private final EnumMap<Category, Allocation> allocations;
        private final Completion completion;
        private final long stagedBytes;
        private final UploadFailure recordedFailure;
        private boolean terminal;

        private UploadTicket(
            NativeTerrainGeometryOwner owner,
            CompiledPayloadBatch batch,
            NativeTerrainSectionLifecycle lifecycle,
            UploadPermit uploadPermit,
            GenerationStamp generations,
            EnumMap<Category, Allocation> allocations,
            Completion completion,
            long stagedBytes,
            UploadFailure recordedFailure
        ) {
            this.owner = owner;
            this.batch = batch;
            this.lifecycle = lifecycle;
            this.uploadPermit = uploadPermit;
            this.generations = generations;
            this.allocations = allocations;
            this.completion = completion;
            this.stagedBytes = stagedBytes;
            this.recordedFailure = recordedFailure;
        }

        public long stagedBytes() {
            return this.stagedBytes;
        }
    }

    public static final class Publication {
        private final NativeTerrainGeometryOwner owner;
        private final PublishedPayload payload;
        private final NativeTerrainSectionLifecycle lifecycle;
        private final GenerationStamp generations;
        private final SectionIdentity section;
        private final EnumMap<Category, GeometryHandle> handles;
        private long lastKnownUse;
        private boolean retiring;
        private boolean retired;

        private Publication(
            NativeTerrainGeometryOwner owner,
            PublishedPayload payload,
            NativeTerrainSectionLifecycle lifecycle,
            GenerationStamp generations,
            SectionIdentity section,
            EnumMap<Category, GeometryHandle> handles
        ) {
            this.owner = owner;
            this.payload = payload;
            this.lifecycle = lifecycle;
            this.generations = generations;
            this.section = section;
            this.handles = handles;
        }

        public GenerationStamp generations() {
            return this.generations;
        }

        public SectionIdentity section() {
            return this.section;
        }

        public Map<Category, GeometryHandle> handles() {
            return Map.copyOf(this.handles);
        }

        public boolean cpuUploadBytesResident(Category category) {
            return this.payload.channel(
                Objects.requireNonNull(category, "category")
            ).bytesResident();
        }

        public List<MeshDescriptor> descriptors(Category category) {
            return this.payload.channel(
                Objects.requireNonNull(category, "category")
            ).descriptors();
        }

        public synchronized void recordUse(long submissionSerial) {
            if (this.retiring || this.retired) {
                throw new IllegalStateException(
                    "publication is retiring"
                );
            }
            if (submissionSerial <= this.lastKnownUse) {
                throw new IllegalArgumentException(
                    "usage serial must increase"
                );
            }
            this.lastKnownUse = submissionSerial;
        }

        public synchronized long lastKnownUse() {
            return this.lastKnownUse;
        }
    }

    public record UploadStart(
        UploadTicket ticket,
        UploadFailure failure,
        String detail
    ) {
        public UploadStart {
            detail = Objects.requireNonNull(detail, "detail");
            if ((ticket == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "upload start must contain exactly one outcome"
                );
            }
        }

        public boolean started() {
            return this.ticket != null;
        }

        public Optional<UploadTicket> ticketOptional() {
            return Optional.ofNullable(this.ticket);
        }
    }

    public record UploadPoll(
        boolean pending,
        Publication publication,
        UploadFailure failure,
        String detail
    ) {
        public UploadPoll {
            detail = Objects.requireNonNull(detail, "detail");
            int outcomes = (pending ? 1 : 0)
                + (publication == null ? 0 : 1)
                + (failure == null ? 0 : 1);
            if (outcomes != 1) {
                throw new IllegalArgumentException(
                    "upload poll must contain one outcome"
                );
            }
        }

        public static UploadPoll pendingResult() {
            return new UploadPoll(true, null, null, "");
        }
    }

    public static final class ResourceTicket {
        private final NativeTerrainGeometryOwner owner;
        private final long generation;
        private final EnumMap<BufferKind, Allocation> allocations;
        private final Completion completion;
        private final UploadFailure recordedFailure;
        private boolean terminal;

        private ResourceTicket(
            NativeTerrainGeometryOwner owner,
            long generation,
            EnumMap<BufferKind, Allocation> allocations,
            Completion completion,
            UploadFailure recordedFailure
        ) {
            this.owner = owner;
            this.generation = generation;
            this.allocations = allocations;
            this.completion = completion;
            this.recordedFailure = recordedFailure;
        }
    }

    public static final class ResourcePublication {
        private final NativeTerrainGeometryOwner owner;
        private final long generation;
        private final EnumMap<BufferKind, GeometryHandle> handles;
        private boolean retiring;
        private boolean retired;

        private ResourcePublication(
            NativeTerrainGeometryOwner owner,
            long generation,
            EnumMap<BufferKind, GeometryHandle> handles
        ) {
            this.owner = owner;
            this.generation = generation;
            this.handles = handles;
        }

        public long generation() {
            return this.generation;
        }

        public synchronized Map<BufferKind, GeometryHandle> handles() {
            requireActive();
            return Map.copyOf(this.handles);
        }

        public synchronized GeometryHandle require(
            BufferKind kind
        ) {
            requireActive();
            GeometryHandle handle = this.handles.get(
                Objects.requireNonNull(kind, "kind")
            );
            if (handle == null) {
                throw new IllegalArgumentException(
                    "resource kind is not published: " + kind
                );
            }
            return handle;
        }

        private void requireActive() {
            if (this.retiring || this.retired) {
                throw new IllegalStateException(
                    "resource publication is retiring"
                );
            }
        }
    }

    public record ResourceStart(
        ResourceTicket ticket,
        UploadFailure failure,
        String detail
    ) {
        public ResourceStart {
            detail = Objects.requireNonNull(detail, "detail");
            if ((ticket == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "resource start must contain exactly one outcome"
                );
            }
        }

        public boolean started() {
            return this.ticket != null;
        }
    }

    public record ResourcePoll(
        boolean pending,
        ResourcePublication publication,
        UploadFailure failure,
        String detail
    ) {
        public ResourcePoll {
            detail = Objects.requireNonNull(detail, "detail");
            int outcomes = (pending ? 1 : 0)
                + (publication == null ? 0 : 1)
                + (failure == null ? 0 : 1);
            if (outcomes != 1) {
                throw new IllegalArgumentException(
                    "resource poll must contain one outcome"
                );
            }
        }

        public static ResourcePoll pendingResult() {
            return new ResourcePoll(true, null, null, "");
        }
    }

    public static final class ResourceUpdateTicket {
        private final NativeTerrainGeometryOwner owner;
        private final long generation;
        private final Completion completion;
        private final UploadFailure recordedFailure;
        private boolean terminal;

        private ResourceUpdateTicket(
            NativeTerrainGeometryOwner owner,
            long generation,
            Completion completion,
            UploadFailure recordedFailure
        ) {
            this.owner = owner;
            this.generation = generation;
            this.completion = completion;
            this.recordedFailure = recordedFailure;
        }
    }

    public record ResourceUpdateStart(
        ResourceUpdateTicket ticket,
        UploadFailure failure,
        String detail
    ) {
        public ResourceUpdateStart {
            detail = Objects.requireNonNull(detail, "detail");
            if ((ticket == null) == (failure == null)) {
                throw new IllegalArgumentException(
                    "resource update start must contain one outcome"
                );
            }
        }

        public boolean started() {
            return this.ticket != null;
        }
    }

    public record ResourceUpdatePoll(
        boolean pending,
        UploadFailure failure,
        String detail
    ) {
        public ResourceUpdatePoll {
            detail = Objects.requireNonNull(detail, "detail");
            if (pending && failure != null) {
                throw new IllegalArgumentException(
                    "pending resource update cannot be failed"
                );
            }
        }

        public boolean successful() {
            return !this.pending && this.failure == null;
        }
    }

    public static final class ResourceRetirement {
        private final ResourcePublication publication;
        private final Completion completion;
        private boolean terminal;

        private ResourceRetirement(
            ResourcePublication publication,
            Completion completion
        ) {
            this.publication = publication;
            this.completion = completion;
        }
    }

    public record BufferBinding(
        BufferKind kind,
        long deviceGeneration,
        long pageSerial,
        OwnedBuffer buffer,
        long offset,
        long length
    ) {
        public BufferBinding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(buffer, "buffer");
            requireRange(offset, length, buffer.size(), "binding");
        }
    }

    public static final class Retirement {
        private final Publication publication;
        private final CleanupDecision cleanup;
        private final Completion completion;
        private final long completionSerial;
        private boolean terminal;

        private Retirement(
            Publication publication,
            CleanupDecision cleanup,
            Completion completion,
            long completionSerial
        ) {
            this.publication = publication;
            this.cleanup = cleanup;
            this.completion = completion;
            this.completionSerial = completionSerial;
        }
    }

    public record Snapshot(
        long requestedBytes,
        long committedBytes,
        long usedBytes,
        long peakUsedBytes,
        long freeBytes,
        long largestFreeRangeBytes,
        long externalFragmentationBytes,
        long stagingBytes,
        int pageCount,
        int allocationCount,
        long successfulUploads,
        long failedUploads,
        long backpressureCount,
        long retirements,
        boolean uploadInFlight,
        boolean closed
    ) {
    }

    private static final class Allocation {
        private final Page page;
        private final long offset;
        private final long requested;
        private final long committed;

        private Allocation(
            Page page,
            long offset,
            long requested,
            long committed
        ) {
            this.page = page;
            this.offset = offset;
            this.requested = requested;
            this.committed = committed;
        }

        private GeometryHandle handle(long deviceGeneration) {
            return new GeometryHandle(
                this.page.kind,
                deviceGeneration,
                this.page.serial,
                this.offset,
                this.requested
            );
        }
    }

    private static final class Page {
        private final BufferKind kind;
        private final long serial;
        private final OwnedBuffer buffer;
        private final long lease;
        private final TreeMap<Long, Long> free = new TreeMap<>();
        private long used;
        private int allocations;
        private boolean closeQueued;

        private Page(
            BufferKind kind,
            long serial,
            OwnedBuffer buffer,
            long lease
        ) {
            this.kind = kind;
            this.serial = serial;
            this.buffer = buffer;
            this.lease = lease;
            this.free.put(0L, buffer.size());
        }
    }

    private final long deviceGeneration;
    private final MemoryBudgetManager budgets;
    private final DeviceAccess device;
    private final PagePolicy pagePolicy;
    private final long maximumGeometryBytes;
    private final long stagingCapacity;
    private final NativeTerrainOwnershipEvidence.GenerationToken
        evidenceToken;
    private final MappedStagingBuffer staging;
    private final long stagingLease;
    private final EnumMap<BufferKind, List<Page>> pages =
        new EnumMap<>(BufferKind.class);
    private long pageSerial;
    private long requestedBytes;
    private long committedBytes;
    private long usedBytes;
    private long peakUsedBytes;
    private int allocationCount;
    private long successfulUploads;
    private long failedUploads;
    private long backpressureCount;
    private long retirements;
    private UploadTicket inFlight;
    private ResourceTicket resourceInFlight;
    private ResourceUpdateTicket resourceUpdateInFlight;
    private boolean stagingCloseQueued;
    private boolean stagingLeaseRetiring;
    private boolean closed;

    public NativeTerrainGeometryOwner(
        long deviceGeneration,
        MemoryBudgetManager budgets,
        DeviceAccess device,
        PagePolicy pagePolicy,
        long maximumGeometryBytes,
        long stagingCapacity
    ) {
        this(
            deviceGeneration,
            budgets,
            device,
            pagePolicy,
            maximumGeometryBytes,
            stagingCapacity,
            null
        );
    }

    public NativeTerrainGeometryOwner(
        long deviceGeneration,
        MemoryBudgetManager budgets,
        DeviceAccess device,
        PagePolicy pagePolicy,
        long maximumGeometryBytes,
        long stagingCapacity,
        NativeTerrainOwnershipEvidence.GenerationToken evidenceToken
    ) {
        if (
            deviceGeneration <= 0L
                || maximumGeometryBytes <= 0L
                || stagingCapacity <= 0L
                || stagingCapacity > Integer.MAX_VALUE
        ) {
            throw new IllegalArgumentException(
                "invalid geometry owner limits"
            );
        }
        this.deviceGeneration = deviceGeneration;
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.device = Objects.requireNonNull(device, "device");
        this.pagePolicy = Objects.requireNonNull(
            pagePolicy,
            "pagePolicy"
        );
        this.maximumGeometryBytes = maximumGeometryBytes;
        this.evidenceToken = evidenceToken;
        this.stagingCapacity = alignUp(
            stagingCapacity,
            pagePolicy.alignmentBytes()
        );
        for (BufferKind kind : BufferKind.values()) {
            this.pages.put(kind, new ArrayList<>());
        }

        long lease = budgets.tryReserve(
            MemoryKind.RAM,
            MemoryCategory.STAGING,
            this.stagingCapacity
        );
        if (lease == 0L) {
            throw new IllegalStateException(
                "staging RAM budget rejected geometry owner"
            );
        }
        MappedStagingBuffer mapped = null;
        try {
            mapped = device.createMappedStaging(this.stagingCapacity);
            this.staging = Objects.requireNonNull(mapped, "staging");
            this.stagingLease = lease;
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            if (mapped != null) {
                try {
                    mapped.close();
                } catch (RuntimeException ignored) {
                    // Construction still fails closed.
                }
            }
            budgets.release(lease);
            throw error;
        }
    }

    public synchronized UploadStart tryUpload(
        CompiledPayloadBatch batch,
        NativeTerrainSectionLifecycle lifecycle,
        GenerationStamp currentGenerations
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(
            currentGenerations,
            "currentGenerations"
        );
        synchronized (this) {
            if (this.closed) {
                return failedStart(
                    UploadFailure.OWNER_CLOSED,
                    "geometry owner is closed"
                );
            }
            if (stagingBusy()) {
                this.backpressureCount++;
                return failedStart(
                    UploadFailure.BACKPRESSURE,
                    "bounded staging pool already has in-flight work"
                );
            }
        }
        if (
            !batch.generations().equals(currentGenerations)
                || !batch.generations().equals(
                    lifecycle.generations()
                )
                || !batch.section().equals(lifecycle.section())
        ) {
            /*
             * Cancel only when the lifecycle still owns this exact stale
             * batch. A lifecycle already advanced to another generation must
             * never be cancelled by an older batch presented to this owner.
             */
            if (
                batch.generations().equals(lifecycle.generations())
                    && batch.section().equals(lifecycle.section())
            ) {
                lifecycle.cancelBeforePublish(
                    staleCause(
                        batch.generations(),
                        currentGenerations,
                        batch.section(),
                        lifecycle.section()
                    ),
                    0L
                );
            }
            return failedStart(
                UploadFailure.GENERATION_MISMATCH,
                "batch/lifecycle/device generation mismatch"
            );
        }
        if (currentGenerations.device() != this.deviceGeneration) {
            return failedStart(
                UploadFailure.GENERATION_MISMATCH,
                "batch targets another Vulkan device generation"
            );
        }
        if (!batch.fullySubmittable()) {
            lifecycle.rejectBudgetBeforeUpload();
            return failedStart(
                UploadFailure.DEFERRED_CHANNEL,
                "translucent/fluid/mod-extra channel requires Mojang"
            );
        }

        long stagedBytes = 0L;
        try {
            for (ChannelPayload channel : batch.channels().values()) {
                if (channel.byteLength() == 0) {
                    continue;
                }
                stagedBytes = alignUp(
                    stagedBytes,
                    this.pagePolicy.alignmentBytes()
                );
                stagedBytes = Math.addExact(
                    stagedBytes,
                    channel.byteLength()
                );
            }
        } catch (ArithmeticException error) {
            lifecycle.rejectBudgetBeforeUpload();
            return failedStart(
                UploadFailure.PAYLOAD_SIZE_OVERFLOW,
                "batched upload size overflow"
            );
        }
        if (stagedBytes > this.stagingCapacity) {
            this.backpressureCount++;
            lifecycle.rejectBudgetBeforeUpload();
            return failedStart(
                UploadFailure.STAGING_CAPACITY_EXCEEDED,
                "complete section batch exceeds bounded staging capacity"
            );
        }

        EnumMap<Category, Allocation> allocations =
            new EnumMap<>(Category.class);
        try {
            for (
                Map.Entry<Category, ChannelPayload> entry
                    : batch.channels().entrySet()
            ) {
                int bytes = entry.getValue().byteLength();
                if (bytes != 0) {
                    allocations.put(
                        entry.getKey(),
                        allocate(BufferKind.VERTEX, bytes)
                    );
                }
            }
        } catch (BudgetFailure failure) {
            rollback(allocations.values());
            lifecycle.rejectBudgetBeforeUpload();
            return failedStart(failure.reason, failure.getMessage());
        }

        UploadPermit permit = lifecycle.beginUpload();
        List<CopyRegion> copies = new ArrayList<>(allocations.size());
        long stagingOffset = 0L;
        try {
            for (
                Map.Entry<Category, Allocation> entry
                    : allocations.entrySet()
            ) {
                ChannelPayload channel =
                    batch.channel(entry.getKey());
                stagingOffset = alignUp(
                    stagingOffset,
                    this.pagePolicy.alignmentBytes()
                );
                this.staging.copyFrom(stagingOffset, channel);
                Allocation allocation = entry.getValue();
                copies.add(
                    new CopyRegion(
                        this.staging,
                        stagingOffset,
                        allocation.page.buffer,
                        allocation.offset,
                        allocation.requested
                    )
                );
                stagingOffset += allocation.requested;
            }
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            rollback(allocations.values());
            CleanupDecision cleanup =
                lifecycle.failUpload(permit, 0L);
            completeFailedCleanup(lifecycle, cleanup);
            this.failedUploads++;
            return failedStart(
                UploadFailure.STAGING_WRITE_FAILED,
                error.getClass().getSimpleName()
            );
        }

        RecordResult recorded = this.device.recordCopies(copies);
        UploadFailure recordedFailure = recorded.successful()
            ? null
            : UploadFailure.COPY_RECORD_FAILED;
        UploadTicket ticket;
        synchronized (this) {
            UploadFailure terminalFailure =
                this.closed || stagingBusy()
                    ? UploadFailure.OWNER_CLOSED
                    : recordedFailure;
            /*
             * Commands may already reference all ranges. Even a concurrent
             * close/conflict therefore becomes a completion-driven rollback
             * ticket rather than an immediate free.
             */
            ticket = new UploadTicket(
                this,
                batch,
                lifecycle,
                permit,
                currentGenerations,
                allocations,
                recorded.completion(),
                stagedBytes,
                terminalFailure
            );
            this.inFlight = ticket;
        }
        return new UploadStart(ticket, null, "");
    }

    public UploadPoll pollUpload(
        UploadTicket ticket,
        GenerationStamp currentGenerations
    ) {
        requireTicket(ticket);
        Objects.requireNonNull(
            currentGenerations,
            "currentGenerations"
        );
        synchronized (ticket) {
            if (ticket.terminal) {
                throw new IllegalStateException(
                    "upload ticket is terminal"
                );
            }
            if (!ticket.completion.completed()) {
                return UploadPoll.pendingResult();
            }
            UploadFailure failure = ticket.recordedFailure;
            if (!ticket.generations.equals(currentGenerations)) {
                failure = UploadFailure.GENERATION_MISMATCH;
            }
            if (failure != null) {
                finishFailedTicket(ticket);
                return new UploadPoll(
                    false,
                    null,
                    failure,
                    failure.name()
                );
            }

            PublishedPayload payload = null;
            try {
                SectionIdentity section = ticket.lifecycle.section();
                payload = ticket.batch.publish();
                EnumMap<Category, GeometryHandle> handles =
                    new EnumMap<>(Category.class);
                for (
                    Map.Entry<Category, Allocation> entry
                        : ticket.allocations.entrySet()
                ) {
                    handles.put(
                        entry.getKey(),
                        entry.getValue().handle(this.deviceGeneration)
                    );
                    payload.channel(entry.getKey())
                        .releaseUploadedBytes();
                }
                ticket.lifecycle.publish(ticket.uploadPermit);
                Publication publication = new Publication(
                    this,
                    payload,
                    ticket.lifecycle,
                    ticket.generations,
                    section,
                    handles
                );
                finishTicket(ticket);
                this.successfulUploads++;
                NativeTerrainOwnershipEvidence
                    .blockFramePayloadPublished(this.evidenceToken);
                NativeTerrainOwnershipEvidence
                    .blockFrameGpuUploaded(this.evidenceToken);
                return new UploadPoll(
                    false,
                    publication,
                    null,
                    ""
                );
            } catch (
                RuntimeException | LinkageError | OutOfMemoryError error
            ) {
                if (payload != null) {
                    try {
                        payload.retire();
                    } catch (RuntimeException ignored) {
                        // Geometry rollback remains authoritative below.
                    }
                }
                finishFailedTicket(ticket);
                return new UploadPoll(
                    false,
                    null,
                    UploadFailure.PUBLICATION_FAILED,
                    error.getClass().getSimpleName()
                );
            }
        }
    }

    public synchronized ResourceStart tryCreateResources(
        Collection<ResourceRequest> requests,
        long currentDeviceGeneration
    ) {
        Objects.requireNonNull(requests, "requests");
        if (this.closed) {
            return new ResourceStart(
                null,
                UploadFailure.OWNER_CLOSED,
                "geometry owner is closed"
            );
        }
        if (currentDeviceGeneration != this.deviceGeneration) {
            return new ResourceStart(
                null,
                UploadFailure.GENERATION_MISMATCH,
                "resource request targets another device generation"
            );
        }
        if (stagingBusy()) {
            this.backpressureCount++;
            return new ResourceStart(
                null,
                UploadFailure.BACKPRESSURE,
                "bounded staging pool already has in-flight work"
            );
        }
        if (requests.isEmpty()) {
            throw new IllegalArgumentException(
                "resource request collection is empty"
            );
        }

        EnumMap<BufferKind, ResourceRequest> unique =
            new EnumMap<>(BufferKind.class);
        long stagedBytes = 0L;
        try {
            for (ResourceRequest request : requests) {
                Objects.requireNonNull(request, "request");
                if (unique.putIfAbsent(request.kind(), request) != null) {
                    throw new IllegalArgumentException(
                        "duplicate resource kind " + request.kind()
                    );
                }
                if (request.hasInitialContents()) {
                    stagedBytes = alignUp(
                        stagedBytes,
                        this.pagePolicy.alignmentBytes()
                    );
                    stagedBytes = Math.addExact(
                        stagedBytes,
                        request.bytes()
                    );
                }
            }
        } catch (ArithmeticException error) {
            return new ResourceStart(
                null,
                UploadFailure.PAYLOAD_SIZE_OVERFLOW,
                "resource upload size overflow"
            );
        }
        if (stagedBytes > this.stagingCapacity) {
            this.backpressureCount++;
            return new ResourceStart(
                null,
                UploadFailure.STAGING_CAPACITY_EXCEEDED,
                "resource initialization exceeds staging capacity"
            );
        }

        EnumMap<BufferKind, Allocation> allocations =
            new EnumMap<>(BufferKind.class);
        try {
            for (ResourceRequest request : unique.values()) {
                allocations.put(
                    request.kind(),
                    allocate(request.kind(), request.bytes())
                );
            }
        } catch (BudgetFailure failure) {
            rollback(allocations.values());
            return new ResourceStart(
                null,
                failure.reason,
                failure.getMessage()
            );
        }

        List<CopyRegion> copies = new ArrayList<>(unique.size());
        long stagingOffset = 0L;
        try {
            for (ResourceRequest request : unique.values()) {
                if (!request.hasInitialContents()) {
                    continue;
                }
                stagingOffset = alignUp(
                    stagingOffset,
                    this.pagePolicy.alignmentBytes()
                );
                this.staging.write(
                    stagingOffset,
                    request.bytes(),
                    request.initialContents()
                );
                Allocation allocation =
                    allocations.get(request.kind());
                copies.add(
                    new CopyRegion(
                        this.staging,
                        stagingOffset,
                        allocation.page.buffer,
                        allocation.offset,
                        allocation.requested
                    )
                );
                stagingOffset += request.bytes();
            }
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            rollback(allocations.values());
            this.failedUploads++;
            return new ResourceStart(
                null,
                UploadFailure.STAGING_WRITE_FAILED,
                error.getClass().getSimpleName()
            );
        }

        RecordResult recorded = this.device.recordCopies(copies);
        ResourceTicket ticket = new ResourceTicket(
            this,
            currentDeviceGeneration,
            allocations,
            recorded.completion(),
            recorded.successful()
                ? null
                : UploadFailure.COPY_RECORD_FAILED
        );
        this.resourceInFlight = ticket;
        return new ResourceStart(ticket, null, "");
    }

    public ResourcePoll pollResources(
        ResourceTicket ticket,
        long currentDeviceGeneration
    ) {
        requireResourceTicket(ticket);
        synchronized (ticket) {
            if (ticket.terminal) {
                throw new IllegalStateException(
                    "resource ticket is terminal"
                );
            }
            if (!ticket.completion.completed()) {
                return ResourcePoll.pendingResult();
            }
            UploadFailure failure = ticket.recordedFailure;
            if (
                currentDeviceGeneration != ticket.generation
                    || ticket.generation != this.deviceGeneration
            ) {
                failure = UploadFailure.GENERATION_MISMATCH;
            }
            if (failure != null) {
                rollback(ticket.allocations.values());
                finishResourceTicket(ticket);
                this.failedUploads++;
                return new ResourcePoll(
                    false,
                    null,
                    failure,
                    failure.name()
                );
            }
            EnumMap<BufferKind, GeometryHandle> handles =
                new EnumMap<>(BufferKind.class);
            for (
                Map.Entry<BufferKind, Allocation> entry
                    : ticket.allocations.entrySet()
            ) {
                handles.put(
                    entry.getKey(),
                    entry.getValue().handle(this.deviceGeneration)
                );
            }
            ResourcePublication publication =
                new ResourcePublication(
                    this,
                    ticket.generation,
                    handles
                );
            finishResourceTicket(ticket);
            this.successfulUploads++;
            return new ResourcePoll(
                false,
                publication,
                null,
                ""
            );
        }
    }

    public synchronized ResourceUpdateStart tryUpdateResources(
        Collection<ResourceWrite> writes,
        long currentDeviceGeneration
    ) {
        Objects.requireNonNull(writes, "writes");
        if (this.closed) {
            return new ResourceUpdateStart(
                null,
                UploadFailure.OWNER_CLOSED,
                "geometry owner is closed"
            );
        }
        if (currentDeviceGeneration != this.deviceGeneration) {
            return new ResourceUpdateStart(
                null,
                UploadFailure.GENERATION_MISMATCH,
                "resource update targets another device generation"
            );
        }
        if (stagingBusy()) {
            this.backpressureCount++;
            return new ResourceUpdateStart(
                null,
                UploadFailure.BACKPRESSURE,
                "bounded staging pool already has in-flight work"
            );
        }
        if (writes.isEmpty()) {
            throw new IllegalArgumentException(
                "resource update collection is empty"
            );
        }

        long stagedBytes = 0L;
        try {
            for (ResourceWrite write : writes) {
                Objects.requireNonNull(write, "write");
                requireBinding(write.target());
                stagedBytes = alignUp(
                    stagedBytes,
                    this.pagePolicy.alignmentBytes()
                );
                stagedBytes = Math.addExact(
                    stagedBytes,
                    write.length()
                );
            }
        } catch (ArithmeticException error) {
            return new ResourceUpdateStart(
                null,
                UploadFailure.PAYLOAD_SIZE_OVERFLOW,
                "dirty resource upload size overflow"
            );
        }
        if (stagedBytes > this.stagingCapacity) {
            this.backpressureCount++;
            return new ResourceUpdateStart(
                null,
                UploadFailure.STAGING_CAPACITY_EXCEEDED,
                "dirty resource upload exceeds staging capacity"
            );
        }

        List<CopyRegion> copies = new ArrayList<>(writes.size());
        long stagingOffset = 0L;
        try {
            for (ResourceWrite write : writes) {
                stagingOffset = alignUp(
                    stagingOffset,
                    this.pagePolicy.alignmentBytes()
                );
                this.staging.write(
                    stagingOffset,
                    write.length(),
                    write.contents()
                );
                BufferBinding binding =
                    requireBinding(write.target());
                copies.add(
                    new CopyRegion(
                        this.staging,
                        stagingOffset,
                        binding.buffer(),
                        binding.offset() + write.targetOffset(),
                        write.length()
                    )
                );
                stagingOffset += write.length();
            }
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            this.failedUploads++;
            return new ResourceUpdateStart(
                null,
                UploadFailure.STAGING_WRITE_FAILED,
                error.getClass().getSimpleName()
            );
        }
        RecordResult recorded = this.device.recordCopies(copies);
        ResourceUpdateTicket ticket = new ResourceUpdateTicket(
            this,
            currentDeviceGeneration,
            recorded.completion(),
            recorded.successful()
                ? null
                : UploadFailure.COPY_RECORD_FAILED
        );
        this.resourceUpdateInFlight = ticket;
        return new ResourceUpdateStart(ticket, null, "");
    }

    public ResourceUpdatePoll pollResourceUpdate(
        ResourceUpdateTicket ticket,
        long currentDeviceGeneration
    ) {
        requireResourceUpdateTicket(ticket);
        synchronized (ticket) {
            if (ticket.terminal) {
                throw new IllegalStateException(
                    "resource update ticket is terminal"
                );
            }
            if (!ticket.completion.completed()) {
                return new ResourceUpdatePoll(true, null, "");
            }
            UploadFailure failure = ticket.recordedFailure;
            if (
                currentDeviceGeneration != ticket.generation
                    || ticket.generation != this.deviceGeneration
            ) {
                failure = UploadFailure.GENERATION_MISMATCH;
            }
            finishResourceUpdateTicket(ticket);
            if (failure != null) {
                this.failedUploads++;
                return new ResourceUpdatePoll(
                    false,
                    failure,
                    failure.name()
                );
            }
            this.successfulUploads++;
            return new ResourceUpdatePoll(false, null, "");
        }
    }

    public ResourceRetirement beginResourceRetirement(
        ResourcePublication publication,
        Completion lastUseCompletion
    ) {
        requireResourcePublication(publication);
        Objects.requireNonNull(
            lastUseCompletion,
            "lastUseCompletion"
        );
        synchronized (publication) {
            publication.requireActive();
            publication.retiring = true;
        }
        return new ResourceRetirement(
            publication,
            lastUseCompletion
        );
    }

    public boolean pollResourceRetirement(
        ResourceRetirement retirement
    ) {
        Objects.requireNonNull(retirement, "retirement");
        if (retirement.terminal) {
            throw new IllegalStateException(
                "resource retirement is terminal"
            );
        }
        if (!retirement.completion.completed()) {
            return false;
        }
        ResourcePublication publication = retirement.publication;
        for (GeometryHandle handle : publication.handles.values()) {
            release(handle);
        }
        retirement.completion.close();
        retirement.terminal = true;
        synchronized (publication) {
            publication.retiring = false;
            publication.retired = true;
        }
        this.retirements++;
        return true;
    }

    public synchronized BufferBinding requireBinding(
        GeometryHandle handle
    ) {
        Objects.requireNonNull(handle, "handle");
        if (handle.deviceGeneration() != this.deviceGeneration) {
            throw new IllegalArgumentException(
                "geometry handle belongs to another device"
            );
        }
        Page page = pageFor(handle);
        return new BufferBinding(
            handle.kind(),
            handle.deviceGeneration(),
            handle.pageSerial(),
            page.buffer,
            handle.byteOffset(),
            handle.byteLength()
        );
    }

    public synchronized GpuBufferSlice requireVulkanSlice(
        GeometryHandle handle
    ) {
        BufferBinding binding = requireBinding(handle);
        if (
            !(binding.buffer()
                instanceof VulkanOwnedBuffer vulkan)
        ) {
            throw new IllegalStateException(
                "resource has no Vulkan buffer binding"
            );
        }
        return vulkan.buffer.slice(
            binding.offset(),
            binding.length()
        );
    }

    public synchronized GpuBufferSlice requireVulkanPageSlice(
        BufferKind kind,
        long pageSerial
    ) {
        requireOpen();
        if (pageSerial <= 0L) {
            throw new IllegalArgumentException(
                "page serial must be positive"
            );
        }
        Page page = this.pages.get(
            Objects.requireNonNull(kind, "kind")
        ).stream()
            .filter(candidate -> candidate.serial == pageSerial)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "geometry page is not owned"
            ));
        if (!(page.buffer instanceof VulkanOwnedBuffer vulkan)) {
            throw new IllegalStateException(
                "page has no Vulkan buffer binding"
            );
        }
        return vulkan.buffer.slice();
    }

    public Retirement beginRetirement(
        Publication publication,
        CleanupDecision cleanup,
        Completion lastUseCompletion,
        long completedSubmissionSerial
    ) {
        requirePublication(publication);
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(
            lastUseCompletion,
            "lastUseCompletion"
        );
        if (!cleanup.cleanupRequired()) {
            throw new IllegalArgumentException(
                "geometry retirement requires a lifecycle permit"
            );
        }
        synchronized (publication) {
            if (publication.retiring || publication.retired) {
                throw new IllegalStateException(
                    "publication is already retiring"
                );
            }
            if (
                completedSubmissionSerial
                    < publication.lastKnownUse
            ) {
                throw new IllegalArgumentException(
                    "completion serial predates last known use"
                );
            }
            publication.retiring = true;
        }
        return new Retirement(
            publication,
            cleanup,
            lastUseCompletion,
            completedSubmissionSerial
        );
    }

    public boolean pollRetirement(Retirement retirement) {
        Objects.requireNonNull(retirement, "retirement");
        if (retirement.terminal) {
            throw new IllegalStateException(
                "retirement ticket is terminal"
            );
        }
        if (!retirement.completion.completed()) {
            return false;
        }
        Publication publication = retirement.publication;
        try {
            for (GeometryHandle handle : publication.handles.values()) {
                release(handle);
            }
            publication.payload.retire();
            publication.lifecycle.completeRetirement(
                retirement.cleanup.retirementPermit(),
                retirement.completionSerial
            );
            retirement.completion.close();
            retirement.terminal = true;
            synchronized (publication) {
                publication.retired = true;
                publication.retiring = false;
            }
            this.retirements++;
            return true;
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            publication.lifecycle.cleanupFailed(
                retirement.cleanup.retirementPermit()
            );
            throw error;
        }
    }

    public synchronized Snapshot snapshot() {
        long freeBytes = this.committedBytes - this.usedBytes;
        long largestFree = this.pages.values().stream()
            .flatMap(Collection::stream)
            .flatMap(page -> page.free.values().stream())
            .mapToLong(Long::longValue)
            .max()
            .orElse(0L);
        int pageCount = this.pages.values().stream()
            .mapToInt(List::size)
            .sum();
        return new Snapshot(
            this.requestedBytes,
            this.committedBytes,
            this.usedBytes,
            this.peakUsedBytes,
            freeBytes,
            largestFree,
            Math.max(0L, freeBytes - largestFree),
            this.stagingCapacity,
            pageCount,
            this.allocationCount,
            this.successfulUploads,
            this.failedUploads,
            this.backpressureCount,
            this.retirements,
            stagingBusy(),
            this.closed
        );
    }

    /**
     * Queues buffers for destruction before Mojang destroys its encoder/VMA.
     * Budget leases remain in GPU retirement until the existing runtime's
     * post-encoder-drain completion hook runs.
     */
    public synchronized boolean closeAndReport() {
        if (this.closed) {
            return true;
        }
        if (stagingBusy()) {
            return false;
        }
        if (this.allocationCount != 0) {
            return false;
        }
        boolean clean = true;
        for (List<Page> kindPages : this.pages.values()) {
            Iterator<Page> iterator = kindPages.iterator();
            while (iterator.hasNext()) {
                Page page = iterator.next();
                try {
                    if (!page.closeQueued) {
                        page.buffer.close();
                        page.closeQueued = true;
                    }
                    if (!this.budgets.retireAfterGpuUse(page.lease)) {
                        clean = false;
                    } else {
                        iterator.remove();
                    }
                } catch (
                    RuntimeException | LinkageError error
                ) {
                    clean = false;
                }
            }
        }
        try {
            if (!this.stagingCloseQueued) {
                this.staging.close();
                this.stagingCloseQueued = true;
            }
            if (
                this.stagingCloseQueued
                    && !this.stagingLeaseRetiring
            ) {
                if (!this.budgets.retireAfterGpuUse(this.stagingLease)) {
                    clean = false;
                } else {
                    this.stagingLeaseRetiring = true;
                }
            }
        } catch (RuntimeException | LinkageError error) {
            clean = false;
        }
        if (this.pages.values().stream().anyMatch(
            pages -> !pages.isEmpty()
        )) {
            clean = false;
        }
        if (clean) {
            this.closed = true;
            this.committedBytes = 0L;
            this.usedBytes = 0L;
            this.requestedBytes = 0L;
            this.allocationCount = 0;
        }
        return clean;
    }

    @Override
    public void close() {
        if (!closeAndReport()) {
            throw new IllegalStateException(
                "geometry owner still has in-flight or failed cleanup"
            );
        }
    }

    private synchronized Allocation allocate(
        BufferKind kind,
        long requested
    ) throws BudgetFailure {
        requireOpen();
        long committed = alignUp(
            requested,
            this.pagePolicy.alignmentBytes()
        );
        for (Page page : this.pages.get(kind)) {
            Allocation allocation = allocateFrom(
                page,
                requested,
                committed
            );
            if (allocation != null) {
                accountAllocation(allocation);
                return allocation;
            }
        }

        long pageBytes;
        try {
            pageBytes = this.pagePolicy.bytesFor(committed);
        } catch (IllegalArgumentException error) {
            throw new BudgetFailure(
                UploadFailure.DEVICE_ALLOCATION_FAILED,
                error.getMessage()
            );
        }
        if (
            this.committedBytes > this.maximumGeometryBytes - pageBytes
        ) {
            throw new BudgetFailure(
                UploadFailure.VRAM_BUDGET_REJECTED,
                "geometry owner maximum would be exceeded"
            );
        }
        long lease = this.budgets.tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.TERRAIN,
            pageBytes
        );
        if (lease == 0L) {
            throw new BudgetFailure(
                UploadFailure.VRAM_BUDGET_REJECTED,
                "VRAM budget rejected a device-local page"
            );
        }

        OwnedBuffer buffer = null;
        Page createdPage = null;
        try {
            buffer = this.device.createDeviceBuffer(kind, pageBytes);
            Page page = new Page(
                kind,
                ++this.pageSerial,
                Objects.requireNonNull(buffer, "device buffer"),
                lease
            );
            createdPage = page;
            this.pages.get(kind).add(page);
            this.committedBytes += pageBytes;
            Allocation allocation = allocateFrom(
                page,
                requested,
                committed
            );
            if (allocation == null) {
                throw new IllegalStateException(
                    "new page could not satisfy its triggering allocation"
                );
            }
            accountAllocation(allocation);
            return allocation;
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            if (createdPage != null) {
                this.pages.get(kind).remove(createdPage);
                this.committedBytes -= pageBytes;
            }
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (RuntimeException ignored) {
                    // Creation remains failed closed.
                }
                this.budgets.retireAfterGpuUse(lease);
            } else {
                this.budgets.release(lease);
            }
            throw new BudgetFailure(
                UploadFailure.DEVICE_ALLOCATION_FAILED,
                error.getClass().getSimpleName()
            );
        }
    }

    private Allocation allocateFrom(
        Page page,
        long requested,
        long committed
    ) {
        long originalOffset = -1L;
        long originalLength = 0L;
        long alignedOffset = 0L;
        long padding = 0L;
        for (Map.Entry<Long, Long> range : page.free.entrySet()) {
            long candidateOffset = alignUp(
                range.getKey(),
                this.pagePolicy.alignmentBytes()
            );
            long candidatePadding = candidateOffset - range.getKey();
            if (
                candidatePadding > range.getValue()
                    || committed
                        > range.getValue() - candidatePadding
            ) {
                continue;
            }
            originalOffset = range.getKey();
            originalLength = range.getValue();
            alignedOffset = candidateOffset;
            padding = candidatePadding;
            break;
        }
        if (originalOffset < 0L) {
            return null;
        }
        page.free.remove(originalOffset);
        if (padding != 0L) {
            page.free.put(originalOffset, padding);
        }
        long tailOffset = alignedOffset + committed;
        long tailLength = originalLength - padding - committed;
        if (tailLength != 0L) {
            page.free.put(tailOffset, tailLength);
        }
        page.used += committed;
        page.allocations++;
        return new Allocation(
            page,
            alignedOffset,
            requested,
            committed
        );
    }

    private void accountAllocation(Allocation allocation) {
        this.requestedBytes += allocation.requested;
        this.usedBytes += allocation.committed;
        this.peakUsedBytes = Math.max(
            this.peakUsedBytes,
            this.usedBytes
        );
        this.allocationCount++;
    }

    private synchronized void release(GeometryHandle handle) {
        requireOpen();
        if (handle.deviceGeneration() != this.deviceGeneration) {
            throw new IllegalArgumentException(
                "geometry handle belongs to another device"
            );
        }
        Page page = pageFor(handle);
        long committed = alignUp(
            handle.byteLength(),
            this.pagePolicy.alignmentBytes()
        );
        mergeFree(page, handle.byteOffset(), committed);
        page.used -= committed;
        page.allocations--;
        this.requestedBytes -= handle.byteLength();
        this.usedBytes -= committed;
        this.allocationCount--;
    }

    private Page pageFor(GeometryHandle handle) {
        return this.pages.get(handle.kind()).stream()
            .filter(candidate ->
                candidate.serial == handle.pageSerial()
            )
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "geometry page is not owned"
            ));
    }

    private static void mergeFree(
        Page page,
        long offset,
        long length
    ) {
        Map.Entry<Long, Long> lower = page.free.floorEntry(offset);
        if (
            lower != null
                && lower.getKey() + lower.getValue() > offset
        ) {
            throw new IllegalStateException(
                "double-free or overlapping geometry range"
            );
        }
        Map.Entry<Long, Long> higher = page.free.ceilingEntry(offset);
        if (higher != null && offset + length > higher.getKey()) {
            throw new IllegalStateException(
                "double-free or overlapping geometry range"
            );
        }
        if (
            lower != null
                && lower.getKey() + lower.getValue() == offset
        ) {
            offset = lower.getKey();
            length += lower.getValue();
            page.free.remove(lower.getKey());
        }
        higher = page.free.ceilingEntry(offset);
        if (higher != null && offset + length == higher.getKey()) {
            length += higher.getValue();
            page.free.remove(higher.getKey());
        }
        page.free.put(offset, length);
    }

    private void finishFailedTicket(UploadTicket ticket) {
        CleanupDecision cleanup = ticket.lifecycle.failUpload(
            ticket.uploadPermit,
            0L
        );
        rollback(ticket.allocations.values());
        try {
            ticket.batch.close();
        } catch (RuntimeException ignored) {
            // Lifecycle cleanup remains fail-closed.
        }
        completeFailedCleanup(ticket.lifecycle, cleanup);
        this.failedUploads++;
        finishTicket(ticket);
    }

    private void finishTicket(UploadTicket ticket) {
        ticket.completion.close();
        ticket.terminal = true;
        synchronized (this) {
            if (this.inFlight != ticket) {
                throw new IllegalStateException(
                    "upload ticket is not the in-flight owner"
                );
            }
            this.inFlight = null;
        }
    }

    private void finishResourceTicket(ResourceTicket ticket) {
        ticket.completion.close();
        ticket.terminal = true;
        synchronized (this) {
            if (this.resourceInFlight != ticket) {
                throw new IllegalStateException(
                    "resource ticket is not the in-flight owner"
                );
            }
            this.resourceInFlight = null;
        }
    }

    private void finishResourceUpdateTicket(
        ResourceUpdateTicket ticket
    ) {
        ticket.completion.close();
        ticket.terminal = true;
        synchronized (this) {
            if (this.resourceUpdateInFlight != ticket) {
                throw new IllegalStateException(
                    "resource update is not the in-flight owner"
                );
            }
            this.resourceUpdateInFlight = null;
        }
    }

    private synchronized void rollback(
        Collection<Allocation> allocations
    ) {
        for (Allocation allocation : allocations) {
            GeometryHandle handle =
                allocation.handle(this.deviceGeneration);
            release(handle);
        }
    }

    private static void completeFailedCleanup(
        NativeTerrainSectionLifecycle lifecycle,
        CleanupDecision cleanup
    ) {
        if (cleanup.cleanupRequired()) {
            lifecycle.completeRetirement(
                cleanup.retirementPermit(),
                cleanup.retirementPermit()
                    .minimumCompletedSubmission()
            );
        }
    }

    private void requireTicket(UploadTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.owner != this) {
            throw new IllegalArgumentException(
                "upload ticket belongs to another owner"
            );
        }
    }

    private void requireResourceTicket(ResourceTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.owner != this) {
            throw new IllegalArgumentException(
                "resource ticket belongs to another owner"
            );
        }
    }

    private void requireResourceUpdateTicket(
        ResourceUpdateTicket ticket
    ) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.owner != this) {
            throw new IllegalArgumentException(
                "resource update belongs to another owner"
            );
        }
    }

    private void requireResourcePublication(
        ResourcePublication publication
    ) {
        Objects.requireNonNull(publication, "publication");
        if (publication.owner != this) {
            throw new IllegalArgumentException(
                "resource publication belongs to another owner"
            );
        }
    }

    private void requirePublication(Publication publication) {
        Objects.requireNonNull(publication, "publication");
        if (publication.owner != this) {
            throw new IllegalArgumentException(
                "publication belongs to another owner"
            );
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                "geometry owner is closed"
            );
        }
    }

    private boolean stagingBusy() {
        return this.inFlight != null
            || this.resourceInFlight != null
            || this.resourceUpdateInFlight != null;
    }

    private static UploadStart failedStart(
        UploadFailure failure,
        String detail
    ) {
        return new UploadStart(null, failure, detail);
    }

    private static NativeTerrainSectionLifecycle.Cause staleCause(
        GenerationStamp stale,
        GenerationStamp current,
        SectionIdentity staleSection,
        SectionIdentity currentSection
    ) {
        if (
            stale.device() != current.device()
        ) {
            return NativeTerrainSectionLifecycle.Cause.DEVICE_CHANGE;
        }
        if (
            stale.world() != current.world()
                || !staleSection.worldIdentity().equals(
                    currentSection.worldIdentity()
                )
        ) {
            return NativeTerrainSectionLifecycle.Cause.WORLD_CHANGE;
        }
        if (stale.resources() != current.resources()) {
            return NativeTerrainSectionLifecycle.Cause.RESOURCE_RELOAD;
        }
        if (stale.renderer() != current.renderer()) {
            return NativeTerrainSectionLifecycle.Cause.RENDERER_CHANGE;
        }
        return NativeTerrainSectionLifecycle.Cause.DIRTY;
    }

    private static long alignUp(long value, long alignment) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                "value must not be negative"
            );
        }
        long mask = alignment - 1L;
        if ((alignment & mask) != 0L) {
            throw new IllegalArgumentException(
                "alignment must be a power of two"
            );
        }
        return Math.addExact(value, mask) & ~mask;
    }

    private static boolean isPowerOfTwo(long value) {
        return value > 0L && (value & (value - 1L)) == 0L;
    }

    private static void requireRange(
        long offset,
        long length,
        long capacity,
        String name
    ) {
        if (
            offset < 0L
                || length <= 0L
                || offset > capacity
                || length > capacity - offset
        ) {
            throw new IllegalArgumentException(
                "invalid " + name + " range"
            );
        }
    }

    private static final class BudgetFailure extends Exception {
        private final UploadFailure reason;

        private BudgetFailure(
            UploadFailure reason,
            String message
        ) {
            super(message);
            this.reason = reason;
        }
    }

    /**
     * Production bridge to Mojang's existing VMA-backed Vulkan resources.
     * MAP_WRITE allocations are HOST_VISIBLE|HOST_COHERENT in this exact
     * Mojang implementation, so no additional non-coherent flush is needed.
     */
    public static final class VulkanDeviceAccess
        implements DeviceAccess {
        private final VulkanDevice device;
        private final VulkanCommandEncoder encoder;

        public VulkanDeviceAccess(VulkanDevice device) {
            this.device = Objects.requireNonNull(device, "device");
            this.encoder = device.createCommandEncoder();
        }

        @Override
        public OwnedBuffer createDeviceBuffer(
            BufferKind kind,
            long bytes
        ) {
            int usage = switch (kind) {
                case VERTEX ->
                    GpuBuffer.USAGE_COPY_DST
                        | GpuBuffer.USAGE_VERTEX;
                case INDEX, SHARED_INDEX ->
                    GpuBuffer.USAGE_COPY_DST
                        | GpuBuffer.USAGE_INDEX;
                case STORAGE_SCENE ->
                    GpuBuffer.USAGE_COPY_DST
                        | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
                        | (1 << 10);
                case INDIRECT_COMMAND, INDIRECT_COUNT ->
                    GpuBuffer.USAGE_COPY_DST
                        | GpuBuffer.USAGE_INDIRECT_PARAMETERS
                        | (1 << 10);
            };
            return new VulkanOwnedBuffer(
                this.device.createBuffer(
                    () -> "BlockFrame Native Terrain "
                        + kind
                        + " Page",
                    usage,
                    bytes
                )
            );
        }

        @Override
        public MappedStagingBuffer createMappedStaging(long bytes) {
            GpuBuffer buffer = this.device.createBuffer(
                () -> "BlockFrame Native Terrain Upload Pool",
                GpuBuffer.USAGE_MAP_WRITE
                    | GpuBuffer.USAGE_COPY_SRC
                    | GpuBuffer.USAGE_HINT_CLIENT_STORAGE,
                bytes
            );
            try {
                return new VulkanStagingBuffer(
                    buffer,
                    buffer.map(false, true)
                );
            } catch (
                RuntimeException | LinkageError | OutOfMemoryError error
            ) {
                buffer.close();
                throw error;
            }
        }

        @Override
        public RecordResult recordCopies(List<CopyRegion> copies) {
            Throwable failure = null;
            try {
                for (CopyRegion copy : copies) {
                    VulkanStagingBuffer source =
                        (VulkanStagingBuffer)copy.source();
                    VulkanOwnedBuffer destination =
                        (VulkanOwnedBuffer)copy.destination();
                    this.encoder.copyToBuffer(
                        source.buffer.slice(
                            copy.sourceOffset(),
                            copy.length()
                        ),
                        destination.buffer.slice(
                            copy.destinationOffset(),
                            copy.length()
                        )
                    );
                }
            } catch (
                RuntimeException | LinkageError | OutOfMemoryError error
            ) {
                failure = error;
            }
            GpuFence fence = this.encoder.createFence();
            return new RecordResult(
                new VulkanCompletion(fence),
                failure
            );
        }
    }

    private static class VulkanOwnedBuffer
        implements OwnedBuffer {
        protected final GpuBuffer buffer;

        private VulkanOwnedBuffer(GpuBuffer buffer) {
            this.buffer = Objects.requireNonNull(buffer, "buffer");
        }

        @Override
        public long size() {
            return this.buffer.size();
        }

        @Override
        public void close() {
            this.buffer.close();
        }
    }

    private static final class VulkanStagingBuffer
        extends VulkanOwnedBuffer
        implements MappedStagingBuffer {
        private final GpuBufferSlice.MappedView mapping;
        private boolean closed;

        private VulkanStagingBuffer(
            GpuBuffer buffer,
            GpuBufferSlice.MappedView mapping
        ) {
            super(buffer);
            this.mapping = Objects.requireNonNull(mapping, "mapping");
        }

        @Override
        public void copyFrom(
            long destinationOffset,
            ChannelPayload payload
        ) {
            Objects.requireNonNull(payload, "payload");
            if (this.closed) {
                throw new IllegalStateException(
                    "staging buffer is closed"
                );
            }
            int length = payload.byteLength();
            requireRange(
                destinationOffset,
                length,
                this.buffer.size(),
                "staging write"
            );
            ByteBuffer destination = this.mapping.data().duplicate();
            destination.position(Math.toIntExact(destinationOffset));
            destination.limit(
                Math.toIntExact(destinationOffset + length)
            );
            payload.copyBytesTo(destination.slice());
        }

        @Override
        public void write(
            long destinationOffset,
            long length,
            BufferWriter writer
        ) {
            Objects.requireNonNull(writer, "writer");
            if (this.closed) {
                throw new IllegalStateException(
                    "staging buffer is closed"
                );
            }
            requireRange(
                destinationOffset,
                length,
                this.buffer.size(),
                "staging write"
            );
            ByteBuffer destination = this.mapping.data().duplicate();
            destination.position(Math.toIntExact(destinationOffset));
            destination.limit(
                Math.toIntExact(destinationOffset + length)
            );
            ByteBuffer slice = destination.slice();
            writer.write(slice);
            if (slice.hasRemaining()) {
                throw new IllegalStateException(
                    "resource writer did not fill its complete range"
                );
            }
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.mapping.close();
                super.close();
            }
        }
    }

    private static final class VulkanCompletion
        implements Completion {
        private final GpuFence fence;
        private boolean closed;

        private VulkanCompletion(GpuFence fence) {
            this.fence = Objects.requireNonNull(fence, "fence");
        }

        @Override
        public boolean completed() {
            return this.closed || this.fence.awaitCompletion(0L);
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.fence.close();
            }
        }
    }
}
