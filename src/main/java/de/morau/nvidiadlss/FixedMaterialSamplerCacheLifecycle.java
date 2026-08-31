package de.morau.nvidiadlss;

import java.util.Objects;

/**
 * Device-scoped, bounded generation owner for material-sampler caches.
 *
 * <p>Generation changes are serialized. The old cache first transfers every
 * live sampler to Mojang's submit-rotated destruction queue, then releases
 * its lookup arrays, and only then can a replacement become active. Normal
 * operation therefore retains at most one active cache. A failed queue
 * transfer retains exactly one uncertain cache and permanently fails closed
 * for that Vulkan-device generation.</p>
 *
 * <p>The caller must serialize selection through the same policy lock used
 * for {@link #switchTo switchTo}; {@code DlssSamplerPolicy}'s synchronized
 * entry points provide that lock. This class also synchronizes its own state
 * so lifecycle diagnostics and shutdown cannot observe a partial switch.</p>
 */
final class FixedMaterialSamplerCacheLifecycle {
    private final Object device;
    private final long deviceGeneration;

    private FixedMaterialSamplerCache activeCache;
    private GenerationKey activeKey;
    private GenerationKey failedCreationKey;
    private FixedMaterialSamplerCache uncertainCache;
    private long successfulSwitches;
    private boolean closePrepared;
    private boolean finished;
    private String state = "connected; no sampler generation";

    FixedMaterialSamplerCacheLifecycle(
        Object device,
        long deviceGeneration
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (deviceGeneration <= 0L) {
            throw new IllegalArgumentException(
                "device generation must be positive"
            );
        }
        this.deviceGeneration = deviceGeneration;
    }

    /**
     * Returns the cache for an unchanged exact generation, or atomically
     * retires the old generation and creates a replacement.
     *
     * <p>A creation failure is memoized for the exact key. A resize, mode or
     * preset change, or a new reload epoch changes the key and permits one
     * new attempt without introducing per-frame allocation.</p>
     */
    synchronized FixedMaterialSamplerCache switchTo(
        GenerationKey key,
        CacheFactory factory,
        FixedMaterialSamplerCache.SamplerCloser closer
    ) {
        requireOwnedKey(key);
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(closer, "closer");
        if (this.closePrepared || this.finished) {
            this.state = "original fallback: lifecycle is closing";
            return null;
        }
        if (this.uncertainCache != null) {
            this.state =
                "original fallback: prior sampler retirement is uncertain";
            return null;
        }
        if (key.equals(this.activeKey)) {
            return this.activeCache;
        }
        if (key.equals(this.failedCreationKey)) {
            this.state =
                "original fallback: generation creation already failed";
            return null;
        }

        if (!retireActive(closer)) {
            return null;
        }

        FixedMaterialSamplerCache replacement;
        try {
            replacement = factory.create();
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            this.failedCreationKey = key;
            this.state =
                "original fallback: generation cache creation failed";
            return null;
        }
        if (replacement == null) {
            this.failedCreationKey = key;
            this.state =
                "original fallback: generation cache factory returned null";
            return null;
        }
        this.activeCache = replacement;
        this.activeKey = key;
        this.failedCreationKey = null;
        this.successfulSwitches++;
        this.state =
            "active sampler generation " + this.successfulSwitches;
        return replacement;
    }

    /**
     * Makes OFF/minimized state authoritative without retaining a stale
     * cache for a later restore.
     */
    synchronized boolean deactivate(
        FixedMaterialSamplerCache.SamplerCloser closer
    ) {
        Objects.requireNonNull(closer, "closer");
        if (this.finished) {
            return true;
        }
        if (this.uncertainCache != null) {
            this.state =
                "cleanup uncertain: sampler generation owner retained";
            return false;
        }
        if (!retireActive(closer)) {
            return false;
        }
        this.failedCreationKey = null;
        this.state = "inactive; no stale sampler generation";
        return true;
    }

    /** Queues the final active generation while the encoder still exists. */
    synchronized boolean prepareDeviceClose(
        FixedMaterialSamplerCache.SamplerCloser closer
    ) {
        Objects.requireNonNull(closer, "closer");
        if (this.finished || this.closePrepared) {
            return this.uncertainCache == null;
        }
        if (this.uncertainCache != null || !retireActive(closer)) {
            this.state =
                "cleanup uncertain: sampler generation owner retained";
            return false;
        }
        this.failedCreationKey = null;
        this.closePrepared = true;
        this.state = "prepared; sampler generations queued";
        return true;
    }

