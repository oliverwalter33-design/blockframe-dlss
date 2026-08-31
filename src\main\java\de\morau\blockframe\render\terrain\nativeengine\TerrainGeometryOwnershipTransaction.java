package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.CompatibilityProof;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Digest;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MeshDescriptor;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.RetirementToken;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import java.util.Objects;

/**
 * Dirty-path ownership protocol for one immutable section/layer generation.
 *
 * <p>This object never participates in the normal per-frame traversal. The
 * single renderer-wide {@link TerrainSubmissionBoundary} owns the no-replay
 * point. A transaction consults that boundary only on an error or retirement
 * path, so adopted section count cannot affect warm submission complexity.</p>
 *
 * <p>This original handoff transaction is retained as the exact NO_GO
 * evidence and generation/no-replay contract for a Mojang-owned payload.
 * The complete native backend does not invoke its Mojang publication bridge:
 * {@link NativeTerrainSectionLifecycle} and
 * {@link NativeTerrainPayloadOwner} own pre-merge compilation and atomic
 * publication instead. In particular, {@link PayloadOwnershipPermit} remains
 * incapable of authorizing the abandoned final-payload upload suppression:
 * it is not by itself production authority to suppress that upload.</p>
 */
public final class TerrainGeometryOwnershipTransaction {
    public enum Stage {
        MOJANG_OWNED,
        CAPTURED,
        GEOMETRY_RESERVED,
        PAYLOAD_RETAINED,
        SCENE_SLOT_RESERVED,
        FALLBACK_BRIDGE_READY,
        PUBLICATION_BRIDGE_READY,
        PAYLOAD_OWNED,
        TRANSFER_RECORDED,
        SCENE_PUBLISHED,
        ACTIVE,
        FALLBACK_PENDING,
        DEMOTION_PENDING,
        QUARANTINED,
        LIFECYCLE_INVALIDATED,
        RETIRING,
        CLOSED
    }

    public enum FallbackAction {
        MOJANG_ORIGINAL_SAME_CALL,
        MOJANG_RESTAGE_RETAINED_PAYLOAD,
        MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION,
        MOJANG_NEXT_FRAME_NO_REPLAY,
        REMESH_REQUIRED_FAIL_CLOSED
    }

    /**
     * Owner-identity token. Its constructor is private so equal generation
     * numbers from another section cannot forge ownership.
     */
    public static final class PayloadOwnershipPermit {
        private final TerrainGeometryOwnershipTransaction owner;
        private final GenerationStamp generations;
        private final long transactionSerial;
        private final long retirementSerial;

        private PayloadOwnershipPermit(
            TerrainGeometryOwnershipTransaction owner
        ) {
            this.owner = owner;
            this.generations = owner.expectedGenerations;
            this.transactionSerial = owner.transactionSerial;
            this.retirementSerial =
                owner.descriptor.retirement().serial();
        }

        public GenerationStamp generations() {
            return this.generations;
        }

        public long transactionSerial() {
            return this.transactionSerial;
        }

        public long retirementSerial() {
            return this.retirementSerial;
        }
    }

    public static final class NativeDrawPermit {
        private final TerrainGeometryOwnershipTransaction owner;
        private final GenerationStamp generations;
        private final long transactionSerial;
        private final long retirementSerial;

        private NativeDrawPermit(
            TerrainGeometryOwnershipTransaction owner
        ) {
            this.owner = owner;
            this.generations = owner.expectedGenerations;
            this.transactionSerial = owner.transactionSerial;
            this.retirementSerial =
                owner.descriptor.retirement().serial();
        }

        public GenerationStamp generations() {
            return this.generations;
        }

        public long transactionSerial() {
            return this.transactionSerial;
        }

        public long retirementSerial() {
            return this.retirementSerial;
        }
    }

    public static final class RetirementFence {
        private final TerrainGeometryOwnershipTransaction owner;
        private final GenerationStamp generations;
        private final RetirementToken retirement;
        private final long minimumCompletedSubmission;

        private RetirementFence(
            TerrainGeometryOwnershipTransaction owner,
            long minimumCompletedSubmission
        ) {
            this.owner = owner;
            this.generations = owner.expectedGenerations;
            this.retirement = owner.descriptor.retirement();
            this.minimumCompletedSubmission =
                minimumCompletedSubmission;
        }

