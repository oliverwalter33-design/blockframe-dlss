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
                measuredCallbacks * 2 + 200_001
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
        // Exercise a measurement-shaped interval before sampling so tiered
        // compilation is not mistaken for steady-state callback allocation.
        for (int index = 0; index < measuredCallbacks; index++) {
            controller.onFrame(
                200_004L + index,
                200_004L + index,
                boundary,
                true
            );
        }

        long threadId = Thread.currentThread().threadId();
        bean.getThreadAllocatedBytes(threadId);
        bean.getThreadAllocatedBytes(threadId);
        long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < measuredCallbacks; index++) {
            controller.onFrame(
                1_200_004L + index,
                1_200_004L + index,
                boundary,
                true
            );
        }
        long allocatedAfter = bean.getThreadAllocatedBytes(threadId);
        long allocatedBytes = allocatedAfter - allocatedBefore;

        assertTrue(
            allocatedBytes <= 8_192L,
            "One million warmed primitive callbacks must remain below "
                + "the conservative ThreadMXBean noise ceiling; observed "
                + allocatedBytes
                + " bytes"
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
        Phase2a0cCaptureRuntime.PresentCorrelationWindow window =
            new Phase2a0cCaptureRuntime.PresentCorrelationWindow(
                measuredPresents + 256
            );
        window.begin(1_000L);
        for (int index = 0; index < 128; index++) {
            long before = 2_000L + index * 10L;
            window.accept(
                index + 1L,
                1L,
                1L,
                17,
                99L,
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
                99L,
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
        long allocatedBytes = allocatedAfter - allocatedBefore;

        assertTrue(
            allocatedBytes <= 8_192L,
            "Warmed Vulkan present capture must remain below the "
                + "conservative ThreadMXBean noise ceiling; observed "
                + allocatedBytes
                + " bytes"
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
        Path capture = project.resolve("src/phase2a0cCapture");
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
            "phase2a0cCapture {",
            "phase2a0cTest {"
        );
        assertFalse(sourceSet.contains("sourceSets.main.output"));
    }

    @Test
    void mixinPackageDoesNotOwnTheModEntrypoint() throws Exception {
        Path capture = projectDirectory().resolve("src/phase2a0cCapture");
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
        Path capture = project.resolve("src/phase2a0cCapture");
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
                        + "correlation-contract-v1.json"
                )
            )
        ).getAsJsonObject();
        assertEquals(1, contract.get("schemaVersion").getAsInt());
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
    }

    @Test
    void exactlyOneRunnerTwoManifestsAndOneDevArtifactBoundary()
        throws Exception {
        Path project = projectDirectory();
        try (
            Stream<Path> manifests = Files.list(
                project.resolve("benchmarks/fixtures")
            )
        ) {
            assertEquals(
                2L,
                manifests
                    .filter(
                        path -> path
                            .getFileName()
                            .toString()
                            .startsWith("blockframe-2a0c-")
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
                            .startsWith("phase2a0c-run")
                    )
                    .count()
            );
        }
        assertTrue(
            Files.isDirectory(project.resolve("src/phase2a0cCapture"))
        );
        assertFalse(
            Files.isDirectory(project.resolve("src/phase2a0cHarness"))
        );
    }

    @Test
    void currentProductionJarHasNoDevClassesAndFrozenEvidenceIsExact()
        throws Exception {
        Path project = projectDirectory();
        Path jar = project.resolve(
            "build/libs/blockframe-dlss-0.3.14-neoforge-26.2.jar"
        );
        assertTrue(Files.isRegularFile(jar));
        assertTrue(Files.size(jar) > 0L);
        try (JarFile archive = new JarFile(jar.toFile())) {
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

        JsonObject closure = JsonParser.parseString(
            Files.readString(
                project.resolve(
                    "benchmarks/fixtures/blockframe-2a0b-closure-v1.json"
                )
            )
        ).getAsJsonObject();
        JsonObject tested = closure
            .getAsJsonObject("artifactStaging")
            .getAsJsonObject("tested");
        assertEquals(
            "blockframe-dlss-0.3.14-neoforge-26.2.jar",
            tested.get("filename").getAsString()
        );
        assertEquals(33_170_515L, tested.get("bytes").getAsLong());
        assertEquals(
            "7e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5",
            tested.get("sha256").getAsString()
        );

        JsonObject historicalProductionJar = closure
            .getAsJsonObject("verification")
            .getAsJsonObject("productionJar");
        assertEquals(
            33_170_515L,
            historicalProductionJar.get("bytes").getAsLong()
        );
        assertEquals(
            339L,
            historicalProductionJar.get("entries").getAsLong()
        );
        assertEquals(
            0L,
            historicalProductionJar.get("phase2a0bEntries").getAsLong()
        );
        assertEquals(
            "7e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5",
            historicalProductionJar.get("sha256").getAsString()
        );
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
        root.addProperty("schemaVersion", 1);
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
