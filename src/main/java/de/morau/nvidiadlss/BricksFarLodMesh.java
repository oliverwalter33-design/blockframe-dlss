package de.morau.nvidiadlss;

import com.matnx.omni.micro.CompositeBlockEntity;
import com.matnx.omni.micro.MicroComponent;
import com.matnx.omni.micro.MicroMaterial;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * Immutable far mesh for one Bricks composite block.
 *
 * <p>Bricks exposes a cached list of occupied 4x4x4-cell faces. Far geometry
 * greedily joins only cells from the same component, plane, and direction.
 * It reproduces Bricks' dimension-specific texture-atlas region and UV swap.
 * Consequently silhouettes, material/UV boundaries, and the
 * opaque/translucent split remain intact while the face count drops sharply.
 *
 * <p>Faces at a neighboring block boundary are deliberately retained. They
 * remain hidden by depth/back-face culling, while retaining them prevents a
 * stale hole if the neighboring composite changes without changing this
 * block's own cached exposed-face-list identity.
 */
public final class BricksFarLodMesh {
    static final int GRID_SIZE = 4;
    private static final float CELL_SIZE = 1.0F / GRID_SIZE;

    private final List<Group> groups;
    private final int sourceFaceCount;
    private final int mergedQuadCount;

    private BricksFarLodMesh(
        List<Group> groups,
        int sourceFaceCount,
        int mergedQuadCount
    ) {
        this.groups = groups;
        this.sourceFaceCount = sourceFaceCount;
        this.mergedQuadCount = mergedQuadCount;
    }

    public static BricksFarLodMesh build(
        List<CompositeBlockEntity.ComponentFace> exposedFaces
    ) {
        Map<PlaneKey, Integer> cellsByPlane = new LinkedHashMap<>();
        IdentityHashMap<MicroComponent, Integer> componentIds =
            new IdentityHashMap<>();
        for (CompositeBlockEntity.ComponentFace face : exposedFaces) {
            MicroComponent component = face.component();
            MicroMaterial material = component.material();
            Direction direction = face.direction();
            int plane = plane(face, direction);
            int u = u(face, direction);
            int v = v(face, direction);
            int componentId = componentIds.computeIfAbsent(
                component,
                ignored -> componentIds.size()
            );
            TextureRegion region = textureRegion(component, direction);
            PlaneKey key = new PlaneKey(
                componentId,
                component,
                region,
                material.translucent(),
                direction,
                plane
            );
            int bit = 1 << (u + GRID_SIZE * v);
            cellsByPlane.merge(key, bit, (left, right) -> left | right);
        }

        Map<BatchKey, List<Quad>> quadsByBatch = new LinkedHashMap<>();
        int mergedCount = 0;
        for (Map.Entry<PlaneKey, Integer> entry : cellsByPlane.entrySet()) {
            PlaneKey plane = entry.getKey();
            int remaining = entry.getValue();
            while (remaining != 0) {
                int first = Integer.numberOfTrailingZeros(remaining);
                int startU = first % GRID_SIZE;
                int startV = first / GRID_SIZE;

                int width = 1;
                while (
                    startU + width < GRID_SIZE
                        && isSet(remaining, startU + width, startV)
                ) {
                    width++;
                }

                int height = 1;
                while (
                    startV + height < GRID_SIZE
                        && rowIsSet(
                            remaining,
                            startU,
                            startV + height,
                            width
                        )
                ) {
                    height++;
                }

                remaining = clearRectangle(
                    remaining,
                    startU,
                    startV,
                    width,
                    height
                );
                BatchKey batch = new BatchKey(
                    plane.region().texture(),
                    plane.translucent()
                );
                quadsByBatch.computeIfAbsent(
                    batch,
                    ignored -> new ArrayList<>()
                ).add(quad(plane, startU, startV, width, height));
                mergedCount++;
            }
        }

        List<Group> groups = new ArrayList<>(quadsByBatch.size());
        quadsByBatch.forEach((batch, quads) ->
            groups.add(new Group(batch, List.copyOf(quads)))
        );
        return new BricksFarLodMesh(
            List.copyOf(groups),
            exposedFaces.size(),
            mergedCount
        );
    }

