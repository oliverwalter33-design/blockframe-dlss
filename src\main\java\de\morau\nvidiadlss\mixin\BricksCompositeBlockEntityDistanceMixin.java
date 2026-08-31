package de.morau.nvidiadlss.mixin;

import de.morau.nvidiadlss.BricksCompatibility;
import de.morau.nvidiadlss.BricksCompositeRenderDistancePolicy;
import de.morau.nvidiadlss.BricksFarLodRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces only the proven distance decision for Bricks' exact composite
 * renderer. Every other renderer delegates to Minecraft unchanged.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BricksCompositeBlockEntityDistanceMixin {
    @Inject(method = "prepare", at = @At("HEAD"), require = 1)
    private void blockframe$beginBricksFarExtraction(
        Vec3 cameraPosition,
        CallbackInfo callback
    ) {
        BricksFarLodRuntime.beginExtractionFrame();
    }

    @Redirect(
        method = "tryExtractRenderState("
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
            + "FLnet/minecraft/client/renderer/feature/"
            + "ModelFeatureRenderer$CrumblingOverlay;Z"
            + "Lnet/minecraft/client/renderer/culling/Frustum;)"
            + "Lnet/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/"
                + "BlockEntityRenderer;shouldRender("
                + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                + "Lnet/minecraft/world/phys/Vec3;)Z",
            opcode = Opcodes.INVOKEINTERFACE
        ),
        require = 1,
        allow = 1
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean blockframe$bricksCompositeViewDistance(
        BlockEntityRenderer renderer,
        BlockEntity blockEntity,
        Vec3 cameraPosition
    ) {
        if (!BricksCompatibility.isExactCompositeRenderer(renderer)) {
            return renderer.shouldRender(blockEntity, cameraPosition);
        }
        return BricksCompositeRenderDistancePolicy.shouldRender(
            blockEntity.getBlockPos(),
            cameraPosition,
            Minecraft.getInstance().options.getEffectiveRenderDistance(),
            BricksCompatibility.configuredViewDistanceBlocks()
        );
    }

    @Redirect(
        method = "tryExtractRenderState("
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
            + "FLnet/minecraft/client/renderer/feature/"
            + "ModelFeatureRenderer$CrumblingOverlay;Z"
            + "Lnet/minecraft/client/renderer/culling/Frustum;)"
            + "Lnet/minecraft/client/renderer/blockentity/state/"
            + "BlockEntityRenderState;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/"
                + "BlockEntityRenderer;extractRenderState("
                + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                + "Lnet/minecraft/client/renderer/blockentity/state/"
                + "BlockEntityRenderState;F"
                + "Lnet/minecraft/world/phys/Vec3;"
                + "Lnet/minecraft/client/renderer/feature/"
                + "ModelFeatureRenderer$CrumblingOverlay;)V",
            opcode = Opcodes.INVOKEINTERFACE
        ),
        require = 1,
        allow = 1
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void blockframe$extractBricksCompositeFarLod(
        BlockEntityRenderer renderer,
        BlockEntity blockEntity,
        BlockEntityRenderState state,
        float partialTick,
        Vec3 cameraPosition,
        ModelFeatureRenderer.CrumblingOverlay breakingOverlay
    ) {
        if (
            BricksCompatibility.isExactCompositeRenderer(renderer)
                && BricksFarLodRuntime.extractFarState(
                    blockEntity,
                    state,
                    cameraPosition,
                    breakingOverlay
                )
        ) {
            return;
        }
        renderer.extractRenderState(
            blockEntity,
            state,
            partialTick,
            cameraPosition,
            breakingOverlay
        );
    }
}
