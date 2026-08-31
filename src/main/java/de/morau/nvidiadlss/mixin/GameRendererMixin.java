package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.state.FeatureId;
import de.morau.nvidiadlss.DlssRenderer;
import de.morau.nvidiadlss.NativeBlockOutlinePoseStackScratch;
import de.morau.nvidiadlss.NvidiaDlssMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow @Final private ProjectionMatrixBuffer levelProjectionMatrixBuffer;
    private final SubmitNodeStorage nvidiaDlss$nativeBlockOutline = new SubmitNodeStorage();
    private NativeBlockOutlinePoseStackScratch nvidiaDlss$nativeBlockOutlinePoseScratch;

    @WrapMethod(method = "render")
    private void blockframe$guardMeasuredFrame(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        Operation<Void> original
    ) {
        boolean originalReturnedNormally = false;
        boolean finalizationSucceeded = true;
        BlockframeRuntime.beginFrame();
        try {
            original.call(deltaTracker, advanceGameTime);
            originalReturnedNormally = true;
        } finally {
            try {
                this.mainRenderTarget = DlssRenderer.restoreOriginalTarget(
                    this.mainRenderTarget
                );
            } catch (Throwable error) {
                finalizationSucceeded = false;
                NvidiaDlssMod.LOGGER.warn(
                    "Minecraft-Hauptziel konnte nach dem Frame nicht wiederhergestellt werden",
                    error
                );
            }
            try {
                BlockframeRuntime.endFrame();
            } catch (Throwable error) {
                finalizationSucceeded = false;
                NvidiaDlssMod.LOGGER.warn(
                    "BlockFrame frame lifecycle could not finish cleanly",
                    error
                );
            }
            if (
                originalReturnedNormally
                    && finalizationSucceeded
                    && this.minecraft.isGameLoadFinished()
                    && advanceGameTime
                    && this.minecraft.level != null
            ) {
                BlockframeRuntime.recordSuccessfulWorldFrame(
                    this.minecraft.level
                );
            } else if (
                !originalReturnedNormally || !finalizationSucceeded
            ) {
                BlockframeRuntime.recordFailedWorldFrame();
            } else if (this.minecraft.level == null) {
                BlockframeRuntime.worldUnavailable();
            }
        }
    }

    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;", ordinal = 0)
    )
    private void nvidiaDlss$useLowResolutionWorldTarget(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        boolean renderLevel = this.minecraft.isGameLoadFinished()
            && advanceGameTime
            && this.minecraft.level != null
            && this.minecraft.windowSurface().isAcquired();
        this.mainRenderTarget = DlssRenderer.beginFrame(
            this.mainRenderTarget,
            this.gameRenderState.levelRenderState.cameraRenderState,
            renderLevel
        );
    }

    @ModifyArg(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
        index = 0
    )
    private Matrix4f nvidiaDlss$applyWorldJitter(Matrix4f projection) {
        return DlssRenderer.applyWorldJitter(projection);
    }

    @ModifyArg(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
        index = 2
    )
    private boolean nvidiaDlss$deferBlockOutline(boolean renderOutline) {
        return DlssRenderer.deferBlockOutline(renderOutline);
    }

    @Inject(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V", shift = At.Shift.BEFORE)
    )
    private void nvidiaDlss$evaluateAfterWorldBeforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        this.mainRenderTarget = DlssRenderer.finishWorldFrame(deltaTracker);
        if (DlssRenderer.consumeDeferredBlockOutline()) {
            this.nvidiaDlss$renderNativeBlockOutline();
        }
    }

    private void nvidiaDlss$renderNativeBlockOutline() {
        Matrix4f projection = DlssRenderer.nativeOverlayProjection();
        if (projection == null || this.minecraft.levelRenderer == null) return;
        if (!DlssRenderer.nativeOutlineDepthReady()) {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(this.mainRenderTarget.getDepthTexture(), 0.0);
        }
        RenderSystem.setProjectionMatrix(this.levelProjectionMatrixBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
        CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.mul(cameraState.viewRotationMatrix);
        DlssRenderer.beginNativeBlockOutlinePass();
        try {
            NativeBlockOutlinePoseStackScratch scratch = null;
            PoseStack outlinePose;
            if (
                BlockframeRuntime.featureEnabled(
                    FeatureId.OUTLINE_POSE_REUSE
                )
            ) {
                scratch =
                    this.nvidiaDlss$nativeBlockOutlinePoseScratch;
                if (scratch == null) {
                    scratch = NativeBlockOutlinePoseStackScratch
                        .createForCurrentThread();
                    this.nvidiaDlss$nativeBlockOutlinePoseScratch =
                        scratch;
                }
                BlockframeRuntime.featureBecameEffective(
                    FeatureId.OUTLINE_POSE_REUSE,
                    "render-thread-pose-stack-reuse-active"
                );
                outlinePose = scratch.beginUse();
            } else {
                outlinePose =
                    nvidiaDlss$createFreshBlockOutlinePose();
            }
            boolean submissionCompleted = false;
            try {
                ((LevelRendererAccessor)(Object)this.minecraft.levelRenderer)
                    .nvidiaDlss$submitBlockOutline(
                        outlinePose,
                        this.nvidiaDlss$nativeBlockOutline,
                        this.gameRenderState.levelRenderState
                );
                submissionCompleted = true;
            } finally {
                if (scratch != null) {
                    scratch.endUse(outlinePose, submissionCompleted);
                    DlssRenderer.recordNativeOutlinePoseStackScratch(
                        scratch
                    );
                }
            }
            this.featureRenderDispatcher.renderAllFeatures(this.nvidiaDlss$nativeBlockOutline);
            DlssRenderer.confirmNativeBlockOutline();
        } finally {
            DlssRenderer.endNativeBlockOutlinePass();
            modelViewStack.popMatrix();
        }
    }

    private static PoseStack nvidiaDlss$createFreshBlockOutlinePose() {
        return new PoseStack();
    }

    @Inject(method = {"resetData", "resize"}, at = @At("HEAD"))
    private void nvidiaDlss$resetHistoryForRendererChange(CallbackInfo ci) {
        this.nvidiaDlss$clearNativeBlockOutlinePoseScratch();
        DlssRenderer.requestReset("Ressourcen-/Pipeline-Neuladen");
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void nvidiaDlss$close(CallbackInfo ci) {
        this.nvidiaDlss$clearNativeBlockOutlinePoseScratch();
    }

    private void nvidiaDlss$clearNativeBlockOutlinePoseScratch() {
        NativeBlockOutlinePoseStackScratch scratch =
            this.nvidiaDlss$nativeBlockOutlinePoseScratch;
        this.nvidiaDlss$nativeBlockOutlinePoseScratch = null;
        if (scratch != null) {
            scratch.clear();
            DlssRenderer.recordNativeOutlinePoseStackScratch(scratch);
        }
    }

}
