package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import java.util.Collection;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Counts Mojang's real solid-terrain multi-draw submissions without replacing
 * the draw owner or iterating the draw records.
 */
@Mixin(ChunkSectionsToRender.class)
abstract class Phase2a1ChunkSectionsToRenderMixin {
    @WrapOperation(
        method = "renderGroup",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;"
                + "drawMultipleIndexed(Ljava/util/Collection;"
                + "Lcom/mojang/blaze3d/buffers/GpuBuffer;"
                + "Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;"
                + "Ljava/lang/Object;)V"
        )
    )
    private void blockframe$phase2a1CountTerrainSubmission(
        RenderPass renderPass,
        Collection<RenderPass.Draw<GpuBufferSlice[]>> draws,
        GpuBuffer defaultIndexBuffer,
        IndexType defaultIndexType,
        Collection<String> dynamicUniforms,
        Object dynamicUniformSlices,
        Operation<Void> original
    ) {
        Phase2a0cCaptureRuntime.onTerrainDrawSubmission(draws.size());
        original.call(
            renderPass,
            draws,
            defaultIndexBuffer,
            defaultIndexType,
            dynamicUniforms,
            dynamicUniformSlices
        );
    }
}
