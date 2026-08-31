package de.morau.blockframe.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Capability contract for temporal upscalers such as the existing DLSS/DLAA
 * path. Frame execution remains provider-specific and outside Phase 1.
 */
public interface TemporalUpscalerProvider
    extends BlockframeProvider<TemporalUpscalerProvider.Capabilities> {

    record Capabilities(
        Set<Mode> modes,
        Set<Input> requiredInputs,
        boolean ownsJitterWhenActive,
        boolean supportsHistoryReset,
        boolean keepsHudNativeResolution,
        boolean reportsRenderedFramesSeparately
    ) {
        public Capabilities {
            modes = immutableEnumSet(modes, Mode.class);
            requiredInputs = immutableEnumSet(requiredInputs, Input.class);
            if (modes.isEmpty()) {
                ownsJitterWhenActive = false;
                supportsHistoryReset = false;
            }
        }
    }

    enum Mode {
        TEMPORAL_SUPER_RESOLUTION,
        NATIVE_RESOLUTION_ANTIALIASING
    }

    enum Input {
        COLOR,
        DEPTH,
        MOTION_VECTORS,
        EXPOSURE,
        CAMERA_JITTER,
        REACTIVE_MASK,
        TRANSPARENCY_MASK,
        HISTORY_RESET
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values, Class<E> type) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(type, "type");
        if (values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }
}
