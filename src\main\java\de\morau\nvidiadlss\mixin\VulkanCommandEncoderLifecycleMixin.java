package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import de.morau.nvidiadlss.DlssBootstrap;
import de.morau.nvidiadlss.DlssRenderer;
import de.morau.nvidiadlss.NvidiaDlssMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Productive Streamline shutdown ordering; never a diagnostics hook. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderLifecycleMixin {
    @Shadow @Final private VulkanDevice device;

    @Inject(
        method = "destroy",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanQueue;waitIdle()V",
            shift = At.Shift.AFTER
        )
    )
    private void blockframe$releaseStreamlineBeforeResourceDestroy(
        CallbackInfo ci
    ) {
        if (!DlssBootstrap.connectedTo(this.device)) {
            return;
        }
        try {
            if (
                !DlssRenderer
                    .releaseStreamlineAfterQueueDrainBeforeResourceDestroy(
                        this.device
                    )
            ) {
                NvidiaDlssMod.LOGGER.warn(
                    "Streamline remained active before the Vulkan destruction-queue drain"
                );
            }
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "Streamline shutdown failed in the safe Vulkan drain window",
                error
            );
        }
    }
}
