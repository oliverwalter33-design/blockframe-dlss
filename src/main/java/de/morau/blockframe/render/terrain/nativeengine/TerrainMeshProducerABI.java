package de.morau.blockframe.render.terrain.nativeengine;

import java.util.Objects;

/**
 * Permanent, backend-neutral terrain mesh contract shared by every producer.
 *
 * <p>The first producer is {@link BlockFrameSectionCompiler}, operating on a
 * pre-merge immutable section snapshot. Later parallel CPU or experimental
 * GPU mesh producers must publish this same descriptor; the renderer is never
 * allowed to branch on producer implementation. This contract owns no
 * Minecraft object, Vulkan handle or borrowed payload lifetime.</p>
 */
public final class TerrainMeshProducerABI {
    public static final int VERSION = 2;
    public static final int MOJANG_BLOCK_STRIDE_BYTES = 28;
    public static final int BLOCK_PAYLOAD_V2_STRIDE_BYTES = 32;
    public static final int MOJANG_CUTOUT_ALPHA_CUTOFF_BITS =
        Float.floatToRawIntBits(0.5F);

    public static final long SEMANTIC_POSITION = 1L;
    public static final long SEMANTIC_COLOR = 1L << 1;
    public static final long SEMANTIC_ATLAS_UV = 1L << 2;
    public static final long SEMANTIC_LIGHT_UV = 1L << 3;
    public static final long SEMANTIC_AO_BAKED_IN_COLOR = 1L << 4;
    public static final long SEMANTIC_TINT_BAKED_IN_COLOR = 1L << 5;
    public static final long SEMANTIC_NORMAL_EXPLICIT = 1L << 6;
    public static final long SEMANTIC_NORMAL_FROM_GEOMETRY = 1L << 7;

    public static final long REQUIRED_BLOCK_SEMANTICS =
        SEMANTIC_POSITION
            | SEMANTIC_COLOR
            | SEMANTIC_ATLAS_UV
            | SEMANTIC_LIGHT_UV
            | SEMANTIC_AO_BAKED_IN_COLOR
            | SEMANTIC_TINT_BAKED_IN_COLOR;
    public static final long NORMAL_SEMANTICS =
        SEMANTIC_NORMAL_EXPLICIT | SEMANTIC_NORMAL_FROM_GEOMETRY;

    public static final long CONTENT_BLOCK_GEOMETRY = 1L;
    public static final long CONTENT_FLUID_GEOMETRY = 1L << 1;
    public static final long CONTENT_ADDITIONAL_GEOMETRY = 1L << 2;
    public static final long CONTENT_UNKNOWN_PROVENANCE = 1L << 3;
    public static final long ALL_KNOWN_CONTENT =
        CONTENT_BLOCK_GEOMETRY
            | CONTENT_FLUID_GEOMETRY
            | CONTENT_ADDITIONAL_GEOMETRY
            | CONTENT_UNKNOWN_PROVENANCE;
    public static final long FIRST_MILESTONE_FORBIDDEN_CONTENT =
        CONTENT_FLUID_GEOMETRY
            | CONTENT_ADDITIONAL_GEOMETRY
            | CONTENT_UNKNOWN_PROVENANCE;

    public static final long OUTPUT_COLOR = 1L;
    public static final long OUTPUT_DEPTH = 1L << 1;
    public static final long OUTPUT_MOTION = 1L << 2;
    public static final long OUTPUT_NORMAL = 1L << 3;
    public static final long OUTPUT_MATERIAL = 1L << 4;
    public static final long OUTPUT_EXPOSURE_JITTER = 1L << 5;
    public static final long OUTPUT_GENERATION_RESET = 1L << 6;
    public static final long REQUIRED_NATIVE_OUTPUTS =
        OUTPUT_COLOR
            | OUTPUT_DEPTH
            | OUTPUT_MOTION
            | OUTPUT_NORMAL
            | OUTPUT_MATERIAL
            | OUTPUT_EXPOSURE_JITTER
            | OUTPUT_GENERATION_RESET;

