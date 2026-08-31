package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import de.morau.nvidiadlss.DlssBootstrap;
import java.util.Set;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.BackendCreationException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @WrapMethod(method = "createDevice")
    private GpuDevice blockframe$guardActualVulkanDeviceCreation(
        long window,
        ShaderSource shaderSource,
        GpuDebugOptions debugOptions,
        Runnable criticalShaderLoader,
        Operation<GpuDevice> original
    ) throws BackendCreationException {
        DlssBootstrap.beginVulkanDeviceCreation();
        try {
            return original.call(
                window,
                shaderSource,
                debugOptions,
                criticalShaderLoader
            );
        } finally {
            DlssBootstrap.endVulkanDeviceCreation();
        }
    }

    @Inject(
        method = "createDevice",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", shift = At.Shift.BEFORE),
        require = 0
    )
    private void nvidiaDlss$enableDeviceRequirements(long window, ShaderSource shaderSource, GpuDebugOptions debugOptions,
        Runnable criticalShaderLoader, CallbackInfoReturnable<GpuDevice> cir,
        @Local(name = "deviceExtensions") Set<String> deviceExtensions,
        @Local(name = "enabledFeatures") Set<VulkanFeature> enabledFeatures,
        @Local(name = "physicalDevice") VulkanPhysicalDevice physicalDevice) {
        DlssBootstrap.configureDeviceCapabilities(
            physicalDevice,
            deviceExtensions,
            enabledFeatures
        );
    }
}