        public GenerationStamp generations() {
            return this.generations;
        }

        public RetirementToken retirement() {
            return this.retirement;
        }

        public long minimumCompletedSubmission() {
            return this.minimumCompletedSubmission;
        }
    }

    /**
     * Owner-identity proof that a strict successor generation has made this
     * generation unreachable. It is separate from a draw/fallback permit.
     */
    public static final class LifecycleInvalidationPermit {
        private final TerrainGeometryOwnershipTransaction owner;
        private final GenerationStamp invalidatedGenerations;
        private final GenerationStamp successorGenerations;

        private LifecycleInvalidationPermit(
            TerrainGeometryOwnershipTransaction owner,
            GenerationStamp successorGenerations
        ) {
            this.owner = owner;
            this.invalidatedGenerations = owner.expectedGenerations;
            this.successorGenerations = successorGenerations;
        }

        public GenerationStamp invalidatedGenerations() {
            return this.invalidatedGenerations;
        }

        public GenerationStamp successorGenerations() {
            return this.successorGenerations;
        }
    }

    private final GenerationStamp expectedGenerations;
    private final long transactionSerial;
    private final Digest expectedSourceContract;
    private final StableId expectedHookContract;

    private Stage stage = Stage.MOJANG_OWNED;
    private MeshDescriptor descriptor;
    private CompatibilityProof compatibilityProof;
    private FallbackAction pendingFallback;
    private TerrainSubmissionBoundary pendingFallbackBoundary;
    private long pendingFallbackFrameSerial;
    private long minimumFallbackSubmissionSerial;
    private boolean geometryReserved;
    private boolean retainedPayload;
    private boolean fallbackBridgeReady;
    private boolean publicationBridgeReady;
    private boolean payloadOwned;
    private boolean nativeSliceHealthy;

    public TerrainGeometryOwnershipTransaction(
        GenerationStamp expectedGenerations,
        long transactionSerial,
        Digest expectedSourceContract,
        StableId expectedHookContract
    ) {
        this.expectedGenerations = Objects.requireNonNull(
            expectedGenerations,
            "expectedGenerations"
        );
        requirePositive(transactionSerial, "transactionSerial");
        this.transactionSerial = transactionSerial;
        this.expectedSourceContract = Objects.requireNonNull(
            expectedSourceContract,
            "expectedSourceContract"
        );
        this.expectedSourceContract.requireKnown(
            "expectedSourceContract"
        );
        this.expectedHookContract = Objects.requireNonNull(
            expectedHookContract,
            "expectedHookContract"
        );
        this.expectedHookContract.requirePresent(
            "expectedHookContract"
        );
    }

    public synchronized Stage stage() {
        return this.stage;
    }

    public synchronized boolean retainedPayload() {
        return this.retainedPayload;
    }

    public synchronized boolean nativeSliceHealthy() {
        return this.nativeSliceHealthy;
    }

    public synchronized void capture(
        MeshDescriptor candidate,
        CompatibilityProof proof
    ) {
        requireStage(Stage.MOJANG_OWNED);
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(proof, "proof");
        requireGenerations(candidate.generations());
        if (
            !candidate.structurallyCompatibleWithFirstMilestone()
                || !proof.matches(
                    candidate,
                    this.expectedSourceContract,
                    this.expectedHookContract
                )
        ) {
            throw new IllegalArgumentException(
                "mesh lacks an exact source, provenance, publication, "
                    + "shader, material or capability proof"
            );
        }
        this.descriptor = candidate;
        this.compatibilityProof = proof;
        this.stage = Stage.CAPTURED;
    }

    public synchronized void reserveGeometry(
        GenerationStamp generations
    ) {
        requireCurrent(generations);
        requireStage(Stage.CAPTURED);
        this.geometryReserved = true;
        this.stage = Stage.GEOMETRY_RESERVED;
    }

