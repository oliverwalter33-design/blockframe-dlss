package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.textures.GpuSampler;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuScenePolicy;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuSceneRuntime;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;

/** Substitutes only the successfully prepared OPAQUE marker. */
@Mixin(ChunkSectionsToRender.class)
public abstract class OpaqueSolidGpuSceneRenderGroupMixin {
    @WrapMethod(method = "renderGroup")
    private void blockframe$renderOpaqueGpuScene(
        ChunkSectionLayerGroup group,
        GpuSampler sampler,
        Operation<Void> original
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            original.call(group, sampler);
            return;
        }
        if (
            OpaqueSolidGpuSceneRuntime.renderOpaqueGroup(
                (ChunkSectionsToRender)(Object)this,
                group,
                sampler
            )
        ) {
            return;
        }
        original.call(group, sampler);
    }
}
