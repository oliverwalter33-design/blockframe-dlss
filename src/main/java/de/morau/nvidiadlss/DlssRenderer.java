package de.morau.nvidiadlss;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TracyGpuProfiler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDebug;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.core.diagnostics.GpuPassDiagnostics;
import de.morau.blockframe.core.diagnostics.GpuPassIdentity;
import de.morau.blockframe.core.state.FeatureId;
import de.morau.nvidiadlss.mixin.CommandEncoderAccessor;
import de.morau.nvidiadlss.mixin.VulkanCommandEncoderAccessor;
import de.morau.nvidiadlss.nativebridge.NativeStreamline;
import de.morau.nvidiadlss.nativebridge.StreamlineEvaluationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

public final class DlssRenderer {
    private static final int MOTION_HISTORY_CAPACITY = 65_536;
    private static final int MOTION_HISTORY_OVERFLOW = -1;
    public static final int STORAGE_USAGE = 32;
    private static MainTarget lowTarget;
    private static DlssAuxiliaryResources auxiliaryResources;
    private static MotionVectorGenerator motionGenerator;
    private static RenderTarget originalTarget;
    private static CameraRenderState cameraState;
    private static Matrix4f unjitteredProjection;
    private static Matrix4f previousViewProjection;
    private static Matrix4f previousFallbackProjection;
    private static Matrix4f previousFallbackViewRotation;
    private static double previousFallbackCameraX;
    private static double previousFallbackCameraY;
    private static double previousFallbackCameraZ;
    private static boolean previousFallbackTransformValid;
    private static DlssTransformScratch transformScratch;
    private static boolean transformScratchCreationAttempted;
    private static boolean transformScratchDisabled;
    private static boolean lastTransformScratchPath;
    private static long transformScratchFallbackFrames;
    private static String transformScratchStatus = "not attempted";
    private static int lowWidth, lowHeight, outputWidth, outputHeight;
    private static int frameIndex;
    private static int jitterPhaseCount = 8;
    private static float jitterX, jitterY, previousJitterX, previousJitterY;
    private static boolean active;
    private static boolean worldPass;
    private static boolean wasActive;
    private static boolean resetRequested = true;
    private static boolean optimalSettingsRefreshRequested = true;
    private static String resetReason = "Initialisierung";
    private static boolean evaluationLogged;
    private static DlssMode allocatedMode = DlssMode.OFF;
    private static DlssMode requestedMode = DlssMode.OFF;
    private static DlssMode lastFallbackRequest = DlssMode.OFF;
    private static int lastFallbackHeight = -1;
    private static Object previousLevel;
    private static Object previousCameraEntity;
    private static Vec3 previousCameraPosition;
    private static Quaternionf previousCameraOrientation;
    private static float previousEffectiveFovRadians = Float.NaN;
    private static boolean previousCameraDeadOrDying;
    private static boolean previousCameraDeadOrDyingValid;
    private static int previousFovSetting = -1;
    private static int previousCameraType = -1;
    private static int previousGuiScale = -1;
    private static boolean previousVrState;
    private static Map<Integer, EntityFrame> previousEntities = Map.of();
    private static int lastEvaluationCode = Integer.MIN_VALUE;
    private static boolean lastEvaluationActive;
    private static int lastSharpeningCode =
        StreamlineEvaluationResult.NIS_NOT_REQUESTED;
    private static boolean lastSharpeningActive;
    private static int loggedSharpeningFailureCode =
        StreamlineEvaluationResult.NIS_NOT_REQUESTED;
    private static boolean lastReset;
    private static int lastMotionObjectCount;
    private static MotionObjectBatch motionObjectBatch;
    private static EntityMotionHistory entityMotionHistory;
    private static boolean motionObjectBatchCreationAttempted;
    private static boolean motionScratchDisabled;
    private static boolean lastMotionObjectBatchPath;
    private static long motionObjectFallbackFrames;
    private static String entityMotionHistoryStatus = "not requested";
    private static float lastAppliedLodBias;
    private static boolean deferredBlockOutline;
    private static boolean nativeOutlineDepthReady;
    private static boolean nativeBlockOutlinePass;
    private static boolean nativeOutlineLogged;
    private static boolean nativeOutlineDepthWarningLogged;
    private static int nativeOutlinePoseScratchStatus;
    private static long nativeOutlinePoseScratchReuseUses;
    private static long nativeOutlinePoseScratchFreshFallbacks;
    private static long nativeOutlinePoseScratchDisables;
    private static long nativeOutlinePoseScratchReentrantFallbacks;
    private static long nativeOutlinePoseScratchImbalanceDisables;
    private static long nativeOutlinePoseScratchUnwoundPoses;
    private static final float[] PROJECTION_ROW_MAJOR = new float[16];
    private static final float[] INVERSE_PROJECTION_ROW_MAJOR = new float[16];
    private static final float[] CLIP_TO_PREVIOUS_ROW_MAJOR = new float[16];
    private static final float[] PREVIOUS_TO_CLIP_ROW_MAJOR = new float[16];
    private static final float[] MATRIX_JSON_SCRATCH = new float[16];
    private static DlssMode failedResourceMode = DlssMode.OFF;
    private static int failedResourceWidth = -1;
    private static int failedResourceHeight = -1;
    private static long resourceRetryAfterNanos;
    private static VulkanDevice lifecycleDevice;
    private static boolean samplerClosePrepared;
    private static boolean samplerCloseFinished;
    private static boolean targetClosePrepared;
    private static boolean auxiliaryClosePrepared;
    private static boolean auxiliaryLeaseRetained;
    private static boolean motionClosePrepared;
    private static boolean motionCloseFinished;
    private static boolean streamlineCloseFinished;
    private static boolean retirementCloseFinished;
    private static boolean deviceCloseStarted;
    private static boolean deviceClosePrepared;
    private static boolean deviceCloseFinished;
    private static boolean deviceGenerationBlocked;
    private static final DlssDeviceCleanupProof DEVICE_CLEANUP_PROOF =
        new DlssDeviceCleanupProof();

    private DlssRenderer() {}

    public static void requestReset() { requestReset("extern angefordert"); }

    public static void requestReset(String reason) {
        resetRequested = true;
        resetReason = reason == null ? "unbekannt" : reason;
        ThirdPersonGeometryMotion.resetHistory();
    }

    static void requestOptimalSettingsRefresh(String reason) {
        optimalSettingsRefreshRequested = true;
        requestReset(reason);
    }

    static void requestWorldHistoryReset(String reason) {
        requestReset(reason);
        clearMotionObjectHistory();
        previousLevel = null;
        previousCameraEntity = null;
        previousCameraPosition = null;
        previousCameraOrientation = null;
        previousEffectiveFovRadians = Float.NaN;
        previousCameraDeadOrDying = false;
        previousCameraDeadOrDyingValid = false;
        previousFovSetting = -1;
        previousCameraType = -1;
        previousGuiScale = -1;
        previousVrState = false;
    }

    public static boolean isWorldPass() { return worldPass; }

    /** Keeps the one-pixel selection line out of temporal reconstruction. */
    public static boolean deferBlockOutline(boolean renderOutline) {
        deferredBlockOutline = active && renderOutline;
        return deferredBlockOutline ? false : renderOutline;
    }

    public static boolean consumeDeferredBlockOutline() {
        boolean result = deferredBlockOutline;
        deferredBlockOutline = false;
        return result;
    }

    public static Matrix4f nativeOverlayProjection() {
        DlssTransformScratch scratch = activeTransformScratch();
        if (scratch != null) {
            try {
                return scratch.copyProjectionForOverlay();
            } catch (Throwable error) {
                disableTransformScratch(error);
            }
        }
        return unjitteredProjection == null
            ? null
            : new Matrix4f(unjitteredProjection);
    }

    public static boolean nativeOutlineDepthReady() { return nativeOutlineDepthReady; }

    public static boolean isNativeBlockOutlinePass() { return nativeBlockOutlinePass; }

    public static void beginNativeBlockOutlinePass() { nativeBlockOutlinePass = true; }

    public static void endNativeBlockOutlinePass() { nativeBlockOutlinePass = false; }

    /**
     * Copies primitive evidence from the renderer-owned outline scratch.
     * String formatting remains confined to the explicit F8 extraction path.
     */
    public static void recordNativeOutlinePoseStackScratch(
        NativeBlockOutlinePoseStackScratch scratch
    ) {
        nativeOutlinePoseScratchStatus = scratch.status();
        nativeOutlinePoseScratchReuseUses = scratch.reuseUses();
        nativeOutlinePoseScratchFreshFallbacks = scratch.freshFallbacks();
        nativeOutlinePoseScratchDisables = scratch.disableCount();
        nativeOutlinePoseScratchReentrantFallbacks =
            scratch.reentrantFallbacks();
        nativeOutlinePoseScratchImbalanceDisables =
            scratch.imbalanceDisables();
        nativeOutlinePoseScratchUnwoundPoses = scratch.unwoundPoses();
    }

    public static void confirmNativeBlockOutline() {
        if (!nativeOutlineLogged) {
            nativeOutlineLogged = true;
            NvidiaDlssMod.LOGGER.info("[DLSS self-test] PASS: Block-Outline post-temporal nativ {}x{}; 2.0 px; Alpha 230; Tiefe {}",
                outputWidth, outputHeight,
                lowWidth == outputWidth && lowHeight == outputHeight ? "1:1 kopiert" : "NEAREST skaliert");
        }
    }

    public static float currentLodBias() {
        if (!worldPass || outputWidth <= 0 || lowWidth <= 0) return 0.0F;
        return currentLodBiasForDimensions();
    }

    public static RenderTarget beginFrame(RenderTarget highTarget, CameraRenderState state, boolean shouldRenderLevel) {
        if (DeveloperDiagnostics.ENABLED) {
            ThirdPersonDlaaSequenceDriver.update(frameIndex);
        }
        active = false;
        worldPass = false;
        originalTarget = highTarget;
        cameraState = state;
        unjitteredProjection = null;
        deferredBlockOutline = false;
        nativeOutlineDepthReady = false;
        nativeBlockOutlinePass = false;
        requestedMode = DlssConfig.mode();
        DlssMode mode = BlockframeRuntime.featureEnabled(
            FeatureId.DLSS_MODE
        )
            ? requestedMode
            : DlssMode.OFF;
        boolean vrRunning = VivecraftCompat.isVrRunning();
        if (
            deviceCloseStarted
                || !shouldRenderLevel
                || mode == DlssMode.OFF
                || !DlssBootstrap.connected()
                || !DlssStatus.ready()
                || vrRunning
        ) {
            ThirdPersonGeometryMotion.suspend();
            if (wasActive) requestReset(!shouldRenderLevel ? "unterbrochene Rendersequenz" : vrRunning ? "VR-Übergang" : "DLSS deaktiviert");
            wasActive = false;
            return highTarget;
        }
        if (!(highTarget.getColorTexture() instanceof VulkanGpuTexture) || !(highTarget.getDepthTexture() instanceof VulkanGpuTexture)) {
            DlssStatus.unavailable("Das aktive Grafik-Backend ist nicht Vulkan");
            return highTarget;
        }
        try {
            mode = effectiveMode(requestedMode, highTarget.height);
            if (mode != requestedMode && (lastFallbackRequest != requestedMode || lastFallbackHeight != highTarget.height)) {
                NvidiaDlssMod.LOGGER.warn("DLSS Ultra Performance ist unter 3840x2160 wegen Vegetationsflimmern gesperrt; Quality wird verwendet (Ausgabe {}x{})",
                    highTarget.width, highTarget.height);
                lastFallbackRequest = requestedMode;
                lastFallbackHeight = highTarget.height;
            } else if (mode == requestedMode) {
                lastFallbackRequest = DlssMode.OFF;
                lastFallbackHeight = -1;
            }
            if (
                mode == failedResourceMode
                    && highTarget.width == failedResourceWidth
                    && highTarget.height == failedResourceHeight
                    && System.nanoTime() < resourceRetryAfterNanos
            ) {
                return highTarget;
            }
            ensureResources(mode, highTarget.width, highTarget.height);
            if (
                DeveloperDiagnostics.ENABLED
                    && Boolean.getBoolean("nvidia_dlss.devForceThirdPerson")
            ) {
                Minecraft.getInstance().options.setCameraType(
                    CameraType.THIRD_PERSON_BACK
                );
            }
            failedResourceMode = DlssMode.OFF;
            failedResourceWidth = -1;
            failedResourceHeight = -1;
            resourceRetryAfterNanos = 0L;
            beginTransformFrame();
            detectHistoryBreaks(state, vrRunning);
            updateJitter();
            active = true;
            worldPass = true;
            wasActive = true;
            ThirdPersonGeometryMotion.beginFrame(
                frameIndex,
                state.pos.x,
                state.pos.y,
                state.pos.z
            );
            BlockframeRuntime.featureBecameEffective(
                FeatureId.DLSS_MODE,
                "low-resolution-world-target-active"
            );
            return lowTarget;
        } catch (Throwable error) {
            ThirdPersonGeometryMotion.suspend();
            failedResourceMode = mode;
            failedResourceWidth = highTarget.width;
            failedResourceHeight = highTarget.height;
            resourceRetryAfterNanos = System.nanoTime() + 1_000_000_000L;
            DlssStatus.error("Renderziele: " + safeMessage(error));
            NvidiaDlssMod.LOGGER.error("DLSS-Renderziele konnten nicht erstellt werden", error);
            requestReset("Fehler beim Erstellen der Renderziele");
            BlockframeRuntime.featureUsedFallback(
                FeatureId.DLSS_MODE,
                true,
                false,
                "render-target-creation-fallback"
            );
            return highTarget;
        }
    }