    /**
     * Confirms an independent bounded payload lifetime. Borrowing Minecraft's
     * final ByteBuffer past the intercepted call is forbidden.
     */
    public synchronized void retainPayload(
        GenerationStamp generations
    ) {
        requireCurrent(generations);
        requireStage(Stage.GEOMETRY_RESERVED);
        this.retainedPayload = true;
        this.stage = Stage.PAYLOAD_RETAINED;
    }

    public synchronized void reserveSceneSlot(
        GenerationStamp generations
    ) {
        requireCurrent(generations);
        requireStage(Stage.PAYLOAD_RETAINED);
        this.stage = Stage.SCENE_SLOT_RESERVED;
    }

    public synchronized void confirmFallbackBridge(
        GenerationStamp generations
    ) {
        requireCurrent(generations);
        requireStage(Stage.SCENE_SLOT_RESERVED);
        this.fallbackBridgeReady = true;
        this.stage = Stage.FALLBACK_BRIDGE_READY;
    }

    /**
     * Confirms exact integration with CompiledSectionMesh upload flags,
     * checkSectionMesh/setSectionMesh, cancellation, reset and release under
     * Minecraft's copyLock. No caller may substitute a scene-only callback.
     */
    public synchronized void confirmMojangPublicationBridge(
        GenerationStamp generations
    ) {
        requireCurrent(generations);
        requireStage(Stage.FALLBACK_BRIDGE_READY);
        this.publicationBridgeReady = true;
        this.stage = Stage.PUBLICATION_BRIDGE_READY;
    }

    /**
     * Commits the retained payload to this owner. This synthetic contract does
     * not itself authorize a production Mixin to skip Minecraft's upload.
     */
    public synchronized PayloadOwnershipPermit commitPayloadOwnership(
        GenerationStamp generations
    ) {
        requireCurrent(generations);
        requireStage(Stage.PUBLICATION_BRIDGE_READY);
        if (
            !this.geometryReserved
                || !this.retainedPayload
                || !this.fallbackBridgeReady
                || !this.publicationBridgeReady
                || this.compatibilityProof == null
        ) {
            throw new IllegalStateException(
                "payload ownership prerequisites are incomplete"
            );
        }
        this.payloadOwned = true;
        this.stage = Stage.PAYLOAD_OWNED;
        return new PayloadOwnershipPermit(this);
    }

    public synchronized void recordTransfer(
        PayloadOwnershipPermit permit
    ) {
        requirePayloadPermit(permit);
        requireStage(Stage.PAYLOAD_OWNED);
        this.stage = Stage.TRANSFER_RECORDED;
    }

    public synchronized void publishScene(
        PayloadOwnershipPermit permit
    ) {
        requirePayloadPermit(permit);
        requireStage(Stage.TRANSFER_RECORDED);
        this.nativeSliceHealthy = true;
        this.stage = Stage.SCENE_PUBLISHED;
    }

    public synchronized NativeDrawPermit armNativeDraw(
        PayloadOwnershipPermit permit
    ) {
        requirePayloadPermit(permit);
        requireStage(Stage.SCENE_PUBLISHED);
        if (
            !this.nativeSliceHealthy
                || !this.fallbackBridgeReady
                || !this.publicationBridgeReady
        ) {
            throw new IllegalStateException(
                "native draw prerequisites are unavailable"
            );
        }
        this.stage = Stage.ACTIVE;
        return new NativeDrawPermit(this);
    }

    public synchronized void releaseRetainedPayload(
        NativeDrawPermit permit
    ) {
        requireDrawPermit(permit);
        requireStage(Stage.ACTIVE);
        if (
            !this.nativeSliceHealthy
                || !this.fallbackBridgeReady
                || !this.publicationBridgeReady
                || !this.retainedPayload
        ) {
            throw new IllegalStateException(
                "cannot release the only fallback payload"
            );
        }
        this.retainedPayload = false;
    }

