package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.CreationCleanupAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.CreationFailureAction;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Phase;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Preflight;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.SelectedBackend;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.Selection;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.WorldResourceCreationPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import java.util.Objects;
import java.util.Optional;

/**
 * Backend-neutral transaction owner for one exclusive native terrain world
 * owner.
 *
 * <p>The factory neither constructs Mojang resources nor knows Minecraft or
 * Vulkan types. It binds one local owner generation to the selector's opaque
 * creation permit. Allocation and preparation failures may request a complete
 * Mojang rebuild only after cleanup proves that no native ownership remains.
 * Calling {@link WorldResourceOwner#publish()} begins the irreversible
 * publication boundary. Any failure from that call onward is conservatively
 * quarantined and can never request an in-place Mojang fallback.</p>
 */
public final class ExclusiveNativeWorldResourceFactory
    implements AutoCloseable {
    public enum FactoryPhase {
        READY,
        CREATING,
        PUBLISHING,
        ACTIVE,
        RETIRING,
        RETIREMENT_CLEANUP_PENDING,
        PRE_PUBLISH_CLEANUP_PENDING,
        AWAITING_WORLD_REVALIDATION,
        MOJANG_FALLBACK_REQUIRED,
        QUARANTINED,
        CLOSED
    }

    public enum CreationOutcome {
        NATIVE_OWNER_PUBLISHED,
        PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
        MOJANG_FALLBACK_REQUIRED,
        FAIL_CLOSED_QUARANTINED
    }

    public enum RetirementReason {
        ABORT_BEFORE_PUBLISH,
        RELOAD,
        WORLD_SWITCH,
        CLOSE
    }

    public enum RetirementOutcome {
        RETIRED,
        CLEANUP_RETRY_REQUIRED,
        RETIRED_BUT_QUARANTINED
    }

    /**
     * The selector generation is immutable while the local serial prevents a
     * prior world or reload owner from being reused.
     */
    public record OwnerGeneration(
        long serial,
        GenerationStamp plannedGenerations
    ) {
        public OwnerGeneration {
            if (serial <= 0L) {
                throw new IllegalArgumentException(
                    "owner generation serial must be positive"
                );
            }
            plannedGenerations = Objects.requireNonNull(
                plannedGenerations,
                "plannedGenerations"
            );
        }
    }

    @FunctionalInterface
    public interface OwnerConstructor {
        WorldResourceOwner construct(OwnerGeneration generation)
            throws Exception;
    }

    /**
     * A constructed owner is private until preparation succeeds. The
     * implementation must treat {@code publish()} as an atomic visibility
     * boundary. Once it is invoked, even an exception means publication is
     * uncertain and fallback is forbidden.
     */
    public interface WorldResourceOwner {
        OwnerGeneration generation();

        void prepare() throws Exception;

        void publish() throws Exception;

        CreationCleanupAttestation retire(
            RetirementReason reason
        ) throws Exception;
    }

    /**
     * Typed construction failure used only when the constructor itself has
     * already completed all cleanup or returns the sole owner that can retire
     * its explicitly attested outstanding ownership.
     */
    public static final class PrePublicationFailure extends Exception {
        private final CreationCleanupAttestation cleanup;
        private final WorldResourceOwner cleanupOwner;

        public PrePublicationFailure(
            String message,
            CreationCleanupAttestation cleanup
        ) {
            this(
                message,
                null,
                cleanup,
                null
            );
        }

        public PrePublicationFailure(
            String message,
            Throwable cause,
            CreationCleanupAttestation cleanup
        ) {
            this(message, cause, cleanup, null);
        }

        public PrePublicationFailure(
            String message,
            CreationCleanupAttestation cleanup,
            WorldResourceOwner cleanupOwner
        ) {
            this(message, null, cleanup, cleanupOwner);
        }

        public PrePublicationFailure(
            String message,
            Throwable cause,
            CreationCleanupAttestation cleanup,
            WorldResourceOwner cleanupOwner
        ) {
            super(
                Objects.requireNonNull(message, "message"),
                cause
            );
            this.cleanup = Objects.requireNonNull(
                cleanup,
                "cleanup"
            );
            if (this.cleanup.complete() == (cleanupOwner != null)) {
                throw new IllegalArgumentException(
                    "incomplete cleanup requires exactly one retained owner"
                );
            }
            this.cleanupOwner = cleanupOwner;
        }

        public CreationCleanupAttestation cleanup() {
            return this.cleanup;
        }

        public Optional<WorldResourceOwner> cleanupOwnerOptional() {
            return Optional.ofNullable(this.cleanupOwner);
        }
    }

    public static final class CreationPermit {
        private final ExclusiveNativeWorldResourceFactory owner;
        private final long epoch;
        private final WorldResourceCreationPermit selectorPermit;
        private final OwnerGeneration generation;
        private boolean consumed;

        private CreationPermit(
            ExclusiveNativeWorldResourceFactory owner,
            long epoch,
            WorldResourceCreationPermit selectorPermit,
            OwnerGeneration generation
        ) {
            this.owner = owner;
            this.epoch = epoch;
            this.selectorPermit = selectorPermit;
            this.generation = generation;
        }

        public OwnerGeneration generation() {
            return this.generation;
        }
    }

    public static final class OwnerHandle {
        private final ExclusiveNativeWorldResourceFactory owner;
        private final long epoch;
        private final OwnerGeneration generation;

        private OwnerHandle(
            ExclusiveNativeWorldResourceFactory owner,
            long epoch,
            OwnerGeneration generation
        ) {
            this.owner = owner;
            this.epoch = epoch;
            this.generation = generation;
        }

        public OwnerGeneration generation() {
            if (this.generation == null) {
                throw new IllegalStateException(
                    "cleanup owner generation is unavailable"
                );
            }
            return this.generation;
        }

        public Optional<OwnerGeneration> generationOptional() {
            return Optional.ofNullable(this.generation);
        }
    }

    public record CreationResult(
        CreationOutcome outcome,
        OwnerHandle ownerHandle,
        String reason
    ) {
        public CreationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reason = Objects.requireNonNull(reason, "reason");
            if (
                outcome == CreationOutcome.NATIVE_OWNER_PUBLISHED
                    && (ownerHandle == null || !reason.isEmpty())
            ) {
                throw new IllegalArgumentException(
                    "published result requires one owner and no failure"
                );
            }
            if (
                outcome != CreationOutcome.NATIVE_OWNER_PUBLISHED
                    && reason.isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "failed creation requires a reason"
                );
            }
            if (
                outcome == CreationOutcome.MOJANG_FALLBACK_REQUIRED
                    && ownerHandle != null
            ) {
                throw new IllegalArgumentException(
                    "Mojang fallback cannot retain a native owner"
                );
            }
            if (
                outcome
                    == CreationOutcome
                        .PRE_PUBLISH_CLEANUP_RETRY_REQUIRED
                    && ownerHandle == null
            ) {
                throw new IllegalArgumentException(
                    "cleanup retry requires its retained owner"
                );
            }
        }

        public Optional<OwnerHandle> ownerHandleOptional() {
            return Optional.ofNullable(this.ownerHandle);
        }
    }

    public record RetirementResult(
        RetirementOutcome outcome,
        CreationCleanupAttestation cleanup,
        String reason
    ) {
        public RetirementResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            cleanup = Objects.requireNonNull(cleanup, "cleanup");
            reason = Objects.requireNonNull(reason, "reason");
            if (
                outcome == RetirementOutcome.RETIRED
                    && (!cleanup.complete() || !reason.isEmpty())
            ) {
                throw new IllegalArgumentException(
                    "clean retirement must be complete"
                );
            }
            if (
                outcome != RetirementOutcome.RETIRED
                    && reason.isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "non-clean retirement requires a reason"
                );
            }
        }
    }

    public record Snapshot(
        FactoryPhase phase,
        long lastOwnerSerial,
        boolean ownerPresent,
        boolean publicationBoundaryCrossed,
        String quarantineReason
    ) {
        public Snapshot {
            phase = Objects.requireNonNull(phase, "phase");
            quarantineReason = Objects.requireNonNull(
                quarantineReason,
                "quarantineReason"
            );
            boolean failureReasonRequired =
                phase == FactoryPhase.QUARANTINED
                    || phase
                        == FactoryPhase
                            .RETIREMENT_CLEANUP_PENDING
                    || phase
                        == FactoryPhase
                            .PRE_PUBLISH_CLEANUP_PENDING;
            if (
                failureReasonRequired
                    != !quarantineReason.isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "failure phase and reason disagree"
                );
            }
        }
    }

    private final NativeTerrainBackendSelector selector;
    private FactoryPhase phase = FactoryPhase.READY;
    private long epoch = 1L;
    private long lastOwnerSerial;
    private CreationPermit activePermit;
    private WorldResourceOwner owner;
    private OwnerHandle ownerHandle;
    private boolean publicationBoundaryCrossed;
    private String quarantineReason = "";
    private RetirementReason pendingRetirementReason;
    private RetirementReason pendingRevalidationReason;
    private GenerationStamp retiredGenerations;

    public ExclusiveNativeWorldResourceFactory(
        NativeTerrainBackendSelector selector
    ) {
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    /**
     * Obtains the existing selector permit and wraps it with a local
     * single-use generation. No owner is constructed here.
     */
    public synchronized CreationPermit beginCreation() {
        requirePhase(FactoryPhase.READY);
        return beginSelectedCreation();
    }

    /**
     * Applies fresh world-lifetime evidence after reload or world switch and
     * begins construction only when that exact preflight still selects native.
     */
    public synchronized Optional<CreationPermit>
    revalidateAndBeginCreation(Preflight freshPreflight) {
        requirePhase(FactoryPhase.AWAITING_WORLD_REVALIDATION);
        Objects.requireNonNull(freshPreflight, "freshPreflight");
        requireFreshGenerations(
            freshPreflight.plannedGenerations()
        );
        Selection revalidated =
            this.selector.revalidateBeforeWorldResources(
                freshPreflight
            );
        this.pendingRevalidationReason = null;
        this.retiredGenerations = null;
        if (!revalidated.nativeBackendSelected()) {
            this.phase = FactoryPhase.MOJANG_FALLBACK_REQUIRED;
            this.epoch = increment(this.epoch);
            return Optional.empty();
        }
        this.phase = FactoryPhase.READY;
        return Optional.of(beginSelectedCreation());
    }

    private CreationPermit beginSelectedCreation() {
        if (!this.selector.selection().nativeBackendSelected()) {
            throw new IllegalStateException(
                "exclusive native factory requires a native selection"
            );
        }
        WorldResourceCreationPermit selectorPermit =
            this.selector.beginWorldResourceCreation();
        if (
            selectorPermit.backend()
                != SelectedBackend.BLOCKFRAME_NATIVE_EXPERIMENTAL
        ) {
            this.selector.abortReferenceWorldResourceCreation(
                selectorPermit
            );
            throw new IllegalStateException(
                "selector did not issue a native creation permit"
            );
        }
        this.lastOwnerSerial = increment(this.lastOwnerSerial);
        this.epoch = increment(this.epoch);
        OwnerGeneration generation = new OwnerGeneration(
            this.lastOwnerSerial,
            Objects.requireNonNull(
                selectorPermit.plannedGenerations(),
                "native permit generations"
            )
        );
        this.activePermit = new CreationPermit(
            this,
            this.epoch,
            selectorPermit,
            generation
        );
        this.publicationBoundaryCrossed = false;
        this.quarantineReason = "";
        this.phase = FactoryPhase.CREATING;
        return this.activePermit;
    }

    /**
     * Constructs at most one owner under a single-use permit.
     */
    public synchronized CreationResult create(
        CreationPermit permit,
        OwnerConstructor constructor
    ) {
        requirePermit(permit);
        Objects.requireNonNull(constructor, "constructor");
        permit.consumed = true;

        WorldResourceOwner constructed;
        try {
            constructed = Objects.requireNonNull(
                constructor.construct(permit.generation),
                "constructed owner"
            );
        } catch (PrePublicationFailure failure) {
            CreationCleanupAttestation cleanup =
                normalizeCleanup(failure.cleanup());
            String reason = "constructor:" + failure.getMessage();
            Optional<WorldResourceOwner> cleanupOwnerOptional =
                failure.cleanupOwnerOptional();
            if (cleanupOwnerOptional.isPresent()) {
                WorldResourceOwner cleanupOwner =
                    cleanupOwnerOptional.orElseThrow();
                OwnerGeneration cleanupGeneration = null;
                try {
                    cleanupGeneration = cleanupOwner.generation();
                } catch (Throwable generationFailure) {
                    reason +=
                        ";cleanup-owner-generation-unavailable:"
                            + generationFailure
                                .getClass()
                                .getSimpleName();
                }
                if (
                    !permit.generation.equals(cleanupGeneration)
                ) {
                    retainCleanupOwnerForRetry(
                        cleanupOwner,
                        cleanupGeneration
                    );
                    cleanup = dirtyCleanup();
                    if (cleanupGeneration != null) {
                        reason +=
                            ";cleanup-owner-generation-mismatch";
                    }
                } else {
                    installOwner(
                        cleanupOwner,
                        cleanupGeneration
                    );
                }
            }
            return abortBeforePublish(
                cleanup,
                reason
            );
        } catch (Throwable failure) {
            return abortBeforePublish(
                dirtyCleanup(),
                "constructor-uncertain:"
                    + failure.getClass().getSimpleName()
            );
        }

        OwnerGeneration actualGeneration;
        try {
            actualGeneration = constructed.generation();
        } catch (Throwable failure) {
            retainCleanupOwnerForRetry(constructed, null);
            return abortConstructedOwnerBeforePublish(
                "owner-generation-unavailable:"
                    + failure.getClass().getSimpleName()
            );
        }
        if (!permit.generation.equals(actualGeneration)) {
            retainCleanupOwnerForRetry(
                constructed,
                actualGeneration
            );
            return abortConstructedOwnerBeforePublish(
                "owner-generation-mismatch"
            );
        }
        installOwner(constructed, actualGeneration);

        try {
            constructed.prepare();
        } catch (Throwable failure) {
            return abortConstructedOwnerBeforePublish(
                "prepare:" + failure.getClass().getSimpleName()
            );
        }

        this.phase = FactoryPhase.PUBLISHING;
        this.publicationBoundaryCrossed = true;
        try {
            constructed.publish();
        } catch (Throwable failure) {
            return quarantineAfterPublicationBoundary(
                "publish-uncertain:"
                    + failure.getClass().getSimpleName()
            );
        }

        try {
            this.selector.completeWorldResourceCreation(
                permit.selectorPermit
            );
        } catch (Throwable failure) {
            return quarantineAfterPublicationBoundary(
                "selector-commit-after-publish:"
                    + failure.getClass().getSimpleName()
            );
        }

        this.phase = FactoryPhase.ACTIVE;
        return new CreationResult(
            CreationOutcome.NATIVE_OWNER_PUBLISHED,
            this.ownerHandle,
            ""
        );
    }

    /**
     * Runtime failure after successful publication. The owner stays retained
     * for ordered retirement, but no Mojang fallback is authorized.
     */
    public synchronized void signalFailureAfterPublish(
        OwnerHandle handle,
        String reason
    ) {
        requirePhase(FactoryPhase.ACTIVE);
        requireHandle(handle);
        String suppliedReason = Objects.requireNonNull(reason, "reason");
        if (suppliedReason.isBlank()) {
            throw new IllegalArgumentException(
                "post-publication failure requires a reason"
            );
        }
        try {
            this.selector.quarantinePublishedWorldResources(
                this.activePermit.selectorPermit,
                suppliedReason
            );
        } catch (Throwable failure) {
            suppliedReason =
                suppliedReason
                    + ";selector-quarantine:"
                    + failure.getClass().getSimpleName();
        }
        this.phase = FactoryPhase.QUARANTINED;
        this.quarantineReason = suppliedReason;
    }

    /**
     * Retries cleanup for a failure that happened before publish. The original
     * selector permit and cleanup owner stay retained until this method
     * produces a complete attestation.
     */
    public synchronized CreationResult retryPrePublicationCleanup(
        OwnerHandle handle
    ) {
        requirePhase(
            FactoryPhase.PRE_PUBLISH_CLEANUP_PENDING
        );
        requireHandle(handle);
        String reason = this.quarantineReason;
        CreationCleanupAttestation cleanup;
        try {
            cleanup = normalizeCleanup(
                this.owner.retire(
                    RetirementReason.ABORT_BEFORE_PUBLISH
                )
            );
        } catch (Throwable failure) {
            cleanup = dirtyCleanup();
            reason =
                reason
                    + ";cleanup-retry:"
                    + failure.getClass().getSimpleName();
        }

        CreationFailureAction action;
        try {
            action =
                this.selector.retryWorldResourceCreationCleanup(
                    this.activePermit.selectorPermit,
                    cleanup
                );
        } catch (Throwable failure) {
            this.phase = FactoryPhase.QUARANTINED;
            this.quarantineReason =
                reason
                    + ";selector-cleanup-retry:"
                    + failure.getClass().getSimpleName();
            if (cleanup.complete()) {
                this.owner = null;
                this.ownerHandle = null;
            }
            return new CreationResult(
                CreationOutcome.FAIL_CLOSED_QUARANTINED,
                this.ownerHandle,
                this.quarantineReason
            );
        }

        if (
            action
                == CreationFailureAction
                    .RETRY_NATIVE_CLEANUP_BEFORE_ANY_BACKEND_CREATION
        ) {
            this.quarantineReason = reason;
            return new CreationResult(
                CreationOutcome
                    .PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
                this.ownerHandle,
                reason
            );
        }

        this.owner = null;
        this.ownerHandle = null;
        this.activePermit = null;
        this.publicationBoundaryCrossed = false;
        this.quarantineReason = "";
        this.phase = FactoryPhase.MOJANG_FALLBACK_REQUIRED;
        this.epoch = increment(this.epoch);
        return new CreationResult(
            CreationOutcome.MOJANG_FALLBACK_REQUIRED,
            null,
            reason
        );
    }

    /**
     * Retires the current owner. Reload and world-switch may construct a new
     * owner only after complete cleanup. A quarantined factory remains
     * quarantined even after resources have been physically released.
     */
    public synchronized RetirementResult retire(
        OwnerHandle handle,
        RetirementReason reason
    ) {
        Objects.requireNonNull(reason, "reason");
        if (reason == RetirementReason.ABORT_BEFORE_PUBLISH) {
            throw new IllegalArgumentException(
                "pre-publication abort is factory-owned"
            );
        }
        if (
            this.phase != FactoryPhase.ACTIVE
                && this.phase != FactoryPhase.QUARANTINED
                && this.phase
                    != FactoryPhase.RETIREMENT_CLEANUP_PENDING
        ) {
            throw new IllegalStateException(
                "owner retirement is unavailable in " + this.phase
            );
        }
        if (
            this.phase
                    == FactoryPhase.RETIREMENT_CLEANUP_PENDING
                && reason != this.pendingRetirementReason
        ) {
            throw new IllegalArgumentException(
                "retirement cleanup must retain its original reason"
            );
        }
        requireHandle(handle);
        boolean wasQuarantined =
            this.phase == FactoryPhase.QUARANTINED;
        String retainedQuarantineReason = this.quarantineReason;
        this.quarantineReason = "";
        this.phase = FactoryPhase.RETIRING;

        CreationCleanupAttestation cleanup;
        try {
            cleanup = normalizeCleanup(this.owner.retire(reason));
        } catch (Throwable failure) {
            cleanup = dirtyCleanup();
            retainedQuarantineReason =
                "retirement-uncertain:"
                    + failure.getClass().getSimpleName();
        }

        if (!cleanup.complete()) {
            String pendingReason =
                retainedQuarantineReason.isEmpty()
                ? "retirement-cleanup-incomplete"
                : retainedQuarantineReason;
            if (wasQuarantined) {
                ensurePublishedSelectorQuarantine(pendingReason);
                this.phase = FactoryPhase.QUARANTINED;
            } else {
                this.phase =
                    FactoryPhase.RETIREMENT_CLEANUP_PENDING;
                this.pendingRetirementReason = reason;
            }
            this.quarantineReason = pendingReason;
            return new RetirementResult(
                RetirementOutcome.CLEANUP_RETRY_REQUIRED,
                cleanup,
                this.quarantineReason
            );
        }

        OwnerGeneration retiringGeneration =
            this.ownerHandle.generation;
        boolean selectorRetired = wasQuarantined
            ? retireQuarantinedSelector(cleanup)
            : reason == RetirementReason.RELOAD
                    || reason == RetirementReason.WORLD_SWITCH
                ? retireSelectorForRevalidation()
                : retireSelectorIfActive();
        this.owner = null;
        this.ownerHandle = null;
        this.activePermit = null;
        this.pendingRetirementReason = null;
        this.publicationBoundaryCrossed = false;
        if (wasQuarantined || !selectorRetired) {
            this.phase = FactoryPhase.QUARANTINED;
            this.quarantineReason = retainedQuarantineReason.isEmpty()
                ? "selector-retirement-unavailable"
                : retainedQuarantineReason;
            return new RetirementResult(
                RetirementOutcome.RETIRED_BUT_QUARANTINED,
                cleanup,
                this.quarantineReason
            );
        }

        this.quarantineReason = "";
        if (reason == RetirementReason.CLOSE) {
            this.phase = FactoryPhase.CLOSED;
        } else {
            this.phase =
                FactoryPhase.AWAITING_WORLD_REVALIDATION;
            this.pendingRevalidationReason = reason;
            this.retiredGenerations =
                retiringGeneration.plannedGenerations();
        }
        this.epoch = increment(this.epoch);
        return new RetirementResult(
            RetirementOutcome.RETIRED,
            cleanup,
            ""
        );
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.phase,
            this.lastOwnerSerial,
            this.owner != null,
            this.publicationBoundaryCrossed,
            this.quarantineReason
        );
    }

    @Override
    public synchronized void close() {
        if (this.phase == FactoryPhase.CLOSED) {
            return;
        }
        if (
            this.phase
                == FactoryPhase.PRE_PUBLISH_CLEANUP_PENDING
        ) {
            CreationResult cleanup =
                retryPrePublicationCleanup(this.ownerHandle);
            if (
                cleanup.outcome()
                    == CreationOutcome
                        .PRE_PUBLISH_CLEANUP_RETRY_REQUIRED
            ) {
                throw new IllegalStateException(
                    "pre-publication owner still requires cleanup"
                );
            }
            if (
                cleanup.outcome()
                    == CreationOutcome.FAIL_CLOSED_QUARANTINED
            ) {
                throw new IllegalStateException(
                    "pre-publication cleanup entered quarantine: "
                        + cleanup.reason()
                );
            }
        }
        if (
            (this.phase == FactoryPhase.ACTIVE
                || this.phase == FactoryPhase.QUARANTINED
                || this.phase
                    == FactoryPhase.RETIREMENT_CLEANUP_PENDING)
                && this.owner != null
        ) {
            RetirementReason closeReason =
                this.phase
                        == FactoryPhase.RETIREMENT_CLEANUP_PENDING
                    ? this.pendingRetirementReason
                    : RetirementReason.CLOSE;
            RetirementResult result = retire(
                this.ownerHandle,
                closeReason
            );
            if (
                result.outcome()
                    == RetirementOutcome.CLEANUP_RETRY_REQUIRED
            ) {
                throw new IllegalStateException(
                    "exclusive native owner did not close cleanly: "
                        + result.reason()
                );
            }
            if (
                result.outcome()
                    == RetirementOutcome
                        .RETIRED_BUT_QUARANTINED
            ) {
                this.phase = FactoryPhase.CLOSED;
                this.quarantineReason = "";
                this.epoch = increment(this.epoch);
            }
            if (
                this.phase
                    == FactoryPhase.AWAITING_WORLD_REVALIDATION
            ) {
                this.phase = FactoryPhase.CLOSED;
                this.pendingRevalidationReason = null;
                this.retiredGenerations = null;
                this.epoch = increment(this.epoch);
            }
            return;
        }
        if (
            this.phase == FactoryPhase.QUARANTINED
                && this.owner == null
        ) {
            if (this.selector.phase() != Phase.QUARANTINED) {
                throw new IllegalStateException(
                    "quarantined ownership is not fully retired"
                );
            }
            this.phase = FactoryPhase.CLOSED;
            this.quarantineReason = "";
            this.epoch = increment(this.epoch);
            return;
        }
        if (
            this.phase == FactoryPhase.READY
                || this.phase
                    == FactoryPhase.AWAITING_WORLD_REVALIDATION
                || this.phase
                    == FactoryPhase.MOJANG_FALLBACK_REQUIRED
        ) {
            this.phase = FactoryPhase.CLOSED;
            this.pendingRevalidationReason = null;
            this.retiredGenerations = null;
            this.epoch = increment(this.epoch);
            return;
        }
        throw new IllegalStateException(
            "exclusive native factory cannot close in " + this.phase
        );
    }

    private CreationResult abortConstructedOwnerBeforePublish(
        String reason
    ) {
        CreationCleanupAttestation cleanup;
        try {
            cleanup = normalizeCleanup(
                this.owner.retire(
                    RetirementReason.ABORT_BEFORE_PUBLISH
                )
            );
        } catch (Throwable failure) {
            cleanup = dirtyCleanup();
            reason =
                reason
                    + ";cleanup-uncertain:"
                    + failure.getClass().getSimpleName();
        }
        return abortBeforePublish(cleanup, reason);
    }

    private CreationResult abortBeforePublish(
        CreationCleanupAttestation cleanup,
        String reason
    ) {
        CreationCleanupAttestation normalized =
            normalizeCleanup(cleanup);
        CreationFailureAction action;
        try {
            action = this.selector.abortWorldResourceCreation(
                this.activePermit.selectorPermit,
                normalized
            );
        } catch (Throwable failure) {
            this.phase = FactoryPhase.QUARANTINED;
            this.quarantineReason =
                reason
                    + ";selector-abort:"
                    + failure.getClass().getSimpleName();
            return new CreationResult(
                CreationOutcome.FAIL_CLOSED_QUARANTINED,
                this.ownerHandle,
                this.quarantineReason
            );
        }

        this.publicationBoundaryCrossed = false;
        if (
            action
                == CreationFailureAction
                    .REBUILD_MOJANG_BEFORE_WORLD_ENTRY
        ) {
            this.owner = null;
            this.ownerHandle = null;
            this.activePermit = null;
            this.phase = FactoryPhase.MOJANG_FALLBACK_REQUIRED;
            this.quarantineReason = "";
            this.epoch = increment(this.epoch);
            return new CreationResult(
                CreationOutcome.MOJANG_FALLBACK_REQUIRED,
                null,
                reason
            );
        }

        if (this.owner != null && this.ownerHandle != null) {
            this.phase =
                FactoryPhase.PRE_PUBLISH_CLEANUP_PENDING;
            this.quarantineReason = reason;
            return new CreationResult(
                CreationOutcome
                    .PRE_PUBLISH_CLEANUP_RETRY_REQUIRED,
                this.ownerHandle,
                reason
            );
        }

        this.phase = FactoryPhase.QUARANTINED;
        this.quarantineReason = reason;
        return new CreationResult(
            CreationOutcome.FAIL_CLOSED_QUARANTINED,
            this.ownerHandle,
            reason
        );
    }

    private CreationResult quarantineAfterPublicationBoundary(
        String reason
    ) {
        this.phase = FactoryPhase.QUARANTINED;
        this.quarantineReason = reason;
        try {
            this.selector.quarantinePublishedWorldResources(
                this.activePermit.selectorPermit,
                reason
            );
        } catch (Throwable failure) {
            this.quarantineReason +=
                ";selector-quarantine:"
                    + failure.getClass().getSimpleName();
        }
        return new CreationResult(
            CreationOutcome.FAIL_CLOSED_QUARANTINED,
            this.ownerHandle,
            this.quarantineReason
        );
    }

    private boolean retireSelectorIfActive() {
        if (this.selector.phase() != Phase.WORLD_RESOURCES_ACTIVE) {
            return false;
        }
        try {
            this.selector.completeWorldResourceRetirement();
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private boolean retireSelectorForRevalidation() {
        if (this.selector.phase() != Phase.WORLD_RESOURCES_ACTIVE) {
            return false;
        }
        try {
            this.selector
                .completeWorldResourceRetirementForRevalidation(
                    this.activePermit.selectorPermit
                );
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private boolean retireQuarantinedSelector(
        CreationCleanupAttestation cleanup
    ) {
        if (
            this.selector.phase()
                != Phase.WORLD_RESOURCES_QUARANTINED
        ) {
            return false;
        }
        try {
            this.selector
                .completeQuarantinedWorldResourceRetirement(
                    this.activePermit.selectorPermit,
                    cleanup
                );
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private void ensurePublishedSelectorQuarantine(String reason) {
        if (this.selector.phase() == Phase.WORLD_RESOURCES_QUARANTINED) {
            return;
        }
        try {
            this.selector.quarantinePublishedWorldResources(
                this.activePermit.selectorPermit,
                reason
            );
        } catch (Throwable ignored) {
            // Local quarantine remains authoritative if selector signaling
            // itself is unavailable.
        }
    }

    private void requireFreshGenerations(GenerationStamp fresh) {
        GenerationStamp supplied = Objects.requireNonNull(
            fresh,
            "freshGenerations"
        );
        if (this.retiredGenerations == null) {
            throw new IllegalStateException(
                "no retired owner awaits revalidation"
            );
        }
        if (supplied.equals(this.retiredGenerations)) {
            throw new IllegalArgumentException(
                "revalidation reused retired generations"
            );
        }
        if (
            this.pendingRevalidationReason
                    == RetirementReason.RELOAD
                && supplied.resources()
                    == this.retiredGenerations.resources()
        ) {
            throw new IllegalArgumentException(
                "reload requires a fresh resource generation"
            );
        }
        if (
            this.pendingRevalidationReason
                    == RetirementReason.WORLD_SWITCH
                && supplied.world()
                    == this.retiredGenerations.world()
        ) {
            throw new IllegalArgumentException(
                "world switch requires a fresh world generation"
            );
        }
    }

    private void installOwner(
        WorldResourceOwner installed,
        OwnerGeneration generation
    ) {
        Objects.requireNonNull(generation, "generation");
        retainCleanupOwnerForRetry(installed, generation);
    }

    /**
     * Retains sole physical cleanup ownership without claiming that an
     * unverified generation belongs to the active factory permit.
     */
    private void retainCleanupOwnerForRetry(
        WorldResourceOwner retained,
        OwnerGeneration actualGeneration
    ) {
        if (this.owner != null || this.ownerHandle != null) {
            throw new IllegalStateException(
                "exclusive native owner already exists"
            );
        }
        this.owner = Objects.requireNonNull(retained, "retained");
        this.ownerHandle = new OwnerHandle(
            this,
            this.epoch,
            actualGeneration
        );
    }

    private CreationCleanupAttestation normalizeCleanup(
        CreationCleanupAttestation cleanup
    ) {
        if (
            cleanup == null
                || cleanup.deviceGeneration()
                    != plannedDeviceGeneration()
        ) {
            return dirtyCleanup();
        }
        return cleanup;
    }

    private CreationCleanupAttestation dirtyCleanup() {
        return new CreationCleanupAttestation(
            plannedDeviceGeneration(),
            0L,
            0L,
            1,
            false
        );
    }

    private long plannedDeviceGeneration() {
        if (this.activePermit != null) {
            return this.activePermit.generation
                .plannedGenerations()
                .device();
        }
        if (this.ownerHandle != null) {
            return this.ownerHandle.generation
                .plannedGenerations()
                .device();
        }
        throw new IllegalStateException(
            "no native owner generation is active"
        );
    }

    private void requirePermit(CreationPermit permit) {
        requirePhase(FactoryPhase.CREATING);
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || permit != this.activePermit
                || permit.epoch != this.epoch
                || permit.consumed
        ) {
            throw new IllegalArgumentException(
                "stale, foreign, or consumed creation permit"
            );
        }
        if (this.owner != null || this.ownerHandle != null) {
            throw new IllegalStateException(
                "exclusive native owner already exists"
            );
        }
    }

    private void requireHandle(OwnerHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (
            handle.owner != this
                || handle != this.ownerHandle
                || handle.epoch != this.epoch
                || this.owner == null
        ) {
            throw new IllegalArgumentException(
                "stale or foreign native owner handle"
            );
        }
    }

    private void requirePhase(FactoryPhase expected) {
        if (this.phase != expected) {
            throw new IllegalStateException(
                "expected " + expected + " but was " + this.phase
            );
        }
    }

    private static long increment(long value) {
        return Math.addExact(value, 1L);
    }
}