    /** Called with the exact world projection after view bob / portal transforms and before upload. */
    public static Matrix4f applyWorldJitter(Matrix4f projection) {
        if (!active) return projection;
        observeEffectiveWorldFov(projection.m11());
        DlssTransformScratch scratch = activeTransformScratch();
        if (scratch != null) {
            try {
                scratch.captureUnjitteredProjection(projection);
            } catch (Throwable error) {
                disableTransformScratch(error);
                unjitteredProjection = new Matrix4f(projection);
            }
        } else {
            unjitteredProjection = new Matrix4f(projection);
        }
        projection.m20(projection.m20() - 2.0F * jitterX / lowWidth);
        projection.m21(projection.m21() - 2.0F * jitterY / lowHeight);
        return projection;
    }

    private static void observeEffectiveWorldFov(float projectionM11) {
        float effectiveFovRadians =
            TemporalResetPolicy.effectiveVerticalFovRadians(
                projectionM11
            );
        if (
            TemporalResetPolicy.effectiveFovCut(
                previousEffectiveFovRadians,
                effectiveFovRadians
            )
        ) {
            requestReset("effektiver FOV-Sprung");
        }
        previousEffectiveFovRadians = effectiveFovRadians;
    }

    private static void ensureResources(DlssMode mode, int width, int height) {
        boolean currentResourcesMatchOutput =
            lowTarget != null
                && auxiliaryResources != null
                && auxiliaryResources.complete()
                && lowTarget.width == lowWidth
                && lowTarget.height == lowHeight
                && width == outputWidth
                && height == outputHeight
                && mode == allocatedMode;
        // Startup, mode/output/fullscreen/window changes naturally miss this
        // fast path. Resource reloads explicitly request another SDK query.
        if (
            currentResourcesMatchOutput
                && !optimalSettingsRefreshRequested
        ) {
            if (motionGenerator == null) {
                motionGenerator = new MotionVectorGenerator(
                    DlssBootstrap.vulkanBackend()
                );
            }
            return;
        }

        long packed = NativeStreamline.optimalSize(mode.nativeId(), width, height);
        int desiredWidth = (int)(packed >>> 32);
        int desiredHeight = (int)packed;
        if (desiredWidth <= 0 || desiredHeight <= 0) {
            throw new IllegalStateException("Streamline lieferte keine gültige optimale Renderauflösung");
        }
        if (
            currentResourcesMatchOutput
                && desiredWidth == lowWidth
                && desiredHeight == lowHeight
        ) {
            optimalSettingsRefreshRequested = false;
            if (motionGenerator == null) {
                motionGenerator = new MotionVectorGenerator(
                    DlssBootstrap.vulkanBackend()
                );
            }
            logIntegrationState("Optimal-Settings erneut bestätigt");
            return;
        }
        VulkanDevice backend = DlssBootstrap.vulkanBackend();
        if (backend == null) {
            throw new IllegalStateException(
                "DLSS-Ressourcenwechsel ohne verbundenes Vulkan-Gerät abgelehnt"
            );
        }
        backend.graphicsQueue().waitIdle();
        int resetResult = NativeStreamline.resetViewport(0);
        if (resetResult != 0) {
            throw new IllegalStateException(
                "Streamline-Viewport-Bereinigung vor Ressourcenwechsel "
                    + "fehlgeschlagen: "
                    + NativeStreamline.lastMessage()
                    + " ["
                    + resetResult
                    + "]"
            );
        }

        DlssAuxiliaryResources previousResources = auxiliaryResources;
        int previousTargetWidth = lowTarget == null ? 0 : lowTarget.width;
        int previousTargetHeight = lowTarget == null ? 0 : lowTarget.height;
        DlssAuxiliaryResources replacement = DlssAuxiliaryResources.create(
            desiredWidth,
            desiredHeight,
            width,
            height
        );
        boolean targetConstructionStarted = false;
        boolean createdTarget = false;
        boolean resizedTarget = false;
        try {
            if (lowTarget == null) {
                targetConstructionStarted = true;
                lowTarget = new MainTarget(desiredWidth, desiredHeight);
                createdTarget = true;
            } else if (
                lowTarget.width != desiredWidth
                    || lowTarget.height != desiredHeight
            ) {
                // LevelRenderer/SkyRenderer cache the RenderTarget identity.
                // RenderTarget.resize destroys the old attachments first, so
                // rollback is required even when resize itself throws.
                resizedTarget = true;
                lowTarget.resize(desiredWidth, desiredHeight);
            }
            if (
                lowTarget.width != desiredWidth
                    || lowTarget.height != desiredHeight
            ) {
                throw new IllegalStateException(
                    "DLSS world target used fallback dimensions "
                        + lowTarget.width
                        + "x"
                        + lowTarget.height
                );
            }
            labelLowResolutionTarget(lowTarget);
            if (motionGenerator == null) {
                motionGenerator = new MotionVectorGenerator(
                    DlssBootstrap.vulkanBackend()
                );
            }
        } catch (Throwable error) {
            boolean retainReplacementLease =
                targetConstructionStarted
                    && !createdTarget
                    && lowTarget == null;
            if (createdTarget && lowTarget != null) {
                MainTarget failedTarget = lowTarget;
                lowTarget = null;
                try {
                    failedTarget.destroyBuffers();
                } catch (Throwable cleanupError) {
                    retainReplacementLease = true;
                    error.addSuppressed(cleanupError);
                }
            } else if (resizedTarget && lowTarget != null) {
                try {
                    lowTarget.resize(
                        previousTargetWidth,
                        previousTargetHeight
                    );
                    labelLowResolutionTarget(lowTarget);
                } catch (Throwable restoreError) {
                    error.addSuppressed(restoreError);
                    MainTarget failedTarget = lowTarget;
                    lowTarget = null;
                    try {
                        failedTarget.destroyBuffers();
                    } catch (Throwable cleanupError) {
                        retainReplacementLease = true;
                        error.addSuppressed(cleanupError);
                    }
                }
            }
            if (retainReplacementLease) {
                // MainTarget allocates attachments inside its constructor,
                // and RenderTarget cleanup may itself fail. In either case an
                // unreachable physical allocation cannot be disproved. Keep
                // the complete replacement lease accounted so diagnostics
                // surface that uncertainty as a conservative leak.
                replacement.closeRetainingLease();
            } else {
                replacement.close();
            }
            throw error;
        }

        auxiliaryResources = replacement;
        lowWidth = desiredWidth;
        lowHeight = desiredHeight;
        outputWidth = width;
        outputHeight = height;
        allocatedMode = mode;
        optimalSettingsRefreshRequested = false;
        nativeOutlineLogged = false;
        nativeOutlineDepthWarningLogged = false;
        if (previousResources != null) {
            previousResources.close();
        }
        resetTransformViewProjection();
        clearMotionObjectHistory();
        frameIndex = 0;
        requestReset("Renderziel/Modus geändert");
        NvidiaDlssMod.LOGGER.info("DLSS-Renderauflösung (offizielle Optimal Settings): {}x{} -> {}x{} ({})",
            lowWidth, lowHeight, width, height, mode);
        logIntegrationState("Renderressourcen erstellt");
    }

    private static void logIntegrationState(String reason) {
        NvidiaDlssMod.LOGGER.info(
            "BlockFrame-DLSS-IQ: reason={} requestedMode={} activeMode={} "
                + "output={}x{} render={}x{} colorInputExtent={}x{} "
                + "depthExtent={}x{} motionExtent={}x{} outputExtent={}x{} "
                + "preset={} sharpness={} mipBias={} mvecScale=[{},{}] "
                + "motionVectorsJittered=false diagnosticHintsActive={} "
                + "releaseSafeHints={} temporalPolicy={} playerMotion={}",
            reason,
            requestedMode,
            allocatedMode,
            outputWidth,
            outputHeight,
            lowWidth,
            lowHeight,
            lowWidth,
            lowHeight,
            lowWidth,
            lowHeight,
            lowWidth,
            lowHeight,
            outputWidth,
            outputHeight,
            presetFor(allocatedMode),
            decimal(DlssConfig.effectiveSharpness(allocatedMode)),
            decimal(currentLodBiasForDimensions()),
            reciprocal(lowWidth),
            reciprocal(lowHeight),
            TemporalHintAudit.active(),
            TemporalHintAudit.releaseSafe(),
            TemporalHintAudit.metadataJson(),
            ThirdPersonMotionAudit.metadataJson()
        );
    }