    /** Selects fallback before this generation is eligible for normal draw. */
    public synchronized FallbackAction failBeforeNativeDraw() {
        requireOpen();
        return switch (this.stage) {
            case MOJANG_OWNED,
                CAPTURED,
                GEOMETRY_RESERVED,
                PAYLOAD_RETAINED,
                SCENE_SLOT_RESERVED,
                FALLBACK_BRIDGE_READY,
                PUBLICATION_BRIDGE_READY -> selectPending(
                    FallbackAction.MOJANG_ORIGINAL_SAME_CALL
                );
            case PAYLOAD_OWNED, TRANSFER_RECORDED -> {
                if (this.retainedPayload) {
                    yield selectPending(
                        FallbackAction
                            .MOJANG_RESTAGE_RETAINED_PAYLOAD
                    );
                }
                yield selectPending(
                    FallbackAction.REMESH_REQUIRED_FAIL_CLOSED
                );
            }
            case SCENE_PUBLISHED -> {
                if (
                    this.nativeSliceHealthy
                        && this.fallbackBridgeReady
                        && this.publicationBridgeReady
                ) {
                    yield selectPending(
                        FallbackAction
                            .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION
                    );
                }
                if (this.retainedPayload) {
                    yield selectPending(
                        FallbackAction
                            .MOJANG_RESTAGE_RETAINED_PAYLOAD
                    );
                }
                yield selectPending(
                    FallbackAction.REMESH_REQUIRED_FAIL_CLOSED
                );
            }
            case ACTIVE -> throw new IllegalStateException(
                "active geometry must consult the global frame boundary"
            );
            case FALLBACK_PENDING,
                DEMOTION_PENDING,
                QUARANTINED,
                LIFECYCLE_INVALIDATED,
                RETIRING,
                CLOSED -> throw new IllegalStateException(
                    "fallback is unavailable in " + this.stage
                );
        };
    }

    /**
     * Error-only query against the one global frame boundary. No scene entry
     * is touched when native submission starts.
     */
    public synchronized FallbackAction failDuringFrame(
        TerrainSubmissionBoundary boundary,
        long frameSerial
    ) {
        requireStage(Stage.ACTIVE);
        Objects.requireNonNull(boundary, "boundary");
        boundary.requireCompatible(this.expectedGenerations);
        TerrainSubmissionBoundary.FailureBoundary frameBoundary =
            boundary.failureBoundary(frameSerial);
        if (
            frameBoundary
                == TerrainSubmissionBoundary.FailureBoundary
                    .NEXT_FRAME_ONLY_NO_REPLAY
        ) {
            this.minimumFallbackSubmissionSerial = Math.max(
                this.minimumFallbackSubmissionSerial,
                boundary.retirementSubmissionSerial()
            );
            return selectFramePending(
                FallbackAction.MOJANG_NEXT_FRAME_NO_REPLAY,
                boundary,
                frameSerial
            );
        }
        if (
            this.nativeSliceHealthy
                && this.fallbackBridgeReady
                && this.publicationBridgeReady
        ) {
            return selectFramePending(
                FallbackAction
                    .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION,
                boundary,
                frameSerial
            );
        }
        if (this.retainedPayload) {
            return selectFramePending(
                FallbackAction.MOJANG_RESTAGE_RETAINED_PAYLOAD,
                boundary,
                frameSerial
            );
        }
        return selectFramePending(
            FallbackAction.REMESH_REQUIRED_FAIL_CLOSED,
            boundary,
            frameSerial
        );
    }

    public synchronized void confirmFallbackCompleted(
        FallbackAction completed
    ) {
        if (
            completed
                == FallbackAction
                    .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION
        ) {
            throw new IllegalStateException(
                "native-slice fallback requires current submission proof"
            );
        }
        completeFallback(completed);
    }

    /**
     * Completes a fallback that directly consumes the native slice only after
     * that exact frame crossed the global no-replay boundary. Its current
     * submission serial becomes a mandatory retirement-completion fence.
     */
    public synchronized void confirmEncodedSliceFallbackSubmitted(
        TerrainSubmissionBoundary boundary,
        long frameSerial
    ) {
        requireStage(Stage.FALLBACK_PENDING);
        Objects.requireNonNull(boundary, "boundary");
        if (
            this.pendingFallback
                != FallbackAction
                    .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION
                || boundary != this.pendingFallbackBoundary
                || frameSerial != this.pendingFallbackFrameSerial
        ) {
            throw new IllegalArgumentException(
                "submission proof belongs to another fallback"
            );
        }
        boundary.requireCompatible(this.expectedGenerations);
        if (
            boundary.failureBoundary(frameSerial)
                != TerrainSubmissionBoundary.FailureBoundary
                    .NEXT_FRAME_ONLY_NO_REPLAY
        ) {
            throw new IllegalStateException(
                "fallback has not crossed the no-replay boundary"
            );
        }
        long submissionSerial = boundary.retirementSubmissionSerial();
        if (submissionSerial <= 0L) {
            throw new IllegalStateException(
                "fallback submission serial is unavailable"
            );
        }
        this.minimumFallbackSubmissionSerial = Math.max(
            this.minimumFallbackSubmissionSerial,
            submissionSerial
        );
        completeFallback(
            FallbackAction
                .MOJANG_ENCODE_NATIVE_SLICE_BEFORE_SUBMISSION
        );
    }

