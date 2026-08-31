package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.IndexType;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.VertexLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable pre-world inventory of every terrain asset and render contract
 * required by the active resource and mod profile.
 *
 * <p>This class performs no Minecraft registry traversal itself. A future
 * owner-bound adapter supplies the observed contracts before backend
 * selection. Missing, custom or unknown contracts are represented explicitly
 * and make the complete native backend ineligible.</p>
 */
public final class NativeTerrainAssetCensus {
    public enum Category {
        SOLID,
        CUTOUT,
        TRANSLUCENT,
        FLUID,
        MOD_EXTRA,
        UNSUPPORTED
    }

    public enum Provenance {
        VANILLA_BLOCK,
        VANILLA_FLUID,
        NEOFORGE_EXTRA,
        CUSTOM,
        UNKNOWN
    }

    public enum AttributeEncoding {
        EXPLICIT,
        BAKED_IN_COLOR,
        DERIVED_FROM_GEOMETRY,
        NOT_AVAILABLE
    }

    public enum BlockerReason {
        CENSUS_INCOMPLETE,
        UNSUPPORTED_ASSET,
        UNKNOWN_PROVENANCE,
        CUSTOM_RENDER_TYPE,
        COMPILER_UNSUPPORTED,
        SUBMISSION_UNSUPPORTED,
        CATEGORY_NOT_SUPPORTED
    }

    /**
     * Shader-visible source attributes. NOT_AVAILABLE is a typed absence, not
     * an invented zero-valued attribute.
     */
    public record AttributeContract(
        AttributeEncoding light,
        AttributeEncoding ambientOcclusion,
        AttributeEncoding tint,
        AttributeEncoding atlasUv,
        AttributeEncoding normals
    ) {
        public AttributeContract {
            Objects.requireNonNull(light, "light");
            Objects.requireNonNull(
                ambientOcclusion,
                "ambientOcclusion"
            );
            Objects.requireNonNull(tint, "tint");
            Objects.requireNonNull(atlasUv, "atlasUv");
            Objects.requireNonNull(normals, "normals");
        }

        public static AttributeContract blockPayloadV2() {
            return new AttributeContract(
                AttributeEncoding.EXPLICIT,
                AttributeEncoding.BAKED_IN_COLOR,
                AttributeEncoding.BAKED_IN_COLOR,
                AttributeEncoding.EXPLICIT,
                AttributeEncoding.EXPLICIT
            );
        }

        public boolean complete() {
            return this.light != AttributeEncoding.NOT_AVAILABLE
                && this.ambientOcclusion
                    != AttributeEncoding.NOT_AVAILABLE
                && this.tint != AttributeEncoding.NOT_AVAILABLE
                && this.atlasUv != AttributeEncoding.NOT_AVAILABLE
                && this.normals != AttributeEncoding.NOT_AVAILABLE;
        }
    }

    /**
     * One census identity. Optional ABI fields are permitted only for a typed
     * unsupported entry; supported entries must attest every field.
     */
    public record Entry(
        StableId assetId,
        StableId blockStateOrModelId,
        StableId renderTypeId,
        Category category,
        Provenance provenance,
        Optional<VertexLayout> vertexLayout,
        Optional<IndexType> indexType,
        Optional<MaterialBinding> material,
        Optional<ShaderContract> shader,
        AttributeContract attributes,
        boolean customRenderType,
        boolean requiredForActiveProfile,
        boolean compilerSupported,
        boolean submissionCompatible,
        Digest contractDigest,
        String unavailableReason
    ) {
        public Entry {
            Objects.requireNonNull(assetId, "assetId")
                .requirePresent("assetId");
            Objects.requireNonNull(
                blockStateOrModelId,
                "blockStateOrModelId"
            );
            Objects.requireNonNull(renderTypeId, "renderTypeId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(provenance, "provenance");
            vertexLayout = copyOptional(vertexLayout, "vertexLayout");
            indexType = copyOptional(indexType, "indexType");
            material = copyOptional(material, "material");
            shader = copyOptional(shader, "shader");
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(contractDigest, "contractDigest")
                .requireKnown("assetContractDigest");
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            ).strip();

            boolean completeContract = vertexLayout.isPresent()
                && indexType.isPresent()
                && material.isPresent()
                && shader.isPresent()
                && attributes.complete()
                && blockStateOrModelId.present()
                && renderTypeId.present();
            if (
                category != Category.UNSUPPORTED
                    && !completeContract
            ) {
                throw new IllegalArgumentException(
                    "known category requires a complete asset contract"
                );
            }
            if (
                category == Category.UNSUPPORTED
                    && unavailableReason.isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "unsupported asset requires a typed reason"
                );
            }
            if (
                (compilerSupported || submissionCompatible)
                    && !completeContract
            ) {
                throw new IllegalArgumentException(
                    "supported asset contract is incomplete"
                );
            }
            if (submissionCompatible && !compilerSupported) {
                throw new IllegalArgumentException(
                    "submission requires compiler support"
                );
            }
        }

