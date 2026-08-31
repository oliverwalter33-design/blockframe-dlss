package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase1a12RuntimeSourceContractTest {
    @Test
    void runStateAndPolicyPublishBeforeOptionalClientWork()
        throws Exception {
        String mod = source(
            "src/main/java/de/morau/nvidiadlss/NvidiaDlssMod.java"
        );
        String constructor = section(
            mod,
            "public NvidiaDlssMod(",
            "private static void registerGuiLayers("
        );

        int snapshot = constructor.indexOf("DlssConfig.snapshot()");
        int initialize = constructor.indexOf(
            "BlockframeRuntime.initializeClient("
        );
        int eventRegistration = constructor.indexOf(
            "modBus.addListener("
        );
        int compatibilityProbe = constructor.indexOf(
            "DlssMixinPlugin.sodiumPresent()"
        );
        int foliage = constructor.indexOf("FoliageAudit.announce()");
        assertTrue(snapshot >= 0);
        assertTrue(initialize > snapshot);
        assertTrue(eventRegistration > initialize);
        assertTrue(compatibilityProbe > initialize);
        assertTrue(foliage > initialize);
    }

    @Test
    void persistenceIsBoundedSeparateAndHasNoShutdownHook()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String store = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "RunStateStore.java"
        );
        String codec = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "RunStateCodec.java"
        );

        assertTrue(
            runtime.contains(
                "Path.of(\"config\", \"blockframe-state\")"
            )
        );
        assertTrue(runtime.contains("RunStateStore.open("));
        assertTrue(
            store.contains(
                "run-state-a.bfrs"
            )
        );
        assertTrue(store.contains("run-state-b.bfrs"));
        assertTrue(store.contains("run-state.lock"));
        assertTrue(store.contains("io.writeForced("));
        assertTrue(store.contains("io.atomicReplace("));
        assertTrue(
            store.contains(
                "RunStatePublicationMode.RECOVERABLE_TWO_SLOT"
            )
        );
        assertTrue(codec.contains("MAX_BYTES = 64 * 1024"));
        assertFalse(runtime.contains("addShutdownHook"));
        assertFalse(store.contains("addShutdownHook"));
        assertFalse(runtime.contains("new Thread("));
        assertFalse(store.contains("new Thread("));
    }

    @Test
    void safeStartIsConsumedBeforeVulkanAndCannotMutateNormalConfig()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String instance = section(
            bootstrap,
            "public static void configureInstanceExtensions(",
            "public static void configureDeviceCapabilities("
        );
        String device = section(
            bootstrap,
            "public static void configureDeviceCapabilities(",
            "private static void configureOptionalDeviceFault("
        );
        String nativeLoad = section(
            bootstrap,
            "public static synchronized boolean ensureNativeLoaded()",
            "private static Path streamlineLogDirectory()"
        );

        assertTrue(
            runtime.contains(
                "boolean safeStart ="
            )
        );
        assertTrue(
            runtime.contains(
                "normalPolicy.requestedMask(),\n"
                    + "                0L,\n"
                    + "                0L"
            )
        );
        assertTrue(
            instance.indexOf("BlockframeRuntime.safeStartActive()")
                < instance.indexOf(
                    "selectInstanceExtensions("
                )
        );
        assertTrue(
            device.indexOf("BlockframeRuntime.safeStartActive()")
                < device.indexOf(
                    "VulkanDeviceCapabilityProbe.query("
                )
        );
        assertTrue(
            nativeLoad.indexOf("BlockframeRuntime.safeStartActive()")
                < nativeLoad.indexOf(
                    "NativeRuntimeArtifacts.materialize()"
                )
        );
        assertFalse(runtime.contains("DlssConfig.save("));
        assertFalse(runtime.contains("EngineConfig.save("));
    }

    @Test
    void coldOffCannotMaterializeOrLateBootstrapStreamline()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String policy = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "RuntimeFeaturePolicy.java"
        );
        String bootstrap = source(
            "src/main/java/de/morau/nvidiadlss/DlssBootstrap.java"
        );
        String instance = methodBody(
            bootstrap,
            "public static void configureInstanceExtensions"
        );
        String device = methodBody(
            bootstrap,
            "public static void configureDeviceCapabilities"
        );
        String load = methodBody(
            bootstrap,
            "public static synchronized boolean ensureNativeLoaded"
        );
        String connect = methodBody(
            bootstrap,
            "public static synchronized void connectDevice"
        );
        String rebase = methodBody(
            runtime,
            "private synchronized void rebaseDlssConfiguration"
        );

        assertTrue(
            policy.contains(
                "private final boolean streamlineBootstrapAllowed;"
            )
        );
        assertTrue(
            policy.contains(
                "!safeStart && !\"off\".equals(this.dlssMode)"
            )
        );
        assertTrue(
            instance.indexOf(
                "!BlockframeRuntime.streamlineBootstrapAllowed()"
            )
                < instance.indexOf("selectInstanceExtensions(")
        );
        assertTrue(
            instance.indexOf(
                "!BlockframeRuntime.streamlineBootstrapAllowed()"
            )
                < instance.indexOf("ensureNativeLoaded()")
        );
        assertTrue(
            load.indexOf(
                "!BlockframeRuntime.streamlineBootstrapAllowed()"
            )
                < load.indexOf(
                    "NativeRuntimeArtifacts.materialize()"
                )
        );
        assertTrue(
            load.indexOf(
                "!BlockframeRuntime.streamlineBootstrapAllowed()"
            )
                < load.indexOf("NativeStreamline.bootstrap(")
        );
        assertTrue(
            connect.contains(
                "!BlockframeRuntime.streamlineBootstrapAllowed()"
            )
        );
        assertTrue(
            device.indexOf("configureOptionalDeviceFault(")
                < device.indexOf(
                    "!BlockframeRuntime.streamlineBootstrapAllowed()"
                )
        );
        assertTrue(rebase.contains("dlssRestartRequired()"));
        assertTrue(
            rebase.contains(
                "DLSS_RESTART_REQUIRED_REASON"
            )
        );
        assertTrue(rebase.contains(".restartRequired();"));
        assertTrue(rebase.contains(".clearRestartRequired();"));
    }

    @Test
    void warmDeviceAggregationReusesItsSingleJavaMeasurement()
        throws Exception {
        String probe = source(
            "src/main/java/de/morau/blockframe/vulkan/"
                + "VulkanMemoryBudgetProbe.java"
        );
        String finish = methodBody(probe, "public PhysicalMemoryTelemetry.DeviceMeasurement finish");
        String query = methodBody(
            probe,
            "public PhysicalMemoryTelemetry.DeviceMeasurement query"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String beginFrame = methodBody(runtime, "beginFrame");

        assertTrue(
            probe.contains(
                "private final PhysicalMemoryTelemetry.DeviceMeasurement measurement"
            )
        );
        assertTrue(finish.contains("this.measurement.update("));
        assertFalse(finish.contains("new "));
        assertFalse(
            query.contains(
                "new PhysicalMemoryTelemetry.DeviceMeasurement"
            )
        );
        assertFalse(beginFrame.contains("new "));
        assertFalse(beginFrame.contains("List"));
        assertFalse(beginFrame.contains("StringBuilder"));
    }

    @Test
    void liveDlssChangesRebaseTheFullCanonicalSnapshot()
        throws Exception {
        String config = source(
            "src/main/java/de/morau/nvidiadlss/DlssConfig.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String store = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "RunStateStore.java"
        );
        String mode = section(
            config,
            "public static synchronized void setMode(",
            "public static synchronized void setSharpening("
        );
        String sharpening = section(
            config,
            "public static synchronized void setSharpening(",
            "public static synchronized void setSharpeningAmount("
        );
        String amount = section(
            config,
            "public static synchronized void setSharpeningAmount(",
            "private static void notifyRuntimeConfigurationChanged("
        );
        assertTrue(mode.contains("notifyRuntimeConfigurationChanged();"));
        assertTrue(
            sharpening.contains("notifyRuntimeConfigurationChanged();")
        );
        assertTrue(amount.contains("notifyRuntimeConfigurationChanged();"));

        String notify = methodBody(
            config,
            "private static void notifyRuntimeConfigurationChanged"
        );
        assertTrue(
            notify.contains(
                "BlockframeRuntime.dlssConfigurationChanged("
            )
        );
        assertTrue(notify.contains("snapshot.mode().id()"));
        assertTrue(notify.contains("snapshot.sharpening().id()"));
        assertTrue(notify.contains("snapshot.sharpeningAmount()"));
        assertTrue(
            notify.contains("snapshot.entityHistoryBackend().id()")
        );

        String rebase = methodBody(
            runtime,
            "private synchronized void rebaseDlssConfiguration"
        );
        assertTrue(rebase.contains("FeatureConfigFingerprint.compute("));
        assertTrue(rebase.contains("this.runStateStore.rebaseIdentity("));
        assertTrue(rebase.contains("requestedMask"));
        assertTrue(rebase.contains("effectiveMask"));
        assertTrue(rebase.contains("this.resetStabilityWindow();"));
        assertTrue(rebase.contains("this.configurationChanged = false;"));
        assertTrue(
            rebase.indexOf("this.runStateStore.rebaseIdentity(")
                < rebase.indexOf(
                    "this.featurePolicy.updateLiveDlssMode("
                )
        );

        String storeRebase = methodBody(
            store,
            "public synchronized boolean rebaseIdentity"
        );
        assertTrue(
            storeRebase.contains(
                "this.snapshot.phase() == RunPhase.FAILED"
            )
        );
        assertTrue(storeRebase.contains("this.identity = next;"));
        assertTrue(storeRebase.contains("this.writeRecord(candidate)"));
        assertTrue(
            storeRebase.indexOf("this.identity = next;")
                < storeRebase.indexOf("this.writeRecord(candidate)")
        );
        assertTrue(storeRebase.contains("this.snapshot = candidate;"));
    }

    @Test
    void stablePromotionRequiresCompletedFrameAndExactWindow()
        throws Exception {
        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "GameRendererMixin.java"
        );
        String guard = methodBody(
            mixin,
            "blockframe$guardMeasuredFrame"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String tracker = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "WorldFrameStabilityTracker.java"
        );
        String store = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "RunStateStore.java"
        );
        String successful = methodBody(
            runtime,
            "private void recordSuccessfulWorldFrame"
        );
        String resource = methodBody(
            runtime,
            "private void resourceLoadFinished"
        );
        String failedFrame = methodBody(
            runtime,
            "private void recordFailedWorldFrame"
        );
        String unavailable = methodBody(
            runtime,
            "private void worldUnavailable"
        );
        String revalidating = methodBody(
            store,
            "public synchronized boolean markStabilityRevalidating"
        );
        int original = guard.indexOf("original.call(");
        int restored = guard.indexOf(
            "DlssRenderer.restoreOriginalTarget("
        );
        int ended = guard.indexOf("BlockframeRuntime.endFrame();");
        int recorded = guard.indexOf(
            "BlockframeRuntime.recordSuccessfulWorldFrame("
        );
        assertTrue(original >= 0);
        assertTrue(restored > original);
        assertTrue(ended > restored);
        assertTrue(recorded > ended);
        assertTrue(guard.contains("originalReturnedNormally"));
        assertTrue(guard.contains("finalizationSucceeded"));
        assertTrue(guard.contains("this.minecraft.isGameLoadFinished()"));
        assertTrue(guard.contains("advanceGameTime"));
        assertTrue(guard.contains("this.minecraft.level != null"));

        assertTrue(runtime.contains("STABLE_WORLD_FRAME_COUNT = 120"));
        assertTrue(
            runtime.indexOf("observeSuccessfulFrame(currentWorld)")
                < runtime.indexOf(
                    "this.runStateStore.markStable("
                )
        );
        assertTrue(
            tracker.contains(
                "this.consecutiveFrames == this.requiredFrames"
            )
        );
        assertTrue(successful.contains("this.stability.tracksWorld("));
        assertFalse(
            successful.contains("STABLE_WORLD_FRAME_COUNT - 1")
        );
        assertTrue(
            successful.contains(
                "this.runStateStore.markStabilityRevalidating("
            )
        );
        assertTrue(
            successful.indexOf(
                "this.runStateStore.markStabilityRevalidating("
            )
                < successful.indexOf(
                    "this.stability.observeSuccessfulFrame("
                )
        );
        assertTrue(
            resource.contains(
                "this.runStateStore.markStabilityRevalidating("
            )
        );
        assertTrue(resource.contains("this.stability.clearWorld();"));
        assertTrue(resource.contains("this.refreshRunStateDebugLines();"));
        assertTrue(
            failedFrame.contains(
                "this.runStateStore.markStabilityRevalidating("
            )
        );
        assertTrue(failedFrame.contains("this.resetStabilityWindow();"));
        assertTrue(
            failedFrame.contains("this.refreshRunStateDebugLines();")
        );
        assertTrue(
            unavailable.contains(
                "this.runStateStore.markStabilityRevalidating("
            )
        );
        assertTrue(unavailable.contains("this.stability.clearWorld();"));
        assertTrue(
            unavailable.contains("if (priorWindow || hadWorld)")
        );
        assertTrue(
            unavailable.contains("this.refreshRunStateDebugLines();")
        );
        assertTrue(
            revalidating.contains(
                "RunCheckpoint.ACTIVE_FEATURES_PUBLISHED"
            )
        );
        assertTrue(
            revalidating.contains("this.snapshot.lastKnownGood()")
        );
    }

    @Test
    void deviceLossIsPersistedBeforeOptionalCapture()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String method = methodBody(
            runtime,
            "recordVulkanDeviceLost"
        );
        int failed = method.indexOf(
            "ConfirmedRunError.DEVICE_LOSS"
        );
        int capture = method.indexOf(
            "current.engine.recordVulkanResult("
        );
        assertTrue(failed >= 0);
        assertTrue(capture > failed);
        assertTrue(method.contains("\"vk-error-device-lost\""));
        assertFalse(method.contains("context, context"));
    }

    @Test
    void f8AndWarmFramePathsUseCachedStateOnly() throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String overlay = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "DlssDebugOverlay.java"
        );
        String beginFrame = methodBody(runtime, "beginFrame");
        String f8 = methodBody(runtime, "runStateDebugLines");
        String tracker = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "WorldFrameStabilityTracker.java"
        );
        String trackerHot = methodBody(
            tracker,
            "observeSuccessfulFrame"
        );

        assertTrue(
            overlay.contains(
                "BlockframeRuntime.runStateDebugLines()"
            )
        );
        assertTrue(f8.contains("current.runStateDebugLines"));
        assertFalse(f8.contains("runStateStore.snapshot()"));
        assertFalse(f8.contains("Files."));
        assertFalse(f8.contains("Vulkan"));
        assertFalse(beginFrame.contains("RunStateStore"));
        assertFalse(beginFrame.contains("Files."));
        assertFalse(beginFrame.contains("Path."));
        assertFalse(beginFrame.contains("new "));
        assertFalse(trackerHot.contains("new "));
        assertFalse(trackerHot.contains("List"));
        assertFalse(trackerHot.contains("StringBuilder"));
    }

    @Test
    void lifecycleCleanMarkerRequiresAllNormalProofs()
        throws Exception {
        String lifecycle = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "MinecraftLifecycleMixin.java"
        );
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String finalize = methodBody(
            runtime,
            "private void tryFinalizeClientClose"
        );
        String failed = methodBody(
            runtime,
            "private void markConfirmedFailure"
        );

        assertTrue(lifecycle.contains("originalReturnedNormally"));
        assertTrue(lifecycle.contains("dlssCleanupSucceeded"));
        assertTrue(
            lifecycle.indexOf("DlssRenderer.closeClientResources")
                < lifecycle.indexOf(
                    "BlockframeRuntime.clientCloseReturned("
                )
        );
        assertTrue(finalize.contains("this.clientStoppingObserved"));
        assertTrue(finalize.contains("this.clientStoppedObserved"));
        assertTrue(
            finalize.contains("this.originalCloseReturnedNormally")
        );
        assertTrue(finalize.contains("cleanupSucceeded"));
        assertTrue(
            finalize.indexOf("if (cleanProof)")
                < finalize.indexOf(
                    "this.runStateStore.markCleanShutdown("
                )
        );
        assertTrue(finalize.contains("this.featureStates"));
        assertTrue(finalize.contains(".effectiveMask()"));
        assertTrue(
            finalize.contains(
                "persisted.phase() == RunPhase.FAILED"
            )
        );
        assertTrue(
            finalize.contains(
                "? persisted.effectiveFeatureMask()"
            )
        );
        assertTrue(failed.contains("this.runStateStore.markFailed("));
        assertTrue(failed.contains("this.featureStates"));
        assertTrue(failed.contains(".effectiveMask()"));
    }

    @Test
    void mixedConsumerOutcomesBecomeStickyWithoutHotpathOscillation()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String effective = methodBody(
            runtime,
            "private void publishFeatureEffective"
        );
        String fallback = methodBody(
            runtime,
            "private void publishFeatureFallback"
        );

        assertTrue(effective.contains("current.effective()"));
        assertTrue(effective.contains("current.fallback()"));
        assertTrue(effective.contains("if (current.quarantined())"));
        assertTrue(effective.contains("!current.quarantined()"));
        assertTrue(fallback.contains("current.fallback()"));
        assertTrue(fallback.contains("current.effective()"));
        assertTrue(fallback.contains("quarantined ? false"));
    }

    @Test
    void deviceGenerationRestartsStabilityWithoutDiscardingLkg()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String store = source(
            "src/main/java/de/morau/blockframe/core/state/"
                + "RunStateStore.java"
        );
        String backendChanged = methodBody(
            runtime,
            "private void backendChanged"
        );
        String reinitializing = methodBody(
            store,
            "markDeviceReinitializing"
        );

        assertTrue(
            backendChanged.contains(
                "this.runStateStore.markDeviceReinitializing("
            )
        );
        assertTrue(
            reinitializing.contains("RunPhase.INITIALIZING")
        );
        assertTrue(
            reinitializing.contains(
                "RunCheckpoint.BACKEND_INITIALIZED"
            )
        );
        assertTrue(
            reinitializing.contains(
                "this.snapshot.lastKnownGood()"
            )
        );
        assertFalse(
            reinitializing.contains(
                "new RunStateRecord.LastKnownGood"
            )
        );
    }

    @Test
    void deviceRecreationRetainsProcessOwnerStateInTheNewGeneration()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String closing = methodBody(
            runtime,
            "private void vulkanDeviceClosing"
        );
        String baseline = methodBody(
            runtime,
            "private void publishBackendBaseline"
        );

        assertTrue(closing.contains("boolean processFeature"));
        assertTrue(
            closing.contains(
                "processFeature && state.effective()"
            )
        );
        assertTrue(
            closing.contains(
                "? state.fallback()\n"
                    + "                        : state.requested()"
            )
        );
        assertTrue(
            closing.contains(
                "processFeature && state.quarantined()"
            )
        );
        assertTrue(
            baseline.contains(
                "old.effective()\n"
                    + "                        && old.enabled()\n"
                    + "                        && supported"
            )
        );
        assertTrue(
            baseline.contains("old.quarantined()")
        );
    }

    @Test
    void physicalFeatureUsesOnlyCachedChannelStatusAndNotPolicyAlone()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String begin = methodBody(runtime, "public static void beginFrame");
        String observe = methodBody(
            runtime,
            "private void publishPhysicalMemoryState"
        );
        String diagnostics = methodBody(
            runtime,
            "private void observeCachedDiagnosticStates"
        );
        String baseline = methodBody(
            runtime,
            "private void publishBackendBaseline"
        );
        String closing = methodBody(
            runtime,
            "private void vulkanDeviceClosing"
        );

        assertFalse(begin.contains("FeatureId.PHYSICAL_MEMORY"));
        assertTrue(
            diagnostics.contains("this.publishPhysicalMemoryState(false)")
        );
        assertTrue(observe.contains("this.engine.physicalMemorySnapshot()"));
        assertTrue(observe.contains("memory.ramStatus()"));
        assertTrue(observe.contains("memory.deviceStatus()"));
        assertTrue(
            observe.contains("PhysicalMemoryFeatureAvailability.from(")
        );
        assertTrue(
            baseline.contains("this.publishPhysicalMemoryState(true)")
        );
        assertTrue(
            closing.contains("this.publishPhysicalMemoryState(true)")
        );
    }

    @Test
    void cachedPublicationComparesReasonQuarantineAndGenerations()
        throws Exception {
        String runtime = source(
            "src/main/java/de/morau/blockframe/core/"
                + "BlockframeRuntime.java"
        );
        String publish = methodBody(
            runtime,
            "private void publishIfChanged"
        );

        assertTrue(publish.contains("fallback || !enabled || !supported"));
        assertTrue(
            publish.contains(
                "boolean actualQuarantined = requested && quarantined"
            )
        );
        assertTrue(publish.contains("old.reason().equals(actualReason)"));
        assertTrue(
            publish.contains(
                "old.clientGeneration() == this.clientGeneration"
            )
        );
        assertTrue(
            publish.contains(
                "old.deviceGeneration() == this.deviceGeneration"
            )
        );
    }

    private static String methodBody(String source, String methodName) {
        int name = source.indexOf(methodName + "(");
        assertTrue(name >= 0, "missing method " + methodName);
        int open = source.indexOf('{', name);
        assertTrue(open > name, "missing body " + methodName);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, index);
                }
            }
        }
        throw new AssertionError("unterminated method " + methodName);
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(
            endMarker,
            start + startMarker.length()
        );
        assertTrue(start >= 0, "missing marker: " + startMarker);
        assertTrue(end > start, "missing marker: " + endMarker);
        return source.substring(start, end);
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(
            System.getProperty("blockframe.projectDir")
        );
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
