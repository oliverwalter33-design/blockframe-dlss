package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.faststart.FastStartRuntime;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the exact LevelLoadTracker transition that closes the loading UI. */
@Mixin(LevelLoadingScreen.class)
public abstract class FastStartLevelLoadingScreenMixin {
    @Inject(method = "onClose", at = @At("HEAD"))
    private void blockframe$fastStartChunksReady(CallbackInfo ci) {
        FastStartRuntime.startChunksReady();
    }
}
