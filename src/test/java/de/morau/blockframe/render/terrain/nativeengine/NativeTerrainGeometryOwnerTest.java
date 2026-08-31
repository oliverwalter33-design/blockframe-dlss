package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.BufferKind;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.Completion;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.CopyRegion;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.DeviceAccess;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.MappedStagingBuffer;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.OwnedBuffer;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.RecordResult;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.UploadFailure;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.Cause;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeTerrainGeometryOwnerTest {
    @Test
    void batchesUploadPublishAndRetireOnlyAfterCompletion() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.SOLID
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var source = NativeTerrainCompilerTestFixtures.snapshot(
            census,
            NativeTerrainCompilerTestFixtures.quad(1L, entry, 0.0F)
        );
        var lifecycle = lifecycleCompiled();
        CompiledPayloadBatch batch =
            NativeTerrainCompilerTestFixtures.compiler()
                .compile(
                    source,
                    census,
                    BlockFrameSectionCompiler.CancellationSignal.NEVER
                )
                .batch()
                .orElseThrow();
        FakeDevice device = new FakeDevice();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );

        var start = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );
        assertTrue(start.started());
        assertEquals(
            NativeTerrainSectionLifecycle.State.UPLOADING,
            lifecycle.state()
        );
        assertTrue(
            owner.pollUpload(
                start.ticket(),
                NativeTerrainCompilerTestFixtures.generations()
            ).pending()
        );

        device.lastCompletion.complete = true;
        var completed = owner.pollUpload(
            start.ticket(),
            NativeTerrainCompilerTestFixtures.generations()
        );
        assertNotNull(completed.publication());
        assertEquals(
            NativeTerrainSectionLifecycle.State.PUBLISHED,
            lifecycle.state()
        );
        assertFalse(
            completed.publication()
                .cpuUploadBytesResident(
                    NativeTerrainAssetCensus.Category.SOLID
                )
        );
        assertEquals(128L, owner.snapshot().usedBytes());
        assertEquals(1, owner.snapshot().allocationCount());

        var cleanup = lifecycle.shutdown(0L);
        FakeCompletion useCompletion = new FakeCompletion(true);
        var retirement = owner.beginRetirement(
            completed.publication(),
            cleanup,
            useCompletion,
            0L
        );
        assertTrue(owner.pollRetirement(retirement));
        assertEquals(
            NativeTerrainSectionLifecycle.State.RETIRED,
            lifecycle.state()
        );
        assertEquals(0L, owner.snapshot().usedBytes());
        assertEquals(1L, owner.snapshot().retirements());
        lifecycle.close();

        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void boundedStagingRejectsWithoutAllocatingGeometry() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.CUTOUT
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        2L,
                        entry,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = lifecycleCompiled();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            new FakeDevice(),
            64L
        );

        var rejected = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );
        assertEquals(
            UploadFailure.STAGING_CAPACITY_EXCEEDED,
            rejected.failure()
        );
        assertEquals(
            NativeTerrainSectionLifecycle.State.CANCELLED,
            lifecycle.state()
        );
        assertEquals(0, owner.snapshot().allocationCount());
        batch.close();
        lifecycle.close();
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void copyFailureWaitsForFenceThenRollsBackAtomically() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.SOLID
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        3L,
                        entry,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = lifecycleCompiled();
        FakeDevice device = new FakeDevice();
        device.failCopy = true;
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );

        var start = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );
        assertTrue(start.started());
        assertTrue(
            owner.pollUpload(
                start.ticket(),
                NativeTerrainCompilerTestFixtures.generations()
            ).pending()
        );
        assertEquals(1, owner.snapshot().allocationCount());

        device.lastCompletion.complete = true;
        var failed = owner.pollUpload(
            start.ticket(),
            NativeTerrainCompilerTestFixtures.generations()
        );
        assertEquals(UploadFailure.COPY_RECORD_FAILED, failed.failure());
        assertEquals(0, owner.snapshot().allocationCount());
        assertEquals(
            NativeTerrainSectionLifecycle.State.CANCELLED,
            lifecycle.state()
        );
        lifecycle.close();
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void generationChangeAfterRecordedCopyRollsBackBeforePublish() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.SOLID
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        4L,
                        entry,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = lifecycleCompiled();
        FakeDevice device = new FakeDevice();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );

        var start = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );
        assertTrue(start.started());
        device.lastCompletion.complete = true;
        var stale = owner.pollUpload(
            start.ticket(),
            new TerrainMeshProducerABI.GenerationStamp(
                2L,
                2L,
                3L,
                NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION,
                5L,
                6L
            )
        );

        assertEquals(UploadFailure.GENERATION_MISMATCH, stale.failure());
        assertEquals(0, owner.snapshot().allocationCount());
        assertEquals(
            NativeTerrainSectionLifecycle.State.CANCELLED,
            lifecycle.state()
        );
        lifecycle.close();
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void staleReloadIsRejectedBeforeAnyVulkanCopyIsRecorded() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.CUTOUT
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        5L,
                        entry,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = lifecycleCompiled();
        FakeDevice device = new FakeDevice();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );

        var rejected = owner.tryUpload(
            batch,
            lifecycle,
            new TerrainMeshProducerABI.GenerationStamp(
                1L,
                2L,
                3L,
                NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION
                    + 1L,
                5L,
                6L
            )
        );

        assertEquals(UploadFailure.GENERATION_MISMATCH, rejected.failure());
        assertEquals(0, device.copyBatches);
        assertEquals(0, owner.snapshot().allocationCount());
        batch.close();
        lifecycle.close();
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void vramMaximumRejectsAtomicallyWithoutRecordingACopy() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.SOLID
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        6L,
                        entry,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = lifecycleCompiled();
        FakeDevice device = new FakeDevice();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner =
            new NativeTerrainGeometryOwner(
                1L,
                budgets,
                device,
                NativeTerrainGeometryOwner.PagePolicy.derive(
                    List.of(128L),
                    4096L,
                    4096L,
                    16L
                ),
                64L,
                4096L
            );

        var rejected = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );

        assertEquals(UploadFailure.VRAM_BUDGET_REJECTED, rejected.failure());
        assertEquals(0, device.copyBatches);
        assertEquals(0, owner.snapshot().allocationCount());
        batch.close();
        lifecycle.close();
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void ownerCloseRetainsAccountingAndRetriesFailedBufferCleanup() {
        var entry = NativeTerrainCompilerTestFixtures.entry(
            NativeTerrainAssetCensus.Category.SOLID
        );
        var census = NativeTerrainCompilerTestFixtures.census(entry);
        var batch = NativeTerrainCompilerTestFixtures.compiler()
            .compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        7L,
                        entry,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            )
            .batch()
            .orElseThrow();
        var lifecycle = lifecycleCompiled();
        FakeDevice device = new FakeDevice();
        device.failNextDeviceBufferClose = true;
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );
        var start = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );
        device.lastCompletion.complete = true;
        var publication = owner.pollUpload(
            start.ticket(),
            NativeTerrainCompilerTestFixtures.generations()
        ).publication();
        var cleanup = lifecycle.shutdown(0L);
        assertTrue(
            owner.pollRetirement(
                owner.beginRetirement(
                    publication,
                    cleanup,
                    new FakeCompletion(true),
                    0L
                )
            )
        );
        lifecycle.close();

        assertFalse(owner.closeAndReport());
        assertFalse(owner.snapshot().closed());
        assertTrue(owner.closeAndReport());
        assertTrue(owner.snapshot().closed());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void pagePolicyUsesObservedP95AndConfiguredCeilings() {
        var policy = NativeTerrainGeometryOwner.PagePolicy.derive(
            List.of(64L, 80L, 96L, 112L, 128L),
            4096L,
            2048L,
            16L
        );
        assertEquals(128L, policy.observedP95PayloadBytes());
        assertEquals(128L, policy.pageBytes());
        assertEquals(16L, policy.alignmentBytes());
    }

    @Test
    void fixedResourcesAndDirtyUpdatesShareBoundedStagingAndRetire() {
        FakeDevice device = new FakeDevice();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );
        var start = owner.tryCreateResources(
            List.of(
                new NativeTerrainGeometryOwner.ResourceRequest(
                    BufferKind.SHARED_INDEX,
                    128L,
                    bytes -> {
                        while (bytes.hasRemaining()) {
                            bytes.put((byte)0x11);
                        }
                    }
                ),
                new NativeTerrainGeometryOwner.ResourceRequest(
                    BufferKind.STORAGE_SCENE,
                    256L,
                    bytes -> {
                        while (bytes.hasRemaining()) {
                            bytes.put((byte)0);
                        }
                    }
                ),
                new NativeTerrainGeometryOwner.ResourceRequest(
                    BufferKind.INDIRECT_COMMAND,
                    320L,
                    null
                ),
                new NativeTerrainGeometryOwner.ResourceRequest(
                    BufferKind.INDIRECT_COUNT,
                    16L,
                    null
                )
            ),
            1L
        );
        assertTrue(start.started());
        assertTrue(
            owner.pollResources(start.ticket(), 1L).pending()
        );
        device.lastCompletion.complete = true;
        var publication =
            owner.pollResources(start.ticket(), 1L).publication();
        assertNotNull(publication);
        assertEquals(4, publication.handles().size());

        var scene = publication.require(BufferKind.STORAGE_SCENE);
        var update = owner.tryUpdateResources(
            List.of(
                new NativeTerrainGeometryOwner.ResourceWrite(
                    scene,
                    32L,
                    16L,
                    bytes -> {
                        while (bytes.hasRemaining()) {
                            bytes.put((byte)0x5A);
                        }
                    }
                )
            ),
            1L
        );
        assertTrue(update.started());
        device.lastCompletion.complete = true;
        assertTrue(
            owner.pollResourceUpdate(update.ticket(), 1L)
                .successful()
        );
        NativeTerrainGeometryOwner.BufferBinding binding =
            owner.requireBinding(scene);
        FakeBuffer sceneBuffer = (FakeBuffer)binding.buffer();
        int updateOffset = Math.toIntExact(
            binding.offset() + 32L
        );
        for (int index = 0; index < 16; index++) {
            assertEquals(
                (byte)0x5A,
                sceneBuffer.bytes[updateOffset + index]
            );
        }

        var retirement = owner.beginResourceRetirement(
            publication,
            new FakeCompletion(true)
        );
        assertTrue(owner.pollResourceRetirement(retirement));
        assertEquals(0, owner.snapshot().allocationCount());
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    @Test
    void resourceUpdateGenerationAndBackpressureFailClosed() {
        FakeDevice device = new FakeDevice();
        MemoryBudgetManager budgets = budgets();
        NativeTerrainGeometryOwner owner = owner(
            budgets,
            device,
            4096L
        );
        var start = owner.tryCreateResources(
            List.of(
                new NativeTerrainGeometryOwner.ResourceRequest(
                    BufferKind.STORAGE_SCENE,
                    256L,
                    bytes -> {
                        while (bytes.hasRemaining()) {
                            bytes.put((byte)0);
                        }
                    }
                )
            ),
            1L
        );
        assertEquals(
            UploadFailure.BACKPRESSURE,
            owner.tryCreateResources(
                List.of(
                    new NativeTerrainGeometryOwner.ResourceRequest(
                        BufferKind.INDIRECT_COUNT,
                        16L,
                        null
                    )
                ),
                1L
            ).failure()
        );
        device.lastCompletion.complete = true;
        var publication =
            owner.pollResources(start.ticket(), 1L).publication();
        var scene = publication.require(BufferKind.STORAGE_SCENE);
        assertEquals(
            UploadFailure.GENERATION_MISMATCH,
            owner.tryUpdateResources(
                List.of(
                    new NativeTerrainGeometryOwner.ResourceWrite(
                        scene,
                        0L,
                        4L,
                        bytes -> bytes.putInt(1)
                    )
                ),
                2L
            ).failure()
        );
        assertTrue(
            owner.pollResourceRetirement(
                owner.beginResourceRetirement(
                    publication,
                    new FakeCompletion(true)
                )
            )
        );
        assertTrue(owner.closeAndReport());
        budgets.completeGpuRetirements();
        assertTrue(budgets.closeAndReport());
    }

    private static NativeTerrainSectionLifecycle lifecycleCompiled() {
        var lifecycle = new NativeTerrainSectionLifecycle(
            NativeTerrainCompilerTestFixtures.section(),
            NativeTerrainCompilerTestFixtures.generations()
        );
        var permit = lifecycle.beginCompilation();
        lifecycle.completeCompilation(permit);
        return lifecycle;
    }

    private static NativeTerrainGeometryOwner owner(
        MemoryBudgetManager budgets,
        FakeDevice device,
        long stagingBytes
    ) {
        return new NativeTerrainGeometryOwner(
            1L,
            budgets,
            device,
            NativeTerrainGeometryOwner.PagePolicy.derive(
                List.of(128L, 256L, 512L),
                16_384L,
                8192L,
                16L
            ),
            32_768L,
            stagingBytes
        );
    }

    private static MemoryBudgetManager budgets() {
        return new MemoryBudgetManager(MemoryBudgetSettings.defaults());
    }

    private static class FakeBuffer implements OwnedBuffer {
        final byte[] bytes;
        boolean closed;
        int failedCloseAttempts;

        private FakeBuffer(long bytes) {
            this.bytes = new byte[Math.toIntExact(bytes)];
        }

        @Override
        public long size() {
            return this.bytes.length;
        }

        @Override
        public void close() {
            if (this.failedCloseAttempts > 0) {
                this.failedCloseAttempts--;
                throw new IllegalStateException(
                    "forced-buffer-close-failure"
                );
            }
            this.closed = true;
        }
    }

    private static final class FakeStaging
        extends FakeBuffer
        implements MappedStagingBuffer {
        private FakeStaging(long bytes) {
            super(bytes);
        }

        @Override
        public void copyFrom(
            long destinationOffset,
            BlockFrameSectionCompiler.ChannelPayload payload
        ) {
            ByteBuffer target = ByteBuffer.wrap(this.bytes);
            target.position(Math.toIntExact(destinationOffset));
            target.limit(
                Math.toIntExact(
                    destinationOffset + payload.byteLength()
                )
            );
            payload.copyBytesTo(target.slice());
        }

        @Override
        public void write(
            long destinationOffset,
            long length,
            NativeTerrainGeometryOwner.BufferWriter writer
        ) {
            ByteBuffer target = ByteBuffer.wrap(this.bytes);
            target.position(Math.toIntExact(destinationOffset));
            target.limit(
                Math.toIntExact(destinationOffset + length)
            );
            ByteBuffer slice = target.slice();
            writer.write(slice);
            if (slice.hasRemaining()) {
                throw new IllegalStateException(
                    "writer did not fill fake staging range"
                );
            }
        }
    }

    private static final class FakeCompletion
        implements Completion {
        boolean complete;
        boolean closed;

        private FakeCompletion(boolean complete) {
            this.complete = complete;
        }

        @Override
        public boolean completed() {
            return this.complete;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private static final class FakeDevice implements DeviceAccess {
        final List<FakeBuffer> buffers = new ArrayList<>();
        FakeCompletion lastCompletion;
        boolean failCopy;
        boolean failNextDeviceBufferClose;
        int copyBatches;

        @Override
        public OwnedBuffer createDeviceBuffer(
            BufferKind kind,
            long bytes
        ) {
            FakeBuffer buffer = new FakeBuffer(bytes);
            if (this.failNextDeviceBufferClose) {
                buffer.failedCloseAttempts = 1;
                this.failNextDeviceBufferClose = false;
            }
            this.buffers.add(buffer);
            return buffer;
        }

        @Override
        public MappedStagingBuffer createMappedStaging(long bytes) {
            FakeStaging staging = new FakeStaging(bytes);
            this.buffers.add(staging);
            return staging;
        }

        @Override
        public RecordResult recordCopies(List<CopyRegion> copies) {
            this.copyBatches++;
            for (CopyRegion copy : copies) {
                FakeStaging source = (FakeStaging)copy.source();
                FakeBuffer destination =
                    (FakeBuffer)copy.destination();
                System.arraycopy(
                    source.bytes,
                    Math.toIntExact(copy.sourceOffset()),
                    destination.bytes,
                    Math.toIntExact(copy.destinationOffset()),
                    Math.toIntExact(copy.length())
                );
            }
            this.lastCompletion = new FakeCompletion(false);
            return new RecordResult(
                this.lastCompletion,
                this.failCopy
                    ? new IllegalStateException("forced-copy-failure")
                    : null
            );
        }
    }
}
