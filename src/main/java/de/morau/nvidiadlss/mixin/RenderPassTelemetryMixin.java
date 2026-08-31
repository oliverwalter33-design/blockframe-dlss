package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import de.morau.blockframe.core.BlockframeRuntime;
import java.nio.IntBuffer;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Counts successful public Blaze3D draw submissions. Redirects avoid
 * allocating callback objects on each draw.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassTelemetryMixin {
    @Redirect(
        method = "drawIndexed(IIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;drawIndexed(IIIII)V"
        )
    )
    private void blockframe$countDrawIndexed(
        RenderPassBackend backend,
        int indexCount,
        int instanceCount,
        int firstIndex,
        int vertexOffset,
        int firstInstance
    ) {
        backend.drawIndexed(
            indexCount,
            instanceCount,
            firstIndex,
            vertexOffset,
            firstInstance
        );
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;multiDrawIndexed(Ljava/nio/IntBuffer;III)V"
        )
    )
    private void blockframe$countMultiDrawIndexedInterleaved(
        RenderPassBackend backend,
        IntBuffer parameters,
        int instanceCount,
        int firstInstance,
        int drawCount
    ) {
        backend.multiDrawIndexed(
            parameters,
            instanceCount,
            firstInstance,
            drawCount
        );
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"
        )
    )
    private void blockframe$countMultiDrawIndexedSeparate(
        RenderPassBackend backend,
        PointerBuffer firstIndexOffsets,
        IntBuffer indexCounts,
        IntBuffer vertexOffsets,
        int drawCount
    ) {
        backend.multiDrawIndexed(
            firstIndexOffsets,
            indexCounts,
            vertexOffsets,
            drawCount
        );
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "drawIndexedIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;drawIndexedIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V"
        )
    )
    private void blockframe$countDrawIndexedIndirect(
        RenderPassBackend backend,
        GpuBufferSlice commands,
        int drawCount
    ) {
        backend.drawIndexedIndirect(commands, drawCount);
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"
        )
    )
    private <T> void blockframe$countDrawMultipleIndexed(
        RenderPassBackend backend,
        Collection<RenderPass.Draw<T>> draws,
        @Nullable GpuBuffer defaultIndexBuffer,
        @Nullable IndexType defaultIndexType,
        Collection<String> dynamicUniforms,
        T uniformArgument
    ) {
        backend.drawMultipleIndexed(
            draws,
            defaultIndexBuffer,
            defaultIndexType,
            dynamicUniforms,
            uniformArgument
        );
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "draw(IIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;draw(IIII)V"
        )
    )
    private void blockframe$countDraw(
        RenderPassBackend backend,
        int vertexCount,
        int instanceCount,
        int firstVertex,
        int firstInstance
    ) {
        backend.draw(vertexCount, instanceCount, firstVertex, firstInstance);
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "multiDraw(Ljava/nio/IntBuffer;III)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;multiDraw(Ljava/nio/IntBuffer;III)V"
        )
    )
    private void blockframe$countMultiDrawInterleaved(
        RenderPassBackend backend,
        IntBuffer parameters,
        int instanceCount,
        int firstInstance,
        int drawCount
    ) {
        backend.multiDraw(parameters, instanceCount, firstInstance, drawCount);
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"
        )
    )
    private void blockframe$countMultiDrawSeparate(
        RenderPassBackend backend,
        IntBuffer firstVertices,
        IntBuffer vertexCounts,
        int drawCount
    ) {
        backend.multiDraw(firstVertices, vertexCounts, drawCount);
        BlockframeRuntime.recordDrawCall();
    }

    @Redirect(
        method = "drawIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;drawIndirect(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V"
        )
    )
    private void blockframe$countDrawIndirect(
        RenderPassBackend backend,
        GpuBufferSlice commands,
        int drawCount
    ) {
        backend.drawIndirect(commands, drawCount);
        BlockframeRuntime.recordDrawCall();
    }
}
