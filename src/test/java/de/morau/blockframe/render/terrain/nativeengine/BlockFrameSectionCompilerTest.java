package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.BatchState;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.ChannelPayload;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompileResult;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.FailureReason;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.PublicationState;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.PublishedPayload;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Layer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BlockFrameSectionCompilerTest {
    @Test
    void solidAndCutoutAreEncodedIntoSeparatePermanentAbiChannels() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        Entry cutout = NativeTerrainCompilerTestFixtures.entry(
            Category.CUTOUT
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid, cutout);
        NativeTerrainSectionSnapshot snapshot =
            NativeTerrainCompilerTestFixtures.snapshot(
                census,
                NativeTerrainCompilerTestFixtures.quad(
                    1L,
                    solid,
                    0.0F
                ),
                NativeTerrainCompilerTestFixtures.quad(
                    2L,
                    cutout,
                    2.0F
                )
            );

        CompileResult result =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );

        assertTrue(result.successful());
        CompiledPayloadBatch batch = result.batch().orElseThrow();
        assertTrue(batch.fullySubmittable());
        assertEquals(6, batch.channels().size());

        ChannelPayload solidPayload = batch.channel(Category.SOLID);
        ChannelPayload cutoutPayload = batch.channel(Category.CUTOUT);
        assertEquals(1, solidPayload.primitiveCount());
        assertEquals(1, cutoutPayload.primitiveCount());
        assertEquals(128, solidPayload.byteLength());
        assertEquals(128, cutoutPayload.byteLength());
        assertEquals(
            Layer.SOLID,
            solidPayload.descriptors().getFirst().layer()
        );
        assertEquals(
            Layer.CUTOUT,
            cutoutPayload.descriptors().getFirst().layer()
        );
        assertTrue(
            solidPayload.descriptors().getFirst()
                .structurallyCompatibleWithFirstMilestone()
        );
        assertEquals(
            solid.blockStateOrModelId(),
            solidPayload.manifests().getFirst()
                .blockStateOrModelId()
        );
        assertEquals(
            solid.renderTypeId(),
            solidPayload.manifests().getFirst().renderTypeId()
        );

        byte[] firstPosition = new byte[] {
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        };
        byte[] actual = solidPayload.bytesCopy();
        assertArrayEquals(
            firstPosition,
            java.util.Arrays.copyOf(actual, 12)
        );
        batch.close();
    }

    @Test
    void deferredCategoriesStaySeparatedAndCannotPublish() {
        Entry translucent = NativeTerrainCompilerTestFixtures.entry(
            Category.TRANSLUCENT
        );
        Entry fluid = NativeTerrainCompilerTestFixtures.entry(
            Category.FLUID
        );
        Entry extra = NativeTerrainCompilerTestFixtures.entry(
            Category.MOD_EXTRA
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(
                translucent,
                fluid,
                extra
            );
        NativeTerrainSectionSnapshot snapshot =
            NativeTerrainCompilerTestFixtures.snapshot(
                census,
                NativeTerrainCompilerTestFixtures.quad(
                    1L,
                    translucent,
                    0.0F
                ),
                NativeTerrainCompilerTestFixtures.quad(
                    2L,
                    fluid,
                    2.0F
                ),
                NativeTerrainCompilerTestFixtures.quad(
                    3L,
                    extra,
                    4.0F
                )
            );

        CompiledPayloadBatch batch =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            ).batch().orElseThrow();

        for (
            Category category
                : List.of(
                    Category.TRANSLUCENT,
                    Category.FLUID,
                    Category.MOD_EXTRA
                )
        ) {
            ChannelPayload channel = batch.channel(category);
            assertEquals(1, channel.primitiveCount());
            assertFalse(channel.submissionCapable());
            assertEquals(0, channel.byteLength());
            assertTrue(channel.descriptors().isEmpty());
        }
        assertFalse(batch.fullySubmittable());
        assertThrows(IllegalStateException.class, batch::publish);
        batch.close();
    }

    @Test
    void unsupportedAssetFailsTheWholeCompileWithoutAPartialBatch() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        Entry unsupported =
            NativeTerrainCompilerTestFixtures.unsupportedEntry();
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(
                solid,
                unsupported
            );
        NativeTerrainSectionSnapshot snapshot =
            NativeTerrainCompilerTestFixtures.snapshot(
                census,
                NativeTerrainCompilerTestFixtures.quad(
                    1L,
                    solid,
                    0.0F
                ),
                NativeTerrainCompilerTestFixtures.quad(
                    2L,
                    unsupported,
                    2.0F
                )
            );

        CompileResult result =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                snapshot,
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );

        assertFalse(result.successful());
        assertTrue(result.batch().isEmpty());
        assertEquals(
            FailureReason.UNSUPPORTED_ASSET,
            result.failureReason().orElseThrow()
        );
    }

    @Test
    void invalidLaterPrimitiveCannotPublishEarlierCompiledGeometry() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        Primitive first =
            NativeTerrainCompilerTestFixtures.quad(1L, solid, 0.0F);
        Primitive invalid =
            NativeTerrainCompilerTestFixtures.triangle(2L, solid);

        CompileResult result =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    first,
                    invalid
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );

        assertFalse(result.successful());
        assertTrue(result.batch().isEmpty());
        assertEquals(
            FailureReason.INVALID_PRIMITIVE,
            result.failureReason().orElseThrow()
        );
    }

    @Test
    void cancellationDuringACompilePublishesNothing() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        AtomicInteger calls = new AtomicInteger();

        CompileResult result =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        1L,
                        solid,
                        0.0F
                    ),
                    NativeTerrainCompilerTestFixtures.quad(
                        2L,
                        solid,
                        2.0F
                    )
                ),
                census,
                () -> calls.incrementAndGet() >= 3
            );

        assertFalse(result.successful());
        assertEquals(
            FailureReason.CANCELLED,
            result.failureReason().orElseThrow()
        );
        assertTrue(result.batch().isEmpty());
    }

    @Test
    void staleGenerationDigestAndAssetContractFailClosed() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        NativeTerrainSectionSnapshot valid =
            NativeTerrainCompilerTestFixtures.snapshot(
                census,
                NativeTerrainCompilerTestFixtures.quad(
                    1L,
                    solid,
                    0.0F
                )
            );
        NativeTerrainAssetCensus.Result changedCensus =
            NativeTerrainAssetCensus.capture(
                NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION,
                false,
                List.of(solid)
            );

        CompileResult staleDigest =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                valid,
                changedCensus,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );

        assertEquals(
            FailureReason.CENSUS_MISMATCH,
            staleDigest.failureReason().orElseThrow()
        );

        NativeTerrainAssetCensus.Result nextGeneration =
            NativeTerrainAssetCensus.capture(
                NativeTerrainCompilerTestFixtures.RESOURCE_GENERATION
                    + 1L,
                true,
                List.of(solid)
            );
        NativeTerrainSectionSnapshot staleGeneration =
            new NativeTerrainSectionSnapshot(
                NativeTerrainCompilerTestFixtures.generations(),
                NativeTerrainCompilerTestFixtures.section(),
                nextGeneration.digest(),
                valid.primitives()
            );
        CompileResult generationFailure =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                staleGeneration,
                nextGeneration,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );
        assertEquals(
            FailureReason.GENERATION_MISMATCH,
            generationFailure.failureReason().orElseThrow()
        );

        Entry changedSolid =
            NativeTerrainCompilerTestFixtures.copyEntry(
                solid,
                solid.provenance(),
                false,
                NativeTerrainCompilerTestFixtures.digest(8_000L)
            );
        NativeTerrainAssetCensus.Result changedContractCensus =
            NativeTerrainCompilerTestFixtures.census(changedSolid);
        NativeTerrainSectionSnapshot staleContract =
            new NativeTerrainSectionSnapshot(
                new GenerationStamp(
                    1L,
                    2L,
                    3L,
                    NativeTerrainCompilerTestFixtures
                        .RESOURCE_GENERATION,
                    5L,
                    6L
                ),
                NativeTerrainCompilerTestFixtures.section(),
                changedContractCensus.digest(),
                valid.primitives()
            );
        CompileResult contractFailure =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                staleContract,
                changedContractCensus,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );
        assertEquals(
            FailureReason.STALE_ASSET_CONTRACT,
            contractFailure.failureReason().orElseThrow()
        );
    }

    @Test
    void publicationTransfersAllChannelsAndRetirementClosesBytes() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        CompiledPayloadBatch batch =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        1L,
                        solid,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            ).batch().orElseThrow();
        ChannelPayload channel = batch.channel(Category.SOLID);

        PublishedPayload publication = batch.publish();

        assertEquals(BatchState.PUBLISHED, batch.state());
        assertEquals(PublicationState.ACTIVE, publication.state());
        assertEquals(128, publication.channel(Category.SOLID).byteLength());
        assertThrows(
            IllegalStateException.class,
            () -> batch.channel(Category.SOLID)
        );
        assertThrows(IllegalStateException.class, batch::close);

        publication.retire();
        assertEquals(PublicationState.RETIRED, publication.state());
        assertThrows(IllegalStateException.class, channel::bytesCopy);
        assertThrows(IllegalStateException.class, publication::retire);
    }

    @Test
    void unpublishedCloseIsIdempotentAndPreventsLatePublication() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        CompiledPayloadBatch batch =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                NativeTerrainCompilerTestFixtures.snapshot(
                    census,
                    NativeTerrainCompilerTestFixtures.quad(
                        1L,
                        solid,
                        0.0F
                    )
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            ).batch().orElseThrow();

        batch.close();
        batch.close();

        assertEquals(BatchState.CLOSED, batch.state());
        assertThrows(IllegalStateException.class, batch::publish);
    }

    @Test
    void highVertexCountSplitsIntoUint16SafeDrawRangesWithoutWrap() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        int quadCount = 16_385;
        List<Primitive> primitives = new ArrayList<>(quadCount);
        for (int index = 0; index < quadCount; index++) {
            primitives.add(
                NativeTerrainCompilerTestFixtures.quad(
                    index + 1L,
                    solid,
                    index * 2.0F
                )
            );
        }

        CompileResult result =
            NativeTerrainCompilerTestFixtures.compiler().compile(
                new NativeTerrainSectionSnapshot(
                    NativeTerrainCompilerTestFixtures.generations(),
                    NativeTerrainCompilerTestFixtures.section(),
                    census.digest(),
                    primitives
                ),
                census,
                BlockFrameSectionCompiler.CancellationSignal.NEVER
            );

        assertTrue(result.successful(), result.detail());
        ChannelPayload channel = result.batch().orElseThrow()
            .channel(Category.SOLID);
        assertEquals(quadCount, channel.descriptors().size());
        assertEquals(quadCount * 128, channel.byteLength());
        assertTrue(
            channel.descriptors().stream().allMatch(descriptor ->
                descriptor.vertexCount() == 4
                    && descriptor.indexLayout().type()
                        == TerrainMeshProducerABI.IndexType.UINT16
                    && descriptor.indexLayout().indexCount() == 6
                    && descriptor.indexLayout().baseVertex() == 0
            )
        );
        /*
         * V1 intentionally splits at each independently proven quad. This is
         * conservative, but it guarantees no UINT16 base/index can wrap.
         * Renderer C may merge adjacent descriptors only with the same proof.
         */
        result.batch().orElseThrow().close();
    }
}
