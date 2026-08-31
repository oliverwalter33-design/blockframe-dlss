package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.scheduling.FrameBudgetController;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.Completion;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.Publication;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.Retirement;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.UploadTicket;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.CompilationPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ProducerIdentity;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit developer-only title-screen integration smoke.
 *
 * <p>Enabled only by {@code -Dblockframe.nativeTerrain.foundationBSmoke=true}.
 * It compiles a fixed Solid/Cutout model matrix from an immutable section on
 * the bounded terrain compiler jobsystem, uploads through the real Vulkan
 * owner, publishes, retires and closes. It never installs a world renderer or
 * submits a terrain draw.</p>
 */
final class NativeTerrainFoundationBSmoke {
    static final String ENABLE_PROPERTY =
        "blockframe.nativeTerrain.foundationBSmoke";

    enum State {
        DISABLED,
        WAITING_FOR_MODELS_AND_DEVICE,
        COMPILING,
        UPLOAD_PENDING,
        PASSED,
        FAILED
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(
        "blockframe-native-terrain-foundation-b-smoke"
    );
    private static final int EVIDENCE_WARMUP_SAMPLES = 4;
    private static final int EVIDENCE_MEASURE_SAMPLES = 32;

    private static volatile State state = enabled()
        ? State.WAITING_FOR_MODELS_AND_DEVICE
        : State.DISABLED;
    private static VulkanDevice device;
    private static ModelManager modelManager;
    private static MinecraftTerrainAssetCensusAdapter.Report census;
    private static NativeTerrainGeometryOwner owner;
    private static NativeTerrainJobSystem jobs;
    private static NativeTerrainSectionLifecycle lifecycle;
    private static CompilationPermit compilationPermit;
    private static MinecraftTerrainSectionSnapshot sourceSnapshot;
    private static NativeTerrainSnapshotPool snapshotPool;
    private static NativeTerrainPayloadArena payloadArena;
    private static volatile MinecraftTerrainModelAdapter.CompileResult
        workerCompile;
    private static volatile String workerFailure = "";
    private static CompiledPayloadBatch compiledBatch;
    private static UploadTicket upload;
    private static long[] snapshotSamples =
        new long[EVIDENCE_MEASURE_SAMPLES];
    private static long[] compileSamples =
        new long[EVIDENCE_MEASURE_SAMPLES];
    private static long[] payloadEncodeSamples =
        new long[EVIDENCE_MEASURE_SAMPLES];
    private static long snapshotNanos;
    private static long snapshotBytes;
    private static long compileNanos;
    private static long snapshotAllocatedBytes;
    private static long compileAllocatedBytes;
    private static long payloadEncodeAllocatedBytes;
    private static long compileGcCollections;
    private static long compileGcMillis;
    private static long uploadRecordNanos;
    private static long uploadWallStartedNanos;
    private static long stagedPayloadBytes;
    private static int quads;

    private NativeTerrainFoundationBSmoke() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static synchronized State state() {
        return state;
    }

    static synchronized void deviceConnected(VulkanDevice connected) {
        if (!enabled() || state == State.PASSED || state == State.FAILED) {
            return;
        }
        device = Objects.requireNonNull(connected, "connected");
        tryStart();
    }

    static synchronized void modelsReady(
        ModelManager manager,
        MinecraftTerrainAssetCensusAdapter.Report report
    ) {
        if (!enabled() || state == State.PASSED || state == State.FAILED) {
            return;
        }
        modelManager = Objects.requireNonNull(manager, "manager");
        census = Objects.requireNonNull(report, "report");
        tryStart();
    }

