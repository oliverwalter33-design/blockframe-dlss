package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanQueryPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanQueryPool.class)
public interface VulkanQueryPoolAccessor {
    @Accessor("vkQueryPool")
    long blockframe$queryPoolHandle();
}
