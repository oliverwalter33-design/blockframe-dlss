package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.faststart.FastStartRuntime;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact, observation-only hook for the start of every client resource reload. */
@Mixin(ReloadableResourceManager.class)
public abstract class FastStartResourceReloadMixin {
    @Inject(method = "createReload", at = @At("HEAD"))
    private void blockframe$fastStartResourceReloadBegin(
        CallbackInfoReturnable<ReloadInstance> cir
    ) {
        FastStartRuntime.resourceReloadStarted();
    }
}
