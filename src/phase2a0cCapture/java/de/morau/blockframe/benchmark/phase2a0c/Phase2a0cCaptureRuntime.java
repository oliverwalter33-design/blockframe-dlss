package de.morau.blockframe.benchmark.phase2a0c;

import de.morau.blockframe.benchmark.phase2a0c.mixin.Phase2a0cCameraInvoker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.software.os.OSProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-scene, single-result development recorder.
 *
 * <p>The receipt is read once during mod construction. The render callback
 * observes only cached values and preallocated primitive storage while the
 * measurement state is active. Result I/O starts only after the end CPU
 * boundary has completed.</p>
 */
public final class Phase2a0cCaptureRuntime {
    static final String MOJANG_PROFILE = "MOJANG_VULKAN";
    static final String BLOCKFRAME_OFF_PROFILE = "BLOCKFRAME_0_3_14_OFF";
    static final String SODIUM_PROFILE = "SODIUM_0_9_1_VULKAN";
    static final int RESULT_SCHEMA_VERSION = 1;
    static final int RECEIPT_SCHEMA_VERSION = 1;
    static final int MAX_FRAME_SAMPLES = 262_144;
    static final int MAX_PRESENT_SAMPLES = 262_144;
    static final int MAX_TRACKED_THREADS = 1_024;