        public static Entry supported(
            StableId assetId,
            StableId blockStateOrModelId,
            StableId renderTypeId,
            Category category,
            Provenance provenance,
            VertexLayout vertexLayout,
            IndexType indexType,
            MaterialBinding material,
            ShaderContract shader,
            AttributeContract attributes,
            boolean customRenderType,
            boolean requiredForActiveProfile,
            boolean submissionCompatible,
            Digest contractDigest
        ) {
            if (category == Category.UNSUPPORTED) {
                throw new IllegalArgumentException(
                    "use unsupported() for an unsupported category"
                );
            }
            return new Entry(
                assetId,
                blockStateOrModelId,
                renderTypeId,
                category,
                provenance,
                Optional.of(Objects.requireNonNull(vertexLayout)),
                Optional.of(Objects.requireNonNull(indexType)),
                Optional.of(Objects.requireNonNull(material)),
                Optional.of(Objects.requireNonNull(shader)),
                attributes,
                customRenderType,
                requiredForActiveProfile,
                true,
                submissionCompatible,
                contractDigest,
                ""
            );
        }

        public static Entry unsupported(
            StableId assetId,
            StableId blockStateOrModelId,
            StableId renderTypeId,
            Provenance provenance,
            boolean customRenderType,
            boolean requiredForActiveProfile,
            Digest contractDigest,
            String unavailableReason
        ) {
            return new Entry(
                assetId,
                blockStateOrModelId,
                renderTypeId,
                Category.UNSUPPORTED,
                provenance,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new AttributeContract(
                    AttributeEncoding.NOT_AVAILABLE,
                    AttributeEncoding.NOT_AVAILABLE,
                    AttributeEncoding.NOT_AVAILABLE,
                    AttributeEncoding.NOT_AVAILABLE,
                    AttributeEncoding.NOT_AVAILABLE
                ),
                customRenderType,
                requiredForActiveProfile,
                false,
                false,
                contractDigest,
                unavailableReason
            );
        }

        public boolean hasCompleteContract() {
            return this.vertexLayout.isPresent()
                && this.indexType.isPresent()
                && this.material.isPresent()
                && this.shader.isPresent()
                && this.attributes.complete()
                && this.blockStateOrModelId.present()
                && this.renderTypeId.present();
        }

