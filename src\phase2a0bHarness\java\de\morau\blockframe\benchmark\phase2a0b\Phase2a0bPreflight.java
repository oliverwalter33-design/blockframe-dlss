package de.morau.blockframe.benchmark.phase2a0b;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Central, typed owner for Phase 2A.0B fail-closed gate evaluation. Live-only
 * facts are provided by the render harness; the offline audit supplies stubs
 * with the same values and drives every gate independently.
 */
public final class Phase2a0bPreflight {
    public static final String EXPECTED_RELEASE_VERSION = "0.3.14";
    public static final String EXPECTED_METADATA_VERSION =
        "0.3.14-neoforge-26.2";
    public static final String EXPECTED_JAR_HASH =
        "7e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5";
    public static final String EXPECTED_FIXTURE_HASH =
        "8218b992c5af65f2c86286e491e2f218f6d9bf4e00990566dfa53fc82ab39a10";
    public static final String EXPECTED_START_PROFILE_HASH =
        "3a78ec4a863e3e10c7bcf57179e57f2889953b0700681d49e6f2ece710e1f40d";
    public static final String EXPECTED_SCENE_MANIFEST_HASH =
        "b401331b195587da414c694fc549de22750b6411460fa634df7ee00b263c8f0f";
    public static final String EXPECTED_MOD_PROFILE_HASH =
        "5eaadfbd0f322e0645d6f9cb0cf5c1b4794f3b2a6b637953ace8bcac9cea78ad";

    public record Input(
        boolean initializationActiveRun,
        boolean initializationRenderThread,
        boolean initializationWorldContext,
        boolean activeRunContract,
        Phase2a0bContracts.RuntimeProfile runtimeProfile,
        Phase2a0bContracts.MeasurementMode measurementMode,
        boolean protectedWorldExcluded,
        boolean runPathsValid,
        Phase2a0bContracts.Sha256 sceneManifestHash,
        Phase2a0bContracts.Sha256 fixtureHash,
        int fixtureFiles,
        long fixtureBytes,
        Phase2a0bContracts.Sha256 modProfileHash,
        boolean configReceiptValid,
        Phase2a0bContracts.Sha256 startProfileHash,
        Phase2a0bContracts.SceneId[] sceneIds,
        String blockframeModId,
        String blockframeCodeSourceFilename,
        boolean blockframeCodeSourceInMods,
        Phase2a0bContracts.ArtifactVersion releaseVersion,
        Phase2a0bContracts.ArtifactVersion metadataVersion,
        Phase2a0bContracts.Sha256 jarHash,
        Phase2a0bContracts.ArtifactVersion minecraftVersion,
        Phase2a0bContracts.ArtifactVersion neoForgeVersion,
        Phase2a0bContracts.ArtifactVersion harnessVersion,
        Phase2a0bContracts.Backend backend,
        String gpu,
        String driver,
        String cpuModel,
        int physicalCores,
        int logicalProcessors,
        int jvmAvailableProcessors,
        int affinityLogicalProcessors,
        int replayOwnerCount,
        boolean loadedRunCopy,
        boolean dimension,
        boolean renderDistance,
        boolean simulationDistance,
        boolean framebuffer,
        boolean windowMode,
        boolean framePacing,
        boolean weather,
        boolean creativeCamera,
        boolean nativeBaselineOff,
        int threadCpuBoundarySnapshots,
        int fileIoDuringMeasure,
        int perFrameThreadScans
    ) {
        public Input {
            Objects.requireNonNull(measurementMode, "measurementMode");
            Objects.requireNonNull(runtimeProfile, "runtimeProfile");
            Objects.requireNonNull(sceneManifestHash, "sceneManifestHash");
            Objects.requireNonNull(fixtureHash, "fixtureHash");
            Objects.requireNonNull(modProfileHash, "modProfileHash");
            Objects.requireNonNull(startProfileHash, "startProfileHash");
            sceneIds = Objects.requireNonNull(sceneIds, "sceneIds").clone();
            Objects.requireNonNull(blockframeModId, "blockframeModId");
            Objects.requireNonNull(
                blockframeCodeSourceFilename,
                "blockframeCodeSourceFilename"
            );
            Objects.requireNonNull(releaseVersion, "releaseVersion");
            Objects.requireNonNull(metadataVersion, "metadataVersion");
            Objects.requireNonNull(jarHash, "jarHash");
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            Objects.requireNonNull(neoForgeVersion, "neoForgeVersion");
            Objects.requireNonNull(harnessVersion, "harnessVersion");
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(gpu, "gpu");
            Objects.requireNonNull(driver, "driver");
            Objects.requireNonNull(cpuModel, "cpuModel");
        }

        @Override
        public Phase2a0bContracts.SceneId[] sceneIds() {
            return this.sceneIds.clone();
        }
    }

