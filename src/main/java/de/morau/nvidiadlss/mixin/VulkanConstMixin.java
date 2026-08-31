package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import de.morau.nvidiadlss.DlssRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {
    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void nvidiaDlss$addStorageUsage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
        if ((usage & DlssRenderer.STORAGE_USAGE) != 0) cir.setReturnValue(cir.getReturnValue() | 8);
    }
}
