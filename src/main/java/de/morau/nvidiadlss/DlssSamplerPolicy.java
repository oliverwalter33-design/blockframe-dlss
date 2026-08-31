package de.morau.nvidiadlss;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.state.FeatureId;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

/**
 * Material-aware Vulkan sampler policy. The dimensions-derived upscaling bias
 * is applied to exact solid and alpha-tested cutout terrain scopes while every
 * BlockFrame-owned sampler remains device-scoped and bounded.
 */
public final class DlssSamplerPolicy {
    static final int MAX_MATERIAL_SAMPLERS = 64;
    static final long CACHE_METADATA_REQUESTED_BYTES = 8_704L;
    static final long CACHE_METADATA_COMMITTED_BYTES = 12_288L;

    private static final ThreadLocal<ConstructionState>
        CONSTRUCTION_STATE =
        new ThreadLocal<>();
    private static final FixedMaterialSamplerCache.SamplerFactory
        PRODUCTION_FACTORY =
        DlssSamplerPolicy::createBiasedSampler;
    private static final FixedMaterialSamplerCache.SamplerObserver
        PRODUCTION_OBSERVER =
        DlssSamplerPolicy::samplerPublished;
    private static final FixedMaterialSamplerCache.SamplerCloser
        PRODUCTION_CLOSER =
            sampler -> ((GpuSampler)sampler).close();
    private static final FixedMaterialSamplerCache.LeaseController
        PRODUCTION_LEASES =
            new RuntimeBudgetLeaseController();

    private static VulkanDevice lifecycleDevice;
    private static FixedMaterialSamplerCacheLifecycle cacheLifecycle;
    private static long deviceGenerationSequence;
    private static FixedMaterialSamplerCache.LeaseController
        pendingCreationLeases;
    private static long pendingCreationLease;
    private static boolean closePrepareConfirmed;
    private static String status = "not connected";

    private DlssSamplerPolicy() {
    }

    /**
     * Captures the descriptor actually submitted to Vulkan. During a private
     * BlockFrame clone construction it first replays the immutable original
     * descriptor and substitutes only the already validated absolute bias.
     */
    public static VulkanSamplerDescriptor prepareSamplerCreateInfo(
        VkSamplerCreateInfo createInfo
    ) {
        Objects.requireNonNull(createInfo, "createInfo");
        ConstructionState state = CONSTRUCTION_STATE.get();
        if (state != null && state.active) {
            state.originalDescriptor.replayInto(
                createInfo,
                state.finalBias,
                MemoryStack.stackGet()
            );
        }
        return VulkanSamplerDescriptor.capture(createInfo);
    }

    public static synchronized float biasFor(GpuSampler sampler) {
        if (sampler == null) {
            return 0.0F;
        }
        FixedMaterialSamplerCache cache = cacheLifecycle == null
            ? null
            : cacheLifecycle.activeCache();
        float activeBias = cache == null
            ? Float.NaN
            : cache.biasFor(sampler);
        if (!Float.isNaN(activeBias)) {
            return activeBias;
        }
        if (
            sampler instanceof VulkanGpuSamplerDescriptorAccess access
                && access.blockframe$samplerDescriptor() != null
        ) {
            return access.blockframe$samplerDescriptor().mipLodBias();
        }
        return 0.0F;
    }

