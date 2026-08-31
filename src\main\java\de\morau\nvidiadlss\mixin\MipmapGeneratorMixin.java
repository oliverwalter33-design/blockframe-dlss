package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import de.morau.nvidiadlss.FoliageAudit;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MipmapGenerator.class)
public abstract class MipmapGeneratorMixin {
    @Inject(method = "generateMipLevels", at = @At("RETURN"))
    private static void nvidiaDlss$auditLeafMipmaps(Identifier name, NativeImage[] currentMips,
        int newMipLevel, MipmapStrategy mipmapStrategy, float alphaCutoffBias, Transparency transparency,
        CallbackInfoReturnable<NativeImage[]> cir) {
        FoliageAudit.recordMipmap(name, cir.getReturnValue(), newMipLevel, mipmapStrategy, alphaCutoffBias, transparency);
    }
}
