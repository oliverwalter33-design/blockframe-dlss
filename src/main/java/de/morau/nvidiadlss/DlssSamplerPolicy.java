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
import org.lwjgl.vulkan.VK12;

/**
 * Material-aware Vulkan sampler policy. Upscaling bias is never applied to
 * cutout terrain, and every BlockFrame-owned sampler remains device-scoped
 * and bounded.
 */
public final class DlssSamplerPolicy {
    static final int MAX_MATERIAL_SAMPLERS = 64;
    static final long CACHE_METADATA_REQUESTED_BYTES = 7_680L;
    static final long CACHE_METADATA_COMMITTED_BYTES = 8_192L;

    private static final ThreadLocal<BiasState> CONSTRUCTION_BIAS =
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
    private static FixedMaterialSamplerCache activeCache;
    private static FixedMaterialSamplerCache retiringCache;
    private static FixedMaterialSamplerCache.LeaseController
        pendingCreationLeases;
    private static long pendingCreationLease;
    private static boolean cacheCreationAttempted;
    private static boolean closePrepareConfirmed;
    private static String status = "not connected";

    private DlssSamplerPolicy() {
    }

    public static float constructionBias(float original) {
        BiasState state = CONSTRUCTION_BIAS.get();
        return state != null && state.active
            ? state.value
            : original;
    }

    public static synchronized float biasFor(GpuSampler sampler) {
        if (sampler == null) {
            return 0.0F;
        }
        float activeBias = activeCache == null
            ? Float.NaN
            : activeCache.biasFor(sampler);
        if (!Float.isNaN(activeBias)) {
            return activeBias;
        }
        float retiringBias = retiringCache == null
            ? Float.NaN
            : retiringCache.biasFor(sampler);
        return Float.isNaN(retiringBias) ? 0.0F : retiringBias;
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
                || cutoutTerrain
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
            float bias = DlssRenderer.currentLodBias();
            if (bias >= -0.001F) {
                return original;
            }
            if (device != lifecycleDevice) {
                status =
                    "original fallback: Vulkan device generation mismatch";
                return original;
            }

            FixedMaterialSamplerCache cache = activeCache;
            if (cache == null) {
                cache = createCache(device);
                if (cache == null) {
                    return original;
                }
            }
            GpuSampler selected = (GpuSampler)cache.select(
                original,
                original.getAddressModeU(),
                original.getAddressModeV(),
                original.getMinFilter(),
                original.getMagFilter(),
                original.getMaxAnisotropy(),
                original.getMaxLod(),
                bias,
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
            activeCache != null
                || retiringCache != null
                || pendingCreationLease != 0L
        ) {
            status =
                "original fallback: prior device ownership remains unconfirmed";
            return;
        }
        lifecycleDevice = device;
        cacheCreationAttempted = false;
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
            return activeCache == null
                && retiringCache == null
                && pendingCreationLease == 0L;
        }
        if (!retryPendingCreationCleanup()) {
            return false;
        }
        FixedMaterialSamplerCache cache = activeCache;
        if (cache == null) {
            status = "closed: no material sampler cache";
            closePrepareConfirmed = true;
            return true;
        }
        if (!cache.prepareDeviceClose(PRODUCTION_CLOSER)) {
            status = cache.status();
            return false;
        }
        activeCache = null;
        if (cache.requiresEncoderDrain()) {
            retiringCache = cache;
            status = cache.status();
        } else {
            cache.finishDeviceCloseAfterEncoderDrain();
            status = "closed: empty material sampler cache";
        }
        closePrepareConfirmed = true;
        return true;
    }

    public static synchronized boolean finishDeviceCloseAfterEncoderDrain(
        VulkanDevice device
    ) {
        if (device == null || device != lifecycleDevice) {
            return closePrepareConfirmed
                && activeCache == null
                && retiringCache == null
                && pendingCreationLease == 0L;
        }
        if (!closePrepareConfirmed) {
            return false;
        }
        if (!retryPendingCreationCleanup()) {
            return false;
        }
        if (activeCache != null) {
            return false;
        }
        FixedMaterialSamplerCache cache = retiringCache;
        if (
            cache != null
                && !cache.finishDeviceCloseAfterEncoderDrain()
        ) {
            status = cache.status();
            return false;
        }
        retiringCache = null;
        lifecycleDevice = null;
        cacheCreationAttempted = false;
        CONSTRUCTION_BIAS.remove();
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
        FixedMaterialSamplerCache cache = activeCache != null
            ? activeCache
            : retiringCache;
        return cache == null
            ? status
            : cache.status();
    }

    static void clearClientThreadState() {
        CONSTRUCTION_BIAS.remove();
    }

    private static FixedMaterialSamplerCache createCache(
        VulkanDevice device
    ) {
        if (cacheCreationAttempted || pendingCreationLease != 0L) {
            return null;
        }
        cacheCreationAttempted = true;
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
            activeCache = cache;
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
        Object addressModeU,
        Object addressModeV,
        Object minFilter,
        Object magFilter,
        int maxAnisotropy,
        OptionalDouble maxLod,
        float bias
    ) {
        VulkanDevice backend = (VulkanDevice)device;
        BiasState state = CONSTRUCTION_BIAS.get();
        boolean localState = state == null;
        if (localState) {
            state = new BiasState();
            CONSTRUCTION_BIAS.set(state);
        }
        boolean previousActive = state.active;
        float previousValue = state.value;
        state.active = true;
        state.value = bias;
        try {
            return backend.createSampler(
                (AddressMode)addressModeU,
                (AddressMode)addressModeV,
                (FilterMode)minFilter,
                (FilterMode)magFilter,
                maxAnisotropy,
                maxLod
            );
        } finally {
            if (localState) {
                CONSTRUCTION_BIAS.remove();
            } else {
                state.active = previousActive;
                state.value = previousValue;
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
            "DLSS-Materialsampler {}/{}: Solid-Terrain LOD-Bias {} / Cutout-Terrain 0 / anisotropisch {}x",
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

    private static final class BiasState {
        private boolean active;
        private float value = Float.NaN;
    }
}
