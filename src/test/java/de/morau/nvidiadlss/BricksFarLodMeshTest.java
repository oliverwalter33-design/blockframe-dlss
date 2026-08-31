package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.matnx.omni.micro.CompositeBlockEntity;
import com.matnx.omni.micro.MicroComponent;
import com.matnx.omni.micro.MicroMaterial;
import com.matnx.omni.micro.MicroOrientation;
import com.matnx.omni.micro.MicroShape;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class BricksFarLodMeshTest {
    @Test
    void fullFaceGreedilyMergesWithoutChangingFullTextureUvContract() {
        MicroComponent slab = component(material("stone", false), MicroShape.SLAB);
        List<CompositeBlockEntity.ComponentFace> faces = rectangle(
            slab,
            Direction.SOUTH,
            0,
            0,
            4,
            4,
            1
        );

        BricksFarLodMesh mesh = BricksFarLodMesh.build(faces);
        BricksFarLodMesh.Quad quad = onlyQuad(mesh);

        assertEquals(16, mesh.sourceFaceCount());
        assertEquals(1, mesh.mergedQuadCount());
        assertEquals(
            slab.material().fullTexture(),
            onlyGroup(mesh).batch().texture()
        );
        assertEquals(0.0F, quad.minU());
        assertEquals(0.0F, quad.minV());
        assertEquals(1.0F, quad.maxU());
        assertEquals(1.0F, quad.maxV());
        assertFalse(quad.rotateUv());
        assertEquals(0.0F, quad.sourceMinS());
        assertEquals(1.0F, quad.sourceMaxS());
        assertEquals(0.0F, quad.sourceMinT());
        assertEquals(1.0F, quad.sourceMaxT());
    }

    @Test
    void rodAndRotatedPartsFacesUseTheExactBricksAtlasRegions() {
        MicroComponent rod = component(material("rod", false), MicroShape.ROD);
        BricksFarLodMesh rodMesh = BricksFarLodMesh.build(rectangle(
            rod,
            Direction.UP,
            0,
            0,
            4,
            1,
            0
        ));
        BricksFarLodMesh.Quad rodQuad = onlyQuad(rodMesh);
        assertEquals(
            rod.material().rodTexture(),
            onlyGroup(rodMesh).batch().texture()
        );
        assertEquals(0.0F, rodQuad.minV());
        assertEquals(0.25F, rodQuad.maxV());
        assertFalse(rodQuad.rotateUv());

        MicroComponent slab = component(material("slab", false), MicroShape.SLAB);
        BricksFarLodMesh partsMesh = BricksFarLodMesh.build(rectangle(
            slab,
            Direction.EAST,
            0,
            0,
            2,
            4,
            3
        ));
        BricksFarLodMesh.Quad partsQuad = onlyQuad(partsMesh);
        assertEquals(
            slab.material().partsTexture(),
            onlyGroup(partsMesh).batch().texture()
        );
        assertEquals(0.0F, partsQuad.minU());
        assertEquals(0.0F, partsQuad.minV());
        assertEquals(1.0F, partsQuad.maxU());
        assertEquals(0.5F, partsQuad.maxV());
        assertTrue(partsQuad.rotateUv());
    }

    @Test
    void everyBricksTextureRegionBranchHasExactTextureAndBounds() {
        MicroMaterial material = material("regions", false);
        List<RegionCase> cases = List.of(
            new RegionCase(
                component(material, MicroShape.SLAB),
                Direction.SOUTH,
                material.fullTexture(),
                0.0F,
                0.0F,
                1.0F,
                1.0F
            ),
            new RegionCase(
                component(material, MicroShape.SLAB),
                Direction.EAST,
                material.partsTexture(),
                0.0F,
                0.0F,
                1.0F,
                0.5F
            ),
            new RegionCase(
                component(material, MicroShape.CUBE),
                Direction.UP,
                material.partsTexture(),
                0.0F,
                0.5F,
                0.5F,
                1.0F
            ),
            new RegionCase(
                component(material, MicroShape.SMALL_SLAB),
                Direction.EAST,
                material.partsTexture(),
                0.5F,
                0.5F,
                1.0F,
                0.75F
            ),
            new RegionCase(
                component(material, MicroShape.SMALL_CUBE),
                Direction.UP,
                material.partsTexture(),
                0.5F,
                0.75F,
                0.75F,
                1.0F
            ),
            new RegionCase(
                component(material, MicroShape.ROD),
                Direction.UP,
                material.rodTexture(),
                0.0F,
                0.0F,
                1.0F,
                0.25F
            )
        );

        for (RegionCase expected : cases) {
            int x = expected.direction().getAxis() == Direction.Axis.X
                ? expected.component().sizeX() - 1
                : 0;
            int y = expected.direction().getAxis() == Direction.Axis.Y
                ? expected.component().sizeY() - 1
                : 0;
            int z = expected.direction().getAxis() == Direction.Axis.Z
                ? expected.component().sizeZ() - 1
                : 0;
            BricksFarLodMesh mesh = BricksFarLodMesh.build(List.of(
                new CompositeBlockEntity.ComponentFace(
                    expected.component(),
                    expected.direction(),
                    x,
                    y,
                    z
                )
            ));
            BricksFarLodMesh.Group group = onlyGroup(mesh);
            BricksFarLodMesh.Quad quad = onlyQuad(mesh);

            assertEquals(expected.texture(), group.batch().texture());
            assertEquals(expected.minU(), quad.minU());
            assertEquals(expected.minV(), quad.minV());
            assertEquals(expected.maxU(), quad.maxU());
            assertEquals(expected.maxV(), quad.maxV());
        }
    }

    @Test
    void mergedPartialOriginKeepsRelativeUvRangeAndRotation() {
        MicroComponent shiftedSlab = new MicroComponent(
            material("shifted", false),
            MicroShape.SLAB,
            MicroOrientation.IDENTITY,
            0,
            0,
            1
        );
        BricksFarLodMesh mesh = BricksFarLodMesh.build(rectangle(
            shiftedSlab,
            Direction.EAST,
            2,
            1,
            1,
            2,
            3
        ));
        BricksFarLodMesh.Quad quad = onlyQuad(mesh);

        assertTrue(quad.rotateUv());
        assertEquals(0.5F, quad.sourceMinS());
        assertEquals(1.0F, quad.sourceMaxS());
        assertEquals(0.25F, quad.sourceMinT());
        assertEquals(0.75F, quad.sourceMaxT());
        assertEquals(0.0F, quad.minU());
        assertEquals(0.0F, quad.minV());
        assertEquals(1.0F, quad.maxU());
        assertEquals(0.5F, quad.maxV());
    }

    @Test
    void identicalMaterialCellsFromDifferentComponentsNeverMerge() {
        MicroMaterial shared = material("shared", false);
        MicroComponent left = new MicroComponent(
            shared,
            MicroShape.SMALL_CUBE,
            MicroOrientation.IDENTITY,
            0,
            0,
            0
        );
        MicroComponent right = new MicroComponent(
            shared,
            MicroShape.SMALL_CUBE,
            MicroOrientation.IDENTITY,
            1,
            0,
            0
        );
        List<CompositeBlockEntity.ComponentFace> faces = List.of(
            new CompositeBlockEntity.ComponentFace(
                left,
                Direction.UP,
                0,
                0,
                0
            ),
            new CompositeBlockEntity.ComponentFace(
                right,
                Direction.UP,
                1,
                0,
                0
            )
        );

        BricksFarLodMesh mesh = BricksFarLodMesh.build(faces);

        assertEquals(2, mesh.sourceFaceCount());
        assertEquals(2, mesh.mergedQuadCount());
        assertEquals(1, mesh.groups().size());
        assertEquals(2, onlyGroup(mesh).quads().size());
    }

    @Test
    void greedyMergeDoesNotFillSilhouetteHoles() {
        MicroComponent slab = component(material("hole", false), MicroShape.SLAB);
        List<CompositeBlockEntity.ComponentFace> faces = new ArrayList<>();
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                if (x != 1 || z != 1) {
                    faces.add(new CompositeBlockEntity.ComponentFace(
                        slab,
                        Direction.UP,
                        x,
                        1,
                        z
                    ));
                }
            }
        }

        BricksFarLodMesh mesh = BricksFarLodMesh.build(faces);
        float area = 0.0F;
        for (BricksFarLodMesh.Quad quad : onlyGroup(mesh).quads()) {
            area += (quad.maxX() - quad.minX())
                * (quad.maxZ() - quad.minZ());
        }

        assertEquals(3.0F / 16.0F, area);
    }

    @Test
    void translucencyAndBoundaryFacesRemainExplicit() {
        MicroComponent opaque = component(
            material("mixed", false),
            MicroShape.SMALL_CUBE
        );
        MicroComponent translucent = component(
            material("mixed", true),
            MicroShape.SMALL_CUBE
        );
        BricksFarLodMesh mesh = BricksFarLodMesh.build(List.of(
            new CompositeBlockEntity.ComponentFace(
                opaque,
                Direction.WEST,
                0,
                0,
                0
            ),
            new CompositeBlockEntity.ComponentFace(
                translucent,
                Direction.WEST,
                0,
                0,
                0
            )
        ));

        assertEquals(2, mesh.groups().size());
        assertTrue(mesh.groups().stream().anyMatch(group ->
            group.batch().translucent()
        ));
        assertTrue(mesh.groups().stream().anyMatch(group ->
            !group.batch().translucent()
        ));
        assertEquals(2, mesh.mergedQuadCount());
    }

    private static MicroComponent component(
        MicroMaterial material,
        MicroShape shape
    ) {
        return new MicroComponent(
            material,
            shape,
            MicroOrientation.IDENTITY,
            0,
            0,
            0
        );
    }

    private static MicroMaterial material(String name, boolean translucent) {
        Identifier id = Identifier.fromNamespaceAndPath("test", name);
        return new MicroMaterial(
            name,
            name,
            null,
            false,
            id,
            id,
            null,
            false,
            translucent
        );
    }

    private static List<CompositeBlockEntity.ComponentFace> rectangle(
        MicroComponent component,
        Direction direction,
        int startU,
        int startV,
        int width,
        int height,
        int planeCell
    ) {
        List<CompositeBlockEntity.ComponentFace> faces = new ArrayList<>();
        for (int v = startV; v < startV + height; v++) {
            for (int u = startU; u < startU + width; u++) {
                int x;
                int y;
                int z;
                switch (direction.getAxis()) {
                    case X -> {
                        x = planeCell;
                        y = v;
                        z = u;
                    }
                    case Y -> {
                        x = u;
                        y = planeCell;
                        z = v;
                    }
                    case Z -> {
                        x = u;
                        y = v;
                        z = planeCell;
                    }
                    default -> throw new IllegalStateException("Unknown axis");
                }
                faces.add(new CompositeBlockEntity.ComponentFace(
                    component,
                    direction,
                    x,
                    y,
                    z
                ));
            }
        }
        return faces;
    }

    private static BricksFarLodMesh.Group onlyGroup(BricksFarLodMesh mesh) {
        assertEquals(1, mesh.groups().size());
        return mesh.groups().getFirst();
    }

    private static BricksFarLodMesh.Quad onlyQuad(BricksFarLodMesh mesh) {
        BricksFarLodMesh.Group group = onlyGroup(mesh);
        assertEquals(1, group.quads().size());
        return group.quads().getFirst();
    }

    private record RegionCase(
        MicroComponent component,
        Direction direction,
        Identifier texture,
        float minU,
        float minV,
        float maxU,
        float maxV
    ) {
    }
}
