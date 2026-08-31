package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import java.util.Objects;

/**
 * Single owner-bound lifecycle for one native terrain section generation.
 *
 * <p>This state machine replaces, rather than skips, Mojang upload callbacks.
 * Worker permits are invalid after every restart. No partial compile or upload
 * can reach {@link State#PUBLISHED}. Once the complete native backend is
 * active, global failure recovery deliberately pauses and rebuilds a backend;
 * it does not promise a same-frame Mojang replay.</p>
 */
public final class NativeTerrainSectionLifecycle {
    public enum State {
        SNAPSHOT,
        COMPILING,
        COMPILED,
        UPLOADING,
        PUBLISHED,
        ACTIVE,
        RETIRING,
        CLEANUP_RETRY,
        RETIRED,
        CANCELLED,
        QUARANTINED,
        CLOSED
    }

    public enum Cause {
        DIRTY,
        SECTION_REMOVED,
        RESOURCE_RELOAD,
        RESIZE,
        RENDERER_CHANGE,
        WORLD_CHANGE,
        DEVICE_CHANGE,
        SHUTDOWN,
        COMPILE_FAILURE,
        UPLOAD_FAILURE,
        BUDGET_FAILURE,
        BACKEND_FAILURE
    }

    public enum GlobalFailureAction {
        ABORT_NATIVE_START_AND_BUILD_MOJANG,
        PAUSE_QUARANTINE_AND_REBUILD_MOJANG_NO_SAME_FRAME_REPLAY
    }

    public static final class CompilationPermit {
        private final NativeTerrainSectionLifecycle owner;
        private final long epoch;
        private final GenerationStamp generations;

        private CompilationPermit(
            NativeTerrainSectionLifecycle owner,
            long epoch,
            GenerationStamp generations
        ) {
            this.owner = owner;
            this.epoch = epoch;
            this.generations = generations;
        }
    }

    public static final class UploadPermit {
        private final NativeTerrainSectionLifecycle owner;
        private final long epoch;
        private final GenerationStamp generations;

        private UploadPermit(
            NativeTerrainSectionLifecycle owner,
            long epoch,
            GenerationStamp generations
        ) {
            this.owner = owner;
            this.epoch = epoch;
            this.generations = generations;
        }
    }

    public static final class RetirementPermit {
        private final NativeTerrainSectionLifecycle owner;
        private final long epoch;
        private final int cleanupAttempt;
        private final long minimumCompletedSubmission;

        private RetirementPermit(
            NativeTerrainSectionLifecycle owner,
            long epoch,
            int cleanupAttempt,
            long minimumCompletedSubmission
        ) {
            this.owner = owner;
            this.epoch = epoch;
            this.cleanupAttempt = cleanupAttempt;
            this.minimumCompletedSubmission = minimumCompletedSubmission;
        }

        public long minimumCompletedSubmission() {
            return this.minimumCompletedSubmission;
        }

        public int cleanupAttempt() {
            return this.cleanupAttempt;
        }
    }

    /**
     * A non-null permit means native upload/geometry cleanup is mandatory.
     * Immediate transitions never manufacture a retirement proof.
     */
    public record CleanupDecision(
        State state,
        RetirementPermit retirementPermit
    ) {
        public CleanupDecision {
            Objects.requireNonNull(state, "state");
            if (
                (state == State.RETIRING)
                    != (retirementPermit != null)
            ) {
                throw new IllegalArgumentException(
                    "retirement state and permit disagree"
                );
            }
        }

        public boolean cleanupRequired() {
            return this.retirementPermit != null;
        }
    }

    private SectionIdentity section;
    private GenerationStamp generations;
    private State state = State.SNAPSHOT;
    private Cause lastCause;
    private long epoch = 1L;
    private long activationFrame;
    private long minimumCompletedSubmission;
    private int cleanupAttempt;
    private boolean everActive;
    private boolean quarantineAfterCleanup;
    private State terminalAfterCleanup;
    private SectionIdentity restartSection;
    private GenerationStamp restartGenerations;

    public NativeTerrainSectionLifecycle(
        SectionIdentity section,
        GenerationStamp generations
    ) {
        this.section = Objects.requireNonNull(section, "section");
        this.generations = Objects.requireNonNull(
            generations,
            "generations"
        );
    }

    public synchronized State state() {
        return this.state;
    }

    public synchronized SectionIdentity section() {
        return this.section;
    }

    public synchronized GenerationStamp generations() {
        return this.generations;
    }

    public synchronized Cause lastCause() {
        return this.lastCause;
    }

    public synchronized long activationFrame() {
        return this.activationFrame;
    }

    public synchronized boolean everActive() {
        return this.everActive;
    }

