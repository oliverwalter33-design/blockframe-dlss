package de.morau.nvidiadlss;

import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Minecraft-independent fixed sampler-cache core. The Vulkan wrapper supplies
 * immutable sampler properties and physical create/close operations.
 */
final class FixedMaterialSamplerCache {
    private static final System.Logger LOGGER =
        System.getLogger(
            FixedMaterialSamplerCache.class.getName()
        );
    private static final byte SLOT_FAILED = 1;
    private static final byte SLOT_LIVE = 2;
    private static final byte SLOT_QUEUED = 3;
    private static final byte SLOT_CLOSE_UNCERTAIN = 4;
    private static final String CAPACITY_FALLBACK =
        "original fallback: fixed sampler capacity reached";

    private final Object device;
    private final LeaseController leases;
    private final ShaderResourceInventory inventory;
    private final Object[] addressModesU;
    private final Object[] addressModesV;
    private final Object[] minFilters;
    private final Object[] magFilters;
    private final int[] maxAnisotropy;
    private final long[] maxLodBits;
    private final int[] biasBits;
    private final boolean[] maxLodPresent;
    private final Object[] samplers;
    private final byte[] states;
    private final int maxEntries;
    private final int tableMask;
    private long metadataLease;
    private int slotCount;
    private int lastLookupSlot = -1;
    private String firstCloseFailureType;
    private boolean closePrepared;
    private boolean finished;
    private String state = "active";