    /**
     * Atomically selects the exact sampler-cache generation committed by the
     * DLSS resource owner. Repeated calls with the same key reuse the existing
     * cache; a resize, mode/preset change, or reload epoch queues the previous
     * private samplers through Mojang before publishing a replacement.
     */
    public static synchronized boolean activateGeneration(
        VulkanDevice device,
        int renderWidth,
        int renderHeight,
        int outputWidth,
        int outputHeight,
        int modeId,
        int presetId,
        float lodBiasDelta,
        long reloadEpoch
    ) {
        if (
            device == null
                || device != lifecycleDevice
                || cacheLifecycle == null
                || closePrepareConfirmed
        ) {
            status =
                "original fallback: sampler lifecycle is not active";
            return false;
        }
        try {
            if (
                !BlockframeRuntime.featureEnabled(
                    FeatureId.MATERIAL_SAMPLER_CACHE
                )
            ) {
                cacheLifecycle.deactivate(PRODUCTION_CLOSER);
                status =
                    "original fallback: disabled by process feature policy";
                return false;
            }
            FixedMaterialSamplerCacheLifecycle.GenerationKey key =
                new FixedMaterialSamplerCacheLifecycle.GenerationKey(
                    device,
                    deviceGenerationSequence,
                    renderWidth,
                    renderHeight,
                    outputWidth,
                    outputHeight,
                    modeId,
                    presetId,
                    lodBiasDelta,
                    reloadEpoch
                );
            FixedMaterialSamplerCache cache = cacheLifecycle.switchTo(
                key,
                () -> createCache(device),
                PRODUCTION_CLOSER
            );
            if (cache == null) {
                status = cacheLifecycle.status();
                return false;
            }
            status = cache.status();
            return true;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            status =
                "original fallback: sampler generation activation failed";
            BlockframeRuntime.featureUsedFallback(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                true,
                true,
                "generation-activation-fallback"
            );
            return false;
        }
    }

    /** OFF, minimize, and non-world states must not retain a stale clone. */
    public static synchronized boolean deactivateGeneration(
        VulkanDevice device
    ) {
        if (
            device == null
                || device != lifecycleDevice
                || cacheLifecycle == null
        ) {
            return true;
        }
        if (closePrepareConfirmed) {
            return true;
        }
        boolean deactivated =
            cacheLifecycle.deactivate(PRODUCTION_CLOSER);
        status = cacheLifecycle.status();
        return deactivated;
    }

