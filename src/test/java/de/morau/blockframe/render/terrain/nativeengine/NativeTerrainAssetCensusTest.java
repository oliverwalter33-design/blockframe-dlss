package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.ActivationAttestation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.BlockerReason;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Provenance;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeTerrainAssetCensusTest {
    @Test
    void resultIsImmutableAndDigestCanonicalizesOrderButBindsGeneration() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        Entry cutout = NativeTerrainCompilerTestFixtures.entry(
            Category.CUTOUT
        );
        List<Entry> mutable = new ArrayList<>(List.of(solid, cutout));
        NativeTerrainAssetCensus.Result first =
            NativeTerrainAssetCensus.capture(4L, true, mutable);
        mutable.clear();
        NativeTerrainAssetCensus.Result identical =
            NativeTerrainAssetCensus.capture(
                4L,
                true,
                List.of(solid, cutout)
            );
        NativeTerrainAssetCensus.Result reordered =
            NativeTerrainAssetCensus.capture(
                4L,
                true,
                List.of(cutout, solid)
            );
        NativeTerrainAssetCensus.Result nextGeneration =
            NativeTerrainAssetCensus.capture(
                5L,
                true,
                List.of(solid, cutout)
            );

        assertEquals(2, first.entries().size());
        assertEquals(first.digest(), identical.digest());
        assertEquals(first.digest(), reordered.digest());
        assertNotEquals(first.digest(), nextGeneration.digest());
        assertThrows(
            UnsupportedOperationException.class,
            () -> first.entries().clear()
        );
    }

    @Test
    void completeSolidCutoutProfileCanAttestOnlyThoseNativeLanes() {
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(
                NativeTerrainCompilerTestFixtures.entry(
                    Category.SOLID
                ),
                NativeTerrainCompilerTestFixtures.entry(
                    Category.CUTOUT
                )
            );

        ActivationAttestation attestation = census.attest(
            EnumSet.of(Category.SOLID, Category.CUTOUT)
        );

        assertTrue(attestation.nativeBackendEligible());
        assertTrue(attestation.blockers().isEmpty());
        assertEquals(census.digest(), attestation.censusDigest());
        assertEquals(2, attestation.requiredAssetCount());
    }

    @Test
    void everyRequiredDeferredLaneFailsTheWholeBackendClosed() {
        for (
            Category category
                : List.of(
                    Category.TRANSLUCENT,
                    Category.FLUID,
                    Category.MOD_EXTRA
                )
        ) {
            NativeTerrainAssetCensus.Result census =
                NativeTerrainCompilerTestFixtures.census(
                    NativeTerrainCompilerTestFixtures.entry(category)
                );

            ActivationAttestation attestation = census.attest(
                EnumSet.of(Category.SOLID, Category.CUTOUT)
            );

            assertFalse(
                attestation.nativeBackendEligible(),
                category.name()
            );
            assertEquals(
                BlockerReason.SUBMISSION_UNSUPPORTED,
                attestation.blockers().getFirst().reason(),
                category.name()
            );
        }
    }

    @Test
    void unknownCustomAssetAndIncompleteCensusSelectMojang() {
        Entry unsupported =
            NativeTerrainCompilerTestFixtures.unsupportedEntry();
        NativeTerrainAssetCensus.Result census =
            NativeTerrainAssetCensus.capture(
                4L,
                false,
                List.of(unsupported)
            );

        ActivationAttestation attestation = census.attest(
            EnumSet.allOf(Category.class)
        );

        assertFalse(attestation.nativeBackendEligible());
        assertEquals(2, attestation.blockers().size());
        assertEquals(
            BlockerReason.CENSUS_INCOMPLETE,
            attestation.blockers().get(0).reason()
        );
        assertEquals(
            BlockerReason.UNSUPPORTED_ASSET,
            attestation.blockers().get(1).reason()
        );
    }

    @Test
    void typedCustomAndUnknownContractsCannotBeSilentlyPromoted() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        Entry custom = NativeTerrainCompilerTestFixtures.copyEntry(
            solid,
            Provenance.CUSTOM,
            true,
            NativeTerrainCompilerTestFixtures.digest(700L)
        );
        Entry unknown = NativeTerrainCompilerTestFixtures.copyEntry(
            solid,
            Provenance.UNKNOWN,
            false,
            NativeTerrainCompilerTestFixtures.digest(710L)
        );

        ActivationAttestation customAttestation =
            NativeTerrainCompilerTestFixtures.census(custom).attest(
                EnumSet.of(Category.SOLID, Category.CUTOUT)
            );
        ActivationAttestation unknownAttestation =
            NativeTerrainCompilerTestFixtures.census(unknown).attest(
                EnumSet.of(Category.SOLID, Category.CUTOUT)
            );

        assertEquals(
            BlockerReason.CUSTOM_RENDER_TYPE,
            customAttestation.blockers().getFirst().reason()
        );
        assertEquals(
            BlockerReason.UNKNOWN_PROVENANCE,
            unknownAttestation.blockers().getFirst().reason()
        );
    }

    @Test
    void duplicateAssetIdentityCannotProduceAnAmbiguousCensus() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> NativeTerrainAssetCensus.capture(
                4L,
                true,
                List.of(solid, solid)
            )
        );
    }
}
