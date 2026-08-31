package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.BufferBinding;
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
    .NativeTerrainGeometryOwner.Publication;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainGeometryOwner.RecordResult;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MaterialBinding;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.MotionModel;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.ShaderContract;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.StableId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeTerrainGpuSceneTest {
    @Test
    void publishesSolidCutoutAsDirtyEntriesThenRemovesAtomically() {
        Fixture fixture = fixture(16);
        NativeTerrainGpuScene.Mutation publish =
            fixture.scene.preparePublication(fixture.geometry);
        assertEquals(2, publish.entryCount());
        apply(fixture, publish);
        assertEquals(2, fixture.scene.snapshot().activeEntries());
        assertEquals(1, fixture.scene.snapshot().vertexPages());
        assertEquals(160L, fixture.scene.snapshot().dirtyBytesUploaded());

        BufferBinding sceneBinding =
            fixture.owner.requireBinding(
                fixture.resources.require(BufferKind.STORAGE_SCENE)
            );
        FakeBuffer sceneBytes = (FakeBuffer)sceneBinding.buffer();
        ByteBuffer first = ByteBuffer.wrap(sceneBytes.bytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        first.position(Math.toIntExact(sceneBinding.offset()));
        assertEquals(
            NativeTerrainGpuScene.FLAG_ACTIVE
                | NativeTerrainGpuScene.FLAG_UNCERTAIN,
            first.getInt()
        );
        assertEquals(0, first.getInt());
        int sceneOffset = Math.toIntExact(sceneBinding.offset());
        assertEquals(
            1,
            first.getInt(
                sceneOffset + 15 * Integer.BYTES
            )
        );
        assertEquals(
            2,
            first.getInt(
                sceneOffset
                    + NativeTerrainGpuScene.ENTRY_BYTES
                    + 15 * Integer.BYTES
            )
        );
        assertEquals(
            List.of(1, 2),
            fixture.scene.surfaceRegistrySnapshot()
                .stream()
                .map(NativeTerrainSurfaceIdRegistry.Entry::id)
                .toList()
        );

        NativeTerrainGpuScene.Mutation remove =
            fixture.scene.prepareRemoval(fixture.geometry);
        apply(fixture, remove);
        assertEquals(0, fixture.scene.snapshot().activeEntries());
        assertEquals(0, fixture.scene.snapshot().highWaterEntries());
        finish(fixture);
    }

    @Test
    void fullMaterialAndShaderContractsPreventSegmentCoalescing() {
        Entry base = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        MaterialBinding material = base.material().orElseThrow();
        ShaderContract shader = base.shader().orElseThrow();
        Entry texture = surfaceVariant(
            base,
            5_001L,
            copyMaterial(
                material,
                NativeTerrainCompilerTestFixtures.id(6_001L),
                material.layerId(),
                material.animationTableId(),
                material.pbrContractId()
            ),
            shader
        );
        Entry layer = surfaceVariant(
            base,
            5_002L,
            copyMaterial(
                material,
                material.textureId(),
                NativeTerrainCompilerTestFixtures.id(6_002L),
                material.animationTableId(),
                material.pbrContractId()
            ),
            shader
        );
        Entry animation = surfaceVariant(
            base,
            5_003L,
            copyMaterial(
                material,
                material.textureId(),
                material.layerId(),
                NativeTerrainCompilerTestFixtures.id(6_003L),
                material.pbrContractId()
            ),
            shader
        );
        Entry pbr = surfaceVariant(
            base,
            5_004L,
            copyMaterial(
                material,
                material.textureId(),
                material.layerId(),
                material.animationTableId(),
                NativeTerrainCompilerTestFixtures.id(6_004L)
            ),
            shader
        );
        Entry motion = surfaceVariant(
            base,
            5_005L,
            material,
            new ShaderContract(
                shader.abiDigest(),
                shader.outputMask(),
                MotionModel.EXPLICIT_PREVIOUS_TRANSFORM
            )
        );
        Fixture fixture = fixture(
            16,
            base,
            texture,
            layer,
            animation,
            pbr,
            motion
        );

        NativeTerrainGpuScene.Mutation mutation =
            fixture.scene.preparePublication(fixture.geometry);
        assertEquals(6, mutation.entryCount());
        apply(fixture, mutation);
        assertEquals(6, fixture.scene.snapshot().activeEntries());
        List<NativeTerrainSurfaceIdRegistry.Entry> surfaces =
            fixture.scene.surfaceRegistrySnapshot();
        assertEquals(6, surfaces.size());
        for (int index = 0; index < surfaces.size(); index++) {
            assertEquals(index + 1, surfaces.get(index).id());
        }

        BufferBinding sceneBinding =
            fixture.owner.requireBinding(
                fixture.resources.require(BufferKind.STORAGE_SCENE)
            );
        FakeBuffer sceneBytes = (FakeBuffer)sceneBinding.buffer();
        ByteBuffer bytes = ByteBuffer.wrap(sceneBytes.bytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        int sceneOffset = Math.toIntExact(sceneBinding.offset());
        for (int index = 0; index < surfaces.size(); index++) {
            assertEquals(
                index + 1,
                bytes.getInt(
                    sceneOffset
                        + index * NativeTerrainGpuScene.ENTRY_BYTES
                        + 15 * Integer.BYTES
                )
            );
        }
        finish(fixture);
    }

    @Test
    void overflowFailsClosedWithoutPublishingPartialContent() {
        Fixture fixture = fixture(1);
        assertThrows(
            IllegalStateException.class,
            () -> fixture.scene.preparePublication(fixture.geometry)
        );
        assertEquals(0, fixture.scene.snapshot().activeEntries());
        assertEquals(0, fixture.scene.snapshot().pendingMutations());
        finish(fixture);
    }

    @Test
    void failedDirtyUploadRollbackReturnsReservedStableSlots() {
        Fixture fixture = fixture(16);
        NativeTerrainGpuScene.Mutation mutation =
            fixture.scene.preparePublication(fixture.geometry);
        fixture.scene.rollback(mutation);
        assertEquals(0, fixture.scene.snapshot().activeEntries());
        assertEquals(
            16,
            fixture.scene.snapshot().freeEntries()
        );
        assertEquals(0, fixture.scene.snapshot().pendingMutations());
        finish(fixture);
    }

    @Test
    void rejectsConcurrentDirtyMutationUntilFirstIsTerminal() {
        Fixture fixture = fixture(16);
        NativeTerrainGpuScene.Mutation mutation =
            fixture.scene.preparePublication(fixture.geometry);
        assertThrows(
            IllegalStateException.class,
            () -> fixture.scene.preparePublication(fixture.geometry)
        );
        fixture.scene.rollback(mutation);
        assertEquals(0, fixture.scene.snapshot().pendingMutations());
        finish(fixture);
    }

    private static void apply(
        Fixture fixture,
        NativeTerrainGpuScene.Mutation mutation
    ) {
        var update = fixture.owner.tryUpdateResources(
            fixture.scene.writes(mutation),
            1L
        );
        assertTrue(update.started());
        assertTrue(
            fixture.owner.pollResourceUpdate(
                update.ticket(),
                1L
            ).successful()
        );
        fixture.scene.recordDirtyUpload(mutation);
        fixture.scene.commit(mutation);
    }

    private static Fixture fixture(int maximumEntries) {
        return fixture(
            maximumEntries,
            NativeTerrainCompilerTestFixtures.entry(Category.SOLID),
            NativeTerrainCompilerTestFixtures.entry(Category.CUTOUT)
        );
    }

    private static Fixture fixture(
        int maximumEntries,
        Entry... sceneEntries
    ) {
        MemoryBudgetManager budgets =
            new MemoryBudgetManager(
                MemoryBudgetSettings.defaults()
            );
        FakeDevice device = new FakeDevice();
        NativeTerrainGeometryOwner owner =
            new NativeTerrainGeometryOwner(
                1L,
                budgets,
                device,
                new NativeTerrainGeometryOwner.PagePolicy(
                    128L,
                    1L * 1024L * 1024L,
                    1L * 1024L * 1024L,
                    32L
                ),
                8L * 1024L * 1024L,
                1L * 1024L * 1024L
            );
        var census =
            NativeTerrainCompilerTestFixtures.census(sceneEntries);
        Primitive[] primitives =
            new Primitive[sceneEntries.length];
        for (int index = 0; index < sceneEntries.length; index++) {
            primitives[index] =
                NativeTerrainCompilerTestFixtures.quad(
                    index + 1L,
                    sceneEntries[index],
                    index * 2.0F
                );
        }
        CompiledPayloadBatch batch =
            NativeTerrainCompilerTestFixtures.compiler()
                .compile(
                    NativeTerrainCompilerTestFixtures.snapshot(
                        census,
                        primitives
                    ),
                    census,
                    BlockFrameSectionCompiler
                        .CancellationSignal.NEVER
                )
                .batch()
                .orElseThrow();
        NativeTerrainSectionLifecycle lifecycle =
            new NativeTerrainSectionLifecycle(
                NativeTerrainCompilerTestFixtures.section(),
                NativeTerrainCompilerTestFixtures.generations()
            );
        var compilation = lifecycle.beginCompilation();
        lifecycle.completeCompilation(compilation);
        var upload = owner.tryUpload(
            batch,
            lifecycle,
            NativeTerrainCompilerTestFixtures.generations()
        );
        Publication geometry = owner.pollUpload(
            upload.ticket(),
            NativeTerrainCompilerTestFixtures.generations()
        ).publication();

        NativeTerrainSharedQuadIndexBuffer indices =
            new NativeTerrainSharedQuadIndexBuffer(
                NativeTerrainSharedQuadIndexBuffer
                    .MAXIMUM_UINT16_QUADS,
                0
            );
        NativeTerrainGpuScene scene = new NativeTerrainGpuScene(
            1L,
            new NativeTerrainGpuScene.Configuration(
                maximumEntries,
                4,
                1L * 1024L * 1024L
            ),
            indices,
            budgets
        );
        var start = owner.tryCreateResources(
            scene.resourceRequests(),
            1L
        );
        assertTrue(start.started());
        var resources = owner.pollResources(
            start.ticket(),
            1L
        ).publication();
        scene.connectResources(resources);
        return new Fixture(
            budgets,
            owner,
            lifecycle,
            geometry,
            resources,
            scene
        );
    }

    private static Entry surfaceVariant(
        Entry source,
        long identity,
        MaterialBinding material,
        ShaderContract shader
    ) {
        return Entry.supported(
            NativeTerrainCompilerTestFixtures.id(identity),
            source.blockStateOrModelId(),
            source.renderTypeId(),
            source.category(),
            source.provenance(),
            source.vertexLayout().orElseThrow(),
            source.indexType().orElseThrow(),
            material,
            shader,
            source.attributes(),
            source.customRenderType(),
            source.requiredForActiveProfile(),
            source.submissionCompatible(),
            NativeTerrainCompilerTestFixtures.digest(identity)
        );
    }

    private static MaterialBinding copyMaterial(
        MaterialBinding source,
        StableId texture,
        StableId layer,
        StableId animation,
        StableId pbr
    ) {
        return new MaterialBinding(
            source.registryGeneration(),
            source.materialFamilyId(),
            texture,
            source.samplerId(),
            layer,
            animation,
            pbr,
            source.alphaMode(),
            source.alphaCutoffBits()
        );
    }

    private static void finish(Fixture fixture) {
        if (fixture.scene.snapshot().activeEntries() != 0) {
            NativeTerrainGpuScene.Mutation remove =
                fixture.scene.prepareRemoval(fixture.geometry);
            apply(fixture, remove);
        }
        fixture.scene.close();
        assertTrue(
            fixture.owner.pollResourceRetirement(
                fixture.owner.beginResourceRetirement(
                    fixture.resources,
                    new ImmediateCompletion()
                )
            )
        );
        var cleanup = fixture.lifecycle.shutdown(0L);
        assertTrue(
            fixture.owner.pollRetirement(
                fixture.owner.beginRetirement(
                    fixture.geometry,
                    cleanup,
                    new ImmediateCompletion(),
                    0L
                )
            )
        );
        fixture.lifecycle.close();
        assertTrue(fixture.owner.closeAndReport());
        fixture.budgets.completeGpuRetirements();
        assertTrue(fixture.budgets.closeAndReport());
    }

    private record Fixture(
        MemoryBudgetManager budgets,
        NativeTerrainGeometryOwner owner,
        NativeTerrainSectionLifecycle lifecycle,
        Publication geometry,
        NativeTerrainGeometryOwner.ResourcePublication resources,
        NativeTerrainGpuScene scene
    ) {
    }

    private static class FakeBuffer implements OwnedBuffer {
        final byte[] bytes;
        private boolean closed;

        private FakeBuffer(long bytes) {
            this.bytes = new byte[Math.toIntExact(bytes)];
        }

        @Override
        public long size() {
            return this.bytes.length;
        }

        @Override
        public void close() {
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
            long offset,
            BlockFrameSectionCompiler.ChannelPayload payload
        ) {
            ByteBuffer destination = ByteBuffer.wrap(this.bytes);
            destination.position(Math.toIntExact(offset));
            destination.limit(
                Math.toIntExact(offset + payload.byteLength())
            );
            payload.copyBytesTo(destination.slice());
        }

        @Override
        public void write(
            long offset,
            long length,
            NativeTerrainGeometryOwner.BufferWriter writer
        ) {
            ByteBuffer destination = ByteBuffer.wrap(this.bytes);
            destination.position(Math.toIntExact(offset));
            destination.limit(Math.toIntExact(offset + length));
            ByteBuffer slice = destination.slice();
            writer.write(slice);
            if (slice.hasRemaining()) {
                throw new IllegalStateException(
                    "test writer left bytes unwritten"
                );
            }
        }
    }

    private static final class ImmediateCompletion
        implements Completion {
        private boolean closed;

        @Override
        public boolean completed() {
            return true;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private static final class FakeDevice implements DeviceAccess {
        private final List<FakeBuffer> buffers = new ArrayList<>();

        @Override
        public OwnedBuffer createDeviceBuffer(
            BufferKind kind,
            long bytes
        ) {
            FakeBuffer buffer = new FakeBuffer(bytes);
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
            return new RecordResult(
                new ImmediateCompletion(),
                null
            );
        }
    }
}