    static synchronized void frameEnded() {
        if (state == State.COMPILING && !workerFailure.isEmpty()) {
            String reason = workerFailure;
            workerFailure = "";
            lifecycle.failCompilation(compilationPermit);
            compilationPermit = null;
            fail("worker-evidence:" + reason);
            return;
        }
        if (state == State.COMPILING && workerCompile != null) {
            finishCompilationAndStartUpload();
        }
        if (state != State.UPLOAD_PENDING || upload == null) {
            return;
        }
        try {
            var polled = owner.pollUpload(
                upload,
                lifecycle.generations()
            );
            if (polled.pending()) {
                return;
            }
            if (polled.failure() != null) {
                fail("upload-poll:" + polled.failure());
                return;
            }
            Publication publication = polled.publication();
            NativeTerrainGeometryOwner.Snapshot publishedMetrics =
                owner.snapshot();
            long uploadWallNanos =
                System.nanoTime() - uploadWallStartedNanos;
            long retirementStarted = System.nanoTime();
            var cleanup = lifecycle.shutdown(0L);
            Retirement retirement = owner.beginRetirement(
                publication,
                cleanup,
                new ImmediateCompletion(),
                0L
            );
            if (!owner.pollRetirement(retirement)) {
                fail("unexpected-retirement-pending");
                return;
            }
            long retirementNanos =
                System.nanoTime() - retirementStarted;
            lifecycle.close();
            lifecycle = null;
            sourceSnapshot.close();
            sourceSnapshot = null;
            NativeTerrainSnapshotPool.Snapshot snapshotPoolMetrics =
                snapshotPool.snapshot();
            snapshotPool.close();
            snapshotPool = null;
            NativeTerrainPayloadArena.Snapshot payloadPoolMetrics =
                payloadArena.snapshot();
            payloadArena.close();
            payloadArena = null;
            if (!owner.closeAndReport()) {
                fail("owner-close-not-clean");
                return;
            }
            NativeTerrainGeometryOwner.Snapshot metrics =
                owner.snapshot();
            owner = null;
            upload = null;
            state = State.PASSED;
            LOGGER.info(
                "FOUNDATION_B_VULKAN_SMOKE_PASSED snapshotNanos={} "
                    + "snapshotBytes={} compileNanos={} quads={} "
                    + "peakDeviceLocalBytes={} stagingBytes={} "
                    + "uploads={} retirements={} "
                    + "snapshotP50={} snapshotP95={} snapshotP99={} "
                    + "compileP50={} compileP95={} compileP99={} "
                    + "snapshotAllocatedBytes={} "
                    + "modelAdapterAllocatedBytes={} "
                    + "payloadEncodeP50={} payloadEncodeP95={} "
                    + "payloadEncodeP99={} "
                    + "payloadEncodeAllocatedBytes={} "
                    + "snapshotPoolHits={} snapshotPoolAllocations={} "
                    + "payloadPoolHits={} payloadPoolAllocations={} "
                    + "gcCollections={} "
                    + "gcMillis={} uploadRecordNanos={} "
                    + "uploadWallNanos={} stagedPayloadBytes={} "
                    + "publishedCommittedBytes={} "
                    + "publishedUsedBytes={} fragmentationBytes={} "
                    + "pageCount={} retirementNanos={}",
                snapshotNanos,
                snapshotBytes,
                compileNanos,
                quads,
                metrics.peakUsedBytes(),
                metrics.stagingBytes(),
                metrics.successfulUploads(),
                metrics.retirements(),
                percentile(snapshotSamples, 0.50D),
                percentile(snapshotSamples, 0.95D),
                percentile(snapshotSamples, 0.99D),
                percentile(compileSamples, 0.50D),
                percentile(compileSamples, 0.95D),
                percentile(compileSamples, 0.99D),
                snapshotAllocatedBytes,
                compileAllocatedBytes,
                percentile(payloadEncodeSamples, 0.50D),
                percentile(payloadEncodeSamples, 0.95D),
                percentile(payloadEncodeSamples, 0.99D),
                payloadEncodeAllocatedBytes,
                snapshotPoolMetrics.pooledHits(),
                snapshotPoolMetrics.allocations(),
                payloadPoolMetrics.pooledHits(),
                payloadPoolMetrics.allocations(),
                compileGcCollections,
                compileGcMillis,
                uploadRecordNanos,
                uploadWallNanos,
                stagedPayloadBytes,
                publishedMetrics.committedBytes(),
                publishedMetrics.usedBytes(),
                publishedMetrics.externalFragmentationBytes(),
                publishedMetrics.pageCount(),
                retirementNanos
            );
            Minecraft.getInstance().stop();
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            fail("frame-poll:" + error.getClass().getSimpleName());
        }
    }