    private static final Logger LOGGER =
        LoggerFactory.getLogger(Phase2a0cCaptureMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private static volatile Phase2a0cCaptureRuntime instance;

    static boolean isSupportedScene(String sceneId) {
        return "DTC_DENSE_STATIC".equals(sceneId)
            || "DTC_POI_SWEEP".equals(sceneId)
            || "DTC_CHUNK_TRAVERSE_COLD".equals(sceneId)
            || "DTC_CHUNK_TRAVERSE_WARM".equals(sceneId)
            || "GREENFIELD_DOWNTOWN_STREET_12".equals(sceneId)
            || "GREENFIELD_DOWNTOWN_STREET_32".equals(sceneId)
            || "GREENFIELD_DOWNTOWN_ROOFTOP_12".equals(sceneId)
            || "GREENFIELD_DOWNTOWN_ROOFTOP_32".equals(sceneId);
    }

    private final Receipt receipt;
    private final String receiptSha256;
    private final String captureArtifactSha256;
    private final SceneRoute route;
    private final ReferenceCapture referenceCapture;
    private final SingleSceneController controller;
    private final CpuWindow cpuWindow;
    private final MemoryWindow memoryWindow;
    private final PresentCorrelationWindow presentCorrelation;
    private final MeasurementBoundaries boundaries;
    private final String captureVersion;

    private long renderOwnerThreadId = -1L;
    private long renderFrameId;
    private int renderOwnerPublicationCount;
    private long wrongThreadCallbacks;
    private boolean resultPublished;
    private volatile boolean failedClosed;
    private RuntimeAttestation attestation;

    private Phase2a0cCaptureRuntime(
        Receipt receipt,
        String receiptSha256,
        String captureArtifactSha256,
        String captureVersion
    ) {
        this.receipt = receipt;
        this.receiptSha256 = receiptSha256;
        this.captureArtifactSha256 = captureArtifactSha256;
        this.captureVersion = captureVersion;
        this.route = receipt.route;
        this.referenceCapture = new ReferenceCapture(
            receipt.referenceColorPath,
            receipt.referenceDownscale,
            route.referenceKeyframeIndex
        );
        this.cpuWindow = new CpuWindow(MAX_TRACKED_THREADS);
        this.memoryWindow = new MemoryWindow();
        this.presentCorrelation = new PresentCorrelationWindow(
            MAX_PRESENT_SAMPLES
        );
        this.boundaries = new MeasurementBoundaries(
            cpuWindow,
            memoryWindow,
            presentCorrelation
        );
        this.controller = new SingleSceneController(
            receipt.warmupNanos,
            receipt.measureNanos,
            MAX_FRAME_SAMPLES
        );
    }

    static void bootstrap(String captureVersion) {
        if (instance != null) {
            return;
        }
        try {
            Path receiptPath = configuredReceiptPath();
            Receipt.Loaded loaded = Receipt.load(receiptPath);
            String artifactHash = sha256(captureArtifactPath());
            requireHashMatch(
                loaded.receipt.captureArtifactSha256,
                artifactHash,
                "CAPTURE_ARTIFACT_HASH_MISMATCH"
            );
            instance = new Phase2a0cCaptureRuntime(
                loaded.receipt,
                loaded.fileSha256,
                artifactHash,
                captureVersion
            );
            LOGGER.info(
                "Phase 2A.0C capture armed for run {} scene {}; receipt read once",
                loaded.receipt.runId,
                loaded.receipt.sceneId
            );
        } catch (Exception exception) {
            LOGGER.error(
                "Phase 2A.0C capture disabled before scene start: {}",
                unavailableReason(exception)
            );
        }
    }

    public static void onRenderCallback(GameRenderer renderer) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.acceptRenderCallback(renderer, System.nanoTime());
        }
    }

    public static void onRenderComplete(
        GameRenderer renderer,
        boolean renderWorld
    ) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.acceptRenderComplete(renderer, renderWorld);
        }
    }

    /**
     * Records the result of Mojang's one existing Vulkan present operation.
     * All arguments are primitive values and the active window stores them in
     * preallocated primitive arrays.
     */
    public static void onVulkanPresent(
        long surfaceGeneration,
        long deviceGeneration,
        int deviceIdentity,
        long windowHandle,
        long swapchainGeneration,
        int framebufferWidth,
        int framebufferHeight,
        int presentMode,
        long beforeNanos,
        long afterNanos,
        int result
    ) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.acceptVulkanPresent(
                surfaceGeneration,
                deviceGeneration,
                deviceIdentity,
                windowHandle,
                swapchainGeneration,
                framebufferWidth,
                framebufferHeight,
                presentMode,
                beforeNanos,
                afterNanos,
                result
            );
        }
    }

    private void acceptRenderCallback(GameRenderer renderer, long nowNanos) {
        if (failedClosed) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = renderer.mainCamera();
        if (
            minecraft == null
                || minecraft.level == null
                || minecraft.player == null
                || camera == null
                || !camera.isInitialized()
                || !RenderSystem.isOnRenderThread()
        ) {
            return;
        }

        long currentThreadId = Thread.currentThread().threadId();
        if (renderOwnerThreadId == -1L) {
            try {
                attestation = validateAndAttest(minecraft);
                renderOwnerThreadId = currentThreadId;
                renderOwnerPublicationCount = 1;
                controller.bind(nowNanos);
                LOGGER.info(
                    "Phase 2A.0C render owner bound once: thread {} world {}",
                    renderOwnerThreadId,
                    receipt.expectedWorldDirectoryName
                );
            } catch (Exception exception) {
                failClosed(exception);
                return;
            }
        } else if (renderOwnerThreadId != currentThreadId) {
            wrongThreadCallbacks++;
            return;
        }

        renderFrameId++;
        boolean justCompleted = controller.onFrame(
            nowNanos,
            System.currentTimeMillis(),
            boundaries,
            referenceCapture.complete()
        );
        route.apply(
            minecraft,
            (Phase2a0cCameraInvoker) (Object) camera,
            controller.motionPhase(),
            controller.motionNanos(nowNanos)
        );
        if (justCompleted && !resultPublished) {
            try {
                cpuWindow.discoverThreadLifecycleAfterMeasure();
                JsonObject result = buildCompleteResult();
                publishAtomically(receipt.resultPath, result);
                resultPublished = true;
                LOGGER.info(
                    "Phase 2A.0C scene COMPLETE: {} samples, two CPU boundaries",
                    controller.sampleCount()
                );
            } catch (Exception exception) {
                failClosed(exception);
            }
        }
    }

    private void acceptVulkanPresent(
        long surfaceGeneration,
        long deviceGeneration,
        int deviceIdentity,
        long windowHandle,
        long swapchainGeneration,
        int framebufferWidth,
        int framebufferHeight,
        int presentMode,
        long beforeNanos,
        long afterNanos,
        int result
    ) {
        RuntimeAttestation currentAttestation = attestation;
        presentCorrelation.accept(
            renderFrameId,
            surfaceGeneration,
            deviceGeneration,
            deviceIdentity,
            windowHandle,
            swapchainGeneration,
            currentAttestation == null
                ? 0
                : currentAttestation.windowWidth,
            currentAttestation == null
                ? 0
                : currentAttestation.windowHeight,
            framebufferWidth,
            framebufferHeight,
            presentMode,
            receipt.vsync,
            beforeNanos,
            afterNanos,
            result
        );
    }

    private void acceptRenderComplete(
        GameRenderer renderer,
        boolean renderWorld
    ) {
        if (
            failedClosed
                || !renderWorld
                || controller.state()
                    != SingleSceneController.State.REFERENCE_PENDING
                || referenceCapture.started()
        ) {
            return;
        }
        if (
            renderOwnerThreadId != Thread.currentThread().threadId()
                || !RenderSystem.isOnRenderThread()
        ) {
            wrongThreadCallbacks++;
            return;
        }
        try {
            referenceCapture.capture(
                renderer,
                route,
                failure -> failClosed(failure)
            );
        } catch (Exception exception) {
            failClosed(exception);
        }
    }

    private RuntimeAttestation validateAndAttest(Minecraft minecraft)
        throws ContractException {
        String worldDirectory = currentWorldDirectory(minecraft);
        boolean blockframeLoaded = ModList.get().isLoaded("voxellift");
        boolean sodiumLoaded = ModList.get().isLoaded("sodium");
        if (blockframeLoaded && sodiumLoaded) {
            throw new ContractException(
                "BLOCKFRAME_AND_SODIUM_PROFILE_CONFLICT"
            );
        }
        String actualProfile = blockframeLoaded
            ? BLOCKFRAME_OFF_PROFILE
            : sodiumLoaded
                ? SODIUM_PROFILE
                : MOJANG_PROFILE;
        validateIdentity(
            receipt,
            actualProfile,
            receipt.sceneId,
            worldDirectory,
            blockframeLoaded,
            sodiumLoaded,
            ModList.get().isLoaded(Phase2a0cCaptureMod.MOD_ID)
        );
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (
            server == null
                || !receipt.expectedLevelName.equals(
                    server.getWorldData().getLevelName()
                )
        ) {
            throw new ContractException("LEVEL_NAME_MISMATCH");
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        if (!receipt.dimension.equals(dimension)) {
            throw new ContractException("DIMENSION_MISMATCH");
        }

        if (
            minecraft.getWindow().getWidth() != receipt.resolutionWidth
                || minecraft.getWindow().getHeight()
                    != receipt.resolutionHeight
                || minecraft.getWindow().isFullscreen()
        ) {
            throw new ContractException("WINDOW_CONTRACT_MISMATCH");
        }
        if (
            minecraft.options.renderDistance().get()
                    != receipt.renderDistanceChunks
                || minecraft.options.simulationDistance().get()
                    != receipt.simulationDistanceChunks
                || minecraft.options.fov().get() != receipt.fov
                || minecraft.options.enableVsync().get() != receipt.vsync
                || minecraft.options.framerateLimit().get()
                    != receipt.fpsLimit
        ) {
            throw new ContractException("OPTIONS_CONTRACT_MISMATCH");
        }

        String minecraftVersion = SharedConstants.getCurrentVersion().name();
        String neoForgeVersion = ModList.get()
            .getModContainerById("neoforge")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("NOT_AVAILABLE");
        String javaVersion = System.getProperty(
            "java.version",
            "NOT_AVAILABLE"
        );
        if (!receipt.minecraftVersion.equals(minecraftVersion)) {
            throw new ContractException("MINECRAFT_VERSION_MISMATCH");
        }
        if (!receipt.neoForgeVersion.equals(neoForgeVersion)) {
            throw new ContractException("NEOFORGE_VERSION_MISMATCH");
        }
        if (!receipt.javaVersion.equals(javaVersion)) {
            throw new ContractException("JAVA_VERSION_MISMATCH");
        }

        DeviceInfo deviceInfo;
        try {
            deviceInfo = RenderSystem.getDevice().getDeviceInfo();
        } catch (RuntimeException exception) {
            throw new ContractException(
                "DEVICE_INFO_UNAVAILABLE",
                exception
            );
        }
        if (
            !deviceInfo.backendName().toLowerCase(Locale.ROOT).contains(
                receipt.expectedBackend.toLowerCase(Locale.ROOT)
            )
        ) {
            throw new ContractException("BACKEND_MISMATCH");
        }
        String gpu = (
            deviceInfo.vendorName() + " " + deviceInfo.name()
        ).trim();
        if (!gpu.contains(receipt.expectedGpuNameContains)) {
            throw new ContractException("GPU_MISMATCH");
        }

        return new RuntimeAttestation(
            minecraftVersion,
            neoForgeVersion,
            javaVersion,
            deviceInfo.backendName(),
            gpu,
            deviceInfo.driverInfo(),
            worldDirectory,
            server.getWorldData().getLevelName(),
            dimension,
            minecraft.getWindow().handle(),
            minecraft.getWindow().getScreenWidth(),
            minecraft.getWindow().getScreenHeight(),
            minecraft.getWindow().getWidth(),
            minecraft.getWindow().getHeight(),
            blockframeLoaded,
            sodiumLoaded
        );
    }

    private static String currentWorldDirectory(Minecraft minecraft)
        throws ContractException {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new ContractException("INTEGRATED_SERVER_UNAVAILABLE");
        }
        Path root = server.getWorldPath(LevelResource.ROOT)
            .toAbsolutePath()
            .normalize();
        Path name = root.getFileName();
        if (name == null) {
            throw new ContractException("WORLD_DIRECTORY_UNAVAILABLE");
        }
        return name.toString();
    }

    private JsonObject buildCompleteResult() throws ContractException {
        if (
            controller.state() != SingleSceneController.State.COMPLETE
                || cpuWindow.boundarySnapshots() != 2
                || controller.sampleCount() <= 0
                || !presentCorrelation.validForPublication(
                    renderOwnerThreadId
                )
        ) {
            throw new ContractException("INCOMPLETE_RESULT_STATE");
        }

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", RESULT_SCHEMA_VERSION);
        root.addProperty("runId", receipt.runId);
        root.addProperty("profileId", receipt.profileId);
        root.addProperty("sceneId", receipt.sceneId);
        root.addProperty("state", "COMPLETE");
        root.addProperty("receiptSha256", receiptSha256);
        root.addProperty("captureArtifactSha256", captureArtifactSha256);
        root.addProperty("captureVersion", captureVersion);
        root.addProperty("publishedAtUtc", Instant.now().toString());

        JsonObject runtime = new JsonObject();
        runtime.addProperty(
            "minecraftVersion",
            attestation.minecraftVersion
        );
        runtime.addProperty(
            "neoForgeVersion",
            attestation.neoForgeVersion
        );
        runtime.addProperty("javaVersion", attestation.javaVersion);
        runtime.addProperty("backend", attestation.backend);
        runtime.addProperty("gpu", attestation.gpu);
        runtime.addProperty("driver", attestation.driver);
        runtime.addProperty("worldDirectory", attestation.worldDirectory);
        runtime.addProperty("levelName", attestation.levelName);
        runtime.addProperty("dimension", attestation.dimension);
        runtime.addProperty("windowHandle", attestation.windowHandle);
        runtime.addProperty("windowWidth", attestation.windowWidth);
        runtime.addProperty("windowHeight", attestation.windowHeight);
        runtime.addProperty(
            "framebufferWidth",
            attestation.framebufferWidth
        );
        runtime.addProperty(
            "framebufferHeight",
            attestation.framebufferHeight
        );
        runtime.addProperty("windowMode", "WINDOWED");
        runtime.addProperty("vsync", receipt.vsync);
        runtime.addProperty(
            "blockframeLoaded",
            attestation.blockframeLoaded
        );
        runtime.addProperty("sodiumLoaded", attestation.sodiumLoaded);
        runtime.addProperty("captureLoaded", true);
        runtime.addProperty("dlss", false);
        runtime.addProperty("dlaa", false);
        runtime.addProperty("frameGeneration", false);
        root.add("runtimeAttestation", runtime);

        JsonObject owner = new JsonObject();
        owner.addProperty("threadId", renderOwnerThreadId);
        owner.addProperty(
            "publicationCount",
            renderOwnerPublicationCount
        );
        owner.addProperty(
            "wrongThreadCallbacks",
            wrongThreadCallbacks
        );
        owner.addProperty("sceneId", receipt.sceneId);
        owner.addProperty("keyframeCount", route.keyframeCount());
        owner.addProperty("warmupMotion", route.warmupMotion.name());
        owner.addProperty("measureMotion", route.measureMotion.name());
        owner.addProperty("interpolation", route.interpolation.name());
        owner.addProperty("routeDurationNanos", route.durationNanos);
        root.add("renderOwner", owner);

        JsonObject measurement = new JsonObject();
        measurement.addProperty(
            "warmupNanos",
            receipt.warmupNanos
        );
        measurement.addProperty(
            "configuredMeasureNanos",
            receipt.measureNanos
        );
        measurement.addProperty(
            "observedWallNanos",
            cpuWindow.wallDeltaNanos()
        );
        measurement.addProperty(
            "measureStartNanos",
            controller.measureStartNanos()
        );
        measurement.addProperty(
            "measureEndNanos",
            controller.measureEndObservedNanos()
        );
        measurement.addProperty(
            "measureStartEpochMillis",
            controller.measureStartEpochMillis()
        );
        measurement.addProperty(
            "measureEndEpochMillis",
            controller.measureEndEpochMillis()
        );
        measurement.addProperty(
            "sampleCount",
            controller.sampleCount()
        );
        measurement.addProperty(
            "sampleOverflow",
            controller.sampleOverflow()
        );
        measurement.addProperty(
            "cpuBoundarySnapshots",
            cpuWindow.boundarySnapshots()
        );
        JsonArray frames = new JsonArray(controller.sampleCount());
        long[] frameNanos = controller.frameNanos();
        for (int index = 0; index < controller.sampleCount(); index++) {
            frames.add(frameNanos[index]);
        }
        measurement.add("frameNanos", frames);
        addTyped(
            measurement,
            "existingGpuTimerNanos",
            "NOT_APPLICABLE",
            null,
            attestation.blockframeLoaded
                ? "BLOCKFRAME_MODE_OFF_HAS_NO_ACTIVE_GPU_TIMER"
                : "MOJANG_VULKAN_HAS_NO_BLOCKFRAME_GPU_TIMER"
        );
        addTyped(
            measurement,
            "sectionDrawSubmitCounts",
            "NOT_APPLICABLE",
            null,
            "NO_BLOCKFRAME_RENDERER_OWNER"
        );
        root.add("measurement", measurement);

        root.add("cpu", cpuWindow.toJson());
        root.add("memoryAndGc", memoryWindow.toJson());
        root.add("colorReference", referenceCapture.toJson(route));
        root.add(
            "vulkanPresentCorrelation",
            presentCorrelation.toJson(
                renderOwnerThreadId,
                receipt.presentCorrelationContractSha256
            )
        );

        JsonObject io = new JsonObject();
        io.addProperty("fileIoDuringMeasure", false);
        io.addProperty("manifestReadsDuringMeasure", 0);
        io.addProperty("threadDiscoveryDuringMeasure", 0);
        io.addProperty("resultWritesDuringMeasure", 0);
        io.addProperty("presentCorrelationWritesDuringMeasure", 0);
        io.addProperty("presentCorrelationThreadScansDuringMeasure", 0);
        io.addProperty(
            "presentCorrelationStorage",
            "PREALLOCATED_PRIMITIVE_ARRAYS"
        );
        io.addProperty("colorReferenceWriteBeforeMeasure", true);
        io.addProperty("publication", "POST_MEASURE_ATOMIC_MOVE");
        root.add("io", io);

        JsonObject claims = new JsonObject();
        claims.addProperty("performanceBaseline", "NOT_RUN");
        claims.addProperty("fpsOrSpeedup", "NOT_CLAIMED");
        claims.addProperty("phase2a1", "NOT_STARTED");
        root.add("claims", claims);

        validateFiniteTree(root);
        return root;
    }

    private void failClosed(Exception exception) {
        failedClosed = true;
        LOGGER.error(
            "Phase 2A.0C capture failed closed: {}",
            unavailableReason(exception)
        );
    }

    static void publishAtomically(Path destination, JsonObject result)
        throws IOException, ContractException {
        Path normalized = destination.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ContractException(
                "RESULT_PARENT_NOT_PREPARED_EXTERNALLY"
            );
        }
        if (Files.exists(normalized)) {
            throw new ContractException("RESULT_ALREADY_EXISTS");
        }
        Path temporary = parent.resolve(
            normalized.getFileName() + ".tmp"
        );
        if (Files.exists(temporary)) {
            throw new ContractException("RESULT_TEMP_ALREADY_EXISTS");
        }

        byte[] json = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        Files.write(
            temporary,
            json,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        byte[] verifiedBytes = Files.readAllBytes(temporary);
        JsonObject verified = JsonParser.parseString(
            new String(verifiedBytes, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        validatePublishedResult(verified);
        try {
            Files.move(
                temporary,
                normalized,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ContractException(
                "ATOMIC_MOVE_NOT_SUPPORTED",
                exception
            );
        }
    }

    static void validatePublishedResult(JsonObject result)
        throws ContractException {
        requireInt(result, "schemaVersion", RESULT_SCHEMA_VERSION);
        requireString(result, "runId");
        requireString(result, "profileId");
        requireString(result, "sceneId");
        if (!"COMPLETE".equals(requireString(result, "state"))) {
            throw new ContractException("RESULT_NOT_COMPLETE");
        }
        JsonObject measurement = requireObject(result, "measurement");
        if (requireLong(measurement, "sampleCount") <= 0) {
            throw new ContractException("RESULT_HAS_NO_SAMPLES");
        }
        if (requireLong(measurement, "cpuBoundarySnapshots") != 2) {
            throw new ContractException(
                "RESULT_CPU_BOUNDARY_COUNT_MISMATCH"
            );
        }
        long measureStartNanos = requireLong(
            measurement,
            "measureStartNanos"
        );
        long measureEndNanos = requireLong(
            measurement,
            "measureEndNanos"
        );
        if (
            measureStartNanos <= 0L
                || measureEndNanos <= measureStartNanos
        ) {
            throw new ContractException("RESULT_MEASURE_WINDOW_INVALID");
        }
        JsonObject present = requireObject(
            result,
            "vulkanPresentCorrelation"
        );
        if (
            requireLong(present, "sampleCount") <= 0L
                || requireLong(present, "boundarySnapshots") != 2L
                || requireLong(present, "ownerPublicationCount") != 1L
                || requireLong(present, "wrongOwnerPresents") != 0L
                || requireLong(present, "wrongThreadPresents") != 0L
                || requireLong(present, "invalidMetadataPresents") != 0L
        ) {
            throw new ContractException(
                "RESULT_VULKAN_PRESENT_CORRELATION_INVALID"
            );
        }
        validateFiniteTree(result);
    }

    static void validateFiniteTree(JsonElement element)
        throws ContractException {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                validateFiniteTree(child);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (
                java.util.Map.Entry<String, JsonElement> entry
                    : element.getAsJsonObject().entrySet()
            ) {
                validateFiniteTree(entry.getValue());
            }
            return;
        }
        if (
            element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber()
        ) {
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) {
                throw new ContractException("NON_FINITE_RESULT_NUMBER");
            }
        }
    }

    static void validateIdentity(
        Receipt receipt,
        String actualProfile,
        String actualScene,
        String actualWorldDirectory,
        boolean blockframeLoaded,
        boolean sodiumLoaded,
        boolean captureLoaded
    ) throws ContractException {
        if (!receipt.profileId.equals(actualProfile)) {
            throw new ContractException("PROFILE_ID_MISMATCH");
        }
        if (!receipt.sceneId.equals(actualScene)) {
            throw new ContractException("SCENE_ID_MISMATCH");
        }
        if (
            !receipt.expectedWorldDirectoryName.equals(
                actualWorldDirectory
            )
        ) {
            throw new ContractException("RUN_WORLD_MISMATCH");
        }
        boolean expectedBlockframe = BLOCKFRAME_OFF_PROFILE.equals(
            receipt.profileId
        );
        boolean expectedSodium = SODIUM_PROFILE.equals(receipt.profileId);
        if (blockframeLoaded != expectedBlockframe) {
            throw new ContractException(
                expectedBlockframe
                    ? "BLOCKFRAME_MISSING_IN_OFF_PROFILE"
                    : "BLOCKFRAME_PRESENT_IN_NON_BLOCKFRAME_PROFILE"
            );
        }
        if (sodiumLoaded != expectedSodium) {
            throw new ContractException(
                expectedSodium
                    ? "SODIUM_MISSING_IN_SODIUM_PROFILE"
                    : "SODIUM_PRESENT_IN_NON_SODIUM_PROFILE"
            );
        }
        if (!captureLoaded) {
            throw new ContractException("CAPTURE_MOD_NOT_LOADED");
        }
    }

    static void requireHashMatch(
        String expected,
        String actual,
        String reason
    ) throws ContractException {
        if (
            !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
            )
        ) {
            throw new ContractException(reason);
        }
    }

    private static void addTyped(
        JsonObject owner,
        String name,
        String status,
        Number value,
        String reason
    ) throws ContractException {
        if (
            !status.equals("AVAILABLE")
                && !status.equals("NOT_APPLICABLE")
                && !status.equals("NOT_AVAILABLE")
                && !status.equals("ERROR")
        ) {
            throw new ContractException("INVALID_OPTIONAL_STATUS");
        }
        JsonObject typed = new JsonObject();
        typed.addProperty("status", status);
        if (value != null) {
            double checked = value.doubleValue();
            if (!Double.isFinite(checked)) {
                throw new ContractException("NON_FINITE_TYPED_VALUE");
            }
            typed.addProperty("value", value);
        }
        if (reason != null) {
            typed.addProperty("reason", reason);
        }
        owner.add(name, typed);
    }

    private static Path configuredReceiptPath() {
        String configured = System.getProperty(
            "blockframe.phase2a0c.receipt"
        );
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(
            System.getProperty("user.dir"),
            "blockframe-phase2a0c",
            "active-receipt.json"
        ).toAbsolutePath().normalize();
    }

    private static Path captureArtifactPath()
        throws URISyntaxException, ContractException {
        if (
            Phase2a0cCaptureRuntime.class
                .getProtectionDomain()
                .getCodeSource() == null
        ) {
            throw new ContractException(
                "CAPTURE_CODE_SOURCE_UNAVAILABLE"
            );
        }
        Path path = Path.of(
            Phase2a0cCaptureRuntime.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
        ).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ContractException(
                "CAPTURE_NOT_LOADED_FROM_STANDALONE_JAR"
            );
        }
        return path;
    }

    static String sha256(Path path)
        throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes)
        throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }

    private static JsonObject requireObject(JsonObject object, String name)
        throws ContractException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new ContractException("MISSING_OBJECT_" + name);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String name)
        throws ContractException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new ContractException("MISSING_ARRAY_" + name);
        }
        return value.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String name)
        throws ContractException {
        JsonElement value = object.get(name);
        if (
            value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
        ) {
            throw new ContractException("MISSING_STRING_" + name);
        }
        String result = value.getAsString();
        if (result.isBlank()) {
            throw new ContractException("BLANK_STRING_" + name);
        }
        return result;
    }

    private static boolean requireBoolean(JsonObject object, String name)
        throws ContractException {
        JsonElement value = object.get(name);
        if (
            value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()
        ) {
            throw new ContractException("MISSING_BOOLEAN_" + name);
        }
        return value.getAsBoolean();
    }

    private static long requireLong(JsonObject object, String name)
        throws ContractException {
        JsonElement value = object.get(name);
        if (
            value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()
        ) {
            throw new ContractException("MISSING_NUMBER_" + name);
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException exception) {
            throw new ContractException(
                "INVALID_LONG_" + name,
                exception
            );
        }
    }

    private static int requireInt(
        JsonObject object,
        String name,
        int expected
    ) throws ContractException {
        long value = requireLong(object, name);
        if (value != expected) {
            throw new ContractException("VALUE_MISMATCH_" + name);
        }
        return (int) value;
    }

    private static int requireIntValue(
        JsonObject object,
        String name
    ) throws ContractException {
        long value = requireLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ContractException("OUT_OF_RANGE_" + name);
        }
        return (int) value;
    }

    private static double requireFiniteDouble(
        JsonObject object,
        String name
    ) throws ContractException {
        JsonElement value = object.get(name);
        if (
            value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()
        ) {
            throw new ContractException("MISSING_NUMBER_" + name);
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new ContractException("NON_FINITE_" + name);
        }
        return result;
    }

    private static String unavailableReason(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
            + (message == null ? "" : ":" + message);
    }

    static final class Receipt {
        final String runId;
        final String profileId;
        final String sceneId;
        final String goldenSha256;
        final String runCopySha256;
        final String modProfileSha256;
        final String configProfileSha256;
        final String captureArtifactSha256;
        final String presentCorrelationContractSha256;
        final String minecraftVersion;
        final String neoForgeVersion;
        final String javaVersion;
        final String expectedBackend;
        final String expectedGpuNameContains;
        final String expectedWorldDirectoryName;
        final String expectedLevelName;
        final String dimension;
        final int resolutionWidth;
        final int resolutionHeight;
        final int renderDistanceChunks;
        final int simulationDistanceChunks;
        final int fov;
        final boolean vsync;
        final int fpsLimit;
        final SceneRoute route;
        final Path referenceColorPath;
        final int referenceDownscale;
        final long warmupNanos;
        final long measureNanos;
        final Instant deadlineUtc;
        final Path resultPath;

        private Receipt(JsonObject contract) throws ContractException {
            runId = requireString(contract, "runId");
            profileId = requireString(contract, "profileId");
            sceneId = requireString(contract, "sceneId");
            goldenSha256 = requireHash(contract, "goldenSha256");
            runCopySha256 = requireHash(contract, "runCopySha256");
            modProfileSha256 = requireHash(
                contract,
                "modProfileSha256"
            );
            configProfileSha256 = requireHash(
                contract,
                "configProfileSha256"
            );
            captureArtifactSha256 = requireHash(
                contract,
                "captureArtifactSha256"
            );
            presentCorrelationContractSha256 = requireHash(
                contract,
                "presentCorrelationContractSha256"
            );
            minecraftVersion = requireString(
                contract,
                "minecraftVersion"
            );
            neoForgeVersion = requireString(
                contract,
                "neoForgeVersion"
            );
            javaVersion = requireString(contract, "javaVersion");
            expectedBackend = requireString(
                contract,
                "expectedBackend"
            );
            expectedGpuNameContains = requireString(
                contract,
                "expectedGpuNameContains"
            );
            expectedWorldDirectoryName = requireSafeId(
                contract,
                "expectedWorldDirectoryName"
            );
            expectedLevelName = requireString(
                contract,
                "expectedLevelName"
            );
            dimension = requireString(contract, "dimension");
            resolutionWidth = positiveInt(
                contract,
                "resolutionWidth",
                16_384
            );
            resolutionHeight = positiveInt(
                contract,
                "resolutionHeight",
                16_384
            );
            renderDistanceChunks = positiveInt(
                contract,
                "renderDistanceChunks",
                64
            );
            simulationDistanceChunks = positiveInt(
                contract,
                "simulationDistanceChunks",
                64
            );
            fov = positiveInt(contract, "fov", 180);
            vsync = requireBoolean(contract, "vsync");
            fpsLimit = positiveInt(contract, "fpsLimit", 10_000);
            route = SceneRoute.fromContract(contract);
            referenceColorPath = Path.of(
                requireString(contract, "referenceColorPath")
            ).toAbsolutePath().normalize();
            referenceDownscale = positiveInt(
                contract,
                "referenceDownscale",
                16
            );
            warmupNanos = positiveLong(
                contract,
                "warmupNanos",
                300_000_000_000L
            );
            measureNanos = positiveLong(
                contract,
                "measureNanos",
                300_000_000_000L
            );
            try {
                deadlineUtc = Instant.parse(
                    requireString(contract, "deadlineUtc")
                );
            } catch (DateTimeParseException exception) {
                throw new ContractException(
                    "INVALID_DEADLINE",
                    exception
                );
            }
            resultPath = Path.of(
                requireString(contract, "resultPath")
            ).toAbsolutePath().normalize();

            if (
                !runId.matches("[A-Za-z0-9._-]{8,96}")
                    || (
                        !MOJANG_PROFILE.equals(profileId)
                            && !BLOCKFRAME_OFF_PROFILE.equals(profileId)
                            && !SODIUM_PROFILE.equals(profileId)
                    )
                    || !isSupportedScene(sceneId)
            ) {
                throw new ContractException(
                    "UNSUPPORTED_SINGLE_SCENE_CONTRACT"
                );
            }
            Path resultParent = resultPath.getParent();
            if (
                resultParent == null
                    || !resultParent.equals(
                        referenceColorPath.getParent()
                    )
                    || resultPath.equals(referenceColorPath)
            ) {
                throw new ContractException(
                    "REFERENCE_PATH_OWNERSHIP_MISMATCH"
                );
            }
        }

        static Loaded load(Path path)
            throws IOException, NoSuchAlgorithmException, ContractException {
            if (!Files.isRegularFile(path)) {
                throw new ContractException("RECEIPT_MISSING");
            }
            byte[] bytes = Files.readAllBytes(path);
            String fileHash = sha256(bytes);
            JsonElement parsed = JsonParser.parseString(
                new String(bytes, StandardCharsets.UTF_8)
            );
            if (!parsed.isJsonObject()) {
                throw new ContractException("RECEIPT_NOT_OBJECT");
            }
            JsonObject root = parsed.getAsJsonObject();
            requireInt(root, "schemaVersion", RECEIPT_SCHEMA_VERSION);
            JsonObject contract = requireObject(root, "contract");
            JsonObject integrity = requireObject(root, "integrity");
            if (
                !"SHA-256".equals(
                    requireString(integrity, "algorithm")
                )
            ) {
                throw new ContractException(
                    "RECEIPT_INTEGRITY_ALGORITHM"
                );
            }
            String expected = requireHash(
                integrity,
                "contractSha256"
            );
            String actual = sha256(
                GSON.toJson(contract).getBytes(StandardCharsets.UTF_8)
            );
            if (
                !MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII)
                )
            ) {
                throw new ContractException(
                    "RECEIPT_INTEGRITY_MISMATCH"
                );
            }
            return new Loaded(new Receipt(contract), fileHash);
        }

        private static String requireHash(
            JsonObject object,
            String name
        ) throws ContractException {
            String value = requireString(object, name);
            if (!value.matches("[0-9a-f]{64}")) {
                throw new ContractException("INVALID_HASH_" + name);
            }
            return value;
        }

        private static String requireSafeId(
            JsonObject object,
            String name
        ) throws ContractException {
            String value = requireString(object, name);
            if (
                value.contains("/")
                    || value.contains("\\")
                    || value.equals(".")
                    || value.equals("..")
            ) {
                throw new ContractException("UNSAFE_ID_" + name);
            }
            return value;
        }

        private static int positiveInt(
            JsonObject object,
            String name,
            int maximum
        ) throws ContractException {
            long value = requireLong(object, name);
            if (value <= 0L || value > maximum) {
                throw new ContractException("OUT_OF_RANGE_" + name);
            }
            return (int) value;
        }

        private static long positiveLong(
            JsonObject object,
            String name,
            long maximum
        ) throws ContractException {
            long value = requireLong(object, name);
            if (value <= 0L || value > maximum) {
                throw new ContractException("OUT_OF_RANGE_" + name);
            }
            return value;
        }

        static final class Loaded {
            final Receipt receipt;
            final String fileSha256;

            Loaded(Receipt receipt, String fileSha256) {
                this.receipt = receipt;
                this.fileSha256 = fileSha256;
            }
        }
    }

    static final class SceneRoute {
        enum Motion {
            STATIC_AT_START,
            LOOP_ROUTE,
            ROUTE_ONCE
        }

        enum Interpolation {
            LINEAR,
            SMOOTHSTEP
        }

        private final long[] timeNanos;
        private final double[] x;
        private final double[] y;
        private final double[] z;
        private final float[] yaw;
        private final float[] pitch;
        private final Motion warmupMotion;
        private final Motion measureMotion;
        private final Interpolation interpolation;
        private final long durationNanos;
        private final int referenceKeyframeIndex;

        private SceneRoute(
            long[] timeNanos,
            double[] x,
            double[] y,
            double[] z,
            float[] yaw,
            float[] pitch,
            Motion warmupMotion,
            Motion measureMotion,
            Interpolation interpolation,
            long durationNanos,
            int referenceKeyframeIndex
        ) {
            this.timeNanos = timeNanos;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.warmupMotion = warmupMotion;
            this.measureMotion = measureMotion;
            this.interpolation = interpolation;
            this.durationNanos = durationNanos;
            this.referenceKeyframeIndex = referenceKeyframeIndex;
        }

        static SceneRoute fromContract(JsonObject contract)
            throws ContractException {
            long duration = requireLong(
                contract,
                "routeDurationNanos"
            );
            if (duration <= 0L || duration > 300_000_000_000L) {
                throw new ContractException(
                    "ROUTE_DURATION_OUT_OF_RANGE"
                );
            }
            Motion warmup = parseMotion(
                requireString(contract, "warmupMotion")
            );
            Motion measure = parseMotion(
                requireString(contract, "measureMotion")
            );
            Interpolation interpolation;
            try {
                interpolation = Interpolation.valueOf(
                    requireString(contract, "interpolation")
                );
            } catch (IllegalArgumentException exception) {
                throw new ContractException(
                    "UNSUPPORTED_INTERPOLATION",
                    exception
                );
            }
            JsonArray frames = requireArray(contract, "cameraKeyframes");
            if (frames.size() < 2 || frames.size() > 16) {
                throw new ContractException("KEYFRAME_COUNT_OUT_OF_RANGE");
            }
            int count = frames.size();
            long[] times = new long[count];
            double[] x = new double[count];
            double[] y = new double[count];
            double[] z = new double[count];
            float[] yaw = new float[count];
            float[] pitch = new float[count];
            long previous = -1L;
            for (int index = 0; index < count; index++) {
                JsonObject frame = frames.get(index).getAsJsonObject();
                long time = requireLong(frame, "timeNanos");
                if (
                    time < 0L
                        || time > duration
                        || time <= previous
                ) {
                    throw new ContractException(
                        "KEYFRAME_TIME_ORDER_INVALID"
                    );
                }
                JsonArray position = requireArray(frame, "position");
                if (position.size() != 3) {
                    throw new ContractException(
                        "KEYFRAME_POSITION_ARITY"
                    );
                }
                times[index] = time;
                x[index] = finiteArrayValue(position, 0);
                y[index] = finiteArrayValue(position, 1);
                z[index] = finiteArrayValue(position, 2);
                yaw[index] = (float) requireFiniteDouble(frame, "yaw");
                pitch[index] = (float) requireFiniteDouble(
                    frame,
                    "pitch"
                );
                previous = time;
            }
            if (times[0] != 0L || times[count - 1] != duration) {
                throw new ContractException(
                    "KEYFRAME_ROUTE_ENDPOINT_MISMATCH"
                );
            }
            int reference = requireIntValue(
                contract,
                "referenceKeyframeIndex"
            );
            if (reference < 0 || reference >= count) {
                throw new ContractException(
                    "REFERENCE_KEYFRAME_OUT_OF_RANGE"
                );
            }
            return new SceneRoute(
                times,
                x,
                y,
                z,
                yaw,
                pitch,
                warmup,
                measure,
                interpolation,
                duration,
                reference
            );
        }

        private static Motion parseMotion(String value)
            throws ContractException {
            try {
                return Motion.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new ContractException(
                    "UNSUPPORTED_MOTION_MODE",
                    exception
                );
            }
        }

        private static double finiteArrayValue(
            JsonArray array,
            int index
        ) throws ContractException {
            JsonElement element = array.get(index);
            if (
                !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isNumber()
            ) {
                throw new ContractException(
                    "KEYFRAME_POSITION_NOT_NUMBER"
                );
            }
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) {
                throw new ContractException(
                    "KEYFRAME_POSITION_NON_FINITE"
                );
            }
            return value;
        }

        void apply(
            Minecraft minecraft,
            Phase2a0cCameraInvoker camera,
            SingleSceneController.MotionPhase phase,
            long elapsedNanos
        ) {
            if (phase == SingleSceneController.MotionPhase.REFERENCE) {
                applyExact(minecraft, camera, referenceKeyframeIndex);
                return;
            }
            Motion motion = phase
                == SingleSceneController.MotionPhase.WARMUP
                    ? warmupMotion
                    : measureMotion;
            long routeNanos;
            if (motion == Motion.STATIC_AT_START) {
                routeNanos = 0L;
            } else if (motion == Motion.LOOP_ROUTE) {
                routeNanos = durationNanos == 0L
                    ? 0L
                    : Math.floorMod(elapsedNanos, durationNanos);
            } else {
                routeNanos = Math.min(
                    Math.max(0L, elapsedNanos),
                    durationNanos
                );
            }
            applySample(minecraft, camera, routeNanos);
        }

        private void applyExact(
            Minecraft minecraft,
            Phase2a0cCameraInvoker camera,
            int index
        ) {
            applyPose(
                minecraft,
                camera,
                x[index],
                y[index],
                z[index],
                yaw[index],
                pitch[index]
            );
        }

        private void applySample(
            Minecraft minecraft,
            Phase2a0cCameraInvoker camera,
            long routeNanos
        ) {
            int right = 1;
            while (
                right < timeNanos.length - 1
                    && routeNanos > timeNanos[right]
            ) {
                right++;
            }
            int left = right - 1;
            long span = timeNanos[right] - timeNanos[left];
            double fraction = span <= 0L
                ? 0.0
                : (double) (routeNanos - timeNanos[left]) / span;
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            if (interpolation == Interpolation.SMOOTHSTEP) {
                fraction = fraction * fraction * (3.0 - 2.0 * fraction);
            }
            float yawDelta = wrapDegrees(yaw[right] - yaw[left]);
            applyPose(
                minecraft,
                camera,
                lerp(x[left], x[right], fraction),
                lerp(y[left], y[right], fraction),
                lerp(z[left], z[right], fraction),
                (float) (yaw[left] + yawDelta * fraction),
                (float) lerp(pitch[left], pitch[right], fraction)
            );
        }

        private static void applyPose(
            Minecraft minecraft,
            Phase2a0cCameraInvoker camera,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
        ) {
            minecraft.player.setPos(x, y, z);
            minecraft.player.setYRot(yaw);
            minecraft.player.setXRot(pitch);
            camera.blockframe$phase2a0cSetPosition(x, y, z);
            camera.blockframe$phase2a0cSetRotation(yaw, pitch);
        }

        private static double lerp(
            double left,
            double right,
            double fraction
        ) {
            return left + (right - left) * fraction;
        }

        private static float wrapDegrees(float value) {
            float wrapped = value % 360.0F;
            if (wrapped >= 180.0F) {
                wrapped -= 360.0F;
            }
            if (wrapped < -180.0F) {
                wrapped += 360.0F;
            }
            return wrapped;
        }

        int keyframeCount() {
            return timeNanos.length;
        }

        JsonObject referencePoseJson() {
            JsonObject pose = new JsonObject();
            pose.addProperty("keyframeIndex", referenceKeyframeIndex);
            pose.addProperty("x", x[referenceKeyframeIndex]);
            pose.addProperty("y", y[referenceKeyframeIndex]);
            pose.addProperty("z", z[referenceKeyframeIndex]);
            pose.addProperty("yaw", yaw[referenceKeyframeIndex]);
            pose.addProperty("pitch", pitch[referenceKeyframeIndex]);
            return pose;
        }
    }

    static final class ReferenceCapture {
        private final Path path;
        private final int downscale;
        private final int referenceKeyframeIndex;
        private volatile boolean started;
        private volatile boolean complete;
        private String sha256;
        private int width;
        private int height;
        private int sampledPixels;
        private int distinctSampledColors;
        private int nonBlackSampledPixels;
        private int minimumLuma;
        private int maximumLuma;
        private long sampledColorHash64;

        ReferenceCapture(
            Path path,
            int downscale,
            int referenceKeyframeIndex
        ) {
            this.path = path;
            this.downscale = downscale;
            this.referenceKeyframeIndex = referenceKeyframeIndex;
        }

        boolean started() {
            return started;
        }

        boolean complete() {
            return complete;
        }

        void capture(
            GameRenderer renderer,
            SceneRoute route,
            Consumer<Exception> failure
        ) throws ContractException {
            if (started) {
                throw new ContractException(
                    "COLOR_REFERENCE_ALREADY_STARTED"
                );
            }
            if (Files.exists(path)) {
                throw new ContractException(
                    "COLOR_REFERENCE_ALREADY_EXISTS"
                );
            }
            started = true;
            route.apply(
                Minecraft.getInstance(),
                (Phase2a0cCameraInvoker) (Object) renderer.mainCamera(),
                SingleSceneController.MotionPhase.REFERENCE,
                0L
            );
            Screenshot.takeScreenshot(
                renderer.mainRenderTarget(),
                downscale,
                image -> finish(image, failure)
            );
        }

        private void finish(
            com.mojang.blaze3d.platform.NativeImage image,
            Consumer<Exception> failure
        ) {
            try (image) {
                width = image.getWidth();
                height = image.getHeight();
                if (width <= 0 || height <= 0) {
                    throw new ContractException(
                        "COLOR_REFERENCE_EMPTY_DIMENSIONS"
                    );
                }
                int[] colors = new int[64];
                int unique = 0;
                int nonBlack = 0;
                int samples = 0;
                int minLuma = 255;
                int maxLuma = 0;
                long hash = 0xcbf29ce484222325L;
                int xStep = Math.max(1, width / 32);
                int yStep = Math.max(1, height / 18);
                for (int y = 0; y < height; y += yStep) {
                    for (int x = 0; x < width; x += xStep) {
                        int color = image.getPixel(x, y);
                        int red = color & 0xff;
                        int green = color >>> 8 & 0xff;
                        int blue = color >>> 16 & 0xff;
                        int luma = (red * 54 + green * 183 + blue * 19)
                            >>> 8;
                        minLuma = Math.min(minLuma, luma);
                        maxLuma = Math.max(maxLuma, luma);
                        if (luma > 8) {
                            nonBlack++;
                        }
                        boolean known = false;
                        for (int index = 0; index < unique; index++) {
                            if (colors[index] == color) {
                                known = true;
                                break;
                            }
                        }
                        if (!known && unique < colors.length) {
                            colors[unique++] = color;
                        }
                        hash ^= Integer.toUnsignedLong(color);
                        hash *= 0x100000001b3L;
                        samples++;
                    }
                }
                if (
                    unique < 8
                        || maxLuma - minLuma < 16
                        || nonBlack * 10 < samples
                ) {
                    throw new ContractException(
                        "COLOR_REFERENCE_BLANK_OR_LOW_VARIANCE"
                    );
                }
                image.writeToFile(path);
                sha256 = Phase2a0cCaptureRuntime.sha256(path);
                sampledPixels = samples;
                distinctSampledColors = unique;
                nonBlackSampledPixels = nonBlack;
                minimumLuma = minLuma;
                maximumLuma = maxLuma;
                sampledColorHash64 = hash;
                complete = true;
            } catch (Exception exception) {
                failure.accept(exception);
            }
        }

        JsonObject toJson(SceneRoute route) throws ContractException {
            if (!complete || sha256 == null || !Files.isRegularFile(path)) {
                throw new ContractException(
                    "COLOR_REFERENCE_INCOMPLETE"
                );
            }
            JsonObject result = new JsonObject();
            result.addProperty("status", "AVAILABLE");
            result.addProperty("path", path.toString());
            result.addProperty("sha256", sha256);
            result.addProperty("bytes", path.toFile().length());
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("downscale", downscale);
            result.addProperty(
                "referenceKeyframeIndex",
                referenceKeyframeIndex
            );
            result.add("pose", route.referencePoseJson());
            result.addProperty("sampledPixels", sampledPixels);
            result.addProperty(
                "distinctSampledColors",
                distinctSampledColors
            );
            result.addProperty(
                "nonBlackSampledPixels",
                nonBlackSampledPixels
            );
            result.addProperty("minimumLuma", minimumLuma);
            result.addProperty("maximumLuma", maximumLuma);
            result.addProperty(
                "sampledColorHash64",
                Long.toUnsignedString(sampledColorHash64)
            );
            result.addProperty(
                "captureTiming",
                "AFTER_WARMUP_BEFORE_MEASURE"
            );
            result.addProperty(
                "captureOwner",
                "MOJANG_SCREENSHOT_ONE_SHOT_TEMPORARY_GPU_BUFFER"
            );
            return result;
        }
    }

    static final class PresentCorrelationWindow {
        private final long[] presentIds;
        private final long[] frameIds;
        private final long[] beforeNanos;
        private final long[] afterNanos;
        private final int[] results;
        private final long[] threadIds;
        private final long[] deviceGenerations;
        private final long[] swapchainGenerations;
        private final int[] windowWidths;
        private final int[] windowHeights;
        private final int[] framebufferWidths;
        private final int[] framebufferHeights;
        private final int[] presentModes;
        private final byte[] vsyncStates;

        private long presentSequence;
        private long ownerSurfaceGeneration;
        private long ownerDeviceGeneration;
        private int ownerDeviceIdentity;
        private long ownerWindowHandle;
        private long presentThreadId = -1L;
        private int ownerPublicationCount;
        private long wrongOwnerPresents;
        private long wrongThreadPresents;
        private long invalidMetadataPresents;
        private long boundaryStartNanos;
        private long boundaryEndNanos;
        private int boundarySnapshots;
        private int sampleCount;
        private boolean active;
        private boolean overflow;

        PresentCorrelationWindow(int maximumSamples) {
            if (maximumSamples <= 0) {
                throw new IllegalArgumentException(
                    "positive present sample bound required"
                );
            }
            presentIds = new long[maximumSamples];
            frameIds = new long[maximumSamples];
            beforeNanos = new long[maximumSamples];
            afterNanos = new long[maximumSamples];
            results = new int[maximumSamples];
            threadIds = new long[maximumSamples];
            deviceGenerations = new long[maximumSamples];
            swapchainGenerations = new long[maximumSamples];
            windowWidths = new int[maximumSamples];
            windowHeights = new int[maximumSamples];
            framebufferWidths = new int[maximumSamples];
            framebufferHeights = new int[maximumSamples];
            presentModes = new int[maximumSamples];
            vsyncStates = new byte[maximumSamples];
        }

        void begin(long nowNanos) {
            if (boundarySnapshots != 0 || active) {
                throw new IllegalStateException(
                    "present start boundary already captured"
                );
            }
            boundaryStartNanos = nowNanos;
            boundarySnapshots = 1;
            active = true;
        }

        void end(long nowNanos) {
            if (boundarySnapshots != 1 || !active) {
                throw new IllegalStateException(
                    "present end boundary without start"
                );
            }
            active = false;
            boundaryEndNanos = nowNanos;
            boundarySnapshots = 2;
        }

        void accept(
            long frameId,
            long surfaceGeneration,
            long deviceGeneration,
            int deviceIdentity,
            long windowHandle,
            long swapchainGeneration,
            int windowWidth,
            int windowHeight,
            int framebufferWidth,
            int framebufferHeight,
            int presentMode,
            boolean vsync,
            long before,
            long after,
            int result
        ) {
            long presentId = ++presentSequence;
            long threadId = Thread.currentThread().threadId();
            if (ownerSurfaceGeneration == 0L) {
                ownerSurfaceGeneration = surfaceGeneration;
                ownerDeviceGeneration = deviceGeneration;
                ownerDeviceIdentity = deviceIdentity;
                ownerWindowHandle = windowHandle;
                presentThreadId = threadId;
                ownerPublicationCount = 1;
            } else if (
                ownerSurfaceGeneration != surfaceGeneration
                    || ownerDeviceGeneration != deviceGeneration
                    || ownerDeviceIdentity != deviceIdentity
                    || ownerWindowHandle != windowHandle
            ) {
                wrongOwnerPresents++;
                return;
            }
            if (presentThreadId != threadId) {
                wrongThreadPresents++;
                return;
            }
            if (!active) {
                return;
            }
            if (
                frameId <= 0L
                    || surfaceGeneration <= 0L
                    || deviceGeneration <= 0L
                    || windowHandle == 0L
                    || swapchainGeneration <= 0L
                    || windowWidth <= 0
                    || windowHeight <= 0
                    || framebufferWidth <= 0
                    || framebufferHeight <= 0
                    || presentMode < 0
                    || presentMode > 3
                    || before < boundaryStartNanos
                    || after < before
            ) {
                invalidMetadataPresents++;
                return;
            }
            if (sampleCount >= presentIds.length) {
                overflow = true;
                return;
            }
            int index = sampleCount++;
            presentIds[index] = presentId;
            frameIds[index] = frameId;
            beforeNanos[index] = before;
            afterNanos[index] = after;
            results[index] = result;
            threadIds[index] = threadId;
            deviceGenerations[index] = deviceGeneration;
            swapchainGenerations[index] = swapchainGeneration;
            windowWidths[index] = windowWidth;
            windowHeights[index] = windowHeight;
            framebufferWidths[index] = framebufferWidth;
            framebufferHeights[index] = framebufferHeight;
            presentModes[index] = presentMode;
            vsyncStates[index] = (byte) (vsync ? 1 : 0);
        }

        int sampleCount() {
            return sampleCount;
        }

        int boundarySnapshots() {
            return boundarySnapshots;
        }

        boolean validForPublication(long expectedRenderThreadId) {
            return boundarySnapshots == 2
                && !active
                && sampleCount > 0
                && !overflow
                && ownerPublicationCount == 1
                && wrongOwnerPresents == 0L
                && wrongThreadPresents == 0L
                && invalidMetadataPresents == 0L
                && presentThreadId == expectedRenderThreadId
                && boundaryEndNanos > boundaryStartNanos;
        }

        JsonObject toJson(
            long expectedRenderThreadId,
            String contractSha256
        ) throws ContractException {
            if (!validForPublication(expectedRenderThreadId)) {
                throw new ContractException(
                    "VULKAN_PRESENT_CAPTURE_INVALID"
                );
            }
            JsonObject root = new JsonObject();
            root.addProperty("status", "IN_PROCESS_PRESENT_CAPTURED");
            root.addProperty("contractSha256", contractSha256);
            root.addProperty(
                "sourceOwner",
                "MOJANG_VULKAN_GPU_SURFACE_PRESENT"
            );
            root.addProperty(
                "hookBefore",
                "AFTER_CURRENT_IMAGE_INDEX_RESET_LAST_STATE_WRITE"
            );
            root.addProperty(
                "hookAfter",
                "FIRST_VK_RESULT_STORE_AFTER_EXISTING_PRESENT_RETURN"
            );
            root.addProperty("timeSource", "JAVA_SYSTEM_NANOTIME");
            root.addProperty(
                "windowsClockContract",
                "HOTSPOT_WINDOWS_QPC_MONOTONIC_SAME_EPOCH_AS_PRESENTMON_QPC"
            );
            root.addProperty("sampleCount", sampleCount);
            root.addProperty("sampleOverflow", overflow);
            root.addProperty("boundarySnapshots", boundarySnapshots);
            root.addProperty("boundaryStartNanos", boundaryStartNanos);
            root.addProperty("boundaryEndNanos", boundaryEndNanos);
            root.addProperty("ownerPublicationCount", ownerPublicationCount);
            root.addProperty(
                "ownerSurfaceGeneration",
                ownerSurfaceGeneration
            );
            root.addProperty(
                "ownerDeviceGeneration",
                ownerDeviceGeneration
            );
            root.addProperty("ownerDeviceIdentity", ownerDeviceIdentity);
            root.addProperty("ownerWindowHandle", ownerWindowHandle);
            root.addProperty("presentThreadId", presentThreadId);
            root.addProperty(
                "renderThreadId",
                expectedRenderThreadId
            );
            root.addProperty("wrongOwnerPresents", wrongOwnerPresents);
            root.addProperty("wrongThreadPresents", wrongThreadPresents);
            root.addProperty(
                "invalidMetadataPresents",
                invalidMetadataPresents
            );
            root.addProperty(
                "storage",
                "PREALLOCATED_PARALLEL_PRIMITIVE_ARRAYS"
            );
            root.addProperty("fileIoDuringMeasure", false);
            root.addProperty("resultSerializationDuringMeasure", false);
            root.addProperty("threadScansPerPresent", 0);
            root.addProperty("extraPresents", 0);
            root.addProperty("presentWaits", 0);
            root.addProperty("swapchainChanges", 0);
            root.addProperty("enabledExtensions", 0);
            root.add("presentId", longArray(presentIds, sampleCount));
            root.add("frameId", longArray(frameIds, sampleCount));
            root.add(
                "beforePresentNanos",
                longArray(beforeNanos, sampleCount)
            );
            root.add(
                "afterPresentNanos",
                longArray(afterNanos, sampleCount)
            );
            root.add("vkResult", intArray(results, sampleCount));
            root.add("threadId", longArray(threadIds, sampleCount));
            root.add(
                "deviceGeneration",
                longArray(deviceGenerations, sampleCount)
            );
            root.add(
                "swapchainGeneration",
                longArray(swapchainGenerations, sampleCount)
            );
            root.add(
                "windowWidth",
                intArray(windowWidths, sampleCount)
            );
            root.add(
                "windowHeight",
                intArray(windowHeights, sampleCount)
            );
            root.add(
                "framebufferWidth",
                intArray(framebufferWidths, sampleCount)
            );
            root.add(
                "framebufferHeight",
                intArray(framebufferHeights, sampleCount)
            );
            root.add(
                "vkPresentMode",
                intArray(presentModes, sampleCount)
            );
            root.add(
                "vsync",
                booleanArray(vsyncStates, sampleCount)
            );
            return root;
        }

        private static JsonArray longArray(long[] values, int count) {
            JsonArray result = new JsonArray(count);
            for (int index = 0; index < count; index++) {
                result.add(values[index]);
            }
            return result;
        }

        private static JsonArray intArray(int[] values, int count) {
            JsonArray result = new JsonArray(count);
            for (int index = 0; index < count; index++) {
                result.add(values[index]);
            }
            return result;
        }

        private static JsonArray booleanArray(byte[] values, int count) {
            JsonArray result = new JsonArray(count);
            for (int index = 0; index < count; index++) {
                result.add(values[index] != 0);
            }
            return result;
        }
    }

    static final class MeasurementBoundaries
        implements SingleSceneController.CpuBoundary {
        private final CpuWindow cpu;
        private final MemoryWindow memory;
        private final PresentCorrelationWindow present;

        MeasurementBoundaries(
            CpuWindow cpu,
            MemoryWindow memory,
            PresentCorrelationWindow present
        ) {
            this.cpu = cpu;
            this.memory = memory;
            this.present = present;
        }

        @Override
        public void begin(long nowNanos) {
            present.begin(nowNanos);
            memory.begin();
            cpu.begin();
        }

        @Override
        public void end(long nowNanos) {
            cpu.end();
            memory.end();
            present.end(nowNanos);
        }
    }

    static final class SingleSceneController {
        enum State {
            UNBOUND,
            WARMUP,
            REFERENCE_PENDING,
            MEASURE,
            COMPLETE
        }

        enum MotionPhase {
            WARMUP,
            REFERENCE,
            MEASURE
        }

        interface CpuBoundary {
            void begin(long nowNanos);

            void end(long nowNanos);
        }

        private final long warmupNanos;
        private final long measureNanos;
        private final long[] frameNanos;

        private State state = State.UNBOUND;
        private long boundNanos;
        private long warmupEndNanos;
        private long measureStartNanos;
        private long measureEndNanos;
        private long measureEndObservedNanos;
        private long measureStartEpochMillis;
        private long measureEndEpochMillis;
        private long previousFrameNanos;
        private int sampleCount;
        private boolean sampleOverflow;

        SingleSceneController(
            long warmupNanos,
            long measureNanos,
            int maximumSamples
        ) {
            if (
                warmupNanos <= 0L
                    || measureNanos <= 0L
                    || maximumSamples <= 0
            ) {
                throw new IllegalArgumentException(
                    "positive single-scene bounds required"
                );
            }
            this.warmupNanos = warmupNanos;
            this.measureNanos = measureNanos;
            this.frameNanos = new long[maximumSamples];
        }

        void bind(long nowNanos) {
            if (state != State.UNBOUND) {
                return;
            }
            boundNanos = nowNanos;
            warmupEndNanos = saturatedAdd(nowNanos, warmupNanos);
            state = State.WARMUP;
        }

        boolean onFrame(
            long nowNanos,
            long epochMillis,
            CpuBoundary cpuBoundary,
            boolean referenceComplete
        ) {
            if (state == State.UNBOUND || state == State.COMPLETE) {
                return false;
            }
            if (state == State.WARMUP) {
                if (nowNanos >= warmupEndNanos) {
                    state = State.REFERENCE_PENDING;
                }
                return false;
            }
            if (state == State.REFERENCE_PENDING) {
                if (referenceComplete) {
                    measureStartNanos = nowNanos;
                    measureStartEpochMillis = epochMillis;
                    previousFrameNanos = nowNanos;
                    measureEndNanos = saturatedAdd(
                        nowNanos,
                        measureNanos
                    );
                    state = State.MEASURE;
                    cpuBoundary.begin(nowNanos);
                }
                return false;
            }
            if (nowNanos >= measureEndNanos) {
                measureEndObservedNanos = nowNanos;
                measureEndEpochMillis = epochMillis;
                state = State.COMPLETE;
                cpuBoundary.end(nowNanos);
                return true;
            }
            if (sampleCount < frameNanos.length) {
                frameNanos[sampleCount++] =
                    nowNanos - previousFrameNanos;
            } else {
                sampleOverflow = true;
            }
            previousFrameNanos = nowNanos;
            return false;
        }

        State state() {
            return state;
        }

        int sampleCount() {
            return sampleCount;
        }

        boolean sampleOverflow() {
            return sampleOverflow;
        }

        long[] frameNanos() {
            return frameNanos;
        }

        long measureStartNanos() {
            return measureStartNanos;
        }

        long measureEndObservedNanos() {
            return measureEndObservedNanos;
        }

        long measureStartEpochMillis() {
            return measureStartEpochMillis;
        }

        long measureEndEpochMillis() {
            return measureEndEpochMillis;
        }

        MotionPhase motionPhase() {
            return switch (state) {
                case WARMUP -> MotionPhase.WARMUP;
                case REFERENCE_PENDING -> MotionPhase.REFERENCE;
                case MEASURE, COMPLETE -> MotionPhase.MEASURE;
                case UNBOUND -> MotionPhase.WARMUP;
            };
        }

        long motionNanos(long nowNanos) {
            return switch (state) {
                case WARMUP -> Math.max(0L, nowNanos - boundNanos);
                case MEASURE, COMPLETE ->
                    Math.max(0L, nowNanos - measureStartNanos);
                case REFERENCE_PENDING, UNBOUND -> 0L;
            };
        }

        private static long saturatedAdd(long left, long right) {
            long result = left + right;
            if (((left ^ result) & (right ^ result)) < 0L) {
                return Long.MAX_VALUE;
            }
            return result;
        }
    }

    static final class MemoryWindow {
        private final MemoryMXBean memoryBean =
            ManagementFactory.getMemoryMXBean();
        private final GarbageCollectorMXBean[] garbageCollectors =
            ManagementFactory.getGarbageCollectorMXBeans().toArray(
                GarbageCollectorMXBean[]::new
            );
        private final SystemInfo systemInfo = new SystemInfo();

        private long heapUsedStart;
        private long heapUsedEnd;
        private long heapCommittedStart;
        private long heapCommittedEnd;
        private long nonHeapUsedStart;
        private long nonHeapUsedEnd;
        private long gcCountStart;
        private long gcCountEnd;
        private long gcTimeStartMillis;
        private long gcTimeEndMillis;
        private long residentSetStart;
        private long residentSetEnd;
        private long virtualSizeStart;
        private long virtualSizeEnd;
        private String processMemoryUnavailableReason;

        void begin() {
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
            heapUsedStart = heap.getUsed();
            heapCommittedStart = heap.getCommitted();
            nonHeapUsedStart = nonHeap.getUsed();
            gcCountStart = garbageCollectionCount();
            gcTimeStartMillis = garbageCollectionTimeMillis();
            long[] process = processMemory();
            residentSetStart = process[0];
            virtualSizeStart = process[1];
        }

        void end() {
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
            heapUsedEnd = heap.getUsed();
            heapCommittedEnd = heap.getCommitted();
            nonHeapUsedEnd = nonHeap.getUsed();
            gcCountEnd = garbageCollectionCount();
            gcTimeEndMillis = garbageCollectionTimeMillis();
            long[] process = processMemory();
            residentSetEnd = process[0];
            virtualSizeEnd = process[1];
        }

        private long garbageCollectionCount() {
            long total = 0L;
            for (GarbageCollectorMXBean collector : garbageCollectors) {
                long value = collector.getCollectionCount();
                if (value >= 0L) {
                    total += value;
                }
            }
            return total;
        }

        private long garbageCollectionTimeMillis() {
            long total = 0L;
            for (GarbageCollectorMXBean collector : garbageCollectors) {
                long value = collector.getCollectionTime();
                if (value >= 0L) {
                    total += value;
                }
            }
            return total;
        }

        private long[] processMemory() {
            try {
                OSProcess process = systemInfo
                    .getOperatingSystem()
                    .getProcess((int) ProcessHandle.current().pid());
                if (process == null) {
                    processMemoryUnavailableReason =
                        "OS_PROCESS_NOT_AVAILABLE";
                    return new long[] {-1L, -1L};
                }
                return new long[] {
                    process.getResidentSetSize(),
                    process.getVirtualSize()
                };
            } catch (RuntimeException exception) {
                processMemoryUnavailableReason =
                    unavailableReason(exception);
                return new long[] {-1L, -1L};
            }
        }

        JsonObject toJson() throws ContractException {
            JsonObject root = new JsonObject();
            JsonObject heap = new JsonObject();
            heap.addProperty("usedStartBytes", heapUsedStart);
            heap.addProperty("usedEndBytes", heapUsedEnd);
            heap.addProperty(
                "usedDeltaBytes",
                heapUsedEnd - heapUsedStart
            );
            heap.addProperty("committedStartBytes", heapCommittedStart);
            heap.addProperty("committedEndBytes", heapCommittedEnd);
            root.add("heap", heap);

            JsonObject nonHeap = new JsonObject();
            nonHeap.addProperty("usedStartBytes", nonHeapUsedStart);
            nonHeap.addProperty("usedEndBytes", nonHeapUsedEnd);
            nonHeap.addProperty(
                "usedDeltaBytes",
                nonHeapUsedEnd - nonHeapUsedStart
            );
            root.add("nonHeap", nonHeap);

            JsonObject gc = new JsonObject();
            gc.addProperty(
                "collections",
                Math.max(0L, gcCountEnd - gcCountStart)
            );
            gc.addProperty(
                "pauseMillis",
                Math.max(0L, gcTimeEndMillis - gcTimeStartMillis)
            );
            root.add("gc", gc);

            addTyped(
                root,
                "processResidentSetStartBytes",
                residentSetStart >= 0L
                    ? "AVAILABLE"
                    : "NOT_AVAILABLE",
                residentSetStart >= 0L ? residentSetStart : null,
                residentSetStart >= 0L
                    ? null
                    : processMemoryUnavailableReason
            );
            addTyped(
                root,
                "processResidentSetEndBytes",
                residentSetEnd >= 0L
                    ? "AVAILABLE"
                    : "NOT_AVAILABLE",
                residentSetEnd >= 0L ? residentSetEnd : null,
                residentSetEnd >= 0L
                    ? null
                    : processMemoryUnavailableReason
            );
            addTyped(
                root,
                "processVirtualStartBytes",
                virtualSizeStart >= 0L
                    ? "AVAILABLE"
                    : "NOT_AVAILABLE",
                virtualSizeStart >= 0L ? virtualSizeStart : null,
                virtualSizeStart >= 0L
                    ? null
                    : processMemoryUnavailableReason
            );
            addTyped(
                root,
                "processVirtualEndBytes",
                virtualSizeEnd >= 0L
                    ? "AVAILABLE"
                    : "NOT_AVAILABLE",
                virtualSizeEnd >= 0L ? virtualSizeEnd : null,
                virtualSizeEnd >= 0L
                    ? null
                    : processMemoryUnavailableReason
            );
            return root;
        }
    }

    static final class CpuWindow {
        private enum Category {
            RENDER_MAIN,
            SERVER,
            MOJANG_WORKER,
            VULKAN_SUBMISSION,
            BLOCKFRAME_WORKER,
            IO,
            GC,
            JIT,
            OTHER,
            UNKNOWN
        }

        private final ThreadMXBean threadBean =
            ManagementFactory.getThreadMXBean();
        private final com.sun.management.OperatingSystemMXBean osBean;
        private final long[] threadIds;
        private final byte[] categories;
        private final long[] startCpu;
        private final long[] endCpu;
        private final long[] startUser;
        private final long[] endUser;
        private final long[] categoryCpu;
        private final long[] categoryUser;
        private final int[] categoryThreadCounts;
        private final long[] newThreadIds;
        private final long[] endedThreadIds;

        private final String cpuModel;
        private final int physicalCores;
        private final int logicalProcessors;
        private final int availableProcessors;
        private final String processAffinity;
        private final String topologyUnavailableReason;

        private int trackedThreadCount;
        private int newThreadCount;
        private int endedThreadCount;
        private int boundarySnapshots;
        private long processCpuStart;
        private long processCpuEnd;
        private long wallStart;
        private long wallEnd;
        private String cpuUnavailableReason;

        CpuWindow(int maximumThreads) {
            threadIds = new long[maximumThreads];
            categories = new byte[maximumThreads];
            startCpu = new long[maximumThreads];
            endCpu = new long[maximumThreads];
            startUser = new long[maximumThreads];
            endUser = new long[maximumThreads];
            categoryCpu = new long[Category.values().length];
            categoryUser = new long[Category.values().length];
            categoryThreadCounts = new int[Category.values().length];
            newThreadIds = new long[maximumThreads];
            endedThreadIds = new long[maximumThreads];

            java.lang.management.OperatingSystemMXBean generic =
                ManagementFactory.getOperatingSystemMXBean();
            osBean = generic
                instanceof com.sun.management.OperatingSystemMXBean supported
                    ? supported
                    : null;

            String detectedModel = "NOT_AVAILABLE";
            int detectedPhysical = 0;
            int detectedLogical = 0;
            String detectedAffinity = "NOT_AVAILABLE";
            String topologyFailure = null;
            try {
                SystemInfo info = new SystemInfo();
                CentralProcessor processor =
                    info.getHardware().getProcessor();
                detectedModel = processor
                    .getProcessorIdentifier()
                    .getName();
                detectedPhysical = processor.getPhysicalProcessorCount();
                detectedLogical = processor.getLogicalProcessorCount();
                OSProcess process = info.getOperatingSystem().getProcess(
                    (int) ProcessHandle.current().pid()
                );
                if (process != null) {
                    detectedAffinity = "0x" + Long.toUnsignedString(
                        process.getAffinityMask(),
                        16
                    );
                } else {
                    topologyFailure = "OS_PROCESS_NOT_AVAILABLE";
                }
            } catch (RuntimeException exception) {
                topologyFailure = unavailableReason(exception);
            }
            cpuModel = detectedModel;
            physicalCores = detectedPhysical;
            logicalProcessors = detectedLogical;
            availableProcessors = Runtime.getRuntime()
                .availableProcessors();
            processAffinity = detectedAffinity;
            topologyUnavailableReason = topologyFailure;
        }

        public void begin() {
            if (boundarySnapshots != 0) {
                throw new IllegalStateException(
                    "CPU start boundary already captured"
                );
            }
            if (
                threadBean.isThreadCpuTimeSupported()
                    && !threadBean.isThreadCpuTimeEnabled()
            ) {
                try {
                    threadBean.setThreadCpuTimeEnabled(true);
                } catch (RuntimeException exception) {
                    cpuUnavailableReason = unavailableReason(exception);
                }
            }

            long[] discovered = threadBean.getAllThreadIds();
            if (discovered.length > threadIds.length) {
                trackedThreadCount = threadIds.length;
                cpuUnavailableReason =
                    "THREAD_CAPACITY_EXCEEDED_"
                        + discovered.length
                        + "_OF_"
                        + threadIds.length;
            } else {
                trackedThreadCount = discovered.length;
            }
            System.arraycopy(
                discovered,
                0,
                threadIds,
                0,
                trackedThreadCount
            );
            ThreadInfo[] information = threadBean.getThreadInfo(
                Arrays.copyOf(discovered, trackedThreadCount),
                0
            );
            for (int index = 0; index < trackedThreadCount; index++) {
                String name = information[index] == null
                    ? null
                    : information[index].getThreadName();
                Category category = classify(name);
                categories[index] = (byte) category.ordinal();
                categoryThreadCounts[category.ordinal()]++;
            }
            captureKnownThreads(startCpu, startUser);
            processCpuStart = processCpuTime();
            wallStart = System.nanoTime();
            boundarySnapshots = 1;
        }

        public void end() {
            if (boundarySnapshots != 1) {
                throw new IllegalStateException(
                    "CPU end boundary without start"
                );
            }
            wallEnd = System.nanoTime();
            processCpuEnd = processCpuTime();
            captureKnownThreads(endCpu, endUser);
            aggregateKnownThreads();
            boundarySnapshots = 2;
        }

        void discoverThreadLifecycleAfterMeasure() {
            if (boundarySnapshots != 2) {
                throw new IllegalStateException(
                    "thread lifecycle scan before end boundary"
                );
            }
            long[] finalIds = threadBean.getAllThreadIds();
            newThreadCount = 0;
            for (
                int index = 0;
                index < finalIds.length
                    && newThreadCount < newThreadIds.length;
                index++
            ) {
                if (!contains(threadIds, trackedThreadCount, finalIds[index])) {
                    newThreadIds[newThreadCount++] = finalIds[index];
                }
            }
            endedThreadCount = 0;
            for (int index = 0; index < trackedThreadCount; index++) {
                if (
                    endCpu[index] < 0L
                        && endedThreadCount < endedThreadIds.length
                ) {
                    endedThreadIds[endedThreadCount++] = threadIds[index];
                }
            }
        }

        int boundarySnapshots() {
            return boundarySnapshots;
        }

        long wallDeltaNanos() {
            return Math.max(0L, wallEnd - wallStart);
        }

        JsonObject toJson() throws ContractException {
            JsonObject root = new JsonObject();
            root.addProperty("model", cpuModel);
            addTyped(
                root,
                "physicalCores",
                physicalCores > 0 ? "AVAILABLE" : "NOT_AVAILABLE",
                physicalCores > 0 ? physicalCores : null,
                physicalCores > 0 ? null : topologyUnavailableReason
            );
            addTyped(
                root,
                "logicalProcessors",
                logicalProcessors > 0 ? "AVAILABLE" : "NOT_AVAILABLE",
                logicalProcessors > 0 ? logicalProcessors : null,
                logicalProcessors > 0 ? null : topologyUnavailableReason
            );
            root.addProperty(
                "availableProcessors",
                availableProcessors
            );
            JsonObject affinity = new JsonObject();
            affinity.addProperty(
                "status",
                processAffinity.equals("NOT_AVAILABLE")
                    ? "NOT_AVAILABLE"
                    : "AVAILABLE"
            );
            if (!processAffinity.equals("NOT_AVAILABLE")) {
                affinity.addProperty("value", processAffinity);
            } else {
                affinity.addProperty(
                    "reason",
                    topologyUnavailableReason == null
                        ? "OS_AFFINITY_NOT_AVAILABLE"
                        : topologyUnavailableReason
                );
            }
            root.add("processAffinity", affinity);
            root.addProperty(
                "boundarySnapshots",
                boundarySnapshots
            );
            root.addProperty("trackedThreads", trackedThreadCount);
            root.addProperty("wallNanos", wallDeltaNanos());

            long processDelta = processCpuStart < 0L || processCpuEnd < 0L
                ? -1L
                : Math.max(0L, processCpuEnd - processCpuStart);
            addTyped(
                root,
                "processTotalCpuNanos",
                processDelta >= 0L ? "AVAILABLE" : "NOT_AVAILABLE",
                processDelta >= 0L ? processDelta : null,
                processDelta >= 0L
                    ? null
                    : "PROCESS_CPU_TIME_NOT_AVAILABLE"
            );

            long threadCpuTotal = 0L;
            long threadUserTotal = 0L;
            for (int index = 0; index < categoryCpu.length; index++) {
                threadCpuTotal += categoryCpu[index];
                threadUserTotal += categoryUser[index];
            }
            addTyped(
                root,
                "classifiedThreadCpuNanos",
                cpuUnavailableReason == null
                    ? "AVAILABLE"
                    : "ERROR",
                threadCpuTotal,
                cpuUnavailableReason
            );
            addTyped(
                root,
                "classifiedThreadUserCpuNanos",
                cpuUnavailableReason == null
                    ? "AVAILABLE"
                    : "ERROR",
                threadUserTotal,
                cpuUnavailableReason
            );

            long wall = wallDeltaNanos();
            if (wall > 0L && processDelta >= 0L) {
                addTyped(
                    root,
                    "approximateUtilizedCores",
                    "AVAILABLE",
                    (double) processDelta / (double) wall,
                    null
                );
            } else {
                addTyped(
                    root,
                    "approximateUtilizedCores",
                    "NOT_AVAILABLE",
                    null,
                    "PROCESS_OR_WALL_CPU_DELTA_NOT_AVAILABLE"
                );
            }

            JsonObject groups = new JsonObject();
            Category[] values = Category.values();
            for (int index = 0; index < values.length; index++) {
                JsonObject group = new JsonObject();
                group.addProperty(
                    "threadCountAtStart",
                    categoryThreadCounts[index]
                );
                group.addProperty("cpuNanos", categoryCpu[index]);
                group.addProperty("userCpuNanos", categoryUser[index]);
                groups.add(values[index].name(), group);
            }
            root.add("threadCategories", groups);

            addWorkerImbalance(root);

            JsonArray created = new JsonArray(newThreadCount);
            for (int index = 0; index < newThreadCount; index++) {
                created.add(newThreadIds[index]);
            }
            root.add("threadsCreatedAfterStartBoundary", created);
            JsonArray ended = new JsonArray(endedThreadCount);
            for (int index = 0; index < endedThreadCount; index++) {
                ended.add(endedThreadIds[index]);
            }
            root.add("threadsEndedDuringWindow", ended);
            return root;
        }

        private void captureKnownThreads(long[] cpu, long[] user) {
            boolean available = threadBean.isThreadCpuTimeSupported()
                && threadBean.isThreadCpuTimeEnabled();
            for (int index = 0; index < trackedThreadCount; index++) {
                cpu[index] = available
                    ? threadBean.getThreadCpuTime(threadIds[index])
                    : -1L;
                user[index] = available
                    ? threadBean.getThreadUserTime(threadIds[index])
                    : -1L;
            }
            if (!available && cpuUnavailableReason == null) {
                cpuUnavailableReason =
                    "THREAD_CPU_TIME_NOT_SUPPORTED_OR_DISABLED";
            }
        }

        private void aggregateKnownThreads() {
            Arrays.fill(categoryCpu, 0L);
            Arrays.fill(categoryUser, 0L);
            for (int index = 0; index < trackedThreadCount; index++) {
                int category = categories[index];
                long cpuDelta = delta(startCpu[index], endCpu[index]);
                long userDelta = delta(startUser[index], endUser[index]);
                categoryCpu[category] += cpuDelta;
                categoryUser[category] += userDelta;
            }
        }

        private void addWorkerImbalance(JsonObject root)
            throws ContractException {
            long minimum = Long.MAX_VALUE;
            long maximum = 0L;
            long total = 0L;
            int count = 0;
            int target = Category.MOJANG_WORKER.ordinal();
            for (int index = 0; index < trackedThreadCount; index++) {
                if (categories[index] != target) {
                    continue;
                }
                long value = delta(startCpu[index], endCpu[index]);
                if (value > 0L) {
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                    total += value;
                    count++;
                }
            }
            if (count > 0 && total > 0L) {
                double average = (double) total / (double) count;
                addTyped(
                    root,
                    "workerImbalanceMaxMinusMinOverMean",
                    "AVAILABLE",
                    (maximum - minimum) / average,
                    null
                );
            } else {
                addTyped(
                    root,
                    "workerImbalanceMaxMinusMinOverMean",
                    "NOT_AVAILABLE",
                    null,
                    "NO_ACTIVE_MOJANG_WORKER_CPU_DELTA"
                );
            }
        }

        private long processCpuTime() {
            return osBean == null ? -1L : osBean.getProcessCpuTime();
        }

        private static long delta(long start, long end) {
            return start < 0L || end < 0L
                ? 0L
                : Math.max(0L, end - start);
        }

        private static boolean contains(
            long[] values,
            int count,
            long target
        ) {
            for (int index = 0; index < count; index++) {
                if (values[index] == target) {
                    return true;
                }
            }
            return false;
        }

        private static Category classify(String name) {
            if (name == null || name.isBlank()) {
                return Category.UNKNOWN;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (
                normalized.equals("render thread")
                    || normalized.equals("main")
            ) {
                return Category.RENDER_MAIN;
            }
            if (
                normalized.contains("server thread")
                    || normalized.contains("integrated server")
            ) {
                return Category.SERVER;
            }
            if (normalized.contains("blockframe")) {
                return Category.BLOCKFRAME_WORKER;
            }
            if (
                normalized.contains("vulkan")
                    || normalized.contains("submission")
                    || normalized.contains("present")
            ) {
                return Category.VULKAN_SUBMISSION;
            }
            if (
                normalized.contains("worker")
                    || normalized.contains("forkjoin")
            ) {
                return Category.MOJANG_WORKER;
            }
            if (
                normalized.contains("file io")
                    || normalized.contains("io-worker")
                    || normalized.contains("netty")
                    || normalized.contains("download")
            ) {
                return Category.IO;
            }
            if (normalized.contains("gc")) {
                return Category.GC;
            }
            if (
                normalized.contains("compilerthread")
                    || normalized.contains("jit")
            ) {
                return Category.JIT;
            }
            return Category.OTHER;
        }
    }

    private record RuntimeAttestation(
        String minecraftVersion,
        String neoForgeVersion,
        String javaVersion,
        String backend,
        String gpu,
        String driver,
        String worldDirectory,
        String levelName,
        String dimension,
        long windowHandle,
        int windowWidth,
        int windowHeight,
        int framebufferWidth,
        int framebufferHeight,
        boolean blockframeLoaded,
        boolean sodiumLoaded
    ) {}

    static final class ContractException extends Exception {
        ContractException(String message) {
            super(message);
        }

        ContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
