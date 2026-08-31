package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

/**
 * Render-thread-owned development replay. Each scene uses preallocated sample
 * storage and exactly two ThreadMXBean CPU-time boundaries. No file I/O,
 * thread discovery or object allocation is performed by the per-frame
 * MEASURE path.
 */
public final class Phase2a0bRuntime {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final long CHUNK_WARMUP_NANOS = 15_000_000_000L;
    private static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    private static final String EXPECTED_BLOCKFRAME_MOD_ID = "voxellift";
    private static final RenderReadinessState READINESS =
        new RenderReadinessState();
    private static Phase2a0bRuntime instance =
        disabled("not-initialized");
    private static volatile boolean bootstrapComplete;
    private static volatile boolean bootstrapActiveRun;
    private static JsonObject bootstrapConfig;
    private static Path bootstrapActivePath;
    private static Path bootstrapRunDirectory;
    private static String bootstrapRunId = "none";
    private static ClientLevel lifecycleLevel;

    private final Minecraft minecraft;
    private final Phase2a0bContracts.MeasurementMode mode;
    private final String runId;
    private final String expectedRunCopyName;
    private final Path runDirectory;
    private final SceneManifest.Scene[] scenes;
    private final String sceneManifestHash;
    private final String benchmarkStartProfileHash;
    private final CpuTopology topology;
    private final RuntimeAttestation runtimeAttestation;
    private final ConfigTransactionReceipt configReceipt;
    private final ReplaySuiteStateTrace progress;
    private final MutableCameraPose pose = new MutableCameraPose();
    private final MeasurementBuffer samples;
    private final ThreadCpuWindow threadCpu;
    private final String[] sceneResultFiles;
    private SceneManifest.Scene scene;
    private String failureReason;
    private Phase2a0bContracts.Backend runtimeBackend =
        Phase2a0bContracts.Backend.UNKNOWN;
    private String runtimeDevice;
    private String runtimeDriver;
    private long stateStartNanos;
    private long frameStartNanos;
    private long measureStartNanos;
    private long frameId;
    private long renderThreadId;
    private JvmBoundarySnapshot jvmStart;
    private JvmBoundarySnapshot jvmEnd;
    private Object memoryStart;
    private Object memoryEnd;
    private ThreadCpuWindow.Result threadResult;
    private int completedScenes;
    private boolean terminalOutputWritten;
    private boolean worldValidated;
    private boolean optionsCaptured;
    private boolean previousVsync;
    private int previousFramerateLimit;
    private int previousFov;
    private boolean previousHideGui;

    private Phase2a0bRuntime(
        Minecraft minecraft,
        Phase2a0bContracts.MeasurementMode mode,
        String runId,
        String expectedRunCopyName,
        Path runDirectory,
        SceneManifest.Scene[] scenes,
        String sceneManifestHash,
        String benchmarkStartProfileHash,
        CpuTopology topology,
        RuntimeAttestation runtimeAttestation,
        ConfigTransactionReceipt configReceipt
    ) {
        this.minecraft = minecraft;
        this.mode = mode;
        this.runId = runId;
        this.expectedRunCopyName = expectedRunCopyName;
        this.runDirectory = runDirectory;
        this.scenes = scenes.clone();
        this.sceneManifestHash = sceneManifestHash;
        this.benchmarkStartProfileHash = benchmarkStartProfileHash;
        this.topology = topology;
        this.runtimeAttestation = runtimeAttestation;
        this.configReceipt = configReceipt;
        String[] sceneIds = new String[scenes.length];
        Phase2a0bContracts.SceneType[] sceneTypes =
            new Phase2a0bContracts.SceneType[scenes.length];
        int capacity = 1;
        for (int index = 0; index < scenes.length; index++) {
            sceneIds[index] = scenes[index].id().name();
            sceneTypes[index] = scenes[index].type();
            capacity = Math.max(
                capacity,
                Math.max(
                    1,
                    Math.multiplyExact(
                        Math.max(1, scenes[index].measureSeconds()),
                        4_000
                    )
                )
            );
        }
        this.progress = new ReplaySuiteStateTrace(sceneIds, sceneTypes);
        this.scene = this.scenes[0];
        this.samples = new MeasurementBuffer(capacity);
        this.threadCpu = new ThreadCpuWindow(true);
        this.sceneResultFiles = new String[scenes.length];
        this.stateStartNanos = System.nanoTime();
    }

    private Phase2a0bRuntime(String reason) {
        this.minecraft = null;
        this.mode = Phase2a0bContracts.MeasurementMode.UNKNOWN;
        this.runId = "none";
        this.expectedRunCopyName = "none";
        this.runDirectory = null;
        this.scenes = new SceneManifest.Scene[0];
        this.sceneManifestHash = NOT_AVAILABLE;
        this.benchmarkStartProfileHash = NOT_AVAILABLE;
        this.topology = null;
        this.runtimeAttestation = null;
        this.configReceipt = null;
        this.progress = null;
        this.samples = new MeasurementBuffer(1);
        this.threadCpu = new ThreadCpuWindow(false);
        this.sceneResultFiles = new String[0];
        this.failureReason = reason;
    }

