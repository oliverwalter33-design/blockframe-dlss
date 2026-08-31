package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import de.morau.blockframe.render.terrain.OpaqueSolidTerrainBatchRuntime;
import java.util.Collection;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tracks the exact no-replay boundary while retaining Mojang submission.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class OpaqueSolidTerrainRenderGroupMixin {
    @WrapMethod(method = "renderGroup")
    private void blockframe$closeOpaqueSolidSubmissionState(
        ChunkSectionLayerGroup group,
        GpuSampler sampler,
        Operation<Void> original
    ) {
        boolean completedNormally = false;
        try {
            original.call(group, sampler);
            completedNormally = true;
        } finally {
            OpaqueSolidTerrainBatchRuntime.finishRenderGroup(
                (ChunkSectionsToRender)(Object)this,
                group,
                completedNormally
            );
        }
    }

    @WrapOperation(
        method = "renderGroup",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;"
                + "drawMultipleIndexed(Ljava/util/Collection;"
                + "Lcom/mojang/blaze3d/buffers/GpuBuffer;"
                + "Lcom/mojang/blaze3d/IndexType;"
                + "Ljava/util/Collection;Ljava/lang/Object;)V"
        )
    )
    private <T> void blockframe$measureOpaqueSolidSubmission(
        RenderPass renderPass,
        Collection<RenderPass.Draw<T>> draws,
        GpuBuffer defaultIndexBuffer,
        IndexType defaultIndexType,
        Collection<String> dynamicUniforms,
        Object dynamicUniformSlices,
        Operation<Void> original,
        ChunkSectionLayerGroup group,
        GpuSampler sampler
    ) {
        boolean tracked =
            OpaqueSolidTerrainBatchRuntime.beginDrawSubmission(
                (ChunkSectionsToRender)(Object)this,
                group
            );
        long started = tracked ? System.nanoTime() : 0L;
        try {
            original.call(
                renderPass,
                draws,
                defaultIndexBuffer,
                defaultIndexType,
                dynamicUniforms,
                dynamicUniformSlices
            );
        } finally {
            if (tracked) {
                OpaqueSolidTerrainBatchRuntime.recordDrawSubmission(
                    true,
                    System.nanoTime() - started
                );
            }
        }
    }
}