    public record Outcome(
        Phase2a0bGateInventory.GateId gateId,
        boolean passed,
        String status
    ) {
    }

    public record Report(
        Outcome[] outcomes,
        int passed,
        int failed,
        String status
    ) {
        public Report {
            outcomes = outcomes.clone();
        }

        @Override
        public Outcome[] outcomes() {
            return this.outcomes.clone();
        }
    }

    private Phase2a0bPreflight() {
    }

    public static Report evaluate(Input input) {
        return evaluate(input, Map.of());
    }

    static Report evaluate(
        Input input,
        Map<Phase2a0bGateInventory.GateId, Boolean> overrides
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(overrides, "overrides");
        ArrayList<Outcome> outcomes = new ArrayList<>();
        int passed = 0;
        for (Phase2a0bGateInventory.Gate gate :
            Phase2a0bGateInventory.all()) {
            boolean result = overrides.getOrDefault(
                gate.id(),
                passes(gate.id(), input)
            );
            outcomes.add(
                new Outcome(
                    gate.id(),
                    result,
                    result ? "PASSED" : "FAILED_CLOSED"
                )
            );
            if (result) {
                passed++;
            }
        }
        int failed = outcomes.size() - passed;
        return new Report(
            outcomes.toArray(Outcome[]::new),
            passed,
            failed,
            failed == 0
                ? "OFFLINE_PREFLIGHT_COMPLETE"
                : "OFFLINE_PREFLIGHT_FAILED"
        );
    }

    public static void requirePreOwner(Input input) throws IOException {
        Report report = evaluate(input);
        for (Outcome outcome : report.outcomes()) {
            Phase2a0bGateInventory.Gate gate =
                Phase2a0bGateInventory.require(outcome.gateId());
            if (
                gate.timing().contains("pre-owner")
                    && !outcome.passed()
            ) {
                throw new IOException(
                    "preflight gate "
                        + outcome.gateId()
                        + " failed closed"
                );
            }
        }
    }

