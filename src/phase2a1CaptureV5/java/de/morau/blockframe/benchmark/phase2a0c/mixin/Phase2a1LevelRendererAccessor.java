package de.morau.blockframe.benchmark.phase2a0c.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to Mojang's existing section-dispatch backlog owner.
 *
 * <p>The capture never replaces, drains or schedules work through this
 * accessor.</p>
 */
@Mixin(LevelRenderer.class)
public interface Phase2a1LevelRendererAccessor {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher blockframe$phase2a1SectionRenderDispatcher();
}