    public static synchronized void bootstrap(Path gameDirectory) {
        if (bootstrapComplete) {
            return;
        }
        Path active = gameDirectory.toAbsolutePath()
            .normalize()
            .resolve("benchmark-2a0b")
            .resolve("active-run.json");
        bootstrapActivePath = active;
        try {
            if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
                instance = disabled("no-active-run-at-bootstrap");
                return;
            }
            JsonObject config = readObject(active);
            Path runDirectory = Path.of(
                requiredString(config, "runDirectory")
            ).toAbsolutePath().normalize();
            if (!Files.isDirectory(runDirectory)) {
                throw new IOException(
                    "prepared run directory unavailable"
                );
            }
            bootstrapConfig = config;
            bootstrapRunDirectory = runDirectory;
            bootstrapRunId = requiredString(config, "runId");
            bootstrapActiveRun = true;
            writeReadinessReceipt(
                RenderReadinessState.State.BOOTSTRAPPED
            );
        } catch (Throwable error) {
            instance = disabled(
                "bootstrap-error:"
                    + error.getClass().getSimpleName()
                    + ":"
                    + bounded(error.getMessage())
            );
            writeInitializationFailure(active, error);
            Phase2a0bHarnessMod.LOGGER.error(
                "Phase 2A.0B bootstrap failed closed",
                error
            );
        } finally {
            bootstrapComplete = true;
        }
    }

    private static synchronized boolean initialize(Minecraft minecraft) {
        if (minecraft == null) {
            instance = disabled("minecraft-unavailable");
            return false;
        }
        Path active = bootstrapActivePath;
        try {
            JsonObject config = bootstrapConfig;
            if (!bootstrapActiveRun || config == null) {
                throw new IOException(
                    "active run was not bootstrapped before render"
                );
            }
            Phase2a0bContracts.MeasurementMode mode =
                Phase2a0bContracts.MeasurementMode.parse(
                    requiredString(config, "mode")
                );
            if (
                mode != Phase2a0bContracts.MeasurementMode.REPLAY_SUITE
                    && mode
                        != Phase2a0bContracts.MeasurementMode.REPLAY
            ) {
                throw new IOException(
                    "unsupported active replay mode: " + mode
                );
            }
            String runId = requiredString(config, "runId");
            String copyName = requiredString(
                config,
                "runCopyDirectoryName"
            );
            if (
                copyName.equals(FixtureRunManager.GOLDEN_DIRECTORY)
                    || copyName.equals(
                        FixtureRunManager.ACTIVE_WORLD_DIRECTORY
                    )
            ) {
                throw new IOException("protected world selected");
            }
            Path runDirectory = Path.of(
                requiredString(config, "runDirectory")
            ).toAbsolutePath().normalize();
            Path scenesPath = Path.of(
                requiredString(config, "scenesManifest")
            ).toAbsolutePath().normalize();
            SceneManifest manifest = SceneManifest.load(scenesPath);
            String expectedSceneHash = requiredString(
                config,
                "sceneHash"
            );
            if (!expectedSceneHash.equals(manifest.fileHash())) {
                throw new IOException("scene hash mismatch");
            }
            if (
                !FixtureRunManager.GOLDEN_SHA256.equals(
                    manifest.fixtureHash()
                )
            ) {
                throw new IOException("scene fixture hash mismatch");
            }
            FixtureRunManager.RuntimeStaticAudit audit =
                new FixtureRunManager.RuntimeStaticAudit(
                    requiredInt(config, "fixtureFiles"),
                    requiredLong(config, "fixtureBytes"),
                    requiredString(config, "fixtureSha256"),
                    requiredInt(config, "modFiles"),
                    requiredString(config, "modHash")
                );
            String expectedProfile = requiredString(
                config,
                "benchmarkStartProfileHash"
            );
            if (
                !manifest.benchmarkStartProfileHash().equals(
                    expectedProfile
                )
            ) {
                throw new IOException(
                    "scene benchmark start profile hash mismatch"
                );
            }
            Phase2a0bContracts.SceneId[] sceneIds =
                sceneIds(config, mode);
            String[] sceneNames = java.util.Arrays.stream(sceneIds)
                .map(Enum::name)
                .toArray(String[]::new);
            SceneManifest.Scene[] scenes =
                manifest.requireReadyScenes(sceneNames);
            requireExpectedSuite(mode, sceneIds);
            CpuTopology topology = CpuTopologyProbe.detect();
            Path receiptPath = Path.of(
                requiredString(config, "configTransactionReceipt")
            ).toAbsolutePath().normalize();
            ConfigTransactionReceipt receipt =
                ConfigTransactionReceipt.readOnce(receiptPath);
            receipt.validateForReplay(
                runId,
                minecraft.gameDirectory.toPath(),
                expectedProfile,
                requiredString(config, "appliedRawConfigHash"),
                requiredString(config, "transactionModProfileHash")
            );
            if (
                !receipt.receiptContentHash().equals(
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(
                            config,
                            "configTransactionReceiptContentHash"
                        )
                    )
                )
            ) {
                throw new IOException(
                    "config receipt manifest hash mismatch"
                );
            }
            RuntimeAttestation attestation = attestLoadedRuntime(
                minecraft,
                audit,
                expectedProfile,
                manifest.fileHash()
            );
            Phase2a0bRuntime runtime = new Phase2a0bRuntime(
                minecraft,
                mode,
                runId,
                copyName,
                runDirectory,
                scenes,
                manifest.fileHash(),
                expectedProfile,
                topology,
                attestation,
                receipt
            );
            runtime.validateLoadedRunCopy();
            runtime.worldValidated = true;
            runtime.validateRuntimeBackend();
            runtime.validateCpuTopology();
            runtime.validatePreOwnerContract(
                audit,
                sceneIds,
                expectedProfile,
                manifest.fileHash()
            );
            READINESS.markReplayArmed(
                Thread.currentThread().threadId()
            );
            runtime.writeProcessManifest(READINESS.ownerPublications());
            instance = runtime;
            publishUnreportedReadinessTransitions();
            return true;
        } catch (Throwable error) {
            READINESS.markOwnerPublicationFailed();
            instance = disabled(
                "preflight-error:"
                    + error.getClass().getSimpleName()
                    + ":"
                    + bounded(error.getMessage())
            );
            writeInitializationFailure(active, error);
            Phase2a0bHarnessMod.LOGGER.error(
                "Phase 2A.0B preflight failed closed",
                error
            );
            return false;
        }
    }

    public static String mode() {
        return instance.mode.name();
    }

    public static void onRenderHead(boolean advanceGameTime) {
        if (!bootstrapComplete || !bootstrapActiveRun) {
            return;
        }
        long callbackThreadId = Thread.currentThread().threadId();
        if (READINESS.replayArmed()) {
            if (!READINESS.heartbeat(callbackThreadId)) {
                Phase2a0bRuntime wrongThreadRuntime = instance;
                if (
                    wrongThreadRuntime.minecraft != null
                        && !wrongThreadRuntime.state().terminal()
                ) {
                    wrongThreadRuntime.fail(
                        "render-callback-thread-changed"
                    );
                }
                return;
            }
        } else {
            Minecraft minecraft = Minecraft.getInstance();
            boolean firstCallback =
                READINESS.totalCallbackCount() == 0L;
            var camera = minecraft == null
                ? null
                : minecraft.gameRenderer.mainCamera();
            RenderReadinessState.Decision decision = READINESS.observe(
                callbackThreadId,
                System.nanoTime(),
                firstCallback ? System.currentTimeMillis() : 0L,
                minecraft != null
                    && minecraft.level != null
                    && minecraft.getSingleplayerServer() != null,
                minecraft != null && minecraft.player != null,
                camera != null
                    && camera.isInitialized()
                    && camera.entity() != null
            );
            publishUnreportedReadinessTransitions();
            if (decision == RenderReadinessState.Decision.WRONG_THREAD) {
                return;
            }
            if (decision == RenderReadinessState.Decision.BIND_OWNER) {
                if (initialize(minecraft)) {
                    Phase2a0bHarnessMod.LOGGER.info(
                        "BlockFrame Phase 2A.0B replay armed from "
                            + "render callback; generation={} threadId={}",
                        READINESS.generation(),
                        READINESS.renderThreadId()
                    );
                }
            }
            if (!READINESS.replayArmed()) {
                return;
            }
        }
        Phase2a0bRuntime runtime = instance;
        if (
            runtime.minecraft == null
                || runtime.state().terminal()
                || !advanceGameTime
        ) {
            return;
        }
        try {
            runtime.frameStartNanos = System.nanoTime();
            runtime.advanceBeforeFrame(runtime.frameStartNanos);
        } catch (Throwable error) {
            runtime.fail(
                "render-head:"
                    + error.getClass().getSimpleName()
                    + ":"
                    + bounded(error.getMessage())
            );
        }
    }

    public static void onRenderReturn(boolean advanceGameTime) {
        if (!READINESS.replayArmed()) {
            return;
        }
        Phase2a0bRuntime runtime = instance;
        if (
            runtime.minecraft == null
                || runtime.state().terminal()
                || !advanceGameTime
        ) {
            return;
        }
        try {
            runtime.finishFrame(System.nanoTime());
        } catch (Throwable error) {
            runtime.fail(
                "render-return:"
                    + error.getClass().getSimpleName()
                    + ":"
                    + bounded(error.getMessage())
            );
        }
    }

    public static void abortReplay(String reason) {
        Phase2a0bRuntime runtime = instance;
        if (
            runtime.minecraft != null
                && (
                    runtime.mode
                        == Phase2a0bContracts.MeasurementMode.REPLAY
                        || runtime.mode
                            == Phase2a0bContracts.MeasurementMode
                                .REPLAY_SUITE
                )
                && !runtime.state().terminal()
        ) {
            runtime.fail("aborted:" + bounded(reason));
        }
    }

    public static void onClientLevelSet(ClientLevel level) {
        if (level == null) {
            onClientLevelUnload();
            return;
        }
        if (lifecycleLevel != null && lifecycleLevel != level) {
            onClientLevelUnload();
        }
        lifecycleLevel = level;
        READINESS.onWorldLifecyclePresent();
    }

    public static void onClientLevelUnload() {
        Phase2a0bRuntime runtime = instance;
        if (
            runtime.minecraft != null
                && !runtime.state().terminal()
        ) {
            runtime.fail("world-unloaded");
        }
        lifecycleLevel = null;
        if (READINESS.invalidateWorld()) {
            writeReadinessReceipt(
                RenderReadinessState.State.BOOTSTRAPPED
            );
        }
    }

    public static void close() {
        Phase2a0bRuntime runtime = instance;
        if (runtime.minecraft != null) {
            if (!runtime.state().terminal()) {
                runtime.fail("minecraft-closing");
            }
            runtime.threadCpu.close();
            runtime.restoreBenchmarkOptions();
            runtime.writeShutdownResult();
        }
        writeFinalReadinessReceipt();
    }

    private void advanceBeforeFrame(long now) throws IOException {
        if (state() == BenchmarkState.PREFLIGHT) {
            transition(BenchmarkState.WORLD_WAIT, now);
        }
        if (state() == BenchmarkState.WORLD_WAIT) {
            if (
                this.minecraft.level == null
                    || this.minecraft.player == null
                    || this.minecraft.getCameraEntity() == null
                    || this.minecraft.getSingleplayerServer() == null
            ) {
                return;
            }
            if (!this.worldValidated) {
                validateLoadedRunCopy();
                this.worldValidated = true;
            }
            validateSceneWorld();
            applyFixedRunConditions();
            validateRuntimeConditions();
            transition(BenchmarkState.CHUNK_WARMUP, now);
        }
        if (
            state() == BenchmarkState.CHUNK_WARMUP
                && now - this.stateStartNanos >= CHUNK_WARMUP_NANOS
        ) {
            transition(BenchmarkState.WARMUP, now);
        }
        if (state() == BenchmarkState.WARMUP) {
            applyCamera(now - this.stateStartNanos);
            if (
                now - this.stateStartNanos
                    >= this.scene.warmupSeconds() * 1_000_000_000L
            ) {
                if (
                    this.scene.type()
                        == Phase2a0bContracts.SceneType.PERFORMANCE
                ) {
                    beginMeasure(now);
                } else {
                    beginReferenceCapture(now);
                }
            }
        } else if (state() == BenchmarkState.MEASURE) {
            long replayNanos = now - this.measureStartNanos;
            applyCamera(replayNanos);
            if (
                replayNanos
                    >= this.scene.measureSeconds() * 1_000_000_000L
            ) {
                endMeasure(now);
            }
        } else if (state() == BenchmarkState.REFERENCE_CAPTURE) {
            completeScene(now);
        }
    }

    private void finishFrame(long now) {
        if (state() != BenchmarkState.MEASURE) {
            return;
        }
        long replayNanos = Math.max(0L, now - this.measureStartNanos);
        if (
            !this.samples.record(
                ++this.frameId,
                replayNanos,
                Math.max(0L, now - this.frameStartNanos),
                this.pose.hash64(),
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE,
                MeasurementBuffer.NOT_AVAILABLE
            )
        ) {
            fail("measurement-buffer-overflow");
        }
    }

    private void beginMeasure(long now) {
        if (
            this.scene.type()
                != Phase2a0bContracts.SceneType.PERFORMANCE
        ) {
            throw new IllegalStateException(
                "only PERFORMANCE scenes may open a CPU window"
            );
        }
        this.renderThreadId = Thread.currentThread().threadId();
        this.threadCpu.prepare();
        this.jvmStart = JvmBoundarySnapshot.capture(this.renderThreadId);
        this.memoryStart = readPhysicalMemorySnapshot();
        this.threadCpu.begin();
        setHudHidden(true);
        this.measureStartNanos = now;
        transition(BenchmarkState.MEASURE, now);
    }

    private void beginReferenceCapture(long now) {
        if (
            this.scene.type()
                != Phase2a0bContracts.SceneType.IMAGE_REFERENCE
        ) {
            throw new IllegalStateException(
                "direct reference capture requires IMAGE_REFERENCE"
            );
        }
        setHudHidden(true);
        transition(BenchmarkState.REFERENCE_CAPTURE, now);
    }

    private void endMeasure(long now) {
        transition(BenchmarkState.REFERENCE_CAPTURE, now);
        this.threadResult = this.threadCpu.end(
            this.topology.physicalCores()
        );
        this.jvmEnd = JvmBoundarySnapshot.capture(this.renderThreadId);
        this.memoryEnd = readPhysicalMemorySnapshot();
    }

    private void completeScene(long now) throws IOException {
        transition(BenchmarkState.COMPLETE, now);
        writeSceneResult();
        this.completedScenes++;
        if (!this.progress.advanceScene()) {
            writeTerminalResult();
            return;
        }
        this.scene = this.scenes[this.progress.sceneIndex()];
        this.samples.reset();
        this.frameId = 0L;
        this.jvmStart = null;
        this.jvmEnd = null;
        this.memoryStart = null;
        this.memoryEnd = null;
        this.threadResult = null;
        this.stateStartNanos = now;
    }

    private void applyCamera(long replayNanos) {
        long clamped = Math.min(
            Math.max(0L, replayNanos),
            this.scene.timeline().durationNanos()
        );
        this.scene.timeline().sample(clamped, this.pose);
        this.minecraft.player.setPos(
            this.pose.x(),
            this.pose.y(),
            this.pose.z()
        );
        this.minecraft.player.setYRot(this.pose.yaw());
        this.minecraft.player.setXRot(this.pose.pitch());
        this.minecraft.options.fov().set(Math.round(this.pose.fov()));
    }

    private void validateLoadedRunCopy() throws IOException {
        Path loaded = this.minecraft.getSingleplayerServer()
            .getWorldPath(LevelResource.ROOT)
            .toAbsolutePath()
            .normalize();
        String actual = loaded.getFileName().toString();
        if (!this.expectedRunCopyName.equals(actual)) {
            throw new IOException(
                "loaded world folder "
                    + actual
                    + " != expected "
                    + this.expectedRunCopyName
            );
        }
    }

    private void validateSceneWorld() throws IOException {
        String dimension = this.minecraft.level.dimension()
            .identifier()
            .toString();
        if (!this.scene.dimension().equals(dimension)) {
            throw new IOException(
                "dimension mismatch: "
                    + dimension
                    + " != "
                    + this.scene.dimension()
            );
        }
    }

    private void applyFixedRunConditions() throws IOException {
        captureBenchmarkOptions();
        this.minecraft.options.enableVsync().set(this.scene.vsync());
        this.minecraft.options.framerateLimit().set(
            this.scene.fpsLimit()
        );
        this.minecraft.options.fov().set(Math.round(this.scene.fov()));
        var server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IOException("integrated server unavailable");
        }
        var clock = server.overworld()
            .dimensionType()
            .defaultClock()
            .orElseThrow(
                () ->
                    new IOException(
                        "overworld default clock unavailable"
                    )
            );
        server.clockManager().setTotalTicks(
            clock,
            this.scene.worldClockTotalTicks()
        );
        server.clockManager().setPaused(clock, true);
        server.getGlobalGameRules().set(
            GameRules.ADVANCE_WEATHER,
            false,
            server
        );
        server.overworld().resetWeatherCycle();
    }

    private void validateRuntimeConditions() throws IOException {
        if (
            this.minecraft.options.renderDistance().get()
                != this.scene.renderDistanceChunks()
        ) {
            throw new IOException("render-distance mismatch");
        }
        if (
            this.minecraft.options.simulationDistance().get()
                != this.scene.simulationDistanceChunks()
        ) {
            throw new IOException("simulation-distance mismatch");
        }
        if (
            this.minecraft.getWindow().getWidth()
                    != this.scene.resolutionWidth()
                || this.minecraft.getWindow().getHeight()
                    != this.scene.resolutionHeight()
        ) {
            throw new IOException(
                "framebuffer-resolution mismatch: "
                    + this.minecraft.getWindow().getWidth()
                    + "x"
                    + this.minecraft.getWindow().getHeight()
            );
        }
        boolean fullscreen = this.minecraft.getWindow().isFullscreen();
        if (
            fullscreen
                != "FULLSCREEN".equals(this.scene.windowMode())
        ) {
            throw new IOException("window-mode mismatch");
        }
        if (
            this.minecraft.options.enableVsync().get()
                    != this.scene.vsync()
                || this.minecraft.options.framerateLimit().get()
                    != this.scene.fpsLimit()
        ) {
            throw new IOException("frame-pacing configuration mismatch");
        }
        if (!"CLEAR".equals(this.scene.weather())) {
            throw new IOException("unsupported weather contract");
        }
        if (!this.minecraft.player.getAbilities().instabuild) {
            throw new IOException(
                "benchmark camera requires the fixture creative player"
            );
        }
        validateNativeBaselineDisabled();
    }

    private void validateRuntimeBackend() throws IOException {
        try {
            var info = RenderSystem.getDevice().getDeviceInfo();
            this.runtimeBackend =
                Phase2a0bContracts.Backend.parse(info.backendName());
            this.runtimeDevice = info.name();
            this.runtimeDriver = info.driverInfo();
            if (
                this.runtimeBackend
                    != Phase2a0bContracts.Backend.VULKAN
            ) {
                throw new IOException(
                    "runtime backend is " + this.runtimeBackend
                );
            }
            if (
                !"NVIDIA GeForce RTX 4090".equals(this.runtimeDevice)
            ) {
                throw new IOException(
                    "runtime GPU differs from captured profile: "
                        + this.runtimeDevice
                );
            }
            if (
                this.runtimeDriver == null
                    || !this.runtimeDriver.contains("610.74")
            ) {
                throw new IOException(
                    "runtime driver differs from captured profile: "
                        + this.runtimeDriver
                );
            }
        } catch (IllegalStateException | LinkageError error) {
            throw new IOException(
                "BlockFrame runtime backend unavailable",
                error
            );
        }
    }

    private void validateCpuTopology() throws IOException {
        if (
            !this.topology.model().contains("9800X3D")
                || this.topology.physicalCores() != 8
                || this.topology.logicalProcessors() != 16
                || this.topology.jvmAvailableProcessors() != 16
                || this.topology.affinityLogicalProcessors() != 16
        ) {
            throw new IOException(
                "benchmark CPU topology mismatch: "
                    + GSON.toJson(this.topology)
            );
        }
    }

    private void validatePreOwnerContract(
        FixtureRunManager.RuntimeStaticAudit audit,
        Phase2a0bContracts.SceneId[] sceneIds,
        String expectedProfile,
        String sceneHash
    ) throws IOException {
        Phase2a0bPreflight.requirePreOwner(
            new Phase2a0bPreflight.Input(
                true,
                true,
                true,
                true,
                Phase2a0bContracts.RuntimeProfile
                    .CAPTURED_SECOND_LIVE_RUN_20260728,
                this.mode,
                true,
                true,
                Phase2a0bContracts.Sha256.parse(sceneHash),
                Phase2a0bContracts.Sha256.parse(
                    audit.fixtureSha256()
                ),
                audit.fixtureFiles(),
                audit.fixtureBytes(),
                Phase2a0bContracts.Sha256.parse(audit.modHash()),
                true,
                Phase2a0bContracts.Sha256.parse(expectedProfile),
                sceneIds,
                this.runtimeAttestation.blockframeModId(),
                this.runtimeAttestation.blockframeCodeSourceFilename(),
                this.runtimeAttestation.blockframeCodeSourceInMods(),
                Phase2a0bContracts.ArtifactVersion.parse(
                    this.runtimeAttestation.blockframeReleaseVersion()
                ),
                Phase2a0bContracts.ArtifactVersion.parse(
                    this.runtimeAttestation.blockframeMetadataVersion()
                ),
                Phase2a0bContracts.Sha256.parse(
                    this.runtimeAttestation
                        .blockframeCodeSourceSha256()
                ),
                Phase2a0bContracts.ArtifactVersion.parse(
                    this.runtimeAttestation.minecraftVersion()
                ),
                Phase2a0bContracts.ArtifactVersion.parse(
                    this.runtimeAttestation.neoForgeVersion()
                ),
                Phase2a0bContracts.ArtifactVersion.parse(
                    this.runtimeAttestation.harnessVersion()
                ),
                this.runtimeBackend,
                this.runtimeDevice,
                this.runtimeDriver,
                this.topology.model(),
                this.topology.physicalCores(),
                this.topology.logicalProcessors(),
                this.topology.jvmAvailableProcessors(),
                this.topology.affinityLogicalProcessors(),
                1,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                2,
                0,
                0
            )
        );
    }

    private void fail(String reason) {
        if (
            state() == BenchmarkState.FAILED
                || (
                    state() == BenchmarkState.COMPLETE
                        && this.terminalOutputWritten
                )
        ) {
            return;
        }
        BenchmarkState previous = state();
        transition(BenchmarkState.FAILED, System.nanoTime());
        this.failureReason = bounded(reason);
        if (previous == BenchmarkState.MEASURE) {
            try {
                this.threadResult = this.threadCpu.end(
                    this.topology.physicalCores()
                );
            } catch (RuntimeException ignored) {
                this.threadCpu.close();
            }
            this.jvmEnd = JvmBoundarySnapshot.capture(
                Thread.currentThread().threadId()
            );
            this.memoryEnd = readPhysicalMemorySnapshot();
        } else {
            this.threadCpu.close();
        }
        try {
            writeTerminalResult();
        } catch (IOException error) {
            Phase2a0bHarnessMod.LOGGER.error(
                "Could not write Phase 2A.0B failure result",
                error
            );
        }
    }

    private BenchmarkState state() {
        return this.progress == null
            ? BenchmarkState.FAILED
            : this.progress.state();
    }

    private void transition(BenchmarkState next, long now) {
        this.progress.transition(next);
        this.stateStartNanos = now;
    }

    private void writeProcessManifest(int ownerCount) throws IOException {
        ensureIoAllowed();
        JsonObject process = new JsonObject();
        process.addProperty("schemaVersion", 1);
        process.addProperty("runId", this.runId);
        process.addProperty("capturedAtUtc", Instant.now().toString());
        process.add("cpuTopology", GSON.toJsonTree(this.topology));
        process.addProperty(
            "benchmarkProcessPid",
            ProcessHandle.current().pid()
        );
        process.addProperty(
            "initializationOwnerCount",
            ownerCount
        );
        process.addProperty("initializationThread", "RENDER_THREAD");
        process.addProperty(
            "renderReadinessState",
            READINESS.state().name()
        );
        process.addProperty(
            "renderReadinessGeneration",
            READINESS.generation()
        );
        process.addProperty(
            "renderCallbackCountAtArm",
            READINESS.totalCallbackCount()
        );
        process.addProperty(
            "renderThreadId",
            READINESS.renderThreadId()
        );
        process.addProperty(
            "firstRenderCallbackEpochMillis",
            READINESS.firstCallbackEpochMillis()
        );
        process.addProperty(
            "lastReadinessMask",
            READINESS.readinessMask()
        );
        process.addProperty("internalInitializationDeadline", false);
        process.addProperty("readinessPolling", false);
        process.addProperty("runtimeBackend", this.runtimeBackend.name());
        process.addProperty("runtimeDevice", this.runtimeDevice);
        process.addProperty("runtimeDriver", this.runtimeDriver);
        process.add(
            "runtimeAttestation",
            GSON.toJsonTree(this.runtimeAttestation)
        );
        process.addProperty(
            "configReceiptStatus",
            this.configReceipt.status().name()
        );
        process.addProperty(
            "configReceiptContentHash",
            this.configReceipt.receiptContentHash().value()
        );
        process.addProperty(
            "benchmarkStartProfileHash",
            this.benchmarkStartProfileHash
        );
        process.addProperty(
            "threadCpuBoundaryContract",
            "two boundaries per PERFORMANCE scene; zero for IMAGE_REFERENCE"
        );
        process.addProperty("perFrameThreadDiscovery", false);
        process.addProperty("samplerThread", false);
        process.addProperty("fileWriterThread", false);
        process.addProperty("affinityOrPriorityChanged", false);
        process.addProperty(
            "windowsProcessorGroups",
            "NOT_AVAILABLE: no dependency-free reliable process-group API"
        );
        process.addProperty(
            "hybridCoreClasses",
            "NOT_AVAILABLE: not inferred from CPU name"
        );
        writeNew(
            this.runDirectory.resolve("run-process-manifest.json"),
            process
        );
    }

    private void writeSceneResult() throws IOException {
        ensureIoAllowed();
        int index = this.progress.sceneIndex();
        JsonObject result = new JsonObject();
        result.addProperty(
            "schemaVersion",
            Phase2a0bResultSchema.VERSION
        );
        result.addProperty("phase", "2A.0B");
        result.addProperty("runId", this.runId);
        result.addProperty("mode", this.mode.name());
        result.addProperty("state", state().name());
        result.addProperty("sceneOrdinal", index + 1);
        result.addProperty("sceneCount", this.scenes.length);
        result.addProperty("sameProcessSuite", true);
        result.addProperty("worldRunCopyValidated", this.worldValidated);
        result.addProperty("sceneId", this.scene.id().name());
        result.addProperty("sceneType", this.scene.type().name());
        result.addProperty(
            "fixtureSha256",
            this.runtimeAttestation.fixtureManifestHash()
        );
        result.addProperty(
            "sceneHash64",
            Long.toUnsignedString(this.scene.sceneHash64())
        );
        result.addProperty(
            "cameraTimelineHash64",
            Long.toUnsignedString(this.scene.timeline().hash64())
        );
        result.addProperty(
            "sceneManifestSha256",
            this.sceneManifestHash
        );
        result.addProperty(
            "benchmarkStartProfileHash",
            this.benchmarkStartProfileHash
        );
        result.addProperty(
            "resolution",
            this.scene.resolutionWidth()
                + "x"
                + this.scene.resolutionHeight()
        );
        result.addProperty(
            "captureTimestampUtc",
            Instant.now().toString()
        );
        result.add(
            "runtimeAttestation",
            GSON.toJsonTree(this.runtimeAttestation)
        );
        result.addProperty("runtimeBackend", this.runtimeBackend.name());
        result.addProperty("runtimeDevice", this.runtimeDevice);
        result.addProperty("runtimeDriver", this.runtimeDriver);
        JsonArray trace = new JsonArray();
        for (BenchmarkState state : this.progress.trace(index)) {
            trace.add(state.name());
        }
        result.add("stateTrace", trace);
        Phase2a0bResultSchema.addCpuContract(
            result,
            this.scene.type(),
            this.threadResult
        );
        if (
            this.scene.type()
                == Phase2a0bContracts.SceneType.PERFORMANCE
        ) {
            result.addProperty("sampleCount", this.samples.size());
            result.addProperty("sampleCapacity", this.samples.capacity());
            result.addProperty(
                "sampleOverflow",
                this.samples.overflowed()
            );
            result.addProperty(
                "sampleSchema",
                "frameId,replayNanos,cpuFrameNanos,cameraHash64,"
                    + "gpuTimerNanos,renderWaitNanos,chunkBacklog,"
                    + "uploadBacklog,jobBacklog,visibleSections,drawCount,"
                    + "submitCount; -1=NOT_AVAILABLE"
            );
            result.add("cpuTopology", GSON.toJsonTree(this.topology));
            result.add("jvmStart", GSON.toJsonTree(this.jvmStart));
            result.add("jvmEnd", GSON.toJsonTree(this.jvmEnd));
            result.add("threadCpu", GSON.toJsonTree(this.threadResult));
            result.add(
                "cachedPhysicalMemoryStart",
                GSON.toJsonTree(this.memoryStart)
            );
            result.add(
                "cachedPhysicalMemoryEnd",
                GSON.toJsonTree(this.memoryEnd)
            );
            result.add(
                "existingBlockframeProfilerAfterMeasure",
                GSON.toJsonTree(readProfilerSnapshot())
            );
        }
        result.addProperty("cameraInputDuringReplay", false);
        result.addProperty("fileIoDuringMeasure", false);
        result.addProperty(
            "resultWrittenAfterMeasure",
            this.scene.type()
                == Phase2a0bContracts.SceneType.PERFORMANCE
        );
        result.addProperty("blocksIntentionallyChanged", false);
        result.addProperty("inventoryIntentionallyChanged", false);
        result.add("referenceCaptures", referenceCaptures());
        result.addProperty("performanceBaseline", "NOT_RUN");
        result.addProperty("fpsOrSpeedupClaim", "NONE");
        result.addProperty("imageParityClaim", "NONE");
        String resultName =
            "scene-result-" + this.scene.id().name() + ".json";
        writeNew(this.runDirectory.resolve(resultName), result);
        this.sceneResultFiles[index] = resultName;
        if (
            this.scene.type()
                    == Phase2a0bContracts.SceneType.PERFORMANCE
                && this.samples.size() > 0
        ) {
            this.samples.writeCsv(
                this.runDirectory.resolve(
                    "scene-samples-"
                        + this.scene.id().name()
                        + ".csv"
                )
            );
        }
    }

    private void writeTerminalResult() throws IOException {
        if (this.terminalOutputWritten) {
            return;
        }
        ensureIoAllowed();
        JsonObject result = new JsonObject();
        result.addProperty(
            "schemaVersion",
            Phase2a0bResultSchema.VERSION
        );
        result.addProperty("phase", "2A.0B");
        result.addProperty("runId", this.runId);
        result.addProperty("mode", this.mode.name());
        result.addProperty("state", state().name());
        result.addProperty("failureReason", value(this.failureReason));
        result.addProperty("scenesExpected", this.scenes.length);
        result.addProperty("scenesCompleted", this.completedScenes);
        result.addProperty(
            "initializationOwnerCount",
            READINESS.ownerPublications()
        );
        result.addProperty("sameMinecraftProcess", true);
        result.addProperty("goldenOpenedByMinecraft", false);
        result.addProperty("activeConstructionWorldOpened", false);
        JsonArray scenesJson = new JsonArray();
        for (int index = 0; index < this.scenes.length; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty(
                "id",
                this.scenes[index].id().name()
            );
            entry.addProperty(
                "result",
                this.sceneResultFiles[index] == null
                    ? NOT_AVAILABLE
                    : this.sceneResultFiles[index]
            );
            entry.addProperty(
                "status",
                this.sceneResultFiles[index] == null
                    ? NOT_AVAILABLE
                    : "COMPLETE"
            );
            scenesJson.add(entry);
        }
        result.add("scenes", scenesJson);
        result.addProperty("performanceBaseline", "NOT_RUN");
        result.addProperty("fpsOrSpeedupClaim", "NONE");
        result.addProperty("phase2a1", "NOT_STARTED");
        writeNew(
            this.runDirectory.resolve("suite-result.json"),
            result
        );
        this.terminalOutputWritten = true;
    }

    private void writeShutdownResult() {
        if (this.runDirectory == null) {
            return;
        }
        try {
            JsonObject shutdown = new JsonObject();
            shutdown.addProperty(
                "schemaVersion",
                Phase2a0bResultSchema.VERSION
            );
            shutdown.addProperty("runId", this.runId);
            shutdown.addProperty("capturedAtUtc", Instant.now().toString());
            shutdown.addProperty("stateAtMinecraftClose", state().name());
            shutdown.addProperty(
                "cleanHarnessCompletion",
                state() == BenchmarkState.COMPLETE
                    && this.completedScenes == this.scenes.length
            );
            shutdown.addProperty("samplerActiveAfterClose", false);
            shutdown.addProperty("fileWriterThreadPresent", false);
            shutdown.addProperty("optionsRestoredInMemory", true);
            writeNew(
                this.runDirectory.resolve("shutdown-result.json"),
                shutdown
            );
        } catch (IOException error) {
            Phase2a0bHarnessMod.LOGGER.error(
                "Could not write Phase 2A.0B shutdown result",
                error
            );
        }
    }

    private void ensureIoAllowed() {
        if (state() == BenchmarkState.MEASURE) {
            throw new IllegalStateException(
                "file I/O is forbidden during MEASURE"
            );
        }
    }

    private static void writeNew(Path path, JsonObject object)
        throws IOException {
        Phase2a0bResultSchema.publishNew(path, object);
    }

    private JsonObject referenceCaptures() throws IOException {
        JsonObject reference = new JsonObject();
        if (
            this.scene.type()
                != Phase2a0bContracts.SceneType.IMAGE_REFERENCE
        ) {
            reference.addProperty("status", "NOT_APPLICABLE");
            reference.addProperty(
                "reasonCode",
                "PERFORMANCE_SCENE_NO_REFERENCE_OUTPUT"
            );
            reference.add("captureFiles", new JsonArray());
            return reference;
        }
        reference.addProperty("status", "REFERENCE_METADATA_COMPLETE");
        reference.addProperty("capturedAtUtc", Instant.now().toString());
        JsonArray captureFiles = new JsonArray();
        JsonObject outputs = new JsonObject();
        addCaptureOutput(
            outputs,
            captureFiles,
            "color",
            "color.png",
            "COLOR_CAPTURE_NOT_WRITTEN"
        );
        addCaptureOutput(
            outputs,
            captureFiles,
            "depth",
            "depth.bin",
            "DEPTH_CAPTURE_NOT_WRITTEN"
        );
        addCaptureOutput(
            outputs,
            captureFiles,
            "motion",
            "motion.bin",
            "MOTION_CAPTURE_NOT_WRITTEN"
        );
        addCaptureOutput(
            outputs,
            captureFiles,
            "normals",
            "normals.bin",
            "NORMALS_CAPTURE_NOT_WRITTEN"
        );
        addCaptureOutput(
            outputs,
            captureFiles,
            "material",
            "material.bin",
            "MATERIAL_CAPTURE_NOT_WRITTEN"
        );
        reference.add("captureFiles", captureFiles);
        reference.add("outputs", outputs);
        reference.addProperty("imageParityClaim", "NONE");
        return reference;
    }

    private void addCaptureOutput(
        JsonObject outputs,
        JsonArray captureFiles,
        String outputName,
        String fileName,
        String unavailableReason
    ) throws IOException {
        Path captureDirectory = this.runDirectory.resolve(
            "reference-captures"
        ).resolve(this.scene.id().name());
        Path output = captureDirectory.resolve(fileName);
        JsonObject metadata = new JsonObject();
        if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(output)) {
                throw new IOException(
                    "reference capture link rejected: " + outputName
                );
            }
            String sha256 = FixtureInventory.sha256(output);
            metadata.addProperty("status", "AVAILABLE");
            metadata.addProperty(
                "path",
                this.runDirectory.relativize(output).toString()
                    .replace('\\', '/')
            );
            metadata.addProperty("bytes", Files.size(output));
            metadata.addProperty("sha256", sha256);
            JsonObject file = metadata.deepCopy();
            file.addProperty("output", outputName);
            captureFiles.add(file);
        } else {
            metadata.addProperty("status", "NOT_AVAILABLE");
            metadata.addProperty("reasonCode", unavailableReason);
        }
        outputs.add(outputName, metadata);
    }

    private static Object readPhysicalMemorySnapshot() {
        try {
            Object engine = currentBlockframeEngine();
            return engine.getClass()
                .getMethod("physicalMemorySnapshot")
                .invoke(engine);
        } catch (
            ReflectiveOperationException | RuntimeException | LinkageError error
        ) {
            return "NOT_AVAILABLE: current BlockFrame runtime exposes no cached physical-memory snapshot";
        }
    }

    private static Object readProfilerSnapshot() {
        try {
            Object engine = currentBlockframeEngine();
            Object profiler = engine.getClass()
                .getMethod("profiler")
                .invoke(engine);
            return profiler.getClass().getMethod("snapshot").invoke(profiler);
        } catch (
            ReflectiveOperationException | RuntimeException | LinkageError error
        ) {
            return "NOT_AVAILABLE: current BlockFrame runtime exposes no read-only profiler snapshot";
        }
    }

    private static Object currentBlockframeEngine()
        throws ReflectiveOperationException {
        Class<?> runtime = Class.forName(
            "de.morau.blockframe.core.BlockframeRuntime"
        );
        return runtime.getMethod("engine").invoke(null);
    }

    private static RuntimeAttestation attestLoadedRuntime(
        Minecraft minecraft,
        FixtureRunManager.RuntimeStaticAudit audit,
        String benchmarkStartProfileHash,
        String sceneManifestHash
    ) throws IOException {
        ModList mods = ModList.get();
        ModContainer blockframe = requiredMod(mods, EXPECTED_BLOCKFRAME_MOD_ID);
        String metadataVersion = blockframe.getModInfo()
            .getVersion()
            .toString();
        Path codeSource = blockframe.getModInfo()
            .getOwningFile()
            .getFile()
            .getFilePath()
            .toAbsolutePath()
            .normalize();
        String codeSourceFilename = codeSource.getFileName().toString();
        String codeSourceSha256 = Files.isRegularFile(
                codeSource,
                LinkOption.NOFOLLOW_LINKS
            )
            ? FixtureInventory.sha256(codeSource)
            : NOT_AVAILABLE;
        Path expectedMods = minecraft.gameDirectory.toPath()
            .resolve("mods")
            .toAbsolutePath()
            .normalize();
        int releaseSeparator = metadataVersion.indexOf("-neoforge-");
        String releaseVersion = releaseSeparator > 0
            ? metadataVersion.substring(0, releaseSeparator)
            : metadataVersion;
        return new RuntimeAttestation(
            blockframe.getModId(),
            releaseVersion,
            metadataVersion,
            codeSourceFilename,
            expectedMods.equals(codeSource.getParent()),
            codeSourceSha256,
            requiredModVersion(mods, "neoforge"),
            requiredModVersion(mods, "minecraft"),
            requiredModVersion(mods, Phase2a0bHarnessMod.MOD_ID),
            audit.modHash(),
            benchmarkStartProfileHash,
            audit.fixtureSha256(),
            sceneManifestHash
        );
    }

    private static ModContainer requiredMod(ModList mods, String modId)
        throws IOException {
        return mods.getModContainerById(modId)
            .orElseThrow(
                () -> new IOException(
                    "required runtime mod is not loaded: " + modId
                )
            );
    }

    private static String requiredModVersion(ModList mods, String modId)
        throws IOException {
        return requiredMod(mods, modId).getModInfo()
            .getVersion()
            .toString();
    }

    private static String requiredString(JsonObject object, String name)
        throws IOException {
        if (
            object.get(name) == null
                || object.get(name).isJsonNull()
                || object.get(name).getAsString().isBlank()
        ) {
            throw new IOException("missing active-run field: " + name);
        }
        return object.get(name).getAsString();
    }

    private static int requiredInt(JsonObject object, String name)
        throws IOException {
        try {
            if (object.get(name) == null) {
                throw new IOException(
                    "missing active-run field: " + name
                );
            }
            return object.get(name).getAsInt();
        } catch (RuntimeException error) {
            throw new IOException(
                "invalid integer active-run field: " + name,
                error
            );
        }
    }

    private static long requiredLong(JsonObject object, String name)
        throws IOException {
        try {
            if (object.get(name) == null) {
                throw new IOException(
                    "missing active-run field: " + name
                );
            }
            return object.get(name).getAsLong();
        } catch (RuntimeException error) {
            throw new IOException(
                "invalid long active-run field: " + name,
                error
            );
        }
    }

    private static Phase2a0bContracts.SceneId[] sceneIds(
        JsonObject config,
        Phase2a0bContracts.MeasurementMode mode
    )
        throws IOException {
        if (mode == Phase2a0bContracts.MeasurementMode.REPLAY) {
            Phase2a0bContracts.SceneId value =
                Phase2a0bContracts.SceneId.parse(
                    requiredString(config, "sceneId")
                );
            if (value == Phase2a0bContracts.SceneId.UNKNOWN) {
                throw new IOException("unknown replay scene");
            }
            return new Phase2a0bContracts.SceneId[] {value};
        }
        JsonArray array = config.getAsJsonArray("sceneIds");
        if (array == null || array.isEmpty()) {
            throw new IOException("replay suite sceneIds missing");
        }
        Phase2a0bContracts.SceneId[] values =
            new Phase2a0bContracts.SceneId[array.size()];
        for (int index = 0; index < array.size(); index++) {
            values[index] = Phase2a0bContracts.SceneId.parse(
                array.get(index).getAsString()
            );
            if (values[index] == Phase2a0bContracts.SceneId.UNKNOWN) {
                throw new IOException("unknown replay suite scene");
            }
        }
        return values;
    }

    private static void requireExpectedSuite(
        Phase2a0bContracts.MeasurementMode mode,
        Phase2a0bContracts.SceneId[] sceneIds
    ) throws IOException {
        if (mode != Phase2a0bContracts.MeasurementMode.REPLAY_SUITE) {
            return;
        }
        Phase2a0bContracts.SceneId[] expected =
            Phase2a0bContracts.SceneId.requiredSuite();
        if (!java.util.Arrays.equals(expected, sceneIds)) {
            throw new IOException(
                "replay suite must contain the four pinned scenes in order"
            );
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        try (
            var reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
            )
        ) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("cannot parse " + path, error);
        }
    }

    private static String value(String value) {
        return value == null ? NOT_AVAILABLE : value;
    }

    private static String bounded(String value) {
        if (value == null) {
            return "unspecified";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 240
            ? normalized
            : normalized.substring(0, 240);
    }

    private static Phase2a0bRuntime disabled(String reason) {
        return new Phase2a0bRuntime(reason);
    }

    private static void publishUnreportedReadinessTransitions() {
        int transition;
        while (
            (
                transition =
                    READINESS.nextUnreportedTransitionBit()
            ) != 0
        ) {
            writeReadinessReceipt(
                RenderReadinessState.stateForTransitionBit(
                    transition
                )
            );
        }
    }

    private static void writeReadinessReceipt(
        RenderReadinessState.State state
    ) {
        if (bootstrapRunDirectory == null) {
            return;
        }
        try {
            JsonObject receipt = readinessJson(state);
            String prefix = switch (state) {
                case CLIENT_RENDER_CALLBACK_SEEN -> "render-heartbeat";
                case REPLAY_ARMED -> "replay-armed";
                default -> "readiness-" + state.name().toLowerCase(
                    java.util.Locale.ROOT
                );
            };
            writeNew(
                bootstrapRunDirectory.resolve(
                    prefix
                        + "-generation-"
                        + READINESS.generation()
                        + ".json"
                ),
                receipt
            );
            Phase2a0bHarnessMod.LOGGER.info(
                "Phase 2A.0B readiness transition: state={} "
                    + "generation={} mask={} callbacks={} threadId={}",
                state,
                READINESS.generation(),
                READINESS.readinessMask(),
                READINESS.totalCallbackCount(),
                READINESS.renderThreadId()
            );
        } catch (IOException error) {
            Phase2a0bHarnessMod.LOGGER.error(
                "Could not publish Phase 2A.0B readiness transition",
                error
            );
        }
    }

    private static void writeFinalReadinessReceipt() {
        if (bootstrapRunDirectory == null) {
            return;
        }
        try {
            JsonObject receipt = readinessJson(READINESS.state());
            ExternalReadinessDeadline.Status status =
                ExternalReadinessDeadline.classify(
                    new ExternalReadinessDeadline.Snapshot(
                        READINESS.worldLifecycleEverSeen(),
                        READINESS.totalCallbackCount(),
                        READINESS.readinessMask(),
                        READINESS.renderThreadId(),
                        READINESS.rejectedWrongThreadCallbacks(),
                        READINESS.ownerPublications()
                    )
                );
            receipt.addProperty(
                "externalDeadlineClassification",
                status.name()
            );
            receipt.addProperty(
                "cleanShutdownRequestedByExternalOwner",
                true
            );
            writeNew(
                bootstrapRunDirectory.resolve(
                    "render-readiness-final.json"
                ),
                receipt
            );
        } catch (IOException error) {
            Phase2a0bHarnessMod.LOGGER.error(
                "Could not publish final Phase 2A.0B readiness state",
                error
            );
        }
    }

    private static JsonObject readinessJson(
        RenderReadinessState.State state
    ) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schemaVersion", 1);
        receipt.addProperty("phase", "2A.0B");
        receipt.addProperty("runId", bootstrapRunId);
        receipt.addProperty("capturedAtUtc", Instant.now().toString());
        receipt.addProperty("state", state.name());
        receipt.addProperty("generation", READINESS.generation());
        receipt.addProperty(
            "renderCallbackCount",
            READINESS.totalCallbackCount()
        );
        receipt.addProperty(
            "generationRenderCallbackCount",
            READINESS.generationCallbackCount()
        );
        receipt.addProperty(
            "lastReadinessMask",
            READINESS.readinessMask()
        );
        receipt.addProperty(
            "ownerPublicationCount",
            READINESS.ownerPublications()
        );
        receipt.addProperty(
            "rejectedWrongThreadCallbacks",
            READINESS.rejectedWrongThreadCallbacks()
        );
        receipt.addProperty(
            "worldLifecycleSeen",
            READINESS.worldLifecycleEverSeen()
        );
        receipt.addProperty("callbackCounterPrimitive", true);
        receipt.addProperty("internalInitializationDeadline", false);
        receipt.addProperty("pollingOrWaitLoop", false);
        if (READINESS.renderThreadId() >= 0L) {
            receipt.addProperty(
                "renderThreadId",
                READINESS.renderThreadId()
            );
        }
        if (READINESS.firstCallbackNanos() >= 0L) {
            receipt.addProperty(
                "firstRenderCallbackNanos",
                READINESS.firstCallbackNanos()
            );
            receipt.addProperty(
                "firstRenderCallbackEpochMillis",
                READINESS.firstCallbackEpochMillis()
            );
        }
        return receipt;
    }

    private static void writeInitializationFailure(
        Path active,
        Throwable error
    ) {
        try {
            if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            JsonObject config = readObject(active);
            Path runDirectory = Path.of(
                requiredString(config, "runDirectory")
            );
            JsonObject failure = new JsonObject();
            failure.addProperty("schemaVersion", 1);
            failure.addProperty("phase", "2A.0B");
            failure.addProperty("state", "FAILED");
            failure.addProperty(
                "failureReason",
                error.getClass().getSimpleName()
                    + ":"
                    + bounded(error.getMessage())
            );
            failure.addProperty("replayOwnerCreated", false);
            failure.addProperty("samplerThread", false);
            failure.addProperty("fileWriterThread", false);
            failure.addProperty(
                "renderReadinessState",
                READINESS.state().name()
            );
            failure.addProperty(
                "renderReadinessGeneration",
                READINESS.generation()
            );
            failure.addProperty(
                "renderCallbackCount",
                READINESS.totalCallbackCount()
            );
            failure.addProperty(
                "lastReadinessMask",
                READINESS.readinessMask()
            );
            failure.addProperty(
                "renderThreadId",
                READINESS.renderThreadId()
            );
            failure.addProperty(
                "internalInitializationDeadline",
                false
            );
            writeNew(
                runDirectory.resolve("initialization-failure.json"),
                failure
            );
        } catch (Throwable ignored) {
            // Fail-closed diagnostics must not affect Minecraft startup.
        }
    }

    private void captureBenchmarkOptions() {
        if (this.optionsCaptured) {
            return;
        }
        this.previousVsync = this.minecraft.options.enableVsync().get();
        this.previousFramerateLimit =
            this.minecraft.options.framerateLimit().get();
        this.previousFov = this.minecraft.options.fov().get();
        this.previousHideGui = this.minecraft.gui.hud.isHidden();
        this.optionsCaptured = true;
    }

    private void restoreBenchmarkOptions() {
        if (!this.optionsCaptured || this.minecraft == null) {
            return;
        }
        this.optionsCaptured = false;
        this.minecraft.options.enableVsync().set(this.previousVsync);
        this.minecraft.options.framerateLimit().set(
            this.previousFramerateLimit
        );
        this.minecraft.options.fov().set(this.previousFov);
        setHudHidden(this.previousHideGui);
    }

    private static void validateNativeBaselineDisabled()
        throws IOException {
        try {
            Object mode = Class.forName(
                    "de.morau.nvidiadlss.DlssConfig"
                )
                .getMethod("mode")
                .invoke(null);
            if (!"OFF".equals(String.valueOf(mode))) {
                throw new IOException(
                    "native baseline requires DLSS/DLAA off"
                );
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IOException(
                "cannot verify DLSS/DLAA baseline state",
                error
            );
        }
    }

    private void setHudHidden(boolean hidden) {
        if (this.minecraft.gui.hud.isHidden() != hidden) {
            this.minecraft.gui.hud.toggle();
        }
    }

    private record RuntimeAttestation(
        String blockframeModId,
        String blockframeReleaseVersion,
        String blockframeMetadataVersion,
        String blockframeCodeSourceFilename,
        boolean blockframeCodeSourceInMods,
        String blockframeCodeSourceSha256,
        String neoForgeVersion,
        String minecraftVersion,
        String harnessVersion,
        String modListHash,
        String benchmarkStartProfileHash,
        String fixtureManifestHash,
        String sceneManifestHash
    ) {
    }
}