    static synchronized boolean deviceClosing(VulkanDevice closing) {
        if (device != closing) {
            return true;
        }
        boolean clean = state == State.DISABLED
            || state == State.PASSED
            || state == State.FAILED;
        if (owner != null) {
            clean &= owner.closeAndReport();
        }
        closeJobs();
        device = null;
        if (!clean) {
            fail("device-close-with-in-flight-foundation-b-smoke");
        }
        return clean;
    }

    private static void tryStart() {
        if (
            state != State.WAITING_FOR_MODELS_AND_DEVICE
                || device == null
                || modelManager == null
                || census == null
        ) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            fail("smoke-start-off-client-thread");
            return;
        }

        try {
            GenerationStamp generations = new GenerationStamp(
                Math.max(1L, BlockframeRuntime.deviceGeneration()),
                1L,
                1L,
                census.resourceGeneration(),
                1L,
                1L
            );
            SectionIdentity section = new SectionIdentity(
                NativeTerrainContractIds.stableId(
                    "smoke-world",
                    "foundation-b-title-screen"
                ),
                SectionPos.asLong(0, 0, 0)
            );
            FixedSmokeWorld world = new FixedSmokeWorld();
            world.put(0, 1, 1, Blocks.STONE.defaultBlockState());
            world.put(-1, 1, 1, Blocks.STONE.defaultBlockState());
            world.put(
                2,
                1,
                1,
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
            );
            world.put(
                4,
                1,
                1,
                Blocks.OAK_STAIRS.defaultBlockState()
            );
            world.put(
                6,
                1,
                1,
                Blocks.OAK_FENCE.defaultBlockState()
            );
            world.put(
                8,
                1,
                1,
                Blocks.COBBLESTONE_WALL.defaultBlockState()
            );
            world.put(
                10,
                1,
                1,
                Blocks.IRON_BARS.defaultBlockState()
            );
            world.put(
                12,
                1,
                1,
                Blocks.OAK_LEAVES.defaultBlockState()
            );
            world.put(
                14,
                1,
                1,
                Blocks.BOOKSHELF.defaultBlockState()
            );
            world.put(
                3,
                3,
                1,
                Blocks.SHORT_GRASS.defaultBlockState()
            );
            long snapshotAllocationBefore = 0L;
            snapshotPool = new NativeTerrainSnapshotPool(2);
            for (
                int sample = -EVIDENCE_WARMUP_SAMPLES;
                sample < EVIDENCE_MEASURE_SAMPLES;
                sample++
            ) {
                if (sample == 0) {
                    snapshotAllocationBefore =
                        currentThreadAllocatedBytes();
                }
                var captured =
                    MinecraftTerrainSectionSnapshot.capture(
                        world,
                        SectionPos.of(0, 0, 0),
                        generations,
                        section,
                        census.census().digest(),
                        BlockframeRuntime.memoryBudgets(),
                        1024L * 1024L,
                        true,
                        snapshotPool
                    );
                if (!captured.successful()) {
                    fail(
                        "snapshot:"
                            + captured.failureReason()
                            + ":"
                            + captured.detail()
                    );
                    return;
                }
                MinecraftTerrainSectionSnapshot sampleSnapshot =
                    captured.snapshot();
                if (sample >= 0) {
                    snapshotSamples[sample] =
                        sampleSnapshot.captureNanos();
                }
                if (sample == EVIDENCE_MEASURE_SAMPLES - 1) {
                    sourceSnapshot = sampleSnapshot;
                } else {
                    sampleSnapshot.close();
                }
            }
            snapshotAllocatedBytes = nonNegativeDelta(
                snapshotAllocationBefore,
                currentThreadAllocatedBytes()
            );
            snapshotNanos = percentile(snapshotSamples, 0.50D);
            snapshotBytes = sourceSnapshot.estimatedBytes();

            lifecycle = new NativeTerrainSectionLifecycle(
                section,
                generations
            );
            compilationPermit = lifecycle.beginCompilation();
            jobs = new NativeTerrainJobSystem(
                NativeTerrainJobSystem.Topology
                    .conservativeRuntimeTopology(),
                4
            );
            FrameBudgetController budgetController =
                new FrameBudgetController(
                    16_666_667L,
                    jobs.physicalWorkerLimit(),
                    jobs.logicalWorkerLimit(),
                    4L * 1024L * 1024L,
                    64L * 1024L * 1024L
                );
            jobs.applyBudget(
                budgetController.decide(
                    new FrameBudgetController.Inputs(
                        0L,
                        0L,
                        0L,
                        0L,
                        0,
                        1,
                        0,
                        BlockframeRuntime.memoryBudgets()
                            .availableBytes(
                                de.morau.blockframe.core.budget
                                    .MemoryKind.RAM
                            ),
                        BlockframeRuntime.memoryBudgets()
                            .availableBytes(
                                de.morau.blockframe.core.budget
                                    .MemoryKind.VRAM
                            ),
                        snapshotNanos,
                        0L,
                        0L
                    )
                )
            );
            MinecraftTerrainSectionSnapshot capturedSnapshot =
                sourceSnapshot;
            ModelManager capturedManager = modelManager;
            var capturedCensus = census;
            state = State.COMPILING;
            if (
                !jobs.submit(
                    new NativeTerrainJobSystem.Job(
                        NativeTerrainJobSystem.Priority.VISIBLE,
                        0,
                        () ->
                            state == State.COMPILING
                                && capturedSnapshot.validFor(
                                    generations
                                ),
                        () -> {
                            MinecraftTerrainModelAdapter adapter =
                                new MinecraftTerrainModelAdapter(
                                    capturedManager
                                        .getBlockStateModelSet(),
                                    minecraft.getBlockColors(),
                                    true,
                                    true
                                );
                            runCompileEvidence(
                                adapter,
                                capturedSnapshot,
                                capturedCensus
                            );
                        }
                    )
                )
            ) {
                fail("bounded-compiler-job-rejected");
                return;
            }
            LOGGER.info(
                "Foundation B real fixture queued: snapshotNanos={} "
                    + "snapshotBytes={} physicalWorkerLimit={} "
                    + "logicalWorkerLimit={}",
                snapshotNanos,
                snapshotBytes,
                jobs.physicalWorkerLimit(),
                jobs.logicalWorkerLimit()
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            fail("smoke-start:" + error.getClass().getSimpleName());
        }
    }

