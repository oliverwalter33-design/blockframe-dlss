package de.morau.blockframe.benchmark.phase2a0c.mixin;

import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to Mojang's existing layer-to-UberBuffer owners. */
@Mixin(SectionRenderDispatcher.class)
public interface Phase2a1SectionRenderDispatcherBuffersAccessor {
    @Accessor("chunkUberBuffers")
    Map<ChunkSectionLayer, ?> blockframe$phase2a1ChunkUberBuffers();
}
