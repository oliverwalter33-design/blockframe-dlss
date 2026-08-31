package de.morau.nvidiadlss;

import com.matnx.omni.micro.CompositeBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Client-only cached and frame-batched execution path for Bricks far LOD. */
public final class BricksFarLodRuntime {
    public static final int NEAR_DISTANCE_BLOCKS = 64;
    private static final double NEAR_DISTANCE_SQUARED =
        (double) NEAR_DISTANCE_BLOCKS * NEAR_DISTANCE_BLOCKS;

    private static final Map<CompositeBlockEntity, CacheEntry> MESH_CACHE =
        new WeakHashMap<>();
    private static final IdentityHashMap<
        BlockEntityRenderState,
        BricksFarLodMesh
    > FAR_STATES = new IdentityHashMap<>();
    private static final Map<BricksFarLodMesh.BatchKey, FarBatch> BATCHES =
        new LinkedHashMap<>();
    private static final ThreadLocal<int[]> QUAD_ORDER_SCRATCH =
        ThreadLocal.withInitial(() -> new int[64]);

    private BricksFarLodRuntime() {
    }

    /** Called from the exact dispatcher prepare hook before state extraction. */
    public static void beginExtractionFrame() {
        FAR_STATES.clear();
    }

    /**
     * Extracts only base state and attaches a cached simplified mesh when the
     * exact Bricks composite lies beyond the untouched 64-block near path.
     */
    public static boolean extractFarState(
        BlockEntity blockEntity,
        BlockEntityRenderState state,
        Vec3 cameraPosition,
        ModelFeatureRenderer.CrumblingOverlay breakingOverlay
    ) {
        if (
            !farLodEnabled()
                || !(blockEntity instanceof CompositeBlockEntity composite)
                || isNear(composite.getBlockPos(), cameraPosition)
        ) {
            return false;
        }

        BlockEntityRenderState.extractBase(composite, state, breakingOverlay);
        List<CompositeBlockEntity.ComponentFace> source =
            composite.exposedFaces();
        CacheEntry cached = MESH_CACHE.get(composite);
        if (cached == null || cached.sourceFaces() != source) {
            cached = new CacheEntry(source, BricksFarLodMesh.build(source));
            MESH_CACHE.put(composite, cached);
        }
        FAR_STATES.put(state, cached.mesh());
        return true;
    }

    public static void beginSubmissionFrame() {
        for (FarBatch batch : BATCHES.values()) {
            batch.clear();
        }
    }

    /** Returns true only when this state was extracted by the far path. */
    public static boolean queueFarState(
        BlockEntityRenderState state,
        CameraRenderState camera
    ) {
        // Keep the marker until the next extraction frame. NeoForge or a
        // compatibility pass may replay the same extracted LevelRenderState;
        // a destructive remove would then delegate an intentionally empty
        // far state to Bricks and make it disappear on the replay.
        BricksFarLodMesh mesh = FAR_STATES.get(state);
        if (mesh == null) {
            return false;
        }

        BlockPos position = state.blockPos;
        double offsetX = position.getX() - camera.pos.x();
        double offsetY = position.getY() - camera.pos.y();
        double offsetZ = position.getZ() - camera.pos.z();
        double centerX = offsetX + 0.5D;
        double centerY = offsetY + 0.5D;
        double centerZ = offsetZ + 0.5D;
        double distanceSquared = centerX * centerX
            + centerY * centerY
            + centerZ * centerZ;

        for (BricksFarLodMesh.Group group : mesh.groups()) {
            BATCHES.computeIfAbsent(
                group.batch(),
                key -> new FarBatch(key.translucent())
            ).add(
                group,
                (float) offsetX,
                (float) offsetY,
                (float) offsetZ,
                state.lightCoords,
                distanceSquared
            );
        }
        return true;
    }

