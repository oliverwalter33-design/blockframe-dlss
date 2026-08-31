package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.AttributeContract;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.AttributeEncoding;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Provenance;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Result;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Vertex;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ContentProvenance;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexLayout;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.InstancingContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Layer;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MeshDescriptor;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.PayloadRange;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ProducerIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.RetirementToken;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.VertexLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Native-owned pre-merge section compiler.
 *
 * <p>The compiler consumes only an immutable BlockFrame snapshot. SOLID and
 * CUTOUT are encoded into owned CPU bytes and the permanent producer ABI.
 * Other known categories remain isolated, typed and non-submittable until
 * their renderer lanes exist. An unknown contract fails the complete compile
 * without exposing a partial payload.</p>
 */
public final class BlockFrameSectionCompiler {
    public enum FailureReason {
        CANCELLED,
        GENERATION_MISMATCH,
        CENSUS_MISMATCH,
        ASSET_NOT_IN_CENSUS,
        STALE_ASSET_CONTRACT,
        UNSUPPORTED_ASSET,
        UNSUPPORTED_SOLID_CUTOUT_FORMAT,
        INVALID_PRIMITIVE,
        INTERNAL_FAILURE
    }

    public enum BatchState {
        COMPILED,
        PUBLISHED,
        CLOSED
    }

    public enum PublicationState {
        ACTIVE,
        RETIRED
    }

    @FunctionalInterface
    public interface CancellationSignal {
        CancellationSignal NEVER = () -> false;

        boolean cancelled();
    }

    /**
     * Permanent producer identity and lifecycle serial range for this compiler
     * generation. The geometry owner supplies these values.
     */
    public record CompilerContract(
        ProducerIdentity producer,
        StableId transformLayoutId,
        long transformGeneration,
        long firstRetirementSerial
    ) {
        public CompilerContract {
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(
                transformLayoutId,
                "transformLayoutId"
            ).requirePresent("transformLayoutId");
            requirePositive(
                transformGeneration,
                "transformGeneration"
            );
            requirePositive(
                firstRetirementSerial,
                "firstRetirementSerial"
            );
        }
    }

