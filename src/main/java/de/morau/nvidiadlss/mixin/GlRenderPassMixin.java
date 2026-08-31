package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import de.morau.nvidiadlss.FoliageAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public abstract class GlRenderPassMixin {
    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void nvidiaDlss$auditBoundAtlasSampler(String name, GpuTextureView textureView,
        GpuSampler sampler, CallbackInfo ci) {
        FoliageAudit.recordSampler("OpenGL", "unknown", name, textureView, sampler);
    }
}
