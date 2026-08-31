package de.morau.nvidiadlss.mixin;

import de.morau.nvidiadlss.ThirdPersonGeometryMotion;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the exact model pose that Mojang is about to rasterize. */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {
    @Inject(
        method = "prepareModel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
        )
    )
    private void nvidiaDlss$captureExactAvatarGeometry(
        ModelFeatureRenderer.Submit<?> submit,
        CallbackInfo ci
    ) {
        if (submit.state() instanceof AvatarRenderState avatar) {
            ThirdPersonGeometryMotion.capture(
                submit.model(),
                avatar,
                submit.pose()
            );
        }
    }
}
