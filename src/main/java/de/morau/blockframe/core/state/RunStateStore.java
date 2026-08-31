package de.morau.blockframe.core.state;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Process-lifetime owner of BlockFrame's bounded two-slot run state.
 *
 * <p>The store writes only when explicitly called at a lifecycle transition.
 * Snapshot access is a volatile read and performs no filesystem or renderer
 * query. Persistence failures disable later writes and never block renderer
 * startup.</p>
 */
public final class RunStateStore implements AutoCloseable {
    public static final String SLOT_A_FILE = "run-state-a.bfrs";
    public static final String SLOT_B_FILE = "run-state-b.bfrs";
    public static final String TEMP_A_FILE = "run-state-a.tmp";
    public static final String TEMP_B_FILE = "run-state-b.tmp";
    public static final String LOCK_FILE = "run-state.lock";

    private final Path directory;
    private RunStateIdentity identity;
    private final RunStateIo io;
    private final Supplier<UUID> uuidSupplier;
    private final Slot slotA;
    private final Slot slotB;
    private final Path lockFile;

    private volatile RunStateRecord snapshot;
    private volatile RunStatePersistenceStatus persistenceStatus;
    private volatile RunStatePublicationMode publicationMode;
    private RunStateIo.LockHandle processLock;
    private Slot nextSlot;
    private boolean closed;

    private RunStateStore(
        Path directory,
        RunStateIdentity identity,
        RunStateIo io,
        Supplier<UUID> uuidSupplier
    ) {
        this.directory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        this.identity = Objects.requireNonNull(identity, "identity");
        this.io = Objects.requireNonNull(io, "io");
        this.uuidSupplier = Objects.requireNonNull(
            uuidSupplier,
            "uuidSupplier"
        );
        this.slotA = new Slot(
            this.directory.resolve(SLOT_A_FILE),
            this.directory.resolve(TEMP_A_FILE)
        );
        this.slotB = new Slot(
            this.directory.resolve(SLOT_B_FILE),
            this.directory.resolve(TEMP_B_FILE)
        );
        this.lockFile = this.directory.resolve(LOCK_FILE);
        this.persistenceStatus =
            RunStatePersistenceStatus.READ_ONLY_IO_FAILURE;
        this.publicationMode = RunStatePublicationMode.NONE;
    }

    /**
     * Opens the lifecycle store. Every filesystem/lock/format failure returns a
     * usable read-only in-memory store rather than failing renderer startup.
     */
    public static RunStateStore open(
        Path directory,
        RunStateIdentity request
    ) {
        return open(directory, request, new NioRunStateIo(), UUID::randomUUID);
    }

    static RunStateStore open(
        Path directory,
        RunStateIdentity request,
        RunStateIo io,
        Supplier<UUID> uuidSupplier
    ) {
        RunStateStore store = new RunStateStore(
            directory,
            request,
            io,
            uuidSupplier
        );
        store.initialize();
        return store;
    }

    /** Cached immutable state; this method never performs I/O. */
    public RunStateRecord snapshot() {
        return this.snapshot;
    }

    public RunStatePersistenceStatus persistenceStatus() {
        return this.persistenceStatus;
    }

    public RunStatePublicationMode publicationMode() {
        return this.publicationMode;
    }

    public boolean safeStartActive() {
        RunStateRecord state = this.snapshot;
        return state != null && state.safeStart().active();
    }

    /**
     * Whether a UI may show the current event. Reading this flag is side-effect
     * free; {@link #offerSafeStart()} durably consumes the one-off offer.
     */
    public boolean safeStartOfferAvailable() {
        RunStateRecord state = this.snapshot;
        return state != null
            && this.persistenceStatus == RunStatePersistenceStatus.READ_WRITE
            && state.safeStart().hasOffer()
            && !state.safeStart().isDeclined();
    }

