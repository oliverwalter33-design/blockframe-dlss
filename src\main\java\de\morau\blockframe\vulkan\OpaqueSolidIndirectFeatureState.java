package de.morau.blockframe.vulkan;

import java.util.Objects;

/** Cached device-generation state; debug consumers never query Vulkan. */
public final class OpaqueSolidIndirectFeatureState {
    private static volatile Snapshot snapshot = Snapshot.notRequested(
        "device-negotiation-not-run"
    );

    private OpaqueSolidIndirectFeatureState() {
    }

    public static void reset(String reason) {
        snapshot = Snapshot.notRequested(reason);
    }

    public static void publish(
        OpaqueSolidIndirectNegotiator.Selection selection
    ) {
        Objects.requireNonNull(selection, "selection");
        var availability = selection.availability();
        snapshot = new Snapshot(
            selection.requested(),
            availability.multiDrawIndirect(),
            availability.shaderDrawParameters(),
            availability.drawIndirectCount(),
            availability.drawIndirectFirstInstance(),
            availability.maxStorageBufferRange(),
            availability.maxTexelBufferElements(),
            availability.maxDrawIndirectCount(),
            selection.enabled(),
            false,
            selection.reason()
        );
    }

    public static void publishFunctionResolution(
        boolean resolved,
        String reason
    ) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(
            current.requested(),
            current.multiDrawIndirect(),
            current.shaderDrawParameters(),
            current.drawIndirectCount(),
            current.drawIndirectFirstInstance(),
            current.maxStorageBufferRange(),
            current.maxTexelBufferElements(),
            current.maxDrawIndirectCount(),
            current.enabled(),
            resolved,
            current.enabled()
                ? resolved
                    ? ""
                    : Objects.requireNonNull(reason, "reason")
                : current.unavailableReason()
        );
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(
        boolean requested,
        boolean multiDrawIndirect,
        boolean shaderDrawParameters,
        boolean drawIndirectCount,
        boolean drawIndirectFirstInstance,
        int maxStorageBufferRange,
        int maxTexelBufferElements,
        int maxDrawIndirectCount,
        boolean enabled,
        boolean functionResolved,
        String unavailableReason
    ) {
        public Snapshot {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
        }

        public boolean usable() {
            return this.enabled && this.functionResolved;
        }

        private static Snapshot notRequested(String reason) {
            return new Snapshot(
                false,
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                false,
                false,
                Objects.requireNonNull(reason, "reason")
            );
        }
    }
}
