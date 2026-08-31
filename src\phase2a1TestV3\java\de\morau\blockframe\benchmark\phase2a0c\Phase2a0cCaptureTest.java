package de.morau.blockframe.benchmark.phase2a0c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Phase2a0cCaptureTest {
    private static final Gson GSON = new Gson();
    private static final String HASH =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void validReceiptLoadsExactlyOnceAndMutationOrAbsenceFails()
        throws Exception {
        Path receipt = writeReceipt(
            "MOJANG_VULKAN",
            "DTC_DENSE_STATIC",
            "BlockFrame_DTC_Capture_2A0C_test"
        );
        Phase2a0cCaptureRuntime.Receipt.Loaded loaded =
            Phase2a0cCaptureRuntime.Receipt.load(receipt);
        assertEquals("MOJANG_VULKAN", loaded.receipt.profileId);
        assertEquals("DTC_DENSE_STATIC", loaded.receipt.sceneId);
        assertEquals(64, loaded.fileSha256.length());

        String changed = Files.readString(receipt)
            .replace("DTC_DENSE_STATIC", "DTC_DENSE_STATIX");
        Files.writeString(receipt, changed);
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.Receipt.load(receipt)
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.Receipt.load(
                temporaryDirectory.resolve("missing.json")
            )
        );
    }

    @Test
    void supportedSodiumProfileLoadsAndWrongSceneFailsClosed()
        throws Exception {
        Path wrongProfile = writeReceipt(
            "SODIUM_0_9_1_VULKAN",
            "DTC_DENSE_STATIC",
            "BlockFrame_DTC_Capture_2A0C_test"
        );
        assertEquals(
            "SODIUM_0_9_1_VULKAN",
            Phase2a0cCaptureRuntime.Receipt.load(wrongProfile)
                .receipt.profileId
        );
        Path wrongScene = writeReceipt(
            "MOJANG_VULKAN",
            "DTC_UNKNOWN",
            "BlockFrame_DTC_Capture_2A0C_test"
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.Receipt.load(wrongScene)
        );
    }

    @Test
    void allBaselineAndGreenfieldScenesAreSupported() throws Exception {
        for (
            String scene : List.of(
                "DTC_DENSE_STATIC",
                "DTC_POI_SWEEP",
                "DTC_CHUNK_TRAVERSE_COLD",
                "DTC_CHUNK_TRAVERSE_WARM",
                "GREENFIELD_DOWNTOWN_STREET_12",
                "GREENFIELD_DOWNTOWN_STREET_32",
                "GREENFIELD_DOWNTOWN_ROOFTOP_12",
                "GREENFIELD_DOWNTOWN_ROOFTOP_32"
            )
        ) {
            Phase2a0cCaptureRuntime.Receipt receipt =
                Phase2a0cCaptureRuntime.Receipt.load(
                    writeReceipt(
                        "MOJANG_VULKAN",
                        scene,
                        "BlockFrame_DTC_Capture_2A0C_" + scene
                    )
                ).receipt;
            assertEquals(scene, receipt.sceneId);
            assertEquals(2, receipt.route.keyframeCount());
        }
    }

    @Test
    void blockframeOffProfileRequiresBlockframe()
        throws Exception {
        Phase2a0cCaptureRuntime.Receipt receipt =
            Phase2a0cCaptureRuntime.Receipt.load(
                writeReceipt(
                    "BLOCKFRAME_0_3_14_OFF",
                    "DTC_DENSE_STATIC",
                    "BlockFrame_DTC_Capture_2A0C_test"
                )
            ).receipt;
        Phase2a0cCaptureRuntime.validateIdentity(
            receipt,
            "BLOCKFRAME_0_3_14_OFF",
            "DTC_DENSE_STATIC",
            receipt.expectedWorldDirectoryName,
            true,
            false,
            true
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.validateIdentity(
                receipt,
                "BLOCKFRAME_0_3_14_OFF",
                "DTC_DENSE_STATIC",
                receipt.expectedWorldDirectoryName,
                false,
                false,
                true
            )
        );
    }

    @Test
    void worldProfileArtifactAndBlockframeAttestationFailClosed()
        throws Exception {
        Phase2a0cCaptureRuntime.Receipt receipt =
            Phase2a0cCaptureRuntime.Receipt.load(
                writeReceipt(
                    "MOJANG_VULKAN",
                    "DTC_DENSE_STATIC",
                    "BlockFrame_DTC_Capture_2A0C_test"
                )
            ).receipt;
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.validateIdentity(
                receipt,
                "MOJANG_VULKAN",
                "DTC_DENSE_STATIC",
                "wrong-world",
                false,
                false,
                true
            )
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.validateIdentity(
                receipt,
                "MOJANG_VULKAN",
                "DTC_DENSE_STATIC",
                receipt.expectedWorldDirectoryName,
                true,
                false,
                true
            )
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.requireHashMatch(
                HASH,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "CAPTURE_ARTIFACT_HASH_MISMATCH"
            )
        );
    }

    @Test
    void sodiumProfileRequiresSodiumAndRejectsBlockframeCoLoading()
        throws Exception {
        Phase2a0cCaptureRuntime.Receipt receipt =
            Phase2a0cCaptureRuntime.Receipt.load(
                writeReceipt(
                    "SODIUM_0_9_1_VULKAN",
                    "GREENFIELD_DOWNTOWN_ROOFTOP_32",
                    "BlockFrame_Greenfield_Diagnostic_test"
                )
            ).receipt;
        Phase2a0cCaptureRuntime.validateIdentity(
            receipt,
            "SODIUM_0_9_1_VULKAN",
            receipt.sceneId,
            receipt.expectedWorldDirectoryName,
            false,
            true,
            true
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.validateIdentity(
                receipt,
                "SODIUM_0_9_1_VULKAN",
                receipt.sceneId,
                receipt.expectedWorldDirectoryName,
                true,
                true,
                true
            )
        );
    }

    @Test
    void passiveCallbacksBindOnceAndProduceExactlyTwoCpuBoundaries() {
        Phase2a0cCaptureRuntime.SingleSceneController controller =
            new Phase2a0cCaptureRuntime.SingleSceneController(10L, 30L, 16);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        Phase2a0cCaptureRuntime.SingleSceneController.CpuBoundary boundary =
            new Phase2a0cCaptureRuntime.SingleSceneController.CpuBoundary() {
                @Override
                public void begin(long nowNanos) {
                    starts.incrementAndGet();
                }

                @Override
                public void end(long nowNanos) {
                    ends.incrementAndGet();
                }
            };

        assertFalse(controller.onFrame(0L, 0L, boundary, false));
        assertEquals(
            Phase2a0cCaptureRuntime.SingleSceneController.State.UNBOUND,
            controller.state()
        );
        controller.bind(100L);
        controller.bind(101L);
        assertFalse(controller.onFrame(109L, 1_009L, boundary, false));
        assertFalse(controller.onFrame(110L, 1_010L, boundary, false));
        assertEquals(
            Phase2a0cCaptureRuntime.SingleSceneController.State
                .REFERENCE_PENDING,
            controller.state()
        );
        assertFalse(controller.onFrame(120L, 1_020L, boundary, false));
        assertFalse(controller.onFrame(130L, 1_030L, boundary, true));
        assertFalse(controller.onFrame(140L, 1_040L, boundary, true));
        assertFalse(controller.onFrame(150L, 1_050L, boundary, true));
        assertTrue(controller.onFrame(160L, 1_060L, boundary, true));
        assertEquals(1, starts.get());
        assertEquals(1, ends.get());
        assertEquals(2, controller.sampleCount());
        assertEquals(130L, controller.measureStartNanos());
        assertEquals(160L, controller.measureEndObservedNanos());
        assertEquals(1_030L, controller.measureStartEpochMillis());
        assertEquals(1_060L, controller.measureEndEpochMillis());

        for (int index = 0; index < 10_000; index++) {
            assertFalse(
                controller.onFrame(
                    170L + index,
                    1_070L + index,
                    boundary,
                    true
                )
            );
        }
        assertEquals(1, starts.get());
        assertEquals(1, ends.get());
        assertEquals(
            Phase2a0cCaptureRuntime.SingleSceneController.State.COMPLETE,
            controller.state()
        );
    }

    @Test
    void warmedPrimitiveCaptureHotpathAllocatesZeroBytes() {
        com.sun.management.ThreadMXBean bean =
            (com.sun.management.ThreadMXBean)
                ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        int measuredCallbacks = 1_000_000;
        Phase2a0cCaptureRuntime.SingleSceneController controller =
            new Phase2a0cCaptureRuntime.SingleSceneController(
                1L,
                Long.MAX_VALUE / 4L,
                measuredCallbacks + 200_001
            );
        Phase2a0cCaptureRuntime.SingleSceneController.CpuBoundary boundary =
            new Phase2a0cCaptureRuntime.SingleSceneController.CpuBoundary() {
                @Override
                public void begin(long nowNanos) {}

                @Override
                public void end(long nowNanos) {}
        };
        controller.bind(1L);
        controller.onFrame(2L, 2L, boundary, false);
        controller.onFrame(3L, 3L, boundary, true);
        for (int index = 0; index < 200_000; index++) {
            controller.onFrame(4L + index, 4L + index, boundary, true);
        }

        long threadId = Thread.currentThread().threadId();
        bean.getThreadAllocatedBytes(threadId);
        bean.getThreadAllocatedBytes(threadId);
        long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < measuredCallbacks; index++) {
            controller.onFrame(
                200_004L + index,
                200_004L + index,
                boundary,
                true
            );
        }
        long allocatedAfter = bean.getThreadAllocatedBytes(threadId);
        long allocatedBytes = allocatedAfter - allocatedBefore;

        assertTrue(
            allocatedBytes <= 16_384L,
            "One million warmed primitive callbacks must remain below "
                + "the conservative ThreadMXBean noise ceiling; observed "
                + allocatedBytes
        );
    }

    @Test
    void warmedPrimitiveVulkanPresentCaptureAllocatesZeroBytes()
        throws Exception {
        com.sun.management.ThreadMXBean bean =
            (com.sun.management.ThreadMXBean)
                ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        int measuredPresents = 200_000;
        long expectedProcessId = 4_242L;
        long glfwWindowPointer = 0x1000_0000L;
        long win32Hwnd = 0x0020_0042L;
        Phase2a0cCaptureRuntime.PresentCorrelationWindow window =
            new Phase2a0cCaptureRuntime.PresentCorrelationWindow(
                measuredPresents + 256,
                new FixedWindowApi(true, expectedProcessId),
                expectedProcessId
            );
        window.accept(
            1L,
            1L,
            1L,
            17,
            glfwWindowPointer,
            win32Hwnd,
            expectedProcessId,
            true,
            1L,
            1920,
            1080,
            1920,
            1080,
            0,
            false,
            900L,
            901L,
            0
        );
        window.begin(1_000L);
        for (int index = 0; index < 128; index++) {
            long before = 2_000L + index * 10L;
            window.accept(
                index + 1L,
                1L,
                1L,
                17,
                glfwWindowPointer,
                win32Hwnd,
                expectedProcessId,
                true,
                1L,
                1920,
                1080,
                1920,
                1080,
                0,
                false,
                before,
                before + 1L,
                0
            );
        }

        long threadId = Thread.currentThread().threadId();
        bean.getThreadAllocatedBytes(threadId);
        bean.getThreadAllocatedBytes(threadId);
        long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < measuredPresents; index++) {
            long before = 10_000L + index * 10L;
            window.accept(
                index + 129L,
                1L,
                1L,
                17,
                glfwWindowPointer,
                win32Hwnd,
                expectedProcessId,
                true,
                1L,
                1920,
                1080,
                1920,
                1080,
                0,
                false,
                before,
                before + 1L,
                0
            );
        }
        long allocatedAfter = bean.getThreadAllocatedBytes(threadId);
        window.end(3_000_000L);

        assertTrue(
            allocatedAfter - allocatedBefore <= 8_192L,
            "Warmed Vulkan present capture must remain below the "
                + "conservative ThreadMXBean noise ceiling"
        );
        assertEquals(measuredPresents + 128, window.sampleCount());
        assertEquals(2, window.boundarySnapshots());
        assertTrue(
            window.validForPublication(Thread.currentThread().threadId())
        );
        JsonObject json = window.toJson(
            Thread.currentThread().threadId(),
            HASH
        );
        assertEquals(
            measuredPresents + 128,
            json.get("presentId").getAsJsonArray().size()
        );
        assertEquals(
            "PREALLOCATED_PARALLEL_PRIMITIVE_ARRAYS",
            json.get("storage").getAsString()
        );
        assertEquals(
            glfwWindowPointer,
            json.get("ownerGlfwWindowPointer").getAsLong()
        );
        assertEquals(
            win32Hwnd,
            json.get("ownerWin32Hwnd").getAsLong()
        );
        assertEquals(
            2,
            json.get("nativeWindowIdentityChecks").getAsInt()
        );
    }

    @Test
    void glfwPointerAndWin32HwndAreDistinctValidIdentities() {
        long expectedProcessId = 77L;
        long glfwWindowPointer = 0x2B74_DEB8_920L;
        long win32Hwnd = 0x0012_08B6L;
        Phase2a0cCaptureRuntime.WindowIdentity identity =
            Phase2a0cCaptureRuntime.inspectWindowIdentity(
                glfwWindowPointer,
                win32Hwnd,
                expectedProcessId,
                new FixedWindowApi(true, expectedProcessId)
            );

        assertTrue(glfwWindowPointer != win32Hwnd);
        assertTrue(identity.valid());
        assertEquals(glfwWindowPointer, identity.glfwWindowPointer());
        assertEquals(win32Hwnd, identity.win32Hwnd());
        assertEquals(expectedProcessId, identity.ownerProcessId());
    }

    @Test
    void wrongWin32WindowProcessFailsClosed() {
        Phase2a0cCaptureRuntime.WindowIdentity identity =
            Phase2a0cCaptureRuntime.inspectWindowIdentity(
                0x1000L,
                0x2000L,
                41L,
                new FixedWindowApi(true, 42L)
            );

        assertFalse(identity.valid());
        assertEquals(
            "WIN32_WINDOW_PROCESS_MISMATCH",
            identity.unavailableReason()
        );
    }

    @Test
    void windowRecreationDuringWarmupOrMeasureInvalidatesOwner() {
        long expectedProcessId = 90L;
        Phase2a0cCaptureRuntime.PresentCorrelationWindow window =
            new Phase2a0cCaptureRuntime.PresentCorrelationWindow(
                16,
                new FixedWindowApi(true, expectedProcessId),
                expectedProcessId
            );
        acceptPresent(window, 1L, 1L, 0x1000L, 0x2000L, expectedProcessId);
        window.begin(1_000L);
        acceptPresent(window, 2L, 1L, 0x1000L, 0x2000L, expectedProcessId);
        acceptPresent(window, 3L, 2L, 0x3000L, 0x4000L, expectedProcessId);
        window.end(2_000L);

        assertFalse(
            window.validForPublication(Thread.currentThread().threadId())
        );
    }

    @Test
    void stableWindowIdentitySurvivesWarmupAndMeasureBoundaries()
        throws Exception {
        long expectedProcessId = 91L;
        long glfwWindowPointer = 0x5000L;
        long win32Hwnd = 0x6000L;
        Phase2a0cCaptureRuntime.PresentCorrelationWindow window =
            new Phase2a0cCaptureRuntime.PresentCorrelationWindow(
                16,
                new FixedWindowApi(true, expectedProcessId),
                expectedProcessId
            );
        acceptPresent(
            window,
            1L,
            1L,
            glfwWindowPointer,
            win32Hwnd,
            expectedProcessId
        );
        window.begin(1_000L);
        acceptPresent(
            window,
            2L,
            1L,
            glfwWindowPointer,
            win32Hwnd,
            expectedProcessId
        );
        window.end(2_000L);

        assertTrue(
            window.validForPublication(Thread.currentThread().threadId())
        );
        JsonObject json = window.toJson(
            Thread.currentThread().threadId(),
            HASH
        );
        assertEquals(0, json.get("nativeWindowIdentityFailures").getAsInt());
        assertTrue(json.get("ownerWindowIdentityValid").getAsBoolean());
    }

    @Test
    void workloadWindowUsesBoundedPrimitiveSamplesAndExactBoundaries() {
        Phase2a0cCaptureRuntime.WorkloadWindow window =
            new Phase2a0cCaptureRuntime.WorkloadWindow(3);
        window.begin(workloadSnapshot(100L, 10, 400, 7));
        window.recordTerrainDrawSubmission(12, true);
        window.recordTerrainDrawSubmission(8, true);
        window.recordTerrainUpload(300L, true);
        window.captureFrame(101, 401, 6);
        window.recordTerrainDrawSubmission(10, true);
        window.recordTerrainUpload(200L, true);
        window.captureFrame(102, 402, 4);
        window.end(workloadSnapshot(700L, 12, 402, 3));

        assertTrue(window.validForPublication());
        assertEquals(2, window.sampleCount());
        JsonObject json = window.toJson();
        assertEquals(
            600L,
            json.getAsJsonObject("deltas").get("gameTime").getAsLong()
        );
        JsonObject frame = json.getAsJsonObject("perRenderedFrame");
        assertEquals(
            3L,
            frame.getAsJsonObject("terrainDrawSubmissions")
                .get("total").getAsLong()
        );
        assertEquals(
            30L,
            frame.getAsJsonObject("terrainDrawRecords")
                .get("total").getAsLong()
        );
        assertEquals(
            500L,
            frame.getAsJsonObject("terrainUploadNanos")
                .get("total").getAsLong()
        );
        assertEquals(
            "PREALLOCATED_PARALLEL_PRIMITIVE_ARRAYS",
            json.getAsJsonObject("captureHotpath")
                .get("storage").getAsString()
        );
    }

    @Test
    void workloadWindowFailsClosedOnOverflowOrInvalidLifecycle() {
        Phase2a0cCaptureRuntime.WorkloadWindow overflow =
            new Phase2a0cCaptureRuntime.WorkloadWindow(1);
        overflow.begin(workloadSnapshot(100L, 10, 400, 7));
        overflow.captureFrame(100, 400, 7);
        overflow.captureFrame(100, 400, 7);
        overflow.end(workloadSnapshot(700L, 10, 400, 7));
        assertFalse(overflow.validForPublication());
        assertThrows(IllegalStateException.class, overflow::toJson);

        Phase2a0cCaptureRuntime.WorkloadWindow lifecycle =
            new Phase2a0cCaptureRuntime.WorkloadWindow(1);
        assertThrows(
            IllegalStateException.class,
            () -> lifecycle.end(workloadSnapshot(1L, 1, 1, 1))
        );
    }

    @Test
    void v3WorkloadHooksObserveMojangOwnersWithoutReplacingThem()
        throws Exception {
        Path capture = projectDirectory().resolve(
            "src/phase2a1CaptureV3"
        );
        String runtime = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/"
                    + "Phase2a0cCaptureRuntime.java"
            )
        );
        assertTrue(
            runtime.contains("PREALLOCATED_PARALLEL_PRIMITIVE_ARRAYS")
        );
        assertTrue(runtime.contains("visibleSections().size()"));
        assertTrue(runtime.contains("getLoadedChunksCount()"));
        assertTrue(runtime.contains("getCompileQueueSize()"));
        assertFalse(runtime.contains("Thread.sleep"));
        assertFalse(runtime.contains("getAllStackTraces"));

        String drawMixin = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/mixin/"
                    + "Phase2a1ChunkSectionsToRenderMixin.java"
            )
        );
        assertTrue(drawMixin.contains("original.call("));
        assertTrue(drawMixin.contains("draws.size()"));
        assertTrue(
            drawMixin.contains(
                "Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;"
            )
        );
        assertTrue(drawMixin.contains("Ljava/lang/Object;)V"));
        assertTrue(drawMixin.contains("Object dynamicUniformSlices"));
        assertFalse(drawMixin.contains("Ljava/util/List;"));
        assertFalse(drawMixin.contains("@Redirect"));
        assertFalse(drawMixin.contains("new "));

        String dispatcherMixin = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/mixin/"
                    + "Phase2a1SectionRenderDispatcherMixin.java"
            )
        );
        assertTrue(dispatcherMixin.contains("original.call()"));
        assertFalse(dispatcherMixin.contains("createCommandEncoder"));
        assertFalse(dispatcherMixin.contains("uploadStagedAllocations"));
    }

    @Test
    void publicationIsValidatedAndAtomicAndNonFiniteValuesFail()
        throws Exception {
        JsonObject result = minimalResult();
        Path destination = temporaryDirectory.resolve("result.json");
        Phase2a0cCaptureRuntime.publishAtomically(destination, result);
        assertTrue(Files.isRegularFile(destination));
        assertFalse(
            Files.exists(temporaryDirectory.resolve("result.json.tmp"))
        );
        Phase2a0cCaptureRuntime.validatePublishedResult(
            JsonParser.parseString(Files.readString(destination))
                .getAsJsonObject()
        );
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.publishAtomically(
                destination,
                result
            )
        );

        JsonObject bad = minimalResult();
        bad.addProperty("notFinite", Double.NaN);
        assertThrows(
            Phase2a0cCaptureRuntime.ContractException.class,
            () -> Phase2a0cCaptureRuntime.validateFiniteTree(bad)
        );
    }

    @Test
    void sourceContractHasNoOldHarnessLifecycleOrMeasureIo()
        throws Exception {
        Path project = projectDirectory();
        Path capture = project.resolve("src/phase2a1CaptureV2");
        List<Path> sourceFiles;
        try (Stream<Path> paths = Files.walk(capture)) {
            sourceFiles = paths.filter(Files::isRegularFile).toList();
        }
        String joined = readAll(sourceFiles);
        assertFalse(joined.contains("phase2a0b"));
        assertFalse(joined.contains("BlockframeRuntime"));
        assertFalse(joined.contains("FrameBudgetController"));
        assertFalse(joined.contains("setLevel"));
        assertFalse(joined.contains("clearClientLevel"));
        assertFalse(joined.contains("disconnect("));
        assertFalse(joined.contains("Thread.sleep"));
        assertFalse(joined.contains("getAllStackTraces"));
        assertFalse(joined.contains("voxellift\"\ntype=\"required\""));

        String runtime = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/"
                    + "Phase2a0cCaptureRuntime.java"
            )
        );
        String callback = between(
            runtime,
            "private void acceptRenderCallback",
            "private void acceptRenderComplete"
        );
        assertFalse(callback.contains("Files."));
        assertFalse(callback.contains("getAllThreadIds"));
        assertFalse(callback.contains("getThreadInfo"));

        String build = Files.readString(project.resolve("build.gradle"));
        String sourceSet = between(
            build,
            "phase2a1CaptureV2 {",
            "phase2a1TestV2 {"
        );
        assertFalse(sourceSet.contains("sourceSets.main.output"));
    }

    @Test
    void mixinPackageDoesNotOwnTheModEntrypoint() throws Exception {
        Path capture = projectDirectory().resolve("src/phase2a1CaptureV2");
        JsonObject mixinConfig = JsonParser.parseString(
            Files.readString(
                capture.resolve(
                    "resources/blockframe_phase2a0c.mixins.json"
                )
            )
        ).getAsJsonObject();
        String mixinPackage = mixinConfig.get("package").getAsString();
        String modSource = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/"
                    + "Phase2a0cCaptureMod.java"
            )
        );
        assertEquals(
            "de.morau.blockframe.benchmark.phase2a0c.mixin",
            mixinPackage
        );
        assertFalse(
            modSource.contains("package " + mixinPackage + ";"),
            "NeoForge must be able to load the mod entrypoint before mixins"
        );
    }

    @Test
    void vulkanPresentHookIsObservationOnlyAndContractIsPredeclared()
        throws Exception {
        Path project = projectDirectory();
        Path capture = project.resolve("src/phase2a1CaptureV2");
        String mixin = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/mixin/"
                    + "Phase2a0cVulkanGpuSurfaceMixin.java"
            )
        );
        assertTrue(mixin.contains("@Mixin(VulkanGpuSurface.class)"));
        assertTrue(
            mixin.contains("currentImageIndex:I")
                && mixin.contains("shift = At.Shift.AFTER")
        );
        assertTrue(
            mixin.contains(
                "@ModifyVariable(method = \"present\", "
                    + "at = @At(\"STORE\"), ordinal = 0)"
            )
        );
        assertFalse(mixin.contains("@Redirect"));
        assertFalse(mixin.contains("KHRSwapchain"));
        assertFalse(mixin.contains("vkWaitForPresentKHR"));
        assertFalse(mixin.contains("Thread.sleep"));
        assertFalse(mixin.contains("Files."));
        assertTrue(
            mixin.contains("resolveWindowIdentity(windowHandle)")
        );
        String runtime = Files.readString(
            capture.resolve(
                "java/de/morau/blockframe/benchmark/phase2a0c/"
                    + "Phase2a0cCaptureRuntime.java"
            )
        );
        assertTrue(runtime.contains("GLFWNativeWin32.glfwGetWin32Window"));
        assertTrue(runtime.contains("User32.INSTANCE.IsWindow"));
        assertTrue(
            runtime.contains("User32.INSTANCE.GetWindowThreadProcessId")
        );
        assertFalse(
            runtime.contains(
                "ownerGlfwWindowPointer != ownerWin32Hwnd"
            ),
            "Distinct GLFW and HWND values must never be rejected"
        );

        JsonObject mixinConfig = JsonParser.parseString(
            Files.readString(
                capture.resolve(
                    "resources/blockframe_phase2a0c.mixins.json"
                )
            )
        ).getAsJsonObject();
        assertTrue(
            mixinConfig.getAsJsonArray("client").asList().stream()
                .anyMatch(
                    value -> value.getAsString().equals(
                        "Phase2a0cVulkanGpuSurfaceMixin"
                    )
                )
        );

        JsonObject contract = JsonParser.parseString(
            Files.readString(
                project.resolve(
                    "benchmarks/phase2a1/"
                        + "greenfield-vulkan-present-"
                        + "correlation-contract-v2.json"
                )
            )
        ).getAsJsonObject();
        assertEquals(2, contract.get("schemaVersion").getAsInt());
        JsonObject correlation = contract.getAsJsonObject("correlation");
        assertEquals(
            2,
            correlation.get("maximumLeadingTrimRows").getAsInt()
        );
        assertEquals(
            2,
            correlation.get("maximumTrailingTrimRows").getAsInt()
        );
        assertEquals(
            0,
            correlation.get("internalUnmatchedRowsAllowed").getAsInt()
        );
        assertTrue(
            contract.getAsJsonObject("failurePolicy")
                .get("noPostHocToleranceChanges")
                .getAsBoolean()
        );
        String runner = Files.readString(
            project.resolve("scripts/phase2a1-run-one-v2.ps1")
        );
        assertTrue(runner.contains("GetWindowThreadProcessId"));
        assertTrue(runner.contains("IsWindow"));
        assertTrue(runner.contains("ownerGlfwWindowPointer"));
        assertTrue(runner.contains("ownerWin32Hwnd"));
        assertTrue(
            runner.contains(
                "[int64]$state.processIdentity.win32Hwnd"
            )
        );
        assertFalse(runner.contains("ownerWindowHandle"));
        assertFalse(runner.contains("mainWindowHandle"));
    }

    @Test
    void exactlyOneVersionedRunnerSchemaAndDevArtifactBoundary()
        throws Exception {
        Path project = projectDirectory();
        try (
            Stream<Path> manifests = Files.list(
                project.resolve("benchmarks/fixtures")
            )
        ) {
            assertEquals(
                1L,
                manifests
                    .filter(
                        path -> path
                            .getFileName()
                            .toString()
                            .equals(
                                "blockframe-2a1-run-result-schema-v2.json"
                            )
                    )
                    .count()
            );
        }
        try (Stream<Path> scripts = Files.list(project.resolve("scripts"))) {
            assertEquals(
                1L,
                scripts
                    .filter(
                        path -> path
                            .getFileName()
                            .toString()
                            .equals("phase2a1-run-one-v2.ps1")
                    )
                    .count()
            );
        }
        assertTrue(
            Files.isDirectory(project.resolve("src/phase2a1CaptureV2"))
        );
        assertFalse(
            Files.isDirectory(project.resolve("src/phase2a0cHarness"))
        );
    }

    @Test
    void existingProductionJarIsByteExactAndContainsNoDevClasses()
        throws Exception {
        Path jar = projectDirectory().resolve(
            "build/libs/blockframe-dlss-0.3.14-neoforge-26.2.jar"
        );
        assertEquals(33_170_515L, Files.size(jar));
        assertEquals(
            "7e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5",
            sha256(Files.readAllBytes(jar))
        );
        try (JarFile archive = new JarFile(jar.toFile())) {
            assertEquals(339L, archive.stream().count());
            assertEquals(
                0L,
                archive
                    .stream()
                    .filter(
                        entry -> entry
                            .getName()
                            .contains("/benchmark/phase2a0")
                    )
                    .count()
            );
        }
    }

    private Path writeReceipt(
        String profile,
        String scene,
        String world
    ) throws Exception {
        JsonObject contract = new JsonObject();
        contract.addProperty("runId", "phase2a0c-test-0001");
        contract.addProperty("profileId", profile);
        contract.addProperty("sceneId", scene);
        contract.addProperty("goldenSha256", HASH);
        contract.addProperty("runCopySha256", HASH);
        contract.addProperty("modProfileSha256", HASH);
        contract.addProperty("configProfileSha256", HASH);
        contract.addProperty("captureArtifactSha256", HASH);
        contract.addProperty(
            "presentCorrelationContractSha256",
            HASH
        );
        contract.addProperty("minecraftVersion", "26.2");
        contract.addProperty("neoForgeVersion", "26.2.0.23-beta");
        contract.addProperty("javaVersion", "25.0.3");
        contract.addProperty("expectedBackend", "Vulkan");
        contract.addProperty("expectedGpuNameContains", "RTX 4090");
        contract.addProperty("expectedWorldDirectoryName", world);
        contract.addProperty("expectedLevelName", "Stadt Bau");
        contract.addProperty("dimension", "minecraft:overworld");
        contract.addProperty("resolutionWidth", 1920);
        contract.addProperty("resolutionHeight", 1080);
        contract.addProperty("renderDistanceChunks", 12);
        contract.addProperty("simulationDistanceChunks", 12);
        contract.addProperty("fov", 70);
        contract.addProperty("vsync", false);
        contract.addProperty("fpsLimit", 260);
        contract.addProperty("routeDurationNanos", 30_000_000_000L);
        contract.addProperty("warmupMotion", "STATIC_AT_START");
        contract.addProperty("measureMotion", "STATIC_AT_START");
        contract.addProperty("interpolation", "LINEAR");
        contract.addProperty("referenceKeyframeIndex", 0);
        JsonArray keyframes = new JsonArray();
        keyframes.add(keyframe(0L, -360.0, -10.0, -220.0));
        keyframes.add(
            keyframe(30_000_000_000L, -360.0, -10.0, -220.0)
        );
        contract.add("cameraKeyframes", keyframes);
        contract.addProperty("referenceDownscale", 4);
        contract.addProperty(
            "referenceColorPath",
            temporaryDirectory.resolve("reference-color.png").toString()
        );
        contract.addProperty("warmupNanos", 30_000_000_000L);
        contract.addProperty("measureNanos", 30_000_000_000L);
        contract.addProperty("deadlineUtc", "2030-01-01T00:00:00Z");
        contract.addProperty(
            "resultPath",
            temporaryDirectory.resolve("result-live.json").toString()
        );

        JsonObject integrity = new JsonObject();
        integrity.addProperty("algorithm", "SHA-256");
        integrity.addProperty(
            "contractSha256",
            sha256(GSON.toJson(contract).getBytes(StandardCharsets.UTF_8))
        );
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("contract", contract);
        root.add("integrity", integrity);
        Path output = temporaryDirectory.resolve(
            profile + "-" + scene + ".json"
        );
        Files.writeString(output, GSON.toJson(root));
        return output;
    }

    private static JsonObject keyframe(
        long timeNanos,
        double x,
        double y,
        double z
    ) {
        JsonObject frame = new JsonObject();
        frame.addProperty("timeNanos", timeNanos);
        JsonArray position = new JsonArray();
        position.add(x);
        position.add(y);
        position.add(z);
        frame.add("position", position);
        frame.addProperty("yaw", -45.0);
        frame.addProperty("pitch", 30.0);
        return frame;
    }

    private static JsonObject minimalResult() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 3);
        root.addProperty("runId", "phase2a0c-test-0001");
        root.addProperty("profileId", "MOJANG_VULKAN");
        root.addProperty("sceneId", "DTC_DENSE_STATIC");
        root.addProperty("state", "COMPLETE");
        JsonObject measurement = new JsonObject();
        measurement.addProperty("sampleCount", 2);
        measurement.addProperty("cpuBoundarySnapshots", 2);
        measurement.addProperty("measureStartNanos", 1_000L);
        measurement.addProperty("measureEndNanos", 2_000L);
        root.add("measurement", measurement);
        JsonObject workload = new JsonObject();
        workload.addProperty("status", "AVAILABLE");
        workload.addProperty("sampleCount", 1);
        workload.addProperty("sampleOverflow", false);
        workload.add("start", new JsonObject());
        workload.add("end", new JsonObject());
        workload.add("perRenderedFrame", new JsonObject());
        root.add("workloadAttestation", workload);
        JsonObject present = new JsonObject();
        present.addProperty("sampleCount", 1);
        present.addProperty("boundarySnapshots", 2);
        present.addProperty("ownerPublicationCount", 1);
        present.addProperty("wrongOwnerPresents", 0);
        present.addProperty("wrongThreadPresents", 0);
        present.addProperty("invalidMetadataPresents", 0);
        root.add("vulkanPresentCorrelation", present);
        return root;
    }

    private static Phase2a0cCaptureRuntime.WorkloadSnapshot
        workloadSnapshot(
            long gameTime,
            int visibleSections,
            int loadedChunks,
            int compileQueue
        ) {
        return new Phase2a0cCaptureRuntime.WorkloadSnapshot(
            gameTime,
            gameTime,
            1.0F,
            0.0F,
            visibleSections,
            loadedChunks,
            4L,
            1,
            "Particles: 20",
            compileQueue,
            1.0,
            2.0,
            3.0,
            -180.0F,
            -12.0F
        );
    }

    private static void acceptPresent(
        Phase2a0cCaptureRuntime.PresentCorrelationWindow window,
        long frameId,
        long surfaceGeneration,
        long glfwWindowPointer,
        long win32Hwnd,
        long processId
    ) {
        long before = frameId == 1L ? 900L : 1_100L + frameId * 10L;
        window.accept(
            frameId,
            surfaceGeneration,
            1L,
            17,
            glfwWindowPointer,
            win32Hwnd,
            processId,
            true,
            1L,
            1920,
            1080,
            1920,
            1080,
            0,
            false,
            before,
            before + 1L,
            0
        );
    }

    private record FixedWindowApi(
        boolean validWindow,
        long ownerProcessId
    ) implements Phase2a0cCaptureRuntime.Win32WindowApi {
        @Override
        public boolean isWindow(long win32Hwnd) {
            return validWindow;
        }

        @Override
        public long windowProcessId(long win32Hwnd) {
            return ownerProcessId;
        }
    }

    private static Path projectDirectory() {
        return Path.of(
            System.getProperty("blockframe.projectDir")
        ).toAbsolutePath().normalize();
    }

    private static String readAll(List<Path> paths) throws IOException {
        StringBuilder joined = new StringBuilder();
        for (Path path : paths) {
            joined.append(Files.readString(path)).append('\n');
        }
        return joined.toString();
    }

    private static String between(
        String text,
        String startMarker,
        String endMarker
    ) {
        int start = text.indexOf(startMarker);
        int end = text.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "missing start marker " + startMarker);
        assertTrue(end > start, "missing end marker " + endMarker);
        return text.substring(start, end);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }
}