    private static void publishWorkerCompile(
        MinecraftTerrainModelAdapter.CompileResult result
    ) {
        if (state == State.COMPILING) {
            workerCompile = Objects.requireNonNull(result, "result");
        }
    }

    private static void runCompileEvidence(
        MinecraftTerrainModelAdapter adapter,
        MinecraftTerrainSectionSnapshot snapshot,
        MinecraftTerrainAssetCensusAdapter.Report capturedCensus
    ) {
        long expectedChecksum = 0L;
        int expectedQuads = -1;
        long allocationBefore = 0L;
        long gcCountBefore = 0L;
        long gcMillisBefore = 0L;
        MinecraftTerrainModelAdapter.CompileResult last = null;
        for (
            int sample = -EVIDENCE_WARMUP_SAMPLES;
            sample < EVIDENCE_MEASURE_SAMPLES;
            sample++
        ) {
            if (sample == 0) {
                allocationBefore = currentThreadAllocatedBytes();
                gcCountBefore = gcCollections();
                gcMillisBefore = gcMillis();
            }
            MinecraftTerrainModelAdapter.CompileResult result =
                adapter.compile(
                    snapshot,
                    capturedCensus.census(),
                    () -> state != State.COMPILING,
                    0
                );
            if (!result.successful()) {
                publishWorkerFailure(
                    result.failureReason() + ":" + result.detail()
                );
                return;
            }
            long checksum = geometryChecksum(result.snapshot());
            if (expectedQuads < 0) {
                expectedQuads = result.emittedQuads();
                expectedChecksum = checksum;
            } else if (
                expectedQuads != result.emittedQuads()
                    || expectedChecksum != checksum
            ) {
                publishWorkerFailure(
                    "non-deterministic-model-output"
                );
                return;
            }
            if (sample >= 0) {
                compileSamples[sample] = result.compileNanos();
                last = result;
            }
        }
        compileAllocatedBytes = nonNegativeDelta(
            allocationBefore,
            currentThreadAllocatedBytes()
        );
        compileGcCollections = nonNegativeDelta(
            gcCountBefore,
            gcCollections()
        );
        compileGcMillis = nonNegativeDelta(
            gcMillisBefore,
            gcMillis()
        );
        publishWorkerCompile(Objects.requireNonNull(last, "last"));
    }

