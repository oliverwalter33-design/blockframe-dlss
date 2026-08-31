package de.morau.nvidiadlss;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Near/far visibility policy for the exact Bricks composite renderer. */
public final class BricksCompositeRenderDistancePolicy {
    private static final int BLOCKS_PER_CHUNK = 16;

    private BricksCompositeRenderDistancePolicy() {
    }

    public static boolean shouldRender(
        BlockPos blockPos,
        Vec3 cameraPosition,
        int effectiveRenderDistanceChunks,
        int configuredLimitBlocks
    ) {
        int distance = effectiveDistanceBlocks(
            effectiveRenderDistanceChunks,
            configuredLimitBlocks
        );
        return Vec3.atCenterOf(blockPos).closerThan(cameraPosition, distance);
    }

    static int effectiveDistanceBlocks(
        int effectiveRenderDistanceChunks,
        int configuredLimitBlocks
    ) {
        int safeConfiguredMode = Math.clamp(
            configuredLimitBlocks,
            BricksCompatibility.MIN_VIEW_DISTANCE_BLOCKS,
            BricksCompatibility.MAX_VIEW_DISTANCE_BLOCKS
        );
        long renderDistanceCap = (long) Math.max(1, effectiveRenderDistanceChunks)
            * BLOCKS_PER_CHUNK;
        // 64 remains the explicit negative-control / vanilla-distance mode.
        // Every enabled far-LOD mode renders to Minecraft's own effective
        // distance; 96/160 must never become raw full-detail distance bands.
        long requested = safeConfiguredMode
                == BricksCompatibility.MIN_VIEW_DISTANCE_BLOCKS
            ? BricksFarLodRuntime.NEAR_DISTANCE_BLOCKS
            : renderDistanceCap;
        return (int) Math.min(requested, renderDistanceCap);
    }
}
