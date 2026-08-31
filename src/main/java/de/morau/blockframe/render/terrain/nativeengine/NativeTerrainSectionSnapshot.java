package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Provenance;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, generation-bound input owned by the native backend compiler.
 *
 * <p>The snapshot contains source vertices and census identities, never a
 * Minecraft MeshData, builder or borrowed upload buffer. All collection state
 * is copied at construction.</p>
 */
public final class NativeTerrainSectionSnapshot {
    public record Vertex(
        float x,
        float y,
        float z,
        int color,
        float atlasU,
        float atlasV,
        int packedLight,
        int packedNormal
    ) {
        public Vertex {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(atlasU, "atlasU");
            requireFinite(atlasV, "atlasV");
            if ((packedNormal & 0x00FFFFFF) == 0) {
                throw new IllegalArgumentException(
                    "packed vertex normal must be resolved and non-zero"
                );
            }
            if ((packedNormal & 0xFF000000) != 0) {
                throw new IllegalArgumentException(
                    "packed vertex normal has non-zero reserved bits"
                );
            }
        }
    }

    /**
     * One pre-merge primitive. Static asset fields are copied from the census
     * entry so the compiler can detect a stale or substituted census.
     */
    public static final class Primitive {
        private final long primitiveId;
        private final StableId assetId;
        private final Digest assetContractDigest;
        private final Category category;
        private final Provenance provenance;
        private final StableId blockStateOrModelId;
        private final StableId renderTypeId;
        private final Bounds bounds;
        private final Digest geometryDigest;
        private final List<Vertex> vertices;

        public Primitive(
            long primitiveId,
            Entry asset,
            Bounds bounds,
            Digest geometryDigest,
            Collection<Vertex> vertices
        ) {
            requirePositive(primitiveId, "primitiveId");
            Objects.requireNonNull(asset, "asset");
            this.primitiveId = primitiveId;
            this.assetId = asset.assetId();
            this.assetContractDigest = asset.contractDigest();
            this.category = asset.category();
            this.provenance = asset.provenance();
            this.blockStateOrModelId = asset.blockStateOrModelId();
            this.renderTypeId = asset.renderTypeId();
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            this.geometryDigest = Objects.requireNonNull(
                geometryDigest,
                "geometryDigest"
            );
            this.geometryDigest.requireKnown("geometryDigest");
            this.vertices = List.copyOf(
                Objects.requireNonNull(vertices, "vertices")
            );
            if (this.vertices.isEmpty()) {
                throw new IllegalArgumentException(
                    "primitive has no source vertices"
                );
            }
            for (Vertex vertex : this.vertices) {
                Objects.requireNonNull(vertex, "vertex");
                if (!contains(this.bounds, vertex)) {
                    throw new IllegalArgumentException(
                        "vertex lies outside primitive bounds"
                    );
                }
            }
        }

        public long primitiveId() {
            return this.primitiveId;
        }

        public StableId assetId() {
            return this.assetId;
        }

        public Digest assetContractDigest() {
            return this.assetContractDigest;
        }

        public Category category() {
            return this.category;
        }

        public Provenance provenance() {
            return this.provenance;
        }

        public StableId blockStateOrModelId() {
            return this.blockStateOrModelId;
        }

        public StableId renderTypeId() {
            return this.renderTypeId;
        }

        public Bounds bounds() {
            return this.bounds;
        }

        public Digest geometryDigest() {
            return this.geometryDigest;
        }

        public List<Vertex> vertices() {
            return this.vertices;
        }
    }

    private final GenerationStamp generations;
    private final SectionIdentity section;
    private final Digest censusDigest;
    private final List<Primitive> primitives;

    public NativeTerrainSectionSnapshot(
        GenerationStamp generations,
        SectionIdentity section,
        Digest censusDigest,
        Collection<Primitive> primitives
    ) {
        this.generations = Objects.requireNonNull(
            generations,
            "generations"
        );
        this.section = Objects.requireNonNull(section, "section");
        this.censusDigest = Objects.requireNonNull(
            censusDigest,
            "censusDigest"
        );
        this.censusDigest.requireKnown("censusDigest");
        this.primitives = List.copyOf(
            Objects.requireNonNull(primitives, "primitives")
        );
        Set<Long> identities = new HashSet<>();
        for (Primitive primitive : this.primitives) {
            Objects.requireNonNull(primitive, "primitive");
            if (!identities.add(primitive.primitiveId())) {
                throw new IllegalArgumentException(
                    "duplicate primitive identity "
                        + primitive.primitiveId()
                );
            }
        }
    }

    public GenerationStamp generations() {
        return this.generations;
    }

    public SectionIdentity section() {
        return this.section;
    }

    public Digest censusDigest() {
        return this.censusDigest;
    }

    public List<Primitive> primitives() {
        return this.primitives;
    }

    private static boolean contains(Bounds bounds, Vertex vertex) {
        return vertex.x() >= bounds.minimumX()
            && vertex.x() <= bounds.maximumX()
            && vertex.y() >= bounds.minimumY()
            && vertex.y() <= bounds.maximumY()
            && vertex.z() >= bounds.minimumZ()
            && vertex.z() <= bounds.maximumZ();
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(
                name + " must be finite"
            );
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