    /**
     * Seals the owner after {@code VulkanCommandEncoder.destroy()} drained all
     * destruction buckets. No Vulkan operation is performed here.
     */
    synchronized boolean finishDeviceCloseAfterEncoderDrain() {
        if (this.finished) {
            return true;
        }
        if (!this.closePrepared || this.uncertainCache != null) {
            return false;
        }
        this.activeCache = null;
        this.activeKey = null;
        this.failedCreationKey = null;
        this.finished = true;
        this.state = "closed after encoder drain";
        return true;
    }

    synchronized FixedMaterialSamplerCache activeCache() {
        return this.activeCache;
    }

    synchronized GenerationKey activeKey() {
        return this.activeKey;
    }

    synchronized int retainedCacheCount() {
        int count = this.activeCache == null ? 0 : 1;
        if (this.uncertainCache != null) {
            count++;
        }
        return count;
    }

    synchronized long successfulSwitches() {
        return this.successfulSwitches;
    }

    synchronized String status() {
        return this.state;
    }

    private boolean retireActive(
        FixedMaterialSamplerCache.SamplerCloser closer
    ) {
        FixedMaterialSamplerCache previous = this.activeCache;
        this.activeCache = null;
        this.activeKey = null;
        if (previous == null) {
            return true;
        }
        if (!previous.prepareDeviceClose(closer)) {
            this.uncertainCache = previous;
            this.state = previous.status();
            return false;
        }
        if (!previous.releaseReferencesAfterQueueTransfer()) {
            this.uncertainCache = previous;
            this.state = previous.status();
            return false;
        }
        return true;
    }

    private void requireOwnedKey(GenerationKey key) {
        Objects.requireNonNull(key, "key");
        if (
            key.device() != this.device
                || key.deviceGeneration() != this.deviceGeneration
        ) {
            throw new IllegalArgumentException(
                "sampler generation belongs to another Vulkan device"
            );
        }
    }

    @FunctionalInterface
    interface CacheFactory {
        FixedMaterialSamplerCache create();
    }

    /**
     * Exact non-material part of the cache key. The full immutable original
     * sampler state and its derived final bias remain the per-entry key in
     * {@link FixedMaterialSamplerCache}; this generation key stores only the
     * resolution/mode-wide LOD-bias delta.
     */
    static final class GenerationKey {
        private final Object device;
        private final long deviceGeneration;
        private final int renderWidth;
        private final int renderHeight;
        private final int outputWidth;
        private final int outputHeight;
        private final int mode;
        private final int preset;
        private final int lodBiasDeltaBits;
        private final long reloadGeneration;

        GenerationKey(
            Object device,
            long deviceGeneration,
            int renderWidth,
            int renderHeight,
            int outputWidth,
            int outputHeight,
            int mode,
            int preset,
            float lodBiasDelta,
            long reloadGeneration
        ) {
            this.device = Objects.requireNonNull(device, "device");
            if (deviceGeneration <= 0L) {
                throw new IllegalArgumentException(
                    "device generation must be positive"
                );
            }
            if (
                renderWidth <= 0
                    || renderHeight <= 0
                    || outputWidth <= 0
                    || outputHeight <= 0
            ) {
                throw new IllegalArgumentException(
                    "sampler generation dimensions must be positive"
                );
            }
            if (!Float.isFinite(lodBiasDelta)) {
                throw new IllegalArgumentException(
                    "sampler LOD-bias delta must be finite"
                );
            }
            if (reloadGeneration < 0L) {
                throw new IllegalArgumentException(
                    "reload generation must not be negative"
                );
            }
            this.deviceGeneration = deviceGeneration;
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.mode = mode;
            this.preset = preset;
            this.lodBiasDeltaBits = Float.floatToIntBits(
                lodBiasDelta
            );
            this.reloadGeneration = reloadGeneration;
        }

        Object device() {
            return this.device;
        }

        long deviceGeneration() {
            return this.deviceGeneration;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GenerationKey key)) {
                return false;
            }
            return this.device == key.device
                && this.deviceGeneration == key.deviceGeneration
                && this.renderWidth == key.renderWidth
                && this.renderHeight == key.renderHeight
                && this.outputWidth == key.outputWidth
                && this.outputHeight == key.outputHeight
                && this.mode == key.mode
                && this.preset == key.preset
                && this.lodBiasDeltaBits == key.lodBiasDeltaBits
                && this.reloadGeneration == key.reloadGeneration;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(this.device);
            hash = 31 * hash + Long.hashCode(this.deviceGeneration);
            hash = 31 * hash + this.renderWidth;
            hash = 31 * hash + this.renderHeight;
            hash = 31 * hash + this.outputWidth;
            hash = 31 * hash + this.outputHeight;
            hash = 31 * hash + this.mode;
            hash = 31 * hash + this.preset;
            hash = 31 * hash + this.lodBiasDeltaBits;
            hash = 31 * hash + Long.hashCode(this.reloadGeneration);
            return hash;
        }
    }
}
