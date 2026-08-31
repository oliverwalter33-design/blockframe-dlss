package de.morau.blockframe.render.terrain.nativeengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Category;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainAssetCensus.Entry;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Primitive;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionSnapshot.Vertex;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.Bounds;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeTerrainSectionSnapshotTest {
    @Test
    void snapshotAndPrimitiveDeepCopyTheirCollections() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        List<Vertex> vertices = new ArrayList<>(
            NativeTerrainCompilerTestFixtures.quad(1L, solid, 0.0F)
                .vertices()
        );
        Primitive primitive = new Primitive(
            1L,
            solid,
            new Bounds(
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                0.0F
            ),
            NativeTerrainCompilerTestFixtures.digest(30L),
            vertices
        );
        List<Primitive> primitives = new ArrayList<>(
            List.of(primitive)
        );
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);
        NativeTerrainSectionSnapshot snapshot =
            new NativeTerrainSectionSnapshot(
                NativeTerrainCompilerTestFixtures.generations(),
                NativeTerrainCompilerTestFixtures.section(),
                census.digest(),
                primitives
            );

        vertices.clear();
        primitives.clear();

        assertEquals(1, snapshot.primitives().size());
        assertEquals(4, primitive.vertices().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.primitives().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> primitive.vertices().clear()
        );
    }

    @Test
    void duplicatePrimitiveIdentityIsRejectedWithinOneGeneration() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        Primitive first =
            NativeTerrainCompilerTestFixtures.quad(1L, solid, 0.0F);
        Primitive duplicate =
            NativeTerrainCompilerTestFixtures.quad(1L, solid, 2.0F);
        NativeTerrainAssetCensus.Result census =
            NativeTerrainCompilerTestFixtures.census(solid);

        assertThrows(
            IllegalArgumentException.class,
            () -> NativeTerrainCompilerTestFixtures.snapshot(
                census,
                first,
                duplicate
            )
        );
    }

    @Test
    void corruptGeometryCannotEnterAnImmutableSnapshot() {
        Entry solid = NativeTerrainCompilerTestFixtures.entry(
            Category.SOLID
        );
        Vertex outside = new Vertex(
            2.0F,
            0.0F,
            0.0F,
            -1,
            0.0F,
            0.0F,
            0,
            0x007F0000
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new Primitive(
                1L,
                solid,
                new Bounds(
                    0.0F,
                    0.0F,
                    0.0F,
                    1.0F,
                    1.0F,
                    1.0F
                ),
                NativeTerrainCompilerTestFixtures.digest(40L),
                List.of(outside)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Vertex(
                0.0F,
                0.0F,
                0.0F,
                -1,
                0.0F,
                0.0F,
                0,
                0
            )
        );
    }
}