    private TerrainMeshProducerABI() {
    }

    public enum Layer {
        SOLID(true, true),
        CUTOUT(true, false),
        TRANSLUCENT(false, false),
        FLUID(false, false);

        private final boolean firstMilestone;
        private final boolean safeHzbOccluder;

        Layer(boolean firstMilestone, boolean safeHzbOccluder) {
            this.firstMilestone = firstMilestone;
            this.safeHzbOccluder = safeHzbOccluder;
        }

        public boolean firstMilestone() {
            return this.firstMilestone;
        }

        public boolean safeHzbOccluder() {
            return this.safeHzbOccluder;
        }
    }

    public enum AlphaMode {
        OPAQUE,
        MASKED,
        BLENDED
    }

    public enum IndexMode {
        CUSTOM_PAYLOAD,
        SHARED_SEQUENTIAL_QUADS
    }

    public enum IndexType {
        UINT16(2),
        UINT32(4);

        private final int bytes;

        IndexType(int bytes) {
            this.bytes = bytes;
        }

        public int bytes() {
            return this.bytes;
        }
    }

    public enum MotionModel {
        STATIC_WORLD_WITH_CAMERA_HISTORY,
        EXPLICIT_PREVIOUS_TRANSFORM
    }

    /** Stable 128-bit identifier. All-zero is reserved for "not present". */
    public record StableId(long high, long low) {
        public boolean present() {
            return this.high != 0L || this.low != 0L;
        }

        public void requirePresent(String name) {
            if (!this.present()) {
                throw new IllegalArgumentException(name + " is unknown");
            }
        }
    }

    /** Stable content/ABI identity. All-zero is never a publishable digest. */
    public record Digest(
        long part0,
        long part1,
        long part2,
        long part3
    ) {
        public boolean known() {
            return this.part0 != 0L
                || this.part1 != 0L
                || this.part2 != 0L
                || this.part3 != 0L;
        }

        public void requireKnown(String name) {
            if (!this.known()) {
                throw new IllegalArgumentException(name + " is unknown");
            }
        }
    }

    public record ProducerIdentity(
        StableId stableId,
        int contractRevision
    ) {
        public ProducerIdentity {
            Objects.requireNonNull(stableId, "stableId")
                .requirePresent("producerId");
            requirePositive(contractRevision, "contractRevision");
        }
    }

    /**
     * Exact generations that make a descriptor valid. Section objects may be
     * reused, so sectionNode is an identity input rather than a generation.
     */
    public record GenerationStamp(
        long device,
        long renderer,
        long world,
        long resources,
        long producer,
        long sectionMesh
    ) {
        public GenerationStamp {
            requirePositive(device, "deviceGeneration");
            requirePositive(renderer, "rendererGeneration");
            requirePositive(world, "worldGeneration");
            requirePositive(resources, "resourceGeneration");
            requirePositive(producer, "producerGeneration");
            requirePositive(sectionMesh, "sectionMeshGeneration");
        }
    }

    public record SectionIdentity(
        StableId worldIdentity,
        long sectionNode
    ) {
        public SectionIdentity {
            Objects.requireNonNull(worldIdentity, "worldIdentity")
                .requirePresent("worldIdentity");
        }
    }