    private static void publishWorkerFailure(String reason) {
        if (state == State.COMPILING) {
            workerFailure = Objects.requireNonNull(reason, "reason");
        }
    }

    private static void finishCompilationAndStartUpload() {
        MinecraftTerrainModelAdapter.CompileResult modelCompile =
            workerCompile;
        workerCompile = null;
        closeJobs();
        if (!modelCompile.successful()) {
            lifecycle.failCompilation(compilationPermit);
            compilationPermit = null;
            fail(
                "model-compile:"
                    + modelCompile.failureReason()
                    + ":"
                    + modelCompile.detail()
            );
            return;
        }
        compileNanos = modelCompile.compileNanos();
        quads = modelCompile.emittedQuads();
        try {
            payloadArena = new NativeTerrainPayloadArena(
                1024 * 1024,
                2
            );
            BlockFrameSectionCompiler compiler =
                new BlockFrameSectionCompiler(
                    new BlockFrameSectionCompiler.CompilerContract(
                        new ProducerIdentity(
                            NativeTerrainContractIds.stableId(
                                "producer",
                                "foundation-b-smoke"
                            ),
                            1
                        ),
                        NativeTerrainContractIds.stableId(
                            "transform-layout",
                            "identity"
                        ),
                        1L,
                        1L
                    ),
                    payloadArena
                );
            long payloadAllocationBefore = 0L;
            long expectedPayloadChecksum = 0L;
            CompiledPayloadBatch batch = null;
            for (
                int sample = -EVIDENCE_WARMUP_SAMPLES;
                sample < EVIDENCE_MEASURE_SAMPLES;
                sample++
            ) {
                if (sample == 0) {
                    payloadAllocationBefore =
                        currentThreadAllocatedBytes();
                }
                long payloadStarted = System.nanoTime();
                var encoded = compiler.compile(
                    modelCompile.snapshot(),
                    census.census(),
                    BlockFrameSectionCompiler.CancellationSignal.NEVER
                );
                long payloadElapsed =
                    System.nanoTime() - payloadStarted;
                if (!encoded.successful()) {
                    lifecycle.failCompilation(compilationPermit);
                    compilationPermit = null;
                    fail(
                        "abi-compile:"
                            + encoded.failureReason().orElse(null)
                            + ":"
                            + encoded.detail()
                    );
                    return;
                }
                CompiledPayloadBatch measured =
                    encoded.batch().orElseThrow();
                long checksum = payloadChecksum(measured);
                if (
                    sample > -EVIDENCE_WARMUP_SAMPLES
                        && checksum != expectedPayloadChecksum
                ) {
                    measured.close();
                    lifecycle.failCompilation(compilationPermit);
                    compilationPermit = null;
                    fail("abi-compile-nondeterministic");
                    return;
                }
                expectedPayloadChecksum = checksum;
                if (sample >= 0) {
                    payloadEncodeSamples[sample] = payloadElapsed;
                }
                if (sample == EVIDENCE_MEASURE_SAMPLES - 1) {
                    batch = measured;
                } else {
                    measured.close();
                }
            }
            payloadEncodeAllocatedBytes = nonNegativeDelta(
                payloadAllocationBefore,
                currentThreadAllocatedBytes()
            );
            lifecycle.completeCompilation(compilationPermit);
            compilationPermit = null;
            batch = Objects.requireNonNull(
                batch,
                "final payload batch"
            );
            compiledBatch = batch;
            List<Long> observed = new ArrayList<>();
            long stagingBytes = 0L;
            for (var channel : batch.channels().values()) {
                int bytes = channel.byteLength();
                if (bytes != 0) {
                    observed.add((long)bytes);
                    stagingBytes = Math.addExact(
                        stagingBytes,
                        bytes
                    );
                }
            }
            if (observed.isEmpty()) {
                batch.close();
                compiledBatch = null;
                fail("actual-model-smoke-emitted-no-payload");
                return;
            }
            long maximumAllocation = device.getDeviceInfo()
                .limits()
                .maxMemoryAllocationSize();
            long maximumObserved = observed.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElseThrow();
            var pagePolicy =
                NativeTerrainGeometryOwner.PagePolicy.derive(
                    observed,
                    maximumAllocation,
                    maximumObserved,
                    16L
                );
            owner = new NativeTerrainGeometryOwner(
                lifecycle.generations().device(),
                BlockframeRuntime.memoryBudgets(),
                new NativeTerrainGeometryOwner.VulkanDeviceAccess(
                    device
                ),
                pagePolicy,
                Math.addExact(
                    Math.multiplyExact(
                        pagePolicy.pageBytes(),
                        observed.size()
                    ),
                    pagePolicy.pageBytes()
                ),
                Math.addExact(stagingBytes, 16L)
            );
            long uploadRecordStarted = System.nanoTime();
            var started = owner.tryUpload(
                /*
                 * CPU record time excludes fence wait; completion wall time
                 * is logged separately and never reported as throughput of a
                 * sustained workload.
                 */
                batch,
                lifecycle,
                lifecycle.generations()
            );
            uploadRecordNanos =
                System.nanoTime() - uploadRecordStarted;
            if (!started.started()) {
                batch.close();
                compiledBatch = null;
                fail(
                    "upload-start:"
                        + started.failure()
                        + ":"
                        + started.detail()
                );
                return;
            }
            upload = started.ticket();
            stagedPayloadBytes = upload.stagedBytes();
            uploadWallStartedNanos = System.nanoTime();
            compiledBatch = null;
            state = State.UPLOAD_PENDING;
            LOGGER.info(
                "Foundation B Vulkan smoke upload recorded: "
                    + "blockStates={} observations={} quads={} bytes={}",
                census.blockStateCount(),
                census.observations().size(),
                quads,
                stagingBytes
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            fail(
                "compile-finalization:"
                    + error.getClass().getSimpleName()
            );
        }
    }

    private static void fail(String reason) {
        state = State.FAILED;
        LOGGER.error("FOUNDATION_B_VULKAN_SMOKE_FAILED reason={}", reason);
        closeJobs();
        if (compiledBatch != null) {
            try {
                compiledBatch.close();
            } catch (RuntimeException ignored) {
                // Failure stays visible and the owner remains fail-closed.
            }
            compiledBatch = null;
        }
        if (sourceSnapshot != null) {
            try {
                sourceSnapshot.close();
            } catch (RuntimeException ignored) {
                // Failure remains visible and owner-bound.
            }
            sourceSnapshot = null;
        }
        if (snapshotPool != null) {
            try {
                snapshotPool.close();
                snapshotPool = null;
            } catch (RuntimeException ignored) {
                // Outstanding snapshot storage remains owner-visible.
            }
        }
        if (lifecycle != null) {
            try {
                switch (lifecycle.state()) {
                    case SNAPSHOT, COMPILING, COMPILED ->
                        lifecycle.cancelBeforePublish(
                            NativeTerrainSectionLifecycle.Cause.SHUTDOWN,
                            0L
                        );
                    default -> {
                    }
                }
                if (
                    lifecycle.state()
                        == NativeTerrainSectionLifecycle.State.CANCELLED
                        || lifecycle.state()
                            == NativeTerrainSectionLifecycle.State.RETIRED
                ) {
                    lifecycle.close();
                    lifecycle = null;
                }
            } catch (RuntimeException ignored) {
                // Device-close hook will retain the failed owner visibly.
            }
        }
        if (owner != null && owner.closeAndReport()) {
            owner = null;
            upload = null;
        }
        if (payloadArena != null) {
            try {
                payloadArena.close();
                payloadArena = null;
            } catch (RuntimeException ignored) {
                // Outstanding upload bytes remain owner-visible.
            }
        }
        if (enabled()) {
            Minecraft.getInstance().stop();
        }
    }

    private static void closeJobs() {
        NativeTerrainJobSystem current = jobs;
        jobs = null;
        if (current != null) {
            current.close();
        }
    }

    private static long percentile(long[] samples, double quantile) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = Math.min(
            sorted.length - 1,
            Math.max(
                0,
                (int)Math.ceil(sorted.length * quantile) - 1
            )
        );
        return sorted[index];
    }