    public static synchronized GpuSampler materialSampler(
        VulkanDevice device,
        GpuSampler original,
        boolean cutoutTerrain
    ) {
        if (
            device == null
                || original == null
                || !DlssRenderer.isWorldPass()
        ) {
            return original;
        }
        try {
            if (
                !BlockframeRuntime.featureEnabled(
                    FeatureId.MATERIAL_SAMPLER_CACHE
                )
            ) {
                status =
                    "original fallback: disabled by process feature policy";
                return original;
            }
            float biasDelta = DlssRenderer.currentLodBias();
            if (biasDelta >= -0.001F) {
                return original;
            }
            if (device != lifecycleDevice) {
                status =
                    "original fallback: Vulkan device generation mismatch";
                return original;
            }

            if (
                !(original
                    instanceof VulkanGpuSamplerDescriptorAccess access)
            ) {
                status =
                    "original fallback: Vulkan sampler descriptor unavailable";
                return original;
            }
            VulkanSamplerDescriptor originalDescriptor =
                access.blockframe$samplerDescriptor();
            if (
                originalDescriptor == null
                    || !originalDescriptor.canReplay()
            ) {
                status =
                    "original fallback: sampler pNext/state is not replayable";
                return original;
            }
            if (!(device instanceof VulkanSamplerDeviceLimits limits)) {
                status =
                    "original fallback: sampler LOD-bias limit unavailable";
                return original;
            }
            float maximumBias = limits.blockframe$maxSamplerLodBias();
            float requestedBias =
                originalDescriptor.mipLodBias() + biasDelta;
            float finalBias = finalSamplerBias(
                originalDescriptor.mipLodBias(),
                biasDelta,
                maximumBias
            );
            if (!Float.isFinite(finalBias)) {
                status =
                    "original fallback: invalid sampler LOD-bias inputs";
                return original;
            }
            if (
                Float.floatToRawIntBits(requestedBias)
                    != Float.floatToRawIntBits(finalBias)
            ) {
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS sampler LOD bias clamped from {} to {} by Vulkan maxSamplerLodBias {}",
                    requestedBias,
                    finalBias,
                    maximumBias
                );
            }
            if (
                Float.floatToRawIntBits(finalBias)
                    == originalDescriptor.mipLodBiasBits()
            ) {
                return original;
            }

            FixedMaterialSamplerCache cache = cacheLifecycle == null
                ? null
                : cacheLifecycle.activeCache();
            if (cache == null) {
                status =
                    "original fallback: no committed sampler generation";
                return original;
            }
            int anisotropy = original.getMaxAnisotropy();
            GpuSampler selected = (GpuSampler)cache.select(
                original,
                originalDescriptor,
                original.getAddressModeU(),
                original.getAddressModeV(),
                original.getMinFilter(),
                original.getMagFilter(),
                anisotropy,
                original.getMaxLod(),
                finalBias,
                PRODUCTION_FACTORY,
                PRODUCTION_OBSERVER
            );
            if (selected == original) {
                BlockframeRuntime.featureUsedFallback(
                    FeatureId.MATERIAL_SAMPLER_CACHE,
                    true,
                    false,
                    "fixed-capacity-original-sampler-fallback"
                );
            } else {
                BlockframeRuntime.featureBecameEffective(
                    FeatureId.MATERIAL_SAMPLER_CACHE,
                    "fixed-material-sampler-selected"
                );
            }
            return selected;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            status =
                "original fallback: material sampler policy failure";
            BlockframeRuntime.featureUsedFallback(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                true,
                true,
                "runtime-quarantine-original-sampler"
            );
            try {
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Materialsampler-Pfad fiel auf den Originalsampler zurück",
                    error
                );
            } catch (Throwable ignored) {
                // The authoritative same-frame fallback is already selected.
            }
            return original;
        }
    }

    public static synchronized void deviceConnected(VulkanDevice device) {
        Objects.requireNonNull(device, "device");
        if (lifecycleDevice == device) {
            return;
        }
        if (
            cacheLifecycle != null
                || pendingCreationLease != 0L
        ) {
            status =
                "original fallback: prior device ownership remains unconfirmed";
            return;
        }
        if (deviceGenerationSequence == Long.MAX_VALUE) {
            status =
                "original fallback: Vulkan device generation exhausted";
            return;
        }
        long nextGeneration = deviceGenerationSequence + 1L;
        FixedMaterialSamplerCacheLifecycle nextLifecycle =
            new FixedMaterialSamplerCacheLifecycle(
                device,
                nextGeneration
            );
        deviceGenerationSequence = nextGeneration;
        lifecycleDevice = device;
        cacheLifecycle = nextLifecycle;
        closePrepareConfirmed = false;
        status = "connected; cache not requested";
    }

    /**
     * Queues live Mojang samplers while the Vulkan encoder still exists.
     * A thrown close is permanently uncertain because Mojang marks the
     * sampler closed before queue insertion.
     */
    public static synchronized boolean prepareDeviceClose(
        VulkanDevice device
    ) {
        if (
            device != null
                && device == lifecycleDevice
                && closePrepareConfirmed
                && pendingCreationLease == 0L
        ) {
            return true;
        }
        if (device == null || device != lifecycleDevice) {
            return cacheLifecycle == null
                && pendingCreationLease == 0L;
        }
        if (!retryPendingCreationCleanup()) {
            return false;
        }
        FixedMaterialSamplerCacheLifecycle lifecycle = cacheLifecycle;
        if (
            lifecycle != null
                && !lifecycle.prepareDeviceClose(PRODUCTION_CLOSER)
        ) {
            status = lifecycle.status();
            return false;
        }
        closePrepareConfirmed = true;
        status = lifecycle == null
            ? "closed: no material sampler lifecycle"
            : lifecycle.status();
        return true;
    }

    public static synchronized boolean finishDeviceCloseAfterEncoderDrain(
        VulkanDevice device
    ) {
        if (device == null || device != lifecycleDevice) {
            return closePrepareConfirmed
                && cacheLifecycle == null
                && pendingCreationLease == 0L;
        }
        if (!closePrepareConfirmed) {
            return false;
        }
        if (!retryPendingCreationCleanup()) {
            return false;
        }
        FixedMaterialSamplerCacheLifecycle lifecycle = cacheLifecycle;
        if (
            lifecycle != null
                && !lifecycle.finishDeviceCloseAfterEncoderDrain()
        ) {
            status = lifecycle.status();
            return false;
        }
        cacheLifecycle = null;
        lifecycleDevice = null;
        CONSTRUCTION_STATE.remove();
        status = "closed after encoder drain";
        return true;
    }

    /**
     * Retries only a cache-construction lease release. It never attempts to
     * queue a sampler after the owning Vulkan encoder is gone.
     */
    public static synchronized boolean retryPendingCreationCleanup() {
        if (pendingCreationLease == 0L) {
            pendingCreationLeases = null;
            return true;
        }
        FixedMaterialSamplerCache.LeaseController leases =
            pendingCreationLeases;
        if (leases == null) {
            status = "original fallback: orphaned metadata lease";
            return false;
        }
        try {
            if (!leases.release(pendingCreationLease)) {
                status =
                    "original fallback: metadata lease release deferred";
                return false;
            }
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            status =
                "original fallback: metadata lease release failure";
            return false;
        }
        pendingCreationLease = 0L;
        pendingCreationLeases = null;
        return true;
    }

    public static synchronized String debugStatus() {
        FixedMaterialSamplerCacheLifecycle lifecycle = cacheLifecycle;
        if (lifecycle == null) {
            return status;
        }
        FixedMaterialSamplerCache cache = lifecycle.activeCache();
        return cache == null ? lifecycle.status() : cache.status();
    }

    static void clearClientThreadState() {
        CONSTRUCTION_STATE.remove();
    }

    static int materialAnisotropy(
        int original,
        int deviceMaximum
    ) {
        int supported = Math.max(1, deviceMaximum);
        return Math.clamp(original, 1, supported);
    }

    static float finalSamplerBias(
        float nativeBias,
        float delta,
        float maximumAbsoluteBias
    ) {
        if (
            !Float.isFinite(nativeBias)
                || !Float.isFinite(delta)
                || !Float.isFinite(maximumAbsoluteBias)
                || maximumAbsoluteBias < 0.0F
        ) {
            return Float.NaN;
        }
        float requested = nativeBias + delta;
        if (!Float.isFinite(requested)) {
            return Float.NaN;
        }
        return Math.max(
            -maximumAbsoluteBias,
            Math.min(maximumAbsoluteBias, requested)
        );
    }

    private static FixedMaterialSamplerCache createCache(
        VulkanDevice device
    ) {
        if (pendingCreationLease != 0L) {
            return null;
        }
        ShaderResourceInventory inventory;
        try {
            inventory = BlockframeRuntime.shaderResources();
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            status = "original fallback: unavailable runtime inventory";
            BlockframeRuntime.featureUsedFallback(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                true,
                false,
                "inventory-unavailable-fallback"
            );
            return null;
        }
        FixedMaterialSamplerCache.LeaseController leases =
            PRODUCTION_LEASES;
        long lease;
        try {
            lease = leases.tryReserve(
                CACHE_METADATA_REQUESTED_BYTES,
                CACHE_METADATA_COMMITTED_BYTES
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            status = "original fallback: metadata budget failure";
            BlockframeRuntime.featureUsedFallback(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                true,
                false,
                "metadata-budget-fallback"
            );
            return null;
        }
        if (lease == 0L) {
            status =
                "original fallback: RAM/SHADER_RESOURCES budget rejected";
            BlockframeRuntime.featureUsedFallback(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                true,
                false,
                "ram-shader-resources-budget-fallback"
            );
            return null;
        }
        pendingCreationLeases = leases;
        pendingCreationLease = lease;
        try {
            FixedMaterialSamplerCache cache =
                new FixedMaterialSamplerCache(
                device,
                leases,
                lease,
                inventory,
                MAX_MATERIAL_SAMPLERS
            );
            pendingCreationLease = 0L;
            pendingCreationLeases = null;
            status =
                "active: 0/"
                    + MAX_MATERIAL_SAMPLERS
                    + " material samplers";
            BlockframeRuntime.featureBecameEffective(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                "fixed-cache-owned-and-budgeted"
            );
            return cache;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            status = "original fallback: cache allocation failure";
            BlockframeRuntime.featureUsedFallback(
                FeatureId.MATERIAL_SAMPLER_CACHE,
                true,
                true,
                "allocation-cleanup-fallback"
            );
            retryPendingCreationCleanup();
            return null;
        }
    }

    private static GpuSampler createBiasedSampler(
        Object device,
        Object samplerDescriptor,
        Object addressModeU,
        Object addressModeV,
        Object minFilter,
        Object magFilter,
        int maxAnisotropy,
        OptionalDouble maxLod,
        float bias
    ) {
        VulkanDevice backend = (VulkanDevice)device;
        if (
            !(samplerDescriptor
                instanceof VulkanSamplerDescriptor originalDescriptor)
                || !originalDescriptor.canReplay()
        ) {
            throw new IllegalArgumentException(
                "sampler descriptor is not safely replayable"
            );
        }
        ConstructionState state = CONSTRUCTION_STATE.get();
        boolean localState = state == null;
        if (localState) {
            state = new ConstructionState();
            CONSTRUCTION_STATE.set(state);
        }
        boolean previousActive = state.active;
        VulkanSamplerDescriptor previousDescriptor =
            state.originalDescriptor;
        float previousBias = state.finalBias;
        state.active = true;
        state.originalDescriptor = originalDescriptor;
        state.finalBias = bias;
        try {
            GpuSampler created = backend.createSampler(
                (AddressMode)addressModeU,
                (AddressMode)addressModeV,
                (FilterMode)minFilter,
                (FilterMode)magFilter,
                maxAnisotropy,
                maxLod
            );
            VulkanSamplerDescriptor actualDescriptor =
                created
                        instanceof VulkanGpuSamplerDescriptorAccess access
                    ? access.blockframe$samplerDescriptor()
                    : null;
            if (
                actualDescriptor == null
                    || !actualDescriptor.canReplay()
                    || !actualDescriptor.matchesReplayOf(
                        originalDescriptor,
                        bias
                    )
            ) {
                IllegalStateException rejection =
                    new IllegalStateException(
                        "created Vulkan sampler descriptor failed replay verification"
                    );
                try {
                    created.close();
                } catch (Throwable closeError) {
                    rejection.addSuppressed(closeError);
                }
                throw rejection;
            }
            return created;
        } finally {
            if (localState) {
                CONSTRUCTION_STATE.remove();
            } else {
                state.active = previousActive;
                state.originalDescriptor = previousDescriptor;
                state.finalBias = previousBias;
            }
        }
    }

    private static void samplerPublished(
        Object device,
        Object sampler,
        int slot,
        float bias,
        int anisotropy
    ) {
        VulkanDevice backend = (VulkanDevice)device;
        if (
            DeveloperDiagnostics.ENABLED
                && sampler instanceof VulkanGpuSampler vulkanSampler
        ) {
            GpuPassDiagnostics.setObjectName(
                backend.instance().debug(),
                backend.vkDevice(),
                VK12.VK_OBJECT_TYPE_SAMPLER,
                vulkanSampler.vkSampler(),
                "BlockFrame / DLSS Material Sampler " + slot
            );
        }
        NvidiaDlssMod.LOGGER.info(
            "DLSS-Materialsampler {}/{}: Solid-/Cutout-Terrain LOD-Bias {} / anisotropisch {}x",
            slot + 1,
            MAX_MATERIAL_SAMPLERS,
            String.format(Locale.ROOT, "%.3f", bias),
            anisotropy
        );
    }

    private static final class RuntimeBudgetLeaseController
        implements FixedMaterialSamplerCache.LeaseController {
        @Override
        public long tryReserve(long requested, long committed) {
            return BlockframeRuntime.memoryBudgets().tryReserve(
                MemoryKind.RAM,
                MemoryCategory.SHADER_RESOURCES,
                requested,
                committed,
                null
            );
        }

        @Override
        public boolean release(long token) {
            return BlockframeRuntime.memoryBudgets().release(token);
        }

        @Override
        public boolean retireAfterGpuUse(long token) {
            return BlockframeRuntime
                .memoryBudgets()
                .retireAfterGpuUse(token);
        }
    }

    private static final class ConstructionState {
        private boolean active;
        private VulkanSamplerDescriptor originalDescriptor;
        private float finalBias = Float.NaN;
    }
}
