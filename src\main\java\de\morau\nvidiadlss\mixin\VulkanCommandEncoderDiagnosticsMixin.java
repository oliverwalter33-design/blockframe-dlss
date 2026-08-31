package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Developer-only graphics-submit diagnostics. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderDiagnosticsMixin {
    @Shadow private long currentSubmitIndex;
    @Shadow private long completedSubmitIndex;

    @WrapOperation(
        method = "submit",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;close()V"
        )
    )
    private void blockframe$traceGraphicsSubmit(
        VulkanQueue.Submission submission,
        Operation<Void> original
    ) {
        var zone = GpuPassDiagnostics.beginCpuTracyZone(
            GpuPassIdentity.GRAPHICS_SUBMIT
        );
        try {
            original.call(submission);
        } finally {
            GpuPassDiagnostics.closeCpuTracyZone(zone);
        }
    }

    @Inject(
        method = "submit",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;close()V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void blockframe$recordSubmit(CallbackInfo ci) {
        try {
            BlockframeRuntime.recordVulkanSubmit(
                this.currentSubmitIndex
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Optional diagnostics must not interrupt Mojang's submit state.
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void blockframe$recordCompletion(CallbackInfo ci) {
        try {
            BlockframeRuntime.recordVulkanCompletion(
                this.completedSubmitIndex
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Optional diagnostics must not affect frame submission.
        }
    }
}
