package de.morau.blockframe.core.diagnostics;

import java.util.Objects;

/**
 * Pure cached-status projection used by the central optional-feature state.
 *
 * <p>It performs no probe. OpenGL makes VRAM telemetry not applicable, while
 * a Vulkan-side unavailable channel remains an explicit partial fallback.</p>
 */
public record PhysicalMemoryFeatureAvailability(
    boolean supported,
    boolean effective,
    boolean fallback,
    String reason
) {
    public PhysicalMemoryFeatureAvailability {
        reason = Objects.requireNonNull(reason, "reason");
    }

    public static PhysicalMemoryFeatureAvailability from(
        PhysicalMemoryTelemetry.RamStatus ram,
        PhysicalMemoryTelemetry.DeviceStatus device
    ) {
        Objects.requireNonNull(ram, "ram");
        Objects.requireNonNull(device, "device");

        boolean ramSupported = switch (ram) {
            case NOT_SAMPLED, AVAILABLE, QUERY_FAILED -> true;
            default -> false;
        };
        boolean deviceSupported = switch (device) {
            case NOT_SAMPLED,
                AVAILABLE,
                NO_DEVICE_LOCAL_HEAP,
                QUERY_FAILED,
                WRONG_THREAD,
                OWNER_CONFLICT -> true;
            default -> false;
        };
        boolean ramAvailable =
            ram == PhysicalMemoryTelemetry.RamStatus.AVAILABLE;
        boolean deviceAvailable =
            device
                == PhysicalMemoryTelemetry.DeviceStatus.AVAILABLE;
        boolean deviceNotApplicable =
            device
                == PhysicalMemoryTelemetry.DeviceStatus.NOT_VULKAN;
        boolean effective = ramAvailable || deviceAvailable;
        boolean fallback =
            !effective
                || !ramAvailable
                || (!deviceNotApplicable && !deviceAvailable);
        return new PhysicalMemoryFeatureAvailability(
            ramSupported || deviceSupported,
            effective,
            fallback,
            "ram-"
                + ramCode(ram)
                + "_vram-"
                + deviceCode(device)
        );
    }

    private static String ramCode(
        PhysicalMemoryTelemetry.RamStatus status
    ) {
        return switch (status) {
            case DISABLED -> "disabled";
            case NOT_SAMPLED -> "not-sampled";
            case AVAILABLE -> "available";
            case UNSUPPORTED -> "unsupported";
            case QUERY_FAILED -> "query-failed";
            case CLOSED -> "closed";
        };
    }

    private static String deviceCode(
        PhysicalMemoryTelemetry.DeviceStatus status
    ) {
        return switch (status) {
            case DISABLED -> "disabled";
            case NOT_REQUESTED -> "not-requested";
            case NOT_VULKAN -> "not-vulkan";
            case NOT_SAMPLED -> "not-sampled";
            case AVAILABLE -> "available";
            case EXTENSION_NOT_ADVERTISED ->
                "extension-not-advertised";
            case NO_DEVICE_LOCAL_HEAP -> "no-device-local-heap";
            case QUERY_FAILED -> "query-failed";
            case WRONG_THREAD -> "wrong-thread";
            case OWNER_CONFLICT -> "owner-conflict";
            case DEVICE_CLOSING -> "device-closing";
            case CLOSED -> "closed";
        };
    }
}
