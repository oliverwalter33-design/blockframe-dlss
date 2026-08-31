package de.morau.blockframe.benchmark.phase2a0c.mixin;

import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes reload/save entry points without cancelling or replacing them. */
final class Phase2a1StaticWorldEventMixin {
    private Phase2a1StaticWorldEventMixin() {
    }

    @Mixin(Minecraft.class)
    abstract static class ResourceReload {
        @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
        private void blockframe$phase2a1ObserveReload(
            CallbackInfoReturnable<?> callback
        ) {
            Phase2a0cCaptureRuntime.onStaticGateExternalMutation(
                "RESOURCE_RELOAD"
            );
        }
    }

    @Mixin(MinecraftServer.class)
    abstract static class WorldSave {
        @Inject(method = "saveAllChunks", at = @At("HEAD"))
        private void blockframe$phase2a1ObserveSaveAllChunks(
            boolean silent,
            boolean flush,
            boolean force,
            CallbackInfoReturnable<Boolean> callback
        ) {
            Phase2a0cCaptureRuntime.onStaticGateExternalMutation(
                "WORLD_SAVE"
            );
        }

        @Inject(method = "saveEverything", at = @At("HEAD"))
        private void blockframe$phase2a1ObserveSaveEverything(
            boolean silent,
            boolean flush,
            boolean force,
            CallbackInfoReturnable<Boolean> callback
        ) {
            Phase2a0cCaptureRuntime.onStaticGateExternalMutation(
                "WORLD_SAVE"
            );
        }
    }
}
