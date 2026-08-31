package de.morau.blockframe.benchmark.phase2a0c.mixin;

import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One narrow callback immediately before Mojang extracts the current camera.
 * No client-lifecycle or world-lifecycle method is observed.
 */
@Mixin(GameRenderer.class)
abstract class Phase2a0cGameRendererMixin {
    @Inject(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;"
                + "extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V",
            shift = At.Shift.BEFORE
        )
    )
    private void blockframe$phase2a0cBeforeCameraExtract(
        DeltaTracker deltaTracker,
        boolean renderWorld,
        CallbackInfo callback
    ) {
        Phase2a0cCaptureRuntime.onRenderCallback(
            (GameRenderer) (Object) this
        );
    }

    /**
     * The one-shot color reference is requested only while the runtime is
     * between WARMUP and MEASURE. Normal measured frames return immediately.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void blockframe$phase2a0cAfterRender(
        DeltaTracker deltaTracker,
        boolean renderWorld,
        CallbackInfo callback
    ) {
        Phase2a0cCaptureRuntime.onRenderComplete(
            (GameRenderer) (Object) this,
            renderWorld
        );
    }
}
