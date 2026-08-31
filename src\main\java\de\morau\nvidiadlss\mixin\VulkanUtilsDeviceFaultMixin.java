package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.nvidiadlss.NvidiaDlssMod;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes Mojang's existing fatal device-loss path without changing it.
 */
@Mixin(VulkanUtils.class)
public abstract class VulkanUtilsDeviceFaultMixin {
    @Inject(
        method = "crashIfFailure(Lcom/mojang/blaze3d/vulkan/VulkanDevice;ILjava/lang/String;)V",
        at = @At("HEAD"),
        require = 0
    )
    private static void blockframe$captureDeviceFault(
        VulkanDevice device,
        int result,
        String message,
        CallbackInfo ci
    ) {
        if (result != VK10.VK_ERROR_DEVICE_LOST) {
            return;
        }
        try {
            var fault = BlockframeRuntime.recordVulkanDeviceLost(
                device,
                result,
                message
            );
            NvidiaDlssMod.LOGGER.error(
                "Vulkan Device Fault capture-status={} reason={} "
                    + "description={} address={}/{} vendor={}/{} "
                    + "vendor-binary-reported={} bytes truncated={}",
                fault.captureStatus(),
                fault.unavailableReason().isBlank()
                    ? "none"
                    : fault.unavailableReason(),
                fault.description(),
                fault.addressInfos().size(),
                fault.addressInfoCountReported(),
                fault.vendorInfos().size(),
                fault.vendorInfoCountReported(),
                Long.toUnsignedString(
                    fault.vendorBinaryBytesReported()
                ),
                fault.truncated()
            );
        } catch (Throwable error) {
            try {
                NvidiaDlssMod.LOGGER.warn(
                    "Optionale Vulkan-Device-Fault-Diagnose ist im "
                        + "bestehenden Device-Loss-Pfad fehlgeschlagen",
                    error
                );
            } catch (Throwable ignored) {
                // Mojang's original checkpoint/exception path must continue.
            }
        }
    }
}