    private void completeFallback(FallbackAction completed) {
        requireStage(Stage.FALLBACK_PENDING);
        if (completed != this.pendingFallback) {
            throw new IllegalArgumentException(
                "completed a different fallback action"
            );
        }
        this.pendingFallback = null;
        this.pendingFallbackBoundary = null;
        this.pendingFallbackFrameSerial = 0L;
        this.retainedPayload = false;
        this.nativeSliceHealthy = false;
        if (completed == FallbackAction.REMESH_REQUIRED_FAIL_CLOSED) {
            this.stage = Stage.QUARANTINED;
        } else {
            this.stage = this.geometryReserved || this.payloadOwned
                ? Stage.DEMOTION_PENDING
                : Stage.MOJANG_OWNED;
        }
    }

    public synchronized void invalidateNativeSlice() {
        requireOpen();
        if (
            this.stage != Stage.SCENE_PUBLISHED
                && this.stage != Stage.ACTIVE
        ) {
            throw new IllegalStateException(
                "no published native slice exists in " + this.stage
            );
        }
        this.nativeSliceHealthy = false;
    }

    /**
     * Error demotion may retire only after its promised usable fallback has
     * completed. Quarantined only-copy loss remains retained until a future
     * real replacement-publication bridge can produce a distinct proof.
     */
    public synchronized RetirementFence requestRetirementAfterDemotion(
        TerrainSubmissionBoundary boundary
    ) {
        requireOpen();
        if (this.stage != Stage.DEMOTION_PENDING) {
            throw new IllegalStateException(
                "fallback/demotion is not complete"
            );
        }
        return beginRetirement(boundary);
    }

    /**
     * Marks this owner unreachable only after a strict successor generation
     * exists. The returned identity token cannot be reused for another owner.
     */
    public synchronized LifecycleInvalidationPermit invalidateLifecycle(
        GenerationStamp successorGenerations
    ) {
        requireOpen();
        if (
            this.stage == Stage.FALLBACK_PENDING
                || this.stage == Stage.RETIRING
                || this.stage == Stage.QUARANTINED
                || this.stage == Stage.LIFECYCLE_INVALIDATED
                || this.descriptor == null
        ) {
            throw new IllegalStateException(
                "lifecycle invalidation is not safe in " + this.stage
            );
        }
        requireStrictSuccessor(
            this.expectedGenerations,
            successorGenerations
        );
        this.stage = Stage.LIFECYCLE_INVALIDATED;
        return new LifecycleInvalidationPermit(
            this,
            successorGenerations
        );
    }