    FixedMaterialSamplerCache(
        Object device,
        LeaseController leases,
        long metadataLease,
        ShaderResourceInventory inventory,
        int capacity
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.leases = Objects.requireNonNull(leases, "leases");
        if (metadataLease == 0L) {
            throw new IllegalArgumentException(
                "metadata lease must be non-zero"
            );
        }
        this.metadataLease = metadataLease;
        this.inventory = Objects.requireNonNull(
            inventory,
            "inventory"
        );
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "capacity must be positive"
            );
        }
        this.maxEntries = capacity;
        int tableCapacity = tableCapacity(capacity);
        this.tableMask = tableCapacity - 1;
        this.addressModesU = new Object[tableCapacity];
        this.addressModesV = new Object[tableCapacity];
        this.minFilters = new Object[tableCapacity];
        this.magFilters = new Object[tableCapacity];
        this.maxAnisotropy = new int[tableCapacity];
        this.maxLodBits = new long[tableCapacity];
        this.biasBits = new int[tableCapacity];
        this.maxLodPresent = new boolean[tableCapacity];
        this.samplers = new Object[tableCapacity];
        this.states = new byte[tableCapacity];
    }

    Object select(
        Object original,
        Object addressModeU,
        Object addressModeV,
        Object minFilter,
        Object magFilter,
        int anisotropy,
        OptionalDouble maxLod,
        float bias,
        SamplerFactory factory,
        SamplerObserver observer
    ) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(addressModeU, "addressModeU");
        Objects.requireNonNull(addressModeV, "addressModeV");
        Objects.requireNonNull(minFilter, "minFilter");
        Objects.requireNonNull(magFilter, "magFilter");
        Objects.requireNonNull(maxLod, "maxLod");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(observer, "observer");
        if (this.closePrepared || this.finished) {
            this.state = "original fallback: cache is closing";
            return original;
        }
        boolean hasMaxLod = maxLod.isPresent();
        if (hasMaxLod && !(maxLod.getAsDouble() > 0.0D)) {
            return original;
        }
        long lodBits = hasMaxLod
            ? Double.doubleToLongBits(maxLod.getAsDouble())
            : Long.MIN_VALUE;
        int requestedBiasBits = Float.floatToIntBits(bias);

        int fastSlot = this.lastLookupSlot;
        if (
            fastSlot >= 0
                && matches(
                    fastSlot,
                    addressModeU,
                    addressModeV,
                    minFilter,
                    magFilter,
                    anisotropy,
                    hasMaxLod,
                    lodBits,
                    requestedBiasBits
                )
        ) {
            return this.states[fastSlot] == SLOT_LIVE
                ? this.samplers[fastSlot]
                : original;
        }

        int slot = keyHash(
            addressModeU,
            addressModeV,
            minFilter,
            magFilter,
            anisotropy,
            hasMaxLod,
            lodBits,
            requestedBiasBits
        ) & this.tableMask;
        while (this.states[slot] != 0) {
            if (
                matches(
                    slot,
                    addressModeU,
                    addressModeV,
                    minFilter,
                    magFilter,
                    anisotropy,
                    hasMaxLod,
                    lodBits,
                    requestedBiasBits
                )
            ) {
                this.lastLookupSlot = slot;
                return this.states[slot] == SLOT_LIVE
                    ? this.samplers[slot]
                    : original;
            }
            slot = (slot + 1) & this.tableMask;
        }
        if (this.slotCount == this.maxEntries) {
            this.state = CAPACITY_FALLBACK;
            return original;
        }

        this.slotCount++;
        this.addressModesU[slot] = addressModeU;
        this.addressModesV[slot] = addressModeV;
        this.minFilters[slot] = minFilter;
        this.magFilters[slot] = magFilter;
        this.maxAnisotropy[slot] = anisotropy;
        this.maxLodPresent[slot] = hasMaxLod;
        this.maxLodBits[slot] = lodBits;
        this.biasBits[slot] = requestedBiasBits;
        this.states[slot] = SLOT_FAILED;

        Object created;
        try {
            created = factory.create(
                this.device,
                addressModeU,
                addressModeV,
                minFilter,
                magFilter,
                anisotropy,
                maxLod,
                bias
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError error
        ) {
            this.inventory.creationFailed(
                ResourceKind.MATERIAL_SAMPLER
            );
            this.state =
                "original fallback: recoverable sampler creation failure";
            return original;
        }
        if (created == null) {
            this.inventory.creationFailed(
                ResourceKind.MATERIAL_SAMPLER
            );
            this.state =
                "original fallback: sampler factory returned null";
            return original;
        }

        this.samplers[slot] = created;
        this.states[slot] = SLOT_LIVE;
        this.lastLookupSlot = slot;
        this.inventory.created(ResourceKind.MATERIAL_SAMPLER);
        int publishedIndex = liveCount() - 1;
        this.state =
            "active: "
                + (publishedIndex + 1)
                + "/"
                + this.maxEntries
                + " material samplers";
        try {
            observer.published(
                this.device,
                created,
                publishedIndex,
                bias,
                anisotropy
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Optional labels/logging never invalidate a published owner.
        }
        return created;
    }

    float biasFor(Object sampler) {
        for (int slot = 0; slot < this.samplers.length; slot++) {
            if (this.samplers[slot] == sampler) {
                return Float.intBitsToFloat(this.biasBits[slot]);
            }
        }
        return Float.NaN;
    }

    boolean prepareDeviceClose(SamplerCloser closer) {
        Objects.requireNonNull(closer, "closer");
        if (this.finished || this.closePrepared) {
            return true;
        }
        boolean uncertain = false;
        boolean queued = false;
        for (
            int slot = this.samplers.length - 1;
            slot >= 0;
            slot--
        ) {
            byte slotState = this.states[slot];
            if (slotState == SLOT_CLOSE_UNCERTAIN) {
                uncertain = true;
                continue;
            }
            if (slotState == SLOT_QUEUED) {
                queued = true;
                continue;
            }
            if (slotState != SLOT_LIVE) {
                continue;
            }
            try {
                closer.close(this.samplers[slot]);
            } catch (Throwable error) {
                this.states[slot] = SLOT_CLOSE_UNCERTAIN;
                if (this.firstCloseFailureType == null) {
                    this.firstCloseFailureType =
                        error.getClass().getName();
                    logFailureType(
                        "Material sampler queue-for-destroy failed; "
                            + "ownership remains uncertain",
                        error
                    );
                }
                try {
                    this.inventory.cleanupFailed(
                        ResourceKind.MATERIAL_SAMPLER
                    );
                } catch (Throwable diagnosticsError) {
                    logFailureType(
                        "Material sampler close failed and inventory "
                            + "bookkeeping also failed",
                        diagnosticsError
                    );
                }
                uncertain = true;
                continue;
            }
            this.states[slot] = SLOT_QUEUED;
            queued = true;
            try {
                this.inventory.queuedForRetirement(
                    ResourceKind.MATERIAL_SAMPLER
                );
            } catch (Throwable diagnosticsError) {
                logFailureType(
                    "Material sampler was queued, but inventory "
                        + "bookkeeping failed",
                    diagnosticsError
                );
            }
        }
        if (uncertain) {
            this.state =
                "cleanup uncertain: material sampler owner retained";
            return false;
        }
        if (this.metadataLease != 0L) {
            boolean transitioned;
            try {
                transitioned = queued
                    ? this.leases.retireAfterGpuUse(
                        this.metadataLease
                    )
                    : this.leases.release(this.metadataLease);
            } catch (Throwable error) {
                this.state =
                    "cleanup deferred: metadata lease transition failure";
                return false;
            }
            if (!transitioned) {
                this.state =
                    "cleanup deferred: metadata lease transition rejected";
                return false;
            }
            this.metadataLease = 0L;
        }
        this.closePrepared = true;
        this.state = queued
            ? "retiring after confirmed sampler queue"
            : "closed: no live material samplers";
        return true;
    }

    boolean finishDeviceCloseAfterEncoderDrain() {
        if (this.finished) {
            return true;
        }
        if (!this.closePrepared) {
            return false;
        }
        Arrays.fill(this.samplers, null);
        Arrays.fill(this.addressModesU, null);
        Arrays.fill(this.addressModesV, null);
        Arrays.fill(this.minFilters, null);
        Arrays.fill(this.magFilters, null);
        Arrays.fill(this.states, (byte)0);
        this.slotCount = 0;
        this.lastLookupSlot = -1;
        this.finished = true;
        this.state = "closed after encoder drain";
        return true;
    }

    boolean requiresEncoderDrain() {
        for (int slot = 0; slot < this.states.length; slot++) {
            if (this.states[slot] == SLOT_QUEUED) {
                return true;
            }
        }
        return false;
    }

    int liveCount() {
        int count = 0;
        for (int slot = 0; slot < this.states.length; slot++) {
            if (this.states[slot] == SLOT_LIVE) {
                count++;
            }
        }
        return count;
    }

    int slotCount() {
        return this.slotCount;
    }

    int capacity() {
        return this.maxEntries;
    }

    long metadataLease() {
        return this.metadataLease;
    }

    String status() {
        return this.state;
    }

    String firstCloseFailureType() {
        return this.firstCloseFailureType;
    }

    private static int tableCapacity(int maxEntries) {
        int requested;
        try {
            requested = Math.multiplyExact(maxEntries, 2);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                "sampler capacity is too large",
                error
            );
        }
        int capacity = 1;
        while (capacity < requested) {
            if (capacity > (1 << 29)) {
                throw new IllegalArgumentException(
                    "sampler table capacity overflow"
                );
            }
            capacity <<= 1;
        }
        return capacity;
    }

    private static void logFailureType(
        String message,
        Throwable error
    ) {
        try {
            LOGGER.log(
                System.Logger.Level.WARNING,
                message
                    + " ("
                    + error.getClass().getName()
                    + ")"
            );
        } catch (Throwable ignored) {
            // Diagnostics must not alter physical ownership transitions.
        }
    }

    private static int keyHash(
        Object addressModeU,
        Object addressModeV,
        Object minFilter,
        Object magFilter,
        int anisotropy,
        boolean hasMaxLod,
        long lodBits,
        int requestedBiasBits
    ) {
        int hash = System.identityHashCode(addressModeU);
        hash = 31 * hash + System.identityHashCode(addressModeV);
        hash = 31 * hash + System.identityHashCode(minFilter);
        hash = 31 * hash + System.identityHashCode(magFilter);
        hash = 31 * hash + anisotropy;
        hash = 31 * hash + (hasMaxLod ? 1 : 0);
        hash = 31 * hash + Long.hashCode(lodBits);
        hash = 31 * hash + requestedBiasBits;
        hash ^= hash >>> 16;
        return hash;
    }

    private boolean matches(
        int slot,
        Object addressModeU,
        Object addressModeV,
        Object minFilter,
        Object magFilter,
        int anisotropy,
        boolean hasMaxLod,
        long lodBits,
        int requestedBiasBits
    ) {
        return this.biasBits[slot] == requestedBiasBits
            && this.maxAnisotropy[slot] == anisotropy
            && this.maxLodPresent[slot] == hasMaxLod
            && this.maxLodBits[slot] == lodBits
            && this.addressModesU[slot] == addressModeU
            && this.addressModesV[slot] == addressModeV
            && this.minFilters[slot] == minFilter
            && this.magFilters[slot] == magFilter;
    }

    @FunctionalInterface
    interface SamplerFactory {
        Object create(
            Object device,
            Object addressModeU,
            Object addressModeV,
            Object minFilter,
            Object magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod,
            float bias
        );
    }

    @FunctionalInterface
    interface SamplerObserver {
        void published(
            Object device,
            Object sampler,
            int slot,
            float bias,
            int anisotropy
        );
    }

    @FunctionalInterface
    interface SamplerCloser {
        void close(Object sampler);
    }

    interface LeaseController {
        long tryReserve(long requested, long committed);

        boolean release(long token);

        boolean retireAfterGpuUse(long token);
    }
}
