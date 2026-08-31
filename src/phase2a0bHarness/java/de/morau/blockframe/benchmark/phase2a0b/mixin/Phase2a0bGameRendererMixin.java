package de.morau.blockframe.benchmark.phase2a0b.mixin;

import de.morau.blockframe.benchmark.phase2a0b.Phase2a0bRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class Phase2a0bGameRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At("HEAD")
    )
    private void blockframe2a0b$beforeRender(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        CallbackInfo callback
    ) {
        Phase2a0bRuntime.onRenderHead(advanceGameTime);
    }

    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At("RETURN")
    )
    private void blockframe2a0b$afterRender(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        CallbackInfo callback
    ) {
        Phase2a0bRuntime.onRenderReturn(advanceGameTime);
    }
}
