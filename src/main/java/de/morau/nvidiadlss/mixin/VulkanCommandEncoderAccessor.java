package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    @Invoker("commandBuffer") VkCommandBuffer nvidiaDlss$commandBuffer();

    @Accessor("currentSubmitIndex")
    long blockframe$currentSubmitIndex();
}