    private static long geometryChecksum(
        NativeTerrainSectionSnapshot snapshot
    ) {
        long value = 0x6A09E667F3BCC909L;
        for (
            NativeTerrainSectionSnapshot.Primitive primitive
                : snapshot.primitives()
        ) {
            var digest = primitive.geometryDigest();
            value = Long.rotateLeft(
                value ^ digest.part0(),
                13
            );
            value = Long.rotateLeft(
                value ^ digest.part1(),
                17
            );
            value = Long.rotateLeft(
                value ^ digest.part2(),
                29
            );
            value = Long.rotateLeft(
                value ^ digest.part3(),
                37
            );
            value ^= primitive.primitiveId();
        }
        return value;
    }

    private static long payloadChecksum(CompiledPayloadBatch batch) {
        long value = 0xBB67AE8584CAA73BL;
        for (var entry : batch.channels().entrySet()) {
            var channel = entry.getValue();
            value = Long.rotateLeft(
                value ^ entry.getKey().ordinal(),
                7
            );
            value = Long.rotateLeft(
                value ^ channel.byteLength(),
                13
            );
            value = Long.rotateLeft(
                value ^ channel.primitiveCount(),
                17
            );
        }
        return value;
    }

    private static long currentThreadAllocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (
            bean instanceof com.sun.management.ThreadMXBean allocated
                && allocated.isThreadAllocatedMemorySupported()
        ) {
            if (!allocated.isThreadAllocatedMemoryEnabled()) {
                allocated.setThreadAllocatedMemoryEnabled(true);
            }
            return allocated.getThreadAllocatedBytes(
                Thread.currentThread().threadId()
            );
        }
        return -1L;
    }

