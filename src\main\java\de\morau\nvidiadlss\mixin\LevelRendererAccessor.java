package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Invoker("submitBlockOutline")
    void nvidiaDlss$submitBlockOutline(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
        LevelRenderState levelRenderState);
}
