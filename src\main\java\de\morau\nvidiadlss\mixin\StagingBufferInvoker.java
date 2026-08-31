package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.vertex.StagingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StagingBuffer.class)
public interface StagingBufferInvoker {
    @Invoker("copyTo")
    void blockframe$copyTo(
        CommandEncoder encoder,
        GpuBuffer destination,
        long destinationOffset,
        long stagingOffset,
        long copySize
    );
}
