package de.morau.blockframe.benchmark.phase2a0b;

/**
 * Primitive, render-callback-driven readiness state. It never waits, sleeps,
 * polls external state or allocates from its warm callback path.
 */
public final class RenderReadinessState {
    public static final int CLIENT_RENDER_CALLBACK_SEEN = 1;
    public static final int WORLD_PRESENT = 1 << 1;
    public static final int PLAYER_PRESENT = 1 << 2;
    public static final int CAMERA_READY = 1 << 3;
    public static final int RENDER_OWNER_BOUND = 1 << 4;
    public static final int REPLAY_ARMED = 1 << 5;

    private static final int CALLBACK_CONTEXT =
        CLIENT_RENDER_CALLBACK_SEEN
            | WORLD_PRESENT
            | PLAYER_PRESENT
            | CAMERA_READY;

    public enum State {
        BOOTSTRAPPED,
        CLIENT_RENDER_CALLBACK_SEEN,
        WORLD_PRESENT,
        PLAYER_PRESENT,
        CAMERA_READY,
        RENDER_OWNER_BOUND,
        REPLAY_ARMED
    }

    public enum Decision {
        NO_CHANGE,
        STATE_CHANGED,
        BIND_OWNER,
        WRONG_THREAD,
        ALREADY_ARMED
    }

    private long generation = 1L;
    private long totalCallbackCount;
    private long generationCallbackCount;
    private long firstCallbackNanos = -1L;
    private long firstCallbackEpochMillis = -1L;
    private long generationFirstCallbackNanos = -1L;
    private long renderThreadId = -1L;
    private long rejectedWrongThreadCallbacks;
    private int readinessMask;
    private int reportedTransitionMask;
    private int ownerPublications;
    private State state = State.BOOTSTRAPPED;
    private boolean ownerAttempted;
    private boolean worldLifecyclePresent;
    private boolean worldLifecycleEverSeen;

    public Decision observe(
        long callbackThreadId,
        long nowNanos,
        long nowEpochMillis,
        boolean worldPresent,
        boolean playerPresent,
        boolean cameraReady
    ) {
        this.totalCallbackCount++;
        this.generationCallbackCount++;
        if (this.renderThreadId < 0L) {
            this.renderThreadId = callbackThreadId;
            this.generationFirstCallbackNanos = nowNanos;
            if (this.firstCallbackNanos < 0L) {
                this.firstCallbackNanos = nowNanos;
                this.firstCallbackEpochMillis = nowEpochMillis;
            }
            this.readinessMask = CLIENT_RENDER_CALLBACK_SEEN;
            this.state = State.CLIENT_RENDER_CALLBACK_SEEN;
        } else if (callbackThreadId != this.renderThreadId) {
            this.rejectedWrongThreadCallbacks++;
            return Decision.WRONG_THREAD;
        }
        if (this.state == State.REPLAY_ARMED) {
            return Decision.ALREADY_ARMED;
        }

        int previousMask = this.readinessMask;
        int currentMask = CLIENT_RENDER_CALLBACK_SEEN;
        if (worldPresent) {
            currentMask |= WORLD_PRESENT;
            this.worldLifecycleEverSeen = true;
            if (playerPresent) {
                currentMask |= PLAYER_PRESENT;
                if (cameraReady) {
                    currentMask |= CAMERA_READY;
                }
            }
        }
        if (this.ownerAttempted) {
            currentMask |= RENDER_OWNER_BOUND;
        }
        this.readinessMask = currentMask;
        this.state = stateForMask(currentMask);

        if (
            !this.ownerAttempted
                && (currentMask & CALLBACK_CONTEXT) == CALLBACK_CONTEXT
        ) {
            this.ownerAttempted = true;
            this.readinessMask |= RENDER_OWNER_BOUND;
            this.state = State.RENDER_OWNER_BOUND;
            return Decision.BIND_OWNER;
        }
        return previousMask == this.readinessMask
            ? Decision.NO_CHANGE
            : Decision.STATE_CHANGED;
    }

    /**
     * Warm post-arm heartbeat: two primitive increments/comparisons only.
     */
    public boolean heartbeat(long callbackThreadId) {
        this.totalCallbackCount++;
        this.generationCallbackCount++;
        if (callbackThreadId == this.renderThreadId) {
            return true;
        }
        this.rejectedWrongThreadCallbacks++;
        return false;
    }

