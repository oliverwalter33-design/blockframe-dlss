package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development-only observation of Mojang's existing Vulkan present owner.
 *
 * <p>The before anchor is Mojang's final state write before the existing
 * present call. The result-variable anchor is the first bytecode operation
 * after that call returns. Neither injection replaces, repeats, waits for, or
 * otherwise changes the present operation.</p>
 */
@Mixin(VulkanGpuSurface.class)
abstract class Phase2a0cVulkanGpuSurfaceMixin {
    @Unique
    private static long blockframe$phase2a0cSurfaceCounter;
    @Unique
    private static long blockframe$phase2a0cDeviceGenerationCounter;
    @Unique
    private static int blockframe$phase2a0cLastDeviceIdentity;

    @Unique
    private long blockframe$phase2a0cSurfaceGeneration;
    @Unique
    private long blockframe$phase2a0cDeviceGeneration;
    @Unique
    private int blockframe$phase2a0cDeviceIdentity;
    @Unique
    private long blockframe$phase2a0cGlfwWindowPointer;
    @Unique
    private long blockframe$phase2a0cWin32Hwnd;
    @Unique
    private long blockframe$phase2a0cWindowProcessId;
    @Unique
    private boolean blockframe$phase2a0cWindowIdentityValid;
    @Unique
    private long blockframe$phase2a0cSwapchainGeneration;
    @Unique
    private int blockframe$phase2a0cFramebufferWidth;
    @Unique
    private int blockframe$phase2a0cFramebufferHeight;
    @Unique
    private int blockframe$phase2a0cPresentMode = -1;
    @Unique
    private long blockframe$phase2a0cPresentBeforeNanos;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void blockframe$phase2a0cConstructed(
        VulkanDevice device,
        long windowHandle,
        CallbackInfo callback
    ) {
        blockframe$phase2a0cSurfaceGeneration =
            ++blockframe$phase2a0cSurfaceCounter;
        blockframe$phase2a0cDeviceIdentity =
            System.identityHashCode(device);
        if (
            blockframe$phase2a0cDeviceGenerationCounter == 0L
                || blockframe$phase2a0cLastDeviceIdentity
                    != blockframe$phase2a0cDeviceIdentity
        ) {
            blockframe$phase2a0cLastDeviceIdentity =
                blockframe$phase2a0cDeviceIdentity;
            blockframe$phase2a0cDeviceGenerationCounter++;
        }
        blockframe$phase2a0cDeviceGeneration =
            blockframe$phase2a0cDeviceGenerationCounter;
        Phase2a0cCaptureRuntime.WindowIdentity windowIdentity =
            Phase2a0cCaptureRuntime.resolveWindowIdentity(windowHandle);
        blockframe$phase2a0cGlfwWindowPointer =
            windowIdentity.glfwWindowPointer();
        blockframe$phase2a0cWin32Hwnd = windowIdentity.win32Hwnd();
        blockframe$phase2a0cWindowProcessId =
            windowIdentity.ownerProcessId();
        blockframe$phase2a0cWindowIdentityValid =
            windowIdentity.valid();
    }

    @Inject(method = "configure", at = @At("RETURN"))
    private void blockframe$phase2a0cConfigured(
        GpuSurface.Configuration configuration,
        CallbackInfo callback
    ) {
        blockframe$phase2a0cSwapchainGeneration++;
        blockframe$phase2a0cFramebufferWidth = configuration.width();
        blockframe$phase2a0cFramebufferHeight = configuration.height();
        blockframe$phase2a0cPresentMode = VulkanConst.toVk(
            configuration.presentMode()
        );
    }

    @Inject(
        method = "present",
        at = @At(
            value = "FIELD",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanGpuSurface;"
                + "currentImageIndex:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        )
    )
    private void blockframe$phase2a0cImmediatelyBeforePresent(
        CallbackInfo callback
    ) {
        blockframe$phase2a0cPresentBeforeNanos = System.nanoTime();
    }

    @ModifyVariable(method = "present", at = @At("STORE"), ordinal = 0)
    private int blockframe$phase2a0cImmediatelyAfterPresent(int result) {
        long afterNanos = System.nanoTime();
        Phase2a0cCaptureRuntime.onVulkanPresent(
            blockframe$phase2a0cSurfaceGeneration,
            blockframe$phase2a0cDeviceGeneration,
            blockframe$phase2a0cDeviceIdentity,
            blockframe$phase2a0cGlfwWindowPointer,
            blockframe$phase2a0cWin32Hwnd,
            blockframe$phase2a0cWindowProcessId,
            blockframe$phase2a0cWindowIdentityValid,
            blockframe$phase2a0cSwapchainGeneration,
            blockframe$phase2a0cFramebufferWidth,
            blockframe$phase2a0cFramebufferHeight,
            blockframe$phase2a0cPresentMode,
            blockframe$phase2a0cPresentBeforeNanos,
            afterNanos,
            result
        );
        return result;
    }
}
