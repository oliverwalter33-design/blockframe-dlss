package de.morau.blockframe.core.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-size, publication-safe registry for current optional-feature states.
 *
 * <p>Readers observe one cached immutable snapshot. A no-op update performs no
 * state, collection, mask, or debug-line allocation; changed states rebuild the
 * thirteen-entry snapshot only at their lifecycle transition.
 */
public final class FeatureStateRegistry {
    private final FeatureState[] states =
        new FeatureState[FeatureId.COUNT];
    private volatile Snapshot snapshot;

    public FeatureStateRegistry() {
        for (FeatureId feature : FeatureId.all()) {
            this.states[feature.bitIndex()] = FeatureState.initial(feature);
        }
        this.snapshot = this.publish(0L);
    }

    public int size() {
        return FeatureId.COUNT;
    }

    public FeatureState state(FeatureId id) {
        return this.snapshot.state(Objects.requireNonNull(id, "id"));
    }

    public Snapshot snapshot() {
        return this.snapshot;
    }

    public List<String> debugLines() {
        return this.snapshot.debugLines();
    }

    /**
     * Publishes a changed state and returns {@code true}; returns {@code false}
     * without allocating when every supplied value equals the cached state.
     */
    public synchronized boolean update(
        FeatureId id,
        boolean requested,
        boolean supported,
        boolean enabled,
        boolean effective,
        boolean fallback,
        boolean quarantined,
        String reason,
        long clientGeneration,
        long deviceGeneration
    ) {
        Objects.requireNonNull(id, "id");
        String normalizedReason = reason == null ? "" : reason;
        if (clientGeneration < 0L) {
            throw new IllegalArgumentException(
                "clientGeneration must not be negative"
            );
        }
        if (deviceGeneration < 0L) {
            throw new IllegalArgumentException(
                "deviceGeneration must not be negative"
            );
        }

        FeatureState current = this.states[id.bitIndex()];
        if (
            current.matches(
                requested,
                supported,
                enabled,
                effective,
                fallback,
                quarantined,
                normalizedReason,
                clientGeneration,
                deviceGeneration
            )
        ) {
            return false;
        }

        this.states[id.bitIndex()] = new FeatureState(
            id,
            requested,
            supported,
            enabled,
            effective,
            fallback,
            quarantined,
            normalizedReason,
            clientGeneration,
            deviceGeneration
        );
        long revision = this.snapshot.revision() == Long.MAX_VALUE
            ? Long.MAX_VALUE
            : this.snapshot.revision() + 1L;
        this.snapshot = this.publish(revision);
        return true;
    }

    private Snapshot publish(long revision) {
        ArrayList<FeatureState> immutableStates =
            new ArrayList<>(FeatureId.COUNT);
        ArrayList<String> debugLines =
            new ArrayList<>(FeatureId.COUNT);
        long requestedMask = 0L;
        long supportedMask = 0L;
        long enabledMask = 0L;
        long effectiveMask = 0L;
        long fallbackMask = 0L;
        long quarantinedMask = 0L;

        for (FeatureState state : this.states) {
            immutableStates.add(state);
            debugLines.add(debugLine(state));
            long mask = state.id().mask();
            if (state.requested()) {
                requestedMask |= mask;
            }
            if (state.supported()) {
                supportedMask |= mask;
            }
            if (state.enabled()) {
                enabledMask |= mask;
            }
            if (state.effective()) {
                effectiveMask |= mask;
            }
            if (state.fallback()) {
                fallbackMask |= mask;
            }
            if (state.quarantined()) {
                quarantinedMask |= mask;
            }
        }

        return new Snapshot(
            revision,
            requestedMask,
            supportedMask,
            enabledMask,
            effectiveMask,
            fallbackMask,
            quarantinedMask,
            immutableStates,
            debugLines
        );
    }

    private static String debugLine(FeatureState state) {
        FeatureId id = state.id();
        return id.stableId()
            + " requested="
            + state.requested()
            + " supported="
            + state.supported()
            + " enabled="
            + state.enabled()
            + " effective="
            + state.effective()
            + " fallback="
            + state.fallback()
            + " quarantined="
            + state.quarantined()
            + " config-owner="
            + id.configSource().relativePath()
            + "#"
            + id.configKey()
            + " apply="
            + state.reloadRequirement()
            + " clientGen="
            + state.clientGeneration()
            + " deviceGen="
            + state.deviceGeneration()
            + " reason="
            + state.reason();
    }

    /** Immutable cache published atomically to F8 and persistence readers. */
    public static final class Snapshot {
        private final long revision;
        private final long requestedMask;
        private final long supportedMask;
        private final long enabledMask;
        private final long effectiveMask;
        private final long fallbackMask;
        private final long quarantinedMask;
        private final List<FeatureState> states;
        private final List<String> debugLines;

        private Snapshot(
            long revision,
            long requestedMask,
            long supportedMask,
            long enabledMask,
            long effectiveMask,
            long fallbackMask,
            long quarantinedMask,
            List<FeatureState> states,
            List<String> debugLines
        ) {
            this.revision = revision;
            this.requestedMask = requestedMask;
            this.supportedMask = supportedMask;
            this.enabledMask = enabledMask;
            this.effectiveMask = effectiveMask;
            this.fallbackMask = fallbackMask;
            this.quarantinedMask = quarantinedMask;
            this.states = List.copyOf(states);
            this.debugLines = List.copyOf(debugLines);
        }

        public long revision() {
            return this.revision;
        }

        public long requestedMask() {
            return this.requestedMask;
        }

        public long supportedMask() {
            return this.supportedMask;
        }

        public long enabledMask() {
            return this.enabledMask;
        }

        public long effectiveMask() {
            return this.effectiveMask;
        }

        public long fallbackMask() {
            return this.fallbackMask;
        }

        public long quarantinedMask() {
            return this.quarantinedMask;
        }

        public List<FeatureState> states() {
            return this.states;
        }

        public List<String> debugLines() {
            return this.debugLines;
        }

        public FeatureState state(FeatureId id) {
            return this.states.get(
                Objects.requireNonNull(id, "id").bitIndex()
            );
        }
    }
}
