package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NativeTerrainOwnershipEvidenceTest {
    @AfterEach
    void reset() {
        NativeTerrainOwnershipEvidence.resetForTests();
    }

    @Test
    void requiresEveryNativeOwnerAndNoMojangWork() {
        var token = NativeTerrainOwnershipEvidence.begin(7L);
        NativeTerrainOwnershipEvidence
            .blockFrameSectionCompiled(token);
        NativeTerrainOwnershipEvidence
            .blockFramePayloadPublished(token);
        NativeTerrainOwnershipEvidence
            .blockFrameGpuUploaded(token);
        NativeTerrainOwnershipEvidence
            .blockFrameSceneEntriesPublished(token, 2);
        NativeTerrainOwnershipEvidence
            .blockFrameComputeCullEncoded(token);
        NativeTerrainOwnershipEvidence
            .blockFrameIndirectSubmissionEncoded(token);

        assertFalse(
            NativeTerrainOwnershipEvidence.snapshot()
                .exclusiveFixtureGatePassed()
        );
        assertTrue(
            NativeTerrainOwnershipEvidence.snapshot()
                .blockFramePathObserved()
        );
        NativeTerrainOwnershipEvidence.end(token);
        assertTrue(
            NativeTerrainOwnershipEvidence.snapshot()
                .exclusiveFixtureGatePassed()
        );

        var contaminated = NativeTerrainOwnershipEvidence.begin(8L);
        NativeTerrainOwnershipEvidence.mojangGpuUploaded();
        NativeTerrainOwnershipEvidence.end(contaminated);
        assertFalse(
            NativeTerrainOwnershipEvidence.snapshot()
                .exclusiveFixtureGatePassed()
        );
    }

    @Test
    void ignoresInstrumentationOutsideExclusivePeriod() {
        NativeTerrainOwnershipEvidence.mojangSectionCompiled();
        NativeTerrainOwnershipEvidence.mojangGpuAllocated();

        assertTrue(
            NativeTerrainOwnershipEvidence.snapshot()
                .mojangTerrainWorkAbsent()
        );
    }

    @Test
    void recognizesOnlyActualMojangSolidCutoutHeapNames() {
        assertTrue(
            NativeTerrainOwnershipEvidence
                .isMojangSolidCutoutHeapName("UberBuffer solid 0")
        );
        assertTrue(
            NativeTerrainOwnershipEvidence
                .isMojangSolidCutoutHeapName("UberBuffer cutout 12")
        );
        assertFalse(
            NativeTerrainOwnershipEvidence
                .isMojangSolidCutoutHeapName(
                    "UberBuffer translucent 0"
                )
        );
        assertFalse(
            NativeTerrainOwnershipEvidence
                .isMojangSolidCutoutHeapName("solid 0")
        );
        assertFalse(
            NativeTerrainOwnershipEvidence
                .isMojangSolidCutoutHeapName(null)
        );
    }

    @Test
    void rejectsOverlapAndStaleEnd() {
        var first = NativeTerrainOwnershipEvidence.begin(3L);
        assertThrows(
            IllegalStateException.class,
            () -> NativeTerrainOwnershipEvidence.begin(4L)
        );
        NativeTerrainOwnershipEvidence.end(first);
        var second = NativeTerrainOwnershipEvidence.begin(4L);
        assertThrows(
            IllegalArgumentException.class,
            () -> NativeTerrainOwnershipEvidence.end(first)
        );
        NativeTerrainOwnershipEvidence.end(second);
        assertFalse(
            NativeTerrainOwnershipEvidence.snapshot()
                .exclusivePeriod()
        );
    }

    @Test
    void staleBlockFrameTokenCannotIncrementTheNextGeneration() {
        var first = NativeTerrainOwnershipEvidence.begin(10L);
        NativeTerrainOwnershipEvidence.end(first);
        var second = NativeTerrainOwnershipEvidence.begin(11L);

        NativeTerrainOwnershipEvidence
            .blockFrameSectionCompiled(first);
        assertFalse(
            NativeTerrainOwnershipEvidence.snapshot()
                .blockFramePathObserved()
        );
        assertTrue(
            NativeTerrainOwnershipEvidence.snapshot()
                .blockFrameSectionCompiles() == 0L
        );

        NativeTerrainOwnershipEvidence
            .blockFrameSectionCompiled(second);
        assertTrue(
            NativeTerrainOwnershipEvidence.snapshot()
                .blockFrameSectionCompiles() == 1L
        );
        NativeTerrainOwnershipEvidence.end(second);
    }
}
