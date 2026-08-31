package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.nvidiadlss.NvidiaDlssMod;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Device telemetry callbacks mixed only in developer processes. */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceDiagnosticsMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void blockframe$connectDiagnostics(
        ShaderSource shaderSource,
        VulkanInstance instance,
        VulkanPhysicalDevice physicalDevice,
        Set<String> enabledExtensions,
        VkDevice vkDevice,
        long vma,
        CheckpointExtension checkpointExtension,
        CallbackInfo ci
    ) {
        VulkanDevice device = (VulkanDevice)(Object)this;
        GpuPassDiagnostics.labelBorrowedQueues(device);
        var fault = BlockframeRuntime.engine().deviceFaultSnapshot();
        NvidiaDlssMod.LOGGER.info(
            "Vulkan Device Fault generation {}: requested={} "
                + "extension-supported={} feature-supported={} "
                + "enabled={} function-resolved={} capture-status={} "
                + "unavailable-reason={}",
            fault.generation(),
            fault.requested(),
            fault.extensionSupported(),
            fault.featureSupported(),
            fault.enabled(),
            fault.functionResolved(),
            fault.captureStatus(),
            fault.unavailableReason().isBlank()
                ? "none"
                : fault.unavailableReason()
        );
    }

    @Inject(
        method = "close",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;destroy()V",
            shift = At.Shift.AFTER
        )
    )
    private void blockframe$finishDiagnosticsClose(CallbackInfo ci) {
        try {
            BlockframeRuntime
                .vulkanEncoderDestroyedWithoutCompletionProof();
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "BlockFrame GPU breadcrumbs could not finalize after encoder destruction",
                error
            );
        }
    }
}