    public void markReplayArmed(long callbackThreadId) {
        if (
            callbackThreadId != this.renderThreadId
                || this.state != State.RENDER_OWNER_BOUND
                || this.ownerPublications != 0
        ) {
            throw new IllegalStateException(
                "replay owner publication violates render generation"
            );
        }
        this.ownerPublications = 1;
        this.readinessMask |= REPLAY_ARMED;
        this.state = State.REPLAY_ARMED;
    }

    public void markOwnerPublicationFailed() {
        if (this.state == State.REPLAY_ARMED) {
            this.readinessMask &= ~REPLAY_ARMED;
        }
        this.ownerPublications = 0;
        this.state = State.RENDER_OWNER_BOUND;
    }

    public void onWorldLifecyclePresent() {
        this.worldLifecyclePresent = true;
        this.worldLifecycleEverSeen = true;
    }

    public boolean invalidateWorld() {
        if (
            !this.worldLifecyclePresent
                && (this.readinessMask & WORLD_PRESENT) == 0
        ) {
            return false;
        }
        this.generation++;
        this.generationCallbackCount = 0L;
        this.generationFirstCallbackNanos = -1L;
        this.renderThreadId = -1L;
        this.readinessMask = 0;
        this.reportedTransitionMask = 0;
        this.ownerPublications = 0;
        this.ownerAttempted = false;
        this.worldLifecyclePresent = false;
        this.state = State.BOOTSTRAPPED;
        return true;
    }

    public int nextUnreportedTransitionBit() {
        int available =
            this.readinessMask
                & ~this.reportedTransitionMask
                & (
                    CLIENT_RENDER_CALLBACK_SEEN
                        | WORLD_PRESENT
                        | PLAYER_PRESENT
                        | CAMERA_READY
                        | RENDER_OWNER_BOUND
                        | REPLAY_ARMED
                );
        if (available == 0) {
            return 0;
        }
        int bit = Integer.lowestOneBit(available);
        this.reportedTransitionMask |= bit;
        return bit;
    }

    public static State stateForTransitionBit(int bit) {
        return switch (bit) {
            case CLIENT_RENDER_CALLBACK_SEEN ->
                State.CLIENT_RENDER_CALLBACK_SEEN;
            case WORLD_PRESENT -> State.WORLD_PRESENT;
            case PLAYER_PRESENT -> State.PLAYER_PRESENT;
            case CAMERA_READY -> State.CAMERA_READY;
            case RENDER_OWNER_BOUND -> State.RENDER_OWNER_BOUND;
            case REPLAY_ARMED -> State.REPLAY_ARMED;
            default -> throw new IllegalArgumentException(
                "unknown readiness transition bit: " + bit
            );
        };
    }

    private static State stateForMask(int mask) {
        if ((mask & REPLAY_ARMED) != 0) {
            return State.REPLAY_ARMED;
        }
        if ((mask & RENDER_OWNER_BOUND) != 0) {
            return State.RENDER_OWNER_BOUND;
        }
        if ((mask & CAMERA_READY) != 0) {
            return State.CAMERA_READY;
        }
        if ((mask & PLAYER_PRESENT) != 0) {
            return State.PLAYER_PRESENT;
        }
        if ((mask & WORLD_PRESENT) != 0) {
            return State.WORLD_PRESENT;
        }
        if ((mask & CLIENT_RENDER_CALLBACK_SEEN) != 0) {
            return State.CLIENT_RENDER_CALLBACK_SEEN;
        }
        return State.BOOTSTRAPPED;
    }

    public State state() {
        return this.state;
    }

    public boolean replayArmed() {
        return this.state == State.REPLAY_ARMED;
    }

    public long generation() {
        return this.generation;
    }

    public long totalCallbackCount() {
        return this.totalCallbackCount;
    }

    public long generationCallbackCount() {
        return this.generationCallbackCount;
    }

    public long firstCallbackNanos() {
        return this.firstCallbackNanos;
    }

    public long firstCallbackEpochMillis() {
        return this.firstCallbackEpochMillis;
    }

    public long generationFirstCallbackNanos() {
        return this.generationFirstCallbackNanos;
    }

    public long renderThreadId() {
        return this.renderThreadId;
    }

    public long rejectedWrongThreadCallbacks() {
        return this.rejectedWrongThreadCallbacks;
    }

    public int readinessMask() {
        return this.readinessMask;
    }

    public int ownerPublications() {
        return this.ownerPublications;
    }

    public boolean ownerAttempted() {
        return this.ownerAttempted;
    }

    public boolean worldLifecyclePresent() {
        return this.worldLifecyclePresent;
    }

    public boolean worldLifecycleEverSeen() {
        return this.worldLifecycleEverSeen;
    }
}
