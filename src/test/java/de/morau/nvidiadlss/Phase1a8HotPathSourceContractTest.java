package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Source-level inventory for the Phase 1A warmed render boundary.
 *
 * <p>The sets below are intentionally explicit. Adding a configured mixin or
 * an injected BlockFrame hook must classify it as warmed-normal,
 * debug/resource-setup, setup/lifecycle, or external-owned before this test
 * accepts it.</p>
 */
class Phase1a8HotPathSourceContractTest {
    private static final String MIXIN_SOURCE =
        "src/main/java/de/morau/nvidiadlss/mixin/";

    private static final Set<String> WARMED_NORMAL_HOOKS = Set.of(
        "GameRendererMixin#blockframe$guardMeasuredFrame",
        "GameRendererMixin#nvidiaDlss$useLowResolutionWorldTarget",
        "GameRendererMixin#nvidiaDlss$applyWorldJitter",
        "GameRendererMixin#nvidiaDlss$deferBlockOutline",
        "GameRendererMixin#nvidiaDlss$evaluateAfterWorldBeforeHand",
        "LevelRendererMixin#nvidiaDlss$useCrispNativeOutlineColor",
        "LevelRendererMixin#nvidiaDlss$useCrispNativeOutlineWidth",
        "ModelFeatureRendererMixin#nvidiaDlss$captureExactAvatarGeometry",
        "VulkanGpuSurfaceMixin#nvidiaDlss$presentThroughStreamline",
        "VulkanRenderPassMixin#nvidiaDlss$trackAndAuditAtlasSampler",
        "VulkanRenderPassMixin#nvidiaDlss$selectMaterialSamplerAtDescriptor"
    );

    private static final Set<String> DEBUG_OR_RESOURCE_SETUP_HOOKS = Set.of(
        "GameRendererDiagnosticsMixin#blockframe$measureFrame",
        "GameRendererDiagnosticsMixin#blockframe$recordVisibleSections",
        "GameRendererDiagnosticsMixin#blockframe$captureFinalWithoutHud",
        "GameRendererDiagnosticsMixin#blockframe$captureFinalWithHud",
        "GameRendererDiagnosticsMixin#blockframe$closeDiagnostics",
        "GlRenderPassMixin#nvidiaDlss$auditBoundAtlasSampler",
        "LevelExtractorTelemetryMixin#blockframe$measureFrustumTraversal",
        "LevelRendererDiagnosticsMixin#blockframe$measureOcclusionUpdate",
        "MipmapGeneratorMixin#nvidiaDlss$auditLeafMipmaps",
        "NativeTerrainDispatcherEvidenceMixin#"
            + "blockframe$recordMojangTerrainUpload",
        "NativeTerrainOpaqueSubmissionEvidenceMixin#"
            + "blockframe$recordMojangOpaqueSubmission",
        "NativeTerrainSectionCompilerEvidenceMixin#"
            + "blockframe$recordMojangTerrainCompile",
        "NativeTerrainUberHeapEvidenceMixin#"
            + "blockframe$recordMojangTerrainHeap",
        "ShaderCompilationCacheMixin#nvidiaDlss$injectFoliageDebugView",
        "SodiumShaderChunkRendererMixin#nvidiaDlss$markCutoutPipeline",
        "SpriteContentsMixin#nvidiaDlss$auditLeafMipmapStrategy",
        "RenderPassTelemetryMixin#blockframe$countDrawIndexed",
        "RenderPassTelemetryMixin#blockframe$countMultiDrawIndexedInterleaved",
        "RenderPassTelemetryMixin#blockframe$countMultiDrawIndexedSeparate",
        "RenderPassTelemetryMixin#blockframe$countDrawIndexedIndirect",
        "RenderPassTelemetryMixin#blockframe$countDrawMultipleIndexed",
        "RenderPassTelemetryMixin#blockframe$countDraw",
        "RenderPassTelemetryMixin#blockframe$countMultiDrawInterleaved",
        "RenderPassTelemetryMixin#blockframe$countMultiDrawSeparate",
        "RenderPassTelemetryMixin#blockframe$countDrawIndirect",
        "StagingBufferUploaderTelemetryMixin#blockframe$measureUploadCopy",
        "VulkanCommandEncoderDiagnosticsMixin#blockframe$traceGraphicsSubmit",
        "VulkanCommandEncoderDiagnosticsMixin#blockframe$recordSubmit",
        "VulkanCommandEncoderDiagnosticsMixin#blockframe$recordCompletion",
        "VulkanDeviceDiagnosticsMixin#blockframe$connectDiagnostics",
        "VulkanDeviceDiagnosticsMixin#blockframe$finishDiagnosticsClose",
        "VulkanUtilsDeviceFaultMixin#blockframe$captureDeviceFault"
    );

