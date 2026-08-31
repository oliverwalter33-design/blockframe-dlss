package de.morau.blockframe.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Immutable identity and feature observation for the active Vulkan device. */
public record VulkanRuntimeInfo(
    boolean active,
    int apiVersion,
    int vendorId,
    int deviceId,
    int driverId,
    int driverVersion,
    String driverName,
    String driverInfo,
    Set<String> observedDlssExtensionCandidates,
    boolean descriptorIndexing,
    boolean bufferDeviceAddress,
    boolean memoryBudgetExtensionAdvertised
) {
    public VulkanRuntimeInfo {
        if (!active) {
            apiVersion = 0;
            vendorId = 0;
            deviceId = 0;
            driverId = 0;
            driverVersion = 0;
            driverName = "Not a Vulkan device";
            driverInfo = "Not a Vulkan device";
            observedDlssExtensionCandidates = Set.of();
            descriptorIndexing = false;
            bufferDeviceAddress = false;
            memoryBudgetExtensionAdvertised = false;
        } else {
            driverName = normalized(driverName, "Unknown Vulkan driver");
            driverInfo = normalized(driverInfo, "Unknown Vulkan driver info");
            observedDlssExtensionCandidates = Collections.unmodifiableSet(
                new LinkedHashSet<>(
                    Objects.requireNonNull(
                        observedDlssExtensionCandidates,
                        "observedDlssExtensionCandidates"
                    )
                )
            );
        }
    }

    public static VulkanRuntimeInfo unavailable() {
        return new VulkanRuntimeInfo(
            false,
            0,
            0,
            0,
            0,
            0,
            "Not a Vulkan device",
            "Not a Vulkan device",
            Set.of(),
            false,
            false,
            false
        );
    }

    public String apiVersionString() {
        if (!this.active) {
            return "N/A";
        }
        return (this.apiVersion >>> 22 & 0x7f)
            + "."
            + (this.apiVersion >>> 12 & 0x3ff)
            + "."
            + (this.apiVersion & 0xfff);
    }

    public String deviceKey() {
        return String.format(
            "%04x:%04x",
            this.vendorId & 0xffff,
            this.deviceId & 0xffff
        );
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
