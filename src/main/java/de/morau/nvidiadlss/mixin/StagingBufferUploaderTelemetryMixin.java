package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.vertex.StagingBuffer;
import de.morau.blockframe.core.BlockframeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StagingBuffer.Uploader.class)
public abstract class StagingBufferUploaderTelemetryMixin {
    @Redirect(
        method = "copyTo",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/StagingBuffer;copyTo("
                + "Lcom/mojang/blaze3d/systems/CommandEncoder;"
                + "Lcom/mojang/blaze3d/buffers/GpuBuffer;JJJ)V"
        )
    )
    private void blockframe$measureUploadCopy(
        StagingBuffer stagingBuffer,
        CommandEncoder encoder,
        GpuBuffer destination,
        long destinationOffset,
        long stagingOffset,
        long copySize
    ) {
        long started = System.nanoTime();
        try {
            ((StagingBufferInvoker)stagingBuffer).blockframe$copyTo(
                encoder,
                destination,
                destinationOffset,
                stagingOffset,
                copySize
            );
        } finally {
            BlockframeRuntime.recordUpload(
                copySize,
                System.nanoTime() - started
            );
        }
    }
}