        private static <T> Optional<T> copyOptional(
            Optional<T> value,
            String name
        ) {
            return Objects.requireNonNull(value, name).map(
                entry -> Objects.requireNonNull(entry, name + " entry")
            );
        }
    }

    public record Blocker(
        BlockerReason reason,
        StableId assetId,
        Category category
    ) {
        public Blocker {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(category, "category");
        }
    }

    /**
     * Immutable backend-selection input. The digest binds the complete entry
     * order and all contract fields to one resource generation.
     */
    public static final class Result {
        private final long resourceGeneration;
        private final boolean complete;
        private final Digest digest;
        private final List<Entry> entries;
        private final Map<StableId, Entry> entriesById;

        private Result(
            long resourceGeneration,
            boolean complete,
            Collection<Entry> observedEntries
        ) {
            requirePositive(resourceGeneration, "resourceGeneration");
            this.resourceGeneration = resourceGeneration;
            this.complete = complete;
            List<Entry> copied = new ArrayList<>(
                Objects.requireNonNull(
                    observedEntries,
                    "observedEntries"
                )
            );
            copied.sort(
                Comparator
                    .comparingLong(
                        (Entry entry) -> entry.assetId().high()
                    )
                    .thenComparingLong(
                        entry -> entry.assetId().low()
                    )
            );
            LinkedHashMap<StableId, Entry> byId =
                new LinkedHashMap<>();
            for (Entry entry : copied) {
                Objects.requireNonNull(entry, "census entry");
                if (byId.putIfAbsent(entry.assetId(), entry) != null) {
                    throw new IllegalArgumentException(
                        "duplicate census asset " + entry.assetId()
                    );
                }
            }
            this.entries = List.copyOf(copied);
            this.entriesById = Collections.unmodifiableMap(byId);
            this.digest = computeDigest(
                resourceGeneration,
                complete,
                this.entries
            );
        }

        public long resourceGeneration() {
            return this.resourceGeneration;
        }

        public boolean complete() {
            return this.complete;
        }

        public Digest digest() {
            return this.digest;
        }

        public List<Entry> entries() {
            return this.entries;
        }

        public Optional<Entry> entry(StableId assetId) {
            return Optional.ofNullable(
                this.entriesById.get(
                    Objects.requireNonNull(assetId, "assetId")
                )
            );
        }

        /**
         * Fails the whole native backend for any required unsupported entry.
         * The caller supplies the categories whose full native submission
         * lanes actually exist for this device generation.
         */
        public ActivationAttestation attest(
            Set<Category> submissionSupportedCategories
        ) {
            Objects.requireNonNull(
                submissionSupportedCategories,
                "submissionSupportedCategories"
            );
            EnumSet<Category> supported =
                submissionSupportedCategories.isEmpty()
                    ? EnumSet.noneOf(Category.class)
                    : EnumSet.copyOf(submissionSupportedCategories);
            List<Blocker> blockers = new ArrayList<>();
            if (!this.complete) {
                blockers.add(
                    new Blocker(
                        BlockerReason.CENSUS_INCOMPLETE,
                        new StableId(0L, 0L),
                        Category.UNSUPPORTED
                    )
                );
            }
            int required = 0;
            for (Entry entry : this.entries) {
                if (!entry.requiredForActiveProfile()) {
                    continue;
                }
                required++;
                BlockerReason blocker = blockerFor(entry, supported);
                if (blocker != null) {
                    blockers.add(
                        new Blocker(
                            blocker,
                            entry.assetId(),
                            entry.category()
                        )
                    );
                }
            }
            return new ActivationAttestation(
                this.resourceGeneration,
                this.digest,
                this.complete,
                required,
                blockers.isEmpty(),
                List.copyOf(blockers)
            );
        }
    }

    public record ActivationAttestation(
        long resourceGeneration,
        Digest censusDigest,
        boolean censusComplete,
        int requiredAssetCount,
        boolean nativeBackendEligible,
        List<Blocker> blockers
    ) {
        public ActivationAttestation {
            requirePositive(resourceGeneration, "resourceGeneration");
            Objects.requireNonNull(censusDigest, "censusDigest")
                .requireKnown("censusDigest");
            if (requiredAssetCount < 0) {
                throw new IllegalArgumentException(
                    "requiredAssetCount must not be negative"
                );
            }
            blockers = List.copyOf(
                Objects.requireNonNull(blockers, "blockers")
            );
            if (nativeBackendEligible != blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "eligibility and blockers disagree"
                );
            }
            if (nativeBackendEligible && !censusComplete) {
                throw new IllegalArgumentException(
                    "an incomplete census cannot activate native terrain"
                );
            }
        }
    }

    private NativeTerrainAssetCensus() {
    }

    public static Result capture(
        long resourceGeneration,
        boolean complete,
        Collection<Entry> observedEntries
    ) {
        return new Result(
            resourceGeneration,
            complete,
            observedEntries
        );
    }

    private static BlockerReason blockerFor(
        Entry entry,
        Set<Category> supported
    ) {
        if (
            entry.category() == Category.UNSUPPORTED
                || !entry.hasCompleteContract()
        ) {
            return BlockerReason.UNSUPPORTED_ASSET;
        }
        if (entry.provenance() == Provenance.UNKNOWN) {
            return BlockerReason.UNKNOWN_PROVENANCE;
        }
        if (
            entry.customRenderType()
                || entry.provenance() == Provenance.CUSTOM
        ) {
            return BlockerReason.CUSTOM_RENDER_TYPE;
        }
        if (!entry.compilerSupported()) {
            return BlockerReason.COMPILER_UNSUPPORTED;
        }
        if (!entry.submissionCompatible()) {
            return BlockerReason.SUBMISSION_UNSUPPORTED;
        }
        if (!supported.contains(entry.category())) {
            return BlockerReason.CATEGORY_NOT_SUPPORTED;
        }
        return null;
    }

    private static Digest computeDigest(
        long generation,
        boolean complete,
        List<Entry> entries
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLong(digest, generation);
            digest.update((byte)(complete ? 1 : 0));
            updateInt(digest, entries.size());
            for (Entry entry : entries) {
                updateEntry(digest, entry);
            }
            byte[] value = digest.digest();
            return new Digest(
                readLong(value, 0),
                readLong(value, 8),
                readLong(value, 16),
                readLong(value, 24)
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                "SHA-256 is unavailable",
                error
            );
        }
    }

    private static void updateEntry(
        MessageDigest digest,
        Entry entry
    ) {
        updateStableId(digest, entry.assetId());
        updateStableId(digest, entry.blockStateOrModelId());
        updateStableId(digest, entry.renderTypeId());
        updateString(digest, entry.category().name());
        updateString(digest, entry.provenance().name());
        updateOptionalVertex(digest, entry.vertexLayout());
        updateOptionalIndex(digest, entry.indexType());
        updateOptionalMaterial(digest, entry.material());
        updateOptionalShader(digest, entry.shader());
        updateString(digest, entry.attributes().light().name());
        updateString(
            digest,
            entry.attributes().ambientOcclusion().name()
        );
        updateString(digest, entry.attributes().tint().name());
        updateString(digest, entry.attributes().atlasUv().name());
        updateString(digest, entry.attributes().normals().name());
        digest.update((byte)(entry.customRenderType() ? 1 : 0));
        digest.update(
            (byte)(entry.requiredForActiveProfile() ? 1 : 0)
        );
        digest.update((byte)(entry.compilerSupported() ? 1 : 0));
        digest.update((byte)(entry.submissionCompatible() ? 1 : 0));
        updateDigest(digest, entry.contractDigest());
        updateString(digest, entry.unavailableReason());
    }

    private static void updateOptionalVertex(
        MessageDigest digest,
        Optional<VertexLayout> layout
    ) {
        digest.update((byte)(layout.isPresent() ? 1 : 0));
        layout.ifPresent(value -> {
            updateStableId(digest, value.stableId());
            updateInt(digest, value.strideBytes());
            updateLong(digest, value.semanticMask());
        });
    }

    private static void updateOptionalIndex(
        MessageDigest digest,
        Optional<IndexType> type
    ) {
        digest.update((byte)(type.isPresent() ? 1 : 0));
        type.ifPresent(value -> updateString(digest, value.name()));
    }

    private static void updateOptionalMaterial(
        MessageDigest digest,
        Optional<MaterialBinding> material
    ) {
        digest.update((byte)(material.isPresent() ? 1 : 0));
        material.ifPresent(value -> {
            updateLong(digest, value.registryGeneration());
            updateStableId(digest, value.materialFamilyId());
            updateStableId(digest, value.textureId());
            updateStableId(digest, value.samplerId());
            updateStableId(digest, value.layerId());
            updateStableId(digest, value.animationTableId());
            updateStableId(digest, value.pbrContractId());
            updateString(digest, value.alphaMode().name());
            updateInt(digest, value.alphaCutoffBits());
        });
    }

    private static void updateOptionalShader(
        MessageDigest digest,
        Optional<ShaderContract> shader
    ) {
        digest.update((byte)(shader.isPresent() ? 1 : 0));
        shader.ifPresent(value -> {
            updateDigest(digest, value.abiDigest());
            updateLong(digest, value.outputMask());
            updateString(digest, value.motionModel().name());
        });
    }

    private static void updateStableId(
        MessageDigest digest,
        StableId id
    ) {
        updateLong(digest, id.high());
        updateLong(digest, id.low());
    }

    private static void updateDigest(
        MessageDigest target,
        Digest value
    ) {
        updateLong(target, value.part0());
        updateLong(target, value.part1());
        updateLong(target, value.part2());
        updateLong(target, value.part3());
    }

    private static void updateString(
        MessageDigest digest,
        String value
    ) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte)(value >>> 24));
        digest.update((byte)(value >>> 16));
        digest.update((byte)(value >>> 8));
        digest.update((byte)value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update((byte)(value >>> 56));
        digest.update((byte)(value >>> 48));
        digest.update((byte)(value >>> 40));
        digest.update((byte)(value >>> 32));
        digest.update((byte)(value >>> 24));
        digest.update((byte)(value >>> 16));
        digest.update((byte)(value >>> 8));
        digest.update((byte)value);
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(
                name + " must be positive"
            );
        }
    }
}
