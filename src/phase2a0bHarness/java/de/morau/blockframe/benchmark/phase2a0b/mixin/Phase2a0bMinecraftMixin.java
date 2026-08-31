package de.morau.blockframe.benchmark.phase2a0b.mixin;

import de.morau.blockframe.benchmark.phase2a0b.Phase2a0bRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class Phase2a0bMinecraftMixin {
    @Inject(method = "close()V", at = @At("HEAD"))
    private void blockframe2a0b$close(CallbackInfo callback) {
        Phase2a0bRuntime.close();
    }

    @Inject(
        method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
        at = @At("RETURN")
    )
    private void blockframe2a0b$levelSet(
        ClientLevel level,
        CallbackInfo callback
    ) {
        Phase2a0bRuntime.onClientLevelSet(level);
    }

    @Inject(
        method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At("HEAD")
    )
    private void blockframe2a0b$levelClearing(
        Screen screen,
        CallbackInfo callback
    ) {
        Phase2a0bRuntime.onClientLevelUnload();
    }
}
