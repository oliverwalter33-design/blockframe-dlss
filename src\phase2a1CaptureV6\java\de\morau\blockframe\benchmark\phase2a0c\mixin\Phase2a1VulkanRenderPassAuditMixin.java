package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import java.util.Collection;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backend boundary audit. Nested drawIndexed calls made by
 * drawMultipleIndexed are suppressed so every CPU draw record is counted once.
 */
@Mixin(value = VulkanRenderPass.class, priority = 500)
abstract class Phase2a1VulkanRenderPassAuditMixin {
    @Shadow
    protected @Nullable VulkanRenderPipeline pipeline;

    @Unique
    private int blockframe$phase2a1MultipleDepth;

    @Unique
    private long blockframe$phase2a1IndirectStarted;

    @WrapMethod(method = "drawMultipleIndexed")
    private <T> void blockframe$phase2a1AuditMultipleIndexed(
        Collection<RenderPass.Draw<T>> draws,
        @Nullable GpuBuffer defaultIndexBuffer,
        @Nullable IndexType defaultIndexType,
        Collection<String> dynamicUniforms,
        T uniformArgument,
        Operation<Void> original
    ) {
        boolean outer = blockframe$phase2a1MultipleDepth++ == 0;
        long started = outer ? System.nanoTime() : 0L;
        try {
            original.call(
                draws,
                defaultIndexBuffer,
                defaultIndexType,
                dynamicUniforms,
                uniformArgument
            );
        } finally {
            blockframe$phase2a1MultipleDepth--;
            if (outer) {
                Phase2a0cCaptureRuntime.onBackendDraw(
                    Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                    draws.size(),
                    System.nanoTime() - started
                );
            }
        }
    }

    @WrapMethod(method = "drawIndexed")
    private void blockframe$phase2a1AuditDrawIndexed(
        int indexCount,
        int instanceCount,
        int firstIndex,
        int vertexOffset,
        int firstInstance,
        Operation<Void> original
    ) {
        if (blockframe$phase2a1MultipleDepth != 0) {
            original.call(
                indexCount,
                instanceCount,
                firstIndex,
                vertexOffset,
                firstInstance
            );
            return;
        }
        long started = System.nanoTime();
        try {
            original.call(
                indexCount,
                instanceCount,
                firstIndex,
                vertexOffset,
                firstInstance
            );
        } finally {
            Phase2a0cCaptureRuntime.onBackendDraw(
                Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                1,
                System.nanoTime() - started
            );
        }
    }

    @WrapMethod(
        method =
            "multiDrawIndexed(Ljava/nio/IntBuffer;III)V"
    )
    private void blockframe$phase2a1AuditMultiDrawIndexed(
        IntBuffer drawParameters,
        int instanceCount,
        int firstInstance,
        int drawCount,
        Operation<Void> original
    ) {
        long started = System.nanoTime();
        try {
            original.call(
                drawParameters,
                instanceCount,
                firstInstance,
                drawCount
            );
        } finally {
            Phase2a0cCaptureRuntime.onBackendDraw(
                Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                drawCount,
                System.nanoTime() - started
            );
        }
    }

    @WrapMethod(method = "drawIndexedIndirect")
    private void blockframe$phase2a1AuditDrawIndexedIndirect(
        GpuBufferSlice commands,
        int drawCount,
        Operation<Void> original
    ) {
        long started = System.nanoTime();
        try {
            original.call(commands, drawCount);
        } finally {
            Phase2a0cCaptureRuntime.onBackendDraw(
                Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                drawCount,
                System.nanoTime() - started
            );
        }
    }

    @WrapMethod(method = "draw")
    private void blockframe$phase2a1AuditDraw(
        int vertexCount,
        int instanceCount,
        int firstVertex,
        int firstInstance,
        Operation<Void> original
    ) {
        long started = System.nanoTime();
        try {
            original.call(
                vertexCount,
                instanceCount,
                firstVertex,
                firstInstance
            );
        } finally {
            Phase2a0cCaptureRuntime.onBackendDraw(
                Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                1,
                System.nanoTime() - started
            );
        }
    }

    @WrapMethod(method = "multiDraw(Ljava/nio/IntBuffer;III)V")
    private void blockframe$phase2a1AuditMultiDraw(
        IntBuffer drawParameters,
        int instanceCount,
        int firstInstance,
        int drawCount,
        Operation<Void> original
    ) {
        long started = System.nanoTime();
        try {
            original.call(
                drawParameters,
                instanceCount,
                firstInstance,
                drawCount
            );
        } finally {
            Phase2a0cCaptureRuntime.onBackendDraw(
                Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                drawCount,
                System.nanoTime() - started
            );
        }
    }

    @WrapMethod(method = "drawIndirect")
    private void blockframe$phase2a1AuditDrawIndirect(
        GpuBufferSlice commands,
        int drawCount,
        Operation<Void> original
    ) {
        long started = System.nanoTime();
        try {
            original.call(commands, drawCount);
        } finally {
            Phase2a0cCaptureRuntime.onBackendDraw(
                Phase2a0cCaptureRuntime.classifyPipeline(pipeline),
                drawCount,
                System.nanoTime() - started
            );
        }
    }

    /**
     * This method exists only after BlockFrame's lower-priority-independent
     * Vulkan mixin is merged. require=0 keeps Mojang-only profiles valid.
     */
    @Inject(
        method = "blockframe$drawIndexedIndirectCount",
        at = @At("HEAD"),
        require = 0,
        remap = false
    )
    private void blockframe$phase2a1IndirectHead(
        GpuBuffer commands,
        long commandOffset,
        GpuBuffer counts,
        long countOffset,
        int maximumDrawCount,
        int commandStride,
        CallbackInfo callback
    ) {
        blockframe$phase2a1IndirectStarted = System.nanoTime();
        Phase2a0cCaptureRuntime.onOpaqueSolidIndirectCall(
            maximumDrawCount
        );
    }

    @Inject(
        method = "blockframe$drawIndexedIndirectCount",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private void blockframe$phase2a1IndirectReturn(
        GpuBuffer commands,
        long commandOffset,
        GpuBuffer counts,
        long countOffset,
        int maximumDrawCount,
        int commandStride,
        CallbackInfo callback
    ) {
        Phase2a0cCaptureRuntime.onOpaqueSolidIndirectCpuNanos(
            System.nanoTime() - blockframe$phase2a1IndirectStarted
        );
    }
}
