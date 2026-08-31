package de.morau.blockframe.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.memory.ReusableNativeBlockPool;
import de.morau.blockframe.core.diagnostics.ShaderResourceInventory;
import de.morau.blockframe.core.state.FeatureStateRegistry;
import de.morau.blockframe.core.state.RuntimeFeaturePolicy;
import de.morau.blockframe.profiler.FrameProfiler;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BlockframeEngineTest {
    @Test
    void closeRejectsFalseSuccessWhenShaderOwnershipRemains() {
        BlockframeEngine engine = new BlockframeEngine(
            new EngineConfig(Path.of("unused-test-config"))
        );
        engine.shaderResources().created(
            ShaderResourceInventory.ResourceKind.MATERIAL_SAMPLER
        );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            engine::close
        );

        assertTrue(
            failure.getMessage().contains(
                "shader resource cleanup retained 1 object"
            )
        );
        assertTrue(engine.memoryBudgets().snapshot().closed());
        assertTrue(engine.shaderResources().snapshot().closed());
        assertEquals(1L, engine.shaderResources().snapshot().leaks());
    }

    @Test
    void encoderDrainCompletesRetirementsWithoutDlssOwnership() {
        BlockframeEngine engine = new BlockframeEngine(
            new EngineConfig(Path.of("unused-test-config"))
        );
        engine.shaderResources().created(
            ShaderResourceInventory.ResourceKind
                .MANAGED_GPU_SCENE_BUFFER
        );
        engine.shaderResources().queuedForRetirement(
            ShaderResourceInventory.ResourceKind
                .MANAGED_GPU_SCENE_BUFFER
        );
        long lease = engine.memoryBudgets().tryReserve(
            MemoryKind.VRAM,
            MemoryCategory.TERRAIN,
            64L
        );

        assertTrue(lease != 0L);
        assertTrue(engine.memoryBudgets().retireAfterGpuUse(lease));
        engine.completeVulkanRetirementsAfterEncoderDrain();

        assertEquals(
            0,
            engine.shaderResources().snapshot().currentTotal()
        );
        assertEquals(
            0,
            engine.memoryBudgets().snapshot().outstanding()
        );
        engine.close();
    }

    @Test
    void closeRejectsFalseSuccessWhenABudgetLeaseRemainsOutstanding() {
        BlockframeEngine engine = new BlockframeEngine(
            new EngineConfig(Path.of("unused-test-config"))
        );
        long lease = engine.memoryBudgets().tryReserve(
            MemoryKind.RAM,
            MemoryCategory.CACHES,
            1L
        );

        assertTrue(lease != 0L);
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            engine::close
        );
        assertTrue(
            failure.getMessage().contains(
                "memory budget cleanup retained 1 outstanding lease"
            )
        );
        MemoryBudgetManager.Snapshot snapshot =
            engine.memoryBudgets().snapshot();
        assertTrue(snapshot.closed());
        assertEquals(1, snapshot.outstanding());
        assertEquals(1L, snapshot.leaks());
    }

    @Test
    void productProfilerDisableOwnsNoRollingStorage() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        Properties properties = EngineConfig.Settings.defaults().toProperties();
        properties.setProperty(EngineConfig.PROFILER_ENABLED_KEY, "false");
        EngineConfig.Settings settings =
            EngineConfig.Settings.from(properties);
        config.setSettings(settings);
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            settings,
            "off",
            "heap",
            false
        );
        FeatureStateRegistry states = new FeatureStateRegistry();
        policy.publishInitial(states, 1L);

        BlockframeEngine engine =
            new BlockframeEngine(config, policy, states);

        assertFalse(engine.profiler().enabled());
        assertEquals(0L, engine.profiler().rollingStorageBytes());
        engine.beginFrame();
        engine.endFrame();
        assertEquals(0L, engine.profiler().snapshot().completedFrames());
        engine.close();
    }

    @Test
    void safeStartProfilerDisableOwnsNoRollingStorage() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        EngineConfig.Settings settings = EngineConfig.Settings.defaults();
        config.setSettings(settings);
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            settings,
            "dlss",
            "heap",
            true
        );
        FeatureStateRegistry states = new FeatureStateRegistry();
        policy.publishInitial(states, 1L);

        BlockframeEngine engine =
            new BlockframeEngine(config, policy, states);

        assertFalse(engine.profiler().enabled());
        assertEquals(0L, engine.profiler().rollingStorageBytes());
        engine.close();
    }

    @Test
    void compatibilityDisableFailsClosedWithoutClosingDiagnostics() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        FrameProfiler profiler = new FrameProfiler(4);
        BlockframeEngine engine = new BlockframeEngine(config, profiler);

        engine.disableForCompatibility("Sodium detected");
        engine.beginFrame();
        engine.endFrame();

        assertTrue(engine.compatibilityDisabled());
        assertEquals("Sodium detected", engine.compatibilityReason());
        assertEquals(0L, profiler.snapshot().completedFrames());
        assertTrue(
            engine.debugLines().stream().anyMatch(line -> line.contains("Sodium detected"))
        );
        engine.close();
    }

    @Test
    void nextFrameAbortsAnIncompleteMeasurementBeforeRecovering() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        RuntimeFeaturePolicy policy = new RuntimeFeaturePolicy(
            config.settings(),
            "off",
            "heap",
            false,
            true
        );
        FeatureStateRegistry states = new FeatureStateRegistry();
        policy.publishInitial(states, 1L);
        BlockframeEngine engine = new BlockframeEngine(
            config,
            policy,
            states
        );
        FrameProfiler profiler = engine.profiler();

        engine.beginFrame();
        profiler.recordUpload(128L, 20L);
        engine.beginFrame();
        profiler.recordUpload(64L, 5L);
        engine.endFrame();

        FrameProfiler.Snapshot snapshot = profiler.snapshot();
        assertEquals(1L, snapshot.completedFrames());
        assertEquals(64L, snapshot.uploadBytes());
        assertEquals(5L, snapshot.uploadNanos());
        engine.close();
    }

    @Test
    void unattachedCacheIsNeverPresentedAsZeroActivity() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));

        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(line -> line.equals("Cache: NOT_ATTACHED / NOT_MEASURED"))
        );
        engine.close();
    }

    @Test
    void nativeShaderStagingPoolIsLazyFixedAndClosedBeforeBudgets() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));

        assertEquals(0, engine.memoryBudgets().snapshot().outstanding());
        ReusableNativeBlockPool pool =
            engine.nativeStagingPoolOrNull();

        assertNotNull(pool);
        assertSame(pool, engine.nativeStagingPoolOrNull());
        assertEquals(
            32L * 1024L,
            engine
                .memoryBudgets()
                .snapshot()
                .requestedBytes(MemoryKind.RAM)
        );
        assertEquals(
            32L * 1024L,
            engine
                .memoryBudgets()
                .snapshot()
                .usedBytes(
                    MemoryKind.RAM,
                    MemoryCategory.STAGING
                )
        );
        long token = pool.tryBorrow(16);
        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(
                    line -> line.equals(
                        "Native staging pool: "
                            + "active evictable: "
                            + "1x32768 bytes | borrowed=1"
                    )
                )
        );
        pool.release(token);
        engine.close();

        assertEquals(0, engine.memoryBudgets().snapshot().outstanding());
        assertEquals(0L, engine.memoryBudgets().snapshot().leaks());
    }

    @Test
    void nativeShaderStagingPoolEvictsUnderLowBudgetAndFallsBack() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));
        ReusableNativeBlockPool pool =
            engine.nativeStagingPoolOrNull();
        assertNotNull(pool);

        long[] ram = categoryLimits(1L << 20);
        long[] vram = categoryLimits(1L << 20);
        ram[MemoryCategory.STAGING.ordinal()] = 1L;
        engine.memoryBudgets().applySettings(
            new MemoryBudgetSettings(
                1L << 20,
                1L << 20,
                0L,
                0L,
                ram,
                vram
            )
        );

        MemoryBudgetManager.Snapshot snapshot =
            engine.memoryBudgets().snapshot();
        assertEquals(1L, snapshot.evictions());
        assertEquals(32L * 1024L, snapshot.reclaimedBytes());
        assertEquals(0L, snapshot.usedBytes(MemoryKind.RAM));
        assertEquals(0, snapshot.outstanding());
        assertNull(engine.nativeStagingPoolOrNull());
        assertThrows(
            IllegalStateException.class,
            () -> pool.tryBorrow(1)
        );
        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(
                    line -> line.contains(
                        "evicted: direct fallback "
                            + "until configuration reload"
                    )
                )
        );
        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(
                    line -> line.equals(
                        "Memory reclaimed: 32768 bytes"
                    )
                )
        );
        engine.close();
        assertEquals(0L, engine.memoryBudgets().snapshot().leaks());
    }

    @Test
    void borrowedNativeShaderStagingPoolDefersLowBudgetEviction() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));
        ReusableNativeBlockPool pool =
            engine.nativeStagingPoolOrNull();
        assertNotNull(pool);
        long borrowed = pool.tryBorrow(16);
        long[] ram = categoryLimits(1L << 20);
        long[] vram = categoryLimits(1L << 20);
        ram[MemoryCategory.STAGING.ordinal()] = 1L;
        MemoryBudgetSettings lowBudget =
            new MemoryBudgetSettings(
                1L << 20,
                1L << 20,
                0L,
                0L,
                ram,
                vram
            );

        engine.memoryBudgets().applySettings(lowBudget);

        assertEquals(0L, engine.memoryBudgets().snapshot().evictions());
        assertEquals(
            32L * 1024L,
            engine.memoryBudgets().snapshot().usedBytes(MemoryKind.RAM)
        );
        assertSame(pool, engine.nativeStagingPoolOrNull());
        pool.release(borrowed);

        engine.memoryBudgets().applySettings(lowBudget);
        assertEquals(1L, engine.memoryBudgets().snapshot().evictions());
        assertEquals(
            32L * 1024L,
            engine.memoryBudgets().snapshot().reclaimedBytes()
        );
        assertNull(engine.nativeStagingPoolOrNull());
        engine.close();
        assertEquals(0L, engine.memoryBudgets().snapshot().leaks());
    }

    @Test
    void inaccessiblePublishedStagingPoolReturnsDirectFallback() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));
        ReusableNativeBlockPool pool =
            engine.nativeStagingPoolOrNull();
        assertNotNull(pool);

        pool.close();

        assertNull(engine.nativeStagingPoolOrNull());
        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(
                    line -> line.contains(
                        "direct fallback: IllegalStateException"
                    )
                )
        );
        assertEquals(0, engine.memoryBudgets().snapshot().outstanding());
        engine.close();
        assertEquals(0L, engine.memoryBudgets().snapshot().leaks());
    }

    @Test
    void wrongThreadEvictionDefersThenOwnerTouchRestoresActivePool()
        throws InterruptedException {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));
        ReusableNativeBlockPool pool =
            engine.nativeStagingPoolOrNull();
        assertNotNull(pool);
        long[] ram = categoryLimits(1L << 20);
        long[] vram = categoryLimits(1L << 20);
        ram[MemoryCategory.STAGING.ordinal()] = 1L;
        MemoryBudgetSettings lowBudget =
            new MemoryBudgetSettings(
                1L << 20,
                1L << 20,
                0L,
                0L,
                ram,
                vram
            );
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(
            () -> {
                try {
                    engine.memoryBudgets().applySettings(lowBudget);
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            "engine-wrong-thread-eviction-test"
        );

        other.start();
        other.join();

        assertNull(failure.get());
        assertEquals(0L, engine.memoryBudgets().snapshot().evictions());
        assertSame(pool, engine.nativeStagingPoolOrNull());
        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(
                    line -> line.contains(
                        "active evictable: 1x32768 bytes"
                    )
                )
        );

        engine.memoryBudgets().applySettings(lowBudget);
        assertEquals(1L, engine.memoryBudgets().snapshot().evictions());
        assertNull(engine.nativeStagingPoolOrNull());
        engine.close();
        assertEquals(0L, engine.memoryBudgets().snapshot().leaks());
    }

    @Test
    void nativeShaderStagingBudgetRejectionDoesNotRetryLoop() {
        EngineConfig config = new EngineConfig(Path.of("unused-test-config"));
        BlockframeEngine engine =
            new BlockframeEngine(config, new FrameProfiler(4));
        long[] ram = categoryLimits(1L << 20);
        long[] vram = categoryLimits(1L << 20);
        ram[MemoryCategory.STAGING.ordinal()] = 32L * 1024L - 1L;
        engine.memoryBudgets().applySettings(
            new MemoryBudgetSettings(
                1L << 20,
                1L << 20,
                0L,
                0L,
                ram,
                vram
            )
        );

        assertNull(engine.nativeStagingPoolOrNull());
        assertEquals(
            1L,
            engine.memoryBudgets().snapshot().rejections()
        );
        assertNull(engine.nativeStagingPoolOrNull());
        assertEquals(
            1L,
            engine.memoryBudgets().snapshot().rejections()
        );
        assertTrue(
            engine
                .debugLines()
                .stream()
                .anyMatch(
                    line -> line.contains(
                        "direct fallback: RAM/STAGING rejected"
                    )
                )
        );
        engine.close();
    }

    private static long[] categoryLimits(long value) {
        long[] result = new long[MemoryCategory.values().length];
        Arrays.fill(result, value);
        return result;
    }
}