    public synchronized GlobalFailureAction globalFailureAction() {
        requireOpen();
        if (this.everActive) {
            return GlobalFailureAction
                .PAUSE_QUARANTINE_AND_REBUILD_MOJANG_NO_SAME_FRAME_REPLAY;
        }
        return GlobalFailureAction.ABORT_NATIVE_START_AND_BUILD_MOJANG;
    }

    public synchronized CompilationPermit beginCompilation() {
        requireState(State.SNAPSHOT);
        this.state = State.COMPILING;
        return new CompilationPermit(
            this,
            this.epoch,
            this.generations
        );
    }

    public synchronized void completeCompilation(
        CompilationPermit permit
    ) {
        requireState(State.COMPILING);
        requireCompilationPermit(permit);
        this.state = State.COMPILED;
    }

    public synchronized void failCompilation(
        CompilationPermit permit
    ) {
        requireState(State.COMPILING);
        requireCompilationPermit(permit);
        cancelWithoutResources(Cause.COMPILE_FAILURE);
    }

    public synchronized void rejectBudgetBeforeUpload() {
        requireState(State.COMPILED);
        cancelWithoutResources(Cause.BUDGET_FAILURE);
    }

    public synchronized UploadPermit beginUpload() {
        requireState(State.COMPILED);
        this.state = State.UPLOADING;
        return new UploadPermit(this, this.epoch, this.generations);
    }

    /**
     * Publishes the complete payload atomically. Callers must perform every
     * allocation, copy and descriptor validation before invoking this method.
     */
    public synchronized void publish(UploadPermit permit) {
        requireState(State.UPLOADING);
        requireUploadPermit(permit);
        this.state = State.PUBLISHED;
    }

    /**
     * Activates the fully prepared native section at a renderer frame boundary.
     */
    public synchronized void activate(long frameSerial) {
        requireState(State.PUBLISHED);
        requirePositive(frameSerial, "activationFrameSerial");
        this.activationFrame = frameSerial;
        this.everActive = true;
        this.state = State.ACTIVE;
    }

    public synchronized CleanupDecision failUpload(
        UploadPermit permit,
        long minimumSubmissionSerial
    ) {
        requireState(State.UPLOADING);
        requireUploadPermit(permit);
        return beginCleanup(
            Cause.UPLOAD_FAILURE,
            State.CANCELLED,
            false,
            minimumSubmissionSerial
        );
    }

    /**
     * Cancels only a not-yet-published generation. Uploading may already own
     * staging or arena ranges and therefore always enters cleanup.
     */
    public synchronized CleanupDecision cancelBeforePublish(
        Cause cause,
        long minimumSubmissionSerial
    ) {
        requireCancellationCause(cause);
        return switch (this.state) {
            case SNAPSHOT, COMPILING, COMPILED -> {
                cancelWithoutResources(cause);
                yield immediateDecision();
            }
            case UPLOADING -> beginCleanup(
                cause,
                State.CANCELLED,
                false,
                minimumSubmissionSerial
            );
            default -> throw new IllegalStateException(
                "cannot cancel a published section in " + this.state
            );
        };
    }

    /**
     * Restarts a dirty/reloaded generation. Before upload this is immediate;
     * after upload it first retires every native resource.
     */
    public synchronized CleanupDecision restart(
        Cause cause,
        SectionIdentity successorSection,
        GenerationStamp successorGenerations,
        long minimumSubmissionSerial
    ) {
        requireRestartCause(cause);
        validateSuccessor(
            cause,
            successorSection,
            successorGenerations
        );
        return switch (this.state) {
            case SNAPSHOT, COMPILING, COMPILED, CANCELLED, RETIRED -> {
                resetToSnapshot(
                    cause,
                    successorSection,
                    successorGenerations
                );
                yield immediateDecision();
            }
            case UPLOADING, PUBLISHED, ACTIVE -> {
                this.restartSection = successorSection;
                this.restartGenerations = successorGenerations;
                yield beginCleanup(
                    cause,
                    State.RETIRED,
                    false,
                    minimumSubmissionSerial
                );
            }
            default -> throw new IllegalStateException(
                "restart already pending or lifecycle unavailable in "
                    + this.state
            );
        };
    }

    public synchronized CleanupDecision removeSection(
        long minimumSubmissionSerial
    ) {
        return stop(
            Cause.SECTION_REMOVED,
            minimumSubmissionSerial
        );
    }

    public synchronized CleanupDecision shutdown(
        long minimumSubmissionSerial
    ) {
        return stop(Cause.SHUTDOWN, minimumSubmissionSerial);
    }