    /**
     * Retires only an owner already made unreachable by a strict successor.
     */
    public synchronized RetirementFence requestLifecycleRetirement(
        TerrainSubmissionBoundary boundary,
        LifecycleInvalidationPermit permit
    ) {
        requireStage(Stage.LIFECYCLE_INVALIDATED);
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || !permit.invalidatedGenerations.equals(
                    this.expectedGenerations
                )
        ) {
            throw new IllegalArgumentException(
                "lifecycle proof belongs to another owner"
            );
        }
        return beginRetirement(boundary);
    }

    public synchronized void completeRetirement(
        RetirementFence fence,
        long completedSubmissionSerial
    ) {
        requireStage(Stage.RETIRING);
        Objects.requireNonNull(fence, "fence");
        if (fence.owner != this) {
            throw new IllegalArgumentException(
                "retirement proof belongs to another owner"
            );
        }
        if (
            completedSubmissionSerial
                < fence.minimumCompletedSubmission
        ) {
            throw new IllegalStateException(
                "retirement completion proof is too old"
            );
        }
        clearOwnership();
        this.stage = Stage.CLOSED;
    }

    public synchronized void closeWithoutResources() {
        requireOpen();
        if (
            this.geometryReserved
                || this.payloadOwned
                || this.nativeSliceHealthy
                || this.stage == Stage.FALLBACK_PENDING
        ) {
            throw new IllegalStateException(
                "owner resources or fallback require retirement proof"
            );
        }
        clearOwnership();
        this.stage = Stage.CLOSED;
    }

    private FallbackAction selectPending(FallbackAction action) {
        this.pendingFallback = action;
        this.pendingFallbackBoundary = null;
        this.pendingFallbackFrameSerial = 0L;
        this.stage = Stage.FALLBACK_PENDING;
        return action;
    }

    private FallbackAction selectFramePending(
        FallbackAction action,
        TerrainSubmissionBoundary boundary,
        long frameSerial
    ) {
        this.pendingFallback = action;
        this.pendingFallbackBoundary = boundary;
        this.pendingFallbackFrameSerial = frameSerial;
        this.stage = Stage.FALLBACK_PENDING;
        return action;
    }

    private RetirementFence beginRetirement(
        TerrainSubmissionBoundary boundary
    ) {
        Objects.requireNonNull(boundary, "boundary");
        boundary.requireCompatible(this.expectedGenerations);
        if (this.descriptor == null) {
            throw new IllegalStateException(
                "no captured generation to retire"
            );
        }
        long completion = Math.max(
            boundary.retirementSubmissionSerial(),
            this.minimumFallbackSubmissionSerial
        );
        this.stage = Stage.RETIRING;
        return new RetirementFence(this, completion);
    }

    private void requirePayloadPermit(
        PayloadOwnershipPermit permit
    ) {
        requireOpen();
        Objects.requireNonNull(permit, "permit");
        if (permit.owner != this || !this.payloadOwned) {
            throw new IllegalArgumentException(
                "stale or foreign payload permit"
            );
        }
        requireGenerations(permit.generations);
    }

    private void requireDrawPermit(NativeDrawPermit permit) {
        requireOpen();
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || !this.payloadOwned
                || !this.nativeSliceHealthy
        ) {
            throw new IllegalArgumentException(
                "stale or foreign native draw permit"
            );
        }
        requireGenerations(permit.generations);
    }

    private void requireCurrent(GenerationStamp generations) {
        requireOpen();
        requireGenerations(generations);
    }

    private void requireGenerations(GenerationStamp generations) {
        if (!this.expectedGenerations.equals(generations)) {
            throw new IllegalArgumentException(
                "owner generation mismatch"
            );
        }
    }

    private void requireStage(Stage expected) {
        requireOpen();
        if (this.stage != expected) {
            throw new IllegalStateException(
                "expected " + expected + " but was " + this.stage
            );
        }
    }

    private void requireOpen() {
        if (this.stage == Stage.CLOSED) {
            throw new IllegalStateException("transaction is closed");
        }
    }

    private void clearOwnership() {
        this.descriptor = null;
        this.compatibilityProof = null;
        this.pendingFallback = null;
        this.pendingFallbackBoundary = null;
        this.pendingFallbackFrameSerial = 0L;
        this.minimumFallbackSubmissionSerial = 0L;
        this.geometryReserved = false;
        this.retainedPayload = false;
        this.fallbackBridgeReady = false;
        this.publicationBridgeReady = false;
        this.payloadOwned = false;
        this.nativeSliceHealthy = false;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireStrictSuccessor(
        GenerationStamp current,
        GenerationStamp successor
    ) {
        Objects.requireNonNull(successor, "successorGenerations");
        if (
            successor.device() < current.device()
                || successor.renderer() < current.renderer()
                || successor.world() < current.world()
                || successor.resources() < current.resources()
                || successor.producer() < current.producer()
                || successor.sectionMesh() < current.sectionMesh()
                || successor.equals(current)
        ) {
            throw new IllegalArgumentException(
                "lifecycle successor must monotonically advance"
            );
        }
    }
}
