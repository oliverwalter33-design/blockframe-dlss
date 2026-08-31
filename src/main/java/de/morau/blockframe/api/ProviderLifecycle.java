package de.morau.blockframe.api;

import java.util.Objects;

/**
 * Immutable lifecycle observation. Generation increases when a provider is
 * recreated after a device, world, or resource-pack transition.
 */
public record ProviderLifecycle(State state, long generation) {
    public ProviderLifecycle {
        state = Objects.requireNonNull(state, "state");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    public static ProviderLifecycle declared() {
        return new ProviderLifecycle(State.DECLARED, 0L);
    }

    public static ProviderLifecycle closed(long generation) {
        return new ProviderLifecycle(State.CLOSED, generation);
    }

    public enum State {
        DECLARED,
        INITIALIZING,
        READY,
        DEGRADED,
        UNAVAILABLE,
        CLOSING,
        CLOSED,
        FAILED
    }
}
