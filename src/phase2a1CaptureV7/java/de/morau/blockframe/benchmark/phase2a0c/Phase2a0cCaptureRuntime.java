package de.morau.blockframe.benchmark.phase2a0c;

import de.morau.blockframe.benchmark.phase2a0c.mixin.Phase2a0cCameraInvoker;
import de.morau.blockframe.benchmark.phase2a0c.mixin.Phase2a1LevelRendererAccessor;
import de.morau.blockframe.benchmark.phase2a0c.mixin.Phase2a1SectionRenderDispatcherBuffersAccessor;
import de.morau.blockframe.benchmark.phase2a0c.mixin.Phase2a1SectionUberBuffersAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
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
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.software.os.OSProcess;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.joml.Matrix4f;
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
    static final String BLOCKFRAME_ON_PROFILE = "BLOCKFRAME_0_3_14_ON";
    static final String SODIUM_PROFILE = "SODIUM_0_9_1_VULKAN";
    static final int RESULT_SCHEMA_VERSION = 5;
    static final int RECEIPT_SCHEMA_VERSION = 1;
    static final int MAX_FRAME_SAMPLES = 262_144;
    static final int MAX_PRESENT_SAMPLES = 262_144;
    static final int MAX_TRACKED_THREADS = 1_024;
    static final int DEFAULT_QUIESCENCE_FRAMES = 300;

    public static final int DRAW_ELIGIBLE_SOLID = 0;
    public static final int DRAW_CUTOUT = 1;
    public static final int DRAW_TRANSLUCENT = 2;
    public static final int DRAW_ENTITY_ABI = 3;
    public static final int DRAW_PARTICLE = 4;
    public static final int DRAW_OUTLINE = 5;
    public static final int DRAW_DEBUG = 6;
    public static final int DRAW_UI = 7;
    public static final int DRAW_STATIC_DECORATION = 8;
    public static final int DRAW_SPECIAL_SHADER = 9;
    public static final int DRAW_OTHER = 10;
    static final int DRAW_CATEGORY_COUNT = 11;
    private static final String[] DRAW_CATEGORY_NAMES = {
        "ELIGIBLE_OPAQUE_SOLID",
        "CUTOUT",
        "TRANSLUCENT_AND_FLUID",
        "ENTITY_ABI_OWNER_UNSEPARATED",
        "PARTICLE",
        "OUTLINE",
        "DEBUG",
        "UI",
        "STATIC_DECORATION_UNKNOWN_OWNER",
        "SPECIAL_SHADER",
        "OTHER_MOJANG_OR_MOD"
    };

    private static final Logger LOGGER =
        LoggerFactory.getLogger(Phase2a0cCaptureMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();
    private static final long CURRENT_PROCESS_ID =
        ProcessHandle.current().pid();
    private static final Win32WindowApi WIN32_WINDOW_API =
        new NativeWin32WindowApi();
    private static final AtomicLong AGGREGATE_UPLOAD_BACKLOG =
        new AtomicLong();
    private static final AtomicLong STATIC_MUTATION_SEQUENCE =
        new AtomicLong();

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
    private final WorkloadWindow workloadWindow;
    private final TerrainFrameAudit terrainFrameAudit;
    private final DeterministicGate deterministicGate;
    private final CanonicalVisibleWorkload canonicalWorkload;
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
        String captureVersion,
        CanonicalVisibleWorkload canonicalWorkload
    ) {
        this.receipt = receipt;
        this.receiptSha256 = receiptSha256;
        this.captureArtifactSha256 = captureArtifactSha256;
        this.captureVersion = captureVersion;
        this.canonicalWorkload = canonicalWorkload;
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
        this.workloadWindow = new WorkloadWindow(MAX_FRAME_SAMPLES);
        this.terrainFrameAudit = new TerrainFrameAudit();
        this.deterministicGate = new DeterministicGate(
            receipt.staticRendererGate,
            receipt.quiescenceFrames,
            receipt.quiescenceReadyPath,
            receipt.measureArmPath,
            receipt.runId
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
                captureVersion,
                CanonicalVisibleWorkload.load(loaded.receipt)
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

    /**
     * Called only by the development-capture mixin after Mojang has published
     * the current frame's visible list. Failures disable capture but leave the
     * normal Mojang list and renderer alive.
     */
    public static void canonicalizeVisibleSections(
        java.util.List<SectionRenderDispatcher.RenderSection> visible,
        ViewArea viewArea
    ) {
        Phase2a0cCaptureRuntime current = instance;
        if (current == null || current.failedClosed) {
            return;
        }
        try {
            current.canonicalWorkload.canonicalize(visible, viewArea);
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            current.failClosed(
                error instanceof Exception exception
                    ? exception
                    : new Exception(
                        "CANONICAL_VISIBLE_WORKLOAD_HOOK_FAILED",
                        error
                    )
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
     * Counts one real Mojang terrain multi-draw invocation. The call is a
     * primitive increment only while MEASURE is active.
     */
    public static void onTerrainDrawSubmission(int drawRecords) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.workloadWindow.recordTerrainDrawSubmission(
                drawRecords,
                current.controller.state()
                    == SingleSceneController.State.MEASURE
            );
        }
    }

    /**
     * Records the duration of Mojang's existing terrain upload call. It never
     * starts an upload and owns no Vulkan resource.
     */
    public static void onTerrainUpload(long elapsedNanos) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.workloadWindow.recordTerrainUpload(
                elapsedNanos,
                current.controller.state()
                    == SingleSceneController.State.MEASURE
            );
        }
    }

    public static boolean suppressDynamicParticles() {
        Phase2a0cCaptureRuntime current = instance;
        return current != null
            && current.receipt.staticRendererGate
            && current.controller.state()
                != SingleSceneController.State.COMPLETE;
    }

    public static void onUploadBacklogDelta(int delta) {
        if (delta == 0) {
            return;
        }
        long value = AGGREGATE_UPLOAD_BACKLOG.addAndGet(delta);
        if (value < 0L) {
            AGGREGATE_UPLOAD_BACKLOG.set(Long.MAX_VALUE);
        }
    }

    public static void onStaticGateExternalMutation(String kind) {
        Phase2a0cCaptureRuntime current = instance;
        if (current == null || !current.receipt.staticRendererGate) {
            return;
        }
        STATIC_MUTATION_SEQUENCE.incrementAndGet();
        current.deterministicGate.observeExternalMutation(kind);
    }

    public static int classifyPipeline(
        VulkanRenderPipeline compiled
    ) {
        if (compiled == null) {
            return DRAW_OTHER;
        }
        RenderPipeline pipeline = compiled.info();
        if (pipeline == ChunkSectionLayer.SOLID.pipeline()) {
            return DRAW_ELIGIBLE_SOLID;
        }
        if (pipeline == ChunkSectionLayer.CUTOUT.pipeline()) {
            return DRAW_CUTOUT;
        }
        if (pipeline == ChunkSectionLayer.TRANSLUCENT.pipeline()) {
            return DRAW_TRANSLUCENT;
        }
        String path = pipeline.getLocation().getPath();
        if (
            path.contains("entity")
                || path.contains("armor")
                || path.contains("item")
                || path.contains("beacon")
                || path.contains("banner")
                || path.contains("leash")
                || path.contains("end_portal")
                || path.contains("end_gateway")
        ) {
            return DRAW_ENTITY_ABI;
        }
        if (path.contains("particle") || path.contains("weather")) {
            return DRAW_PARTICLE;
        }
        if (path.contains("outline")) {
            return DRAW_OUTLINE;
        }
        if (
            path.contains("debug")
                || path.contains("wireframe")
                || path.contains("lines")
        ) {
            return DRAW_DEBUG;
        }
        if (
            path.contains("gui")
                || path.contains("screen")
                || path.contains("crosshair")
                || path.contains("vignette")
                || path.contains("mojang_logo")
                || path.contains("panorama")
        ) {
            return DRAW_UI;
        }
        if (
            path.contains("solid_block")
                || path.contains("cutout_block")
                || path.contains("crumbling")
                || path.contains("water_mask")
        ) {
            return DRAW_STATIC_DECORATION;
        }
        if (
            path.contains("sky")
                || path.contains("cloud")
                || path.contains("lightning")
                || path.contains("dragon")
                || path.contains("glint")
                || path.contains("text")
                || path.contains("lightmap")
                || path.contains("animate_sprite")
        ) {
            return DRAW_SPECIAL_SHADER;
        }
        return DRAW_OTHER;
    }

    public static void onBackendDraw(
        int category,
        int records,
        long cpuNanos
    ) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.workloadWindow.recordBackendDraw(
                category,
                records,
                cpuNanos,
                current.controller.state()
                    == SingleSceneController.State.MEASURE
            );
        }
    }

    public static void onOpaqueSolidIndirectCall(
        int maximumDrawCount
    ) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.workloadWindow.recordOpaqueSolidIndirectCall(
                maximumDrawCount,
                current.controller.state()
                    == SingleSceneController.State.MEASURE
            );
        }
    }

    public static void onOpaqueSolidIndirectCpuNanos(long cpuNanos) {
        Phase2a0cCaptureRuntime current = instance;
        if (current != null) {
            current.workloadWindow.recordOpaqueSolidIndirectCpuNanos(
                cpuNanos,
                current.controller.state()
                    == SingleSceneController.State.MEASURE
            );
        }
    }

    /**
     * Resolves and validates the two deliberately distinct Windows window
     * identities. This is called only during surface/owner setup, never from
     * the per-present measurement hot path.
     */
    public static WindowIdentity resolveWindowIdentity(
        long glfwWindowPointer
    ) {
        if (glfwWindowPointer == 0L) {
            return WindowIdentity.unavailable(
                glfwWindowPointer,
                0L,
                "GLFW_WINDOW_POINTER_ZERO"
            );
        }
        try {
            long win32Hwnd = GLFWNativeWin32.glfwGetWin32Window(
                glfwWindowPointer
            );
            return inspectWindowIdentity(
                glfwWindowPointer,
                win32Hwnd,
                CURRENT_PROCESS_ID,
                WIN32_WINDOW_API
            );
        } catch (Throwable failure) {
            return WindowIdentity.unavailable(
                glfwWindowPointer,
                0L,
                "GLFW_NATIVE_WIN32_UNAVAILABLE_"
                    + failure.getClass().getSimpleName()
            );
        }
    }

    static WindowIdentity inspectWindowIdentity(
        long glfwWindowPointer,
        long win32Hwnd,
        long expectedProcessId,
        Win32WindowApi api
    ) {
        if (glfwWindowPointer == 0L) {
            return WindowIdentity.unavailable(
                glfwWindowPointer,
                win32Hwnd,
                "GLFW_WINDOW_POINTER_ZERO"
            );
        }
        if (win32Hwnd == 0L) {
            return WindowIdentity.unavailable(
                glfwWindowPointer,
                win32Hwnd,
                "WIN32_HWND_ZERO"
            );
        }
        if (!api.isWindow(win32Hwnd)) {
            return WindowIdentity.unavailable(
                glfwWindowPointer,
                win32Hwnd,
                "WIN32_IS_WINDOW_FALSE"
            );
        }
        long ownerProcessId = api.windowProcessId(win32Hwnd);
        if (ownerProcessId <= 0L) {
            return WindowIdentity.unavailable(
                glfwWindowPointer,
                win32Hwnd,
                "WIN32_WINDOW_PROCESS_UNAVAILABLE"
            );
        }
        if (ownerProcessId != expectedProcessId) {
            return new WindowIdentity(
                glfwWindowPointer,
                win32Hwnd,
                ownerProcessId,
                expectedProcessId,
                false,
                "WIN32_WINDOW_PROCESS_MISMATCH"
            );
        }
        return new WindowIdentity(
            glfwWindowPointer,
            win32Hwnd,
            ownerProcessId,
            expectedProcessId,
            true,
            "NONE"
        );
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
        long glfwWindowPointer,
        long win32Hwnd,
        long windowProcessId,
        boolean windowIdentityValid,
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
                glfwWindowPointer,
                win32Hwnd,
                windowProcessId,
                windowIdentityValid,
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
                route.applyWorldStateContract(
                    minecraft,
                    receipt.staticRendererGate
                );
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
        boolean measureReady =
            referenceCapture.complete()
                && deterministicGate.measureReady();
        boolean startingMeasure =
            controller.state()
                == SingleSceneController.State.REFERENCE_PENDING
                && measureReady;
        if (startingMeasure) {
            try {
                workloadWindow.begin(snapshotWorkload(minecraft, renderer));
            } catch (Exception exception) {
                failClosed(exception);
                return;
            }
        }

        boolean justCompleted = controller.onFrame(
            nowNanos,
            System.currentTimeMillis(),
            boundaries,
            measureReady
        );
        if (justCompleted) {
            try {
                workloadWindow.end(snapshotWorkload(minecraft, renderer));
            } catch (Exception exception) {
                failClosed(exception);
                return;
            }
        }
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
        long glfwWindowPointer,
        long win32Hwnd,
        long windowProcessId,
        boolean windowIdentityValid,
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
            glfwWindowPointer,
            win32Hwnd,
            windowProcessId,
            windowIdentityValid,
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
        if (failedClosed || !renderWorld) {
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
            if (
                !route.settleStaticWorldState(
                    Minecraft.getInstance(),
                    receipt.staticRendererGate
                )
            ) {
                return;
            }
        } catch (Exception exception) {
            failClosed(exception);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && receipt.fixedGameTime >= 0L) {
            minecraft.level.setTimeFromServer(receipt.fixedGameTime);
        }
        SingleSceneController.State state = controller.state();
        if (
            state == SingleSceneController.State.MEASURE
                || state
                    == SingleSceneController.State.REFERENCE_PENDING
        ) {
            try {
                terrainFrameAudit.scan(
                    minecraft,
                    renderer.mainCamera(),
                    currentCompileQueueSize(),
                    AGGREGATE_UPLOAD_BACKLOG.get(),
                    STATIC_MUTATION_SEQUENCE.get()
                );
                terrainFrameAudit.canonicalWorkloadReady =
                    canonicalWorkload.readyForGate();
                terrainFrameAudit.canonicalWorkloadRejection =
                    canonicalWorkload.gateRejectionReason();
                if (
                    state
                        == SingleSceneController.State.REFERENCE_PENDING
                ) {
                    deterministicGate.accept(terrainFrameAudit);
                    if (deterministicGate.quiescent()) {
                        canonicalWorkload.prepareAtQuiescence(
                            minecraft,
                            renderer.mainCamera(),
                            minecraft.levelRenderer.visibleSections(),
                            terrainFrameAudit.compileQueueSize,
                            terrainFrameAudit.uploadBacklog
                        );
                    }
                    if (
                        deterministicGate.quiescent()
                            && !referenceCapture.started()
                    ) {
                        referenceCapture.capture(
                            renderer,
                            route,
                            failure -> failClosed(failure)
                        );
                    }
                    if (
                        deterministicGate.quiescent()
                            && referenceCapture.complete()
                    ) {
                        deterministicGate.publishReady(
                            receipt,
                            captureArtifactSha256,
                            snapshotWorkload(
                                Minecraft.getInstance(),
                                renderer
                            )
                        );
                        deterministicGate.pollMeasureArm();
                    }
                    return;
                }
                deterministicGate.verifyMeasureFrame(
                    terrainFrameAudit
                );
                workloadWindow.captureFrame(terrainFrameAudit);
            } catch (Exception exception) {
                failClosed(exception);
            }
            return;
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
            ? (
                BLOCKFRAME_ON_PROFILE.equals(receipt.profileId)
                    ? BLOCKFRAME_ON_PROFILE
                    : BLOCKFRAME_OFF_PROFILE
            )
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
                || minecraft.options.guiScale().get()
                    != receipt.guiScaleOption
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

        WindowIdentity windowIdentity = resolveWindowIdentity(
            minecraft.getWindow().handle()
        );
        if (!windowIdentity.valid()) {
            throw new ContractException(
                "WINDOW_IDENTITY_UNAVAILABLE_"
                    + windowIdentity.unavailableReason()
            );
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
            windowIdentity.glfwWindowPointer(),
            windowIdentity.win32Hwnd(),
            windowIdentity.ownerProcessId(),
            windowIdentity.expectedProcessId(),
            windowIdentity.valid(),
            minecraft.getWindow().getScreenWidth(),
            minecraft.getWindow().getScreenHeight(),
            minecraft.getWindow().getWidth(),
            minecraft.getWindow().getHeight(),
            minecraft.options.guiScale().get(),
            minecraft.getWindow().getGuiScale(),
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

    private WorkloadSnapshot snapshotWorkload(
        Minecraft minecraft,
        GameRenderer renderer
    ) throws ContractException {
        if (minecraft.level == null || renderer.mainCamera() == null) {
            throw new ContractException("WORKLOAD_WORLD_UNAVAILABLE");
        }
        long entityCount = 0L;
        for (Object ignored : minecraft.level.entitiesForRendering()) {
            entityCount++;
        }
        VisibleSolidIdentity visibleSolid =
            snapshotVisibleSolidIdentity(minecraft);
        Camera camera = renderer.mainCamera();
        return new WorkloadSnapshot(
            minecraft.level.getGameTime(),
            minecraft.level.getOverworldClockTime(),
            minecraft.level.getRainLevel(1.0F),
            minecraft.level.getThunderLevel(1.0F),
            visibleSolid.totalVisibleSections(),
            visibleSolid.compatibleSectionNodes(),
            visibleSolid.sectionNodeSha256(),
            visibleSolid.drawTemplateSha256(),
            visibleSolid.materialContract(),
            visibleSolid.materialContractSha256(),
            currentLoadedChunks(),
            entityCount,
            minecraft.level.getGloballyRenderedBlockEntities().size(),
            minecraft.particleEngine.countParticles(),
            currentCompileQueueSize(),
            minecraft.player.getX(),
            minecraft.player.getY(),
            minecraft.player.getZ(),
            minecraft.player.getYRot(),
            minecraft.player.getXRot(),
            camera.position().x(),
            camera.position().y(),
            camera.position().z(),
            camera.yRot(),
            camera.xRot(),
            CanonicalVisibleWorkload.captureMatrix(camera)
        );
    }

    private static int currentVisibleSections()
        throws ContractException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.levelRenderer == null) {
            throw new ContractException(
                "VISIBLE_SECTION_OWNER_UNAVAILABLE"
            );
        }
        return minecraft.levelRenderer.visibleSections().size();
    }

    /**
     * Captures the exact Mojang-owned visible solid set only at the two
     * measurement boundaries. It neither scans renderer slots nor runs per
     * frame. The parallel arrays are sorted together so the serialized IDs and
     * template checksum are canonical across fresh JVM processes.
     */
    private static VisibleSolidIdentity snapshotVisibleSolidIdentity(
        Minecraft minecraft
    ) throws ContractException {
        if (minecraft == null || minecraft.levelRenderer == null) {
            throw new ContractException(
                "VISIBLE_SECTION_OWNER_UNAVAILABLE"
            );
        }
        var visible = minecraft.levelRenderer.visibleSections();
        long[] nodes = new long[visible.size()];
        long[] templates = new long[visible.size()];
        int compatible = 0;
        for (SectionRenderDispatcher.RenderSection section : visible) {
            SectionMesh.SectionDraw draw = section
                .getSectionMesh()
                .getSectionDraw(ChunkSectionLayer.SOLID);
            if (draw == null) {
                continue;
            }
            nodes[compatible] = section.getSectionNode();
            templates[compatible] =
                ((long) draw.indexCount() << 32)
                    | ((long) draw.indexType().bytes << 1)
                    | (draw.hasCustomIndexBuffer() ? 1L : 0L);
            compatible++;
        }
        nodes = Arrays.copyOf(nodes, compatible);
        templates = Arrays.copyOf(templates, compatible);
        sortIdentityPairs(nodes, templates, 0, compatible - 1);
        String materialContract = "minecraft:block-atlas:solid";
        try {
            MessageDigest nodeDigest =
                MessageDigest.getInstance("SHA-256");
            MessageDigest templateDigest =
                MessageDigest.getInstance("SHA-256");
            templateDigest.update(
                materialContract.getBytes(StandardCharsets.UTF_8)
            );
            for (int index = 0; index < compatible; index++) {
                updateLong(nodeDigest, nodes[index]);
                updateLong(templateDigest, nodes[index]);
                updateLong(templateDigest, templates[index]);
            }
            return new VisibleSolidIdentity(
                visible.size(),
                nodes,
                HexFormat.of().formatHex(nodeDigest.digest()),
                HexFormat.of().formatHex(templateDigest.digest()),
                materialContract,
                sha256(
                    materialContract.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException error) {
            throw new ContractException(
                "VISIBLE_SOLID_IDENTITY_SHA256_UNAVAILABLE"
            );
        }
    }

    private static void sortIdentityPairs(
        long[] nodes,
        long[] templates,
        int low,
        int high
    ) {
        int left = low;
        int right = high;
        long pivot = low <= high
            ? nodes[(low + high) >>> 1]
            : 0L;
        while (left <= right) {
            while (nodes[left] < pivot) {
                left++;
            }
            while (nodes[right] > pivot) {
                right--;
            }
            if (left <= right) {
                long node = nodes[left];
                nodes[left] = nodes[right];
                nodes[right] = node;
                long template = templates[left];
                templates[left] = templates[right];
                templates[right] = template;
                left++;
                right--;
            }
        }
        if (low < right) {
            sortIdentityPairs(nodes, templates, low, right);
        }
        if (left < high) {
            sortIdentityPairs(nodes, templates, left, high);
        }
    }

    private static void updateLong(
        MessageDigest digest,
        long value
    ) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static int currentLoadedChunks() throws ContractException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            throw new ContractException("CHUNK_SOURCE_UNAVAILABLE");
        }
        return minecraft.level.getChunkSource().getLoadedChunksCount();
    }

    private static int currentCompileQueueSize()
        throws ContractException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.levelRenderer == null) {
            throw new ContractException(
                "SECTION_DISPATCHER_OWNER_UNAVAILABLE"
            );
        }
        SectionRenderDispatcher dispatcher =
            ((Phase2a1LevelRendererAccessor) minecraft.levelRenderer)
                .blockframe$phase2a1SectionRenderDispatcher();
        if (dispatcher == null) {
            throw new ContractException(
                "SECTION_DISPATCHER_UNAVAILABLE"
            );
        }
        return dispatcher.getCompileQueueSize();
    }

    private JsonObject buildCompleteResult() throws ContractException {
        if (
            controller.state() != SingleSceneController.State.COMPLETE
                || cpuWindow.boundarySnapshots() != 2
                || controller.sampleCount() <= 0
                || !workloadWindow.validForPublication()
                || !deterministicGate.measureReady()
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
        runtime.addProperty(
            "glfwWindowPointer",
            attestation.glfwWindowPointer
        );
        runtime.addProperty("win32Hwnd", attestation.win32Hwnd);
        runtime.addProperty(
            "win32WindowProcessId",
            attestation.win32WindowProcessId
        );
        runtime.addProperty(
            "expectedProcessId",
            attestation.expectedProcessId
        );
        runtime.addProperty(
            "win32WindowValid",
            attestation.win32WindowValid
        );
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
            "renderDistanceChunks",
            receipt.renderDistanceChunks
        );
        runtime.addProperty(
            "simulationDistanceChunks",
            receipt.simulationDistanceChunks
        );
        runtime.addProperty("configuredFov", receipt.fov);
        runtime.addProperty(
            "configuredGuiScaleOption",
            attestation.guiScaleOption
        );
        runtime.addProperty(
            "effectiveGuiScale",
            attestation.effectiveGuiScale
        );
        runtime.addProperty("fpsLimit", receipt.fpsLimit);
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

        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("goldenSha256", receipt.goldenSha256);
        artifacts.addProperty(
            "runCopySourceSha256",
            receipt.runCopySha256
        );
        artifacts.addProperty(
            "modProfileSha256",
            receipt.modProfileSha256
        );
        artifacts.addProperty(
            "configProfileSha256",
            receipt.configProfileSha256
        );
        artifacts.addProperty(
            "resourcePackSha256",
            receipt.resourcePackSha256
        );
        artifacts.addProperty(
            "presentCorrelationContractSha256",
            receipt.presentCorrelationContractSha256
        );
        root.add("artifactAttestation", artifacts);
        root.add(
            "canonicalVisibleWorkload",
            canonicalWorkload.toJson()
        );

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
        owner.addProperty("fixedDayTime", route.fixedDayTime);
        owner.addProperty("fixedWeather", route.fixedWeather.name());
        owner.addProperty(
            "worldStateSetup",
            route.fixedDayTime >= 0L
                ? (
                    receipt.staticRendererGate
                        ? "COMMANDS_THEN_TICK_FREEZE_AFTER_WEATHER_SETTLED"
                        : "COMMANDS_SENT_ONCE_BEFORE_WARMUP"
                )
                : "UNCHANGED"
        );
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
        root.add("workloadAttestation", workloadWindow.toJson());
        root.add(
            "deterministicRendererGate",
            deterministicGate.toJson()
        );
        root.add(
            "drawRecordAudit",
            workloadWindow.drawRecordAudit(receipt.profileId)
        );

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
        claims.addProperty(
            "phase2a1",
            "WORKLOAD_PARITY_TRIAGE_ONLY"
        );
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

    private static void publishHandshakeAtomically(
        Path destination,
        JsonObject value
    ) throws IOException, ContractException {
        Path normalized = destination.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ContractException(
                "HANDSHAKE_PARENT_NOT_PREPARED_EXTERNALLY"
            );
        }
        if (Files.exists(normalized)) {
            throw new ContractException(
                "HANDSHAKE_ALREADY_EXISTS"
            );
        }
        Path temporary = parent.resolve(
            normalized.getFileName() + ".tmp"
        );
        if (Files.exists(temporary)) {
            throw new ContractException(
                "HANDSHAKE_TEMP_ALREADY_EXISTS"
            );
        }
        byte[] json = GSON.toJson(value).getBytes(
            StandardCharsets.UTF_8
        );
        Files.write(
            temporary,
            json,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        byte[] verified = Files.readAllBytes(temporary);
        JsonElement parsed = JsonParser.parseString(
            new String(verified, StandardCharsets.UTF_8)
        );
        if (!parsed.isJsonObject()) {
            throw new ContractException(
                "HANDSHAKE_VERIFICATION_FAILED"
            );
        }
        try {
            Files.move(
                temporary,
                normalized,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ContractException(
                "HANDSHAKE_ATOMIC_MOVE_NOT_SUPPORTED",
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
        JsonObject workload = requireObject(
            result,
            "workloadAttestation"
        );
        if (
            !"AVAILABLE".equals(requireString(workload, "status"))
                || requireLong(workload, "sampleCount") <= 0L
                || workload.get("sampleOverflow") == null
                || workload.get("sampleOverflow").getAsBoolean()
        ) {
            throw new ContractException(
                "RESULT_WORKLOAD_ATTESTATION_INVALID"
            );
        }
        requireObject(workload, "start");
        requireObject(workload, "end");
        requireObject(workload, "perRenderedFrame");
        JsonObject deterministic = requireObject(
            result,
            "deterministicRendererGate"
        );
        if (
            requireBoolean(deterministic, "enabled")
                && (
                    !"PASSED".equals(
                        requireString(deterministic, "status")
                    )
                        || requireLong(
                            deterministic,
                            "consecutiveIdenticalFrames"
                        ) < DEFAULT_QUIESCENCE_FRAMES
                        || !requireBoolean(
                            deterministic,
                            "measureArmObserved"
                        )
                        || requireBoolean(
                            deterministic,
                            "invalidatedAfterReady"
                        )
                )
        ) {
            throw new ContractException(
                "RESULT_DETERMINISTIC_GATE_INVALID"
            );
        }
        JsonObject canonical = requireObject(
            result,
            "canonicalVisibleWorkload"
        );
        if (!"PASSED".equals(requireString(canonical, "status"))) {
            throw new ContractException(
                "RESULT_CANONICAL_WORKLOAD_INVALID"
            );
        }
        requireObject(result, "artifactAttestation");
        JsonObject drawAudit = requireObject(result, "drawRecordAudit");
        for (
            String field : new String[] {
                "baselineTerrainDrawRecords",
                "eligibleOpaqueSolidTerrainRecords",
                "suppressedEligibleOpaqueSolidRecords",
                "residualEligibleOpaqueSolidRecords",
                "cutoutTerrainRecords",
                "translucentTerrainRecords",
                "fluidTerrainRecords",
                "entityAbiRecords",
                "otherKnownRecords",
                "totalInstrumentedRecords",
                "unmeasuredRendererRecords",
                "indirectCallsPerFrame",
                "indirectCallsTotal",
                "activeBucketsPerFrame",
                "executedIndirectCommandsPerFrame"
            }
        ) {
            if (!drawAudit.has(field)) {
                throw new ContractException(
                    "RESULT_DRAW_AUDIT_FIELD_MISSING_" + field
                );
            }
        }
        if (drawAudit.has("baselineTotalDrawRecords")) {
            throw new ContractException(
                "RESULT_MISLEADING_BASELINE_TOTAL_PRESENT"
            );
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
        boolean expectedBlockframe =
            BLOCKFRAME_OFF_PROFILE.equals(receipt.profileId)
                || BLOCKFRAME_ON_PROFILE.equals(receipt.profileId);
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
        final String resourcePackSha256;
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
        final int guiScaleOption;
        final boolean vsync;
        final int fpsLimit;
        final long fixedGameTime;
        final String canonicalMode;
        final Path canonicalVisibleWorkloadPath;
        final String canonicalVisibleWorkloadSha256;
        final SceneRoute route;
        final Path referenceColorPath;
        final int referenceDownscale;
        final boolean staticRendererGate;
        final int quiescenceFrames;
        final Path quiescenceReadyPath;
        final Path measureArmPath;
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
            resourcePackSha256 = requireHash(
                contract,
                "resourcePackSha256"
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
            guiScaleOption = requireIntValue(
                contract,
                "guiScaleOption"
            );
            vsync = requireBoolean(contract, "vsync");
            fpsLimit = positiveInt(contract, "fpsLimit", 10_000);
            fixedGameTime = requireLong(contract, "fixedGameTime");
            canonicalMode = requireString(contract, "canonicalMode");
            canonicalVisibleWorkloadPath = Path.of(
                requireString(
                    contract,
                    "canonicalVisibleWorkloadPath"
                )
            ).toAbsolutePath().normalize();
            canonicalVisibleWorkloadSha256 =
                CanonicalVisibleWorkload.ENFORCE.equals(canonicalMode)
                    ? requireHash(
                        contract,
                        "canonicalVisibleWorkloadSha256"
                    )
                    : null;
            route = SceneRoute.fromContract(contract);
            referenceColorPath = Path.of(
                requireString(contract, "referenceColorPath")
            ).toAbsolutePath().normalize();
            referenceDownscale = positiveInt(
                contract,
                "referenceDownscale",
                16
            );
            staticRendererGate = contract.has("staticRendererGate")
                && requireBoolean(contract, "staticRendererGate");
            quiescenceFrames = staticRendererGate
                ? positiveInt(
                    contract,
                    "quiescenceFrames",
                    10_000
                )
                : DEFAULT_QUIESCENCE_FRAMES;
            quiescenceReadyPath = staticRendererGate
                ? Path.of(
                    requireString(contract, "quiescenceReadyPath")
                ).toAbsolutePath().normalize()
                : resultPathPlaceholder(referenceColorPath);
            measureArmPath = staticRendererGate
                ? Path.of(
                    requireString(contract, "measureArmPath")
                ).toAbsolutePath().normalize()
                : resultPathPlaceholder(referenceColorPath);
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
                            && !BLOCKFRAME_ON_PROFILE.equals(profileId)
                            && !SODIUM_PROFILE.equals(profileId)
                    )
                    || !isSupportedScene(sceneId)
                    || fixedGameTime < 0L
                    || guiScaleOption < 0
                    || (
                        !CanonicalVisibleWorkload.DISCOVER.equals(
                            canonicalMode
                        )
                            && !CanonicalVisibleWorkload.ENFORCE.equals(
                                canonicalMode
                            )
                    )
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
            if (
                staticRendererGate
                    && (
                        quiescenceFrames < DEFAULT_QUIESCENCE_FRAMES
                            || !resultParent.equals(
                                quiescenceReadyPath.getParent()
                            )
                            || !resultParent.equals(
                                measureArmPath.getParent()
                            )
                            || quiescenceReadyPath.equals(
                                measureArmPath
                            )
                            || quiescenceReadyPath.equals(resultPath)
                            || measureArmPath.equals(resultPath)
                            || quiescenceReadyPath.equals(
                                referenceColorPath
                            )
                            || measureArmPath.equals(
                                referenceColorPath
                            )
                    )
            ) {
                throw new ContractException(
                    "STATIC_GATE_PATH_OR_FRAME_CONTRACT_INVALID"
                );
            }
            Path canonicalName =
                canonicalVisibleWorkloadPath.getFileName();
            if (
                !staticRendererGate
                    || canonicalName == null
                    || !"greenfield-canonical-visible-workload-v1.json"
                        .equals(canonicalName.toString())
                    || (
                        CanonicalVisibleWorkload.ENFORCE.equals(
                            canonicalMode
                        )
                            && !Files.isRegularFile(
                                canonicalVisibleWorkloadPath
                            )
                    )
            ) {
                throw new ContractException(
                    "CANONICAL_WORKLOAD_PATH_OR_MODE_INVALID"
                );
            }
        }

        private static Path resultPathPlaceholder(Path referencePath) {
            Path parent = referencePath.getParent();
            return parent == null
                ? referencePath
                : parent.resolve(".static-gate-unused");
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

        enum FixedWeather {
            UNCHANGED,
            CLEAR,
            RAIN
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
        private final long fixedDayTime;
        private final FixedWeather fixedWeather;
        private boolean staticTickFreezePending;
        private boolean staticTickFreezeSent;

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
            int referenceKeyframeIndex,
            long fixedDayTime,
            FixedWeather fixedWeather
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
            this.fixedDayTime = fixedDayTime;
            this.fixedWeather = fixedWeather;
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
            long fixedDayTime = -1L;
            if (contract.has("fixedDayTime")) {
                fixedDayTime = requireLong(contract, "fixedDayTime");
                if (fixedDayTime < 0L || fixedDayTime > 1_000_000L) {
                    throw new ContractException(
                        "FIXED_DAY_TIME_OUT_OF_RANGE"
                    );
                }
            }
            FixedWeather fixedWeather = FixedWeather.UNCHANGED;
            if (contract.has("fixedWeather")) {
                try {
                    fixedWeather = FixedWeather.valueOf(
                        requireString(contract, "fixedWeather")
                    );
                } catch (IllegalArgumentException exception) {
                    throw new ContractException(
                        "UNSUPPORTED_FIXED_WEATHER",
                        exception
                    );
                }
            }
            if (
                fixedDayTime < 0L
                    && fixedWeather != FixedWeather.UNCHANGED
            ) {
                throw new ContractException(
                    "FIXED_WEATHER_REQUIRES_FIXED_DAY_TIME"
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
                reference,
                fixedDayTime,
                fixedWeather
            );
        }

        long fixedDayTime() {
            return fixedDayTime;
        }

        void applyWorldStateContract(
            Minecraft minecraft,
            boolean staticRendererGate
        )
            throws ContractException {
            if (
                fixedDayTime < 0L
                    && fixedWeather == FixedWeather.UNCHANGED
            ) {
                return;
            }
            if (
                minecraft == null
                    || minecraft.getConnection() == null
                    || minecraft.getSingleplayerServer() == null
            ) {
                throw new ContractException(
                    "WORLD_STATE_COMMAND_OWNER_UNAVAILABLE"
                );
            }
            minecraft.getConnection().sendCommand("time pause");
            minecraft.getConnection().sendCommand(
                "time set " + fixedDayTime
            );
            if (staticRendererGate) {
                minecraft.getConnection().sendCommand(
                    "gamerule minecraft:spawn_mobs false"
                );
                minecraft.getConnection().sendCommand(
                    "gamerule minecraft:random_tick_speed 0"
                );
            }
            if (fixedWeather == FixedWeather.CLEAR) {
                minecraft.getConnection().sendCommand(
                    "weather clear 1000000"
                );
            } else if (fixedWeather == FixedWeather.RAIN) {
                minecraft.getConnection().sendCommand(
                    "weather rain 1000000"
                );
            }
            if (fixedWeather != FixedWeather.UNCHANGED) {
                minecraft.getConnection().sendCommand(
                    "gamerule advance_weather false"
                );
            }
            staticTickFreezePending = staticRendererGate;
        }

        boolean settleStaticWorldState(
            Minecraft minecraft,
            boolean staticRendererGate
        ) throws ContractException {
            if (!staticRendererGate || staticTickFreezeSent) {
                return true;
            }
            if (
                !staticTickFreezePending
                    || minecraft == null
                    || minecraft.level == null
                    || minecraft.getConnection() == null
            ) {
                throw new ContractException(
                    "STATIC_WORLD_SETTLEMENT_OWNER_UNAVAILABLE"
                );
            }
            float rainLevel = minecraft.level.getRainLevel(1.0F);
            float thunderLevel =
                minecraft.level.getThunderLevel(1.0F);
            if (
                !weatherSettled(
                    fixedWeather,
                    rainLevel,
                    thunderLevel
                )
            ) {
                return false;
            }
            minecraft.getConnection().sendCommand("tick freeze");
            staticTickFreezePending = false;
            staticTickFreezeSent = true;
            return true;
        }

        static boolean weatherSettled(
            FixedWeather weather,
            float rainLevel,
            float thunderLevel
        ) {
            if (
                weather == null
                    || !Float.isFinite(rainLevel)
                    || !Float.isFinite(thunderLevel)
            ) {
                return false;
            }
            return switch (weather) {
                case CLEAR ->
                    Float.compare(rainLevel, 0.0F) == 0
                        && Float.compare(thunderLevel, 0.0F) == 0;
                case RAIN ->
                    Float.compare(rainLevel, 1.0F) == 0
                        && Float.compare(thunderLevel, 0.0F) == 0;
                case UNCHANGED -> true;
            };
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
        private final Win32WindowApi windowApi;
        private final long expectedProcessId;
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
        private long ownerGlfwWindowPointer;
        private long ownerWin32Hwnd;
        private long ownerWindowProcessId;
        private boolean ownerWindowIdentityValid;
        private long presentThreadId = -1L;
        private int ownerPublicationCount;
        private long wrongOwnerPresents;
        private long wrongThreadPresents;
        private long invalidMetadataPresents;
        private int nativeWindowIdentityChecks;
        private int nativeWindowIdentityFailures;
        private long boundaryStartNanos;
        private long boundaryEndNanos;
        private int boundarySnapshots;
        private int sampleCount;
        private boolean active;
        private boolean overflow;

        PresentCorrelationWindow(int maximumSamples) {
            this(maximumSamples, WIN32_WINDOW_API, CURRENT_PROCESS_ID);
        }

        PresentCorrelationWindow(
            int maximumSamples,
            Win32WindowApi windowApi,
            long expectedProcessId
        ) {
            if (maximumSamples <= 0) {
                throw new IllegalArgumentException(
                    "positive present sample bound required"
                );
            }
            this.windowApi = windowApi;
            this.expectedProcessId = expectedProcessId;
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
            verifyNativeWindowIdentity();
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
            verifyNativeWindowIdentity();
            active = false;
            boundaryEndNanos = nowNanos;
            boundarySnapshots = 2;
        }

        void accept(
            long frameId,
            long surfaceGeneration,
            long deviceGeneration,
            int deviceIdentity,
            long glfwWindowPointer,
            long win32Hwnd,
            long windowProcessId,
            boolean windowIdentityValid,
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
                ownerGlfwWindowPointer = glfwWindowPointer;
                ownerWin32Hwnd = win32Hwnd;
                ownerWindowProcessId = windowProcessId;
                ownerWindowIdentityValid = windowIdentityValid;
                presentThreadId = threadId;
                ownerPublicationCount = 1;
            } else if (
                ownerSurfaceGeneration != surfaceGeneration
                    || ownerDeviceGeneration != deviceGeneration
                    || ownerDeviceIdentity != deviceIdentity
                    || ownerGlfwWindowPointer != glfwWindowPointer
                    || ownerWin32Hwnd != win32Hwnd
                    || ownerWindowProcessId != windowProcessId
                    || ownerWindowIdentityValid != windowIdentityValid
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
                    || glfwWindowPointer == 0L
                    || win32Hwnd == 0L
                    || windowProcessId != expectedProcessId
                    || !windowIdentityValid
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
                && ownerGlfwWindowPointer != 0L
                && ownerWin32Hwnd != 0L
                && ownerWindowProcessId == expectedProcessId
                && ownerWindowIdentityValid
                && nativeWindowIdentityChecks == 2
                && nativeWindowIdentityFailures == 0
                && presentThreadId == expectedRenderThreadId
                && boundaryEndNanos > boundaryStartNanos;
        }

        private void verifyNativeWindowIdentity() {
            nativeWindowIdentityChecks++;
            WindowIdentity current = inspectWindowIdentity(
                ownerGlfwWindowPointer,
                ownerWin32Hwnd,
                expectedProcessId,
                windowApi
            );
            if (
                !current.valid()
                    || current.glfwWindowPointer()
                        != ownerGlfwWindowPointer
                    || current.win32Hwnd() != ownerWin32Hwnd
                    || current.ownerProcessId()
                        != ownerWindowProcessId
            ) {
                nativeWindowIdentityFailures++;
            }
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
            root.addProperty(
                "ownerGlfwWindowPointer",
                ownerGlfwWindowPointer
            );
            root.addProperty("ownerWin32Hwnd", ownerWin32Hwnd);
            root.addProperty(
                "ownerWindowProcessId",
                ownerWindowProcessId
            );
            root.addProperty(
                "expectedProcessId",
                expectedProcessId
            );
            root.addProperty(
                "ownerWindowIdentityValid",
                ownerWindowIdentityValid
            );
            root.addProperty(
                "nativeWindowIdentityChecks",
                nativeWindowIdentityChecks
            );
            root.addProperty(
                "nativeWindowIdentityFailures",
                nativeWindowIdentityFailures
            );
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

    record VisibleSolidIdentity(
        int totalVisibleSections,
        long[] compatibleSectionNodes,
        String sectionNodeSha256,
        String drawTemplateSha256,
        String materialContract,
        String materialContractSha256
    ) {
        VisibleSolidIdentity {
            if (
                totalVisibleSections < 0
                    || compatibleSectionNodes == null
                    || sectionNodeSha256 == null
                    || sectionNodeSha256.length() != 64
                    || drawTemplateSha256 == null
                    || drawTemplateSha256.length() != 64
                    || materialContract == null
                    || materialContract.isBlank()
                    || materialContractSha256 == null
                    || materialContractSha256.length() != 64
            ) {
                throw new IllegalArgumentException(
                    "complete visible solid identity required"
                );
            }
            compatibleSectionNodes = compatibleSectionNodes.clone();
        }

        @Override
        public long[] compatibleSectionNodes() {
            return compatibleSectionNodes.clone();
        }
    }

    record WorkloadSnapshot(
        long gameTime,
        long dayTime,
        float rainLevel,
        float thunderLevel,
        int visibleSections,
        long[] visibleCompatibleSolidSectionNodes,
        String visibleCompatibleSolidSectionNodeSha256,
        String visibleCompatibleSolidDrawTemplateSha256,
        String solidMaterialContract,
        String solidMaterialContractSha256,
        int loadedChunks,
        long entitiesForRendering,
        int globallyRenderedBlockEntities,
        String particleCountText,
        int compileQueueSize,
        double playerX,
        double playerY,
        double playerZ,
        float playerYaw,
        float playerPitch,
        double cameraX,
        double cameraY,
        double cameraZ,
        float cameraYaw,
        float cameraPitch,
        CanonicalVisibleWorkload.MatrixContract matrixContract
    ) {
        WorkloadSnapshot {
            if (
                visibleSections < 0
                    || visibleCompatibleSolidSectionNodes == null
                    || visibleCompatibleSolidSectionNodeSha256 == null
                    || visibleCompatibleSolidSectionNodeSha256.length()
                        != 64
                    || visibleCompatibleSolidDrawTemplateSha256 == null
                    || visibleCompatibleSolidDrawTemplateSha256.length()
                        != 64
                    || solidMaterialContract == null
                    || solidMaterialContract.isBlank()
                    || solidMaterialContractSha256 == null
                    || solidMaterialContractSha256.length() != 64
                    || loadedChunks < 0
                    || entitiesForRendering < 0L
                    || globallyRenderedBlockEntities < 0
                    || particleCountText == null
                    || compileQueueSize < 0
                    || !Double.isFinite(playerX)
                    || !Double.isFinite(playerY)
                    || !Double.isFinite(playerZ)
                    || !Float.isFinite(playerYaw)
                    || !Float.isFinite(playerPitch)
                    || !Float.isFinite(rainLevel)
                    || !Float.isFinite(thunderLevel)
                    || !Double.isFinite(cameraX)
                    || !Double.isFinite(cameraY)
                    || !Double.isFinite(cameraZ)
                    || !Float.isFinite(cameraYaw)
                    || !Float.isFinite(cameraPitch)
                    || matrixContract == null
            ) {
                throw new IllegalArgumentException(
                    "finite non-negative workload snapshot required"
                );
            }
            visibleCompatibleSolidSectionNodes =
                visibleCompatibleSolidSectionNodes.clone();
        }

        @Override
        public long[] visibleCompatibleSolidSectionNodes() {
            return visibleCompatibleSolidSectionNodes.clone();
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("gameTime", gameTime);
            root.addProperty("dayTime", dayTime);
            root.addProperty("rainLevel", rainLevel);
            root.addProperty("thunderLevel", thunderLevel);
            root.addProperty("visibleSections", visibleSections);
            JsonObject compatibleSolid = new JsonObject();
            compatibleSolid.addProperty(
                "status",
                "EXACT_BOUNDARY_IDENTITY_AVAILABLE"
            );
            compatibleSolid.addProperty(
                "captureTiming",
                "BOUNDARIES_ONLY_OUTSIDE_MEASURE"
            );
            compatibleSolid.addProperty(
                "count",
                visibleCompatibleSolidSectionNodes.length
            );
            JsonArray sectionNodes = new JsonArray(
                visibleCompatibleSolidSectionNodes.length
            );
            for (long sectionNode : visibleCompatibleSolidSectionNodes) {
                sectionNodes.add(sectionNode);
            }
            compatibleSolid.add("sectionNodes", sectionNodes);
            compatibleSolid.addProperty(
                "sectionNodeSha256",
                visibleCompatibleSolidSectionNodeSha256
            );
            compatibleSolid.addProperty(
                "drawTemplateSha256",
                visibleCompatibleSolidDrawTemplateSha256
            );
            compatibleSolid.addProperty(
                "materialContract",
                solidMaterialContract
            );
            compatibleSolid.addProperty(
                "materialContractSha256",
                solidMaterialContractSha256
            );
            root.add("compatibleOpaqueSolid", compatibleSolid);
            root.addProperty("loadedChunks", loadedChunks);
            root.addProperty(
                "entitiesForRendering",
                entitiesForRendering
            );
            root.addProperty(
                "globallyRenderedBlockEntities",
                globallyRenderedBlockEntities
            );
            root.addProperty("particleCountText", particleCountText);
            root.addProperty("compileQueueSize", compileQueueSize);
            JsonObject player = new JsonObject();
            player.addProperty("x", playerX);
            player.addProperty("y", playerY);
            player.addProperty("z", playerZ);
            player.addProperty("yaw", playerYaw);
            player.addProperty("pitch", playerPitch);
            root.add("player", player);
            JsonObject camera = new JsonObject();
            camera.addProperty("x", cameraX);
            camera.addProperty("y", cameraY);
            camera.addProperty("z", cameraZ);
            camera.addProperty("yaw", cameraYaw);
            camera.addProperty("pitch", cameraPitch);
            root.add("camera", camera);
            root.add("matrixContract", matrixContract.toJson());
            return root;
        }
    }

    static final class TerrainFrameAudit {
        private static final ChunkSectionLayer[] LAYERS =
            ChunkSectionLayer.values();
        private final Matrix4f viewMatrix = new Matrix4f();
        private final Matrix4f projectionMatrix = new Matrix4f();
        private final float[] matrixScratch = new float[16];

        int visibleSections;
        int loadedChunks;
        int compileQueueSize;
        long uploadBacklog;
        int addedChunks;
        int removedChunks;
        int particleCount;
        long gameTime;
        long mutationSequence;
        long cameraXBits;
        long cameraYBits;
        long cameraZBits;
        int cameraYawBits;
        int cameraPitchBits;
        int cameraFovBits;
        int cameraNearBits;
        int cameraFarBits;
        long viewMatrixHash;
        long projectionMatrixHash;
        boolean canonicalWorkloadReady;
        String canonicalWorkloadRejection;

        long totalTerrainDrawRecords;
        long eligibleOpaqueSolidRecords;
        long solidMissingSliceRecords;
        long solidMissingCustomIndexRecords;
        long cutoutRecords;
        long translucentRecords;

        long visibleHashA;
        long visibleHashB;
        long compatibleHashA;
        long compatibleHashB;
        long templateHashA;
        long templateHashB;
        long bufferGenerationHashA;
        long bufferGenerationHashB;

        void scan(
            Minecraft minecraft,
            Camera camera,
            int compileBacklog,
            long aggregateUploadBacklog,
            long externalMutationSequence
        ) throws ContractException {
            if (
                minecraft == null
                    || minecraft.level == null
                    || minecraft.levelRenderer == null
                    || camera == null
                    || !camera.isInitialized()
                    || compileBacklog < 0
                    || aggregateUploadBacklog < 0L
            ) {
                throw new ContractException(
                    "TERRAIN_AUDIT_OWNER_UNAVAILABLE"
                );
            }
            reset();
            compileQueueSize = compileBacklog;
            uploadBacklog = aggregateUploadBacklog;
            mutationSequence = externalMutationSequence;
            gameTime = minecraft.level.getGameTime();
            loadedChunks = minecraft.level
                .getChunkSource()
                .getLoadedChunksCount();
            ClientChunkCache chunkCache =
                minecraft.level.getChunkSource();
            addedChunks = chunkCache.addedLoadedChunks().size();
            removedChunks = chunkCache.removedLoadedChunks().size();
            particleCount = suppressDynamicParticles() ? 0 : -1;
            cameraXBits = Double.doubleToRawLongBits(
                camera.position().x()
            );
            cameraYBits = Double.doubleToRawLongBits(
                camera.position().y()
            );
            cameraZBits = Double.doubleToRawLongBits(
                camera.position().z()
            );
            cameraYawBits = Float.floatToRawIntBits(camera.yRot());
            cameraPitchBits = Float.floatToRawIntBits(camera.xRot());
            camera.getViewRotationMatrix(viewMatrix);
            var projection =
                ((Phase2a0cCameraInvoker) camera)
                    .blockframe$phase2a1Projection();
            projection.getMatrix(projectionMatrix);
            cameraFovBits = Float.floatToRawIntBits(camera.getFov());
            cameraNearBits = Float.floatToRawIntBits(
                projection.zNear()
            );
            cameraFarBits = Float.floatToRawIntBits(
                projection.zFar()
            );
            viewMatrixHash = matrixHash(viewMatrix, matrixScratch);
            projectionMatrixHash = matrixHash(
                projectionMatrix,
                matrixScratch
            );

            var visible = minecraft.levelRenderer.visibleSections();
            visibleSections = visible.size();
            SectionRenderDispatcher dispatcher =
                ((Phase2a1LevelRendererAccessor)
                        minecraft.levelRenderer)
                    .blockframe$phase2a1SectionRenderDispatcher();
            if (dispatcher == null) {
                throw new ContractException(
                    "SECTION_DISPATCHER_UNAVAILABLE"
                );
            }
            dispatcher.lock();
            try {
                for (
                    int sectionIndex = 0;
                    sectionIndex < visible.size();
                    sectionIndex++
                ) {
                    SectionRenderDispatcher.RenderSection section =
                        visible.get(sectionIndex);
                    long sectionNode = section.getSectionNode();
                    accumulateVisible(sectionNode);
                    SectionMesh mesh = section.getSectionMesh();
                    for (
                        int layerIndex = 0;
                        layerIndex < LAYERS.length;
                        layerIndex++
                    ) {
                        ChunkSectionLayer layer =
                            LAYERS[layerIndex];
                        SectionMesh.SectionDraw draw =
                            mesh.getSectionDraw(layer);
                        if (draw == null) {
                            continue;
                        }
                        Object layerBuffers =
                            ((Phase2a1SectionRenderDispatcherBuffersAccessor)
                                    dispatcher)
                                .blockframe$phase2a1ChunkUberBuffers()
                                .get(layer);
                        if (layerBuffers == null) {
                            if (layer == ChunkSectionLayer.SOLID) {
                                solidMissingSliceRecords++;
                            }
                            continue;
                        }
                        Phase2a1SectionUberBuffersAccessor buffers =
                            (Phase2a1SectionUberBuffersAccessor)
                                layerBuffers;
                        UberGpuBuffer<SectionMesh> vertexOwner =
                            buffers.blockframe$phase2a1VertexBuffer();
                        UberGpuBuffer<SectionMesh> indexOwner =
                            buffers.blockframe$phase2a1IndexBuffer();
                        TlsfAllocator.Allocation vertexAllocation =
                            vertexOwner.getAllocation(mesh);
                        if (vertexAllocation == null) {
                            if (layer == ChunkSectionLayer.SOLID) {
                                solidMissingSliceRecords++;
                            }
                            continue;
                        }
                        TlsfAllocator.Allocation indexAllocation =
                            indexOwner.getAllocation(mesh);
                        if (
                            draw.hasCustomIndexBuffer()
                                && indexAllocation == null
                        ) {
                            if (layer == ChunkSectionLayer.SOLID) {
                                solidMissingCustomIndexRecords++;
                            }
                            continue;
                        }
                        totalTerrainDrawRecords++;
                        if (layer == ChunkSectionLayer.SOLID) {
                            eligibleOpaqueSolidRecords++;
                            accumulateCompatible(
                                sectionNode,
                                draw,
                                vertexOwner,
                                vertexAllocation,
                                indexOwner,
                                indexAllocation
                            );
                        } else if (
                            layer == ChunkSectionLayer.CUTOUT
                        ) {
                            cutoutRecords++;
                        } else if (
                            layer
                                == ChunkSectionLayer.TRANSLUCENT
                        ) {
                            translucentRecords++;
                        }
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }

        private void reset() {
            visibleSections = 0;
            loadedChunks = 0;
            compileQueueSize = 0;
            uploadBacklog = 0L;
            addedChunks = 0;
            removedChunks = 0;
            particleCount = 0;
            gameTime = 0L;
            mutationSequence = 0L;
            cameraXBits = 0L;
            cameraYBits = 0L;
            cameraZBits = 0L;
            cameraYawBits = 0;
            cameraPitchBits = 0;
            cameraFovBits = 0;
            cameraNearBits = 0;
            cameraFarBits = 0;
            viewMatrixHash = 0L;
            projectionMatrixHash = 0L;
            canonicalWorkloadReady = false;
            canonicalWorkloadRejection =
                "CANONICAL_WORKLOAD_NOT_SAMPLED";
            totalTerrainDrawRecords = 0L;
            eligibleOpaqueSolidRecords = 0L;
            solidMissingSliceRecords = 0L;
            solidMissingCustomIndexRecords = 0L;
            cutoutRecords = 0L;
            translucentRecords = 0L;
            visibleHashA = 0L;
            visibleHashB = 0L;
            compatibleHashA = 0L;
            compatibleHashB = 0L;
            templateHashA = 0L;
            templateHashB = 0L;
            bufferGenerationHashA = 0L;
            bufferGenerationHashB = 0L;
        }

        private void accumulateVisible(long node) {
            long mixed = mix64(node);
            visibleHashA += mixed;
            visibleHashB ^= Long.rotateLeft(mixed, (int) node & 63);
        }

        private void accumulateCompatible(
            long node,
            SectionMesh.SectionDraw draw,
            UberGpuBuffer<SectionMesh> vertexOwner,
            TlsfAllocator.Allocation vertexAllocation,
            UberGpuBuffer<SectionMesh> indexOwner,
            TlsfAllocator.Allocation indexAllocation
        ) {
            long nodeMixed = mix64(node ^ 0x6a09e667f3bcc909L);
            compatibleHashA += nodeMixed;
            compatibleHashB ^= Long.rotateLeft(
                nodeMixed,
                (int) (node >>> 7) & 63
            );
            long template =
                ((long) draw.indexCount() << 32)
                    ^ ((long) draw.indexType().bytes << 8)
                    ^ (draw.hasCustomIndexBuffer() ? 1L : 0L);
            long templateMixed = mix64(node ^ template);
            templateHashA += templateMixed;
            templateHashB ^= Long.rotateLeft(
                templateMixed,
                draw.indexCount() & 63
            );
            long generation =
                ((long) System.identityHashCode(
                        vertexOwner.getGpuBuffer(vertexAllocation)
                    )
                        << 32)
                    ^ (
                        indexAllocation == null
                            ? 0L
                            : System.identityHashCode(
                                indexOwner.getGpuBuffer(
                                    indexAllocation
                                )
                            )
                    );
            long generationMixed = mix64(node ^ generation);
            bufferGenerationHashA += generationMixed;
            bufferGenerationHashB ^= Long.rotateLeft(
                generationMixed,
                (int) node & 63
            );
        }

        boolean sameStaticIdentity(TerrainFrameAudit other) {
            return other != null
                && visibleSections == other.visibleSections
                && loadedChunks == other.loadedChunks
                && totalTerrainDrawRecords
                    == other.totalTerrainDrawRecords
                && eligibleOpaqueSolidRecords
                    == other.eligibleOpaqueSolidRecords
                && cutoutRecords == other.cutoutRecords
                && translucentRecords == other.translucentRecords
                && visibleHashA == other.visibleHashA
                && visibleHashB == other.visibleHashB
                && compatibleHashA == other.compatibleHashA
                && compatibleHashB == other.compatibleHashB
                && templateHashA == other.templateHashA
                && templateHashB == other.templateHashB
                && bufferGenerationHashA
                    == other.bufferGenerationHashA
                && bufferGenerationHashB
                    == other.bufferGenerationHashB
                && cameraXBits == other.cameraXBits
                && cameraYBits == other.cameraYBits
                && cameraZBits == other.cameraZBits
                && cameraYawBits == other.cameraYawBits
                && cameraPitchBits == other.cameraPitchBits
                && cameraFovBits == other.cameraFovBits
                && cameraNearBits == other.cameraNearBits
                && cameraFarBits == other.cameraFarBits
                && viewMatrixHash == other.viewMatrixHash
                && projectionMatrixHash == other.projectionMatrixHash
                && canonicalWorkloadReady
                    == other.canonicalWorkloadReady;
        }

        void copyFrom(TerrainFrameAudit source) {
            visibleSections = source.visibleSections;
            loadedChunks = source.loadedChunks;
            compileQueueSize = source.compileQueueSize;
            uploadBacklog = source.uploadBacklog;
            addedChunks = source.addedChunks;
            removedChunks = source.removedChunks;
            particleCount = source.particleCount;
            gameTime = source.gameTime;
            mutationSequence = source.mutationSequence;
            cameraXBits = source.cameraXBits;
            cameraYBits = source.cameraYBits;
            cameraZBits = source.cameraZBits;
            cameraYawBits = source.cameraYawBits;
            cameraPitchBits = source.cameraPitchBits;
            cameraFovBits = source.cameraFovBits;
            cameraNearBits = source.cameraNearBits;
            cameraFarBits = source.cameraFarBits;
            viewMatrixHash = source.viewMatrixHash;
            projectionMatrixHash = source.projectionMatrixHash;
            canonicalWorkloadReady = source.canonicalWorkloadReady;
            canonicalWorkloadRejection =
                source.canonicalWorkloadRejection;
            totalTerrainDrawRecords =
                source.totalTerrainDrawRecords;
            eligibleOpaqueSolidRecords =
                source.eligibleOpaqueSolidRecords;
            solidMissingSliceRecords =
                source.solidMissingSliceRecords;
            solidMissingCustomIndexRecords =
                source.solidMissingCustomIndexRecords;
            cutoutRecords = source.cutoutRecords;
            translucentRecords = source.translucentRecords;
            visibleHashA = source.visibleHashA;
            visibleHashB = source.visibleHashB;
            compatibleHashA = source.compatibleHashA;
            compatibleHashB = source.compatibleHashB;
            templateHashA = source.templateHashA;
            templateHashB = source.templateHashB;
            bufferGenerationHashA =
                source.bufferGenerationHashA;
            bufferGenerationHashB =
                source.bufferGenerationHashB;
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("visibleSections", visibleSections);
            root.addProperty("loadedChunks", loadedChunks);
            root.addProperty(
                "totalTerrainDrawRecords",
                totalTerrainDrawRecords
            );
            root.addProperty(
                "eligibleOpaqueSolidRecords",
                eligibleOpaqueSolidRecords
            );
            root.addProperty(
                "solidMissingSliceRecords",
                solidMissingSliceRecords
            );
            root.addProperty(
                "solidMissingCustomIndexRecords",
                solidMissingCustomIndexRecords
            );
            root.addProperty("cutoutRecords", cutoutRecords);
            root.addProperty(
                "translucentRecords",
                translucentRecords
            );
            root.addProperty(
                "visibleSectionHash128",
                hash128(visibleHashA, visibleHashB)
            );
            root.addProperty(
                "compatibleSectionHash128",
                hash128(compatibleHashA, compatibleHashB)
            );
            root.addProperty(
                "drawTemplateHash128",
                hash128(templateHashA, templateHashB)
            );
            root.addProperty(
                "bufferGenerationHash128WithinProcess",
                hash128(
                    bufferGenerationHashA,
                    bufferGenerationHashB
                )
            );
            root.addProperty(
                "viewMatrixHash64",
                String.format(Locale.ROOT, "%016x", viewMatrixHash)
            );
            root.addProperty(
                "projectionMatrixHash64",
                String.format(
                    Locale.ROOT,
                    "%016x",
                    projectionMatrixHash
                )
            );
            root.addProperty(
                "effectiveFovRawBits",
                Integer.toUnsignedLong(cameraFovBits)
            );
            root.addProperty(
                "nearPlaneRawBits",
                Integer.toUnsignedLong(cameraNearBits)
            );
            root.addProperty(
                "farPlaneRawBits",
                Integer.toUnsignedLong(cameraFarBits)
            );
            root.addProperty(
                "canonicalWorkloadReady",
                canonicalWorkloadReady
            );
            return root;
        }

        private static long matrixHash(
            Matrix4f matrix,
            float[] scratch
        ) {
            matrix.get(scratch);
            long hash = 0xcbf29ce484222325L;
            for (float value : scratch) {
                hash ^= Integer.toUnsignedLong(
                    Float.floatToRawIntBits(value)
                );
                hash *= 0x100000001b3L;
            }
            return hash;
        }

        private static long mix64(long value) {
            value ^= value >>> 30;
            value *= 0xbf58476d1ce4e5b9L;
            value ^= value >>> 27;
            value *= 0x94d049bb133111ebL;
            return value ^ (value >>> 31);
        }

        private static String hash128(long first, long second) {
            return String.format(
                Locale.ROOT,
                "%016x%016x",
                first,
                second
            );
        }
    }

    static final class DeterministicGate {
        private final boolean enabled;
        private final int requiredFrames;
        private final Path readyPath;
        private final Path armPath;
        private final String runId;
        private final TerrainFrameAudit stable =
            new TerrainFrameAudit();

        private int consecutiveFrames;
        private boolean hasStable;
        private boolean quiescent;
        private boolean readyPublished;
        private boolean armObserved;
        private boolean invalidatedAfterReady;
        private long acceptedMutationSequence = -1L;
        private long lastGameTime = Long.MIN_VALUE;
        private String lastResetReason = "NOT_STARTED";
        private String lastMutationKind = "NONE";
        private String readySha256;

        DeterministicGate(
            boolean enabled,
            int requiredFrames,
            Path readyPath,
            Path armPath,
            String runId
        ) {
            this.enabled = enabled;
            this.requiredFrames = requiredFrames;
            this.readyPath = readyPath;
            this.armPath = armPath;
            this.runId = runId;
        }

        void accept(TerrainFrameAudit frame) {
            if (!enabled || invalidatedAfterReady) {
                return;
            }
            String rejection = rejectionReason(frame);
            if (rejection != null) {
                reset(rejection);
                return;
            }
            if (
                hasStable
                    && (
                        !frame.sameStaticIdentity(stable)
                            || frame.gameTime != lastGameTime
                            || frame.mutationSequence
                                != acceptedMutationSequence
                    )
            ) {
                reset("STATIC_IDENTITY_CHANGED");
            }
            if (!hasStable) {
                stable.copyFrom(frame);
                hasStable = true;
                acceptedMutationSequence = frame.mutationSequence;
                lastGameTime = frame.gameTime;
            }
            consecutiveFrames++;
            if (consecutiveFrames >= requiredFrames) {
                quiescent = true;
                lastResetReason = "NONE";
            }
        }

        private String rejectionReason(TerrainFrameAudit frame) {
            if (!frame.canonicalWorkloadReady) {
                return frame.canonicalWorkloadRejection == null
                    ? "CANONICAL_VISIBLE_WORKLOAD_NOT_READY"
                    : frame.canonicalWorkloadRejection;
            }
            if (frame.compileQueueSize != 0) {
                return "MESH_BUILD_BACKLOG_NONZERO";
            }
            if (frame.uploadBacklog != 0L) {
                return "UPLOAD_BACKLOG_NONZERO";
            }
            if (frame.addedChunks != 0 || frame.removedChunks != 0) {
                return "CHUNK_LOAD_BACKLOG_NONZERO";
            }
            if (frame.particleCount != 0) {
                return "DYNAMIC_PARTICLE_COUNT_NONZERO";
            }
            if (frame.eligibleOpaqueSolidRecords <= 0L) {
                return "NO_ELIGIBLE_OPAQUE_SOLID_RECORDS";
            }
            if (
                frame.solidMissingSliceRecords != 0L
                    || frame.solidMissingCustomIndexRecords != 0L
            ) {
                return "ELIGIBLE_SOLID_TECHNICAL_EXCLUSION_PRESENT";
            }
            return null;
        }

        private void reset(String reason) {
            if (readyPublished || quiescent) {
                invalidatedAfterReady = true;
            }
            consecutiveFrames = 0;
            hasStable = false;
            quiescent = false;
            lastResetReason = reason;
            acceptedMutationSequence = -1L;
            lastGameTime = Long.MIN_VALUE;
        }

        void observeExternalMutation(String kind) {
            lastMutationKind = kind;
            reset(kind);
        }

        boolean quiescent() {
            return !enabled || quiescent;
        }

        boolean measureReady() {
            return !enabled
                || (
                    quiescent
                        && readyPublished
                        && armObserved
                        && !invalidatedAfterReady
                );
        }

        void publishReady(
            Receipt receipt,
            String captureArtifactSha256,
            WorkloadSnapshot exactSnapshot
        ) throws IOException, ContractException, NoSuchAlgorithmException {
            if (!enabled || readyPublished) {
                return;
            }
            if (!quiescent || invalidatedAfterReady) {
                throw new ContractException(
                    "QUIESCENCE_PUBLICATION_WITHOUT_STABLE_GATE"
                );
            }
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", 1);
            root.addProperty("status", "QUIESCENCE_READY");
            root.addProperty("runId", runId);
            root.addProperty(
                "captureArtifactSha256",
                captureArtifactSha256
            );
            root.addProperty("requiredFrames", requiredFrames);
            root.addProperty(
                "consecutiveIdenticalFrames",
                consecutiveFrames
            );
            root.addProperty(
                "staticRendererGate",
                receipt.staticRendererGate
            );
            root.addProperty(
                "particleCondition",
                "DEV_CAPTURE_SUPPRESSED_BYTE_IDENTICAL_BOTH_PROFILES"
            );
            root.addProperty(
                "worldMutationSequence",
                acceptedMutationSequence
            );
            root.addProperty(
                "lastMutationKind",
                lastMutationKind
            );
            root.add(
                "allocationFreeFrameIdentity",
                stable.toJson()
            );
            root.add("exactBoundaryIdentity", exactSnapshot.toJson());
            publishHandshakeAtomically(readyPath, root);
            readySha256 = sha256(readyPath);
            readyPublished = true;
        }

        void pollMeasureArm()
            throws IOException, ContractException,
                NoSuchAlgorithmException {
            if (
                !enabled
                    || armObserved
                    || !readyPublished
                    || !Files.isRegularFile(armPath)
            ) {
                return;
            }
            byte[] bytes = Files.readAllBytes(armPath);
            JsonElement parsed = JsonParser.parseString(
                new String(bytes, StandardCharsets.UTF_8)
            );
            if (!parsed.isJsonObject()) {
                throw new ContractException(
                    "MEASURE_ARM_NOT_OBJECT"
                );
            }
            JsonObject arm = parsed.getAsJsonObject();
            if (
                requireIntValue(arm, "schemaVersion") != 1
                    || !"MEASURE_ARMED".equals(
                        requireString(arm, "status")
                    )
                    || !runId.equals(
                        requireString(arm, "runId")
                    )
                    || !readySha256.equals(
                        Receipt.requireHash(
                            arm,
                            "quiescenceReadySha256"
                        )
                    )
            ) {
                throw new ContractException(
                    "MEASURE_ARM_CONTRACT_MISMATCH"
                );
            }
            if (invalidatedAfterReady) {
                throw new ContractException(
                    "QUIESCENCE_INVALIDATED_BEFORE_MEASURE"
                );
            }
            armObserved = true;
        }

        void verifyMeasureFrame(TerrainFrameAudit frame)
            throws ContractException {
            if (!enabled) {
                return;
            }
            String rejection = rejectionReason(frame);
            if (rejection != null) {
                throw new ContractException(
                    "STATIC_GATE_CHANGED_DURING_MEASURE_"
                        + rejection
                );
            }
            if (frame.mutationSequence != acceptedMutationSequence) {
                throw new ContractException(
                    "STATIC_GATE_CHANGED_DURING_MEASURE_EXTERNAL_MUTATION_"
                        + lastMutationKind
                );
            }
            if (frame.gameTime != lastGameTime) {
                throw new ContractException(
                    "STATIC_GATE_CHANGED_DURING_MEASURE_GAME_TIME"
                );
            }
            if (!frame.sameStaticIdentity(stable)) {
                throw new ContractException(
                    "STATIC_GATE_CHANGED_DURING_MEASURE_TERRAIN_IDENTITY"
                );
            }
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty(
                "status",
                !enabled
                    ? "NOT_APPLICABLE"
                    : measureReady()
                        ? "PASSED"
                        : "FAILED"
            );
            root.addProperty("enabled", enabled);
            root.addProperty("requiredFrames", requiredFrames);
            root.addProperty(
                "consecutiveIdenticalFrames",
                consecutiveFrames
            );
            root.addProperty("quiescent", quiescent);
            root.addProperty("readyPublished", readyPublished);
            root.addProperty("measureArmObserved", armObserved);
            root.addProperty(
                "invalidatedAfterReady",
                invalidatedAfterReady
            );
            root.addProperty("lastResetReason", lastResetReason);
            root.addProperty("lastMutationKind", lastMutationKind);
            if (readySha256 != null) {
                root.addProperty(
                    "quiescenceReadySha256",
                    readySha256
                );
            }
            return root;
        }
    }

    static final class WorkloadWindow {
        private final long[] visibleSections;
        private final long[] loadedChunks;
        private final long[] compileQueueSize;
        private final long[] terrainDrawSubmissions;
        private final long[] terrainDrawRecords;
        private final long[] terrainUploadCalls;
        private final long[] terrainUploadNanos;
        private final long[] preparedTerrainDrawRecords;
        private final long[] eligibleOpaqueSolidRecords;
        private final long[] solidTechnicalExclusions;
        private final long[] preparedCutoutRecords;
        private final long[] preparedTranslucentRecords;
        private final long[][] backendRecords;
        private final long[][] backendCpuNanos;
        private final long[] indirectCalls;
        private final long[] indirectMaximumCommandCapacity;
        private final long[] indirectCpuNanos;

        private WorkloadSnapshot start;
        private WorkloadSnapshot end;
        private int sampleCount;
        private boolean overflow;
        private long currentDrawSubmissions;
        private long currentDrawRecords;
        private long currentUploadCalls;
        private long currentUploadNanos;
        private final long[] currentBackendRecords =
            new long[DRAW_CATEGORY_COUNT];
        private final long[] currentBackendCpuNanos =
            new long[DRAW_CATEGORY_COUNT];
        private long currentIndirectCalls;
        private long currentIndirectMaximumCommandCapacity;
        private long currentIndirectCpuNanos;

        WorkloadWindow(int maximumSamples) {
            if (maximumSamples <= 0) {
                throw new IllegalArgumentException(
                    "positive workload sample bound required"
                );
            }
            visibleSections = new long[maximumSamples];
            loadedChunks = new long[maximumSamples];
            compileQueueSize = new long[maximumSamples];
            terrainDrawSubmissions = new long[maximumSamples];
            terrainDrawRecords = new long[maximumSamples];
            terrainUploadCalls = new long[maximumSamples];
            terrainUploadNanos = new long[maximumSamples];
            preparedTerrainDrawRecords =
                new long[maximumSamples];
            eligibleOpaqueSolidRecords =
                new long[maximumSamples];
            solidTechnicalExclusions =
                new long[maximumSamples];
            preparedCutoutRecords = new long[maximumSamples];
            preparedTranslucentRecords =
                new long[maximumSamples];
            backendRecords =
                new long[DRAW_CATEGORY_COUNT][maximumSamples];
            backendCpuNanos =
                new long[DRAW_CATEGORY_COUNT][maximumSamples];
            indirectCalls = new long[maximumSamples];
            indirectMaximumCommandCapacity =
                new long[maximumSamples];
            indirectCpuNanos = new long[maximumSamples];
        }

        void begin(WorkloadSnapshot snapshot) {
            if (start != null || end != null || sampleCount != 0) {
                throw new IllegalStateException(
                    "workload window already started"
                );
            }
            start = snapshot;
            resetCurrentFrame();
        }

        void recordTerrainDrawSubmission(
            int drawRecords,
            boolean measuring
        ) {
            if (!measuring || start == null || end != null) {
                return;
            }
            if (drawRecords < 0) {
                overflow = true;
                return;
            }
            currentDrawSubmissions++;
            currentDrawRecords = saturatedAdd(
                currentDrawRecords,
                drawRecords
            );
        }

        void recordTerrainUpload(long elapsedNanos, boolean measuring) {
            if (!measuring || start == null || end != null) {
                return;
            }
            if (elapsedNanos < 0L) {
                overflow = true;
                return;
            }
            currentUploadCalls++;
            currentUploadNanos = saturatedAdd(
                currentUploadNanos,
                elapsedNanos
            );
        }

        void recordBackendDraw(
            int category,
            int records,
            long cpuNanos,
            boolean measuring
        ) {
            if (!measuring || start == null || end != null) {
                return;
            }
            if (
                category < 0
                    || category >= DRAW_CATEGORY_COUNT
                    || records < 0
                    || cpuNanos < 0L
            ) {
                overflow = true;
                return;
            }
            currentBackendRecords[category] = saturatedAdd(
                currentBackendRecords[category],
                records
            );
            currentBackendCpuNanos[category] = saturatedAdd(
                currentBackendCpuNanos[category],
                cpuNanos
            );
        }

        void recordOpaqueSolidIndirectCall(
            int maximumDrawCount,
            boolean measuring
        ) {
            if (!measuring || start == null || end != null) {
                return;
            }
            if (maximumDrawCount <= 0) {
                overflow = true;
                return;
            }
            currentIndirectCalls++;
            currentIndirectMaximumCommandCapacity = saturatedAdd(
                currentIndirectMaximumCommandCapacity,
                maximumDrawCount
            );
        }

        void recordOpaqueSolidIndirectCpuNanos(
            long cpuNanos,
            boolean measuring
        ) {
            if (!measuring || start == null || end != null) {
                return;
            }
            if (cpuNanos < 0L) {
                overflow = true;
                return;
            }
            currentIndirectCpuNanos = saturatedAdd(
                currentIndirectCpuNanos,
                cpuNanos
            );
        }

        void captureFrame(TerrainFrameAudit audit) {
            if (
                start == null
                    || end != null
                    || audit.visibleSections < 0
                    || audit.loadedChunks < 0
                    || audit.compileQueueSize < 0
            ) {
                overflow = true;
                return;
            }
            if (sampleCount >= visibleSections.length) {
                overflow = true;
                resetCurrentFrame();
                return;
            }
            int index = sampleCount++;
            visibleSections[index] = audit.visibleSections;
            loadedChunks[index] = audit.loadedChunks;
            compileQueueSize[index] = audit.compileQueueSize;
            terrainDrawSubmissions[index] = currentDrawSubmissions;
            terrainDrawRecords[index] = currentDrawRecords;
            terrainUploadCalls[index] = currentUploadCalls;
            terrainUploadNanos[index] = currentUploadNanos;
            preparedTerrainDrawRecords[index] =
                audit.totalTerrainDrawRecords;
            eligibleOpaqueSolidRecords[index] =
                audit.eligibleOpaqueSolidRecords;
            solidTechnicalExclusions[index] = saturatedAdd(
                audit.solidMissingSliceRecords,
                audit.solidMissingCustomIndexRecords
            );
            preparedCutoutRecords[index] = audit.cutoutRecords;
            preparedTranslucentRecords[index] =
                audit.translucentRecords;
            for (
                int category = 0;
                category < DRAW_CATEGORY_COUNT;
                category++
            ) {
                backendRecords[category][index] =
                    currentBackendRecords[category];
                backendCpuNanos[category][index] =
                    currentBackendCpuNanos[category];
            }
            indirectCalls[index] = currentIndirectCalls;
            indirectMaximumCommandCapacity[index] =
                currentIndirectMaximumCommandCapacity;
            indirectCpuNanos[index] = currentIndirectCpuNanos;
            resetCurrentFrame();
        }

        void end(WorkloadSnapshot snapshot) {
            if (start == null || end != null) {
                throw new IllegalStateException(
                    "workload window not active"
                );
            }
            end = snapshot;
        }

        boolean validForPublication() {
            return start != null
                && end != null
                && sampleCount > 0
                && !overflow;
        }

        int sampleCount() {
            return sampleCount;
        }

        JsonObject toJson() {
            if (!validForPublication()) {
                throw new IllegalStateException(
                    "invalid workload publication"
                );
            }
            JsonObject root = new JsonObject();
            root.addProperty("status", "AVAILABLE");
            root.addProperty(
                "semantics",
                "MOJANG_OWNER_WORKLOAD_IDENTITY_NOT_RENDERER_SPEEDUP"
            );
            root.addProperty("sampleCount", sampleCount);
            root.addProperty("sampleOverflow", overflow);
            root.add("start", start.toJson());
            root.add("end", end.toJson());

            JsonObject deltas = new JsonObject();
            deltas.addProperty(
                "gameTime",
                end.gameTime() - start.gameTime()
            );
            deltas.addProperty(
                "dayTime",
                end.dayTime() - start.dayTime()
            );
            deltas.addProperty(
                "loadedChunks",
                end.loadedChunks() - start.loadedChunks()
            );
            deltas.addProperty(
                "entitiesForRendering",
                end.entitiesForRendering()
                    - start.entitiesForRendering()
            );
            deltas.addProperty(
                "compileQueueSize",
                end.compileQueueSize() - start.compileQueueSize()
            );
            root.add("deltas", deltas);

            JsonObject frame = new JsonObject();
            frame.add(
                "visibleSections",
                distribution(
                    visibleSections,
                    sampleCount,
                    "sections"
                )
            );
            frame.add(
                "loadedChunks",
                distribution(loadedChunks, sampleCount, "chunks")
            );
            frame.add(
                "compileQueueSize",
                distribution(
                    compileQueueSize,
                    sampleCount,
                    "jobs"
                )
            );
            frame.add(
                "terrainDrawSubmissions",
                distribution(
                    terrainDrawSubmissions,
                    sampleCount,
                    "submissions"
                )
            );
            frame.add(
                "terrainDrawRecords",
                distribution(
                    terrainDrawRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            frame.add(
                "terrainUploadCalls",
                distribution(
                    terrainUploadCalls,
                    sampleCount,
                    "calls"
                )
            );
            frame.add(
                "terrainUploadNanos",
                distribution(
                    terrainUploadNanos,
                    sampleCount,
                    "nanoseconds"
                )
            );
            frame.add(
                "preparedTotalTerrainDrawRecords",
                distribution(
                    preparedTerrainDrawRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            frame.add(
                "eligibleOpaqueSolidRecords",
                distribution(
                    eligibleOpaqueSolidRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            frame.add(
                "eligibleSolidTechnicalExclusions",
                distribution(
                    solidTechnicalExclusions,
                    sampleCount,
                    "draw_records"
                )
            );
            frame.add(
                "preparedCutoutRecords",
                distribution(
                    preparedCutoutRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            frame.add(
                "preparedTranslucentRecords",
                distribution(
                    preparedTranslucentRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            JsonObject backend = new JsonObject();
            for (
                int category = 0;
                category < DRAW_CATEGORY_COUNT;
                category++
            ) {
                JsonObject categoryResult = new JsonObject();
                categoryResult.add(
                    "records",
                    distribution(
                        backendRecords[category],
                        sampleCount,
                        "draw_records"
                    )
                );
                categoryResult.add(
                    "cpuNanos",
                    distribution(
                        backendCpuNanos[category],
                        sampleCount,
                        "nanoseconds"
                    )
                );
                categoryResult.addProperty(
                    "classification",
                    category == DRAW_ENTITY_ABI
                        ? "PIPELINE_ABI_CANNOT_SEPARATE_ENTITY_FROM_BLOCK_ENTITY_OWNER"
                        : "EXACT_PIPELINE_OR_EXACT_PATH_ALLOWLIST"
                );
                backend.add(
                    DRAW_CATEGORY_NAMES[category],
                    categoryResult
                );
            }
            frame.add("vulkanBackendByCategory", backend);
            JsonObject indirect = new JsonObject();
            indirect.add(
                "callsPerFrame",
                distribution(
                    indirectCalls,
                    sampleCount,
                    "calls"
                )
            );
            indirect.add(
                "maximumCommandCapacityPerFrame",
                distribution(
                    indirectMaximumCommandCapacity,
                    sampleCount,
                    "commands_upper_bound"
                )
            );
            indirect.add(
                "cpuNanos",
                distribution(
                    indirectCpuNanos,
                    sampleCount,
                    "nanoseconds"
                )
            );
            indirect.add(
                "executedCommandsPerFrame",
                typedUnavailable(
                    "GPU_COUNT_BUFFER_HAS_NO_NONSTALLING_READBACK_CONTRACT"
                )
            );
            frame.add("opaqueSolidIndirect", indirect);
            root.add("perRenderedFrame", frame);

            JsonObject hotpath = new JsonObject();
            hotpath.addProperty("fileIo", 0);
            hotpath.addProperty("manifestReads", 0);
            hotpath.addProperty("threadScans", 0);
            hotpath.addProperty(
                "storage",
                "PREALLOCATED_PARALLEL_PRIMITIVE_ARRAYS"
            );
            hotpath.addProperty(
                "entityAndParticleEnumeration",
                "BOUNDARIES_ONLY_OUTSIDE_MEASURE"
            );
            hotpath.addProperty(
                "perDrawWork",
                "NANOTIME_AND_PREALLOCATED_PRIMITIVE_COUNTERS"
            );
            hotpath.addProperty(
                "perUploadWork",
                "TWO_NANOTIME_READS_AND_PRIMITIVE_COUNTERS"
            );
            hotpath.addProperty(
                "gpuSubpassTimestamps",
                "NOT_AVAILABLE_NO_SAFE_NONOWNING_GENERATION_BOUND_QUERY_POOL"
            );
            root.add("captureHotpath", hotpath);
            return root;
        }

        JsonObject drawRecordAudit(String profileId) {
            if (!validForPublication()) {
                throw new IllegalStateException(
                    "invalid draw-record audit publication"
                );
            }
            boolean blockframeOn =
                BLOCKFRAME_ON_PROFILE.equals(profileId);
            long[] suppressed = new long[sampleCount];
            long[] otherKnown = new long[sampleCount];
            long[] totalInstrumented = new long[sampleCount];
            long[] otherInstrumentedUnknown = new long[sampleCount];
            for (int index = 0; index < sampleCount; index++) {
                long residual =
                    backendRecords[DRAW_ELIGIBLE_SOLID][index];
                suppressed[index] = blockframeOn
                    ? Math.max(
                        0L,
                        eligibleOpaqueSolidRecords[index] - residual
                    )
                    : 0L;
                long known = 0L;
                long totalBackend =
                    backendRecords[DRAW_ELIGIBLE_SOLID][index];
                for (
                    int category = 1;
                    category < DRAW_CATEGORY_COUNT;
                    category++
                ) {
                    totalBackend = saturatedAdd(
                        totalBackend,
                        backendRecords[category][index]
                    );
                    if (
                        category >= DRAW_PARTICLE
                            && category <= DRAW_SPECIAL_SHADER
                    ) {
                        known = saturatedAdd(
                            known,
                            backendRecords[category][index]
                        );
                    }
                }
                otherKnown[index] = known;
                totalInstrumented[index] = totalBackend;
                otherInstrumentedUnknown[index] =
                    backendRecords[DRAW_OTHER][index];
            }

            JsonObject root = new JsonObject();
            root.addProperty(
                "semantics",
                "CANONICAL_PROFILE_LOCAL_EXACT_MEASURE_WINDOW"
            );
            root.add(
                "baselineTerrainDrawRecords",
                blockframeOn
                    ? typedUnavailable(
                        "ONLY_DEFINED_BY_PAIRED_MOJANG_PROFILE"
                    )
                    : distribution(
                        preparedTerrainDrawRecords,
                        sampleCount,
                        "draw_records"
                    )
            );
            root.add(
                "eligibleOpaqueSolidTerrainRecords",
                distribution(
                    eligibleOpaqueSolidRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "suppressedEligibleOpaqueSolidRecords",
                blockframeOn
                    ? distribution(
                        suppressed,
                        sampleCount,
                        "draw_records"
                    )
                    : typedUnavailable(
                        "BLOCKFRAME_GPU_SCENE_NOT_LOADED"
                    )
            );
            root.add(
                "residualEligibleOpaqueSolidRecords",
                distribution(
                    backendRecords[DRAW_ELIGIBLE_SOLID],
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "cutoutTerrainRecords",
                distribution(
                    preparedCutoutRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "translucentTerrainRecords",
                typedUnavailable(
                    "MOJANG_TRANSLUCENT_PIPELINE_DOES_NOT_RETAIN_FLUID_OWNER"
                )
            );
            root.add(
                "fluidTerrainRecords",
                typedUnavailable(
                    "MOJANG_TRANSLUCENT_PIPELINE_DOES_NOT_RETAIN_FLUID_OWNER"
                )
            );
            root.add(
                "translucentOrFluidTerrainRecords",
                distribution(
                    preparedTranslucentRecords,
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "entityAbiRecords",
                distribution(
                    backendRecords[DRAW_ENTITY_ABI],
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "otherKnownRecords",
                distribution(
                    otherKnown,
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "totalInstrumentedRecords",
                distribution(
                    totalInstrumented,
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "otherInstrumentedUnknownRecords",
                distribution(
                    otherInstrumentedUnknown,
                    sampleCount,
                    "draw_records"
                )
            );
            root.add(
                "unmeasuredRendererRecords",
                typedUnavailable(
                    "NO_GLOBAL_ALL_RENDERER_RECORD_OWNER_EXISTS"
                )
            );
            JsonObject byReason = new JsonObject();
            for (
                int category = 1;
                category < DRAW_CATEGORY_COUNT;
                category++
            ) {
                JsonObject reason = distribution(
                    backendRecords[category],
                    sampleCount,
                    "draw_records"
                );
                reason.add(
                    "cpuNanos",
                    distribution(
                        backendCpuNanos[category],
                        sampleCount,
                        "nanoseconds"
                    )
                );
                reason.addProperty(
                    "exactExclusionReason",
                    switch (category) {
                        case DRAW_CUTOUT ->
                            "OUTSIDE_OPAQUE_SOLID_SLICE_CUTOUT";
                        case DRAW_TRANSLUCENT ->
                            "OUTSIDE_OPAQUE_SOLID_SLICE_TRANSLUCENT_OR_FLUID";
                        case DRAW_ENTITY_ABI ->
                            "OUTSIDE_TERRAIN_OWNER_ENTITY_ABI_PIPELINE";
                        case DRAW_PARTICLE ->
                            "OUTSIDE_TERRAIN_OWNER_PARTICLE_PIPELINE";
                        case DRAW_OUTLINE ->
                            "OUTSIDE_TERRAIN_OWNER_OUTLINE_PIPELINE";
                        case DRAW_DEBUG ->
                            "OUTSIDE_TERRAIN_OWNER_DEBUG_PIPELINE";
                        case DRAW_UI ->
                            "OUTSIDE_WORLD_TERRAIN_OWNER_UI_PIPELINE";
                        case DRAW_STATIC_DECORATION ->
                            "OUTSIDE_SECTION_SOLID_OWNER_STATIC_DECORATION_PIPELINE";
                        case DRAW_SPECIAL_SHADER ->
                            "OUTSIDE_ALLOWLIST_SPECIAL_SHADER_PIPELINE";
                        default ->
                            "OUTSIDE_ALLOWLIST_UNKNOWN_MOJANG_OR_MOD_PIPELINE";
                    }
                );
                addClassificationMetadata(reason, category);
                byReason.add(
                    DRAW_CATEGORY_NAMES[category],
                    reason
                );
            }
            root.add("mojangOnlyByExactReason", byReason);
            JsonObject eligibleContract = new JsonObject();
            eligibleContract.addProperty(
                "renderLayer",
                "SOLID"
            );
            eligibleContract.addProperty(
                "pipeline",
                "minecraft:pipeline/solid_terrain"
            );
            eligibleContract.addProperty(
                "shaderAbi",
                "VANILLA_TERRAIN_VSH_FSH_EXACT_HASH_ALLOWLIST"
            );
            eligibleContract.addProperty(
                "material",
                "minecraft:block-atlas:solid"
            );
            eligibleContract.addProperty(
                "indexFormat",
                "PER_RECORD_SHORT_OR_INT_AND_CUSTOM_OR_SEQUENTIAL"
            );
            eligibleContract.addProperty(
                "bufferGeneration",
                "WITHIN_PROCESS_IDENTITY_HASHED_IN_DETERMINISTIC_GATE"
            );
            eligibleContract.addProperty(
                "sectionType",
                "MOJANG_CHUNK_RENDER_SECTION"
            );
            root.add(
                "eligibleOpaqueSolidContract",
                eligibleContract
            );
            root.add(
                "indirectCallsPerFrame",
                distribution(
                    indirectCalls,
                    sampleCount,
                    "calls"
                )
            );
            root.add(
                "activeBucketsPerFrame",
                distribution(
                    indirectCalls,
                    sampleCount,
                    "active_buckets_inferred_one_call_per_bucket"
                )
            );
            JsonObject totalCalls = distribution(
                indirectCalls,
                sampleCount,
                "calls"
            );
            root.addProperty(
                "indirectCallsTotal",
                totalCalls.get("total").getAsLong()
            );
            root.add(
                "executedIndirectCommandsPerFrame",
                typedUnavailable(
                    "GPU_COUNT_BUFFER_HAS_NO_NONSTALLING_READBACK_CONTRACT"
                )
            );
            root.add(
                "executedIndirectCommandsPerBucket",
                typedUnavailable(
                    "GPU_COUNT_BUFFER_HAS_NO_NONSTALLING_READBACK_CONTRACT"
                )
            );
            return root;
        }

        private static void addClassificationMetadata(
            JsonObject target,
            int category
        ) {
            switch (category) {
                case DRAW_CUTOUT -> {
                    target.addProperty("renderLayer", "CUTOUT");
                    target.addProperty(
                        "pipeline",
                        "minecraft:pipeline/cutout_terrain"
                    );
                    target.addProperty(
                        "shaderAbi",
                        "VANILLA_TERRAIN_ALPHA_CUTOUT"
                    );
                    target.addProperty(
                        "material",
                        "minecraft:block-atlas:cutout"
                    );
                    target.addProperty(
                        "sectionType",
                        "MOJANG_CHUNK_RENDER_SECTION"
                    );
                }
                case DRAW_TRANSLUCENT -> {
                    target.addProperty(
                        "renderLayer",
                        "TRANSLUCENT"
                    );
                    target.addProperty(
                        "pipeline",
                        "minecraft:pipeline/translucent_terrain"
                    );
                    target.addProperty(
                        "shaderAbi",
                        "VANILLA_TERRAIN_TRANSLUCENT"
                    );
                    target.addProperty(
                        "material",
                        "BLOCK_ATLAS_TRANSLUCENT_AND_FLUID_NOT_SEPARABLE_AT_PIPELINE"
                    );
                    target.addProperty(
                        "sectionType",
                        "MOJANG_CHUNK_RENDER_SECTION"
                    );
                }
                case DRAW_ENTITY_ABI -> {
                    target.addProperty(
                        "renderLayer",
                        "ENTITY_ABI_FAMILY"
                    );
                    target.addProperty(
                        "pipeline",
                        "EXACT_ENTITY_ITEM_ARMOR_BEACON_BANNER_PATH_ALLOWLIST_FAMILY"
                    );
                    target.addProperty(
                        "shaderAbi",
                        "ENTITY_OR_BLOCK_ENTITY_SUBMIT_OWNER_NOT_RETAINED_BY_VULKAN_BACKEND"
                    );
                    target.addProperty(
                        "material",
                        "PIPELINE_SPECIFIC"
                    );
                    target.addProperty(
                        "sectionType",
                        "NOT_A_TERRAIN_SECTION"
                    );
                }
                case DRAW_PARTICLE ->
                    addNonTerrainMetadata(
                        target,
                        "PARTICLE_OR_WEATHER",
                        "pipeline/*particle*|pipeline/*weather*"
                    );
                case DRAW_OUTLINE ->
                    addNonTerrainMetadata(
                        target,
                        "OUTLINE",
                        "pipeline/*outline*"
                    );
                case DRAW_DEBUG ->
                    addNonTerrainMetadata(
                        target,
                        "DEBUG",
                        "pipeline/debug*|pipeline/lines*|pipeline/wireframe"
                    );
                case DRAW_UI ->
                    addNonTerrainMetadata(
                        target,
                        "UI",
                        "pipeline/gui*|screen|crosshair|vignette"
                    );
                case DRAW_STATIC_DECORATION ->
                    addNonTerrainMetadata(
                        target,
                        "STATIC_DECORATION_UNKNOWN_OWNER",
                        "pipeline/solid_block|cutout_block|crumbling|water_mask"
                    );
                case DRAW_SPECIAL_SHADER ->
                    addNonTerrainMetadata(
                        target,
                        "SPECIAL_SHADER",
                        "EXACT_SKY_CLOUD_TEXT_GLINT_LIGHTMAP_PATH_FAMILY"
                    );
                default ->
                    addNonTerrainMetadata(
                        target,
                        "OTHER",
                        "UNRECOGNIZED_EXACT_PIPELINE"
                    );
            }
            target.addProperty(
                "indexFormat",
                category == DRAW_CUTOUT
                    || category == DRAW_TRANSLUCENT
                    ? "PER_RECORD_SHORT_OR_INT_AND_CUSTOM_OR_SEQUENTIAL"
                    : "PIPELINE_CALL_SPECIFIC_NOT_RETAINED_IN_CATEGORY_COUNTER"
            );
            target.addProperty(
                "bufferGeneration",
                category == DRAW_CUTOUT
                    || category == DRAW_TRANSLUCENT
                    ? "MOJANG_UBER_BUFFER_OWNER_CURRENT_GENERATION"
                    : "NOT_A_TERRAIN_GENERATION_TOKEN"
            );
        }

        private static void addNonTerrainMetadata(
            JsonObject target,
            String layer,
            String pipeline
        ) {
            target.addProperty("renderLayer", layer);
            target.addProperty("pipeline", pipeline);
            target.addProperty(
                "shaderAbi",
                "PIPELINE_LOCATION_CLASSIFICATION_ONLY"
            );
            target.addProperty(
                "material",
                "PIPELINE_SPECIFIC_NOT_OWNED_BY_OPAQUE_SOLID_SLICE"
            );
            target.addProperty(
                "sectionType",
                "NOT_A_COMPATIBLE_OPAQUE_SOLID_SECTION"
            );
        }

        private static JsonObject typedUnavailable(String reason) {
            JsonObject root = new JsonObject();
            root.addProperty("status", "NOT_AVAILABLE");
            root.addProperty("unavailableReason", reason);
            return root;
        }

        private void resetCurrentFrame() {
            currentDrawSubmissions = 0L;
            currentDrawRecords = 0L;
            currentUploadCalls = 0L;
            currentUploadNanos = 0L;
            Arrays.fill(currentBackendRecords, 0L);
            Arrays.fill(currentBackendCpuNanos, 0L);
            currentIndirectCalls = 0L;
            currentIndirectMaximumCommandCapacity = 0L;
            currentIndirectCpuNanos = 0L;
        }

        private static long saturatedAdd(long left, long right) {
            long result = left + right;
            if (((left ^ result) & (right ^ result)) < 0L) {
                return Long.MAX_VALUE;
            }
            return result;
        }

        private static JsonObject distribution(
            long[] source,
            int count,
            String unit
        ) {
            long[] values = Arrays.copyOf(source, count);
            Arrays.sort(values);
            double sum = 0.0;
            for (long value : values) {
                sum += value;
            }
            JsonObject result = new JsonObject();
            result.addProperty("status", "AVAILABLE");
            result.addProperty("unit", unit);
            result.addProperty("count", count);
            result.addProperty("mean", sum / count);
            result.addProperty("median", percentile(values, 0.50));
            result.addProperty("p95", percentile(values, 0.95));
            result.addProperty("p99", percentile(values, 0.99));
            result.addProperty("minimum", values[0]);
            result.addProperty("maximum", values[count - 1]);
            long total = 0L;
            boolean totalOverflow = false;
            for (long value : values) {
                long next = saturatedAdd(total, value);
                if (next == Long.MAX_VALUE && value > 0L) {
                    totalOverflow = true;
                }
                total = next;
            }
            if (totalOverflow) {
                result.addProperty("total", "OVERFLOW");
            } else {
                result.addProperty("total", total);
            }
            return result;
        }

        private static long percentile(long[] values, double percentile) {
            int index = (int) Math.ceil(percentile * values.length) - 1;
            return values[Math.max(0, Math.min(index, values.length - 1))];
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
        long glfwWindowPointer,
        long win32Hwnd,
        long win32WindowProcessId,
        long expectedProcessId,
        boolean win32WindowValid,
        int windowWidth,
        int windowHeight,
        int framebufferWidth,
        int framebufferHeight,
        int guiScaleOption,
        int effectiveGuiScale,
        boolean blockframeLoaded,
        boolean sodiumLoaded
    ) {}

    public record WindowIdentity(
        long glfwWindowPointer,
        long win32Hwnd,
        long ownerProcessId,
        long expectedProcessId,
        boolean valid,
        String unavailableReason
    ) {
        static WindowIdentity unavailable(
            long glfwWindowPointer,
            long win32Hwnd,
            String reason
        ) {
            return new WindowIdentity(
                glfwWindowPointer,
                win32Hwnd,
                0L,
                CURRENT_PROCESS_ID,
                false,
                reason
            );
        }
    }

    interface Win32WindowApi {
        boolean isWindow(long win32Hwnd);

        long windowProcessId(long win32Hwnd);
    }

    private static final class NativeWin32WindowApi
        implements Win32WindowApi {
        @Override
        public boolean isWindow(long win32Hwnd) {
            return User32.INSTANCE.IsWindow(hwnd(win32Hwnd));
        }

        @Override
        public long windowProcessId(long win32Hwnd) {
            IntByReference processId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(
                hwnd(win32Hwnd),
                processId
            );
            return Integer.toUnsignedLong(processId.getValue());
        }

        private static HWND hwnd(long value) {
            HWND result = new HWND();
            result.setPointer(new Pointer(value));
            return result;
        }
    }

    static final class ContractException extends Exception {
        ContractException(String message) {
            super(message);
        }

        ContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