    private static final Set<String> SETUP_OR_LIFECYCLE_HOOKS = Set.of(
        "ClientPacketListenerMixin#nvidiaDlss$resetAfterPlayerTeleport",
        "ClientPacketListenerMixin#nvidiaDlss$resetAfterEntityTeleport",
        "FastStartLevelLoadingScreenMixin#blockframe$fastStartChunksReady",
        "FastStartResourceReloadMixin#blockframe$fastStartResourceReloadBegin",
        "GameRendererMixin#nvidiaDlss$resetHistoryForRendererChange",
        "GameRendererMixin#nvidiaDlss$close",
        "LevelRendererMixin#blockframe$createSelectedTerrainWorldResources",
        "LevelRendererMixin#blockframe$terrainWorldResourcesClosed",
        "MinecraftLifecycleMixin#blockframe$closeRuntimeWithClient",
        "NativeTerrainModelManagerMixin#blockframe$invalidateNativeTerrainCensus",
        "NativeTerrainModelManagerMixin#blockframe$captureNativeTerrainCensus",
        "SodiumConfigBuilderMixin#nvidiaDlss$insertAboveVsync",
        "VideoSettingsScreenMixin#nvidiaDlss$insertAboveVsync",
        "VulkanBackendMixin#blockframe$guardActualVulkanDeviceCreation",
        "VulkanBackendMixin#nvidiaDlss$enableDeviceRequirements",
        "VulkanConstMixin#nvidiaDlss$addStorageUsage",
        "VulkanCommandEncoderLifecycleMixin#"
            + "blockframe$releaseStreamlineBeforeResourceDestroy",
        "VulkanDeviceMixin#nvidiaDlss$connectStreamline",
        "VulkanDeviceMixin#blockframe$prepareDeviceClose",
        "VulkanDeviceMixin#blockframe$finishDeviceClose",
        "VulkanDeviceMixin#blockframe$sealClosedDeviceOwners",
        "VulkanGpuSamplerMixin#nvidiaDlss$applyConstructionBias",
        "VulkanInstanceMixin#nvidiaDlss$enableInstanceExtensions"
    );

    private static final Set<String> EXTERNAL_OWNED_MIXINS = Set.of(
        "CommandEncoderAccessor",
        "GpuDeviceAccessor",
        "LevelRendererAccessor",
        "OptionBuilderAccessor",
        "RenderPassAccessor",
        "StagingBufferInvoker",
        "VulkanCommandEncoderAccessor",
        "VulkanQueryPoolAccessor",
        "VulkanTextureViewAndSamplerAccessor"
    );

    private static final Set<String> READ_ONLY_MODEL_CENSUS_ACCESSORS =
        Set.of(
            "ModelPartAccessor",
            "NativeTerrainMultiPartModelAccessor",
            "NativeTerrainWeightedVariantsAccessor"
        );

    private static final Set<String> WARMED_NORMAL_HELPERS = Set.of(
        "GameRendererMixin#nvidiaDlss$renderNativeBlockOutline",
        "VulkanRenderPassMixin#nvidiaDlss$containsIgnoreCase"
    );

    private static final Pattern HOOK_ANNOTATION = Pattern.compile(
        "^\\s*@(Inject|Redirect|ModifyArg|ModifyExpressionValue"
            + "|WrapMethod|WrapOperation|ModifyReturnValue|ModifyVariable)\\b"
    );
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "^\\s*(?:private|protected|public)\\s+.*?"
            + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("
    );
    private static final Pattern EXPLICIT_CONSTRUCTION = Pattern.compile(
        "\\bnew\\s+[A-Za-z_$]"
    );