    public static void flushFarBatches(
        PoseStack poseStack,
        SubmitNodeCollector collector
    ) {
        for (
            Map.Entry<BricksFarLodMesh.BatchKey, FarBatch> entry
                : BATCHES.entrySet()
        ) {
            BricksFarLodMesh.BatchKey key = entry.getKey();
            FarBatch batch = entry.getValue();
            if (batch.isEmpty()) {
                continue;
            }
            SubmittedFarBatch submitted = batch.immutableSnapshot();
            collector.submitCustomGeometry(
                poseStack,
                key.translucent()
                    ? RenderTypes.entityTranslucent(key.texture())
                    : RenderTypes.entityCutout(key.texture()),
                submitted::render
            );
        }
    }

    static boolean isNear(BlockPos position, Vec3 cameraPosition) {
        double x = position.getX() + 0.5D - cameraPosition.x();
        double y = position.getY() + 0.5D - cameraPosition.y();
        double z = position.getZ() + 0.5D - cameraPosition.z();
        return x * x + y * y + z * z <= NEAR_DISTANCE_SQUARED;
    }

    static boolean farLodEnabled() {
        return BricksCompatibility.configuredViewDistanceBlocks()
            > BricksCompatibility.MIN_VIEW_DISTANCE_BLOCKS;
    }

    static int cachedMeshCountForTests() {
        return MESH_CACHE.size();
    }

    static BricksFarLodMesh cachedMeshForTests(CompositeBlockEntity entity) {
        CacheEntry entry = MESH_CACHE.get(entity);
        return entry == null ? null : entry.mesh();
    }

    static void resetForTests() {
        MESH_CACHE.clear();
        FAR_STATES.clear();
        BATCHES.clear();
    }