    /**
     * Vertex semantics are representation-aware. ABI V2 retains Mojang's
     * 28-byte block fields and appends NeoForge's exact packed SNORM8x3 normal
     * in a four-byte lane. AO/tint remain baked into color. An unspecified
     * baked normal is resolved through BakedQuad.direction by the Minecraft
     * adapter before it reaches this backend-neutral contract.
     */
    public record VertexLayout(
        StableId stableId,
        int strideBytes,
        long semanticMask
    ) {
        public VertexLayout {
            Objects.requireNonNull(stableId, "stableId")
                .requirePresent("vertexLayoutId");
            requirePositive(strideBytes, "vertexStrideBytes");
            if (
                (semanticMask & REQUIRED_BLOCK_SEMANTICS)
                    != REQUIRED_BLOCK_SEMANTICS
            ) {
                throw new IllegalArgumentException(
                    "light, AO, tint, color, position and UV semantics "
                        + "are required"
                );
            }
            if ((semanticMask & NORMAL_SEMANTICS) == 0L) {
                throw new IllegalArgumentException(
                    "an explicit or geometrically derived normal is required"
                );
            }
        }

        public static VertexLayout blockPayloadV2(
            StableId stableId
        ) {
            return new VertexLayout(
                stableId,
                BLOCK_PAYLOAD_V2_STRIDE_BYTES,
                REQUIRED_BLOCK_SEMANTICS | SEMANTIC_NORMAL_EXPLICIT
            );
        }
    }

    public record PayloadRange(long byteOffset, long byteLength) {
        public PayloadRange {
            if (
                byteOffset < 0L
                    || byteLength <= 0L
                    || byteOffset > Long.MAX_VALUE - byteLength
            ) {
                throw new IllegalArgumentException(
                    "invalid payload byte range"
                );
            }
        }

        public long endExclusive() {
            return this.byteOffset + this.byteLength;
        }
    }

    public record IndexLayout(
        IndexMode mode,
        IndexType type,
        long payloadByteOffset,
        long payloadByteLength,
        int firstIndex,
        int indexCount,
        int baseVertex
    ) {
        public IndexLayout {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(type, "type");
            if (
                firstIndex < 0
                    || indexCount <= 0
                    || baseVertex < 0
            ) {
                throw new IllegalArgumentException(
                    "invalid indexed draw range"
                );
            }
            long referencedIndexCount;
            try {
                referencedIndexCount = Math.addExact(
                    firstIndex,
                    indexCount
                );
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                    "index range overflows",
                    error
                );
            }
            long requiredBytes = multiplyExact(
                referencedIndexCount,
                type.bytes(),
                "index payload"
            );
            if (mode == IndexMode.CUSTOM_PAYLOAD) {
                if (
                    payloadByteOffset < 0L
                        || payloadByteLength < requiredBytes
                        || payloadByteOffset
                            > Long.MAX_VALUE - payloadByteLength
                ) {
                    throw new IllegalArgumentException(
                        "invalid custom index payload"
                    );
                }
            } else if (
                payloadByteOffset != 0L
                    || payloadByteLength != 0L
                    || firstIndex != 0
                    || indexCount % 6 != 0
            ) {
                throw new IllegalArgumentException(
                    "shared quad indices have no producer payload"
                );
            }
        }

