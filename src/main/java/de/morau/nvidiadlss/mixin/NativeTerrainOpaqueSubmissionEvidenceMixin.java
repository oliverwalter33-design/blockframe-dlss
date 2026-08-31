package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainOwnershipEvidence;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records entry into Mojang's actual Solid/Cutout submission group. */
@Mixin(ChunkSectionsToRender.class)
public abstract class NativeTerrainOpaqueSubmissionEvidenceMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void blockframe$recordMojangOpaqueSubmission(
        ChunkSectionLayerGroup group,
        GpuSampler sampler,
        CallbackInfo callback
    ) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            NativeTerrainOwnershipEvidence.mojangOpaqueSubmitted();
        }
    }
}
