package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import de.morau.nvidiadlss.FoliageAudit;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {
    @Redirect(
        method = "increaseMipLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/MipmapGenerator;generateMipLevels(Lnet/minecraft/resources/Identifier;[Lcom/mojang/blaze3d/platform/NativeImage;ILnet/minecraft/client/renderer/texture/MipmapStrategy;FLcom/mojang/blaze3d/platform/Transparency;)[Lcom/mojang/blaze3d/platform/NativeImage;"
        )
    )
    private NativeImage[] nvidiaDlss$auditLeafMipmapStrategy(Identifier name, NativeImage[] currentMips,
        int newMipLevel, MipmapStrategy mipmapStrategy, float alphaCutoffBias, Transparency transparency) {
        MipmapStrategy selected = FoliageAudit.leafMipmapStrategy(name, mipmapStrategy);
        return MipmapGenerator.generateMipLevels(name, currentMips, newMipLevel, selected, alphaCutoffBias, transparency);
    }
}
