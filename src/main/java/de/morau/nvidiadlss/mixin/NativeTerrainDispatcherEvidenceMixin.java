package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainOwnershipEvidence;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records entry into Mojang's real terrain upload pass. */
@Mixin(SectionRenderDispatcher.class)
public abstract class NativeTerrainDispatcherEvidenceMixin {
    @Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"))
    private void blockframe$recordMojangTerrainUpload(
        CallbackInfo callback
    ) {
        NativeTerrainOwnershipEvidence.mojangGpuUploaded();
    }
}