    /**
     * Durably marks the offer as displayed. It returns {@code true} exactly
     * once per persisted candidate event.
     */
    public synchronized boolean offerSafeStart() {
        if (!this.canPersistSafeDecision()) {
            return false;
        }
        RunStateRecord.SafeStartState safe = this.snapshot.safeStart();
        UUID candidate = safe.candidateEvent();
        if (
            candidate == null
                || candidate.equals(safe.offeredEvent())
                || candidate.equals(safe.declinedEvent())
        ) {
            return false;
        }
        RunStateRecord.SafeStartState offered =
            new RunStateRecord.SafeStartState(
                candidate,
                candidate,
                safe.declinedEvent(),
                safe.queuedEvent(),
                safe.consumedEvent(),
                safe.active()
            );
        return this.persistRequired(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                this.snapshot.effectiveFeatureMask(),
                this.snapshot.phase(),
                this.snapshot.checkpoint(),
                this.snapshot.cleanShutdown(),
                this.snapshot.currentError(),
                this.snapshot.currentErrorContext(),
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                offered
            )
        );
    }

    /**
     * Explicit rejection changes no user configuration and prevents later
     * acceptance of this event in the same/persisted decision history.
     */
    public synchronized boolean declineSafeStart() {
        if (!this.canPersistSafeDecision()) {
            return false;
        }
        RunStateRecord.SafeStartState safe = this.snapshot.safeStart();
        UUID candidate = safe.candidateEvent();
        if (
            candidate == null
                || !candidate.equals(safe.offeredEvent())
                || candidate.equals(safe.declinedEvent())
                || candidate.equals(safe.queuedEvent())
        ) {
            return false;
        }
        RunStateRecord.SafeStartState declined =
            new RunStateRecord.SafeStartState(
                candidate,
                safe.offeredEvent(),
                candidate,
                safe.queuedEvent(),
                safe.consumedEvent(),
                safe.active()
            );
        return this.persistRequired(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                this.snapshot.effectiveFeatureMask(),
                this.snapshot.phase(),
                this.snapshot.checkpoint(),
                this.snapshot.cleanShutdown(),
                this.snapshot.currentError(),
                this.snapshot.currentErrorContext(),
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                declined
            )
        );
    }

    /**
     * Explicitly queues an offered event for the next process. It never writes
     * normal configuration and does not change the current renderer.
     */
    public synchronized boolean queueSafeStartForNextRun() {
        if (!this.canPersistSafeDecision()) {
            return false;
        }
        RunStateRecord.SafeStartState safe = this.snapshot.safeStart();
        UUID candidate = safe.candidateEvent();
        if (
            candidate == null
                || !candidate.equals(safe.offeredEvent())
                || candidate.equals(safe.declinedEvent())
                || candidate.equals(safe.queuedEvent())
        ) {
            return false;
        }
        RunStateRecord.SafeStartState queued =
            new RunStateRecord.SafeStartState(
                candidate,
                safe.offeredEvent(),
                safe.declinedEvent(),
                candidate,
                safe.consumedEvent(),
                false
            );
        return this.persistRequired(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                this.snapshot.effectiveFeatureMask(),
                this.snapshot.phase(),
                this.snapshot.checkpoint(),
                this.snapshot.cleanShutdown(),
                this.snapshot.currentError(),
                this.snapshot.currentErrorContext(),
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                queued
            )
        );
    }

    /**
     * Activates Safe Start only while still STARTING and only after its
     * consumption marker is durably published.
     */
    public synchronized boolean activateSafeStartForCurrentRun() {
        if (
            !this.canPersistSafeDecision()
                || this.snapshot.phase() != RunPhase.STARTING
        ) {
            return false;
        }
        RunStateRecord.SafeStartState safe = this.snapshot.safeStart();
        UUID candidate = safe.candidateEvent();
        if (
            candidate == null
                || !candidate.equals(safe.offeredEvent())
                || candidate.equals(safe.declinedEvent())
                || safe.active()
        ) {
            return false;
        }
        RunStateRecord.SafeStartState active =
            new RunStateRecord.SafeStartState(
                candidate,
                safe.offeredEvent(),
                safe.declinedEvent(),
                candidate,
                candidate,
                true
            );
        return this.persistRequired(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                this.identity.safeStartEffectiveFeatureMask(),
                this.snapshot.phase(),
                this.snapshot.checkpoint(),
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                active
            )
        );
    }

    /**
     * Rebases the active client run after a semantic live-configuration
     * change. This is a lifecycle transition, never a warm-frame write.
     *
     * <p>The current run identity and generation stay unchanged. Persisted
     * evidence from earlier attempts remains attached, while the current
     * attempt starts a fresh stability window under the replacement
     * fingerprint and requested mask. The in-memory identity is switched
     * before publication is attempted so a failed write can only disable
     * persistence; no later transition can write the superseded identity.</p>
     */
    public synchronized boolean rebaseIdentity(
        RunStateIdentity replacement,
        long effectiveFeatureMask
    ) {
        RunStateIdentity next = Objects.requireNonNull(
            replacement,
            "replacement"
        );
        validateEffectiveMask(effectiveFeatureMask, next);
        if (
            this.closed
                || this.snapshot == null
                || this.snapshot.phase() == RunPhase.FAILED
                || this.snapshot.cleanShutdown()
        ) {
            return false;
        }
        if (
            !this.snapshot.modVersion().equals(next.modVersion())
                || !this.snapshot.minecraftVersion().equals(
                    next.minecraftVersion()
                )
                || this.snapshot.featureSchemaVersion()
                    != next.featureSchemaVersion()
        ) {
            throw new IllegalArgumentException(
                "a live rebase may only change configuration identity"
            );
        }
        if (this.snapshot.identityMatches(next)) {
            this.identity = next;
            return false;
        }

        long nextGeneration = nextCommitGeneration(this.snapshot);
        RunStateRecord candidate = rebasedRecord(
            this.snapshot,
            next,
            nextGeneration < 0L
                ? this.snapshot.commitGeneration()
                : nextGeneration,
            effectiveFeatureMask
        );
        this.identity = next;
        if (nextGeneration < 0L) {
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_GENERATION_OVERFLOW
            );
            this.snapshot = candidate;
            return false;
        }

        boolean persisted = false;
        if (
            this.persistenceStatus
                == RunStatePersistenceStatus.READ_WRITE
        ) {
            persisted = this.writeRecord(candidate);
        }
        this.snapshot = candidate;
        return persisted;
    }

    public synchronized boolean markInitializing(
        RunBackend backend,
        long effectiveFeatureMask
    ) {
        Objects.requireNonNull(backend, "backend");
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        if (
            backend == RunBackend.UNKNOWN
                || this.snapshot.phase() != RunPhase.STARTING
        ) {
            return false;
        }
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                backend,
                effectiveFeatureMask,
                RunPhase.INITIALIZING,
                RunCheckpoint.BACKEND_INITIALIZED,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            )
        );
    }

    /**
     * Starts a new device/backend stability window within the same client
     * run. A prior LKG remains intact until the new generation independently
     * reaches STABILITY_WINDOW_COMPLETE.
     */
    public synchronized boolean markDeviceReinitializing(
        RunBackend backend,
        long effectiveFeatureMask
    ) {
        Objects.requireNonNull(backend, "backend");
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        RunPhase currentPhase = this.snapshot.phase();
        if (
            backend == RunBackend.UNKNOWN
                || currentPhase == RunPhase.STARTING
                || currentPhase == RunPhase.FAILED
                || currentPhase == RunPhase.CLEAN_SHUTDOWN
                || this.snapshot.cleanShutdown()
        ) {
            return false;
        }
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                backend,
                effectiveFeatureMask,
                RunPhase.INITIALIZING,
                RunCheckpoint.BACKEND_INITIALIZED,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            )
        );
    }

    public synchronized boolean markActiveFeaturesPublished(
        long effectiveFeatureMask
    ) {
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        if (
            this.snapshot.phase() != RunPhase.INITIALIZING
                || this.snapshot.checkpoint()
                    != RunCheckpoint.BACKEND_INITIALIZED
        ) {
            return false;
        }
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                effectiveFeatureMask,
                RunPhase.INITIALIZING,
                RunCheckpoint.ACTIVE_FEATURES_PUBLISHED,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            )
        );
    }

    /**
     * Restarts only world-frame validation after a real resource or world
     * lifecycle boundary. Backend and feature publication remain valid, while
     * the prior LKG is retained until a new complete stability window.
     */
    public synchronized boolean markStabilityRevalidating(
        long effectiveFeatureMask
    ) {
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        if (
            this.snapshot.backend() == RunBackend.UNKNOWN
                || this.snapshot.phase() == RunPhase.STARTING
                || this.snapshot.phase() == RunPhase.FAILED
                || this.snapshot.phase() == RunPhase.CLEAN_SHUTDOWN
                || this.snapshot.cleanShutdown()
                || (
                    this.snapshot.phase() == RunPhase.INITIALIZING
                        && this.snapshot.checkpoint()
                            == RunCheckpoint.ACTIVE_FEATURES_PUBLISHED
                )
        ) {
            return false;
        }
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                effectiveFeatureMask,
                RunPhase.INITIALIZING,
                RunCheckpoint.ACTIVE_FEATURES_PUBLISHED,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            )
        );
    }

    public synchronized boolean markFirstWorldFrame(
        long effectiveFeatureMask
    ) {
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        if (
            this.snapshot.phase() != RunPhase.INITIALIZING
                || this.snapshot.checkpoint()
                    != RunCheckpoint.ACTIVE_FEATURES_PUBLISHED
        ) {
            return false;
        }
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                effectiveFeatureMask,
                RunPhase.INITIALIZING,
                RunCheckpoint.FIRST_WORLD_FRAME,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            )
        );
    }

    /**
     * Promotes LKG only after the caller's bounded stability window. A Safe
     * Start run can become STABLE but never replaces the normal-run LKG.
     */
    public synchronized boolean markStable(long effectiveFeatureMask) {
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        if (
            this.snapshot.phase() != RunPhase.INITIALIZING
                || this.snapshot.checkpoint()
                    != RunCheckpoint.FIRST_WORLD_FRAME
        ) {
            return false;
        }
        return this.lifecycleTransition(generation -> {
            RunStateRecord.LastKnownGood lkg =
                this.snapshot.lastKnownGood();
            if (!this.snapshot.safeStart().active()) {
                lkg = new RunStateRecord.LastKnownGood(
                    this.snapshot.runId(),
                    this.snapshot.runGeneration(),
                    generation,
                    this.snapshot.modVersion(),
                    this.snapshot.minecraftVersion(),
                    this.snapshot.backend(),
                    this.snapshot.configFingerprint(),
                    this.snapshot.featureSchemaVersion(),
                    this.snapshot.requestedFeatureMask(),
                    effectiveFeatureMask,
                    RunCheckpoint.STABILITY_WINDOW_COMPLETE
                );
            }
            return copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                effectiveFeatureMask,
                RunPhase.STABLE,
                RunCheckpoint.STABILITY_WINDOW_COMPLETE,
                false,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                lkg,
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            );
        });
    }

    public synchronized boolean markFailed(
        ConfirmedRunError error,
        String stableContextCode,
        long effectiveFeatureMask
    ) {
        Objects.requireNonNull(error, "error");
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        String context = RunStateRecord.context(stableContextCode);
        if (
            error == ConfirmedRunError.NONE
                || this.snapshot.phase() == RunPhase.CLEAN_SHUTDOWN
                || this.snapshot.cleanShutdown()
        ) {
            return false;
        }
        if (
            this.snapshot.phase() == RunPhase.FAILED
                && this.snapshot.currentError() == error
                && this.snapshot.currentErrorContext().equals(context)
        ) {
            return false;
        }
        RunStateRecord.ConfirmedFailure failure =
            new RunStateRecord.ConfirmedFailure(
                this.snapshot.runId(),
                this.snapshot.runGeneration(),
                error,
                context
            );
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                effectiveFeatureMask,
                RunPhase.FAILED,
                RunCheckpoint.FAILURE_RECORDED,
                false,
                error,
                context,
                this.snapshot.lastKnownGood(),
                failure,
                this.snapshot.safeStart()
            )
        );
    }

    /**
     * Marks only a real client lifecycle close. A FAILED run keeps FAILED and
     * its confirmed error while gaining the independent clean marker.
     */
    public synchronized boolean markCleanShutdown(
        long effectiveFeatureMask
    ) {
        validateEffectiveMask(effectiveFeatureMask, this.identity);
        if (this.snapshot.cleanShutdown()) {
            return false;
        }
        if (this.snapshot.phase() == RunPhase.FAILED) {
            return this.lifecycleTransition(
                generation -> copy(
                    this.snapshot,
                    generation,
                    this.snapshot.backend(),
                    effectiveFeatureMask,
                    RunPhase.FAILED,
                    RunCheckpoint.CLIENT_SHUTDOWN,
                    true,
                    this.snapshot.currentError(),
                    this.snapshot.currentErrorContext(),
                    this.snapshot.lastKnownGood(),
                    this.snapshot.lastConfirmedFailure(),
                    this.snapshot.safeStart()
                )
            );
        }
        return this.lifecycleTransition(
            generation -> copy(
                this.snapshot,
                generation,
                this.snapshot.backend(),
                effectiveFeatureMask,
                RunPhase.CLEAN_SHUTDOWN,
                RunCheckpoint.CLIENT_SHUTDOWN,
                true,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT,
                this.snapshot.lastKnownGood(),
                this.snapshot.lastConfirmedFailure(),
                this.snapshot.safeStart()
            )
        );
    }

    /**
     * Releases only the persistence lock. It deliberately never writes a
     * clean marker; callers must use the real client lifecycle method above.
     */
    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.releaseProcessLock();
        this.persistenceStatus = RunStatePersistenceStatus.CLOSED;
    }

    private void initialize() {
        UUID currentRunId = this.nextUuid();
        try {
            this.io.ensureDirectory(this.directory);
            this.processLock = this.io.tryAcquire(this.lockFile);
        } catch (IOException | RuntimeException failure) {
            this.snapshot = freshRecord(
                this.identity,
                currentRunId,
                1L,
                1L,
                null,
                null,
                null,
                RunStateRecord.SafeStartState.empty(),
                false
            );
            this.persistenceStatus =
                RunStatePersistenceStatus.READ_ONLY_IO_FAILURE;
            this.releaseProcessLock();
            return;
        }

        if (this.processLock == null) {
            // Deliberately do not inspect slots without exclusive ownership.
            this.snapshot = freshRecord(
                this.identity,
                currentRunId,
                1L,
                1L,
                null,
                null,
                null,
                RunStateRecord.SafeStartState.empty(),
                false
            );
            this.persistenceStatus =
                RunStatePersistenceStatus.READ_ONLY_LOCK_CONFLICT;
            return;
        }

        SlotRead first;
        SlotRead second;
        try {
            first = this.readSlot(this.slotA);
            second = this.readSlot(this.slotB);
        } catch (IOException | RuntimeException failure) {
            this.snapshot = freshRecord(
                this.identity,
                currentRunId,
                1L,
                1L,
                null,
                null,
                null,
                RunStateRecord.SafeStartState.empty(),
                false
            );
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_IO_FAILURE
            );
            return;
        }

        if (first.kind == SlotKind.FUTURE || second.kind == SlotKind.FUTURE) {
            this.snapshot = readOnlyRecord(
                this.identity,
                currentRunId,
                newestValid(first, second)
            );
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_FUTURE_FORMAT
            );
            return;
        }

        RunStateRecord latest = newestValid(first, second);
        if (latest == AMBIGUOUS) {
            this.snapshot = freshRecord(
                this.identity,
                currentRunId,
                1L,
                1L,
                null,
                null,
                null,
                RunStateRecord.SafeStartState.empty(),
                false
            );
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_AMBIGUOUS_GENERATION
            );
            return;
        }
        if (
            latest == null
                && (first.kind != SlotKind.ABSENT
                    || second.kind != SlotKind.ABSENT)
        ) {
            this.snapshot = freshRecord(
                this.identity,
                currentRunId,
                1L,
                1L,
                null,
                null,
                null,
                RunStateRecord.SafeStartState.empty(),
                false
            );
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_CORRUPT_STATE
            );
            return;
        }
        if (
            latest != null
                && (latest.runGeneration() == Long.MAX_VALUE
                    || latest.commitGeneration() == Long.MAX_VALUE)
        ) {
            this.snapshot = readOnlyRecord(
                this.identity,
                currentRunId,
                latest
            );
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_GENERATION_OVERFLOW
            );
            return;
        }

        long runGeneration =
            latest == null ? 1L : latest.runGeneration() + 1L;
        long commitGeneration =
            latest == null ? 1L : latest.commitGeneration() + 1L;
        this.nextSlot = selectTarget(first, second);
        this.persistenceStatus = RunStatePersistenceStatus.READ_WRITE;

        InitialState initial = buildInitialState(
            this.identity,
            currentRunId,
            runGeneration,
            commitGeneration,
            latest
        );
        this.snapshot = initial.record;
        boolean written = this.writeRecord(initial.record);
        if (!written && initial.autoSafeStart) {
            this.snapshot = initial.normalFallback;
        }
    }

    private SlotRead readSlot(Slot slot) throws IOException {
        byte[] content = this.io.readBounded(
            slot.path,
            RunStateCodec.MAX_BYTES
        );
        if (content == null) {
            return new SlotRead(slot, SlotKind.ABSENT, null);
        }
        try {
            return new SlotRead(
                slot,
                SlotKind.VALID,
                RunStateCodec.decode(content)
            );
        } catch (RunStateCodec.FutureFormatException future) {
            return new SlotRead(slot, SlotKind.FUTURE, null);
        } catch (RunStateCodec.InvalidFormatException invalid) {
            return new SlotRead(slot, SlotKind.INVALID, null);
        }
    }

    private synchronized boolean lifecycleTransition(
        LongFunction<RunStateRecord> transition
    ) {
        if (this.closed || this.snapshot == null) {
            return false;
        }
        long nextGeneration = nextCommitGeneration(this.snapshot);
        if (nextGeneration < 0L) {
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_GENERATION_OVERFLOW
            );
            return false;
        }
        RunStateRecord candidate = transition.apply(nextGeneration);
        boolean persisted = false;
        if (
            this.persistenceStatus
                == RunStatePersistenceStatus.READ_WRITE
        ) {
            persisted = this.writeRecord(candidate);
        }
        this.snapshot = candidate;
        return persisted;
    }

    private boolean persistRequired(
        LongFunction<RunStateRecord> transition
    ) {
        if (
            this.closed
                || this.persistenceStatus
                    != RunStatePersistenceStatus.READ_WRITE
        ) {
            return false;
        }
        long nextGeneration = nextCommitGeneration(this.snapshot);
        if (nextGeneration < 0L) {
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_GENERATION_OVERFLOW
            );
            return false;
        }
        RunStateRecord candidate = transition.apply(nextGeneration);
        if (!this.writeRecord(candidate)) {
            return false;
        }
        this.snapshot = candidate;
        return true;
    }

    private boolean writeRecord(RunStateRecord record) {
        if (
            this.persistenceStatus
                != RunStatePersistenceStatus.READ_WRITE
                || this.processLock == null
                || this.nextSlot == null
        ) {
            return false;
        }
        Slot target = this.nextSlot;
        byte[] encoded;
        try {
            encoded = RunStateCodec.encode(record);
            this.io.deleteIfExists(target.temporary);
            this.io.writeForced(target.temporary, encoded);
            validateExact(this.io, target.temporary, encoded);
            try {
                this.io.atomicReplace(target.temporary, target.path);
                validateExact(this.io, target.path, encoded);
                this.publicationMode = RunStatePublicationMode.ATOMIC;
            } catch (
                AtomicMoveNotSupportedException atomicMoveNotSupported
            ) {
                // The chosen target is always the older/invalid slot. The
                // newest valid slot is therefore retained during this move.
                this.io.replace(target.temporary, target.path);
                validateExact(this.io, target.path, encoded);
                this.publicationMode =
                    RunStatePublicationMode.RECOVERABLE_TWO_SLOT;
            }
            this.nextSlot = target == this.slotA ? this.slotB : this.slotA;
            return true;
        } catch (IOException | RuntimeException failure) {
            // Provider runtime failures are fail-open. VM/linkage Errors are
            // deliberately not swallowed as ordinary persistence failures.
            try {
                this.io.deleteIfExists(target.temporary);
            } catch (IOException | RuntimeException ignored) {
                // Cleanup failure is part of the fail-open persistence state.
            }
            this.disablePersistence(
                RunStatePersistenceStatus.READ_ONLY_IO_FAILURE
            );
            return false;
        }
    }

    private boolean canPersistSafeDecision() {
        return !this.closed
            && this.snapshot != null
            && this.persistenceStatus == RunStatePersistenceStatus.READ_WRITE
            && !this.snapshot.cleanShutdown()
            && this.snapshot.phase() != RunPhase.FAILED;
    }

    private void disablePersistence(RunStatePersistenceStatus status) {
        this.persistenceStatus = status;
        this.releaseProcessLock();
    }

    private void releaseProcessLock() {
        RunStateIo.LockHandle held = this.processLock;
        this.processLock = null;
        if (held != null) {
            try {
                held.close();
            } catch (IOException | RuntimeException ignored) {
                // Releasing a failed persistence facility must remain fail-open.
            }
        }
    }

    private UUID nextUuid() {
        UUID value = this.uuidSupplier.get();
        if (value == null) {
            throw new IllegalStateException("UUID supplier returned null");
        }
        return value;
    }

    private static void validateExact(
        RunStateIo io,
        Path path,
        byte[] expected
    ) throws IOException {
        byte[] actual = io.readBounded(path, RunStateCodec.MAX_BYTES);
        if (
            actual == null
                || !Arrays.equals(expected, actual)
                || !RunStateCodec.decode(actual).equals(
                    RunStateCodec.decode(expected)
                )
        ) {
            throw new IOException("published run-state validation failed");
        }
    }

    private static RunStateRecord newestValid(
        SlotRead first,
        SlotRead second
    ) {
        RunStateRecord left =
            first.kind == SlotKind.VALID ? first.record : null;
        RunStateRecord right =
            second.kind == SlotKind.VALID ? second.record : null;
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        if (left.commitGeneration() == right.commitGeneration()) {
            return AMBIGUOUS;
        }
        RunStateRecord newest =
            left.commitGeneration() > right.commitGeneration()
                ? left
                : right;
        RunStateRecord older = newest == left ? right : left;
        if (
            newest.runGeneration() < older.runGeneration()
                || newest.runGeneration() == older.runGeneration()
                    && !newest.runId().equals(older.runId())
        ) {
            return AMBIGUOUS;
        }
        return newest;
    }

    private static Slot selectTarget(SlotRead first, SlotRead second) {
        if (first.kind != SlotKind.VALID) {
            return first.slot;
        }
        if (second.kind != SlotKind.VALID) {
            return second.slot;
        }
        return first.record.commitGeneration()
                < second.record.commitGeneration()
            ? first.slot
            : second.slot;
    }

    private static InitialState buildInitialState(
        RunStateIdentity identity,
        UUID runId,
        long runGeneration,
        long commitGeneration,
        RunStateRecord latest
    ) {
        RunStateRecord.PreviousRun previous = previousRun(latest);
        RunStateRecord.LastKnownGood lkg =
            latest == null ? null : latest.lastKnownGood();
        RunStateRecord.ConfirmedFailure failure =
            latest == null ? null : latest.lastConfirmedFailure();
        RunStateRecord.SafeStartState inherited =
            latest == null
                ? RunStateRecord.SafeStartState.empty()
                : latest.safeStart();

        UUID candidate = safeCandidate(latest, identity);
        boolean pendingOneShot = inherited.hasQueuedOneShot();
        if (pendingOneShot && candidate == null) {
            candidate = inherited.candidateEvent() == null
                ? inherited.queuedEvent()
                : inherited.candidateEvent();
        }

        RunStateRecord.SafeStartState normalSafe;
        if (
            candidate != null
                && candidate.equals(inherited.candidateEvent())
        ) {
            normalSafe = new RunStateRecord.SafeStartState(
                candidate,
                inherited.offeredEvent(),
                inherited.declinedEvent(),
                inherited.queuedEvent(),
                inherited.consumedEvent(),
                false
            );
        } else {
            normalSafe = new RunStateRecord.SafeStartState(
                candidate,
                null,
                null,
                inherited.queuedEvent(),
                inherited.consumedEvent(),
                false
            );
        }

        RunStateRecord normal = freshRecord(
            identity,
            runId,
            runGeneration,
            commitGeneration,
            previous,
            lkg,
            failure,
            normalSafe,
            false
        );
        if (!pendingOneShot) {
            return new InitialState(normal, normal, false);
        }

        UUID event = inherited.queuedEvent();
        UUID offered = event.equals(inherited.offeredEvent())
            ? event
            : null;
        RunStateRecord.SafeStartState consumed =
            new RunStateRecord.SafeStartState(
                event,
                offered,
                null,
                event,
                event,
                true
            );
        RunStateRecord active = freshRecord(
            identity,
            runId,
            runGeneration,
            commitGeneration,
            previous,
            lkg,
            failure,
            consumed,
            true
        );
        return new InitialState(active, normal, true);
    }

    private static RunStateRecord readOnlyRecord(
        RunStateIdentity identity,
        UUID runId,
        RunStateRecord latest
    ) {
        return freshRecord(
            identity,
            runId,
            1L,
            1L,
            previousRun(latest),
            latest == null ? null : latest.lastKnownGood(),
            latest == null ? null : latest.lastConfirmedFailure(),
            new RunStateRecord.SafeStartState(
                safeCandidate(latest, identity),
                null,
                null,
                null,
                null,
                false
            ),
            false
        );
    }

    private static RunStateRecord freshRecord(
        RunStateIdentity identity,
        UUID runId,
        long runGeneration,
        long commitGeneration,
        RunStateRecord.PreviousRun previous,
        RunStateRecord.LastKnownGood lkg,
        RunStateRecord.ConfirmedFailure failure,
        RunStateRecord.SafeStartState safe,
        boolean safeActive
    ) {
        return new RunStateRecord(
            RunStateRecord.CURRENT_SCHEMA_VERSION,
            RunStateRecord.CURRENT_WRITER_VERSION,
            runId,
            runGeneration,
            commitGeneration,
            identity.modVersion(),
            identity.minecraftVersion(),
            RunBackend.UNKNOWN,
            identity.configFingerprint(),
            identity.featureSchemaVersion(),
            identity.requestedFeatureMask(),
            safeActive
                ? identity.safeStartEffectiveFeatureMask()
                : identity.normalEffectiveFeatureMask(),
            RunPhase.STARTING,
            RunCheckpoint.PROCESS_STARTED,
            false,
            ConfirmedRunError.NONE,
            RunStateRecord.NO_CONTEXT,
            previous,
            lkg,
            failure,
            safe
        );
    }

    private static RunStateRecord.PreviousRun previousRun(
        RunStateRecord latest
    ) {
        if (latest == null) {
            return null;
        }
        if (latest.phase() == RunPhase.FAILED) {
            return new RunStateRecord.PreviousRun(
                latest.runId(),
                latest.runGeneration(),
                RunPhase.FAILED,
                latest.cleanShutdown(),
                latest.currentError(),
                latest.currentErrorContext()
            );
        }
        if (latest.cleanShutdown()) {
            return new RunStateRecord.PreviousRun(
                latest.runId(),
                latest.runGeneration(),
                RunPhase.CLEAN_SHUTDOWN,
                true,
                ConfirmedRunError.NONE,
                RunStateRecord.NO_CONTEXT
            );
        }
        return new RunStateRecord.PreviousRun(
            latest.runId(),
            latest.runGeneration(),
            RunPhase.UNCLEAN,
            false,
            ConfirmedRunError.NONE,
            RunStateRecord.NO_CONTEXT
        );
    }

    private static UUID safeCandidate(
        RunStateRecord latest,
        RunStateIdentity identity
    ) {
        if (latest == null || !latest.identityMatches(identity)) {
            return null;
        }
        if (latest.phase() == RunPhase.FAILED) {
            return latest.runId();
        }
        if (
            !latest.cleanShutdown()
                && latest.phase() != RunPhase.STABLE
        ) {
            return latest.runId();
        }
        return null;
    }

    private static RunStateRecord copy(
        RunStateRecord source,
        long commitGeneration,
        RunBackend backend,
        long effectiveFeatureMask,
        RunPhase phase,
        RunCheckpoint checkpoint,
        boolean cleanShutdown,
        ConfirmedRunError currentError,
        String currentErrorContext,
        RunStateRecord.LastKnownGood lkg,
        RunStateRecord.ConfirmedFailure failure,
        RunStateRecord.SafeStartState safe
    ) {
        return new RunStateRecord(
            source.schemaVersion(),
            source.writerVersion(),
            source.runId(),
            source.runGeneration(),
            commitGeneration,
            source.modVersion(),
            source.minecraftVersion(),
            backend,
            source.configFingerprint(),
            source.featureSchemaVersion(),
            source.requestedFeatureMask(),
            effectiveFeatureMask,
            phase,
            checkpoint,
            cleanShutdown,
            currentError,
            currentErrorContext,
            source.previousRun(),
            lkg,
            failure,
            safe
        );
    }

    private static RunStateRecord rebasedRecord(
        RunStateRecord source,
        RunStateIdentity identity,
        long commitGeneration,
        long effectiveFeatureMask
    ) {
        boolean initialized = source.backend() != RunBackend.UNKNOWN;
        return new RunStateRecord(
            source.schemaVersion(),
            source.writerVersion(),
            source.runId(),
            source.runGeneration(),
            commitGeneration,
            identity.modVersion(),
            identity.minecraftVersion(),
            source.backend(),
            identity.configFingerprint(),
            identity.featureSchemaVersion(),
            identity.requestedFeatureMask(),
            effectiveFeatureMask,
            initialized ? RunPhase.INITIALIZING : RunPhase.STARTING,
            initialized
                ? RunCheckpoint.ACTIVE_FEATURES_PUBLISHED
                : RunCheckpoint.PROCESS_STARTED,
            false,
            ConfirmedRunError.NONE,
            RunStateRecord.NO_CONTEXT,
            source.previousRun(),
            source.lastKnownGood(),
            source.lastConfirmedFailure(),
            source.safeStart()
        );
    }

    private static long nextCommitGeneration(RunStateRecord record) {
        return record.commitGeneration() == Long.MAX_VALUE
            ? -1L
            : record.commitGeneration() + 1L;
    }

    private static void validateEffectiveMask(
        long effectiveMask,
        RunStateIdentity identity
    ) {
        if ((effectiveMask & ~identity.requestedFeatureMask()) != 0L) {
            throw new IllegalArgumentException(
                "effective mask must be a subset of the requested mask"
            );
        }
    }

    private record Slot(Path path, Path temporary) {
    }

    private enum SlotKind {
        ABSENT,
        VALID,
        INVALID,
        FUTURE
    }

    private record SlotRead(
        Slot slot,
        SlotKind kind,
        RunStateRecord record
    ) {
    }

    private record InitialState(
        RunStateRecord record,
        RunStateRecord normalFallback,
        boolean autoSafeStart
    ) {
    }

    /*
     * Private identity sentinel returned only by newestValid. It is never
     * encoded or exposed as a real run.
     */
    private static final RunStateRecord AMBIGUOUS = new RunStateRecord(
        1,
        1,
        new UUID(0L, 0L),
        1L,
        1L,
        "ambiguous",
        "ambiguous",
        RunBackend.UNKNOWN,
        "0".repeat(64),
        1,
        0L,
        0L,
        RunPhase.STARTING,
        RunCheckpoint.PROCESS_STARTED,
        false,
        ConfirmedRunError.NONE,
        RunStateRecord.NO_CONTEXT,
        null,
        null,
        null,
        RunStateRecord.SafeStartState.empty()
    );
}
