package de.morau.blockframe.core;

import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import java.util.Locale;

/** Immutable snapshot of the Blaze3D capabilities used by BlockFrame. */
public record EngineCapabilities(
    Backend backend,
    String deviceName,
    String vendorName,
    String driverInfo,
    boolean drawIndirect,
    boolean multiDrawIndirect,
    boolean multiDrawDirect,
    boolean shaderDrawParameters,
    boolean nonZeroFirstInstance,
    boolean persistentMapping,
    boolean compute,
    boolean bufferDeviceAddressAvailable,
    boolean bufferDeviceAddressEnabled,
    int maxAnisotropy,
    int minUniformOffsetAlignment,
    int maxTextureSize,
    long maxMemoryAllocationSize,
    int maxMultiDrawDirectDrawCount,
    int maxColorAttachments,
    float timestampPeriod
) {
    public EngineCapabilities {
        backend = backend == null ? Backend.UNKNOWN : backend;
        deviceName = normalized(deviceName, "Unknown device");
        vendorName = normalized(vendorName, "Unknown vendor");
        driverInfo = normalized(driverInfo, "Unknown driver");
        bufferDeviceAddressAvailable = backend == Backend.VULKAN && bufferDeviceAddressAvailable;
        bufferDeviceAddressEnabled = bufferDeviceAddressAvailable && bufferDeviceAddressEnabled;
        compute = backend == Backend.VULKAN && compute;
        maxAnisotropy = Math.max(0, maxAnisotropy);
        minUniformOffsetAlignment = Math.max(0, minUniformOffsetAlignment);
        maxTextureSize = Math.max(0, maxTextureSize);
        maxMemoryAllocationSize = Math.max(0L, maxMemoryAllocationSize);
        maxMultiDrawDirectDrawCount = Math.max(0, maxMultiDrawDirectDrawCount);
        maxColorAttachments = Math.max(0, maxColorAttachments);
        timestampPeriod = Float.isFinite(timestampPeriod) && timestampPeriod > 0.0F ? timestampPeriod : 0.0F;
    }

    public static EngineCapabilities from(
        DeviceInfo info,
        boolean bufferDeviceAddressAvailable,
        boolean bufferDeviceAddressEnabled
    ) {
        if (info == null) {
            return unknown();
        }

        DeviceFeatures features = info.features();
        DeviceLimits limits = info.limits();
        return fromSnapshot(
            new DeviceSnapshot(
                info.backendName(),
                info.name(),
                info.vendorName(),
                info.driverInfo(),
                info.timestampPeriod(),
                features == null
                    ? FeatureSnapshot.disabled()
                    : new FeatureSnapshot(
                        features.drawIndirect(),
                        features.multiDrawIndirect(),
                        features.multiDrawDirectInterleaved(),
                        features.multiDrawDirectSeparate(),
                        features.shaderDrawParameters(),
                        features.nonZeroFirstInstance(),
                        features.persistentMapping()
                    ),
                limits == null
                    ? LimitSnapshot.empty()
                    : new LimitSnapshot(
                        limits.maxAnisotropy(),
                        limits.minUniformOffsetAlignment(),
                        limits.maxTextureSize(),
                        limits.maxMemoryAllocationSize(),
                        limits.maxMultiDrawDirectInterleavedDrawCount(),
                        limits.maxColorAttachments()
                    )
            ),
            bufferDeviceAddressAvailable,
            bufferDeviceAddressEnabled
        );
    }

    /**
     * Pure mapping entry point for tests and non-Mojang capability providers.
     * The production {@link #from(DeviceInfo, boolean, boolean)} adapter uses
     * the same mapping.
     */
    public static EngineCapabilities fromSnapshot(
        DeviceSnapshot info,
        boolean bufferDeviceAddressAvailable,
        boolean bufferDeviceAddressEnabled
    ) {
        if (info == null) {
            return unknown();
        }

        Backend backend = Backend.from(info.backendName());
        FeatureSnapshot features = info.features() == null ? FeatureSnapshot.disabled() : info.features();
        LimitSnapshot limits = info.limits() == null ? LimitSnapshot.empty() : info.limits();
        return new EngineCapabilities(
            backend,
            info.name(),
            info.vendorName(),
            info.driverInfo(),
            features.drawIndirect(),
            features.multiDrawIndirect(),
            features.multiDrawDirectInterleaved() || features.multiDrawDirectSeparate(),
            features.shaderDrawParameters(),
            features.nonZeroFirstInstance(),
            features.persistentMapping(),
            false,
            bufferDeviceAddressAvailable,
            bufferDeviceAddressEnabled,
            limits.maxAnisotropy(),
            limits.minUniformOffsetAlignment(),
            limits.maxTextureSize(),
            limits.maxMemoryAllocationSize(),
            limits.maxMultiDrawDirectDrawCount(),
            limits.maxColorAttachments(),
            info.timestampPeriod()
        );
    }

    public static EngineCapabilities unknown() {
        return new EngineCapabilities(
            Backend.UNKNOWN,
            "Unknown device",
            "Unknown vendor",
            "Unknown driver",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            0,
            0L,
            0,
            0,
            0.0F
        );
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DeviceSnapshot(
        String backendName,
        String name,
        String vendorName,
        String driverInfo,
        float timestampPeriod,
        FeatureSnapshot features,
        LimitSnapshot limits
    ) {
    }

    public record FeatureSnapshot(
        boolean drawIndirect,
        boolean multiDrawIndirect,
        boolean multiDrawDirectInterleaved,
        boolean multiDrawDirectSeparate,
        boolean shaderDrawParameters,
        boolean nonZeroFirstInstance,
        boolean persistentMapping
    ) {
        public static FeatureSnapshot disabled() {
            return new FeatureSnapshot(false, false, false, false, false, false, false);
        }
    }

    public record LimitSnapshot(
        int maxAnisotropy,
        int minUniformOffsetAlignment,
        int maxTextureSize,
        long maxMemoryAllocationSize,
        int maxMultiDrawDirectDrawCount,
        int maxColorAttachments
    ) {
        public static LimitSnapshot empty() {
            return new LimitSnapshot(0, 0, 0, 0L, 0, 0);
        }
    }

    public enum Backend {
        UNKNOWN,
        OPENGL,
        VULKAN;

        public static Backend from(String backendName) {
            if (backendName == null) {
                return UNKNOWN;
            }

            String normalized = backendName.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("vulkan")) {
                return VULKAN;
            }
            if (normalized.contains("opengl") || normalized.equals("gl")) {
                return OPENGL;
            }
            return UNKNOWN;
        }
    }
}
