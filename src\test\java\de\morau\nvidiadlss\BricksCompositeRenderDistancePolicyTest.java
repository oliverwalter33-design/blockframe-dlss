package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class BricksCompositeRenderDistancePolicyTest {
    @Test
    void enabledFarLodRunsToEffectiveMinecraftDistance() {
        assertEquals(
            512,
            BricksCompositeRenderDistancePolicy.effectiveDistanceBlocks(32, 96)
        );
        assertEquals(
            80,
            BricksCompositeRenderDistancePolicy.effectiveDistanceBlocks(5, 96)
        );
        assertEquals(
            64,
            BricksCompositeRenderDistancePolicy.effectiveDistanceBlocks(4, 128)
        );
    }

    @Test
    void sixtyFourStaysNegativeControlWhileEnabledValuesAreNotRawBands() {
        assertEquals(
            64,
            BricksCompositeRenderDistancePolicy.effectiveDistanceBlocks(32, 0)
        );
        assertEquals(
            512,
            BricksCompositeRenderDistancePolicy.effectiveDistanceBlocks(32, 512)
        );
    }

    @Test
    void measuredTargetIsAcceptedAt96ButRejectedBy64NegativeControl() {
        BlockPos target = new BlockPos(78, 0, 0);
        Vec3 camera = new Vec3(0.0, 0.5, 0.5);

        assertTrue(BricksCompositeRenderDistancePolicy.shouldRender(
            target,
            camera,
            32,
            96
        ));
        assertFalse(BricksCompositeRenderDistancePolicy.shouldRender(
            target,
            camera,
            32,
            64
        ));
    }
}