    /**
     * Quarantine is immediate only while no native resources can exist.
     * Otherwise cleanup remains mandatory and retryable.
     */
    public synchronized CleanupDecision quarantine(
        Cause cause,
        long minimumSubmissionSerial
    ) {
        if (
            cause != Cause.BACKEND_FAILURE
                && cause != Cause.UPLOAD_FAILURE
                && cause != Cause.BUDGET_FAILURE
        ) {
            throw new IllegalArgumentException(
                "cause does not justify backend quarantine"
            );
        }
        return switch (this.state) {
            case SNAPSHOT, COMPILING, COMPILED, CANCELLED, RETIRED -> {
                clearPending();
                this.lastCause = cause;
                this.state = State.QUARANTINED;
                yield immediateDecision();
            }
            case UPLOADING, PUBLISHED, ACTIVE -> beginCleanup(
                cause,
                State.QUARANTINED,
                true,
                minimumSubmissionSerial
            );
            default -> throw new IllegalStateException(
                "cannot quarantine lifecycle in " + this.state
            );
        };
    }

    public synchronized void cleanupFailed(
        RetirementPermit permit
    ) {
        requireState(State.RETIRING);
        requireRetirementPermit(permit);
        this.state = State.CLEANUP_RETRY;
    }

    public synchronized RetirementPermit retryCleanup() {
        requireState(State.CLEANUP_RETRY);
        this.cleanupAttempt = Math.addExact(this.cleanupAttempt, 1);
        this.state = State.RETIRING;
        return currentRetirementPermit();
    }

    public synchronized void completeRetirement(
        RetirementPermit permit,
        long completedSubmissionSerial
    ) {
        requireState(State.RETIRING);
        requireRetirementPermit(permit);
        if (
            completedSubmissionSerial
                < this.minimumCompletedSubmission
        ) {
            throw new IllegalStateException(
                "retirement completion proof is too old"
            );
        }
        if (this.restartGenerations != null) {
            SectionIdentity nextSection = this.restartSection;
            GenerationStamp nextGenerations =
                this.restartGenerations;
            Cause restartCause = this.lastCause;
            resetToSnapshot(
                restartCause,
                nextSection,
                nextGenerations
            );
            return;
        }
        State next = this.quarantineAfterCleanup
            ? State.QUARANTINED
            : this.terminalAfterCleanup;
        clearPending();
        this.state = Objects.requireNonNull(next, "cleanupTerminal");
    }

    public synchronized void close() {
        requireOpen();
        if (
            this.state != State.RETIRED
                && this.state != State.CANCELLED
                && this.state != State.QUARANTINED
        ) {
            throw new IllegalStateException(
                "resources or work remain in " + this.state
            );
        }
        clearPending();
        this.state = State.CLOSED;
    }

    private CleanupDecision stop(
        Cause cause,
        long minimumSubmissionSerial
    ) {
        return switch (this.state) {
            case SNAPSHOT, COMPILING, COMPILED, CANCELLED, RETIRED -> {
                clearPending();
                this.lastCause = cause;
                this.state = State.RETIRED;
                yield immediateDecision();
            }
            case UPLOADING, PUBLISHED, ACTIVE -> beginCleanup(
                cause,
                State.RETIRED,
                false,
                minimumSubmissionSerial
            );
            default -> throw new IllegalStateException(
                "stop already pending or lifecycle unavailable in "
                    + this.state
            );
        };
    }

    private CleanupDecision beginCleanup(
        Cause cause,
        State terminal,
        boolean quarantine,
        long minimumSubmissionSerial
    ) {
        requireNonNegative(
            minimumSubmissionSerial,
            "minimumSubmissionSerial"
        );
        this.lastCause = cause;
        this.minimumCompletedSubmission =
            minimumSubmissionSerial;
        this.cleanupAttempt = 1;
        this.terminalAfterCleanup = terminal;
        this.quarantineAfterCleanup = quarantine;
        this.state = State.RETIRING;
        return new CleanupDecision(
            this.state,
            currentRetirementPermit()
        );
    }

    private CleanupDecision immediateDecision() {
        return new CleanupDecision(this.state, null);
    }

    private RetirementPermit currentRetirementPermit() {
        return new RetirementPermit(
            this,
            this.epoch,
            this.cleanupAttempt,
            this.minimumCompletedSubmission
        );
    }

    private void cancelWithoutResources(Cause cause) {
        clearPending();
        this.lastCause = cause;
        this.state = State.CANCELLED;
    }

    private void resetToSnapshot(
        Cause cause,
        SectionIdentity successorSection,
        GenerationStamp successorGenerations
    ) {
        clearPending();
        this.section = successorSection;
        this.generations = successorGenerations;
        this.lastCause = cause;
        this.activationFrame = 0L;
        this.epoch = Math.addExact(this.epoch, 1L);
        this.state = State.SNAPSHOT;
    }

