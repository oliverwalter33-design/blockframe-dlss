package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import de.morau.nvidiadlss.DlssSamplerPolicy;
import de.morau.nvidiadlss.VulkanGpuSamplerDescriptorAccess;
import de.morau.nvidiadlss.VulkanSamplerDescriptor;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuSampler.class)
public abstract class VulkanGpuSamplerMixin
    implements VulkanGpuSamplerDescriptorAccess {
    @Unique
    private VulkanSamplerDescriptor blockframe$samplerDescriptor;

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target =
                "Lorg/lwjgl/vulkan/VK12;vkCreateSampler("
                    + "Lorg/lwjgl/vulkan/VkDevice;"
                    + "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"
                    + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
                    + "Ljava/nio/LongBuffer;)I"
        ),
        index = 1
    )
    private VkSamplerCreateInfo nvidiaDlss$applyConstructionBias(
        VkSamplerCreateInfo createInfo
    ) {
        this.blockframe$samplerDescriptor =
            DlssSamplerPolicy.prepareSamplerCreateInfo(createInfo);
        return createInfo;
    }

    @Override
    public VulkanSamplerDescriptor blockframe$samplerDescriptor() {
        return this.blockframe$samplerDescriptor;
    }
}
