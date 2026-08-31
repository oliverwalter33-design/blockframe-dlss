package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuScenePolicy;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuSceneRuntime;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks only exact UberGpuBuffer mutation points. Publication runs from the
 * original callback after Mojang has inserted the physical allocation.
 */
@Mixin(UberGpuBuffer.class)
public abstract class OpaqueSolidGpuSceneUberBufferMixin {
    @Shadow @Final private int bufferUsage;
    @Shadow @Final private String name;

    @WrapMethod(method = "addAllocation")
    private <U> boolean blockframe$wrapSolidUploadPublication(
        U key,
        UberGpuBuffer.UploadCallback<U> callback,
        ByteBuffer buffer,
        Operation<Boolean> original
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return original.call(key, callback, buffer);
        }
        UberGpuBuffer.UploadCallback<U> wrapped = uploaded -> {
            try {
                OpaqueSolidGpuSceneRuntime.rangePublished(
                    (UberGpuBuffer<?>)(Object)this,
                    uploaded,
                    this.name,
                    this.bufferUsage
                );
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError error
            ) {
                OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                    "range-publish",
                    error
                );
            } finally {
                if (callback != null) {
                    callback.bufferHasBeenUploaded(uploaded);
                }
            }
        };
        return original.call(key, wrapped, buffer);
    }

    @Inject(method = "freeAllocation", at = @At("HEAD"))
    private void blockframe$invalidateSolidRangeBeforeFree(
        Object key,
        CallbackInfo callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.rangeInvalidating(
                (UberGpuBuffer<?>)(Object)this,
                key,
                this.name,
                this.bufferUsage
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "range-invalidate",
                error
            );
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void blockframe$invalidateSolidRangesBeforeClose(
        CallbackInfo callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.uberBufferClosing(
                (UberGpuBuffer<?>)(Object)this,
                this.name,
                this.bufferUsage
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "uber-buffer-close",
                error
            );
        }
    }
}
