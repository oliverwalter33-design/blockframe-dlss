package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendFoundation;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Post-bake census boundary. No model or resource ownership is intercepted.
 */
@Mixin(ModelManager.class)
public abstract class NativeTerrainModelManagerMixin {
    @Inject(method = "apply", at = @At("HEAD"))
    private void blockframe$invalidateNativeTerrainCensus(
        CallbackInfo callback
    ) {
        NativeTerrainBackendFoundation.resourceReloadBeginning();
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void blockframe$captureNativeTerrainCensus(
        CallbackInfo callback
    ) {
        NativeTerrainBackendFoundation.modelsReloaded(
            (ModelManager)(Object)this
        );
    }
}