        public static IndexLayout sequentialQuads(
            IndexType type,
            int indexCount,
            int baseVertex
        ) {
            return new IndexLayout(
                IndexMode.SHARED_SEQUENTIAL_QUADS,
                type,
                0L,
                0L,
                0,
                indexCount,
                baseVertex
            );
        }
    }

    public record Bounds(
        float minimumX,
        float minimumY,
        float minimumZ,
        float maximumX,
        float maximumY,
        float maximumZ
    ) {
        public Bounds {
            requireFinite(minimumX, "minimumX");
            requireFinite(minimumY, "minimumY");
            requireFinite(minimumZ, "minimumZ");
            requireFinite(maximumX, "maximumX");
            requireFinite(maximumY, "maximumY");
            requireFinite(maximumZ, "maximumZ");
            if (
                minimumX > maximumX
                    || minimumY > maximumY
                    || minimumZ > maximumZ
            ) {
                throw new IllegalArgumentException("unordered bounds");
            }
        }
    }

    /**
     * Producer-side provenance collected before Minecraft merges contributors
     * into one layer payload. The final ByteBuffer alone cannot reconstruct
     * this information.
     */
    public record ContentProvenance(
        long contentMask,
        Digest auditDigest,
        boolean complete
    ) {
        public ContentProvenance {
            if (contentMask == 0L) {
                throw new IllegalArgumentException(
                    "content provenance is empty"
                );
            }
            if ((contentMask & ~ALL_KNOWN_CONTENT) != 0L) {
                throw new IllegalArgumentException(
                    "content provenance has unknown ABI bits"
                );
            }
            Objects.requireNonNull(auditDigest, "auditDigest")
                .requireKnown("contentProvenanceDigest");
        }
    }

    /**
     * Stable bucket identities. A producer may identify the active global
     * block atlas and exact layer family only from its pre-merge asset
     * contract; it may not manufacture per-quad texture or PBR identities.
     */
    public record MaterialBinding(
        long registryGeneration,
        StableId materialFamilyId,
        StableId textureId,
        StableId samplerId,
        StableId layerId,
        StableId animationTableId,
        StableId pbrContractId,
        AlphaMode alphaMode,
        int alphaCutoffBits
    ) {
        public MaterialBinding {
            requirePositive(
                registryGeneration,
                "materialRegistryGeneration"
            );
            Objects.requireNonNull(materialFamilyId, "materialFamilyId")
                .requirePresent("materialFamilyId");
            Objects.requireNonNull(textureId, "textureId")
                .requirePresent("textureId");
            Objects.requireNonNull(samplerId, "samplerId")
                .requirePresent("samplerId");
            Objects.requireNonNull(layerId, "layerId")
                .requirePresent("layerId");
            Objects.requireNonNull(animationTableId, "animationTableId");
            Objects.requireNonNull(pbrContractId, "pbrContractId");
            Objects.requireNonNull(alphaMode, "alphaMode");
            float cutoff = Float.intBitsToFloat(alphaCutoffBits);
            if (!Float.isFinite(cutoff) || cutoff < 0.0F || cutoff > 1.0F) {
                throw new IllegalArgumentException(
                    "invalid alpha cutoff"
                );
            }
            if (
                alphaMode == AlphaMode.OPAQUE
                    && alphaCutoffBits
                        != Float.floatToRawIntBits(0.0F)
            ) {
                throw new IllegalArgumentException(
                    "opaque material must not use an alpha cutoff"
                );
            }
            if (alphaMode == AlphaMode.MASKED && cutoff <= 0.0F) {
                throw new IllegalArgumentException(
                    "masked material requires an alpha cutoff"
                );
            }
        }
    }

    public record ShaderContract(
        Digest abiDigest,
        long outputMask,
        MotionModel motionModel
    ) {
        public ShaderContract {
            Objects.requireNonNull(abiDigest, "abiDigest")
                .requireKnown("shaderAbi");
            Objects.requireNonNull(motionModel, "motionModel");
            if (
                (outputMask & REQUIRED_NATIVE_OUTPUTS)
                    != REQUIRED_NATIVE_OUTPUTS
            ) {
                throw new IllegalArgumentException(
                    "native terrain outputs are incomplete"
                );
            }
        }
    }

    public record RetirementToken(
        long deviceGeneration,
        long rendererGeneration,
        long worldGeneration,
        long resourceGeneration,
        long producerGeneration,
        long sectionMeshGeneration,
        long serial
    ) {
        public RetirementToken {
            requirePositive(deviceGeneration, "retirementDeviceGeneration");
            requirePositive(
                rendererGeneration,
                "retirementRendererGeneration"
            );
            requirePositive(worldGeneration, "retirementWorldGeneration");
            requirePositive(
                resourceGeneration,
                "retirementResourceGeneration"
            );
            requirePositive(
                producerGeneration,
                "retirementProducerGeneration"
            );
            requirePositive(
                sectionMeshGeneration,
                "retirementSectionMeshGeneration"
            );
            requirePositive(serial, "retirementSerial");
        }
    }

    /**
     * Instancing is permitted only for an exact geometry identity. Geometry
     * digests include baked light, AO, tint, UV and shader-visible data.
     */
    public record InstancingContract(
        Digest exactGeometryIdentity,
        StableId transformLayoutId,
        long transformGeneration,
        int instanceCount
    ) {
        public InstancingContract {
            Objects.requireNonNull(
                exactGeometryIdentity,
                "exactGeometryIdentity"
            ).requireKnown("exactGeometryIdentity");
            Objects.requireNonNull(transformLayoutId, "transformLayoutId")
                .requirePresent("transformLayoutId");
            requirePositive(transformGeneration, "transformGeneration");
            requirePositive(instanceCount, "instanceCount");
        }
    }

    /**
     * Dirty-path proof minted only by the exact source, capability, shader and
     * material registries. Structural descriptor validity alone can never
     * authorize upload suppression.
     */
    record CompatibilityProof(
        int abiVersion,
        GenerationStamp generations,
        Digest sourceContractDigest,
        StableId hookContractId,
        ProducerIdentity producer,
        Layer layer,
        SectionIdentity section,
        StableId vertexLayoutId,
        PayloadRange vertexPayload,
        int vertexCount,
        IndexLayout indexLayout,
        Digest shaderAbiDigest,
        long materialRegistryGeneration,
        StableId materialFamilyId,
        StableId textureId,
        StableId samplerId,
        StableId layerId,
        StableId animationTableId,
        StableId pbrContractId,
        int alphaCutoffBits,
        long shaderOutputMask,
        MotionModel motionModel,
        long contentMask,
        Digest contentProvenanceDigest,
        Bounds bounds,
        Digest geometryDigest,
        InstancingContract instancing,
        long retirementSerial,
        long capabilityDeviceGeneration,
        boolean sourceContractValidated,
        boolean hookHealthy,
        boolean publicationProtocolValidated,
        boolean shaderAbiAllowlisted,
        boolean materialSemanticsValidated,
        boolean capabilitiesValidated
    ) {
        public CompatibilityProof {
            if (abiVersion != VERSION) {
                throw new IllegalArgumentException(
                    "compatibility proof ABI mismatch"
                );
            }
            Objects.requireNonNull(generations, "generations");
            Objects.requireNonNull(
                sourceContractDigest,
                "sourceContractDigest"
            ).requireKnown("sourceContractDigest");
            Objects.requireNonNull(hookContractId, "hookContractId")
                .requirePresent("hookContractId");
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(vertexLayoutId, "vertexLayoutId")
                .requirePresent("vertexLayoutId");
            Objects.requireNonNull(vertexPayload, "vertexPayload");
            requirePositive(vertexCount, "proofVertexCount");
            Objects.requireNonNull(indexLayout, "indexLayout");
            Objects.requireNonNull(shaderAbiDigest, "shaderAbiDigest")
                .requireKnown("shaderAbiDigest");
            requirePositive(
                materialRegistryGeneration,
                "materialRegistryGeneration"
            );
            Objects.requireNonNull(
                materialFamilyId,
                "materialFamilyId"
            ).requirePresent("materialFamilyId");
            Objects.requireNonNull(textureId, "textureId")
                .requirePresent("textureId");
            Objects.requireNonNull(samplerId, "samplerId")
                .requirePresent("samplerId");
            Objects.requireNonNull(layerId, "layerId")
                .requirePresent("layerId");
            Objects.requireNonNull(animationTableId, "animationTableId");
            Objects.requireNonNull(pbrContractId, "pbrContractId");
            Objects.requireNonNull(motionModel, "motionModel");
            if (
                (shaderOutputMask & REQUIRED_NATIVE_OUTPUTS)
                    != REQUIRED_NATIVE_OUTPUTS
            ) {
                throw new IllegalArgumentException(
                    "proof shader outputs are incomplete"
                );
            }
            if (contentMask == 0L) {
                throw new IllegalArgumentException(
                    "proof content provenance is empty"
                );
            }
            if ((contentMask & ~ALL_KNOWN_CONTENT) != 0L) {
                throw new IllegalArgumentException(
                    "proof content provenance has unknown ABI bits"
                );
            }
            Objects.requireNonNull(
                contentProvenanceDigest,
                "contentProvenanceDigest"
            ).requireKnown("contentProvenanceDigest");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(geometryDigest, "geometryDigest")
                .requireKnown("geometryDigest");
            Objects.requireNonNull(instancing, "instancing");
            requirePositive(retirementSerial, "retirementSerial");
            requirePositive(
                capabilityDeviceGeneration,
                "capabilityDeviceGeneration"
            );
            if (
                capabilityDeviceGeneration != generations.device()
                    || !sourceContractValidated
                    || !hookHealthy
                    || !publicationProtocolValidated
                    || !shaderAbiAllowlisted
                    || !materialSemanticsValidated
                    || !capabilitiesValidated
            ) {
                throw new IllegalArgumentException(
                    "compatibility proof is incomplete or stale"
                );
            }
        }

        public boolean matches(
            MeshDescriptor descriptor,
            Digest expectedSourceContract,
            StableId expectedHookContract
        ) {
            return descriptor != null
                && this.sourceContractDigest.equals(
                    expectedSourceContract
                )
                && this.hookContractId.equals(expectedHookContract)
                && this.abiVersion == descriptor.abiVersion()
                && this.generations.equals(descriptor.generations())
                && this.producer.equals(descriptor.producer())
                && this.layer == descriptor.layer()
                && this.section.equals(descriptor.section())
                && this.vertexLayoutId.equals(
                    descriptor.vertexLayout().stableId()
                )
                && this.vertexPayload.equals(
                    descriptor.vertexPayload()
                )
                && this.vertexCount == descriptor.vertexCount()
                && this.indexLayout.equals(descriptor.indexLayout())
                && this.shaderAbiDigest.equals(
                    descriptor.shader().abiDigest()
                )
                && this.materialRegistryGeneration
                    == descriptor.material().registryGeneration()
                && this.materialFamilyId.equals(
                    descriptor.material().materialFamilyId()
                )
                && this.textureId.equals(
                    descriptor.material().textureId()
                )
                && this.samplerId.equals(
                    descriptor.material().samplerId()
                )
                && this.layerId.equals(
                    descriptor.material().layerId()
                )
                && this.animationTableId.equals(
                    descriptor.material().animationTableId()
                )
                && this.pbrContractId.equals(
                    descriptor.material().pbrContractId()
                )
                && this.alphaCutoffBits
                    == descriptor.material().alphaCutoffBits()
                && this.shaderOutputMask
                    == descriptor.shader().outputMask()
                && this.motionModel
                    == descriptor.shader().motionModel()
                && this.contentMask
                    == descriptor.provenance().contentMask()
                && this.contentProvenanceDigest.equals(
                    descriptor.provenance().auditDigest()
                )
                && descriptor.provenance().complete()
                && this.bounds.equals(descriptor.bounds())
                && this.geometryDigest.equals(
                    descriptor.geometryDigest()
                )
                && this.instancing.equals(descriptor.instancing())
                && this.retirementSerial
                    == descriptor.retirement().serial();
        }
    }

    public record MeshDescriptor(
        int abiVersion,
        ProducerIdentity producer,
        GenerationStamp generations,
        SectionIdentity section,
        Layer layer,
        VertexLayout vertexLayout,
        PayloadRange vertexPayload,
        int vertexCount,
        IndexLayout indexLayout,
        MaterialBinding material,
        ShaderContract shader,
        Bounds bounds,
        ContentProvenance provenance,
        Digest geometryDigest,
        InstancingContract instancing,
        RetirementToken retirement
    ) {
        public MeshDescriptor {
            if (abiVersion != VERSION) {
                throw new IllegalArgumentException(
                    "unsupported terrain producer ABI " + abiVersion
                );
            }
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(generations, "generations");
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(vertexLayout, "vertexLayout");
            Objects.requireNonNull(vertexPayload, "vertexPayload");
            requirePositive(vertexCount, "vertexCount");
            Objects.requireNonNull(indexLayout, "indexLayout");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(provenance, "provenance");
            Objects.requireNonNull(geometryDigest, "geometryDigest")
                .requireKnown("geometryDigest");
            Objects.requireNonNull(instancing, "instancing");
            Objects.requireNonNull(retirement, "retirement");

            long requiredVertexBytes = multiplyExact(
                vertexCount,
                vertexLayout.strideBytes(),
                "vertex payload"
            );
            if (vertexPayload.byteLength() < requiredVertexBytes) {
                throw new IllegalArgumentException(
                    "vertex payload is shorter than its declared layout"
                );
            }
            if (
                indexLayout.mode()
                    == IndexMode.SHARED_SEQUENTIAL_QUADS
            ) {
                if (
                    vertexCount % 4 != 0
                        || indexLayout.indexCount()
                            != multiplyExactInt(
                                vertexCount / 4,
                                6,
                                "quad index count"
                            )
                ) {
                    throw new IllegalArgumentException(
                        "sequential quad counts are inconsistent"
                    );
                }
                if (
                    indexLayout.type() == IndexType.UINT16
                        && vertexCount > 65_536
                ) {
                    throw new IllegalArgumentException(
                        "UINT16 sequential indices exceed 65535"
                    );
                }
            }
            if (
                retirement.deviceGeneration() != generations.device()
                    || retirement.rendererGeneration()
                        != generations.renderer()
                    || retirement.worldGeneration()
                        != generations.world()
                    || retirement.resourceGeneration()
                        != generations.resources()
                    || retirement.producerGeneration()
                        != generations.producer()
                    || retirement.sectionMeshGeneration()
                        != generations.sectionMesh()
            ) {
                throw new IllegalArgumentException(
                    "retirement token generation mismatch"
                );
            }
            if (
                !geometryDigest.equals(
                    instancing.exactGeometryIdentity()
                )
            ) {
                throw new IllegalArgumentException(
                    "instancing identity must include exact geometry"
                );
            }
            if (
                layer == Layer.SOLID
                    && material.alphaMode() != AlphaMode.OPAQUE
            ) {
                throw new IllegalArgumentException(
                    "solid requires an opaque material"
                );
            }
            if (
                layer == Layer.CUTOUT
                    && (
                        material.alphaMode() != AlphaMode.MASKED
                            || material.alphaCutoffBits()
                                != MOJANG_CUTOUT_ALPHA_CUTOFF_BITS
                    )
            ) {
                throw new IllegalArgumentException(
                    "Stage-A cutout requires the exact known alpha contract"
                );
            }
        }

        public boolean structurallyCompatibleWithFirstMilestone() {
            return this.layer.firstMilestone()
                && this.provenance.complete()
                && (
                    this.provenance.contentMask()
                        & CONTENT_BLOCK_GEOMETRY
                ) != 0L
                && (
                    this.provenance.contentMask()
                        & FIRST_MILESTONE_FORBIDDEN_CONTENT
                ) == 0L
                && this.vertexLayout.strideBytes()
                    == BLOCK_PAYLOAD_V2_STRIDE_BYTES
                && (
                    this.vertexLayout.semanticMask()
                        & REQUIRED_BLOCK_SEMANTICS
                ) == REQUIRED_BLOCK_SEMANTICS
                && (
                    this.vertexLayout.semanticMask()
                        & SEMANTIC_NORMAL_EXPLICIT
                ) != 0L
                && (
                    this.shader.outputMask() & REQUIRED_NATIVE_OUTPUTS
                ) == REQUIRED_NATIVE_OUTPUTS;
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static long multiplyExact(
        long left,
        long right,
        String name
    ) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " overflows", error);
        }
    }

    private static int multiplyExactInt(
        int left,
        int right,
        String name
    ) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(name + " overflows", error);
        }
    }
}