    private static void labelLowResolutionTarget(MainTarget target) {
        if (!DeveloperDiagnostics.ENABLED || target == null) {
            return;
        }
        try {
            VulkanDevice backend = DlssBootstrap.vulkanBackend();
            if (backend == null) {
                return;
            }
            VulkanGpuTexture color =
                (VulkanGpuTexture)target.getColorTexture();
            VulkanGpuTextureView colorView =
                (VulkanGpuTextureView)target.getColorTextureView();
            VulkanGpuTexture depth =
                (VulkanGpuTexture)target.getDepthTexture();
            VulkanGpuTextureView depthView =
                (VulkanGpuTextureView)target.getDepthTextureView();
            VulkanDebug debug = backend.instance().debug();
            var device = backend.vkDevice();
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_IMAGE,
                color.vkImage(),
                GpuPassDiagnostics.LOW_RESOLUTION_COLOR_IMAGE
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_IMAGE_VIEW,
                colorView.vkImageView(),
                GpuPassDiagnostics.LOW_RESOLUTION_COLOR_VIEW
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_IMAGE,
                depth.vkImage(),
                GpuPassDiagnostics.LOW_RESOLUTION_DEPTH_IMAGE
            );
            GpuPassDiagnostics.setObjectName(
                debug,
                device,
                VK12.VK_OBJECT_TYPE_IMAGE_VIEW,
                depthView.vkImageView(),
                GpuPassDiagnostics.LOW_RESOLUTION_DEPTH_VIEW
            );
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            // Naming never changes target publication or rollback semantics.
        }
    }

    public static RenderTarget finishWorldFrame(DeltaTracker deltaTracker) {
        if (!active) return originalTarget;
        active = false;
        worldPass = false;
        try {
            DlssAuxiliaryResources resources = auxiliaryResources;
            if (resources == null || !resources.complete()) {
                throw new IllegalStateException(
                    "DLSS auxiliary resource set is incomplete"
                );
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            CommandEncoderAccessor encoderAccess =
                (CommandEncoderAccessor)(Object)encoder;
            Object backend = encoderAccess.nvidiaDlss$backend();
            if (!(backend instanceof VulkanCommandEncoder vulkanEncoder)) throw new IllegalStateException("Kein Vulkan-CommandEncoder");
            var commandBuffer = ((VulkanCommandEncoderAccessor)(Object)vulkanEncoder).nvidiaDlss$commandBuffer();
            TracyGpuProfiler tracyGpuProfiler = null;
            try {
                tracyGpuProfiler =
                    encoderAccess.blockframe$tracyGpuProfiler();
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError ignored
            ) {
                // Optional Tracy cannot select the spatial safety fallback.
            }

            VulkanGpuTexture color = (VulkanGpuTexture)lowTarget.getColorTexture();
            VulkanGpuTextureView colorView = (VulkanGpuTextureView)lowTarget.getColorTextureView();
            VulkanGpuTexture depth = (VulkanGpuTexture)lowTarget.getDepthTexture();
            VulkanGpuTextureView depthView = (VulkanGpuTextureView)lowTarget.getDepthTextureView();
            VulkanGpuTexture motion = (VulkanGpuTexture)resources.motionTexture;
            VulkanGpuTextureView nativeMotionView = (VulkanGpuTextureView)resources.motionView;
            VulkanGpuTexture output = (VulkanGpuTexture)resources.outputTexture;
            VulkanGpuTextureView nativeOutputView = (VulkanGpuTextureView)resources.outputView;
            VulkanGpuTexture sharpen = (VulkanGpuTexture)resources.sharpenTexture;
            VulkanGpuTextureView nativeSharpenView = (VulkanGpuTextureView)resources.sharpenView;

            DlssTransformScratch frameScratch =
                activeTransformScratch();
            Matrix4f projection = null;
            Matrix4f viewProjection = null;
            Matrix4f previousVp = null;
            Matrix4f inverseViewProjection = null;
            Matrix4f clipToPrev = null;
            Matrix4f prevToClip = null;
            Matrix4f inverseProjection = null;
            Vector3f up = null;
            Vector3f right = null;
            Vector3f forward = null;
            boolean reset = false;
            boolean transformScratchFrame = false;
            if (frameScratch != null) {
                Matrix4f slabFallbackProjection = null;
                try {
                    slabFallbackProjection =
                        frameScratch.copyProjectionForOverlay();
                    reset = frameScratch.prepareCurrentTransforms(
                        cameraState.projectionMatrix,
                        cameraState.viewRotationMatrix,
                        cameraState.orientation,
                        cameraState.pos.x,
                        cameraState.pos.y,
                        cameraState.pos.z,
                        resetRequested
                    );
                    projection = frameScratch.projection();
                    viewProjection = frameScratch.viewProjection();
                    previousVp =
                        frameScratch.previousViewProjectionForFrame();
                    inverseViewProjection =
                        frameScratch.inverseViewProjection();
                    clipToPrev = frameScratch.clipToPrevious();
                    prevToClip = frameScratch.previousToClip();
                    inverseProjection =
                        frameScratch.inverseProjection();
                    up = frameScratch.up();
                    right = frameScratch.right();
                    forward = frameScratch.forward();
                    transformScratchFrame = true;
                } catch (Throwable error) {
                    if (slabFallbackProjection != null) {
                        unjitteredProjection =
                            new Matrix4f(slabFallbackProjection);
                    }
                    disableTransformScratch(error);
                    frameScratch = null;
                }
            }
            if (!transformScratchFrame) {
                projection = unjitteredProjection != null
                    ? new Matrix4f(unjitteredProjection)
                    : new Matrix4f(cameraState.projectionMatrix);
                viewProjection = new Matrix4f(projection)
                    .mul(cameraState.viewRotationMatrix)
                    .translate(
                        (float)-cameraState.pos.x,
                        (float)-cameraState.pos.y,
                        (float)-cameraState.pos.z
                    );
                reset =
                    !previousFallbackTransformValid || resetRequested;
                previousVp = previousViewProjection == null
                    ? new Matrix4f(viewProjection)
                    : new Matrix4f(previousViewProjection);
                inverseViewProjection =
                    new Matrix4f(viewProjection).invert();
                Matrix4f previousProjectionForFrame =
                    previousFallbackTransformValid
                        ? previousFallbackProjection
                        : projection;
                Matrix4f previousViewRotationForFrame =
                    previousFallbackTransformValid
                        ? previousFallbackViewRotation
                        : cameraState.viewRotationMatrix;
                double previousCameraX = previousFallbackTransformValid
                    ? previousFallbackCameraX
                    : cameraState.pos.x;
                double previousCameraY = previousFallbackTransformValid
                    ? previousFallbackCameraY
                    : cameraState.pos.y;
                double previousCameraZ = previousFallbackTransformValid
                    ? previousFallbackCameraZ
                    : cameraState.pos.z;
                clipToPrev = new Matrix4f();
                prevToClip = new Matrix4f();
                DlssTransformScratch.setCameraRelativeClipTransforms(
                    clipToPrev,
                    prevToClip,
                    previousProjectionForFrame,
                    previousViewRotationForFrame,
                    previousCameraX,
                    previousCameraY,
                    previousCameraZ,
                    projection,
                    cameraState.viewRotationMatrix,
                    cameraState.pos.x,
                    cameraState.pos.y,
                    cameraState.pos.z
                );
                inverseProjection =
                    new Matrix4f(projection).invert();
                up = new Vector3f(0, 1, 0)
                    .rotate(cameraState.orientation);
                right = new Vector3f(1, 0, 0)
                    .rotate(cameraState.orientation);
                forward = new Vector3f(0, 0, -1)
                    .rotate(cameraState.orientation);
                if (transformScratchFallbackFrames != Long.MAX_VALUE) {
                    transformScratchFallbackFrames++;
                }
            }
            lastTransformScratchPath = transformScratchFrame;
            ThirdPersonGeometryBatch articulatedPlayer =
                ThirdPersonGeometryMotion.freezeForDispatch();
            if (articulatedPlayer.overflow()) {
                requestReset("unvollstaendige Third-Person-Modellgeometrie");
                reset = true;
            }
            MotionObjectBatch batch = motionObjectBatchOrNull();
            List<MotionVectorGenerator.MotionObject> legacyObjects;
            int fixedMovingObjectCount = MOTION_HISTORY_OVERFLOW;
            if (batch != null) {
                try {
                    fixedMovingObjectCount = collectMotionObjects(
                        deltaTracker,
                        batch,
                        entityMotionHistory
                    );
                } catch (RuntimeException | LinkageError error) {
                    disableMotionScratchAfterHistoryFailure(error);
                    batch = null;
                    reset = true;
                    requestReset("Entity-History-Laufzeitfehler");
                }
            }
            if (
                batch != null
                    && fixedMovingObjectCount
                        != MOTION_HISTORY_OVERFLOW
            ) {
                legacyObjects = null;
                lastMotionObjectCount = fixedMovingObjectCount;
                lastMotionObjectBatchPath = true;
            } else {
                EntityMotionHistory overflowHistory =
                    batch == null
                        ? null
                        : entityMotionHistory;
                LegacyMotionCollection legacyCollection =
                    collectLegacyMotionObjects(
                        deltaTracker,
                        overflowHistory
                    );
                legacyObjects = legacyCollection.objects();
                if (overflowHistory != null) {
                    disableMotionScratchAfterHistoryOverflow();
                    batch = null;
                }
                lastMotionObjectCount =
                    legacyCollection.observedMovingObjects();
                lastMotionObjectBatchPath = false;
                if (motionObjectFallbackFrames != Long.MAX_VALUE) {
                    motionObjectFallbackFrames++;
                }
            }
            if (
                rejectIncompleteMotionCoverage(
                    lastMotionObjectCount,
                    batch,
                    legacyObjects
                )
            ) {
                reset = true;
            }
            if (
                DeveloperDiagnostics.ENABLED
                    && FoliageAudit.shouldAutoCapture(frameIndex)
            ) {
                DlssDebugCapture.request();
            }
            boolean captureDebug =
                DeveloperDiagnostics.ENABLED
                    && DlssDebugCapture.shouldCaptureFrame(frameIndex);

            var motionCpuZone = DeveloperDiagnostics.ENABLED
                ? GpuPassDiagnostics.beginCpuTracyZone(
                    GpuPassIdentity.MOTION_COMPUTE
                )
                : null;
            boolean motionGpuZone =
                DeveloperDiagnostics.ENABLED
                && GpuPassDiagnostics.beginGpuTracyZone(
                    tracyGpuProfiler,
                    encoder,
                    GpuPassIdentity.MOTION_COMPUTE
                );
            try {
                if (batch != null) {
                    motionGenerator.dispatch(commandBuffer, colorView, depthView, nativeMotionView,
                        (VulkanGpuTextureView)resources.depthDebugView, (VulkanGpuTextureView)resources.motionDebugView,
                        (VulkanGpuTextureView)resources.motionValidityView,
                        (VulkanGpuTextureView)resources.transparencyHintView,
                        inverseViewProjection, previousVp, clipToPrev,
                        lowWidth, lowHeight, jitterX, jitterY,
                        reset, captureDebug, batch);
                } else {
                    motionGenerator.dispatch(commandBuffer, colorView, depthView, nativeMotionView,
                        (VulkanGpuTextureView)resources.depthDebugView, (VulkanGpuTextureView)resources.motionDebugView,
                        (VulkanGpuTextureView)resources.motionValidityView,
                        (VulkanGpuTextureView)resources.transparencyHintView,
                        inverseViewProjection, previousVp, clipToPrev,
                        lowWidth, lowHeight, jitterX, jitterY,
                        reset, captureDebug, legacyObjects);
                }
            } finally {
                if (DeveloperDiagnostics.ENABLED) {
                    GpuPassDiagnostics.endGpuTracyZone(
                        tracyGpuProfiler,
                        encoder,
                        motionGpuZone
                    );
                    GpuPassDiagnostics.closeCpuTracyZone(motionCpuZone);
                }
            }

            if (captureDebug) {
                DlssDebugCapture.captureBeforeEvaluate(
                    encoder,
                    frameIndex,
                    allocatedMode,
                    outputWidth,
                    outputHeight,
                    lowWidth,
                    lowHeight,
                    presetFor(allocatedMode),
                    lowTarget.getColorTexture(),
                    lowTarget.getDepthTexture(),
                    resources.motionTexture,
                    resources.depthDebugTexture,
                    resources.motionDebugTexture,
                    resources.motionValidityTexture,
                    resources.transparencyHintTexture
                );
            }

            float fov = 2.0F * (float)Math.atan(1.0F / Math.abs(projection.m11()));
            float far = cameraState.depthFar > 0.0F ? cameraState.depthFar : 1024.0F;

            float sharpness = DlssConfig.effectiveSharpness(allocatedMode);
            var evaluateCpuZone =
                DeveloperDiagnostics.ENABLED
                    ? GpuPassDiagnostics.beginCpuTracyZone(
                        GpuPassIdentity.DLSS_EVALUATE
                    )
                    : null;
            boolean evaluateGpuZone =
                DeveloperDiagnostics.ENABLED
                && GpuPassDiagnostics.beginGpuTracyZone(
                    tracyGpuProfiler,
                    encoder,
                    GpuPassIdentity.DLSS_EVALUATE
                );
            VulkanDebug debug = null;
            if (DeveloperDiagnostics.ENABLED) {
                try {
                    VulkanDevice currentVulkanDevice =
                        DlssBootstrap.vulkanBackend();
                    if (currentVulkanDevice != null) {
                        debug = currentVulkanDevice.instance().debug();
                    }
                } catch (
                    RuntimeException
                        | LinkageError
                        | OutOfMemoryError ignored
                ) {
                    // Optional labels do not control DLSS evaluation.
                }
            }
            VkCommandBuffer dlssCommandBuffer = null;
            VkCommandBuffer nisCommandBuffer = null;
            boolean dlssCommandBufferEndAttempted = false;
            boolean nisCommandBufferEndAttempted = false;
            boolean dlssDebugGroup = false;
            boolean nisDebugGroup = false;
            boolean dlssDebugGroupEndAttempted = false;
            boolean nisDebugGroupEndAttempted = false;
            boolean dlssCommandBufferSubmitted = false;
            boolean nisCommandBufferSubmitted = false;
            Throwable nisCommandBufferFailure = null;
            StreamlineEvaluationResult evaluationResult =
                StreamlineEvaluationResult.notEvaluated();
            try {
                dlssCommandBuffer =
                    vulkanEncoder
                        .allocateAndBeginTransientCommandBuffer();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(
                        dlssCommandBuffer,
                        stack
                    );
                }
                if (sharpness > 0.0F) {
                    try {
                        nisCommandBuffer =
                            vulkanEncoder
                                .allocateAndBeginTransientCommandBuffer();
                        try (
                            MemoryStack stack = MemoryStack.stackPush()
                        ) {
                            VulkanCommandEncoder.memoryBarrier(
                                nisCommandBuffer,
                                stack
                            );
                        }
                    } catch (
                        RuntimeException
                            | LinkageError
                            | OutOfMemoryError error
                    ) {
                        nisCommandBufferFailure = error;
                    }
                }
                if (DeveloperDiagnostics.ENABLED) {
                    dlssDebugGroup = GpuPassDiagnostics.beginDebugGroup(
                        debug,
                        dlssCommandBuffer,
                        GpuPassIdentity.DLSS_EVALUATE
                    );
                    if (
                        nisCommandBuffer != null
                            && nisCommandBufferFailure == null
                    ) {
                        nisDebugGroup = GpuPassDiagnostics.beginDebugGroup(
                            debug,
                            nisCommandBuffer,
                            GpuPassIdentity.DLSS_EVALUATE
                        );
                    }
                }
                try {
                    long packedEvaluationResult = NativeStreamline.evaluate(
                        0, frameIndex, allocatedMode.nativeId(),
                        lowWidth, lowHeight, outputWidth, outputHeight,
                        dlssCommandBuffer.address(),
                        nisCommandBuffer == null
                                || nisCommandBufferFailure != null
                            ? 0L
                            : nisCommandBuffer.address(),
                        color.vkImage(), colorView.vkImageView(), depth.vkImage(), depthView.vkImageView(),
                        motion.vkImage(), nativeMotionView.vkImageView(),
                        0L, 0L,
                        ((VulkanGpuTexture)resources.transparencyHintTexture).vkImage(), ((VulkanGpuTextureView)resources.transparencyHintView).vkImageView(),
                        output.vkImage(), nativeOutputView.vkImageView(),
                        sharpen.vkImage(), nativeSharpenView.vkImageView(), sharpness,
                        rowMajor(projection, PROJECTION_ROW_MAJOR),
                        rowMajor(inverseProjection, INVERSE_PROJECTION_ROW_MAJOR),
                        rowMajor(clipToPrev, CLIP_TO_PREVIOUS_ROW_MAJOR),
                        rowMajor(prevToClip, PREVIOUS_TO_CLIP_ROW_MAJOR),
                        (float)cameraState.pos.x, (float)cameraState.pos.y, (float)cameraState.pos.z,
                        up.x, up.y, up.z, right.x, right.y, right.z, forward.x, forward.y, forward.z,
                        0.05F, far, fov, (float)outputWidth / outputHeight, jitterX, jitterY,
                        DeveloperDiagnostics.ENABLED
                            ? TemporalHintAudit.secondaryHintMode(
                                FoliageAudit.streamlineHintMode()
                            )
                            : FoliageAudit.HINT_TRANSPARENCY,
                        reset);
                    evaluationResult =
                        StreamlineEvaluationResult.unpack(
                            packedEvaluationResult
                        );
                    if (
                        evaluationResult.dlssSucceeded()
                            && nisCommandBufferFailure != null
                    ) {
                        evaluationResult =
                            new StreamlineEvaluationResult(
                                evaluationResult.dlssResult(),
                                StreamlineEvaluationResult
                                    .NIS_COMMAND_BUFFER_FAILURE
                            );
                    }
                } finally {
                    if (DeveloperDiagnostics.ENABLED) {
                        dlssDebugGroupEndAttempted = true;
                        try {
                            GpuPassDiagnostics.endDebugGroup(
                                debug,
                                dlssCommandBuffer,
                                dlssDebugGroup
                            );
                        } finally {
                            if (nisCommandBuffer != null) {
                                nisDebugGroupEndAttempted = true;
                                GpuPassDiagnostics.endDebugGroup(
                                    debug,
                                    nisCommandBuffer,
                                    nisDebugGroup
                                );
                            }
                        }
                    }
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(
                        dlssCommandBuffer,
                        stack
                    );
                }
                dlssCommandBufferEndAttempted = true;
                int dlssEndResult = VK12.vkEndCommandBuffer(
                    dlssCommandBuffer
                );
                if (dlssEndResult != VK12.VK_SUCCESS) {
                    throw new IllegalStateException(
                        "DLSS-Commandbuffer konnte nicht beendet werden ["
                            + dlssEndResult
                            + "]"
                    );
                }
                if (evaluationResult.dlssSucceeded()) {
                    vulkanEncoder.execute(dlssCommandBuffer);
                    dlssCommandBufferSubmitted = true;
                }

                if (
                    nisCommandBuffer != null
                        && nisCommandBufferFailure == null
                ) {
                    try {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            VulkanCommandEncoder.memoryBarrier(
                                nisCommandBuffer,
                                stack
                            );
                        }
                        nisCommandBufferEndAttempted = true;
                        int nisEndResult = VK12.vkEndCommandBuffer(
                            nisCommandBuffer
                        );
                        if (nisEndResult != VK12.VK_SUCCESS) {
                            throw new IllegalStateException(
                                "NIS-Commandbuffer konnte nicht beendet werden ["
                                    + nisEndResult
                                    + "]"
                            );
                        }
                        if (evaluationResult.nisSucceeded()) {
                            vulkanEncoder.execute(nisCommandBuffer);
                            nisCommandBufferSubmitted = true;
                        }
                    } catch (
                        RuntimeException
                            | LinkageError
                            | OutOfMemoryError error
                    ) {
                        nisCommandBufferFailure = error;
                        evaluationResult =
                            new StreamlineEvaluationResult(
                                evaluationResult.dlssResult(),
                                StreamlineEvaluationResult
                                    .NIS_COMMAND_BUFFER_FAILURE
                            );
                    }
                }
            } finally {
                if (
                    dlssCommandBuffer != null
                        && dlssDebugGroup
                        && !dlssDebugGroupEndAttempted
                ) {
                    try {
                        GpuPassDiagnostics.endDebugGroup(
                            debug,
                            dlssCommandBuffer,
                            true
                        );
                    } catch (
                        RuntimeException
                            | LinkageError
                            | OutOfMemoryError ignored
                    ) {
                        // Best effort before discarding an isolated buffer.
                    }
                }
                if (
                    nisCommandBuffer != null
                        && nisDebugGroup
                        && !nisDebugGroupEndAttempted
                ) {
                    try {
                        GpuPassDiagnostics.endDebugGroup(
                            debug,
                            nisCommandBuffer,
                            true
                        );
                    } catch (
                        RuntimeException
                            | LinkageError
                            | OutOfMemoryError ignored
                    ) {
                        // Best effort before discarding an isolated buffer.
                    }
                }
                if (
                    dlssCommandBuffer != null
                        && !dlssCommandBufferEndAttempted
                ) {
                    try {
                        VK12.vkEndCommandBuffer(dlssCommandBuffer);
                    } catch (
                        RuntimeException
                            | LinkageError
                            | OutOfMemoryError ignored
                    ) {
                        // A partial failed DLSS buffer is never submitted.
                    }
                }
                if (
                    nisCommandBuffer != null
                        && !nisCommandBufferEndAttempted
                ) {
                    try {
                        VK12.vkEndCommandBuffer(nisCommandBuffer);
                    } catch (
                        RuntimeException
                            | LinkageError
                            | OutOfMemoryError ignored
                    ) {
                        // A partial failed NIS buffer is never submitted.
                    }
                }
                if (DeveloperDiagnostics.ENABLED) {
                    GpuPassDiagnostics.endGpuTracyZone(
                        tracyGpuProfiler,
                        encoder,
                        evaluateGpuZone
                    );
                    GpuPassDiagnostics.closeCpuTracyZone(
                        evaluateCpuZone
                    );
                }
            }
            lastEvaluationCode = evaluationResult.dlssResult();
            lastEvaluationActive =
                evaluationResult.dlssSucceeded()
                    && dlssCommandBufferSubmitted;
            lastReset = reset;
            lastAppliedLodBias = currentLodBiasForDimensions();
            if (!lastEvaluationActive) {
                throw new IllegalStateException(
                    safeNativeMessage()
                        + " ["
                        + evaluationResult.dlssResult()
                        + "]"
                );
            }
            recordSharpeningOutcome(
                sharpness,
                evaluationResult,
                nisCommandBufferSubmitted,
                nisCommandBufferFailure
            );
            BlockframeRuntime.recordDlssEvaluationPass();
            float appliedSharpness = nisCommandBufferSubmitted
                ? sharpness
                : 0.0F;

            if (captureDebug) {
                DlssDebugCapture.captureAfterEvaluate(
                    encoder,
                    frameIndex,
                    resources.outputTexture,
                    resources.sharpenTexture,
                    appliedSharpness,
                    jitterX,
                    jitterY,
                    previousJitterX,
                    previousJitterY,
                    metadataJson(
                        frameIndex,
                        reset,
                        projection,
                        inverseProjection,
                        clipToPrev,
                        prevToClip,
                        fov,
                        far
                    )
                );
            }

            GpuTexture finalWorld = nisCommandBufferSubmitted
                ? resources.sharpenTexture
                : resources.outputTexture;
            encoder.copyTextureToTexture(finalWorld, originalTarget.getColorTexture(), 0, 0, 0, 0, 0, outputWidth, outputHeight);
            VkCommandBuffer postStreamlineCommandBuffer =
                ((VulkanCommandEncoderAccessor)(Object)vulkanEncoder)
                    .nvidiaDlss$commandBuffer();
            prepareNativeOutlineDepthSafely(
                encoder,
                postStreamlineCommandBuffer
            );
            if (transformScratchFrame) {
                frameScratch.commitPreviousViewProjection();
            } else {
                commitFallbackTransforms(
                    projection,
                    cameraState.viewRotationMatrix,
                    viewProjection,
                    cameraState.pos.x,
                    cameraState.pos.y,
                    cameraState.pos.z
                );
            }
            ThirdPersonGeometryMotion.commitSuccessfulFrame();
            previousJitterX = jitterX;
            previousJitterY = jitterY;
            resetRequested = false;
            frameIndex++;
            if (!evaluationLogged) {
                evaluationLogged = true;
                NvidiaDlssMod.LOGGER.info(
                    "[DLSS self-test] PASS: DLSS-Commandbuffer eingereiht; {} | echte Depth-Motion-Vectors | Halton-Jitter",
                    sharpeningResultDescription()
                );
            }
            if (reset) NvidiaDlssMod.LOGGER.info("DLSS-History-Reset: {}", resetReason);
        } catch (Throwable error) {
            ThirdPersonGeometryMotion.discardFailedFrame();
            lastEvaluationActive = false;
            lastSharpeningActive = false;
            requestReset("DLSS-Framefehler");
            DlssStatus.error("DLSS-Frame: " + safeMessage(error));
            NvidiaDlssMod.LOGGER.error("DLSS-Auswertung fehlgeschlagen; räumlicher Sicherheits-Blit wird verwendet", error);
            try {
                lowTarget.blitAndBlendToTexture(originalTarget.getColorTextureView(), originalTarget.getDepthTextureView());
                prepareNativeOutlineDepthSafely();
            } catch (Throwable fallbackError) {
                NvidiaDlssMod.LOGGER.error("Auch der DLSS-Sicherheits-Blit ist fehlgeschlagen", fallbackError);
            }
        }
        return originalTarget;
    }

    private static void prepareNativeOutlineDepthSafely() {
        if (!deferredBlockOutline || lowTarget == null || originalTarget == null) return;
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            Object backend = ((CommandEncoderAccessor)(Object)encoder).nvidiaDlss$backend();
            if (!(backend instanceof VulkanCommandEncoder vulkanEncoder)) return;
            prepareNativeOutlineDepth(encoder,
                ((VulkanCommandEncoderAccessor)(Object)vulkanEncoder).nvidiaDlss$commandBuffer());
        } catch (Throwable error) {
            logNativeOutlineDepthFailure(error);
        }
    }

    private static void prepareNativeOutlineDepthSafely(CommandEncoder encoder, VkCommandBuffer commandBuffer) {
        try {
            prepareNativeOutlineDepth(encoder, commandBuffer);
        } catch (Throwable error) {
            logNativeOutlineDepthFailure(error);
        }
    }

    private static void logNativeOutlineDepthFailure(Throwable error) {
        nativeOutlineDepthReady = false;
        if (!nativeOutlineDepthWarningLogged) {
            nativeOutlineDepthWarningLogged = true;
            NvidiaDlssMod.LOGGER.warn("Nativer Blockrand: Tiefenskalierung nicht verfuegbar; verwende sicheren Overlay-Fallback", error);
        }
    }

    private static void prepareNativeOutlineDepth(CommandEncoder encoder, VkCommandBuffer commandBuffer) {
        nativeOutlineDepthReady = false;
        if (!deferredBlockOutline || lowTarget == null || originalTarget == null) return;
        GpuTexture source = lowTarget.getDepthTexture();
        GpuTexture destination = originalTarget.getDepthTexture();
        if (source == null || destination == null || source.getFormat() != destination.getFormat()) {
            throw new IllegalStateException("Inkompatible Tiefenformate fuer nativen Blockrand");
        }
        if (lowWidth == outputWidth && lowHeight == outputHeight) {
            encoder.copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, outputWidth, outputHeight);
            nativeOutlineDepthReady = true;
            return;
        }

        VulkanGpuTexture vulkanSource = (VulkanGpuTexture)source;
        VulkanGpuTexture vulkanDestination = (VulkanGpuTexture)destination;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource()
                .aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(lowWidth, lowHeight, 1);
            region.dstSubresource()
                .aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
            region.dstOffsets(0).set(0, 0, 0);
            region.dstOffsets(1).set(outputWidth, outputHeight, 1);
            VK12.vkCmdBlitImage(commandBuffer, vulkanSource.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL,
                vulkanDestination.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, region, VK12.VK_FILTER_NEAREST);
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            nativeOutlineDepthReady = true;
        }
    }

    private static float currentLodBiasForDimensions() {
        // DLAA is native-resolution antialiasing and must never inherit the
        // upscaling-specific bias, even if an SDK reports odd dimensions.
        if (allocatedMode == DlssMode.DLAA) return 0.0F;
        if (outputWidth <= 0 || lowWidth <= 0 || lowWidth == outputWidth) return 0.0F;
        return Mth.clamp((float)(Math.log((double)lowWidth / outputWidth) / Math.log(2.0)) - 1.0F, -2.75F, 0.0F);
    }

    /** Complete per-frame integration facts, rendered after DLSS so the overlay itself stays native-resolution. */
    public static List<String> debugLines() {
        DlssMode mode = allocatedMode;
        String result = lastEvaluationCode == Integer.MIN_VALUE ? "noch keiner" : Integer.toString(lastEvaluationCode);
        String preset = switch (mode) {
            case DLAA, QUALITY, BALANCED -> "K";
            case PERFORMANCE -> "M";
            case ULTRA_PERFORMANCE -> "L";
            default -> "-";
        };
        String color = lowWidth > 0 ? lowWidth + "x" + lowHeight + " RGBA8_UNORM" : "-";
        String depth = lowWidth > 0 ? lowWidth + "x" + lowHeight + " D32_FLOAT" : "-";
        String motion = lowWidth > 0 ? lowWidth + "x" + lowHeight + " RG16_FLOAT" : "-";
        return List.of(
            "NVIDIA DLSS/DLAA IQ Debug [F8]",
            "Backend: " + (DlssBootstrap.vulkanBackend() != null ? "Vulkan" : "nicht verbunden"),
            "Modus: " + mode + (requestedMode != mode ? " (gewählt: " + requestedMode + ")" : "")
                + " | aktiv: " + (lastEvaluationActive ? "ja" : "nein"),
            "Streamline: " + result + " | Preset: " + preset,
            "Ausgabe: " + (outputWidth > 0 ? outputWidth + "x" + outputHeight : "-"),
            "Intern: " + (lowWidth > 0 ? lowWidth + "x" + lowHeight : "-"),
            "Color: " + color,
            "Depth: " + depth + " | inverted: true",
            "Motion: " + motion,
            "Motion validity: "
                + (lowWidth > 0
                    ? lowWidth + "x" + lowHeight + " R8_UINT"
                    : "-"),
            "mvecScale: " + reciprocal(lowWidth) + ", " + reciprocal(lowHeight),
            "cameraMotionIncluded: true | jittered MV: false",
            "Temporal policy: " + TemporalHintAudit.metadataJson(),
            "Player motion: " + ThirdPersonMotionAudit.metadataJson(),
            "Exposure: Auto | Eingang: tonemapped LDR",
            "Jitter px: " + decimal(jitterX) + ", " + decimal(jitterY) + " | Phasen: " + jitterPhaseCount,
            "Reset: " + (lastReset ? "ja" : "nein") + " | Grund: " + resetReason,
            "Frame: " + frameIndex + " | Viewport: 0 | bewegte Objekte: " + lastMotionObjectCount,
            "Motion-Objekte: " + motionObjectPathDescription() + " | Fallback-Frames: " + motionObjectFallbackFrames,
            "Entity-History: " + entityMotionHistoryStatus,
            "Transform-Scratch: " + (lastTransformScratchPath ? "Slab" : "Legacy-Fallback")
                + " (" + transformScratchStatus + ") | Fallback-Frames: " + transformScratchFallbackFrames,
            "Outline-Pose-Scratch: "
                + nativeOutlinePoseScratchStatusDescription()
                + " | Reuse: " + nativeOutlinePoseScratchReuseUses
                + " | Fresh-Fallbacks: "
                + nativeOutlinePoseScratchFreshFallbacks
                + " | Disables: " + nativeOutlinePoseScratchDisables
                + " | Reentrant: "
                + nativeOutlinePoseScratchReentrantFallbacks
                + " | Imbalance: "
                + nativeOutlinePoseScratchImbalanceDisables
                + " | Unwound: " + nativeOutlinePoseScratchUnwoundPoses,
            "Sharpening: " + sharpeningDescription()
                + " | Ergebnis: " + sharpeningResultDescription()
                + " | LOD-Bias: " + decimal(lastAppliedLodBias),
            "Material-Sampler: " + DlssSamplerPolicy.debugStatus()
        );
    }

    private static String reciprocal(int value) {
        return value > 0 ? String.format(Locale.ROOT, "%.7f", 1.0 / value) : "-";
    }

    private static String decimal(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String motionObjectPathDescription() {
        if (!motionObjectBatchCreationAttempted) {
            return "noch nicht versucht";
        }
        return lastMotionObjectBatchPath ? "Batch" : "Legacy-Fallback";
    }

    private static String nativeOutlinePoseScratchStatusDescription() {
        return switch (nativeOutlinePoseScratchStatus) {
            case NativeBlockOutlinePoseStackScratch.STATUS_ACTIVE -> "active";
            case NativeBlockOutlinePoseStackScratch.STATUS_DISABLED ->
                "disabled/fresh fallback";
            case NativeBlockOutlinePoseStackScratch.STATUS_CLEARED -> "cleared";
            default -> "not attempted";
        };
    }

    private static String sharpeningDescription() {
        float sharpness = DlssConfig.effectiveSharpness(allocatedMode);
        return sharpness > 0.0F
            ? "NVIDIA NIS NVSharpen " + Math.round(sharpness * 100.0F) + " %"
            : "aus (0 %)";
    }

    private static void recordSharpeningOutcome(
        float sharpness,
        StreamlineEvaluationResult result,
        boolean nisSubmitted,
        Throwable commandBufferFailure
    ) {
        if (sharpness <= 0.0F) {
            lastSharpeningCode =
                StreamlineEvaluationResult.NIS_NOT_REQUESTED;
            lastSharpeningActive = false;
            loggedSharpeningFailureCode =
                StreamlineEvaluationResult.NIS_NOT_REQUESTED;
            return;
        }

        lastSharpeningCode = result.nisResult();
        lastSharpeningActive = nisSubmitted;
        if (nisSubmitted) {
            loggedSharpeningFailureCode =
                StreamlineEvaluationResult.NIS_NOT_REQUESTED;
            return;
        }
        if (loggedSharpeningFailureCode == result.nisResult()) {
            return;
        }

        loggedSharpeningFailureCode = result.nisResult();
        String detail = commandBufferFailure == null
            ? safeNativeMessage()
            : safeMessage(commandBufferFailure);
        NvidiaDlssMod.LOGGER.warn(
            "NVIDIA NIS NVSharpen fehlgeschlagen [{}]: {}; "
                + "erfolgreiche DLSS-Ausgabe wird beibehalten",
            result.nisResult(),
            detail
        );
    }

    private static String sharpeningResultDescription() {
        if (
            lastSharpeningCode
                == StreamlineEvaluationResult.NIS_NOT_REQUESTED
        ) {
            return "nicht angefordert";
        }
        if (lastSharpeningActive) {
            return "NIS aktiv (0)";
        }
        return "NIS fehlgeschlagen ("
            + lastSharpeningCode
            + "), DLSS-Ausgabe beibehalten";
    }

    private static String presetFor(DlssMode mode) {
        return mode == DlssMode.PERFORMANCE ? "M"
            : mode == DlssMode.ULTRA_PERFORMANCE ? "L"
            : mode == DlssMode.OFF ? "-" : "K";
    }

    private static String metadataJson(int frame, boolean reset, Matrix4f projection, Matrix4f inverseProjection,
        Matrix4f clipToPrev, Matrix4f prevToClip, float fov, float far) {
        var profiler = BlockframeRuntime.engine().profiler().snapshot();
        return String.format(Locale.ROOT, """
            {
              "frameId": %d,
              "viewportId": 0,
              "mode": "%s",
              "preset": "%s",
              "foliageAudit": %s,
              "temporalHintAudit": %s,
              "thirdPersonMotionAudit": %s,
              "thirdPersonGeometry": %s,
              "output": {"width": %d, "height": %d, "format": "RGBA8_UNORM"},
              "inputColor": {"width": %d, "height": %d, "format": "RGBA8_UNORM", "colorBuffersHDR": false},
              "depth": {"width": %d, "height": %d, "format": "D32_FLOAT", "depthInverted": true},
              "motionVectors": {"width": %d, "height": %d, "format": "RG16_FLOAT", "currentToPrevious": true,
                "mvecScale": [%s, %s], "cameraMotionIncluded": true, "motionVectorsJittered": false,
                "invalidFallback": "finite zero; magenta classification is debug-only", "movingObjectTransforms": %d},
              "jitterPixels": [%s, %s],
              "previousJitterPixels": [%s, %s],
              "jitterPhaseCount": %d,
              "reset": %s,
              "resetReason": "%s",
              "nearPlane": 0.05,
              "farPlane": %s,
              "verticalFovRadians": %s,
              "aspectRatio": %s,
              "exposure": {"mode": "auto", "input": "tonemapped LDR"},
              "sharpening": {"method": "NVIDIA NIS NVSharpen", "intensity": %s},
              "textureLodBias": %s,
              "atlasSamplers": %s,
              "frameProfiler": {
                "completedFrames": %d,
                "rollingSamples": %d,
                "frameTimeMs": {"last": %s, "p50": %s, "p95": %s, "p99": %s},
                "completedGpuFrames": %d,
                "rollingGpuSamples": %d,
                "gpuFrameTimeMs": {"last": %s, "p50": %s, "p95": %s, "p99": %s}
              },
              "historyWeightDebug": "application eligibility only; DLSS internal history weight is not exposed",
              "projectionRowMajor": %s,
              "inverseProjectionRowMajor": %s,
              "clipToPrevClipRowMajor": %s,
              "prevClipToClipRowMajor": %s
            }
            """, frame, allocatedMode.id(), presetFor(allocatedMode), FoliageAudit.debugMetadataJson(),
            TemporalHintAudit.metadataJson(), ThirdPersonMotionAudit.metadataJson(),
            ThirdPersonGeometryMotion.metadataJson(), outputWidth, outputHeight,
            lowWidth, lowHeight, lowWidth, lowHeight, lowWidth, lowHeight,
            reciprocal(lowWidth), reciprocal(lowHeight), lastMotionObjectCount,
            decimal(jitterX), decimal(jitterY), decimal(previousJitterX), decimal(previousJitterY), jitterPhaseCount,
            reset, jsonEscape(resetReason), decimal(far), decimal(fov), decimal((float)outputWidth / outputHeight),
            decimal(DlssConfig.effectiveSharpness(allocatedMode)), decimal(currentLodBiasForDimensions()),
            FoliageAudit.atlasSamplersJson(),
            profiler.completedFrames(), profiler.rollingSampleCount(),
            jsonMillis(profiler.frameDurationNanos(), profiler.completedFrames()),
            jsonMillis(profiler.p50FrameNanos(), profiler.rollingSampleCount()),
            jsonMillis(profiler.p95FrameNanos(), profiler.rollingSampleCount()),
            jsonMillis(profiler.p99FrameNanos(), profiler.rollingSampleCount()),
            profiler.completedGpuFrames(), profiler.rollingGpuSampleCount(),
            jsonMillis(profiler.gpuFrameDurationNanos(), profiler.completedGpuFrames()),
            jsonMillis(profiler.p50GpuFrameNanos(), profiler.rollingGpuSampleCount()),
            jsonMillis(profiler.p95GpuFrameNanos(), profiler.rollingGpuSampleCount()),
            jsonMillis(profiler.p99GpuFrameNanos(), profiler.rollingGpuSampleCount()),
            matrixJson(projection), matrixJson(inverseProjection), matrixJson(clipToPrev), matrixJson(prevToClip));
    }

    private static String jsonMillis(long nanos, long sampleCount) {
        return sampleCount > 0L
            ? String.format(Locale.ROOT, "%.6f", nanos / 1_000_000.0D)
            : "null";
    }

    private static String matrixJson(Matrix4f matrix) {
        float[] values = rowMajor(matrix, MATRIX_JSON_SCRATCH);
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(", ");
            result.append(String.format(Locale.ROOT, "%.8g", values[i]));
        }
        return result.append(']').toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void updateJitter() {
        double ratio = (double)outputWidth / lowWidth;
        jitterPhaseCount = Math.max(8, (int)Math.ceil(8.0 * ratio * ratio));
        int phase = jitterPhaseForFrame(frameIndex, jitterPhaseCount);
        jitterX = radicalInverse(phase, 2) - 0.5F;
        if (!DeveloperDiagnostics.ENABLED) {
            jitterY = radicalInverse(phase, 3) - 0.5F;
            return;
        }
        FoliageAudit.JitterMode jitterMode = FoliageAudit.jitterMode();
        jitterY = switch (jitterMode) {
            case FULL -> radicalInverse(phase, 3) - 0.5F;
            case HORIZONTAL_ONLY, OFF -> 0.0F;
        };
        if (jitterMode == FoliageAudit.JitterMode.OFF) jitterX = 0.0F;
    }

    /** Uses the exact unsigned 32-bit frame value passed to Streamline. */
    static int jitterPhaseForFrame(int frame, int phaseCount) {
        if (phaseCount <= 0) throw new IllegalArgumentException("phaseCount must be positive");
        return Integer.remainderUnsigned(frame, phaseCount) + 1;
    }

    private static float radicalInverse(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F / base;
        while (index > 0) {
            result += (index % base) * fraction;
            index /= base;
            fraction /= base;
        }
        return result;
    }

    private static DlssMode effectiveMode(DlssMode requested, int height) {
        return requested == DlssMode.ULTRA_PERFORMANCE && height < 2160 ? DlssMode.QUALITY : requested;
    }

    private static void detectHistoryBreaks(CameraRenderState state, boolean vrRunning) {
        Minecraft minecraft = Minecraft.getInstance();
        Object level = minecraft.level;
        if (TemporalResetPolicy.identityChanged(previousLevel, level)) {
            requestWorldHistoryReset("Welt-/Dimensionswechsel");
        }
        Object cameraEntity = minecraft.getCameraEntity();
        if (
            TemporalResetPolicy.identityChanged(
                previousCameraEntity,
                cameraEntity
            )
        ) {
            requestReset("Kamera-Entity-/Respawn-Wechsel");
        }
        if (
            previousCameraPosition != null
                && TemporalResetPolicy.cameraPositionCut(
                    previousCameraPosition.distanceToSqr(state.pos)
                )
        ) {
            requestReset("Teleport/großer Kamerasprung");
        }
        boolean scratchOrientation = false;
        DlssTransformScratch scratch = activeTransformScratch();
        if (scratch != null) {
            try {
                if (
                    scratch.hasPreviousOrientation()
                        && TemporalResetPolicy.cameraOrientationCut(
                            scratch.previousOrientationDot(
                                state.orientation
                            )
                        )
                ) {
                    requestReset("großer Kamerarotationssprung");
                }
                scratch.rememberOrientation(state.orientation);
                scratchOrientation = true;
                previousCameraOrientation = null;
            } catch (Throwable error) {
                disableTransformScratch(error);
            }
        }
        if (!scratchOrientation) {
            if (
                previousCameraOrientation != null
                    && TemporalResetPolicy.cameraOrientationCut(
                        previousCameraOrientation.dot(state.orientation)
                    )
            ) {
                requestReset("großer Kamerarotationssprung");
            }
            previousCameraOrientation =
                new Quaternionf(state.orientation);
        }
        boolean cameraDeadOrDying =
            state.entityRenderState.isLiving
                && state.entityRenderState.isDeadOrDying;
        if (
            TemporalResetPolicy.booleanStateChanged(
                previousCameraDeadOrDyingValid,
                previousCameraDeadOrDying,
                cameraDeadOrDying
            )
        ) {
            requestReset(
                cameraDeadOrDying
                    ? "Spielertod"
                    : "Tod-/Respawn-Übergang"
            );
        }
        int fovSetting = minecraft.options.fov().get();
        if (previousFovSetting >= 0 && previousFovSetting != fovSetting) requestReset("FOV-Wechsel");
        int cameraType = minecraft.options.getCameraType().ordinal();
        if (previousCameraType >= 0 && previousCameraType != cameraType) requestReset("Perspektivwechsel");
        int guiScale = minecraft.options.guiScale().get();
        if (previousGuiScale >= 0 && previousGuiScale != guiScale) requestReset("GUI-Skalierung");
        if (previousVrState != vrRunning) requestReset("Desktop-/VR-Wechsel");
        previousLevel = level;
        previousCameraEntity = cameraEntity;
        previousCameraPosition = state.pos;
        previousCameraDeadOrDying = cameraDeadOrDying;
        previousCameraDeadOrDyingValid = true;
        previousFovSetting = fovSetting;
        previousCameraType = cameraType;
        previousGuiScale = guiScale;
        previousVrState = vrRunning;
    }

    private static void beginTransformFrame() {
        DlssTransformScratch scratch = transformScratchOrNull();
        if (scratch == null) {
            return;
        }
        try {
            scratch.beginFrame();
        } catch (Throwable error) {
            disableTransformScratch(error);
        }
    }

    private static DlssTransformScratch transformScratchOrNull() {
        if (transformScratchDisabled) {
            return null;
        }
        if (transformScratchCreationAttempted) {
            DlssTransformScratch existing = transformScratch;
            if (existing != null) {
                BlockframeRuntime.featureBecameEffective(
                    FeatureId.TRANSFORM_SCRATCH,
                    "budgeted-transform-scratch-active"
                );
            } else if (
                BlockframeRuntime.featureEnabled(
                    FeatureId.TRANSFORM_SCRATCH
                )
            ) {
                BlockframeRuntime.featureUsedFallback(
                    FeatureId.TRANSFORM_SCRATCH,
                    true,
                    false,
                    "ram-shader-budget-fallback"
                );
            }
            return existing;
        }
        transformScratchCreationAttempted = true;
        if (
            !BlockframeRuntime.featureEnabled(
                FeatureId.TRANSFORM_SCRATCH
            )
        ) {
            transformScratchStatus =
                "disabled by process feature policy";
            return null;
        }
        try {
            DlssTransformScratch scratch =
                DlssTransformScratch.tryCreate(
                    BlockframeRuntime.memoryBudgets()
                );
            if (scratch == null) {
                transformScratchStatus =
                    "RAM/SHADER budget rejected";
                BlockframeRuntime.featureUsedFallback(
                    FeatureId.TRANSFORM_SCRATCH,
                    true,
                    false,
                    "ram-shader-budget-fallback"
                );
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Transform-Slab konnte das SHADER_RESOURCES-RAM-Budget nicht reservieren; Legacy-Fallback bleibt aktiv"
                );
                return null;
            }
            transformScratch = scratch;
            transformScratchStatus =
                "budgeted "
                    + DlssTransformScratch.LAYOUT.requestedBytes()
                    + "/"
                    + DlssTransformScratch.LAYOUT.committedBytes()
                    + " bytes";
            BlockframeRuntime.featureBecameEffective(
                FeatureId.TRANSFORM_SCRATCH,
                "budgeted-transform-scratch-active"
            );
            return scratch;
        } catch (Throwable error) {
            disableTransformScratch(error);
            return null;
        }
    }

    private static DlssTransformScratch activeTransformScratch() {
        return transformScratchDisabled ? null : transformScratch;
    }

    private static void disableTransformScratch(Throwable error) {
        if (!transformScratchDisabled) {
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Transform-Slab wurde deaktiviert; Legacy-Fallback bleibt aktiv",
                error
            );
        }
        transformScratchDisabled = true;
        transformScratchStatus =
            "disabled: " + error.getClass().getSimpleName();
        clearFallbackTransformHistory();
        BlockframeRuntime.featureUsedFallback(
            FeatureId.TRANSFORM_SCRATCH,
            true,
            true,
            "runtime-quarantine-legacy-matrices"
        );
        requestReset("Transform-Scratch-Fallback");
    }

    private static void resetTransformViewProjection() {
        clearFallbackTransformHistory();
        DlssTransformScratch scratch = transformScratch;
        if (scratch != null) {
            try {
                scratch.resetPreviousViewProjection();
            } catch (Throwable error) {
                disableTransformScratch(error);
            }
        }
    }

    private static void clearTransformScratchDeviceState() {
        clearFallbackTransformHistory();
        DlssTransformScratch scratch = transformScratch;
        if (scratch != null) {
            try {
                scratch.clearDeviceState();
            } catch (Throwable error) {
                disableTransformScratch(error);
            }
        }
    }

    private static void commitFallbackTransforms(
        Matrix4f projection,
        Matrix4f viewRotation,
        Matrix4f viewProjection,
        double cameraX,
        double cameraY,
        double cameraZ
    ) {
        if (previousViewProjection == null) {
            previousViewProjection = new Matrix4f(viewProjection);
        } else {
            previousViewProjection.set(viewProjection);
        }
        if (previousFallbackProjection == null) {
            previousFallbackProjection = new Matrix4f(projection);
        } else {
            previousFallbackProjection.set(projection);
        }
        if (previousFallbackViewRotation == null) {
            previousFallbackViewRotation = new Matrix4f(viewRotation);
        } else {
            previousFallbackViewRotation.set(viewRotation);
        }
        previousFallbackCameraX = cameraX;
        previousFallbackCameraY = cameraY;
        previousFallbackCameraZ = cameraZ;
        previousFallbackTransformValid = true;
    }

    private static void clearFallbackTransformHistory() {
        previousViewProjection = null;
        previousFallbackProjection = null;
        previousFallbackViewRotation = null;
        previousFallbackCameraX = 0.0D;
        previousFallbackCameraY = 0.0D;
        previousFallbackCameraZ = 0.0D;
        previousFallbackTransformValid = false;
    }

    private static MotionObjectBatch motionObjectBatchOrNull() {
        if (motionScratchDisabled) {
            return null;
        }
        if (motionObjectBatchCreationAttempted) {
            MotionObjectBatch existing = motionObjectBatch;
            if (existing != null) {
                BlockframeRuntime.featureBecameEffective(
                    FeatureId.ENTITY_MOTION_SCRATCH,
                    "fixed-motion-batch-active"
                );
                EntityMotionHistory existingHistory =
                    entityMotionHistory;
                if (
                    BlockframeRuntime.featureEnabled(
                        FeatureId
                            .ENTITY_HISTORY_NATIVE_EXPERIMENTAL
                    )
                        && existingHistory != null
                ) {
                    if (
                        existingHistory.storageKind()
                            == EntityMotionHistory.StorageKind.NATIVE
                    ) {
                        BlockframeRuntime.featureBecameEffective(
                            FeatureId
                                .ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                            "native-experimental-history-active"
                        );
                    } else {
                        BlockframeRuntime.featureUsedFallback(
                            FeatureId
                                .ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                            true,
                            false,
                            "heap-physical-allocation-fallback"
                        );
                    }
                }
            } else if (
                BlockframeRuntime.featureEnabled(
                    FeatureId.ENTITY_MOTION_SCRATCH
                )
            ) {
                BlockframeRuntime.featureUsedFallback(
                    FeatureId.ENTITY_MOTION_SCRATCH,
                    true,
                    false,
                    "entity-history-allocation-fallback"
                );
            }
            return existing;
        }
        motionObjectBatchCreationAttempted = true;
        if (
            !BlockframeRuntime.featureEnabled(
                FeatureId.ENTITY_MOTION_SCRATCH
            )
        ) {
            entityMotionHistoryStatus =
                "Legacy-Fallback: disabled by process feature policy";
            return null;
        }
        MotionObjectBatch batch = null;
        EntityMotionHistory history = null;
        EntityMotionHistory.BackendPreference configuredHistoryBackend =
            DlssConfig.entityHistoryBackend();
        EntityMotionHistory.BackendPreference historyBackend =
            configuredHistoryBackend
                    == EntityMotionHistory.BackendPreference
                        .NATIVE_EXPERIMENTAL
                    && BlockframeRuntime.featureEnabled(
                        FeatureId
                            .ENTITY_HISTORY_NATIVE_EXPERIMENTAL
                    )
                ? configuredHistoryBackend
                : EntityMotionHistory.BackendPreference.HEAP;
        try {
            batch = MotionObjectBatch.tryCreate(
                BlockframeRuntime.memoryBudgets(),
                MotionVectorGenerator.MAX_OBJECTS
            );
            if (batch == null) {
                entityMotionHistoryStatus =
                    "Legacy-Fallback: motion batch RAM/ENTITIES budget/allocation";
                BlockframeRuntime.featureUsedFallback(
                    FeatureId.ENTITY_MOTION_SCRATCH,
                    true,
                    false,
                    "ram-entities-budget-fallback"
                );
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Motion-Objektbatch konnte das ENTITIES-RAM-Budget nicht reservieren; Legacy-Fallback bleibt aktiv"
                );
                return null;
            }
            history = EntityMotionHistory.tryCreate(
                BlockframeRuntime.memoryBudgets(),
                MOTION_HISTORY_CAPACITY,
                historyBackend
            );
            if (history == null) {
                batch.close();
                entityMotionHistoryStatus =
                    historyBackend
                            == EntityMotionHistory.BackendPreference
                                .NATIVE_EXPERIMENTAL
                        ? "Legacy-Fallback: experimental native/heap RAM/ENTITIES budget/allocation"
                        : "Legacy-Fallback: heap RAM/ENTITIES budget/allocation";
                BlockframeRuntime.featureUsedFallback(
                    FeatureId.ENTITY_MOTION_SCRATCH,
                    true,
                    false,
                    "entity-history-allocation-fallback"
                );
                if (
                    historyBackend
                        == EntityMotionHistory.BackendPreference
                            .NATIVE_EXPERIMENTAL
                ) {
                    BlockframeRuntime.featureUsedFallback(
                        FeatureId
                            .ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                        true,
                        false,
                        "native-to-heap-or-legacy-fallback"
                    );
                }
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Entity-History konnte den gewaehlten {}-Pfad nicht reservieren/allokieren; Legacy-Fallback bleibt aktiv",
                    historyBackend.id()
                );
                return null;
            }
            motionObjectBatch = batch;
            entityMotionHistory = history;
            entityMotionHistoryStatus =
                history.storageKind() == EntityMotionHistory.StorageKind.NATIVE
                    ? "Native-Experimental "
                        + history.requestedBytes()
                        + "/"
                        + history.committedBytes()
                        + " bytes"
                    : historyBackend
                            == EntityMotionHistory.BackendPreference
                                .NATIVE_EXPERIMENTAL
                        ? "Heap-Fallback-from-Native-Experimental "
                            + history.requestedBytes()
                            + "/"
                            + history.committedBytes()
                            + " bytes"
                        : configuredHistoryBackend
                                == EntityMotionHistory.BackendPreference
                                    .NATIVE_EXPERIMENTAL
                            ? "Heap-Process-Policy-Fallback "
                                + history.requestedBytes()
                                + "/"
                                + history.committedBytes()
                                + " bytes"
                        : "Heap-Standard "
                            + history.requestedBytes()
                            + "/"
                            + history.committedBytes()
                            + " bytes";
            BlockframeRuntime.featureBecameEffective(
                FeatureId.ENTITY_MOTION_SCRATCH,
                "fixed-motion-batch-active"
            );
            if (
                history.storageKind()
                    == EntityMotionHistory.StorageKind.NATIVE
            ) {
                BlockframeRuntime.featureBecameEffective(
                    FeatureId
                        .ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                    "native-experimental-history-active"
                );
            } else if (
                historyBackend
                    == EntityMotionHistory.BackendPreference
                        .NATIVE_EXPERIMENTAL
            ) {
                BlockframeRuntime.featureUsedFallback(
                    FeatureId
                        .ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
                    true,
                    false,
                    "heap-physical-allocation-fallback"
                );
            }
        } catch (Throwable error) {
            boolean historyClosed = CreationRollback.close(
                history,
                error
            );
            boolean batchClosed = CreationRollback.close(
                batch,
                error
            );
            boolean historyCleanupRetained =
                EntityMotionHistory.hasPendingCleanup();
            entityMotionHistory = historyClosed ? null : history;
            motionObjectBatch = batchClosed ? null : batch;
            motionScratchDisabled = true;
            entityMotionHistoryStatus =
                historyClosed && !historyCleanupRetained
                    ? "Legacy-Fallback: creation failed"
                    : "Legacy-Fallback: creation cleanup retained";
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Motion-Scratch konnte nicht angelegt werden; Legacy-Fallback bleibt aktiv",
                error
            );
            BlockframeRuntime.featureUsedFallback(
                FeatureId.ENTITY_MOTION_SCRATCH,
                true,
                true,
                "creation-quarantine-legacy-rebuild"
            );
            return null;
        }
        return motionObjectBatch;
    }

    private static void clearMotionObjectHistory() {
        previousEntities = Map.of();
        EntityMotionHistory history = entityMotionHistory;
        if (history != null && !motionScratchDisabled) {
            history.clear();
        }
    }

    private static void disableMotionScratchAfterHistoryOverflow() {
        motionScratchDisabled = true;
        entityMotionHistoryStatus =
            "Legacy-Fallback: fixed history overflow";
        NvidiaDlssMod.LOGGER.warn(
            "DLSS-Entity-History erreichte ihr festes Limit von {} Eintraegen; Legacy-Fallback bleibt fuer diesen Client aktiv",
            entityMotionHistory == null
                ? 0
                : entityMotionHistory.maxEntries()
        );
        BlockframeRuntime.featureUsedFallback(
            FeatureId.ENTITY_MOTION_SCRATCH,
            true,
            true,
            "fixed-history-overflow-quarantine"
        );
        BlockframeRuntime.featureUsedFallback(
            FeatureId.ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
            true,
            true,
            "fixed-history-overflow-quarantine"
        );
        EntityMotionHistory history = entityMotionHistory;
        if (
            history != null
                && runCloseStage(
                    "DLSS-Entity-History",
                    history::close
                )
                && entityMotionHistory == history
        ) {
            entityMotionHistory = null;
        }
        MotionObjectBatch batch = motionObjectBatch;
        if (
            batch != null
                && runCloseStage(
                    "DLSS-Motion-Objektbatch",
                    batch::close
                )
                && motionObjectBatch == batch
        ) {
            motionObjectBatch = null;
        }
    }

    private static void disableMotionScratchAfterHistoryFailure(
        Throwable error
    ) {
        motionScratchDisabled = true;
        entityMotionHistoryStatus =
            "Legacy-Fallback: native/heap history runtime fault";
        NvidiaDlssMod.LOGGER.warn(
            "DLSS-Entity-History fiel zur Laufzeit aus; derselbe Frame wird vollstaendig ueber den Legacy-Pfad neu aufgebaut",
            error
        );
        BlockframeRuntime.featureUsedFallback(
            FeatureId.ENTITY_MOTION_SCRATCH,
            true,
            true,
            "history-runtime-fault-same-frame-legacy-rebuild"
        );
        BlockframeRuntime.featureUsedFallback(
            FeatureId.ENTITY_HISTORY_NATIVE_EXPERIMENTAL,
            true,
            true,
            "history-runtime-fault-same-frame-legacy-rebuild"
        );
        EntityMotionHistory history = entityMotionHistory;
        if (
            history != null
                && runCloseStage(
                    "DLSS-Entity-History nach Laufzeitfehler",
                    history::close
                )
                && entityMotionHistory == history
        ) {
            entityMotionHistory = null;
        }
        MotionObjectBatch batch = motionObjectBatch;
        if (
            batch != null
                && runCloseStage(
                    "DLSS-Motion-Objektbatch nach History-Laufzeitfehler",
                    batch::close
                )
                && motionObjectBatch == batch
        ) {
            motionObjectBatch = null;
        }
    }

    private static boolean rejectIncompleteMotionCoverage(
        int observedMovingObjects,
        MotionObjectBatch batch,
        List<MotionVectorGenerator.MotionObject> legacyObjects
    ) {
        if (
            !TemporalResetPolicy.motionObjectCapacityExceeded(
                observedMovingObjects,
                MotionVectorGenerator.MAX_OBJECTS
            )
        ) {
            return false;
        }
        if (batch != null) {
            batch.clear();
        } else {
            Objects.requireNonNull(
                legacyObjects,
                "legacyObjects"
            ).clear();
        }
        requestReset(
            "mehr als "
                + MotionVectorGenerator.MAX_OBJECTS
                + " bewegte Objekte; vollständiger History-Reset"
        );
        return true;
    }

    private static int collectMotionObjects(
        DeltaTracker deltaTracker,
        MotionObjectBatch result,
        EntityMotionHistory history
    ) {
        Objects.requireNonNull(history, "history");
        result.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            history.clear();
            previousEntities = Map.of();
            return 0;
        }
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        int observedMovingObjects = 0;
        history.beginFrame();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (
                entity == minecraft.player
                    && (
                        minecraft.options.getCameraType().isFirstPerson()
                            || ThirdPersonMotionAudit
                                .excludeLocalPlayerFromRigidTransport()
                    )
            ) {
                continue;
            }
            double x = Mth.lerp(partial, entity.xo, entity.getX());
            double y = Mth.lerp(partial, entity.yo, entity.getY());
            double z = Mth.lerp(partial, entity.zo, entity.getZ());
            float yaw =
                Mth.rotLerp(partial, entity.yRotO, entity.getYRot())
                    * Mth.DEG_TO_RAD;
            AABB bounds = entity.getBoundingBox();
            double offsetX = x - entity.getX();
            double offsetY = y - entity.getY();
            double offsetZ = z - entity.getZ();
            double minX = bounds.minX + offsetX;
            double minY = bounds.minY + offsetY;
            double minZ = bounds.minZ + offsetZ;
            double maxX = bounds.maxX + offsetX;
            double maxY = bounds.maxY + offsetY;
            double maxZ = bounds.maxZ + offsetZ;
            int entityId = entity.getId();
            boolean hasPrevious = history.findPrevious(entityId);
            double previousX = 0.0D;
            double previousY = 0.0D;
            double previousZ = 0.0D;
            float previousYaw = 0.0F;
            if (hasPrevious) {
                previousX = history.previousX();
                previousY = history.previousY();
                previousZ = history.previousZ();
                previousYaw = history.previousYaw();
            }
            if (!history.putCurrent(entityId, x, y, z, yaw)) {
                return MOTION_HISTORY_OVERFLOW;
            }
            if (!hasPrevious) {
                continue;
            }
            double movement =
                Mth.square(x - previousX)
                    + Mth.square(y - previousY)
                    + Mth.square(z - previousZ);
            float yawDelta =
                Mth.wrapDegrees((yaw - previousYaw) * Mth.RAD_TO_DEG)
                    * Mth.DEG_TO_RAD;
            if (
                movement < 1.0E-8
                    && Math.abs(yawDelta) < 1.0E-4F
            ) {
                continue;
            }
            observedMovingObjects++;
            if (
                TemporalResetPolicy.motionObjectCapacityExceeded(
                    observedMovingObjects,
                    MotionVectorGenerator.MAX_OBJECTS
                )
            ) {
                continue;
            }
            if (
                !result.add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    previousX,
                    previousY,
                    previousZ,
                    x,
                    y,
                    z,
                    yaw,
                    previousYaw
                )
            ) {
                throw new IllegalStateException(
                    "Motion-Objektbatch war trotz Kapazitaetspruefung voll"
                );
            }
        }
        return observedMovingObjects;
    }

    private static LegacyMotionCollection collectLegacyMotionObjects(
        DeltaTracker deltaTracker
    ) {
        return collectLegacyMotionObjects(deltaTracker, null);
    }

    private static LegacyMotionCollection collectLegacyMotionObjects(
        DeltaTracker deltaTracker,
        EntityMotionHistory primitivePrevious
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            if (primitivePrevious != null) {
                primitivePrevious.clear();
            }
            previousEntities = Map.of();
            return new LegacyMotionCollection(List.of(), 0);
        }
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        Map<Integer, EntityFrame> currentFrames = new HashMap<>();
        List<MotionVectorGenerator.MotionObject> result = new ArrayList<>();
        int observedMovingObjects = 0;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (
                entity == minecraft.player
                    && (
                        minecraft.options.getCameraType().isFirstPerson()
                            || ThirdPersonMotionAudit
                                .excludeLocalPlayerFromRigidTransport()
                    )
            ) continue;
            double x = Mth.lerp(partial, entity.xo, entity.getX());
            double y = Mth.lerp(partial, entity.yo, entity.getY());
            double z = Mth.lerp(partial, entity.zo, entity.getZ());
            float yaw = Mth.rotLerp(partial, entity.yRotO, entity.getYRot()) * Mth.DEG_TO_RAD;
            AABB bounds = entity.getBoundingBox();
            double offsetX = x - entity.getX();
            double offsetY = y - entity.getY();
            double offsetZ = z - entity.getZ();
            double minX = bounds.minX + offsetX;
            double minY = bounds.minY + offsetY;
            double minZ = bounds.minZ + offsetZ;
            double maxX = bounds.maxX + offsetX;
            double maxY = bounds.maxY + offsetY;
            double maxZ = bounds.maxZ + offsetZ;
            EntityFrame current = new EntityFrame(x, y, z, yaw);
            int entityId = entity.getId();
            currentFrames.put(entityId, current);
            EntityFrame previous =
                primitivePrevious == null
                    ? previousEntities.get(entityId)
                    : null;
            boolean hasPrevious =
                primitivePrevious == null
                    ? previous != null
                    : primitivePrevious.findPrevious(entityId);
            if (!hasPrevious) {
                continue;
            }
            double previousX =
                primitivePrevious == null
                    ? previous.x
                    : primitivePrevious.previousX();
            double previousY =
                primitivePrevious == null
                    ? previous.y
                    : primitivePrevious.previousY();
            double previousZ =
                primitivePrevious == null
                    ? previous.z
                    : primitivePrevious.previousZ();
            float previousYaw =
                primitivePrevious == null
                    ? previous.yaw
                    : primitivePrevious.previousYaw();
            double movement =
                Mth.square(x - previousX)
                    + Mth.square(y - previousY)
                    + Mth.square(z - previousZ);
            float yawDelta =
                Mth.wrapDegrees((yaw - previousYaw) * Mth.RAD_TO_DEG)
                    * Mth.DEG_TO_RAD;
            if (movement < 1.0E-8 && Math.abs(yawDelta) < 1.0E-4F) continue;
            observedMovingObjects++;
            if (
                TemporalResetPolicy.motionObjectCapacityExceeded(
                    observedMovingObjects,
                    MotionVectorGenerator.MAX_OBJECTS
                )
            ) {
                continue;
            }
            result.add(new MotionVectorGenerator.MotionObject(
                minX, minY, minZ, maxX, maxY, maxZ,
                previousX, previousY, previousZ, x, y, z, yaw, previousYaw
            ));
        }
        previousEntities = currentFrames;
        return new LegacyMotionCollection(
            result,
            observedMovingObjects
        );
    }

    private static float[] rowMajor(Matrix4f matrix, float[] target) {
        target[0] = matrix.m00();
        target[1] = matrix.m10();
        target[2] = matrix.m20();
        target[3] = matrix.m30();
        target[4] = matrix.m01();
        target[5] = matrix.m11();
        target[6] = matrix.m21();
        target[7] = matrix.m31();
        target[8] = matrix.m02();
        target[9] = matrix.m12();
        target[10] = matrix.m22();
        target[11] = matrix.m32();
        target[12] = matrix.m03();
        target[13] = matrix.m13();
        target[14] = matrix.m23();
        target[15] = matrix.m33();
        return target;
    }

    /**
     * Restores Minecraft's native target when a render exception bypasses the
     * normal post-world assignment. The GameRenderer mixin invokes this from
     * a whole-method {@code finally} block.
     */
    public static RenderTarget restoreOriginalTarget(RenderTarget current) {
        boolean interrupted = active
            || worldPass
            || (lowTarget != null && current == lowTarget);
        RenderTarget restored = current;
        if (
            lowTarget != null
                && current == lowTarget
                && originalTarget != null
        ) {
            restored = originalTarget;
        }
        originalTarget = null;
        if (interrupted) {
            active = false;
            worldPass = false;
            wasActive = false;
            deferredBlockOutline = false;
            nativeOutlineDepthReady = false;
            nativeBlockOutlinePass = false;
            requestReset("abgebrochener Render-Frame");
        }
        return restored;
    }

    /**
     * Starts a lifecycle generation only for a genuinely new, successfully
     * connected Vulkan device. Stale references belong to an already
     * destroyed previous device and must never be reused by the new one.
     */
    public static void deviceConnected(VulkanDevice device) {
        Objects.requireNonNull(device, "device");
        if (lifecycleDevice == device) {
            return;
        }
        if (lifecycleDevice != null && !deviceCloseFinished) {
            DEVICE_CLEANUP_PROOF.recordDeviceClosed(false);
            NvidiaDlssMod.LOGGER.warn(
                "Vorherige DLSS-Gerätegeneration endete mit konservativ beibehaltenen Ressourcen; die neue Generation bleibt im nativen Minecraft-Fallback"
            );
            DlssSamplerPolicy.deviceConnected(device);
            deviceGenerationBlocked = true;
            deviceCloseStarted = true;
            active = false;
            worldPass = false;
            wasActive = false;
            return;
        }
        lifecycleDevice = device;
        DEVICE_CLEANUP_PROOF.beginGeneration();
        DlssSamplerPolicy.deviceConnected(device);
        lowTarget = null;
        auxiliaryResources = null;
        motionGenerator = null;
        originalTarget = null;
        active = false;
        worldPass = false;
        wasActive = false;
        samplerClosePrepared = false;
        samplerCloseFinished = false;
        targetClosePrepared = false;
        auxiliaryClosePrepared = false;
        auxiliaryLeaseRetained = false;
        motionClosePrepared = false;
        motionCloseFinished = false;
        streamlineCloseFinished = false;
        retirementCloseFinished = false;
        deviceCloseStarted = false;
        deviceClosePrepared = false;
        deviceCloseFinished = false;
        deviceGenerationBlocked = false;
        lowWidth = 0;
        lowHeight = 0;
        outputWidth = 0;
        outputHeight = 0;
        allocatedMode = DlssMode.OFF;
        requestedMode = DlssMode.OFF;
        failedResourceMode = DlssMode.OFF;
        failedResourceWidth = -1;
        failedResourceHeight = -1;
        resourceRetryAfterNanos = 0L;
        cameraState = null;
        unjitteredProjection = null;
        clearTransformScratchDeviceState();
        previousLevel = null;
        previousCameraEntity = null;
        previousCameraPosition = null;
        previousCameraOrientation = null;
        previousEffectiveFovRadians = Float.NaN;
        previousCameraDeadOrDying = false;
        previousCameraDeadOrDyingValid = false;
        previousFovSetting = -1;
        previousCameraType = -1;
        previousGuiScale = -1;
        previousVrState = false;
        clearMotionObjectHistory();
        frameIndex = 0;
        previousJitterX = 0.0F;
        previousJitterY = 0.0F;
        deferredBlockOutline = false;
        nativeOutlineDepthReady = false;
        nativeBlockOutlinePass = false;
        nativeOutlineLogged = false;
        nativeOutlineDepthWarningLogged = false;
        evaluationLogged = false;
        lastEvaluationCode = Integer.MIN_VALUE;
        lastEvaluationActive = false;
        lastSharpeningCode =
            StreamlineEvaluationResult.NIS_NOT_REQUESTED;
        lastSharpeningActive = false;
        loggedSharpeningFailureCode =
            StreamlineEvaluationResult.NIS_NOT_REQUESTED;
        lastMotionObjectCount = 0;
        requestReset("neue Vulkan-Gerätegeneration");
    }

    /**
     * Seals failed owners after Mojang has destroyed their VkDevice. Cleanup
     * may still report retained accounting, but it must never touch a raw
     * handle from that dead generation again.
     */
    public static void deviceClosed(VulkanDevice device) {
        if (device == null) {
            return;
        }
        MotionVectorGenerator.deviceClosed(device);
        MotionVectorGenerator generator = motionGenerator;
        if (generator != null) {
            generator.owningDeviceClosed(device);
        }
        if (lifecycleDevice == device) {
            DEVICE_CLEANUP_PROOF.recordDeviceClosed(
                deviceCloseFinished
            );
            if (!deviceCloseFinished) {
                deviceGenerationBlocked = true;
                deviceCloseStarted = true;
                active = false;
                worldPass = false;
                wasActive = false;
            }
        }
    }

    /**
     * Queues every Mojang-managed object while the command encoder is alive.
     * Their raw Vulkan objects remain intact in Mojang's destruction queue
     * until the encoder has submitted and drained its pending builder.
     */
    public static boolean prepareDeviceClose() {
        if (deviceCloseFinished || deviceClosePrepared) {
            return true;
        }
        if (deviceGenerationBlocked) {
            return DEVICE_CLEANUP_PROOF.recordPrepare(false);
        }
        deviceCloseStarted = true;
        active = false;
        worldPass = false;
        wasActive = false;
        originalTarget = null;

        if (!samplerClosePrepared) {
            samplerClosePrepared = confirmCloseStage(
                "DLSS-Materialsampler",
                () -> DlssSamplerPolicy.prepareDeviceClose(
                    lifecycleDevice
                )
            );
        }

        if (!targetClosePrepared) {
            MainTarget target = lowTarget;
            if (target == null) {
                targetClosePrepared = true;
            } else if (
                runCloseStage("DLSS-Weltziel", target::destroyBuffers)
            ) {
                if (lowTarget == target) {
                    lowTarget = null;
                }
                targetClosePrepared = true;
            }
        }

        if (!auxiliaryClosePrepared) {
            DlssAuxiliaryResources resources = auxiliaryResources;
            if (resources == null) {
                auxiliaryClosePrepared = true;
            } else if (targetClosePrepared) {
                boolean closeReturned = runCloseStage(
                    "DLSS-Hilfsressourcen",
                    resources::close
                );
                auxiliaryClosePrepared =
                    closeReturned && resources.closeConfirmed();
                if (closeReturned && !auxiliaryClosePrepared) {
                    NvidiaDlssMod.LOGGER.warn(
                        "DLSS-Hilfsressourcen blieben nach einem Teilfehler unbestätigt; Lease und Referenz bleiben konservativ aktiv"
                    );
                }
            } else {
                auxiliaryClosePrepared = runCloseStage(
                    "DLSS-Hilfsressourcen ohne Lease-Freigabe",
                    resources::closeRetainingLease
                );
                auxiliaryLeaseRetained = auxiliaryClosePrepared;
            }
        }

        if (!motionClosePrepared) {
            boolean retainedConstructionPrepared =
                confirmCloseStage(
                    "DLSS-Motion-Konstruktionsrollback",
                    MotionVectorGenerator::
                        retryRetainedFailedConstruction
                );
            MotionVectorGenerator generator = motionGenerator;
            motionClosePrepared =
                retainedConstructionPrepared
                    && (
                        generator == null
                            || confirmCloseStage(
                                "DLSS-Motion-Buffer",
                                generator::prepareDeviceClose
                            )
                    );
        }

        deviceClosePrepared = DlssLifecycleState.allPrepareStagesComplete(
            samplerClosePrepared,
            targetClosePrepared,
            auxiliaryClosePrepared,
            auxiliaryLeaseRetained,
            motionClosePrepared
        );
        return DEVICE_CLEANUP_PROOF.recordPrepare(
            deviceClosePrepared
        );
    }

    /**
     * Runs after VulkanCommandEncoder closed its final submission and waited
     * for the graphics queue, but before its destruction queue releases any
     * tagged raw image. Native shutdown first frees every tracked DLSS/NIS
     * viewport and only then calls slShutdown.
     */
    public static boolean
        releaseStreamlineAfterQueueDrainBeforeResourceDestroy(
            VulkanDevice device
        ) {
        if (!DlssBootstrap.connectedTo(device)) {
            return streamlineCloseFinished;
        }
        streamlineCloseFinished = confirmCloseStage(
            "Streamline-Verbindung vor Vulkan-Ressourcenzerstörung",
            DlssBootstrap::shutdownConnectionAndReport
        );
        return streamlineCloseFinished;
    }

    /**
     * Runs only after VulkanCommandEncoder.destroy() submitted and drained
     * pending commands and Mojang's destruction queue. Streamline must
     * already be shut down; retrying it here could touch destroyed tags.
     */
    public static boolean finishDeviceCloseAfterEncoderDrain() {
        if (deviceCloseFinished) {
            return true;
        }
        if (deviceGenerationBlocked) {
            return DEVICE_CLEANUP_PROOF.recordFinish(false);
        }
        if (!samplerCloseFinished) {
            samplerCloseFinished = confirmCloseStage(
                "DLSS-Materialsampler nach Encoder-Drain",
                () -> DlssSamplerPolicy
                    .finishDeviceCloseAfterEncoderDrain(
                        lifecycleDevice
                    )
            );
        }
        if (!motionCloseFinished) {
            MotionVectorGenerator generator = motionGenerator;
            motionCloseFinished = generator == null
                || confirmCloseStage(
                    "DLSS-Motion-Pipeline",
                    generator::finishDeviceCloseAfterEncoderDrain
                );
            if (motionCloseFinished && motionGenerator == generator) {
                motionGenerator = null;
            }
        }
        if (
            DlssLifecycleState.mayCompleteGpuRetirements(
                deviceClosePrepared,
                samplerCloseFinished,
                motionCloseFinished,
                streamlineCloseFinished
            )
                && !retirementCloseFinished
        ) {
            retirementCloseFinished = runCloseStage(
                "GPU-Retirement-Budgets",
                () -> {
                    BlockframeRuntime
                        .shaderResources()
                        .completeGpuRetirements();
                    BlockframeRuntime
                        .memoryBudgets()
                        .completeGpuRetirements();
                }
            );
        }
        deviceCloseFinished = DlssLifecycleState.allFinishStagesComplete(
            deviceClosePrepared,
            samplerCloseFinished,
            motionCloseFinished,
            streamlineCloseFinished,
            retirementCloseFinished
        );
        if (deviceCloseFinished) {
            auxiliaryResources = null;
            lowWidth = 0;
            lowHeight = 0;
            outputWidth = 0;
            outputHeight = 0;
            allocatedMode = DlssMode.OFF;
        }
        return DEVICE_CLEANUP_PROOF.recordFinish(
            deviceCloseFinished
        );
    }

    /**
     * Compatibility entry point. Physical cleanup remains deferred to the
     * post-encoder device-close hook.
     */
    public static void close() {
        prepareDeviceClose();
    }

    /**
     * Releases client-generation CPU scratch independently of Vulkan device
     * recreation. Called once from Minecraft's final close hook.
     */
    public static void closeClientResources() {
        closeClientResourcesAndReport();
    }

    /**
     * Releases the same owners as {@link #closeClientResources()} while
     * preserving a complete cleanup result for the normal-shutdown proof.
     * Every stage is still attempted after an earlier stage failed.
     */
    public static boolean closeClientResourcesAndReport() {
        boolean cleanupSucceeded = true;
        DlssTransformScratch scratch = transformScratch;
        if (scratch != null) {
            boolean scratchClosed = runCloseStage(
                "DLSS-Transform-Slab",
                scratch::close
            );
            cleanupSucceeded &= scratchClosed;
            if (scratchClosed && transformScratch == scratch) {
                transformScratch = null;
                transformScratchStatus = "closed";
            }
        }
        cleanupSucceeded &= runCloseStage(
            "DLSS-Transform-Slab-Konstruktionsrollback",
            DlssTransformScratch::closeRetainedFailedCreation
        );
        cleanupSucceeded &= runCloseStage(
            "DLSS-Entity-History",
            () -> closeEntityMotionHistoryOwner(
                "DLSS-Entity-History"
            )
        );
        cleanupSucceeded &= runCloseStage(
            "DLSS-Entity-History-Konstruktionsrollback",
            EntityMotionHistory::retryPendingCleanup
        );
        cleanupSucceeded &= runCloseStage(
            "DLSS-Entity-History-Retry",
            () -> closeEntityMotionHistoryOwner(
                "DLSS-Entity-History-Retry"
            )
        );
        cleanupSucceeded &= runCloseStage(
            "DLSS-Entity-History-Konstruktionsrollback-Retry",
            EntityMotionHistory::retryPendingCleanup
        );
        cleanupSucceeded &= runCloseStage(
            "DLSS-Materialsampler-Metadatenrollback",
            DlssSamplerPolicy::retryPendingCreationCleanup
        );
        cleanupSucceeded &= runCloseStage(
            "DLSS-Materialsampler-Threadzustand",
            DlssSamplerPolicy::clearClientThreadState
        );
        if (!deviceGenerationBlocked) {
            cleanupSucceeded &= runCloseStage(
                "DLSS-Motion-Konstruktionsrollback",
                MotionVectorGenerator::
                    retryRetainedFailedConstruction
            );
        } else if (
            MotionVectorGenerator.hasRetainedFailedConstruction()
        ) {
            cleanupSucceeded = false;
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Motion-Konstruktionsrollback bleibt nach dem Tod seines VkDevice konservativ erhalten; kein Raw-Handle-Retry wird ausgefuehrt"
            );
        }
        if (lifecycleDevice != null && !deviceCloseFinished) {
            DEVICE_CLEANUP_PROOF.recordDeviceClosed(false);
            cleanupSucceeded = false;
        }
        cleanupSucceeded &= confirmCloseStage(
            "nicht verbundener Streamline-Bootstrap",
            DlssBootstrap::shutdownUnconnectedBootstrapAndReport
        );
        MotionObjectBatch batch = motionObjectBatch;
        if (batch != null) {
            boolean batchClosed = runCloseStage(
                "DLSS-Motion-Objektbatch",
                batch::close
            );
            cleanupSucceeded &= batchClosed;
            if (batchClosed && motionObjectBatch == batch) {
                motionObjectBatch = null;
            }
        }
        cleanupSucceeded = DEVICE_CLEANUP_PROOF.reportClientClose(
            cleanupSucceeded
        );
        return cleanupSucceeded;
    }

    private static void closeEntityMotionHistoryOwner(String label) {
        EntityMotionHistory history = entityMotionHistory;
        if (history == null) {
            return;
        }
        history.close();
        if (entityMotionHistory == history) {
            entityMotionHistory = null;
            entityMotionHistoryStatus = "closed";
        }
    }

    private static boolean runCloseStage(String label, Runnable stage) {
        try {
            stage.run();
            return true;
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "{} konnte nicht sauber geschlossen werden",
                label,
                error
            );
            return false;
        }
    }

    private static boolean confirmCloseStage(
        String label,
        BooleanSupplier stage
    ) {
        try {
            boolean complete = stage.getAsBoolean();
            if (!complete) {
                NvidiaDlssMod.LOGGER.warn(
                    "{} blieb unvollständig; konservativer Lease-/Referenzerhalt bleibt aktiv",
                    label
                );
            }
            return complete;
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "{} konnte nicht sauber geschlossen werden",
                label,
                error
            );
            return false;
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String safeNativeMessage() {
        try {
            String message = NativeStreamline.lastMessage();
            return message == null || message.isBlank()
                ? "Streamline-Auswertung fehlgeschlagen"
                : message;
        } catch (
            RuntimeException
                | LinkageError
                | OutOfMemoryError ignored
        ) {
            return "Streamline-Auswertung fehlgeschlagen";
        }
    }

    private record LegacyMotionCollection(
        List<MotionVectorGenerator.MotionObject> objects,
        int observedMovingObjects
    ) {}

    private record EntityFrame(double x, double y, double z, float yaw) {}
}