    private static void renderQuad(
        PoseStack.Pose pose,
        VertexConsumer consumer,
        BricksFarLodMesh.Quad quad,
        float offsetX,
        float offsetY,
        float offsetZ,
        int light
    ) {
        Direction direction = quad.direction();
        boolean reverse = direction == Direction.EAST
            || direction == Direction.UP
            || direction == Direction.NORTH;
        for (int vertex = 0; vertex < 4; vertex++) {
            int corner = reverse ? (4 - vertex) % 4 : vertex;
            float s = switch (corner) {
                case 1, 2 -> 1.0F;
                default -> 0.0F;
            };
            float t = corner >= 2 ? 1.0F : 0.0F;
            float sourceS = lerp(
                quad.sourceMinS(),
                quad.sourceMaxS(),
                s
            );
            float sourceT = lerp(
                quad.sourceMinT(),
                quad.sourceMaxT(),
                t
            );
            float textureS = quad.rotateUv() ? sourceT : sourceS;
            float textureT = quad.rotateUv() ? sourceS : 1.0F - sourceT;
            float textureU = lerp(quad.minU(), quad.maxU(), textureS);
            float textureV = lerp(quad.minV(), quad.maxV(), textureT);
            float x;
            float y;
            float z;
            switch (direction.getAxis()) {
                case X -> {
                    x = direction == Direction.WEST
                        ? quad.minX()
                        : quad.maxX();
                    y = lerp(quad.minY(), quad.maxY(), t);
                    z = lerp(quad.minZ(), quad.maxZ(), s);
                }
                case Y -> {
                    x = lerp(quad.minX(), quad.maxX(), s);
                    y = direction == Direction.DOWN
                        ? quad.minY()
                        : quad.maxY();
                    z = lerp(quad.minZ(), quad.maxZ(), t);
                }
                case Z -> {
                    x = lerp(quad.minX(), quad.maxX(), s);
                    y = lerp(quad.minY(), quad.maxY(), t);
                    z = direction == Direction.NORTH
                        ? quad.minZ()
                        : quad.maxZ();
                }
                default -> throw new IllegalStateException("Unknown axis");
            }
            consumer.addVertex(
                pose,
                offsetX + x,
                offsetY + y,
                offsetZ + z
            ).setColor(-1)
                .setUv(textureU, textureV)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(
                    pose,
                    direction.getStepX(),
                    direction.getStepY(),
                    direction.getStepZ()
                );
        }
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private record CacheEntry(
        List<CompositeBlockEntity.ComponentFace> sourceFaces,
        BricksFarLodMesh mesh
    ) {
    }

    /** Reusable collection queue. It is never captured by a submitted node. */
    private static final class FarBatch {
        private static final int INITIAL_CAPACITY = 64;
        private final boolean translucent;
        private BricksFarLodMesh.Group[] groups =
            new BricksFarLodMesh.Group[INITIAL_CAPACITY];
        private float[] x = new float[INITIAL_CAPACITY];
        private float[] y = new float[INITIAL_CAPACITY];
        private float[] z = new float[INITIAL_CAPACITY];
        private int[] light = new int[INITIAL_CAPACITY];
        private double[] distanceSquared = new double[INITIAL_CAPACITY];
        private int[] order = new int[INITIAL_CAPACITY];
        private int size;

        private FarBatch(boolean translucent) {
            this.translucent = translucent;
        }

        private void clear() {
            Arrays.fill(groups, 0, size, null);
            size = 0;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void add(
            BricksFarLodMesh.Group group,
            float offsetX,
            float offsetY,
            float offsetZ,
            int packedLight,
            double squaredDistance
        ) {
            ensureCapacity(size + 1);
            groups[size] = group;
            x[size] = offsetX;
            y[size] = offsetY;
            z[size] = offsetZ;
            light[size] = packedLight;
            distanceSquared[size] = squaredDistance;
            size++;
        }

        private SubmittedFarBatch immutableSnapshot() {
            BricksFarLodMesh.Group[] submittedGroups = Arrays.copyOf(
                groups,
                size
            );
            float[] submittedX = Arrays.copyOf(x, size);
            float[] submittedY = Arrays.copyOf(y, size);
            float[] submittedZ = Arrays.copyOf(z, size);
            int[] submittedLight = Arrays.copyOf(light, size);
            double[] submittedDistance = Arrays.copyOf(
                distanceSquared,
                size
            );
            int[] submittedOrder = Arrays.copyOf(order, size);
            for (int index = 0; index < size; index++) {
                submittedOrder[index] = index;
            }
            if (translucent && size > 1) {
                sortPlacements(
                    submittedOrder,
                    submittedDistance,
                    0,
                    size - 1
                );
            }
            return new SubmittedFarBatch(
                translucent,
                submittedGroups,
                submittedX,
                submittedY,
                submittedZ,
                submittedLight,
                submittedOrder
            );
        }

        private static void sortPlacements(
            int[] sortedOrder,
            double[] squaredDistance,
            int low,
            int high
        ) {
            int left = low;
            int right = high;
            double pivot = squaredDistance[
                sortedOrder[(low + high) >>> 1]
            ];
            while (left <= right) {
                while (squaredDistance[sortedOrder[left]] > pivot) {
                    left++;
                }
                while (squaredDistance[sortedOrder[right]] < pivot) {
                    right--;
                }
                if (left <= right) {
                    int swap = sortedOrder[left];
                    sortedOrder[left] = sortedOrder[right];
                    sortedOrder[right] = swap;
                    left++;
                    right--;
                }
            }
            if (low < right) {
                sortPlacements(sortedOrder, squaredDistance, low, right);
            }
            if (left < high) {
                sortPlacements(sortedOrder, squaredDistance, left, high);
            }
        }

        private void ensureCapacity(int required) {
            if (required <= groups.length) {
                return;
            }
            int capacity = Math.max(required, groups.length * 2);
            groups = Arrays.copyOf(groups, capacity);
            x = Arrays.copyOf(x, capacity);
            y = Arrays.copyOf(y, capacity);
            z = Arrays.copyOf(z, capacity);
            light = Arrays.copyOf(light, capacity);
            distanceSquared = Arrays.copyOf(distanceSquared, capacity);
            order = Arrays.copyOf(order, capacity);
        }
    }

    /**
     * Immutable ownership transferred to SubmitNodeCollector. No array or
     * list reachable from this callback is cleared or overwritten after
     * submission, so deferred and replayed passes observe identical data.
     */
    private static final class SubmittedFarBatch {
        private final boolean translucent;
        private final BricksFarLodMesh.Group[] groups;
        private final float[] x;
        private final float[] y;
        private final float[] z;
        private final int[] light;
        private final int[] order;

        private SubmittedFarBatch(
            boolean translucent,
            BricksFarLodMesh.Group[] groups,
            float[] x,
            float[] y,
            float[] z,
            int[] light,
            int[] order
        ) {
            this.translucent = translucent;
            this.groups = groups;
            this.x = x;
            this.y = y;
            this.z = z;
            this.light = light;
            this.order = order;
        }

        private void render(PoseStack.Pose pose, VertexConsumer consumer) {
            for (int cursor = 0; cursor < order.length; cursor++) {
                renderPlacement(order[cursor], pose, consumer);
            }
        }

        private void renderPlacement(
            int placement,
            PoseStack.Pose pose,
            VertexConsumer consumer
        ) {
            List<BricksFarLodMesh.Quad> quads = groups[placement].quads();
            if (translucent && quads.size() > 1) {
                int[] quadOrder = quadOrderScratch(quads.size());
                for (int quad = 0; quad < quads.size(); quad++) {
                    quadOrder[quad] = quad;
                }
                sortQuads(
                    quadOrder,
                    quads,
                    placement,
                    0,
                    quads.size() - 1
                );
                for (int cursor = 0; cursor < quads.size(); cursor++) {
                    emit(quads.get(quadOrder[cursor]), placement, pose, consumer);
                }
                return;
            }
            for (BricksFarLodMesh.Quad quad : quads) {
                emit(quad, placement, pose, consumer);
            }
        }

        private void emit(
            BricksFarLodMesh.Quad quad,
            int placement,
            PoseStack.Pose pose,
            VertexConsumer consumer
        ) {
            renderQuad(
                pose,
                consumer,
                quad,
                x[placement],
                y[placement],
                z[placement],
                light[placement]
            );
        }

        private void sortQuads(
            int[] sortedOrder,
            List<BricksFarLodMesh.Quad> quads,
            int placement,
            int low,
            int high
        ) {
            int left = low;
            int right = high;
            double pivot = quadDistanceSquared(
                quads.get(sortedOrder[(low + high) >>> 1]),
                placement
            );
            while (left <= right) {
                while (
                    quadDistanceSquared(
                        quads.get(sortedOrder[left]),
                        placement
                    ) > pivot
                ) {
                    left++;
                }
                while (
                    quadDistanceSquared(
                        quads.get(sortedOrder[right]),
                        placement
                    ) < pivot
                ) {
                    right--;
                }
                if (left <= right) {
                    int swap = sortedOrder[left];
                    sortedOrder[left] = sortedOrder[right];
                    sortedOrder[right] = swap;
                    left++;
                    right--;
                }
            }
            if (low < right) {
                sortQuads(sortedOrder, quads, placement, low, right);
            }
            if (left < high) {
                sortQuads(sortedOrder, quads, placement, left, high);
            }
        }

        private double quadDistanceSquared(
            BricksFarLodMesh.Quad quad,
            int placement
        ) {
            double centerX = x[placement]
                + (quad.minX() + quad.maxX()) * 0.5D;
            double centerY = y[placement]
                + (quad.minY() + quad.maxY()) * 0.5D;
            double centerZ = z[placement]
                + (quad.minZ() + quad.maxZ()) * 0.5D;
            return centerX * centerX + centerY * centerY + centerZ * centerZ;
        }

        private static int[] quadOrderScratch(int required) {
            int[] scratch = QUAD_ORDER_SCRATCH.get();
            if (required <= scratch.length) {
                return scratch;
            }
            int[] expanded = Arrays.copyOf(
                scratch,
                Math.max(required, scratch.length * 2)
            );
            QUAD_ORDER_SCRATCH.set(expanded);
            return expanded;
        }
    }
}