    @Test
    void everyConfiguredMixinAndInjectedHookHasOneExplicitBoundary()
        throws Exception {
        Set<String> classifiedHooks = allClassifiedHooks();
        assertPairwiseDisjoint(
            WARMED_NORMAL_HOOKS,
            DEBUG_OR_RESOURCE_SETUP_HOOKS,
            SETUP_OR_LIFECYCLE_HOOKS
        );

        Set<String> hookMixins = mixinNames(classifiedHooks);
        Set<String> classifiedMixins = new TreeSet<>(hookMixins);
        classifiedMixins.addAll(EXTERNAL_OWNED_MIXINS);
        classifiedMixins.addAll(READ_ONLY_MODEL_CENSUS_ACCESSORS);
        assertEquals(
            configuredMixins(),
            classifiedMixins,
            "A configured mixin silently crossed the explicit ownership "
                + "inventory"
        );

        Set<String> discoveredHooks = new TreeSet<>();
        for (String mixin : hookMixins) {
            discoveredHooks.addAll(discoverHooks(mixin));
        }
        assertEquals(
            classifiedHooks,
            discoveredHooks,
            "A BlockFrame injection hook was added, removed, or renamed "
                + "without classifying its warmed/setup/debug boundary"
        );
    }

    @Test
    void warmedNormalHooksAndDirectHelpersDoNotConstructTemporaries()
        throws Exception {
        Set<String> warmedMethods = new TreeSet<>(WARMED_NORMAL_HOOKS);
        warmedMethods.addAll(WARMED_NORMAL_HELPERS);
        for (String key : warmedMethods) {
            String mixin = key.substring(0, key.indexOf('#'));
            String method = key.substring(key.indexOf('#') + 1);
            String body = methodBody(
                source(MIXIN_SOURCE + mixin + ".java"),
                method,
                1
            );
            assertNoExplicitConstruction(key, body);
        }
    }

    @Test
    void deferredOutlineUsesLazyReusableScratchAndFinallyReleasesIt()
        throws Exception {
        String gameRenderer = source(
            MIXIN_SOURCE + "GameRendererMixin.java"
        );
        String outline = methodBody(
            gameRenderer,
            "nvidiaDlss$renderNativeBlockOutline",
            1
        );
        String scratch = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "NativeBlockOutlinePoseStackScratch.java"
        );
        String scratchState = source(
            "src/main/java/de/morau/nvidiadlss/"
                + "RenderThreadPoseStackScratch.java"
        );

        assertFalse(outline.contains("new PoseStack("));
        assertNoExplicitConstruction(
            "GameRendererMixin#nvidiaDlss$renderNativeBlockOutline",
            outline
        );
        assertTrue(
            outline.contains(
                "NativeBlockOutlinePoseStackScratch"
                    + "\n                        .createForCurrentThread()"
            )
        );
        assertTrue(
            gameRenderer.indexOf("createForCurrentThread()")
                > gameRenderer.indexOf(
                    "private void nvidiaDlss$renderNativeBlockOutline()"
                ),
            "scratch construction must remain lazy at its first consumer"
        );

        int beginUse = outline.indexOf("scratch.beginUse()");
        int submit = outline.indexOf(
            ".nvidiaDlss$submitBlockOutline(",
            beginUse
        );
        int completed = outline.indexOf(
            "submissionCompleted = true;",
            submit
        );
        int endUse = outline.indexOf(
            "scratch.endUse(outlinePose, submissionCompleted);",
            completed
        );
        int releaseFinally = outline.lastIndexOf("finally {", endUse);
        int renderFeatures = outline.indexOf(
            "this.featureRenderDispatcher.renderAllFeatures(",
            endUse
        );
        assertTrue(beginUse >= 0);
        assertTrue(submit > beginUse);
        assertTrue(completed > submit);
        assertTrue(releaseFinally > completed);
        assertTrue(endUse > releaseFinally);
        assertTrue(renderFeatures > endUse);
        assertTrue(
            outline.indexOf(
                "DlssRenderer.endNativeBlockOutlinePass();"
            ) > renderFeatures
        );
        assertTrue(
            outline.indexOf("modelViewStack.popMatrix();")
                > renderFeatures
        );

