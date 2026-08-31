package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssLifecycleSourceContractTest {
    @Test
    void renderFinallyRestoresMinecraftTargetAndEndsExactlyOneFrame()
        throws Exception {
        String source = source(
            "src/main/java/de/morau/nvidiadlss/mixin/GameRendererMixin.java"
        );

        assertTrue(source.contains("@WrapMethod(method = \"render\")"));
        assertTrue(source.contains("Operation<Void> original"));
        assertTrue(source.contains("original.call(deltaTracker, advanceGameTime)"));
        assertTrue(source.contains("} finally {"));
        assertTrue(source.contains("DlssRenderer.restoreOriginalTarget("));
        assertTrue(source.contains("BlockframeRuntime.endFrame();"));
        assertFalse(source.contains("DlssRenderer.close();"));
        assertFalse(source.contains("BlockframeRuntime.close();"));
    }

    @Test
    void deviceShutdownQueuesManagedObjectsBeforeEncoderAndRawObjectsAfter()
        throws Exception {
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/VulkanDeviceMixin.java"
        );
        String encoderMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VulkanCommandEncoderLifecycleMixin.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java"
        );
        String auxiliary = source(
            "src/main/java/de/morau/nvidiadlss/DlssAuxiliaryResources.java"
        );

        String encoderTarget =
            "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;destroy()V";
        assertTrue(deviceMixin.contains(encoderTarget));
        assertTrue(deviceMixin.contains("shift = At.Shift.AFTER"));
        assertTrue(deviceMixin.contains("@Unique"));
        assertTrue(
            deviceMixin.contains(
                "if (this.blockframe$dlssCloseStarted)"
            )
        );
        assertTrue(encoderMixin.contains("method = \"destroy\""));
        assertTrue(
            encoderMixin.contains(
                "Lcom/mojang/blaze3d/vulkan/VulkanQueue;waitIdle()V"
            )
        );
        assertTrue(encoderMixin.contains("shift = At.Shift.AFTER"));
        assertTrue(
            encoderMixin.contains(
                "releaseStreamlineAfterQueueDrainBeforeResourceDestroy("
            )
        );
        assertTrue(
            deviceMixin.contains("DlssRenderer.prepareDeviceClose()")
        );
        assertTrue(
            deviceMixin.contains(
                "DlssRenderer.finishDeviceCloseAfterEncoderDrain()"
            )
        );
        int nativeFoundationClose = deviceMixin.indexOf(
            "NativeTerrainBackendFoundation.deviceClosing(device)"
        );
        int centralPrepare = deviceMixin.indexOf(
            "BlockframeRuntime.vulkanDeviceClosing(device)"
        );
        int dlssPrepare = deviceMixin.indexOf(
            "DlssRenderer.prepareDeviceClose()"
        );
        int dlssFinish = deviceMixin.indexOf(
            "DlssRenderer.finishDeviceCloseAfterEncoderDrain()"
        );
        int centralRetirement = deviceMixin.indexOf(
            "completeVulkanRetirementsAfterEncoderDrain()"
        );
        String closeOrder = "foundation=" + nativeFoundationClose
            + " centralPrepare=" + centralPrepare
            + " dlssPrepare=" + dlssPrepare
            + " dlssFinish=" + dlssFinish
            + " centralRetirement=" + centralRetirement;
        assertTrue(nativeFoundationClose >= 0, closeOrder);
        assertTrue(
            centralPrepare > nativeFoundationClose,
            closeOrder
        );
        assertTrue(dlssPrepare > centralPrepare, closeOrder);
        assertTrue(dlssFinish > dlssPrepare, closeOrder);
        assertTrue(centralRetirement > dlssFinish, closeOrder);
        assertFalse(deviceMixin.contains("OpaqueSolidGpuScene"));
        assertTrue(
            renderer.indexOf("public static boolean prepareDeviceClose()")
                < renderer.indexOf(
                    "releaseStreamlineAfterQueueDrainBeforeResourceDestroy("
                )
        );
        assertTrue(
            renderer.indexOf(
                "releaseStreamlineAfterQueueDrainBeforeResourceDestroy("
            )
                < renderer.indexOf(
                    "public static boolean finishDeviceCloseAfterEncoderDrain()"
                )
        );
        String finish = renderer.substring(
            renderer.indexOf(
                "public static boolean finishDeviceCloseAfterEncoderDrain()"
            ),
            renderer.indexOf(
                "public static void close()",
                renderer.indexOf(
                    "public static boolean finishDeviceCloseAfterEncoderDrain()"
                )
            )
        );
        assertFalse(
            finish.contains("DlssBootstrap::shutdownConnectionAndReport")
        );
        assertTrue(renderer.contains("resources::close"));
        assertTrue(renderer.contains("resources::closeRetainingLease"));
        assertTrue(renderer.contains("resources.closeConfirmed()"));
        assertTrue(auxiliary.contains("boolean closeConfirmed()"));
        assertTrue(auxiliary.contains("this.cleanupConfirmed = fullyClosed;"));
        assertTrue(
            auxiliary.indexOf("this.budgetLease = 0L;")
                < auxiliary.indexOf(
                    "this.cleanupConfirmed = true;",
                    auxiliary.indexOf("this.budgetLease = 0L;")
                )
        );
        assertFalse(renderer.contains("previousResources.close(true)"));
        assertTrue(motion.contains("public boolean prepareDeviceClose()"));
        assertTrue(
            motion.contains(
                "public boolean finishDeviceCloseAfterEncoderDrain()"
            )
        );
        assertFalse(motion.contains("graphicsQueue().waitIdle()"));
        int rawDestroy = motion.indexOf("if (!this.destroyRawResources())");
        int leaseRelease = motion.indexOf(
            "this.budgets.release(this.budgetLease)",
            rawDestroy
        );
        assertTrue(rawDestroy >= 0);
        assertTrue(leaseRelease > rawDestroy);
    }

    @Test
    void staticRuntimeClosesOnlyWithFinalMinecraftClient() throws Exception {
        String lifecycle = source(
            "src/main/java/de/morau/nvidiadlss/mixin/MinecraftLifecycleMixin.java"
        );
        String mixinConfig = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/VulkanDeviceMixin.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/BlockframeRuntime.java"
        );
        String mod = source(
            "src/main/java/de/morau/nvidiadlss/NvidiaDlssMod.java"
        );

        assertTrue(lifecycle.contains("@Mixin(Minecraft.class)"));
        assertTrue(
            lifecycle.contains(
                "@WrapMethod(method = \"close\")"
            )
        );
        assertTrue(lifecycle.contains("Operation<Void> original"));
        assertTrue(lifecycle.contains("original.call();"));
        assertTrue(lifecycle.contains("} finally {"));
        assertTrue(
            lifecycle.contains("DlssRenderer.closeClientResourcesAndReport()")
        );
        assertTrue(
            lifecycle.contains("BlockframeRuntime.clientCloseReturned(")
        );
        assertTrue(lifecycle.contains("originalReturnedNormally,"));
        assertTrue(lifecycle.contains("dlssCleanupSucceeded"));
        assertTrue(runtime.contains("this.clientStoppingObserved"));
        assertTrue(runtime.contains("this.clientStoppedObserved"));
        assertTrue(runtime.contains("this.originalCloseReturnedNormally"));
        assertTrue(runtime.contains("this.dlssCleanupSucceeded"));
        assertTrue(runtime.contains("this.engineCleanupSucceeded"));
        assertTrue(mod.contains("BlockframeRuntime.clientStopping();"));
        assertTrue(mod.contains("BlockframeRuntime.clientStopped();"));
        assertTrue(mixinConfig.contains("\"MinecraftLifecycleMixin\""));
        assertFalse(mixinConfig.contains("\"GpuDeviceLifecycleMixin\""));
        assertFalse(
            deviceMixin.contains("BlockframeRuntime.clientCloseReturned(")
        );
    }

    @Test
    void closeCompletionFlagsAndReferencesFollowConfirmedStages()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String motion = source(
            "src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java"
        );

        assertTrue(
            renderer.contains(
                "deviceClosePrepared = DlssLifecycleState.allPrepareStagesComplete("
            )
        );
        assertTrue(
            renderer.contains(
                "deviceCloseFinished = DlssLifecycleState.allFinishStagesComplete("
            )
        );
        assertTrue(
            renderer.indexOf("if (motionCloseFinished && motionGenerator == generator)")
                < renderer.indexOf("motionGenerator = null;", renderer.indexOf(
                    "if (motionCloseFinished && motionGenerator == generator)"
                ))
        );
        assertTrue(
            motion.indexOf("buffer.close();")
                < motion.indexOf("this.frameBuffers[i] = null;")
        );
        assertTrue(motion.contains("if (!this.destroyRawResources())"));
        assertFalse(
            motion.substring(
                motion.indexOf("public boolean prepareDeviceClose()"),
                motion.indexOf(
                    "public boolean finishDeviceCloseAfterEncoderDrain()"
                )
            ).contains("retireAfterGpuUse")
        );
    }

    @Test
    void deviceCleanupFailuresReachClientProofWithoutDeadDeviceRetry()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );

        assertTrue(
            renderer.contains(
                "DEVICE_CLEANUP_PROOF.recordPrepare("
            )
        );
        assertTrue(
            renderer.contains(
                "DEVICE_CLEANUP_PROOF.recordFinish("
            )
        );
        assertTrue(
            renderer.contains(
                "DEVICE_CLEANUP_PROOF.recordDeviceClosed("
            )
        );
        assertTrue(
            renderer.contains(
                "cleanupSucceeded = DEVICE_CLEANUP_PROOF.reportClientClose("
            )
        );
        assertTrue(
            renderer.contains(
                "DlssBootstrap::shutdownConnectionAndReport"
            )
        );
        assertTrue(
            renderer.contains(
                "DlssBootstrap::shutdownUnconnectedBootstrapAndReport"
            )
        );
        int deadDeviceGuard = renderer.indexOf(
            "if (!deviceGenerationBlocked)"
        );
        int retainedMotionRetry = renderer.indexOf(
            "retryRetainedFailedConstruction",
            deadDeviceGuard
        );
        assertTrue(deadDeviceGuard >= 0);
        assertTrue(retainedMotionRetry > deadDeviceGuard);
    }

    @Test
    void lifecycleGenerationResetsOnlyAfterConfirmedConnection()
        throws Exception {
        String deviceMixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/VulkanDeviceMixin.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );

        int connectedGuard = deviceMixin.indexOf(
            "if (DlssBootstrap.connectedTo(device))"
        );
        int generationReset = deviceMixin.indexOf(
            "DlssRenderer.deviceConnected(device);",
            connectedGuard
        );
        assertTrue(connectedGuard >= 0);
        assertTrue(generationReset > connectedGuard);
        assertTrue(
            renderer.contains("if (lifecycleDevice == device)")
        );
        int unresolvedGeneration = renderer.indexOf(
            "if (lifecycleDevice != null && !deviceCloseFinished)"
        );
        int blockedReturn = renderer.indexOf(
            "return;",
            unresolvedGeneration
        );
        int acceptedReset = renderer.indexOf(
            "DEVICE_CLEANUP_PROOF.beginGeneration();",
            unresolvedGeneration
        );
        assertTrue(unresolvedGeneration >= 0);
        assertTrue(blockedReturn > unresolvedGeneration);
        assertTrue(acceptedReset > blockedReturn);
    }

    @Test
    void unconnectedNativeBootstrapHasAStickyFinalCleanupProof()
        throws Exception {
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );

        int connectFailure = bootstrap.indexOf("if (result != 0)");
        int immediateShutdown = bootstrap.indexOf(
            "shutdownUnconnectedBootstrapAndReport();",
            connectFailure
        );
        int uncertainty = bootstrap.indexOf(
            "nativeShutdownUncertain = true"
        );
        int finalGuard = renderer.indexOf(
            "DlssBootstrap::shutdownUnconnectedBootstrapAndReport"
        );
        int finalProof = renderer.indexOf(
            "DEVICE_CLEANUP_PROOF.reportClientClose(",
            finalGuard
        );

        assertTrue(connectFailure >= 0);
        assertTrue(immediateShutdown > connectFailure);
        assertTrue(uncertainty >= 0);
        assertTrue(finalGuard >= 0);
        assertTrue(finalProof > finalGuard);
    }

    @Test
    void failedTargetResizeIsMarkedForRollbackBeforeDestructiveCall()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        int resize = renderer.indexOf(
            "lowTarget.resize(desiredWidth, desiredHeight)"
        );
        int rollbackFlag = renderer.lastIndexOf(
            "resizedTarget = true;",
            resize
        );
        assertTrue(resize >= 0);
        assertTrue(rollbackFlag >= 0);
        assertTrue(rollbackFlag < resize);
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