    private void clearPending() {
        this.minimumCompletedSubmission = 0L;
        this.cleanupAttempt = 0;
        this.quarantineAfterCleanup = false;
        this.terminalAfterCleanup = null;
        this.restartSection = null;
        this.restartGenerations = null;
    }

    private void requireCompilationPermit(
        CompilationPermit permit
    ) {
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || permit.epoch != this.epoch
                || !permit.generations.equals(this.generations)
        ) {
            throw new IllegalArgumentException(
                "stale or foreign compilation permit"
            );
        }
    }

    private void requireUploadPermit(UploadPermit permit) {
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || permit.epoch != this.epoch
                || !permit.generations.equals(this.generations)
        ) {
            throw new IllegalArgumentException(
                "stale or foreign upload permit"
            );
        }
    }

    private void requireRetirementPermit(
        RetirementPermit permit
    ) {
        Objects.requireNonNull(permit, "permit");
        if (
            permit.owner != this
                || permit.epoch != this.epoch
                || permit.cleanupAttempt != this.cleanupAttempt
                || permit.minimumCompletedSubmission
                    != this.minimumCompletedSubmission
        ) {
            throw new IllegalArgumentException(
                "stale or foreign retirement permit"
            );
        }
    }

    private void requireState(State expected) {
        requireOpen();
        if (this.state != expected) {
            throw new IllegalStateException(
                "expected " + expected + " but was " + this.state
            );
        }
    }

    private void requireOpen() {
        if (this.state == State.CLOSED) {
            throw new IllegalStateException(
                "section lifecycle is closed"
            );
        }
    }

    private static void requireCancellationCause(Cause cause) {
        Objects.requireNonNull(cause, "cause");
        if (
            cause != Cause.DIRTY
                && cause != Cause.SECTION_REMOVED
                && cause != Cause.RESOURCE_RELOAD
                && cause != Cause.RESIZE
                && cause != Cause.RENDERER_CHANGE
                && cause != Cause.WORLD_CHANGE
                && cause != Cause.DEVICE_CHANGE
                && cause != Cause.SHUTDOWN
                && cause != Cause.COMPILE_FAILURE
        ) {
            throw new IllegalArgumentException(
                "cause is not a cancellation"
            );
        }
    }

    private static void requireRestartCause(Cause cause) {
        Objects.requireNonNull(cause, "cause");
        if (
            cause != Cause.DIRTY
                && cause != Cause.RESOURCE_RELOAD
                && cause != Cause.RESIZE
                && cause != Cause.RENDERER_CHANGE
                && cause != Cause.WORLD_CHANGE
                && cause != Cause.DEVICE_CHANGE
        ) {
            throw new IllegalArgumentException(
                "cause is not restartable"
            );
        }
    }

    private void validateSuccessor(
        Cause cause,
        SectionIdentity successorSection,
        GenerationStamp successor
    ) {
        Objects.requireNonNull(successorSection, "successorSection");
        Objects.requireNonNull(successor, "successorGenerations");
        requireMonotonicSuccessor(this.generations, successor);
        switch (cause) {
            case DIRTY -> requireAdvanced(
                successor.sectionMesh(),
                this.generations.sectionMesh(),
                "sectionMeshGeneration"
            );
            case RESOURCE_RELOAD -> requireAdvanced(
                successor.resources(),
                this.generations.resources(),
                "resourceGeneration"
            );
            case RESIZE, RENDERER_CHANGE -> requireAdvanced(
                successor.renderer(),
                this.generations.renderer(),
                "rendererGeneration"
            );
            case WORLD_CHANGE -> {
                requireAdvanced(
                    successor.world(),
                    this.generations.world(),
                    "worldGeneration"
                );
                if (
                    successorSection.worldIdentity().equals(
                        this.section.worldIdentity()
                    )
                ) {
                    throw new IllegalArgumentException(
                        "world change requires a new world identity"
                    );
                }
            }
            case DEVICE_CHANGE -> requireAdvanced(
                successor.device(),
                this.generations.device(),
                "deviceGeneration"
            );
            default -> throw new IllegalArgumentException(
                "cause is not restartable"
            );
        }
        if (
            cause != Cause.WORLD_CHANGE
                && !successorSection.equals(this.section)
        ) {
            throw new IllegalArgumentException(
                "only world change may replace section identity"
            );
        }
    }

    private static void requireMonotonicSuccessor(
        GenerationStamp current,
        GenerationStamp successor
    ) {
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

    private static void requireAdvanced(
        long successor,
        long current,
        String name
    ) {
        if (successor <= current) {
            throw new IllegalArgumentException(
                name + " did not advance"
            );
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                name + " must not be negative"
            );
        }
    }
}
