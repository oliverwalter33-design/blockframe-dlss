package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import de.morau.nvidiadlss.DlssSamplerPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuSampler.class)
public abstract class VulkanGpuSamplerMixin {
    @ModifyArg(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"),
        index = 0
    )
    private float nvidiaDlss$applyConstructionBias(float original) {
        return DlssSamplerPolicy.constructionBias(original);
    }
}
