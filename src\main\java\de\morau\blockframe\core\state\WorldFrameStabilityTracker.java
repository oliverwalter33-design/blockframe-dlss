package de.morau.blockframe.core.state;

import java.util.Objects;

/**
 * Render-thread-owned, allocation-free consecutive-world-frame counter.
 *
 * <p>It owns no world resource; the reference is identity-only and is cleared
 * on unload, resource reload, device change and shutdown.</p>
 */
public final class WorldFrameStabilityTracker {
    private final int requiredFrames;
    private Object worldIdentity;
    private int consecutiveFrames;

    public WorldFrameStabilityTracker(int requiredFrames) {
        if (requiredFrames <= 0) {
            throw new IllegalArgumentException(
                "requiredFrames must be positive"
            );
        }
        this.requiredFrames = requiredFrames;
    }

    public Transition observeSuccessfulFrame(Object currentWorld) {
        Objects.requireNonNull(currentWorld, "currentWorld");
        if (this.worldIdentity != currentWorld) {
            this.worldIdentity = currentWorld;
            this.consecutiveFrames = 0;
        }
        if (this.consecutiveFrames >= this.requiredFrames) {
            return Transition.NONE;
        }
        this.consecutiveFrames++;
        if (this.consecutiveFrames == 1) {
            return Transition.FIRST_WORLD_FRAME;
        }
        if (this.consecutiveFrames == this.requiredFrames) {
            return Transition.STABILITY_WINDOW_COMPLETE;
        }
        return Transition.NONE;
    }

    /** Cached identity comparison; performs no ownership or allocation. */
    public boolean tracksWorld(Object currentWorld) {
        return this.worldIdentity
            == Objects.requireNonNull(currentWorld, "currentWorld");
    }

    public boolean hasWorld() {
        return this.worldIdentity != null;
    }

    /** Resets consecutiveness while retaining only the current identity. */
    public void resetWindow() {
        this.consecutiveFrames = 0;
    }

    /** Drops the non-owning world identity at a lifecycle boundary. */
    public void clearWorld() {
        this.worldIdentity = null;
        this.consecutiveFrames = 0;
    }

    public int consecutiveFrames() {
        return this.consecutiveFrames;
    }

    public int requiredFrames() {
        return this.requiredFrames;
    }

    public enum Transition {
        NONE,
        FIRST_WORLD_FRAME,
        STABILITY_WINDOW_COMPLETE
    }
}
