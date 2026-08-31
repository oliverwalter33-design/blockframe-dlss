package de.morau.blockframe.core.state;

import java.util.Objects;

/** One immutable, generation-scoped observation of an optional feature. */
public record FeatureState(
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
    public FeatureState {
        id = Objects.requireNonNull(id, "id");
        reason = reason == null ? "" : reason;
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
    }

    static FeatureState initial(FeatureId id) {
        return new FeatureState(
            id,
            false,
            false,
            false,
            false,
            false,
            false,
            "not-evaluated",
            0L,
            0L
        );
    }

    boolean matches(
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
        return this.requested == requested
            && this.supported == supported
            && this.enabled == enabled
            && this.effective == effective
            && this.fallback == fallback
            && this.quarantined == quarantined
            && this.reason.equals(reason)
            && this.clientGeneration == clientGeneration
            && this.deviceGeneration == deviceGeneration;
    }

    public FeatureId.ConfigSource configSource() {
        return this.id.configSource();
    }

    public String configKey() {
        return this.id.configKey();
    }

    public FeatureId.ReloadRequirement reloadRequirement() {
        if (
            this.id == FeatureId.DLSS_MODE
                && RuntimeFeaturePolicy
                    .DLSS_RESTART_REQUIRED_REASON
                    .equals(this.reason)
        ) {
            return FeatureId.ReloadRequirement.PROCESS_RESTART;
        }
        return this.id.reloadRequirement();
    }
}
