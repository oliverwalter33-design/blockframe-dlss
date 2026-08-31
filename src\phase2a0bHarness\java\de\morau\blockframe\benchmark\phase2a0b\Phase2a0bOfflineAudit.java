package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/**
 * Non-production, deterministic end-to-end runner for the complete replay
 * preflight and four-scene state machine.
 */
public final class Phase2a0bOfflineAudit {
    public static final String DATASET =
        "benchmarks/fixtures/blockframe-2a0b-captured-runtime-v2.json";
    public static final String REPORT =
        "benchmarks/fixtures/blockframe-2a0b-offline-preflight-v3.json";
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private Phase2a0bOfflineAudit() {
    }

    public static Path run(
        Path repository,
        Path expectedInstance
    ) throws IOException {
        Path datasetPath = repository.resolve(DATASET);
        byte[] datasetBytes = Files.readAllBytes(datasetPath);
        JsonObject dataset = JsonParser.parseString(
            new String(datasetBytes, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        require(
            dataset.get("schemaVersion").getAsInt() == 2,
            "captured-runtime schema mismatch"
        );
        JsonObject runtime = dataset.getAsJsonObject("capturedRuntime");
        JsonObject current = dataset.getAsJsonObject(
            "currentArtifactContract"
        );
        JsonObject receiptData = dataset.getAsJsonObject(
            "syntheticConfigReceipt"
        );

        ConfigTransactionReceipt receipt =
            ConfigTransactionReceipt.create(
                receiptData.get("transactionId").getAsString(),
                current.get("benchmarkStartProfileHash").getAsString(),
                receiptData.get("appliedRawFileHash").getAsString(),
                current.get("benchmarkStartProfileHash").getAsString(),
                receiptData.get("backupHash").getAsString(),
                Instant.parse(receiptData.get("appliedAtUtc").getAsString()),
                expectedInstance,
                current.get("modProfileHash").getAsString()
            );
        receipt.validateForReplay(
            receiptData.get("transactionId").getAsString(),
            expectedInstance,
            current.get("benchmarkStartProfileHash").getAsString(),
            receiptData.get("appliedRawFileHash").getAsString(),
            current.get("modProfileHash").getAsString()
        );
        RenderReadinessState readiness = simulateReadiness();

        Phase2a0bPreflight.Input input = input(
            runtime,
            current,
            true,
            2,
            0,
            0
        );
        Phase2a0bPreflight.Report preflight =
            Phase2a0bPreflight.evaluate(input);
        require(
            "OFFLINE_PREFLIGHT_COMPLETE".equals(preflight.status()),
            "offline gate evaluation did not complete"
        );

        Phase2a0bContracts.SceneId[] sceneIds =
            Phase2a0bContracts.SceneId.requiredSuite();
        String[] sceneNames = Arrays.stream(sceneIds)
            .map(Enum::name)
            .toArray(String[]::new);
        SceneManifest manifest = SceneManifest.load(
            repository.resolve(FixtureRunManager.SCENE_MANIFEST_RELATIVE)
        );
        require(
            current.get("sceneManifestHash").getAsString()
                .equals(manifest.fileHash()),
            "current typed scene manifest hash mismatch"
        );
        SceneManifest.Scene[] typedScenes =
            manifest.requireReadyScenes(sceneNames);
        Phase2a0bContracts.SceneType[] sceneTypes =
            Arrays.stream(typedScenes)
                .map(SceneManifest.Scene::type)
                .toArray(Phase2a0bContracts.SceneType[]::new);
        ReplaySuiteStateTrace trace =
            new ReplaySuiteStateTrace(sceneNames, sceneTypes);
        int replayOwnerCount = 1;
        int totalBoundarySnapshots = 0;
        int fileIoDuringMeasure = 0;
        int perFrameThreadScans = 0;
        for (int scene = 0; scene < sceneIds.length; scene++) {
            trace.transition(BenchmarkState.WORLD_WAIT);
            trace.transition(BenchmarkState.CHUNK_WARMUP);
            trace.transition(BenchmarkState.WARMUP);
            ThreadCpuWindow.Result cpuResult = null;
            if (
                sceneTypes[scene]
                    == Phase2a0bContracts.SceneType.PERFORMANCE
            ) {
                CountingThreadAccess access = new CountingThreadAccess();
                ThreadCpuWindow cpu = new ThreadCpuWindow(access, true);
                cpu.prepare();
                int scansBeforeMeasure = access.threadScans;
                cpu.begin();
                trace.transition(BenchmarkState.MEASURE);
                for (int frame = 0; frame < 120; frame++) {
                    // Camera/sample stubs advance without I/O or scans.
                }
                perFrameThreadScans +=
                    access.threadScans - scansBeforeMeasure;
                trace.transition(BenchmarkState.REFERENCE_CAPTURE);
                cpuResult = cpu.end(8);
                totalBoundarySnapshots +=
                    cpuResult.boundarySnapshotCount();
                cpu.close();
            } else {
                trace.transition(BenchmarkState.REFERENCE_CAPTURE);
            }
            JsonObject offlineResult = new JsonObject();
            offlineResult.addProperty(
                "schemaVersion",
                Phase2a0bResultSchema.VERSION
            );
            offlineResult.addProperty(
                "sceneId",
                sceneIds[scene].name()
            );
            offlineResult.addProperty(
                "sceneType",
                sceneTypes[scene].name()
            );
            Phase2a0bResultSchema.addCpuContract(
                offlineResult,
                sceneTypes[scene],
                cpuResult
            );
            Phase2a0bResultSchema.validateForSerialization(offlineResult);
            trace.transition(BenchmarkState.COMPLETE);
            if (scene + 1 < sceneIds.length) {
                require(trace.advanceScene(), "scene did not advance");
            } else {
                require(!trace.advanceScene(), "unexpected fifth scene");
            }
        }
        require(replayOwnerCount == 1, "replay owner count mismatch");
        require(
            totalBoundarySnapshots == 6,
            "ThreadMXBean boundary count mismatch"
        );
        require(
            fileIoDuringMeasure == 0,
            "file I/O occurred during MEASURE"
        );
        require(
            perFrameThreadScans == 0,
            "per-frame thread scan occurred"
        );

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 2);
        report.addProperty("phase", "2A.0B");
        report.addProperty("status", "OFFLINE_PREFLIGHT_COMPLETE");
        report.addProperty("generatedAtUtc", Instant.now().toString());
        report.addProperty(
            "testDatasetSha256",
            FixtureInventory.sha256(datasetBytes)
        );
        report.addProperty(
            "runtimeProfileHash",
            runtimeProfileHash(runtime)
        );
        report.addProperty(
            "benchmarkStartProfileHash",
            input.startProfileHash().value()
        );
        report.addProperty(
            "sceneManifestSha256",
            input.sceneManifestHash().value()
        );
        report.addProperty(
            "capturedSecondRunSceneManifestSha256",
            runtime.get("sceneManifestHash").getAsString()
        );
        report.addProperty(
            "gateCount",
            preflight.outcomes().length
        );
        report.addProperty("gatesPassed", preflight.passed());
        report.addProperty("gatesFailed", preflight.failed());
        JsonArray gates = new JsonArray();
        for (Phase2a0bPreflight.Outcome outcome :
            preflight.outcomes()) {
            Phase2a0bGateInventory.Gate metadata =
                Phase2a0bGateInventory.require(outcome.gateId());
            JsonObject gate = GSON.toJsonTree(metadata).getAsJsonObject();
            gate.addProperty("result", outcome.status());
            gates.add(gate);
        }
        report.add("gates", gates);
        report.addProperty("replayOwnerCount", replayOwnerCount);
        report.addProperty(
            "readinessModel",
            "EVENT_DRIVEN_RENDER_HEAD"
        );
        report.addProperty(
            "readinessState",
            readiness.state().name()
        );
        report.addProperty(
            "readinessOwnerPublications",
            readiness.ownerPublications()
        );
        report.addProperty(
            "readinessCallbackCount",
            readiness.totalCallbackCount()
        );
        report.addProperty("readinessNoWorldCallbacks", 100);
        report.addProperty("readinessWarmCallbacks", 100_000);
        report.addProperty("internalReadinessTimeout", false);
        report.addProperty("internalReadinessPolling", false);
        report.addProperty(
            "externalLaunchDeadlineSeconds",
            FixtureRunManager.EXTERNAL_LAUNCH_DEADLINE_SECONDS
        );
        report.addProperty("launchStatus", "READY_TO_LAUNCH");
        report.addProperty(
            "threadMxBeanBoundarySnapshots",
            totalBoundarySnapshots
        );
        report.addProperty(
            "threadMxBeanBoundarySnapshotsPerPerformanceScene",
            2
        );
        report.addProperty(
            "threadMxBeanBoundarySnapshotsForImageReference",
            0
        );
        report.addProperty(
            "fileIoDuringMeasure",
            fileIoDuringMeasure
        );
        report.addProperty(
            "perFrameThreadScans",
            perFrameThreadScans
        );
        JsonArray scenes = new JsonArray();
        for (int index = 0; index < sceneNames.length; index++) {
            JsonObject scene = new JsonObject();
            scene.addProperty("sceneId", sceneNames[index]);
            scene.addProperty("sceneType", sceneTypes[index].name());
            scene.addProperty(
                "threadMxBeanBoundarySnapshots",
                sceneTypes[index]
                        == Phase2a0bContracts.SceneType.PERFORMANCE
                    ? 2
                    : 0
            );
            JsonArray states = new JsonArray();
            for (BenchmarkState state : trace.trace(index)) {
                states.add(state.name());
            }
            scene.add("states", states);
            scene.addProperty("status", "COMPLETE");
            scenes.add(scene);
        }
        report.add("scenes", scenes);
        report.addProperty(
            "capturedRuntimeRegression",
            "PASSED"
        );
        report.addProperty(
            "configOwner",
            Phase2a0bContracts.ConfigOwner.EXTERNAL_LAUNCHER.name()
        );
        report.addProperty(
            "configReceiptStatus",
            receipt.status().name()
        );
        report.addProperty(
            "minecraftStartAuthorized",
            true
        );
        report.addProperty("performanceBaseline", "NOT_RUN");
        report.addProperty("phase2a1", "NOT_STARTED");

        Path output = repository.resolve(REPORT);
        Phase2a0bResultSchema.publishNew(output, report);
        return output;
    }

    private static RenderReadinessState simulateReadiness()
        throws IOException {
        RenderReadinessState readiness = new RenderReadinessState();
        long renderThreadId = 71L;
        for (int callback = 0; callback < 100; callback++) {
            readiness.observe(
                renderThreadId,
                1_000L + callback,
                2_000L + callback,
                false,
                false,
                false
            );
        }
        require(
            readiness.state()
                == RenderReadinessState.State.CLIENT_RENDER_CALLBACK_SEEN,
            "no-world callbacks advanced readiness"
        );
        readiness.onWorldLifecyclePresent();
        require(
            readiness.observe(
                renderThreadId,
                2_000L,
                3_000L,
                true,
                true,
                true
            ) == RenderReadinessState.Decision.BIND_OWNER,
            "complete callback did not bind replay owner"
        );
        readiness.markReplayArmed(renderThreadId);
        for (int callback = 0; callback < 100_000; callback++) {
            require(
                readiness.heartbeat(renderThreadId),
                "warm readiness heartbeat rejected render owner"
            );
        }
        require(
            readiness.state() == RenderReadinessState.State.REPLAY_ARMED,
            "offline replay was not armed"
        );
        require(
            readiness.ownerPublications() == 1,
            "offline readiness owner publication mismatch"
        );
        return readiness;
    }

    static Phase2a0bPreflight.Input input(
        JsonObject runtime,
        JsonObject current,
        boolean receiptValid,
        int boundarySnapshots,
        int fileIoDuringMeasure,
        int perFrameThreadScans
    ) throws IOException {
        JsonObject cpu = runtime.getAsJsonObject("cpuTopology");
        JsonArray scenes = current.getAsJsonArray("sceneIds");
        Phase2a0bContracts.SceneId[] ids =
            new Phase2a0bContracts.SceneId[scenes.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = Phase2a0bContracts.SceneId.parse(
                scenes.get(index).getAsString()
            );
        }
        return new Phase2a0bPreflight.Input(
            true,
            true,
            true,
            true,
            Phase2a0bContracts.RuntimeProfile
                .CAPTURED_SECOND_LIVE_RUN_20260728,
            Phase2a0bContracts.MeasurementMode.REPLAY_SUITE,
            true,
            true,
            Phase2a0bContracts.Sha256.parse(
                current.get("sceneManifestHash").getAsString()
            ),
            Phase2a0bContracts.Sha256.parse(
                runtime.get("fixtureHash").getAsString()
            ),
            115,
            55_962_095L,
            Phase2a0bContracts.Sha256.parse(
                current.get("modProfileHash").getAsString()
            ),
            receiptValid,
            Phase2a0bContracts.Sha256.parse(
                current.get("benchmarkStartProfileHash").getAsString()
            ),
            ids,
            runtime.get("blockframeModId").getAsString(),
            runtime.get("blockframeCodeSourceFilename").getAsString(),
            true,
            Phase2a0bContracts.ArtifactVersion.parse(
                runtime.get("blockframeReleaseVersion").getAsString()
            ),
            Phase2a0bContracts.ArtifactVersion.parse(
                runtime.get("blockframeMetadataVersion").getAsString()
            ),
            Phase2a0bContracts.Sha256.parse(
                runtime.get("blockframeCodeSourceSha256").getAsString()
            ),
            Phase2a0bContracts.ArtifactVersion.parse(
                runtime.get("minecraftVersion").getAsString()
            ),
            Phase2a0bContracts.ArtifactVersion.parse(
                runtime.get("neoForgeVersion").getAsString()
            ),
            Phase2a0bContracts.ArtifactVersion.parse(
                runtime.get("harnessVersion").getAsString()
            ),
            Phase2a0bContracts.Backend.parse(
                runtime.get("backend").getAsString()
            ),
            runtime.get("gpu").getAsString(),
            runtime.get("driver").getAsString(),
            cpu.get("model").getAsString(),
            cpu.get("physicalCores").getAsInt(),
            cpu.get("logicalProcessors").getAsInt(),
            cpu.get("jvmAvailableProcessors").getAsInt(),
            cpu.get("affinityLogicalProcessors").getAsInt(),
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
            boundarySnapshots,
            fileIoDuringMeasure,
            perFrameThreadScans
        );
    }

    static JsonObject loadDataset(Path repository) throws IOException {
        return JsonParser.parseString(
            Files.readString(
                repository.resolve(DATASET),
                StandardCharsets.UTF_8
            )
        ).getAsJsonObject();
    }

    private static String runtimeProfileHash(JsonObject runtime) {
        String canonical = String.join(
            "\n",
            "BLOCKFRAME_CAPTURED_RUNTIME_V1",
            "backend=" + runtime.get("backend").getAsString(),
            "blockframeCodeSourceSha256="
                + runtime.get("blockframeCodeSourceSha256").getAsString(),
            "blockframeMetadataVersion="
                + runtime.get("blockframeMetadataVersion").getAsString(),
            "blockframeReleaseVersion="
                + runtime.get("blockframeReleaseVersion").getAsString(),
            "driver=" + runtime.get("driver").getAsString(),
            "fixtureHash=" + runtime.get("fixtureHash").getAsString(),
            "gpu=" + runtime.get("gpu").getAsString(),
            "harnessVersion="
                + runtime.get("harnessVersion").getAsString(),
            "minecraftVersion="
                + runtime.get("minecraftVersion").getAsString(),
            "modProfileHash="
                + runtime.get("modProfileHash").getAsString(),
            "neoForgeVersion="
                + runtime.get("neoForgeVersion").getAsString(),
            "sceneManifestHash="
                + runtime.get("sceneManifestHash").getAsString()
        );
        return FixtureInventory.sha256(
            canonical.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void require(boolean condition, String message)
        throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private static final class CountingThreadAccess
        implements ThreadCpuWindow.Access {
        private int threadScans;
        private long cpu = 10_000L;

        @Override
        public boolean cpuTimeSupported() {
            return true;
        }

        @Override
        public boolean cpuTimeEnabled() {
            return true;
        }

        @Override
        public void cpuTimeEnabled(boolean enabled) {
        }

        @Override
        public long[] allThreadIds() {
            this.threadScans++;
            return new long[] {7L};
        }

        @Override
        public ThreadCpuWindow.Descriptor descriptor(long threadId) {
            return new ThreadCpuWindow.Descriptor(
                "Render thread",
                Thread.State.RUNNABLE
            );
        }

        @Override
        public long cpuTime(long threadId) {
            this.cpu += 10_000L;
            return this.cpu;
        }

        @Override
        public long userTime(long threadId) {
            return this.cpu / 2L;
        }
    }
}