    public List<Group> groups() {
        return groups;
    }

    int sourceFaceCount() {
        return sourceFaceCount;
    }

    int mergedQuadCount() {
        return mergedQuadCount;
    }

    private static Quad quad(
        PlaneKey key,
        int startU,
        int startV,
        int width,
        int height
    ) {
        float plane = key.plane() * CELL_SIZE;
        float minU = startU * CELL_SIZE;
        float minV = startV * CELL_SIZE;
        float maxU = (startU + width) * CELL_SIZE;
        float maxV = (startV + height) * CELL_SIZE;
        MicroComponent component = key.component();
        float sourceMinS;
        float sourceMaxS;
        float sourceMinT;
        float sourceMaxT;
        switch (key.direction().getAxis()) {
            case X -> {
                sourceMinS = (float) (startU - component.originZ())
                    / component.sizeZ();
                sourceMaxS = (float) (
                    startU + width - component.originZ()
                ) / component.sizeZ();
                sourceMinT = (float) (startV - component.originY())
                    / component.sizeY();
                sourceMaxT = (float) (
                    startV + height - component.originY()
                ) / component.sizeY();
            }
            case Y -> {
                sourceMinS = (float) (startU - component.originX())
                    / component.sizeX();
                sourceMaxS = (float) (
                    startU + width - component.originX()
                ) / component.sizeX();
                sourceMinT = (float) (startV - component.originZ())
                    / component.sizeZ();
                sourceMaxT = (float) (
                    startV + height - component.originZ()
                ) / component.sizeZ();
            }
            case Z -> {
                sourceMinS = (float) (startU - component.originX())
                    / component.sizeX();
                sourceMaxS = (float) (
                    startU + width - component.originX()
                ) / component.sizeX();
                sourceMinT = (float) (startV - component.originY())
                    / component.sizeY();
                sourceMaxT = (float) (
                    startV + height - component.originY()
                ) / component.sizeY();
            }
            default -> throw new IllegalStateException("Unknown axis");
        }
        TextureRegion region = key.region();
        boolean rotateUv = sourceAxisPixels(component, key.direction())
            < targetAxisPixels(component, key.direction());
        return switch (key.direction().getAxis()) {
            case X -> new Quad(
                key.direction(),
                plane,
                minV,
                minU,
                plane,
                maxV,
                maxU,
                region.minU(),
                region.minV(),
                region.maxU(),
                region.maxV(),
                rotateUv,
                sourceMinS,
                sourceMaxS,
                sourceMinT,
                sourceMaxT
            );
            case Y -> new Quad(
                key.direction(),
                minU,
                plane,
                minV,
                maxU,
                plane,
                maxV,
                region.minU(),
                region.minV(),
                region.maxU(),
                region.maxV(),
                rotateUv,
                sourceMinS,
                sourceMaxS,
                sourceMinT,
                sourceMaxT
            );
            case Z -> new Quad(
                key.direction(),
                minU,
                minV,
                plane,
                maxU,
                maxV,
                plane,
                region.minU(),
                region.minV(),
                region.maxU(),
                region.maxV(),
                rotateUv,
                sourceMinS,
                sourceMaxS,
                sourceMinT,
                sourceMaxT
            );
        };
    }

