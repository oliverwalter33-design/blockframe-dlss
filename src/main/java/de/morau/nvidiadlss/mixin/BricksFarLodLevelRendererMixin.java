package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.morau.nvidiadlss.BricksFarLodRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame-batches only states explicitly marked by the exact Bricks far path. */
@Mixin(LevelRenderer.class)
public abstract class BricksFarLodLevelRendererMixin {
    @Inject(
        method = "submitBlockEntities",
        at = @At("HEAD"),
        require = 1
    )
    private void blockframe$beginBricksFarSubmission(
        PoseStack poseStack,
        LevelRenderState levelState,
        SubmitNodeCollector collector,
        CallbackInfo callback
    ) {
        BricksFarLodRuntime.beginSubmissionFrame();
    }

    @Redirect(
        method = "submitBlockEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/"
                + "BlockEntityRenderDispatcher;submit("
                + "Lnet/minecraft/client/renderer/blockentity/state/"
                + "BlockEntityRenderState;"
                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                + "Lnet/minecraft/client/renderer/state/level/"
                + "CameraRenderState;)V"
        ),
        require = 1,
        allow = 1
    )
    private void blockframe$queueBricksFarState(
        BlockEntityRenderDispatcher dispatcher,
        BlockEntityRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        CameraRenderState camera
    ) {
        if (BricksFarLodRuntime.queueFarState(state, camera)) {
            return;
        }
        dispatcher.submit(state, poseStack, collector, camera);
    }

    @Inject(
        method = "submitBlockEntities",
        at = @At("TAIL"),
        require = 1
    )
    private void blockframe$flushBricksFarSubmission(
        PoseStack poseStack,
        LevelRenderState levelState,
        SubmitNodeCollector collector,
        CallbackInfo callback
    ) {
        BricksFarLodRuntime.flushFarBatches(poseStack, collector);
    }
}