    private static boolean passes(
        Phase2a0bGateInventory.GateId gate,
        Input input
    ) {
        return switch (gate) {
            case INITIALIZATION_ACTIVE_RUN ->
                input.initializationActiveRun();
            case INITIALIZATION_RENDER_THREAD ->
                input.initializationRenderThread();
            case INITIALIZATION_WORLD_CONTEXT ->
                input.initializationWorldContext();
            case ACTIVE_RUN_CONTRACT ->
                input.activeRunContract()
                    && input.runtimeProfile()
                        == Phase2a0bContracts.RuntimeProfile
                            .CAPTURED_SECOND_LIVE_RUN_20260728;
            case REPLAY_MODE ->
                input.measurementMode()
                    == Phase2a0bContracts.MeasurementMode.REPLAY_SUITE;
            case PROTECTED_WORLD -> input.protectedWorldExcluded();
            case RUN_PATHS -> input.runPathsValid();
            case SCENE_MANIFEST_HASH ->
                EXPECTED_SCENE_MANIFEST_HASH.equals(
                    input.sceneManifestHash().value()
                );
            case FIXTURE_HASH ->
                EXPECTED_FIXTURE_HASH.equals(
                    input.fixtureHash().value()
                );
            case GOLDEN_INVENTORY ->
                input.fixtureFiles() == 115
                    && input.fixtureBytes() == 55_962_095L
                    && EXPECTED_FIXTURE_HASH.equals(
                        input.fixtureHash().value()
                    );
            case MOD_PROFILE_HASH ->
                EXPECTED_MOD_PROFILE_HASH.equals(
                    input.modProfileHash().value()
                );
            case CONFIG_RECEIPT -> input.configReceiptValid();
            case START_PROFILE_HASH ->
                EXPECTED_START_PROFILE_HASH.equals(
                    input.startProfileHash().value()
                );
            case SCENE_SET ->
                Arrays.equals(
                    Phase2a0bContracts.SceneId.requiredSuite(),
                    input.sceneIds()
                );
            case BLOCKFRAME_IDENTITY ->
                "voxellift".equals(input.blockframeModId())
                    && "blockframe-dlss-0.3.14-neoforge-26.2.jar"
                        .equals(input.blockframeCodeSourceFilename())
                    && input.blockframeCodeSourceInMods();
            case BLOCKFRAME_VERSION ->
                acceptsBlockframeVersion(
                    input.releaseVersion().value(),
                    input.metadataVersion().value()
                );
            case BLOCKFRAME_JAR_HASH ->
                EXPECTED_JAR_HASH.equals(input.jarHash().value());
            case LOADER_VERSIONS ->
                "26.2".equals(input.minecraftVersion().value())
                    && "26.2.0.23-beta".equals(
                        input.neoForgeVersion().value()
                    )
                    && "1.0.0".equals(input.harnessVersion().value());
            case BACKEND ->
                input.backend() == Phase2a0bContracts.Backend.VULKAN;
            case GPU -> "NVIDIA GeForce RTX 4090".equals(input.gpu());
            case DRIVER -> input.driver().contains("610.74");
            case CPU_TOPOLOGY ->
                input.cpuModel().contains("9800X3D")
                    && input.physicalCores() == 8
                    && input.logicalProcessors() == 16
                    && input.jvmAvailableProcessors() == 16;
            case CPU_AFFINITY ->
                input.affinityLogicalProcessors() == 16;
            case REPLAY_OWNER -> input.replayOwnerCount() == 1;
            case LOADED_RUN_COPY -> input.loadedRunCopy();
            case DIMENSION -> input.dimension();
            case RENDER_DISTANCE -> input.renderDistance();
            case SIMULATION_DISTANCE -> input.simulationDistance();
            case FRAMEBUFFER -> input.framebuffer();
            case WINDOW_MODE -> input.windowMode();
            case FRAME_PACING -> input.framePacing();
            case WEATHER -> input.weather();
            case CREATIVE_CAMERA -> input.creativeCamera();
            case NATIVE_BASELINE_OFF -> input.nativeBaselineOff();
            case THREAD_CPU_BOUNDARIES ->
                input.threadCpuBoundarySnapshots() == 2;
            case MEASURE_FILE_IO -> input.fileIoDuringMeasure() == 0;
            case MEASURE_THREAD_SCAN ->
                input.perFrameThreadScans() == 0;
        };
    }

    static EnumMap<Phase2a0bGateInventory.GateId, Boolean>
    failOnly(Phase2a0bGateInventory.GateId id) {
        EnumMap<Phase2a0bGateInventory.GateId, Boolean> overrides =
            new EnumMap<>(Phase2a0bGateInventory.GateId.class);
        overrides.put(id, false);
        return overrides;
    }

    static boolean acceptsBlockframeVersion(
        String release,
        String metadata
    ) {
        return EXPECTED_RELEASE_VERSION.equals(release)
            && EXPECTED_METADATA_VERSION.equals(metadata);
    }

    static boolean acceptsHash(
        Phase2a0bGateInventory.GateId gate,
        String raw
    ) throws IOException {
        String canonical = Phase2a0bContracts.Sha256.parse(raw).value();
        return switch (gate) {
            case BLOCKFRAME_JAR_HASH ->
                EXPECTED_JAR_HASH.equals(canonical);
            case FIXTURE_HASH ->
                EXPECTED_FIXTURE_HASH.equals(canonical);
            case MOD_PROFILE_HASH ->
                EXPECTED_MOD_PROFILE_HASH.equals(canonical);
            case START_PROFILE_HASH ->
                EXPECTED_START_PROFILE_HASH.equals(canonical);
            case SCENE_MANIFEST_HASH ->
                EXPECTED_SCENE_MANIFEST_HASH.equals(canonical);
            default -> throw new IllegalArgumentException(
                "gate has no pinned hash: " + gate
            );
        };
    }
}
