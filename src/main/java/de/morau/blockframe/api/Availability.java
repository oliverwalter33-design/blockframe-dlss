package de.morau.blockframe.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed provider availability.
 *
 * <p>{@link State#AVAILABLE} has no reasons. Every degraded or unavailable
 * state must explain itself, so capability fallbacks cannot silently look
 * like successful activation.
 */
public record Availability(State state, List<Reason> reasons) {
    public Availability {
        state = Objects.requireNonNull(state, "state");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (state == State.AVAILABLE && !reasons.isEmpty()) {
            throw new IllegalArgumentException("An available provider cannot carry failure reasons");
        }
        if (state != State.AVAILABLE && reasons.isEmpty()) {
            throw new IllegalArgumentException("A degraded or unavailable provider needs a reason");
        }
    }

    public static Availability available() {
        return new Availability(State.AVAILABLE, List.of());
    }

    public static Availability degraded(Reason first, Reason... additional) {
        return withReasons(State.DEGRADED, first, additional);
    }

    public static Availability unavailable(Reason first, Reason... additional) {
        return withReasons(State.UNAVAILABLE, first, additional);
    }

    /**
     * A degraded provider may be selected only when its advertised capability
     * subset satisfies the caller. An unavailable provider must never be used.
     */
    public boolean usable() {
        return this.state != State.UNAVAILABLE;
    }

    private static Availability withReasons(State state, Reason first, Reason[] additional) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(additional, "additional");
        List<Reason> reasons = new ArrayList<>(additional.length + 1);
        reasons.add(first);
        for (Reason reason : additional) {
            reasons.add(Objects.requireNonNull(reason, "reason"));
        }
        return new Availability(state, reasons);
    }

    public enum State {
        AVAILABLE,
        DEGRADED,
        UNAVAILABLE
    }
}