        String beginUseBody = methodBody(scratchState, "beginUse", 1);
        String endUseBody = methodBody(scratchState, "endUse", 1);
        String freshFallback = methodBody(
            scratchState,
            "freshFallback",
            1
        );
        assertTrue(beginUseBody.contains("Thread.currentThread()"));
        assertTrue(beginUseBody.contains("this.inUse"));
        assertTrue(beginUseBody.contains("this.access.isEmpty(stack)"));
        assertTrue(beginUseBody.contains("this.access.setIdentity(stack)"));
        assertTrue(beginUseBody.contains("return this.freshFallback();"));
        assertTrue(endUseBody.contains("submissionCompleted"));
        assertTrue(endUseBody.contains("this.access.isEmpty(stack)"));
        assertTrue(endUseBody.contains("this.access.setIdentity(stack)"));
        assertTrue(endUseBody.contains("finally {"));
        assertTrue(endUseBody.contains("this.inUse = false;"));
        assertTrue(
            freshFallback.contains("return this.access.createFresh();")
        );
        assertTrue(
            methodBody(scratch, "createFresh", 1)
                .contains("return new PoseStack();")
        );
        assertTrue(
            methodBody(scratch, "isEmpty", 1)
                .contains("return stack.isEmpty();")
        );
        assertTrue(
            methodBody(scratch, "setIdentity", 1)
                .contains("stack.setIdentity();")
        );

