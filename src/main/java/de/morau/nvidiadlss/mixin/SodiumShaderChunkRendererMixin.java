package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds one BlockFrame-owned define to Sodium's cutout pipeline without
 * importing, replacing, or taking ownership of Sodium terrain classes.
 */
@Pseudo
@Mixin(
    targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer",
    remap = false
)
public abstract class SodiumShaderChunkRendererMixin {
    @Redirect(
        method = "createShader",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
                + "withShaderDefine(Ljava/lang/String;F)"
                + "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
        ),
        require = 2
    )
    private RenderPipeline.Builder nvidiaDlss$markCutoutPipeline(
        RenderPipeline.Builder builder,
        String name,
        float value
    ) {
        RenderPipeline.Builder result = builder.withShaderDefine(name, value);
        if ("ALPHA_CUTOUT".equals(name) && Float.compare(value, 0.5F) == 0) {
            result.withShaderDefine("NVIDIA_DLSS_CUTOUT");
        }
        return result;
    }
}
