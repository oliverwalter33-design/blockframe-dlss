package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import de.morau.nvidiadlss.DlssBootstrap;
import de.morau.nvidiadlss.nativebridge.NativeStreamline;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    private static final int NOT_HANDLED = Integer.MIN_VALUE;

    @Redirect(
        method = "present",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I")
    )
    private int nvidiaDlss$presentThroughStreamline(VkQueue queue, VkPresentInfoKHR presentInfo) {
        if (!DlssBootstrap.handlesPresentQueue(queue.address())) {
            return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
        }

        int result = NativeStreamline.queuePresent(queue.address(), presentInfo.address());
        return result == NOT_HANDLED ? KHRSwapchain.vkQueuePresentKHR(queue, presentInfo) : result;
    }
}
