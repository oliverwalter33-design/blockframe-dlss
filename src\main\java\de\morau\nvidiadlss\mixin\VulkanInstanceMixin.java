package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import de.morau.nvidiadlss.DlssBootstrap;
import java.util.Set;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @Shadow @Final private Set<String> enabledExtensions;

    @Inject(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwGetRequiredInstanceExtensions()Lorg/lwjgl/PointerBuffer;",
            shift = At.Shift.BEFORE
        )
    )
    private void nvidiaDlss$enableInstanceExtensions(
        int debugVerbosity,
        boolean wantsDebugLabels,
        boolean validation,
        CallbackInfo ci,
        @Local(name = "availableExtensions") Set<String> availableExtensions
    ) {
        DlssBootstrap.configureInstanceExtensions(
            availableExtensions,
            this.enabledExtensions
        );
    }
}
