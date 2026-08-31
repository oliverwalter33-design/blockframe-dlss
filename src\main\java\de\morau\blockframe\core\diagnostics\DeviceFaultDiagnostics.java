package de.morau.blockframe.core.diagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Client-scoped, generation-safe state for optional Vulkan device faults.
 *
 * <p>This owner never creates or closes a Vulkan object. The only live native
 * dependency is the capture adapter borrowed for the exact current device
 * generation. F8 consumers read {@link #snapshot()} only.</p>
 */
public final class DeviceFaultDiagnostics implements AutoCloseable {
    public static final int VK_ERROR_DEVICE_LOST = -4;

    private volatile Snapshot snapshot = Snapshot.initial();
    private Negotiation pending = Negotiation.initial();
    private Object owner;
    private Capture capture;
    private long generation;
    private boolean captureAttempted;
    private long staleDeviceEvents;
    private boolean closed;

    public synchronized void publishNegotiation(
        boolean requested,
        boolean extensionSupported,
        boolean featureSupported,
        boolean enabled,
        String unavailableReason
    ) {
        if (this.closed) {
            return;
        }
        this.pending = new Negotiation(
            requested,
            extensionSupported,
            featureSupported,
            enabled,
            normalizedReason(
                unavailableReason,
                enabled ? "" : "unavailable"
            )
        );
        if (this.owner == null) {
            this.publish(
                this.pending,
                false,
                enabled
                    ? CaptureStatus.AWAITING_DEVICE
                    : requested
                        ? CaptureStatus.UNAVAILABLE
                        : CaptureStatus.NOT_REQUESTED,
                this.pending.unavailableReason(),
                CaptureResult.empty()
            );
        }
    }

    /**
     * Binds a borrowed capture adapter to one exact current device identity.
     * A competing live owner fails closed instead of replacing native state.
     */
    public synchronized void attachVulkanDevice(
        Object device,
        boolean functionResolved,
        Capture capture,
        String unavailableReason
    ) {
        Objects.requireNonNull(device, "device");
        if (this.closed) {
            return;
        }
        if (this.owner != null && this.owner != device) {
            this.capture = null;
            this.owner = null;
            this.captureAttempted = false;
            this.generation = incrementSaturated(this.generation);
            this.pending = this.pending.disabled("device-owner-conflict");
            this.publish(
                this.pending,
                false,
                CaptureStatus.UNAVAILABLE,
                "device-owner-conflict",
                CaptureResult.empty()
            );
            return;
        }

        this.owner = device;
        this.generation = incrementSaturated(this.generation);
        this.captureAttempted = false;
        boolean resolved =
            this.pending.enabled() && functionResolved && capture != null;
        this.capture = resolved ? capture : null;
        String reason = resolved
            ? ""
            : normalizedReason(
                unavailableReason,
                this.pending.enabled()
                    ? "function-unresolved"
                    : this.pending.unavailableReason()
            );
        this.publish(
            this.pending,
            resolved,
            resolved
                ? CaptureStatus.READY_NOT_CAPTURED
                : this.pending.requested()
                    ? CaptureStatus.UNAVAILABLE
                    : CaptureStatus.NOT_REQUESTED,
            reason,
            CaptureResult.empty()
        );
    }

    /**
     * Observes Vulkan results without polling. Only an exact device-lost
     * result for the current owner can invoke the capture adapter.
     */
    public synchronized Snapshot recordResult(
        Object device,
        int result,
        String context
    ) {
        if (result != VK_ERROR_DEVICE_LOST) {
            return this.snapshot;
        }
        if (this.closed) {
            return this.snapshot;
        }
        if (device == null || device != this.owner) {
            this.staleDeviceEvents =
                incrementSaturated(this.staleDeviceEvents);
            return this.snapshot;
        }
        if (this.capture == null || !this.snapshot.functionResolved()) {
            return this.snapshot;
        }
        if (this.captureAttempted) {
            return this.snapshot;
        }

        this.captureAttempted = true;
        CaptureResult resultSnapshot;
        try {
            resultSnapshot = Objects.requireNonNull(
                this.capture.capture(),
                "capture result"
            );
        } catch (Throwable error) {
            resultSnapshot = CaptureResult.unavailable(
                "capture-threw:" + error.getClass().getSimpleName()
            );
        }
        String reason = resultSnapshot.available()
            ? ""
            : normalizedReason(
                resultSnapshot.unavailableReason(),
                "capture-unavailable"
            );
        this.publish(
            this.pending,
            true,
            resultSnapshot.available()
                ? resultSnapshot.truncated()
                    ? CaptureStatus.CAPTURED_TRUNCATED
                    : CaptureStatus.CAPTURED
                : CaptureStatus.UNAVAILABLE,
            reason,
            resultSnapshot.withContext(context)
        );
        return this.snapshot;
    }

    /**
     * Detaches before Mojang destroys the device. A stale close never clears
     * a newer generation.
     */
    public synchronized void vulkanDeviceClosing(Object device) {
        if (this.closed || device == null) {
            return;
        }
        if (device != this.owner) {
            this.staleDeviceEvents =
                incrementSaturated(this.staleDeviceEvents);
            return;
        }
        this.owner = null;
        this.capture = null;
        this.captureAttempted = false;
        Snapshot previous = this.snapshot;
        this.snapshot = new Snapshot(
            previous.requested(),
            previous.extensionSupported(),
            previous.featureSupported(),
            previous.enabled(),
            false,
            CaptureStatus.CLOSED,
            "device-closing",
            previous.generation(),
            previous.captureAttempted(),
            previous.captureContext(),
            previous.description(),
            previous.addressInfoCountReported(),
            previous.addressInfos(),
            previous.vendorInfoCountReported(),
            previous.vendorInfos(),
            previous.vendorBinaryBytesReported(),
            previous.truncated(),
            this.staleDeviceEvents
        );
    }

    public synchronized void notVulkanBackend() {
        if (this.closed) {
            return;
        }
        this.owner = null;
        this.capture = null;
        this.captureAttempted = false;
        this.pending = new Negotiation(
            false,
            false,
            false,
            false,
            "not-vulkan"
        );
        this.publish(
            this.pending,
            false,
            CaptureStatus.NOT_REQUESTED,
            "not-vulkan",
            CaptureResult.empty()
        );
    }

    public Snapshot snapshot() {
        return this.snapshot;
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.owner = null;
        this.capture = null;
        this.captureAttempted = false;
        Snapshot previous = this.snapshot;
        this.snapshot = new Snapshot(
            previous.requested(),
            previous.extensionSupported(),
            previous.featureSupported(),
            previous.enabled(),
            false,
            CaptureStatus.CLOSED,
            "closed",
            previous.generation(),
            previous.captureAttempted(),
            previous.captureContext(),
            previous.description(),
            previous.addressInfoCountReported(),
            previous.addressInfos(),
            previous.vendorInfoCountReported(),
            previous.vendorInfos(),
            previous.vendorBinaryBytesReported(),
            previous.truncated(),
            this.staleDeviceEvents
        );
    }

    private void publish(
        Negotiation negotiation,
        boolean functionResolved,
        CaptureStatus status,
        String unavailableReason,
        CaptureResult captureResult
    ) {
        this.snapshot = new Snapshot(
            negotiation.requested(),
            negotiation.extensionSupported(),
            negotiation.featureSupported(),
            negotiation.enabled(),
            functionResolved,
            status,
            normalizedReason(unavailableReason, ""),
            this.generation,
            this.captureAttempted,
            captureResult.context(),
            captureResult.description(),
            captureResult.addressInfoCountReported(),
            captureResult.addressInfos(),
            captureResult.vendorInfoCountReported(),
            captureResult.vendorInfos(),
            captureResult.vendorBinaryBytesReported(),
            captureResult.truncated(),
            this.staleDeviceEvents
        );
    }

    private static String normalizedReason(
        String candidate,
        String fallback
    ) {
        return candidate == null || candidate.isBlank()
            ? fallback
            : candidate;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    @FunctionalInterface
    public interface Capture {
        CaptureResult capture();
    }

    public enum CaptureStatus {
        NOT_REQUESTED,
        AWAITING_DEVICE,
        READY_NOT_CAPTURED,
        CAPTURED,
        CAPTURED_TRUNCATED,
        UNAVAILABLE,
        CLOSED
    }

    public record AddressInfo(
        int addressType,
        long reportedAddress,
        long addressPrecision
    ) {
    }

    public record VendorInfo(
        String description,
        long vendorFaultCode,
        long vendorFaultData
    ) {
        public VendorInfo {
            description = Objects.requireNonNull(description, "description");
        }
    }

    public record CaptureResult(
        boolean available,
        boolean truncated,
        String unavailableReason,
        String context,
        String description,
        long addressInfoCountReported,
        List<AddressInfo> addressInfos,
        long vendorInfoCountReported,
        List<VendorInfo> vendorInfos,
        long vendorBinaryBytesReported
    ) {
        public CaptureResult {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            context = Objects.requireNonNull(context, "context");
            description = Objects.requireNonNull(
                description,
                "description"
            );
            addressInfos = List.copyOf(addressInfos);
            vendorInfos = List.copyOf(vendorInfos);
        }

        public static CaptureResult captured(
            boolean truncated,
            String description,
            long addressInfoCountReported,
            List<AddressInfo> addressInfos,
            long vendorInfoCountReported,
            List<VendorInfo> vendorInfos,
            long vendorBinaryBytesReported
        ) {
            return new CaptureResult(
                true,
                truncated,
                "",
                "",
                normalizedReason(description, "unavailable"),
                addressInfoCountReported,
                addressInfos,
                vendorInfoCountReported,
                vendorInfos,
                vendorBinaryBytesReported
            );
        }

        public static CaptureResult unavailable(String reason) {
            return new CaptureResult(
                false,
                false,
                normalizedReason(reason, "unavailable"),
                "",
                "unavailable",
                0L,
                List.of(),
                0L,
                List.of(),
                0L
            );
        }

        private static CaptureResult empty() {
            return new CaptureResult(
                false,
                false,
                "",
                "",
                "unavailable",
                0L,
                List.of(),
                0L,
                List.of(),
                0L
            );
        }

        private CaptureResult withContext(String value) {
            return new CaptureResult(
                this.available,
                this.truncated,
                this.unavailableReason,
                normalizedReason(value, "device-lost"),
                this.description,
                this.addressInfoCountReported,
                this.addressInfos,
                this.vendorInfoCountReported,
                this.vendorInfos,
                this.vendorBinaryBytesReported
            );
        }
    }

    public record Snapshot(
        boolean requested,
        boolean extensionSupported,
        boolean featureSupported,
        boolean enabled,
        boolean functionResolved,
        CaptureStatus captureStatus,
        String unavailableReason,
        long generation,
        boolean captureAttempted,
        String captureContext,
        String description,
        long addressInfoCountReported,
        List<AddressInfo> addressInfos,
        long vendorInfoCountReported,
        List<VendorInfo> vendorInfos,
        long vendorBinaryBytesReported,
        boolean truncated,
        long staleDeviceEvents
    ) {
        public Snapshot {
            captureStatus = Objects.requireNonNull(
                captureStatus,
                "captureStatus"
            );
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
            captureContext = Objects.requireNonNull(
                captureContext,
                "captureContext"
            );
            description = Objects.requireNonNull(
                description,
                "description"
            );
            addressInfos = List.copyOf(addressInfos);
            vendorInfos = List.copyOf(vendorInfos);
        }

        private static Snapshot initial() {
            return new Snapshot(
                false,
                false,
                false,
                false,
                false,
                CaptureStatus.NOT_REQUESTED,
                "not-evaluated",
                0L,
                false,
                "",
                "unavailable",
                0L,
                List.of(),
                0L,
                List.of(),
                0L,
                false,
                0L
            );
        }
    }

    private record Negotiation(
        boolean requested,
        boolean extensionSupported,
        boolean featureSupported,
        boolean enabled,
        String unavailableReason
    ) {
        private Negotiation {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
        }

        private static Negotiation initial() {
            return new Negotiation(
                false,
                false,
                false,
                false,
                "not-evaluated"
            );
        }

        private Negotiation disabled(String reason) {
            return new Negotiation(
                this.requested,
                this.extensionSupported,
                this.featureSupported,
                false,
                reason
            );
        }
    }
}
