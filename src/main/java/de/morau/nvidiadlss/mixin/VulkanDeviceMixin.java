package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendFoundation;
import de.morau.nvidiadlss.DlssBootstrap;
import de.morau.nvidiadlss.DlssRenderer;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Productive Vulkan/DLSS lifecycle only. */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Unique
    private boolean blockframe$dlssCloseStarted;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void nvidiaDlss$connectStreamline(
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
        try {
            BlockframeRuntime.vulkanDeviceConnected(device);
            NativeTerrainBackendFoundation.vulkanDeviceConnected(device);
        } catch (Throwable error) {
            de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                "BlockFrame runtime initialization is unavailable for this Vulkan device",
                error
            );
        }
        DlssBootstrap.connectDevice(device);
        BlockframeRuntime.publishDlssConnection(
            DlssBootstrap.connectedTo(device),
            DlssBootstrap.capabilityReason()
        );
        if (DlssBootstrap.connectedTo(device)) {
            DlssRenderer.deviceConnected(device);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void blockframe$prepareDeviceClose(CallbackInfo ci) {
        VulkanDevice device = (VulkanDevice)(Object)this;
        if (!NativeTerrainBackendFoundation.deviceClosing(device)) {
            de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                "Native terrain Foundation B device cleanup remained in flight; the backend stays fail-closed"
            );
        }
        try {
            BlockframeRuntime.vulkanDeviceClosing(device);
        } catch (Throwable error) {
            de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                "BlockFrame Vulkan lifecycle close preparation failed",
                error
            );
        }
        if (DlssBootstrap.connectedTo(device)) {
            this.blockframe$dlssCloseStarted = true;
            try {
                if (!DlssRenderer.prepareDeviceClose()) {
                    de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                        "DLSS resources remained active before the encoder drain"
                    );
                }
            } catch (Throwable error) {
                de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                    "DLSS resources could not be queued before the encoder drain",
                    error
                );
            }
        }
    }

    @Inject(
        method = "close",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;destroy()V",
            shift = At.Shift.AFTER
        )
    )
    private void blockframe$finishDeviceClose(CallbackInfo ci) {
        if (this.blockframe$dlssCloseStarted) {
            try {
                if (!DlssRenderer.finishDeviceCloseAfterEncoderDrain()) {
                    de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                        "DLSS raw resources remained active after the encoder drain"
                    );
                }
            } catch (Throwable error) {
                de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                    "DLSS raw resources could not close after the encoder drain",
                    error
                );
            }
        }
        try {
            BlockframeRuntime
                .completeVulkanRetirementsAfterEncoderDrain();
        } catch (Throwable error) {
            de.morau.nvidiadlss.NvidiaDlssMod.LOGGER.warn(
                "BlockFrame Vulkan retirement did not complete after the encoder drain",
                error
            );
        }
    }

    @WrapMethod(method = "close")
    private void blockframe$sealClosedDeviceOwners(
        Operation<Void> original
    ) {
        try {
            original.call();
        } finally {
            DlssRenderer.deviceClosed((VulkanDevice)(Object)this);
        }
    }
}
