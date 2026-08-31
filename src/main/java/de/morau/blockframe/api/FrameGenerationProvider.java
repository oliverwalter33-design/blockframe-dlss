package de.morau.blockframe.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Frame-generation capability boundary. Implementations must keep rendered
 * frames, generated frames, presented frames, and latency as separate metrics.
 */
public interface FrameGenerationProvider
    extends BlockframeProvider<FrameGenerationProvider.Capabilities> {

    record Capabilities(
        Set<Input> requiredInputs,
        boolean hudSeparation,
        boolean reportsGeneratedFramesSeparately,
        boolean reportsPresentedFramesSeparately,
        boolean reportsInputLatency,
        int maximumGeneratedFramesBetweenRenderedFrames
    ) {
        public Capabilities {
            Objects.requireNonNull(requiredInputs, "requiredInputs");
            requiredInputs = requiredInputs.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(requiredInputs));
            maximumGeneratedFramesBetweenRenderedFrames =
                Math.max(0, maximumGeneratedFramesBetweenRenderedFrames);
        }
    }

    enum Input {
        COLOR,
        DEPTH,
        MOTION_VECTORS,
        EXPOSURE,
        HUD_SEPARATION,
        FRAME_PACING
    }
}