    private static TextureRegion textureRegion(
        MicroComponent component,
        Direction direction
    ) {
        int width = sourceAxisPixels(component, direction);
        int height = targetAxisPixels(component, direction);
        int longest = Math.max(width, height);
        int shortest = Math.min(width, height);
        MicroMaterial material = component.material();
        if (longest == 16 && shortest == 16) {
            return new TextureRegion(
                material.fullTexture(),
                0.0F,
                0.0F,
                1.0F,
                1.0F
            );
        }
        if (longest == 16 && shortest == 8) {
            return new TextureRegion(
                material.partsTexture(),
                0.0F,
                0.0F,
                1.0F,
                0.5F
            );
        }
        if (longest == 8 && shortest == 8) {
            return new TextureRegion(
                material.partsTexture(),
                0.0F,
                0.5F,
                0.5F,
                1.0F
            );
        }
        if (longest == 8 && shortest == 4) {
            return new TextureRegion(
                material.partsTexture(),
                0.5F,
                0.5F,
                1.0F,
                0.75F
            );
        }
        if (longest == 4 && shortest == 4) {
            return new TextureRegion(
                material.partsTexture(),
                0.5F,
                0.75F,
                0.75F,
                1.0F
            );
        }
        if (longest == 16 && shortest == 4) {
            return new TextureRegion(
                material.rodTexture(),
                0.0F,
                0.0F,
                1.0F,
                0.25F
            );
        }
        throw new IllegalArgumentException(
            "Unsupported Bricks component face dimensions "
                + width + "x" + height
        );
    }

    private static int sourceAxisPixels(
        MicroComponent component,
        Direction direction
    ) {
        return switch (direction.getAxis()) {
            case X -> component.sizeZ() * GRID_SIZE;
            case Y, Z -> component.sizeX() * GRID_SIZE;
        };
    }

    private static int targetAxisPixels(
        MicroComponent component,
        Direction direction
    ) {
        return switch (direction.getAxis()) {
            case X, Z -> component.sizeY() * GRID_SIZE;
            case Y -> component.sizeZ() * GRID_SIZE;
        };
    }

    private static int plane(
        CompositeBlockEntity.ComponentFace face,
        Direction direction
    ) {
        int cell = switch (direction.getAxis()) {
            case X -> face.cellX();
            case Y -> face.cellY();
            case Z -> face.cellZ();
        };
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
            ? cell + 1
            : cell;
    }

    private static int u(
        CompositeBlockEntity.ComponentFace face,
        Direction direction
    ) {
        return switch (direction.getAxis()) {
            case X -> face.cellZ();
            case Y, Z -> face.cellX();
        };
    }

    private static int v(
        CompositeBlockEntity.ComponentFace face,
        Direction direction
    ) {
        return switch (direction.getAxis()) {
            case X, Z -> face.cellY();
            case Y -> face.cellZ();
        };
    }

    private static boolean isSet(int bits, int u, int v) {
        return (bits & (1 << (u + GRID_SIZE * v))) != 0;
    }

    private static boolean rowIsSet(
        int bits,
        int startU,
        int v,
        int width
    ) {
        for (int u = startU; u < startU + width; u++) {
            if (!isSet(bits, u, v)) {
                return false;
            }
        }
        return true;
    }

    private static int clearRectangle(
        int bits,
        int startU,
        int startV,
        int width,
        int height
    ) {
        int result = bits;
        for (int v = startV; v < startV + height; v++) {
            for (int u = startU; u < startU + width; u++) {
                result &= ~(1 << (u + GRID_SIZE * v));
            }
        }
        return result;
    }

    public record BatchKey(Identifier texture, boolean translucent) {
    }

    public record Group(BatchKey batch, List<Quad> quads) {
    }

    public record Quad(
        Direction direction,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ,
        float minU,
        float minV,
        float maxU,
        float maxV,
        boolean rotateUv,
        float sourceMinS,
        float sourceMaxS,
        float sourceMinT,
        float sourceMaxT
    ) {
    }

    private record PlaneKey(
        int componentId,
        MicroComponent component,
        TextureRegion region,
        boolean translucent,
        Direction direction,
        int plane
    ) {
    }

    private record TextureRegion(
        Identifier texture,
        float minU,
        float minV,
        float maxU,
        float maxV
    ) {
    }
}
