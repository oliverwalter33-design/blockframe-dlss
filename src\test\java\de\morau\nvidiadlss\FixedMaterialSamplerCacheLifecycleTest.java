package de.morau.nvidiadlss;

import static de.morau.blockframe.core.diagnostics.ShaderResourceInventory.ResourceKind.MATERIAL_SAMPLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class FixedMaterialSamplerCacheLifecycleTest {
    private static final Object ADDRESS_U = new Object();
    private static final Object ADDRESS_V = new Object();
    private static final Object MIN_FILTER = new Object();
    private static final Object MAG_FILTER = new Object();
    private static final FixedMaterialSamplerCache.SamplerObserver
        NO_OBSERVER =
            (device, sampler, slot, bias, anisotropy) -> {
            };

    @Test
    void exactGenerationReuseDoesNotAllocatePerFrame() {
        Object device = new Object();
        FakeLeases leases = new FakeLeases();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 7L);
        int[] allocations = new int[1];
        FixedMaterialSamplerCacheLifecycle.CacheFactory factory =
            () -> {
                allocations[0]++;
                return cache(device, leases, inventory);
            };
        var key = key(device, 7L, 2560, 1369, 3840, 2054, 1, 11, -1.585F, 0L);

        FixedMaterialSamplerCache first = lifecycle.switchTo(
            key,
            factory,
            sampler -> {
            }
        );
        for (int frame = 0; frame < 240; frame++) {
            assertSame(
                first,
                lifecycle.switchTo(
                    key,
                    factory,
                    sampler -> {
                    }
                )
            );
        }

        assertEquals(1, allocations[0]);
        assertEquals(1L, lifecycle.successfulSwitches());
        assertEquals(1, lifecycle.retainedCacheCount());
    }

    @Test
    void everyRequiredGenerationDimensionParticipatesInTheKey() {
        Object device = new Object();
        var baseline = key(
            device,
            3L,
            2560,
            1369,
            3840,
            2054,
            1,
            11,
            -1.585F,
            4L
        );

        assertFalse(baseline.equals(key(device, 4L, 2560, 1369, 3840, 2054, 1, 11, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2559, 1369, 3840, 2054, 1, 11, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1368, 3840, 2054, 1, 11, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1369, 3839, 2054, 1, 11, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1369, 3840, 2053, 1, 11, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1369, 3840, 2054, 2, 11, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1369, 3840, 2054, 1, 12, -1.585F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1369, 3840, 2054, 1, 11, -1.584F, 4L)));
        assertFalse(baseline.equals(key(device, 3L, 2560, 1369, 3840, 2054, 1, 11, -1.585F, 5L)));
        assertFalse(
            baseline.equals(
                key(
                    new Object(),
                    3L,
                    2560,
                    1369,
                    3840,
                    2054,
                    1,
                    11,
                    -1.585F,
                    4L
                )
            )
        );
    }

    @Test
    void oneHundredTwentyEightSwitchesStayBoundedAndDrainExactlyOnce() {
        Object device = new Object();
        FakeLeases leases = new FakeLeases();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FakeTwoSubmitDestructionQueue destroyQueue =
            new FakeTwoSubmitDestructionQueue();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 9L);
        List<FakeSampler> created = new ArrayList<>();

        for (int generation = 0; generation < 128; generation++) {
            int outputWidth = 1800 + generation;
            int outputHeight = 1000 + generation % 17;
            FixedMaterialSamplerCache active = lifecycle.switchTo(
                key(
                    device,
                    9L,
                    outputWidth * 2 / 3,
                    outputHeight * 2 / 3,
                    outputWidth,
                    outputHeight,
                    generation % 5 + 1,
                    generation % 3 + 10,
                    -1.0F - generation / 1024.0F,
                    generation / 19
                ),
                () -> cache(device, leases, inventory),
                sampler -> ((FakeSampler)sampler)
                    .queueForDestroy(destroyQueue)
            );
            assertNotNull(active);
            FakeSampler published = new FakeSampler();
            created.add(published);
            assertSame(
                published,
                select(active, new FakeSampler(), published)
            );

            assertEquals(1, lifecycle.retainedCacheCount());
            assertTrue(destroyQueue.pendingCount() <= 2);
            assertTrue(leases.currentCount() <= 4);
            assertTrue(
                inventory.snapshot().current(MATERIAL_SAMPLER) <= 4
            );

            destroyQueue.completedSubmitAndRotate();
            leases.advanceFrame();
            inventory.advanceFrame();
        }

        assertEquals(128L, lifecycle.successfulSwitches());
        assertTrue(
            lifecycle.prepareDeviceClose(
                sampler -> ((FakeSampler)sampler)
                    .queueForDestroy(destroyQueue)
            )
        );
        assertEquals(0, lifecycle.retainedCacheCount());
        destroyQueue.drainAfterQueueIdle();
        assertTrue(lifecycle.finishDeviceCloseAfterEncoderDrain());
        assertTrue(lifecycle.finishDeviceCloseAfterEncoderDrain());
        leases.completeGpuRetirements();
        inventory.completeGpuRetirements();

        assertEquals(0, leases.currentCount());
        assertEquals(
            0,
            inventory.snapshot().current(MATERIAL_SAMPLER)
        );
        assertEquals(
            128,
            created.stream().mapToInt(sampler -> sampler.destroyCalls).sum()
        );
        assertTrue(created.stream().allMatch(sampler -> sampler.destroyCalls == 1));
    }

    @Test
    void queueTransferKeepsStrongReferenceUntilSecondRotation() {
        Object device = new Object();
        FakeLeases leases = new FakeLeases();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FakeTwoSubmitDestructionQueue destroyQueue =
            new FakeTwoSubmitDestructionQueue();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 2L);
        FixedMaterialSamplerCache first = lifecycle.switchTo(
            key(device, 2L, 1280, 720, 1920, 1080, 1, 11, -1.585F, 0L),
            () -> cache(device, leases, inventory),
            sampler -> ((FakeSampler)sampler)
                .queueForDestroy(destroyQueue)
        );
        FakeSampler firstSampler = new FakeSampler();
        select(first, new FakeSampler(), firstSampler);

        assertNotNull(
            lifecycle.switchTo(
                key(device, 2L, 1706, 960, 2560, 1440, 2, 11, -1.585F, 0L),
                () -> cache(device, leases, inventory),
                sampler -> ((FakeSampler)sampler)
                    .queueForDestroy(destroyQueue)
            )
        );
        assertEquals(1, destroyQueue.pendingCount());
        assertTrue(destroyQueue.contains(firstSampler));
        assertEquals(0, first.slotCount());
        assertEquals(0, firstSampler.destroyCalls);

        destroyQueue.completedSubmitAndRotate();
        assertEquals(0, firstSampler.destroyCalls);
        destroyQueue.completedSubmitAndRotate();
        assertEquals(1, firstSampler.destroyCalls);
    }

    @Test
    void failedQueueTransferRetainsOneOwnerAndPermanentlyFailsClosed() {
        Object device = new Object();
        FakeLeases leases = new FakeLeases();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 4L);
        FixedMaterialSamplerCache first = lifecycle.switchTo(
            key(device, 4L, 1280, 720, 1920, 1080, 1, 11, -1.585F, 0L),
            () -> cache(device, leases, inventory),
            sampler -> {
            }
        );
        FakeSampler sampler = new FakeSampler();
        select(first, new FakeSampler(), sampler);
        int[] replacementAllocations = new int[1];

        assertNull(
            lifecycle.switchTo(
                key(device, 4L, 1706, 960, 2560, 1440, 2, 11, -1.585F, 0L),
                () -> {
                    replacementAllocations[0]++;
                    return cache(device, leases, inventory);
                },
                ignored -> {
                    throw new IllegalStateException("injected queue failure");
                }
            )
        );
        assertEquals(0, replacementAllocations[0]);
        assertEquals(1, lifecycle.retainedCacheCount());
        assertNull(lifecycle.activeCache());
        assertFalse(lifecycle.deactivate(ignored -> {
        }));
        assertFalse(lifecycle.prepareDeviceClose(ignored -> {
        }));
        assertFalse(lifecycle.finishDeviceCloseAfterEncoderDrain());
        assertTrue(lifecycle.status().contains("uncertain"));
    }

    @Test
    void exactCreationFailureIsMemoizedUntilKeyOrReloadChanges() {
        Object device = new Object();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 1L);
        int[] attempts = new int[1];
        FixedMaterialSamplerCacheLifecycle.CacheFactory failing =
            () -> {
                attempts[0]++;
                throw new OutOfMemoryError("injected");
            };
        var firstKey = key(device, 1L, 1280, 720, 1920, 1080, 1, 11, -1.585F, 0L);

        for (int frame = 0; frame < 100; frame++) {
            assertNull(
                lifecycle.switchTo(
                    firstKey,
                    failing,
                    sampler -> {
                    }
                )
            );
        }
        assertEquals(1, attempts[0]);
        assertNull(
            lifecycle.switchTo(
                key(device, 1L, 1280, 720, 1920, 1080, 1, 11, -1.585F, 1L),
                failing,
                sampler -> {
                }
            )
        );
        assertEquals(2, attempts[0]);
    }

    @Test
    void minimizedOrOffDeactivationPreventsStaleReuseOnRestore() {
        Object device = new Object();
        FakeLeases leases = new FakeLeases();
        ShaderResourceInventory inventory =
            new ShaderResourceInventory();
        FakeTwoSubmitDestructionQueue queue =
            new FakeTwoSubmitDestructionQueue();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 6L);
        var restoredKey = key(device, 6L, 1280, 720, 1920, 1080, 1, 11, -1.585F, 0L);
        FixedMaterialSamplerCache before = lifecycle.switchTo(
            restoredKey,
            () -> cache(device, leases, inventory),
            sampler -> ((FakeSampler)sampler).queueForDestroy(queue)
        );

        assertTrue(
            lifecycle.deactivate(
                sampler -> ((FakeSampler)sampler)
                    .queueForDestroy(queue)
            )
        );
        assertNull(lifecycle.activeCache());
        FixedMaterialSamplerCache after = lifecycle.switchTo(
            restoredKey,
            () -> cache(device, leases, inventory),
            sampler -> ((FakeSampler)sampler).queueForDestroy(queue)
        );

        assertNotNull(after);
        assertFalse(before == after);
    }

    @Test
    void foreignDeviceKeyAndInvalidDimensionsAreRejected() {
        Object device = new Object();
        FixedMaterialSamplerCacheLifecycle lifecycle =
            new FixedMaterialSamplerCacheLifecycle(device, 5L);
        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.switchTo(
                key(new Object(), 5L, 1, 1, 1, 1, 1, 1, -1.0F, 0L),
                () -> null,
                sampler -> {
                }
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> key(device, 5L, 0, 1, 1, 1, 1, 1, -1.0F, 0L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> key(device, 5L, 1, 1, 1, 1, 1, 1, Float.NaN, 0L)
        );
    }

    private static FixedMaterialSamplerCacheLifecycle.GenerationKey key(
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
        return new FixedMaterialSamplerCacheLifecycle.GenerationKey(
            device,
            deviceGeneration,
            renderWidth,
            renderHeight,
            outputWidth,
            outputHeight,
            mode,
            preset,
            lodBiasDelta,
            reloadGeneration
        );
    }

    private static FixedMaterialSamplerCache cache(
        Object device,
        FakeLeases leases,
        ShaderResourceInventory inventory
    ) {
        return new FixedMaterialSamplerCache(
            device,
            leases,
            leases.reserve(),
            inventory,
            4
        );
    }

    private static Object select(
        FixedMaterialSamplerCache cache,
        Object original,
        FakeSampler published
    ) {
        return cache.select(
            original,
            ADDRESS_U,
            ADDRESS_V,
            MIN_FILTER,
            MAG_FILTER,
            4,
            OptionalDouble.of(12.0D),
            -1.585F,
            (
                device,
                descriptor,
                u,
                v,
                min,
                mag,
                anisotropy,
                maxLod,
                bias
            ) ->
                published,
            NO_OBSERVER
        );
    }

    private static final class FakeLeases
        implements FixedMaterialSamplerCache.LeaseController {
        private final Map<Long, Long> active = new HashMap<>();
        private long nextToken = 1L;
        private long epoch;

        private long reserve() {
            long token = this.nextToken++;
            this.active.put(token, Long.MAX_VALUE);
            return token;
        }

        private int currentCount() {
            return this.active.size();
        }

        private void advanceFrame() {
            this.epoch++;
            this.active.entrySet().removeIf(
                entry -> entry.getValue() <= this.epoch
            );
        }

        private void completeGpuRetirements() {
            this.active.clear();
        }

        @Override
        public long tryReserve(long requested, long committed) {
            return reserve();
        }

        @Override
        public boolean release(long token) {
            return this.active.remove(token) != null;
        }

        @Override
        public boolean retireAfterGpuUse(long token) {
            if (!this.active.containsKey(token)) {
                return false;
            }
            this.active.put(token, this.epoch + 3L);
            return true;
        }
    }

    private static final class FakeTwoSubmitDestructionQueue {
        private final ArrayDeque<FakeSampler>[] buckets;
        private int currentBucket;

        @SuppressWarnings("unchecked")
        private FakeTwoSubmitDestructionQueue() {
            this.buckets = new ArrayDeque[] {
                new ArrayDeque<>(),
                new ArrayDeque<>()
            };
        }

        private void add(FakeSampler sampler) {
            this.buckets[this.currentBucket].addLast(sampler);
        }

        private int pendingCount() {
            return this.buckets[0].size() + this.buckets[1].size();
        }

        private boolean contains(FakeSampler expected) {
            return this.buckets[0].stream().anyMatch(
                sampler -> sampler == expected
            ) || this.buckets[1].stream().anyMatch(
                sampler -> sampler == expected
            );
        }

        private void completedSubmitAndRotate() {
            this.currentBucket = (this.currentBucket + 1) % 2;
            ArrayDeque<FakeSampler> completed =
                this.buckets[this.currentBucket];
            FakeSampler sampler;
            while ((sampler = completed.pollFirst()) != null) {
                sampler.destroy();
            }
        }

        private void drainAfterQueueIdle() {
            completedSubmitAndRotate();
            completedSubmitAndRotate();
        }
    }

    private static final class FakeSampler {
        private boolean closed;
        private int destroyCalls;

        private void queueForDestroy(
            FakeTwoSubmitDestructionQueue queue
        ) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            queue.add(this);
        }

        private void destroy() {
            this.destroyCalls++;
        }
    }
}
