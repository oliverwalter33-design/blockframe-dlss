package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view onto Mojang's render-pass texture/sampler pair. Physical
 * image-view and sampler ownership remains entirely with Minecraft.
 */
@Mixin(
    targets =
        "com.mojang.blaze3d.vulkan."
            + "VulkanRenderPass$TextureViewAndSampler"
)
public interface VulkanTextureViewAndSamplerAccessor {
    @Accessor("view")
    VulkanGpuTextureView blockframe$view();

    @Accessor("sampler")
    VulkanGpuSampler blockframe$sampler();
}