        String reset = methodBody(
            gameRenderer,
            "nvidiaDlss$resetHistoryForRendererChange",
            1
        );
        String close = methodBody(
            gameRenderer,
            "nvidiaDlss$close",
            1
        );
        String clear = methodBody(
            gameRenderer,
            "nvidiaDlss$clearNativeBlockOutlinePoseScratch",
            1
        );
        assertTrue(
            reset.contains(
                "this.nvidiaDlss$clearNativeBlockOutlinePoseScratch();"
            )
        );
        assertTrue(
            close.contains(
                "this.nvidiaDlss$clearNativeBlockOutlinePoseScratch();"
            )
        );
        assertTrue(
            clear.indexOf(
                "this.nvidiaDlss$nativeBlockOutlinePoseScratch = null;"
            ) < clear.indexOf("scratch.clear();")
        );
    }

    @Test
    void knownAllocatingPathsRemainFailureOrDebugBoundaries()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String fixedCollector = methodBody(
            renderer,
            "collectMotionObjects",
            1
        );
        String legacyCollector = methodBody(
            renderer,
            "collectLegacyMotionObjects",
            2
        );
        assertFalse(fixedCollector.contains("new HashMap"));
        assertFalse(fixedCollector.contains("new ArrayList"));
        assertFalse(
            fixedCollector.contains(
                "new MotionVectorGenerator.MotionObject"
            )
        );
        assertTrue(legacyCollector.contains("new HashMap<>();"));
        assertTrue(legacyCollector.contains("new ArrayList<>();"));
        assertTrue(legacyCollector.contains("new EntityFrame("));
        assertTrue(
            legacyCollector.contains(
                "new MotionVectorGenerator.MotionObject("
            )
        );

        String finishWorldFrame = methodBody(
            renderer,
            "finishWorldFrame",
            1
        );
        int fixedAttempt = finishWorldFrame.indexOf(
            "fixedMovingObjectCount = collectMotionObjects("
        );
        int legacyFallback = finishWorldFrame.indexOf(
            "collectLegacyMotionObjects(",
            fixedAttempt
        );
        assertTrue(fixedAttempt >= 0);
        assertTrue(legacyFallback > fixedAttempt);
        assertTrue(
            finishWorldFrame.indexOf(
                "motionObjectFallbackFrames++;",
                legacyFallback
            ) > legacyFallback
        );

        int transformFallback = finishWorldFrame.indexOf(
            "if (!transformScratchFrame) {"
        );
        int transformFallbackEnd = finishWorldFrame.indexOf(
            "lastTransformScratchPath = transformScratchFrame;",
            transformFallback
        );
        String transformFallbackBody = finishWorldFrame.substring(
            transformFallback,
            transformFallbackEnd
        );
        assertTrue(
            transformFallbackBody.contains(
                "new Matrix4f(unjitteredProjection)"
            )
        );
        assertTrue(transformFallbackBody.contains("new Vector3f("));
        assertTrue(
            transformFallbackBody.contains(
                "transformScratchFallbackFrames++;"
            )
        );

        String foliage = source(
            "src/main/java/de/morau/nvidiadlss/FoliageAudit.java"
        );
        String samplerAudit = methodBody(foliage, "recordSampler", 1);
        assertTrue(
            samplerAudit.contains(
                "if (!ENABLED || view == null || sampler == null) return;"
            )
        );
        String capture = source(
            "src/main/java/de/morau/nvidiadlss/DlssDebugCapture.java"
        );
        assertTrue(
            methodBody(capture, "captureBeforeEvaluate", 1)
                .contains("if (!singleFrame && !isSequenceFrame(frame)) {")
        );
        assertTrue(
            methodBody(capture, "captureFinalWithoutHud", 1)
                .contains("if (session == null || session.postRenderLevelDone) {")
        );
    }

    @Test
    void samplerOpenGlAndLifecycleFallbacksRemainAuthoritative()
        throws Exception {
        String renderer = source(
            "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
        );
        String beginFrame = methodBody(renderer, "beginFrame", 1);
        assertTrue(
            beginFrame.contains(
                "highTarget.getColorTexture() instanceof VulkanGpuTexture"
            )
        );
        assertTrue(
            beginFrame.contains(
                "DlssStatus.unavailable("
                    + "\"Das aktive Grafik-Backend ist nicht Vulkan\""
            )
        );
        assertTrue(
            beginFrame.indexOf(
                "DlssStatus.unavailable("
                    + "\"Das aktive Grafik-Backend ist nicht Vulkan\""
            ) < beginFrame.lastIndexOf("return highTarget;")
        );

        String vulkanPass = source(
            MIXIN_SOURCE + "VulkanRenderPassMixin.java"
        );
        String descriptor = methodBody(
            vulkanPass,
            "nvidiaDlss$selectMaterialSamplerAtDescriptor",
            1
        );
        assertNoExplicitConstruction(
            "VulkanRenderPassMixin#"
                + "nvidiaDlss$selectMaterialSamplerAtDescriptor",
            descriptor
        );
        assertTrue(
            descriptor.contains("DlssSamplerPolicy.materialSampler(")
        );
        assertTrue(descriptor.contains("catch (Throwable error)"));
        assertTrue(
            descriptor.lastIndexOf("return original;")
                > descriptor.indexOf("catch (Throwable error)")
        );
        assertTrue(descriptor.contains("&& FoliageAudit.enabled()"));

        String policy = source(
            "src/main/java/de/morau/nvidiadlss/DlssSamplerPolicy.java"
        );
        String materialSampler = methodBody(
            policy,
            "materialSampler",
            1
        );
        assertTrue(materialSampler.contains("return original;"));
        assertTrue(
            materialSampler.contains(
                "RuntimeException"
                    + "\n                | LinkageError"
                    + "\n                | OutOfMemoryError error"
            )
        );
        assertFalse(materialSampler.contains("catch (Throwable error)"));
        assertFalse(
            source(MIXIN_SOURCE + "GlRenderPassMixin.java")
                .contains("DlssSamplerPolicy")
        );

        String frameGuard = methodBody(
            source(MIXIN_SOURCE + "GameRendererMixin.java"),
            "blockframe$guardMeasuredFrame",
            1
        );
        assertTrue(frameGuard.contains("finally {"));
        assertTrue(
            frameGuard.indexOf("DlssRenderer.restoreOriginalTarget(")
                < frameGuard.indexOf("BlockframeRuntime.endFrame();")
        );

        String clientClose = source(
            MIXIN_SOURCE + "MinecraftLifecycleMixin.java"
        );
        assertTrue(clientClose.contains("finally {"));
        assertTrue(
            clientClose.indexOf(
                "DlssRenderer.closeClientResourcesAndReport();"
            ) < clientClose.indexOf(
                "BlockframeRuntime.clientCloseReturned("
            )
        );
        String deviceClose = source(
            MIXIN_SOURCE + "VulkanDeviceMixin.java"
        );
        String sealedClose = methodBody(
            deviceClose,
            "blockframe$sealClosedDeviceOwners",
            1
        );
        assertTrue(sealedClose.contains("finally {"));
        assertTrue(
            sealedClose.contains("DlssRenderer.deviceClosed(")
        );
    }

    @Test
    void externalOwnedRedirectsCallMinecraftBeforeRecording()
        throws Exception {
        String drawTelemetry = source(
            MIXIN_SOURCE + "RenderPassTelemetryMixin.java"
        );
        for (String key : WARMED_NORMAL_HOOKS) {
            if (!key.startsWith("RenderPassTelemetryMixin#")) {
                continue;
            }
            String method = key.substring(key.indexOf('#') + 1);
            String body = methodBody(drawTelemetry, method, 1);
            assertTrue(
                body.indexOf("backend.")
                    < body.indexOf("BlockframeRuntime.recordDrawCall();"),
                method + " must preserve the Mojang-owned draw first"
            );
        }

        String upload = methodBody(
            source(
                MIXIN_SOURCE
                    + "StagingBufferUploaderTelemetryMixin.java"
            ),
            "blockframe$measureUploadCopy",
            1
        );
        assertTrue(
            upload.indexOf(".blockframe$copyTo(")
                < upload.indexOf("BlockframeRuntime.recordUpload(")
        );
        String traversal = methodBody(
            source(MIXIN_SOURCE + "LevelExtractorTelemetryMixin.java"),
            "blockframe$measureFrustumTraversal",
            1
        );
        assertTrue(
            traversal.indexOf("graph.addSectionsInFrustum(")
                < traversal.indexOf("BlockframeRuntime.recordCpuCull(")
        );

        for (String mixin : EXTERNAL_OWNED_MIXINS) {
            String accessor = source(mixinSource(mixin));
            assertTrue(accessor.contains("public interface " + mixin));
            assertTrue(
                accessor.contains("@Accessor(")
                    || accessor.contains("@Invoker(")
            );
            assertFalse(
                EXPLICIT_CONSTRUCTION
                    .matcher(codeOnly(accessor))
                    .find(),
                mixin + " must remain a view onto Minecraft ownership"
            );
        }
        for (String mixin : READ_ONLY_MODEL_CENSUS_ACCESSORS) {
            String accessor = source(
                MIXIN_SOURCE + "accessor/" + mixin + ".java"
            );
            assertTrue(accessor.contains("public interface " + mixin));
            assertTrue(accessor.contains("@Accessor("));
            assertTrue(accessor.contains("Read-only"));
            assertFalse(accessor.contains("@Invoker("));
            assertFalse(
                EXPLICIT_CONSTRUCTION
                    .matcher(codeOnly(accessor))
                    .find(),
                mixin + " must remain a read-only model view"
            );
        }
    }

    private static Set<String> allClassifiedHooks() {
        Set<String> result = new TreeSet<>(WARMED_NORMAL_HOOKS);
        result.addAll(DEBUG_OR_RESOURCE_SETUP_HOOKS);
        result.addAll(SETUP_OR_LIFECYCLE_HOOKS);
        return result;
    }

    @SafeVarargs
    private static void assertPairwiseDisjoint(Set<String>... sets) {
        Set<String> seen = new HashSet<>();
        for (Set<String> set : sets) {
            for (String value : set) {
                assertTrue(
                    seen.add(value),
                    value + " has more than one boundary classification"
                );
            }
        }
    }

    private static Set<String> mixinNames(Set<String> hooks) {
        Set<String> result = new TreeSet<>();
        for (String hook : hooks) {
            result.add(hook.substring(0, hook.indexOf('#')));
        }
        return result;
    }

    private static Set<String> configuredMixins() throws Exception {
        String json = source(
            "src/main/resources/nvidia_dlss.mixins.json"
        );
        String client = section(json, "\"client\": [", "],")
            .substring("\"client\": [".length());
        Matcher names = Pattern.compile(
            "\"([A-Za-z0-9_$.]+)\""
        ).matcher(client);
        Set<String> result = new TreeSet<>();
        while (names.find()) {
            String configuredName = names.group(1);
            int packageSeparator = configuredName.lastIndexOf('.');
            result.add(
                packageSeparator < 0
                    ? configuredName
                    : configuredName.substring(packageSeparator + 1)
            );
        }
        return result;
    }

    private static Set<String> discoverHooks(String mixin)
        throws Exception {
        String text = source(mixinSource(mixin));
        Set<String> result = new TreeSet<>();
        boolean awaitingDeclaration = false;
        for (String line : text.split("\\R")) {
            if (HOOK_ANNOTATION.matcher(line).find()) {
                awaitingDeclaration = true;
            }
            if (!awaitingDeclaration) {
                continue;
            }
            Matcher declaration = METHOD_DECLARATION.matcher(line);
            if (declaration.find()) {
                result.add(mixin + "#" + declaration.group(1));
                awaitingDeclaration = false;
            }
        }
        assertFalse(
            awaitingDeclaration,
            mixin + " has an injection annotation without a method"
        );
        return result;
    }

    private static String mixinSource(String mixin) {
        if (
            "OptionBuilderAccessor".equals(mixin)
                || "SodiumConfigBuilderMixin".equals(mixin)
        ) {
            return MIXIN_SOURCE + "sodium/" + mixin + ".java";
        }
        return MIXIN_SOURCE + mixin + ".java";
    }

    private static void assertNoExplicitConstruction(
        String owner,
        String body
    ) {
        Matcher allocation = EXPLICIT_CONSTRUCTION.matcher(codeOnly(body));
        assertFalse(
            allocation.find(),
            owner + " silently added an explicit normal-path construction"
        );
    }

    private static String methodBody(
        String text,
        String method,
        int occurrence
    ) {
        Pattern declaration = Pattern.compile(
            "(?m)^\\s*(?:(?:private|protected|public|static|final"
                + "|synchronized|abstract|native)\\s+)*"
                + "(?:<[^>\\r\\n]+>\\s+)?"
                + "[A-Za-z_$@][A-Za-z0-9_.$<>?,\\[\\]@]*\\s+"
                + Pattern.quote(method)
                + "\\s*\\("
        );
        Matcher matcher = declaration.matcher(text);
        int start = -1;
        for (int index = 0; index < occurrence; index++) {
            assertTrue(
                matcher.find(),
                "missing method occurrence "
                    + occurrence
                    + ": "
                    + method
            );
            start = matcher.start();
        }
        int open = text.indexOf('{', start);
        assertTrue(open >= 0, "missing method body: " + method);
        int close = matchingBrace(text, open);
        assertTrue(close > open, "unclosed method body: " + method);
        return text.substring(open, close + 1);
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        int state = 0;
        for (int index = open; index < text.length(); index++) {
            char current = text.charAt(index);
            char next = index + 1 < text.length()
                ? text.charAt(index + 1)
                : '\0';
            if (state == 1) {
                if (current == '\n' || current == '\r') {
                    state = 0;
                }
                continue;
            }
            if (state == 2) {
                if (current == '*' && next == '/') {
                    state = 0;
                    index++;
                }
                continue;
            }
            if (state == 3 || state == 4) {
                if (current == '\\') {
                    index++;
                } else if (
                    (state == 3 && current == '"')
                        || (state == 4 && current == '\'')
                ) {
                    state = 0;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                state = 1;
                index++;
            } else if (current == '/' && next == '*') {
                state = 2;
                index++;
            } else if (current == '"') {
                state = 3;
            } else if (current == '\'') {
                state = 4;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String codeOnly(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int state = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char next = index + 1 < text.length()
                ? text.charAt(index + 1)
                : '\0';
            if (state == 0) {
                if (current == '/' && next == '/') {
                    result.append(' ').append(' ');
                    state = 1;
                    index++;
                } else if (current == '/' && next == '*') {
                    result.append(' ').append(' ');
                    state = 2;
                    index++;
                } else if (current == '"') {
                    result.append(' ');
                    state = 3;
                } else if (current == '\'') {
                    result.append(' ');
                    state = 4;
                } else {
                    result.append(current);
                }
            } else if (state == 1) {
                result.append(
                    current == '\n' || current == '\r' ? current : ' '
                );
                if (current == '\n' || current == '\r') {
                    state = 0;
                }
            } else if (state == 2) {
                result.append(' ');
                if (current == '*' && next == '/') {
                    result.append(' ');
                    state = 0;
                    index++;
                }
            } else {
                result.append(' ');
                if (current == '\\') {
                    if (index + 1 < text.length()) {
                        result.append(' ');
                        index++;
                    }
                } else if (
                    (state == 3 && current == '"')
                        || (state == 4 && current == '\'')
                ) {
                    state = 0;
                }
            }
        }
        return result.toString();
    }

    private static String section(
        String text,
        String startMarker,
        String endMarker
    ) {
        int start = text.indexOf(startMarker);
        int end = text.indexOf(
            endMarker,
            start + startMarker.length()
        );
        assertTrue(start >= 0, "missing marker: " + startMarker);
        assertTrue(end > start, "missing marker: " + endMarker);
        return text.substring(start, end);
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