    /**
     * Complete source identity retained per primitive before channel-specific
     * payload generation.
     */
    public record PrimitiveManifest(
        long primitiveId,
        StableId assetId,
        Digest assetContractDigest,
        StableId blockStateOrModelId,
        StableId renderTypeId,
        Category category,
        Provenance provenance,
        VertexLayout vertexLayout,
        IndexType indexType,
        TerrainMeshProducerABI.MaterialBinding material,
        TerrainMeshProducerABI.ShaderContract shader,
        AttributeContract attributes,
        Bounds bounds,
        Digest geometryDigest,
        boolean submissionCapable
    ) {
        public PrimitiveManifest {
            requirePositive(primitiveId, "primitiveId");
            Objects.requireNonNull(assetId, "assetId")
                .requirePresent("assetId");
            Objects.requireNonNull(
                assetContractDigest,
                "assetContractDigest"
            ).requireKnown("assetContractDigest");
            Objects.requireNonNull(
                blockStateOrModelId,
                "blockStateOrModelId"
            ).requirePresent("blockStateOrModelId");
            Objects.requireNonNull(renderTypeId, "renderTypeId")
                .requirePresent("renderTypeId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(provenance, "provenance");
            Objects.requireNonNull(vertexLayout, "vertexLayout");
            Objects.requireNonNull(indexType, "indexType");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(
                geometryDigest,
                "geometryDigest"
            ).requireKnown("geometryDigest");
        }
    }

    /**
     * One owned output channel. Bytes are never exposed mutably. Foundation B
     * copies directly into its mapped staging range and releases CPU bytes
     * only after the upload fence completes; immutable manifests and
     * descriptors remain alive until geometry retirement.
     */
    public static final class ChannelPayload {
        private final Category category;
        private final boolean submissionCapable;
        private final List<PrimitiveManifest> manifests;
        private final List<MeshDescriptor> descriptors;
        private byte[] bytes;
        private final int byteLength;
        private NativeTerrainPayloadArena.Lease arenaLease;
        private boolean bytesReleased;
        private boolean closed;

        private ChannelPayload(
            Category category,
            boolean submissionCapable,
            List<PrimitiveManifest> manifests,
            List<MeshDescriptor> descriptors,
            OwnedBytes ownedBytes
        ) {
            this.category = Objects.requireNonNull(
                category,
                "category"
            );
            this.submissionCapable = submissionCapable;
            this.manifests = List.copyOf(manifests);
            this.descriptors = List.copyOf(descriptors);
            ownedBytes = Objects.requireNonNull(
                ownedBytes,
                "ownedBytes"
            );
            this.bytes = ownedBytes.bytes();
            this.byteLength = ownedBytes.length();
            this.arenaLease = ownedBytes.arenaLease();
            if (
                !submissionCapable
                    && (!this.descriptors.isEmpty()
                        || this.byteLength != 0)
            ) {
                throw new IllegalArgumentException(
                    "deferred channel must not expose render payload"
                );
            }
        }

        public synchronized Category category() {
            requireMetadata();
            return this.category;
        }

        public synchronized boolean submissionCapable() {
            requireMetadata();
            return this.submissionCapable;
        }

        public synchronized int primitiveCount() {
            requireMetadata();
            return this.manifests.size();
        }

        public synchronized List<PrimitiveManifest> manifests() {
            requireMetadata();
            return this.manifests;
        }

        public synchronized List<MeshDescriptor> descriptors() {
            requireMetadata();
            return this.descriptors;
        }

        public synchronized int byteLength() {
            requireBytes();
            return this.byteLength;
        }

        public synchronized byte[] bytesCopy() {
            requireBytes();
            return Arrays.copyOf(this.bytes, this.byteLength);
        }

        public synchronized void copyBytesTo(
            java.nio.ByteBuffer destination
        ) {
            requireBytes();
            Objects.requireNonNull(destination, "destination");
            if (destination.remaining() < this.byteLength) {
                throw new IllegalArgumentException(
                    "staging destination is too small"
                );
            }
            destination.put(this.bytes, 0, this.byteLength);
        }

        public synchronized void releaseUploadedBytes() {
            requireMetadata();
            if (!this.bytesReleased) {
                releaseBytes();
            }
        }

        public synchronized boolean bytesResident() {
            requireMetadata();
            return !this.bytesReleased;
        }

        private synchronized void closeOwnedBytes() {
            if (!this.closed) {
                if (!this.bytesReleased) {
                    releaseBytes();
                }
                this.closed = true;
            }
        }

        private void releaseBytes() {
            if (this.arenaLease != null) {
                this.arenaLease.close();
                this.arenaLease = null;
            } else {
                Arrays.fill(this.bytes, (byte)0);
            }
            this.bytes = null;
            this.bytesReleased = true;
        }

        private void requireMetadata() {
            if (this.closed) {
                throw new IllegalStateException(
                    "channel payload is closed"
                );
            }
        }

        private void requireBytes() {
            requireMetadata();
            if (this.bytesReleased) {
                throw new IllegalStateException(
                    "channel upload bytes were released"
                );
            }
        }
    }

    /**
     * Compiler-owned atomic batch. publish() transfers every populated
     * submittable channel in one state transition; deferred channels prevent
     * publication of the complete backend.
     */
    public static final class CompiledPayloadBatch
        implements AutoCloseable {
        private final GenerationStamp generations;
        private final SectionIdentity section;
        private final Digest censusDigest;
        private final Map<Category, ChannelPayload> channels;
        private BatchState state = BatchState.COMPILED;

        private CompiledPayloadBatch(
            GenerationStamp generations,
            SectionIdentity section,
            Digest censusDigest,
            Map<Category, ChannelPayload> channels
        ) {
            this.generations = generations;
            this.section = section;
            this.censusDigest = censusDigest;
            this.channels = Collections.unmodifiableMap(
                new EnumMap<>(channels)
            );
        }

        public synchronized BatchState state() {
            return this.state;
        }

        public synchronized GenerationStamp generations() {
            requireCompiled();
            return this.generations;
        }

        public synchronized SectionIdentity section() {
            requireCompiled();
            return this.section;
        }

        public synchronized Digest censusDigest() {
            requireCompiled();
            return this.censusDigest;
        }

        public synchronized Map<Category, ChannelPayload> channels() {
            requireCompiled();
            return this.channels;
        }

        public synchronized ChannelPayload channel(Category category) {
            requireCompiled();
            return this.channels.get(
                Objects.requireNonNull(category, "category")
            );
        }

        public synchronized boolean fullySubmittable() {
            requireCompiled();
            for (ChannelPayload channel : this.channels.values()) {
                if (
                    channel.primitiveCount() != 0
                        && !channel.submissionCapable()
                ) {
                    return false;
                }
            }
            return true;
        }

        public synchronized PublishedPayload publish() {
            requireCompiled();
            if (!fullySubmittable()) {
                throw new IllegalStateException(
                    "deferred channels require the Mojang backend"
                );
            }
            PublishedPayload publication =
                new PublishedPayload(
                    this.generations,
                    this.section,
                    this.censusDigest,
                    this.channels
                );
            this.state = BatchState.PUBLISHED;
            return publication;
        }

        @Override
        public synchronized void close() {
            if (this.state == BatchState.PUBLISHED) {
                throw new IllegalStateException(
                    "published payload must be retired by its owner"
                );
            }
            if (this.state == BatchState.CLOSED) {
                return;
            }
            closeChannels(this.channels.values());
            this.state = BatchState.CLOSED;
        }

        private void requireCompiled() {
            if (this.state != BatchState.COMPILED) {
                throw new IllegalStateException(
                    "batch is " + this.state
                );
            }
        }
    }

    /**
     * Owner object returned only after an atomic full-batch publication.
     */
    public static final class PublishedPayload {
        private final GenerationStamp generations;
        private final SectionIdentity section;
        private final Digest censusDigest;
        private final Map<Category, ChannelPayload> channels;
        private PublicationState state = PublicationState.ACTIVE;

        private PublishedPayload(
            GenerationStamp generations,
            SectionIdentity section,
            Digest censusDigest,
            Map<Category, ChannelPayload> channels
        ) {
            this.generations = generations;
            this.section = section;
            this.censusDigest = censusDigest;
            this.channels = channels;
        }

        public synchronized PublicationState state() {
            return this.state;
        }

        public synchronized GenerationStamp generations() {
            requireActive();
            return this.generations;
        }

        public synchronized SectionIdentity section() {
            requireActive();
            return this.section;
        }

        public synchronized Digest censusDigest() {
            requireActive();
            return this.censusDigest;
        }

        public synchronized ChannelPayload channel(Category category) {
            requireActive();
            return this.channels.get(
                Objects.requireNonNull(category, "category")
            );
        }

        public synchronized void retire() {
            requireActive();
            closeChannels(this.channels.values());
            this.state = PublicationState.RETIRED;
        }

        private void requireActive() {
            if (this.state != PublicationState.ACTIVE) {
                throw new IllegalStateException(
                    "publication is retired"
                );
            }
        }
    }

    public static final class CompileResult {
        private final CompiledPayloadBatch batch;
        private final FailureReason failureReason;
        private final String detail;

        private CompileResult(
            CompiledPayloadBatch batch,
            FailureReason failureReason,
            String detail
        ) {
            this.batch = batch;
            this.failureReason = failureReason;
            this.detail = detail;
        }

        public boolean successful() {
            return this.batch != null;
        }

        public Optional<CompiledPayloadBatch> batch() {
            return Optional.ofNullable(this.batch);
        }

        public Optional<FailureReason> failureReason() {
            return Optional.ofNullable(this.failureReason);
        }

        public String detail() {
            return this.detail;
        }
    }

    private static final class CompileFailure extends Exception {
        private final FailureReason reason;

        private CompileFailure(
            FailureReason reason,
            String message
        ) {
            super(message);
            this.reason = reason;
        }
    }

    private static final class ChannelBuilder
        implements AutoCloseable {
        private final Category category;
        private final List<PrimitiveManifest> manifests =
            new ArrayList<>();
        private final List<MeshDescriptor> descriptors =
            new ArrayList<>();
        private final OwnedByteWriter writer;
        private boolean transferred;

        private ChannelBuilder(
            Category category,
            NativeTerrainPayloadArena arena
        ) {
            this.category = category;
            this.writer = new OwnedByteWriter(arena);
        }

        private void addManifest(PrimitiveManifest manifest) {
            this.manifests.add(manifest);
        }

        private long byteOffset() {
            return this.writer.size();
        }

        private void putVertex(Vertex vertex) {
            this.writer.putFloat(vertex.x());
            this.writer.putFloat(vertex.y());
            this.writer.putFloat(vertex.z());
            this.writer.putInt(vertex.color());
            this.writer.putFloat(vertex.atlasU());
            this.writer.putFloat(vertex.atlasV());
            this.writer.putInt(vertex.packedLight());
            this.writer.putInt(vertex.packedNormal());
        }

        private void addDescriptor(MeshDescriptor descriptor) {
            this.descriptors.add(descriptor);
        }

        private ChannelPayload finish(boolean submissionCapable) {
            OwnedBytes bytes = this.writer.takeBytes();
            if (!submissionCapable && bytes.length() != 0) {
                bytes.close();
                throw new IllegalStateException(
                    "deferred builder produced render bytes"
                );
            }
            this.transferred = true;
            return new ChannelPayload(
                this.category,
                submissionCapable,
                this.manifests,
                this.descriptors,
                bytes
            );
        }

        @Override
        public void close() {
            if (!this.transferred) {
                this.writer.close();
            }
        }
    }

    private static final class OwnedBytes implements AutoCloseable {
        private byte[] bytes;
        private final int length;
        private NativeTerrainPayloadArena.Lease arenaLease;

        private OwnedBytes(
            byte[] bytes,
            int length,
            NativeTerrainPayloadArena.Lease arenaLease
        ) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            if (length < 0 || length > bytes.length) {
                throw new IllegalArgumentException(
                    "invalid owned byte length"
                );
            }
            this.length = length;
            this.arenaLease = arenaLease;
        }

        private byte[] bytes() {
            requireOpen();
            return this.bytes;
        }

        private int length() {
            requireOpen();
            return this.length;
        }

        private NativeTerrainPayloadArena.Lease arenaLease() {
            requireOpen();
            NativeTerrainPayloadArena.Lease lease = this.arenaLease;
            this.arenaLease = null;
            return lease;
        }

        @Override
        public void close() {
            if (this.bytes == null) {
                return;
            }
            if (this.arenaLease != null) {
                this.arenaLease.close();
                this.arenaLease = null;
            } else {
                Arrays.fill(this.bytes, (byte)0);
            }
            this.bytes = null;
        }

        private void requireOpen() {
            if (this.bytes == null) {
                throw new IllegalStateException(
                    "owned bytes are closed"
                );
            }
        }
    }

