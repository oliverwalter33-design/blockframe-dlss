package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.jtracy.Zone;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassIdentity;
import de.morau.nvidiadlss.DlssDebugCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture and frame-timing callbacks mixed only in developer processes. */
@Mixin(GameRenderer.class)
public abstract class GameRendererDiagnosticsMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    private Zone blockframe$tracyZone;

    @WrapMethod(method = "render")
    private void blockframe$measureFrame(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        Operation<Void> original
    ) {
        this.blockframe$closeTracyZone();
        if (BlockframeRuntime.engine().profilerFrameOpen()) {
            this.blockframe$tracyZone =
                GpuPassDiagnostics.beginCpuTracyZone(
                    GpuPassIdentity.FRAME
                );
        }
        try {
            original.call(deltaTracker, advanceGameTime);
        } finally {
            this.blockframe$closeTracyZone();
        }
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V",
            shift = At.Shift.BEFORE
        )
    )
    private void blockframe$recordVisibleSections(
        DeltaTracker deltaTracker,
        CallbackInfo ci
    ) {
        if (this.minecraft.levelRenderer != null) {
            BlockframeRuntime.recordVisibleSections(
                this.minecraft.levelRenderer.visibleSections().size()
            );
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void blockframe$captureFinalWithoutHud(
        DeltaTracker deltaTracker,
        CallbackInfo ci
    ) {
        DlssDebugCapture.captureFinalWithoutHud(
            this.mainRenderTarget.getColorTexture(),
            this.mainRenderTarget.width,
            this.mainRenderTarget.height
        );
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void blockframe$captureFinalWithHud(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        CallbackInfo ci
    ) {
        DlssDebugCapture.captureFinalWithHud(
            this.mainRenderTarget.getColorTexture(),
            this.mainRenderTarget.width,
            this.mainRenderTarget.height
        );
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void blockframe$closeDiagnostics(CallbackInfo ci) {
        this.blockframe$closeTracyZone();
    }

    private void blockframe$closeTracyZone() {
        Zone zone = this.blockframe$tracyZone;
        this.blockframe$tracyZone = null;
        GpuPassDiagnostics.closeCpuTracyZone(zone);
    }
}
