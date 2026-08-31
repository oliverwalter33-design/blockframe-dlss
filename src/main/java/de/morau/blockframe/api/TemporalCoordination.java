package de.morau.blockframe.api;

import java.util.Objects;

/**
 * Single-owner policy for temporal antialiasing and camera jitter.
 *
 * <p>A rejected decision always returns {@link Owner#NONE} for both owners.
 * Callers must then disable both temporal paths for the frame instead of
 * attempting a double-TAA or double-jitter fallback.
 */
public final class TemporalCoordination {
    public static final String DOUBLE_TEMPORAL_AA = "DOUBLE_TEMPORAL_AA";
    public static final String JITTER_OWNER_MISMATCH = "JITTER_OWNER_MISMATCH";

    private TemporalCoordination() {
    }

    public static Decision negotiate(Request request) {
        Objects.requireNonNull(request, "request");

        if (request.blockframeTemporalActive() && request.shaderPackTemporalActive()) {
            Reason reason = new Reason(
                DOUBLE_TEMPORAL_AA,
                "Blockframe temporal reconstruction and shader-pack temporal AA cannot be active together"
            );
            return Decision.rejected(reason);
        }

        Owner expectedOwner = request.blockframeTemporalActive()
            ? Owner.BLOCKFRAME_TEMPORAL_UPSCALER
            : request.shaderPackTemporalActive() ? Owner.SHADER_PACK : Owner.NONE;
        if (request.requestedJitterOwner() != expectedOwner) {
            Reason reason = new Reason(
                JITTER_OWNER_MISMATCH,
                "Temporal owner " + expectedOwner + " requires the same exclusive jitter owner"
            );
            return Decision.rejected(reason);
        }

        return new Decision(
            Availability.available(),
            expectedOwner,
            expectedOwner
        );
    }

    public record Request(
        boolean blockframeTemporalActive,
        boolean shaderPackTemporalActive,
        Owner requestedJitterOwner
    ) {
        public Request {
            requestedJitterOwner = Objects.requireNonNull(requestedJitterOwner, "requestedJitterOwner");
        }

        public static Request none() {
            return new Request(false, false, Owner.NONE);
        }

        public static Request blockframe() {
            return new Request(true, false, Owner.BLOCKFRAME_TEMPORAL_UPSCALER);
        }

        public static Request shaderPack() {
            return new Request(false, true, Owner.SHADER_PACK);
        }
    }

    public record Decision(
        Availability availability,
        Owner temporalOwner,
        Owner jitterOwner
    ) {
        public Decision {
            availability = Objects.requireNonNull(availability, "availability");
            temporalOwner = Objects.requireNonNull(temporalOwner, "temporalOwner");
            jitterOwner = Objects.requireNonNull(jitterOwner, "jitterOwner");
            if (availability.state() == Availability.State.UNAVAILABLE
                && (temporalOwner != Owner.NONE || jitterOwner != Owner.NONE)) {
                throw new IllegalArgumentException("A rejected temporal decision must fail closed");
            }
            if (availability.usable() && temporalOwner != jitterOwner) {
                throw new IllegalArgumentException("Temporal and jitter ownership must be identical");
            }
        }

        public boolean accepted() {
            return this.availability.usable();
        }

        private static Decision rejected(Reason reason) {
            return new Decision(
                Availability.unavailable(reason),
                Owner.NONE,
                Owner.NONE
            );
        }
    }

    public enum Owner {
        NONE,
        BLOCKFRAME_TEMPORAL_UPSCALER,
        SHADER_PACK
    }
}
