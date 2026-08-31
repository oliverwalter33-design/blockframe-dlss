package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks the exact aggregate number of Mojang-owned staged allocations.
 * It neither reads their payload nor changes upload/allocator ownership.
 */
@Mixin(UberGpuBuffer.class)
abstract class Phase2a1UberGpuBufferBacklogMixin {
    @Shadow
    private Object2ObjectOpenHashMap<?, ?> stagedAllocations;

    @Unique
    private int blockframe$phase2a1BacklogBeforeMutation;

    @Inject(method = "addAllocation", at = @At("HEAD"))
    private void blockframe$phase2a1BeforeAdd(
        Object key,
        UberGpuBuffer.UploadCallback<?> callback,
        java.nio.ByteBuffer buffer,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        blockframe$phase2a1BacklogBeforeMutation =
            stagedAllocations.size();
    }

    @Inject(method = "addAllocation", at = @At("RETURN"))
    private void blockframe$phase2a1AfterAdd(
        Object key,
        UberGpuBuffer.UploadCallback<?> callback,
        java.nio.ByteBuffer buffer,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        Phase2a0cCaptureRuntime.onUploadBacklogDelta(
            stagedAllocations.size()
                - blockframe$phase2a1BacklogBeforeMutation
        );
    }

    @Inject(method = "uploadStagedAllocations", at = @At("HEAD"))
    private void blockframe$phase2a1BeforeUpload(
        com.mojang.blaze3d.systems.GpuDevice device,
        com.mojang.blaze3d.vertex.StagingBuffer.Uploader uploader,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        blockframe$phase2a1BacklogBeforeMutation =
            stagedAllocations.size();
    }

    @Inject(method = "uploadStagedAllocations", at = @At("RETURN"))
    private void blockframe$phase2a1AfterUpload(
        com.mojang.blaze3d.systems.GpuDevice device,
        com.mojang.blaze3d.vertex.StagingBuffer.Uploader uploader,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        Phase2a0cCaptureRuntime.onUploadBacklogDelta(
            stagedAllocations.size()
                - blockframe$phase2a1BacklogBeforeMutation
        );
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void blockframe$phase2a1BeforeClose(CallbackInfo callback) {
        blockframe$phase2a1BacklogBeforeMutation =
            stagedAllocations.size();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void blockframe$phase2a1AfterClose(CallbackInfo callback) {
        Phase2a0cCaptureRuntime.onUploadBacklogDelta(
            stagedAllocations.size()
                - blockframe$phase2a1BacklogBeforeMutation
        );
    }
}
