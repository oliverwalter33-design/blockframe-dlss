package de.morau.nvidiadlss;

import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.MATERIAL_SAMPLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class FixedMaterialSamplerCacheTest {
    private static final FixedMaterialSamplerCache.SamplerObserver
        NO_OBSERVER =
            (device, sampler, slot, bias, anisotropy) -> {
            };
    private static final Object ADDRESS_U = new Object();
    private static final Object ADDRESS_V = new Object();
    private static final Object MIN_FILTER = new Object();
    private static final Object MAG_FILTER = new Object();

    @Test
    void warmedLookupReusesThePublishedSampler() {
        FakeLeaseController leases = new FakeLeaseController();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCache cache = cache(
            leases,
            inventory,
            4
        );
        FakeSampler original = new FakeSampler();
        int[] creates = new int[1];
        FixedMaterialSamplerCache.SamplerFactory factory =
            (device, u, v, min, mag, anisotropy, maxLod, bias) -> {
                creates[0]++;
                return new FakeSampler();
            };

        Object first = select(
            cache,
            original,
            -1.25F,
            factory
        );
        Object second = select(
            cache,
            original,
            -1.25F,
            factory
        );

        assertNotSame(original, first);
        assertSame(first, second);
        assertEquals(1, creates[0]);
        assertEquals(1, cache.slotCount());
        assertEquals(-1.25F, cache.biasFor(first));
        assertEquals(
            1,
            inventory.snapshot().active(MATERIAL_SAMPLER)
        );
    }

    @Test
    void factoryFailureIsMemoizedForTheExactKey() {
        FakeLeaseController leases = new FakeLeaseController();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCache cache = cache(
            leases,
            inventory,
            4
        );
        FakeSampler original = new FakeSampler();
        int[] attempts = new int[1];
        FixedMaterialSamplerCache.SamplerFactory failing =
            (device, u, v, min, mag, anisotropy, maxLod, bias) -> {
                attempts[0]++;
                throw new OutOfMemoryError("injected");
            };

        assertSame(
            original,
            select(cache, original, -1.0F, failing)
        );
        assertSame(
            original,
            select(cache, original, -1.0F, failing)
        );
        assertEquals(1, attempts[0]);
        assertEquals(
            1L,
            inventory
                .snapshot()
                .creationFailures(MATERIAL_SAMPLER)
        );
    }

    @Test
    void capacityOverflowFallsBackWithoutEviction() {
        FakeLeaseController leases = new FakeLeaseController();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCache cache = cache(
            leases,
            inventory,
            2
        );
        FakeSampler original = new FakeSampler();
        int[] creates = new int[1];
        FixedMaterialSamplerCache.SamplerFactory factory =
            (device, u, v, min, mag, anisotropy, maxLod, bias) -> {
                creates[0]++;
                return new FakeSampler();
            };

        Object first = select(cache, original, -0.75F, factory);
        Object second = select(cache, original, -1.25F, factory);
        Object overflow = select(
            cache,
            original,
            -1.75F,
            factory
        );

        assertNotSame(original, first);
        assertNotSame(original, second);
        assertSame(original, overflow);
        assertEquals(2, creates[0]);
        assertEquals(2, cache.slotCount());
        assertTrue(cache.status().contains("capacity"));
    }

    @Test
    void repeatedOverflowReusesAConstantFallbackStatus() {
        FakeLeaseController leases = new FakeLeaseController();
        FixedMaterialSamplerCache cache = cache(
            leases,
            new ShaderResourceInventory(),
            1
        );
        FakeSampler original = new FakeSampler();
        FixedMaterialSamplerCache.SamplerFactory factory =
            (device, u, v, min, mag, anisotropy, maxLod, bias) ->
                new FakeSampler();

        select(cache, original, -0.75F, factory);
        assertSame(
            original,
            select(cache, original, -1.25F, factory)
        );
        String firstStatus = cache.status();
        for (int attempt = 0; attempt < 32; attempt++) {
            assertSame(
                original,
                select(cache, original, -1.25F, factory)
            );
            assertSame(firstStatus, cache.status());
        }
    }

    @Test
    void observerReceivesLogicalCreationIndices() {
        FixedMaterialSamplerCache cache = cache(
            new FakeLeaseController(),
            new ShaderResourceInventory(),
            4
        );
        FakeSampler original = new FakeSampler();
        int[] expectedIndex = new int[1];
        FixedMaterialSamplerCache.SamplerObserver observer =
            (device, sampler, index, bias, anisotropy) -> {
                assertEquals(expectedIndex[0], index);
                expectedIndex[0]++;
            };
        for (int index = 0; index < 4; index++) {
            Object selected = cache.select(
                original,
                ADDRESS_U,
                ADDRESS_V,
                MIN_FILTER,
                MAG_FILTER,
                4,
                OptionalDouble.of(12.0D),
                -0.75F - index,
                (
                    device,
                    u,
                    v,
                    min,
                    mag,
                    anisotropy,
                    maxLod,
                    bias
                ) -> new FakeSampler(),
                observer
            );
            assertNotSame(original, selected);
        }
        assertEquals(4, expectedIndex[0]);
    }

    @Test
    void noMipmapSamplerDoesNotConsumeAKeySlot() {
        FakeLeaseController leases = new FakeLeaseController();
        FixedMaterialSamplerCache cache = cache(
            leases,
            new ShaderResourceInventory(),
            2
        );
        FakeSampler original = new FakeSampler();
        int[] creates = new int[1];

        Object selected = cache.select(
            original,
            ADDRESS_U,
            ADDRESS_V,
            MIN_FILTER,
            MAG_FILTER,
            4,
            OptionalDouble.of(0.0D),
            -1.0F,
            (device, u, v, min, mag, anisotropy, maxLod, bias) -> {
                creates[0]++;
                return new FakeSampler();
            },
            NO_OBSERVER
        );

        assertSame(original, selected);
        assertEquals(0, creates[0]);
        assertEquals(0, cache.slotCount());
    }

    @Test
    void successfulCloseQueuesOnceAndRetiresAfterDrain() {
        FakeLeaseController leases = new FakeLeaseController();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCache cache = cache(
            leases,
            inventory,
            4
        );
        FakeSampler original = new FakeSampler();
        FakeSampler created = new FakeSampler();
        select(
            cache,
            original,
            -1.0F,
            (device, u, v, min, mag, anisotropy, maxLod, bias) ->
                created
        );

        assertTrue(
            cache.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler).close()
            )
        );
        assertTrue(
            cache.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler).close()
            )
        );
        assertEquals(1, created.closeCalls);
        assertEquals(1, leases.retireAttempts);
        assertEquals(0L, cache.metadataLease());
        assertEquals(
            1,
            inventory.snapshot().retiring(MATERIAL_SAMPLER)
        );
        assertTrue(cache.finishDeviceCloseAfterEncoderDrain());
        inventory.completeGpuRetirements();
        assertEquals(
            0,
            inventory.snapshot().current(MATERIAL_SAMPLER)
        );
    }

    @Test
    void retirementRejectionRetriesOnlyLeaseTransition() {
        FakeLeaseController leases = new FakeLeaseController();
        leases.rejectFirstRetirement = true;
        FixedMaterialSamplerCache cache = cache(
            leases,
            new ShaderResourceInventory(),
            4
        );
        FakeSampler original = new FakeSampler();
        FakeSampler created = new FakeSampler();
        select(
            cache,
            original,
            -1.0F,
            (device, u, v, min, mag, anisotropy, maxLod, bias) ->
                created
        );

        assertFalse(
            cache.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler).close()
            )
        );
        assertTrue(
            cache.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler).close()
            )
        );
        assertEquals(1, created.closeCalls);
        assertEquals(2, leases.retireAttempts);
    }

    @Test
    void thrownCloseStaysPermanentlyUncertain() {
        FakeLeaseController leases = new FakeLeaseController();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCache cache = cache(
            leases,
            inventory,
            4
        );
        FakeSampler original = new FakeSampler();
        FakeSampler created = new FakeSampler();
        created.throwOnClose = true;
        select(
            cache,
            original,
            -1.0F,
            (device, u, v, min, mag, anisotropy, maxLod, bias) ->
                created
        );

        assertFalse(
            cache.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler).close()
            )
        );
        assertFalse(
            cache.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler).close()
            )
        );
        assertEquals(1, created.closeCalls);
        assertEquals(0, leases.retireAttempts);
        assertEquals(91L, cache.metadataLease());
        ShaderResourceInventory.Snapshot snapshot =
            inventory.snapshot();
        assertEquals(1, snapshot.active(MATERIAL_SAMPLER));
        assertEquals(
            1L,
            snapshot.cleanupFailures(MATERIAL_SAMPLER)
        );
        assertEquals(
            IllegalStateException.class.getName(),
            cache.firstCloseFailureType()
        );
    }

    @Test
    void emptyCacheReleasesMetadataWithoutRetirement() {
        FakeLeaseController leases = new FakeLeaseController();
        FixedMaterialSamplerCache cache = cache(
            leases,
            new ShaderResourceInventory(),
            2
        );

        assertTrue(cache.prepareDeviceClose(sampler -> {
        }));
        assertEquals(1, leases.releaseAttempts);
        assertEquals(0, leases.retireAttempts);
        assertFalse(cache.requiresEncoderDrain());
    }

    private static Object select(
        FixedMaterialSamplerCache cache,
        Object original,
        float bias,
        FixedMaterialSamplerCache.SamplerFactory factory
    ) {
        return cache.select(
            original,
            ADDRESS_U,
            ADDRESS_V,
            MIN_FILTER,
            MAG_FILTER,
            4,
            OptionalDouble.of(12.0D),
            bias,
            factory,
            NO_OBSERVER
        );
    }

    private static FixedMaterialSamplerCache cache(
        FakeLeaseController leases,
        ShaderResourceInventory inventory,
        int capacity
    ) {
        return new FixedMaterialSamplerCache(
            new Object(),
            leases,
            91L,
            inventory,
            capacity
        );
    }

    private static final class FakeLeaseController
        implements FixedMaterialSamplerCache.LeaseController {
        private int releaseAttempts;
        private int retireAttempts;
        private boolean rejectFirstRetirement;

        @Override
        public long tryReserve(long requested, long committed) {
            return 91L;
        }

        @Override
        public boolean release(long token) {
            this.releaseAttempts++;
            return true;
        }

        @Override
        public boolean retireAfterGpuUse(long token) {
            this.retireAttempts++;
            return !this.rejectFirstRetirement
                || this.retireAttempts > 1;
        }
    }

    private static final class FakeSampler {
        private int closeCalls;
        private boolean throwOnClose;

        private void close() {
            this.closeCalls++;
            if (this.throwOnClose) {
                throw new IllegalStateException(
                    "injected queue failure"
                );
            }
        }
    }
}
