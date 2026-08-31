package de.morau.blockframe.render.terrain.nativeengine;

/**
 * One render-thread-owned no-replay boundary for the complete native terrain
 * frame. It is deliberately global rather than attached to section records.
 *
 * <p>The warm path mutates this one preallocated object once at frame open,
 * once before the first native terrain command and once at frame end. Error
 * paths may query it without scanning scene entries.</p>
 */
public final class TerrainSubmissionBoundary {
    public enum Phase {
        IDLE,
        BEFORE_NATIVE_SUBMISSION,
        NATIVE_SUBMISSION_STARTED,
        CLOSED
    }

    public enum FailureBoundary {
        SAME_FRAME_FALLBACK_ALLOWED,
        NEXT_FRAME_ONLY_NO_REPLAY
    }

    public record Generations(
        long device,
        long renderer,
        long world,
        long resources
    ) {
        public Generations {
            requirePositive(device, "deviceGeneration");
            requirePositive(renderer, "rendererGeneration");
            requirePositive(world, "worldGeneration");
            requirePositive(resources, "resourceGeneration");
        }
    }

    private final Generations generations;
    private Phase phase = Phase.IDLE;
    private long frameSerial;
    private long lastFrameSerial;
    private long lastSubmissionSerial;

    public TerrainSubmissionBoundary(Generations generations) {
        this.generations = java.util.Objects.requireNonNull(
            generations,
            "generations"
        );
    }

    public synchronized Phase phase() {
        return this.phase;
    }

    public synchronized void beginFrame(long nextFrameSerial) {
        requireOpen();
        if (this.phase != Phase.IDLE) {
            throw new IllegalStateException(
                "previous terrain frame is still open"
            );
        }
        if (nextFrameSerial <= this.lastFrameSerial) {
            throw new IllegalArgumentException(
                "terrain frame serial must increase"
            );
        }
        this.frameSerial = nextFrameSerial;
        this.lastFrameSerial = nextFrameSerial;
        this.phase = Phase.BEFORE_NATIVE_SUBMISSION;
    }

    /** Marks the global no-replay point exactly once for this terrain frame. */
    public synchronized void beginNativeSubmission(
        long expectedFrameSerial,
        long submissionSerial
    ) {
        requireFrame(
            expectedFrameSerial,
            Phase.BEFORE_NATIVE_SUBMISSION
        );
        if (submissionSerial <= this.lastSubmissionSerial) {
            throw new IllegalArgumentException(
                "submission serial must increase"
            );
        }
        this.lastSubmissionSerial = submissionSerial;
        this.phase = Phase.NATIVE_SUBMISSION_STARTED;
    }

    public synchronized FailureBoundary failureBoundary(
        long expectedFrameSerial
    ) {
        requireOpen();
        if (expectedFrameSerial != this.frameSerial) {
            throw new IllegalArgumentException(
                "failure belongs to another terrain frame"
            );
        }
        return switch (this.phase) {
            case BEFORE_NATIVE_SUBMISSION ->
                FailureBoundary.SAME_FRAME_FALLBACK_ALLOWED;
            case NATIVE_SUBMISSION_STARTED ->
                FailureBoundary.NEXT_FRAME_ONLY_NO_REPLAY;
            case IDLE, CLOSED -> throw new IllegalStateException(
                "no terrain frame is open"
            );
        };
    }

    public synchronized void endFrame(long expectedFrameSerial) {
        requireOpen();
        if (
            expectedFrameSerial != this.frameSerial
                || (
                    this.phase != Phase.BEFORE_NATIVE_SUBMISSION
                        && this.phase
                            != Phase.NATIVE_SUBMISSION_STARTED
                )
        ) {
            throw new IllegalStateException(
                "cannot close a different terrain frame"
            );
        }
        this.frameSerial = 0L;
        this.phase = Phase.IDLE;
    }

    /**
     * Conservative completion fence for any resource invalidated now. It may
     * over-retain entries that were not visible, but needs no section scan.
     */
    public synchronized long retirementSubmissionSerial() {
        requireOpen();
        return this.lastSubmissionSerial;
    }

    synchronized void requireCompatible(
        TerrainMeshProducerABI.GenerationStamp stamp
    ) {
        requireOpen();
        if (
            stamp == null
                || stamp.device() != this.generations.device()
                || stamp.renderer() != this.generations.renderer()
                || stamp.world() != this.generations.world()
                || stamp.resources() != this.generations.resources()
        ) {
            throw new IllegalArgumentException(
                "terrain submission generation mismatch"
            );
        }
    }

    public synchronized void closeAfterCompletion(
        long completedSubmissionSerial
    ) {
        requireOpen();
        if (this.phase != Phase.IDLE) {
            throw new IllegalStateException(
                "terrain frame must end before boundary close"
            );
        }
        if (completedSubmissionSerial < this.lastSubmissionSerial) {
            throw new IllegalStateException(
                "submission completion proof is too old"
            );
        }
        this.phase = Phase.CLOSED;
    }

    private void requireFrame(long expectedFrameSerial, Phase expected) {
        requireOpen();
        if (
            this.frameSerial != expectedFrameSerial
                || this.phase != expected
        ) {
            throw new IllegalStateException(
                "expected frame "
                    + expectedFrameSerial
                    + " in "
                    + expected
                    + " but was "
                    + this.frameSerial
                    + " in "
                    + this.phase
            );
        }
    }

    private void requireOpen() {
        if (this.phase == Phase.CLOSED) {
            throw new IllegalStateException(
                "terrain submission boundary is closed"
            );
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