    private static final class OwnedByteWriter
        implements AutoCloseable {
        private final NativeTerrainPayloadArena arena;
        private NativeTerrainPayloadArena.Lease arenaLease;
        private byte[] bytes;
        private int size;
        private boolean transferred;

        private OwnedByteWriter(NativeTerrainPayloadArena arena) {
            this.arena = arena;
        }

        private int size() {
            if (this.transferred) {
                throw new IllegalStateException(
                    "payload bytes were already transferred"
                );
            }
            return this.size;
        }

        private void putFloat(float value) {
            putInt(Float.floatToRawIntBits(value));
        }

        private void putInt(int value) {
            ensureCapacity(Integer.BYTES);
            this.bytes[this.size++] = (byte)value;
            this.bytes[this.size++] = (byte)(value >>> 8);
            this.bytes[this.size++] = (byte)(value >>> 16);
            this.bytes[this.size++] = (byte)(value >>> 24);
        }

        private OwnedBytes takeBytes() {
            if (this.transferred) {
                throw new IllegalStateException(
                    "payload bytes were already transferred"
                );
            }
            if (this.bytes == null) {
                this.transferred = true;
                return new OwnedBytes(new byte[0], 0, null);
            }
            byte[] owned;
            NativeTerrainPayloadArena.Lease lease = this.arenaLease;
            if (lease == null) {
                owned = Arrays.copyOf(this.bytes, this.size);
                Arrays.fill(this.bytes, (byte)0);
            } else {
                owned = this.bytes;
            }
            this.bytes = null;
            this.arenaLease = null;
            int length = this.size;
            this.size = 0;
            this.transferred = true;
            return new OwnedBytes(owned, length, lease);
        }

        private void ensureCapacity(int additionalBytes) {
            if (this.transferred) {
                throw new IllegalStateException(
                    "payload bytes were already transferred"
                );
            }
            if (this.bytes == null) {
                if (this.arena == null) {
                    this.bytes = new byte[256];
                } else {
                    this.arenaLease = this.arena.acquire(
                        NativeTerrainPayloadArena.MINIMUM_CLASS_BYTES
                    );
                    this.bytes = this.arenaLease.bytes();
                }
            }
            int required;
            try {
                required = Math.addExact(this.size, additionalBytes);
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                    "section payload size overflows",
                    error
                );
            }
            if (required <= this.bytes.length) {
                return;
            }
            int doubled;
            try {
                doubled = Math.multiplyExact(this.bytes.length, 2);
            } catch (ArithmeticException error) {
                doubled = Integer.MAX_VALUE;
            }
            int next = Math.max(required, doubled);
            if (this.arena == null) {
                this.bytes = Arrays.copyOf(this.bytes, next);
                return;
            }
            NativeTerrainPayloadArena.Lease replacement =
                this.arena.acquire(next);
            byte[] replacementBytes = replacement.bytes();
            System.arraycopy(
                this.bytes,
                0,
                replacementBytes,
                0,
                this.size
            );
            this.arenaLease.close();
            this.arenaLease = replacement;
            this.bytes = replacementBytes;
        }

