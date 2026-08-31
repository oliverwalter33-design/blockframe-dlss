package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accesses the two existing buffers of Mojang's private record. Returning
 * their owners avoids allocating RenderSectionBufferSlice wrappers.
 */
@Mixin(
    targets =
        "net.minecraft.client.renderer.chunk."
            + "SectionRenderDispatcher$SectionUberBuffers"
)
public interface Phase2a1SectionUberBuffersAccessor {
    @Accessor("vertexBuffer")
    UberGpuBuffer<SectionMesh> blockframe$phase2a1VertexBuffer();

    @Accessor("indexBuffer")
    UberGpuBuffer<SectionMesh> blockframe$phase2a1IndexBuffer();
}