    private static long gcCollections() {
        long total = 0L;
        for (
            var collector
                : ManagementFactory.getGarbageCollectorMXBeans()
        ) {
            long count = collector.getCollectionCount();
            if (count >= 0L) {
                total = Math.addExact(total, count);
            }
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0L;
        for (
            var collector
                : ManagementFactory.getGarbageCollectorMXBeans()
        ) {
            long millis = collector.getCollectionTime();
            if (millis >= 0L) {
                total = Math.addExact(total, millis);
            }
        }
        return total;
    }

    private static long nonNegativeDelta(long before, long after) {
        if (before < 0L || after < 0L) {
            return -1L;
        }
        return Math.max(0L, after - before);
    }

    private static final class ImmediateCompletion
        implements Completion {
        @Override
        public boolean completed() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final class FixedSmokeWorld
        implements BlockAndTintGetter {
        private final Map<Long, BlockState> states = new HashMap<>();

        private void put(int x, int y, int z, BlockState state) {
            this.states.put(BlockPos.asLong(x, y, z), state);
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return this.states.getOrDefault(
                position.asLong(),
                Blocks.AIR.defaultBlockState()
            );
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public int getBrightness(
            LightLayer layer,
            BlockPos position
        ) {
            return 15;
        }

        @Override
        public int getBlockTint(
            BlockPos position,
            ColorResolver resolver
        ) {
            if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
                return 0x66AA44;
            }
            if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
                return 0x559944;
            }
            if (
                resolver
                    == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER
            ) {
                return 0x998855;
            }
            return 0x3F76E4;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(
            BlockPos position
        ) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