        @Override
        public void close() {
            if (!this.transferred && this.bytes != null) {
                if (this.arenaLease != null) {
                    this.arenaLease.close();
                    this.arenaLease = null;
                } else {
                    Arrays.fill(this.bytes, (byte)0);
                }
                this.bytes = null;
                this.size = 0;
            }
        }

        private void requireOwned() {
            if (this.transferred || this.bytes == null) {
                throw new IllegalStateException(
                    "payload bytes were already transferred"
                );
            }
        }
    }

    private final CompilerContract contract;
    private final NativeTerrainPayloadArena payloadArena;
    private final NativeTerrainOwnershipEvidence.GenerationToken
        evidenceToken;

    public BlockFrameSectionCompiler(CompilerContract contract) {
        this(contract, null, null);
    }

    public BlockFrameSectionCompiler(
        CompilerContract contract,
        NativeTerrainPayloadArena payloadArena
    ) {
        this(contract, payloadArena, null);
    }

    public BlockFrameSectionCompiler(
        CompilerContract contract,
        NativeTerrainPayloadArena payloadArena,
        NativeTerrainOwnershipEvidence.GenerationToken evidenceToken
    ) {
        this.contract = Objects.requireNonNull(
            contract,
            "contract"
        );
        this.payloadArena = payloadArena;
        this.evidenceToken = evidenceToken;
    }

    public CompileResult compile(
        NativeTerrainSectionSnapshot snapshot,
        Result census,
        CancellationSignal cancellation
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(census, "census");
        Objects.requireNonNull(cancellation, "cancellation");

        EnumMap<Category, ChannelBuilder> builders =
            new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            builders.put(
                category,
                new ChannelBuilder(category, this.payloadArena)
            );
        }
        try {
            checkCancellation(cancellation);
            requireMatchingGenerations(snapshot, census);
            long descriptorOrdinal = 0L;
            for (Primitive primitive : snapshot.primitives()) {
                checkCancellation(cancellation);
                Entry entry = census.entry(primitive.assetId())
                    .orElseThrow(
                        () -> new CompileFailure(
                            FailureReason.ASSET_NOT_IN_CENSUS,
                            "snapshot asset is absent from census"
                        )
                    );
                requireCurrentContract(primitive, entry);
                if (entry.category() == Category.UNSUPPORTED) {
                    throw new CompileFailure(
                        FailureReason.UNSUPPORTED_ASSET,
                        entry.unavailableReason()
                    );
                }

                boolean renderable =
                    entry.category() == Category.SOLID
                        || entry.category() == Category.CUTOUT;
                PrimitiveManifest manifest = manifest(
                    primitive,
                    entry,
                    renderable
                );
                ChannelBuilder channel =
                    builders.get(entry.category());
                channel.addManifest(manifest);
                if (renderable) {
                    compileSolidOrCutout(
                        snapshot,
                        primitive,
                        entry,
                        channel,
                        descriptorOrdinal
                    );
                    descriptorOrdinal++;
                }
            }
            checkCancellation(cancellation);

            EnumMap<Category, ChannelPayload> channels =
                new EnumMap<>(Category.class);
            try {
                for (Category category : Category.values()) {
                    boolean submissionCapable =
                        category == Category.SOLID
                            || category == Category.CUTOUT;
                    channels.put(
                        category,
                        builders.get(category).finish(
                            submissionCapable
                        )
                    );
                }
            } catch (RuntimeException error) {
                closeChannels(channels.values());
                throw error;
            }
            CompiledPayloadBatch compiled =
                new CompiledPayloadBatch(
                    snapshot.generations(),
                    snapshot.section(),
                    snapshot.censusDigest(),
                    channels
                );
            NativeTerrainOwnershipEvidence
                .blockFrameSectionCompiled(this.evidenceToken);
            return success(
                compiled
            );
        } catch (CompileFailure failure) {
            return failure(failure.reason, failure.getMessage());
        } catch (IllegalArgumentException error) {
            return failure(
                FailureReason.INVALID_PRIMITIVE,
                error.getMessage()
            );
        } catch (RuntimeException error) {
            return failure(
                FailureReason.INTERNAL_FAILURE,
                error.getClass().getSimpleName()
            );
        } finally {
            for (ChannelBuilder builder : builders.values()) {
                builder.close();
            }
        }
    }

    private void compileSolidOrCutout(
        NativeTerrainSectionSnapshot snapshot,
        Primitive primitive,
        Entry entry,
        ChannelBuilder channel,
        long descriptorOrdinal
    ) throws CompileFailure {
        if (
            !entry.compilerSupported()
                || !solidCutoutContractSupported(entry)
        ) {
            throw new CompileFailure(
                FailureReason.UNSUPPORTED_SOLID_CUTOUT_FORMAT,
                "solid/cutout contract is not supported by V1"
            );
        }
        if (primitive.vertices().size() != 4) {
            throw new CompileFailure(
                FailureReason.INVALID_PRIMITIVE,
                "V1 solid/cutout compiler requires one exact quad"
            );
        }

        long vertexOffset = channel.byteOffset();
        for (Vertex vertex : primitive.vertices()) {
            channel.putVertex(vertex);
        }
        long vertexLength =
            (long)primitive.vertices().size()
                * entry.vertexLayout().orElseThrow().strideBytes();
        long retirementSerial;
        try {
            retirementSerial = Math.addExact(
                this.contract.firstRetirementSerial(),
                descriptorOrdinal
            );
        } catch (ArithmeticException error) {
            throw new CompileFailure(
                FailureReason.INVALID_PRIMITIVE,
                "retirement serial overflows"
            );
        }

        GenerationStamp generations = snapshot.generations();
        Layer layer = entry.category() == Category.SOLID
            ? Layer.SOLID
            : Layer.CUTOUT;
        MeshDescriptor descriptor = new MeshDescriptor(
            TerrainMeshProducerABI.VERSION,
            this.contract.producer(),
            generations,
            snapshot.section(),
            layer,
            entry.vertexLayout().orElseThrow(),
            new PayloadRange(vertexOffset, vertexLength),
            primitive.vertices().size(),
            IndexLayout.sequentialQuads(
                entry.indexType().orElseThrow(),
                6,
                0
            ),
            entry.material().orElseThrow(),
            entry.shader().orElseThrow(),
            primitive.bounds(),
            new ContentProvenance(
                TerrainMeshProducerABI.CONTENT_BLOCK_GEOMETRY,
                primitive.assetContractDigest(),
                true
            ),
            primitive.geometryDigest(),
            new InstancingContract(
                primitive.geometryDigest(),
                this.contract.transformLayoutId(),
                this.contract.transformGeneration(),
                1
            ),
            new RetirementToken(
                generations.device(),
                generations.renderer(),
                generations.world(),
                generations.resources(),
                generations.producer(),
                generations.sectionMesh(),
                retirementSerial
            )
        );
        channel.addDescriptor(descriptor);
    }

    private static PrimitiveManifest manifest(
        Primitive primitive,
        Entry entry,
        boolean submissionCapable
    ) {
        return new PrimitiveManifest(
            primitive.primitiveId(),
            primitive.assetId(),
            primitive.assetContractDigest(),
            primitive.blockStateOrModelId(),
            primitive.renderTypeId(),
            primitive.category(),
            primitive.provenance(),
            entry.vertexLayout().orElseThrow(),
            entry.indexType().orElseThrow(),
            entry.material().orElseThrow(),
            entry.shader().orElseThrow(),
            entry.attributes(),
            primitive.bounds(),
            primitive.geometryDigest(),
            submissionCapable
        );
    }

    private static boolean solidCutoutContractSupported(Entry entry) {
        VertexLayout layout = entry.vertexLayout().orElseThrow();
        AttributeContract attributes = entry.attributes();
        return layout.strideBytes()
                == TerrainMeshProducerABI.BLOCK_PAYLOAD_V2_STRIDE_BYTES
            && attributes.light() == AttributeEncoding.EXPLICIT
            && attributes.ambientOcclusion()
                == AttributeEncoding.BAKED_IN_COLOR
            && attributes.tint()
                == AttributeEncoding.BAKED_IN_COLOR
            && attributes.atlasUv() == AttributeEncoding.EXPLICIT
            && attributes.normals() == AttributeEncoding.EXPLICIT
            && (
                layout.semanticMask()
                    & TerrainMeshProducerABI.SEMANTIC_NORMAL_EXPLICIT
            ) != 0L;
    }

    private static void requireMatchingGenerations(
        NativeTerrainSectionSnapshot snapshot,
        Result census
    ) throws CompileFailure {
        if (
            snapshot.generations().resources()
                != census.resourceGeneration()
        ) {
            throw new CompileFailure(
                FailureReason.GENERATION_MISMATCH,
                "snapshot resource generation is stale"
            );
        }
        if (!snapshot.censusDigest().equals(census.digest())) {
            throw new CompileFailure(
                FailureReason.CENSUS_MISMATCH,
                "snapshot census digest is stale"
            );
        }
    }

    private static void requireCurrentContract(
        Primitive primitive,
        Entry entry
    ) throws CompileFailure {
        if (
            !primitive.assetContractDigest().equals(
                entry.contractDigest()
            )
                || primitive.category() != entry.category()
                || primitive.provenance() != entry.provenance()
                || !primitive.blockStateOrModelId().equals(
                    entry.blockStateOrModelId()
                )
                || !primitive.renderTypeId().equals(
                    entry.renderTypeId()
                )
        ) {
            throw new CompileFailure(
                FailureReason.STALE_ASSET_CONTRACT,
                "snapshot asset contract does not match the census"
            );
        }
    }

    private static void checkCancellation(
        CancellationSignal cancellation
    ) throws CompileFailure {
        if (cancellation.cancelled()) {
            throw new CompileFailure(
                FailureReason.CANCELLED,
                "compile generation was cancelled"
            );
        }
    }

    private static CompileResult success(
        CompiledPayloadBatch batch
    ) {
        return new CompileResult(batch, null, "");
    }

    private static CompileResult failure(
        FailureReason reason,
        String detail
    ) {
        return new CompileResult(
            null,
            Objects.requireNonNull(reason, "reason"),
            detail == null ? "" : detail
        );
    }

    private static void closeChannels(
        Collection<ChannelPayload> channels
    ) {
        for (ChannelPayload channel : channels) {
            channel.closeOwnedBytes();
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(
                name + " must be positive"
            );
        }
    }
}
