package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonStreamParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit command-line owner for verified, physical Phase 2A.0B run copies.
 * It never opens Minecraft, never mutates the Golden fixture and never
 * deletes a run copy.
 */
public final class FixtureRunManager {
    public static final String GOLDEN_DIRECTORY =
        "BlockFrame_DTC_Benchmark_v1";
    public static final String ACTIVE_WORLD_DIRECTORY = "Stadt Bau";
    public static final String GOLDEN_SHA256 =
        "8218b992c5af65f2c86286e491e2f218f6d9bf4e00990566dfa53fc82ab39a10";
    public static final String HARNESS_JAR_MARKER =
        "phase2a0b-harness-dev-only";
    public static final String SCENE_MANIFEST_RELATIVE =
        "benchmarks/fixtures/blockframe-dtc-scenes-v2.json";
    public static final long EXTERNAL_LAUNCH_DEADLINE_SECONDS = 900L;
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final DateTimeFormatter RUN_TIME =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private FixtureRunManager() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5) {
            throw new IllegalArgumentException(
                "usage: <create-config-profile|prepare-scout|prepare-replay|"
                    + "prepare-suite|verify-start-profile|finalize|"
                    + "finalize-suite|restore-config|raw-config-hash|"
                    + "audit|topology|offline-audit> "
                    + "<instance> <repository> [run-id] [scene-id]"
            );
        }
        String command = args[0];
        Path instance = Path.of(args[1]).toAbsolutePath().normalize();
        Path repository = Path.of(args[2]).toAbsolutePath().normalize();
        switch (command) {
            case "create-config-profile" -> {
                assertNoMinecraftClient();
                auditGoldenAndMods(instance, repository);
                BenchmarkConfigTransaction.ProfileCreation created =
                    BenchmarkConfigTransaction.createGoldenProfile(
                        instance,
                        repository
                    );
                System.out.println(
                    "profileDirectory=" + created.profileDirectory()
                );
                System.out.println(
                    "sourceBackup=" + created.sourceBackup()
                );
                System.out.println(
                    "sourceRawConfigHash="
                        + created.sourceRawConfigHash()
                );
                System.out.println(
                    "benchmarkStartProfileHash="
                        + created.benchmarkStartProfileHash()
                );
                System.out.println(
                    "physicalProfileInventoryHash="
                        + created.physicalProfileInventoryHash()
                );
            }
            case "prepare-scout" -> {
                String runId = args.length == 4
                    ? checkedRunId(args[3])
                    : "scout-"
                        + RUN_TIME.format(Instant.now())
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
                PreparedRun run = prepareScout(instance, repository, runId);
                System.out.println("runId=" + run.runId());
                System.out.println("runCopy=" + run.runCopy());
                System.out.println("runDirectory=" + run.runDirectory());
            }
            case "prepare-replay" -> {
                if (args.length != 5) {
                    throw new IllegalArgumentException(
                        "prepare-replay requires run-id and scene-id"
                    );
                }
                PreparedRun run = prepareReplay(
                    instance,
                    repository,
                    checkedRunId(args[3]),
                    args[4]
                );
                System.out.println("runId=" + run.runId());
                System.out.println("runCopy=" + run.runCopy());
                System.out.println("runDirectory=" + run.runDirectory());
            }
            case "prepare-suite" -> {
                if (args.length != 4) {
                    throw new IllegalArgumentException(
                        "prepare-suite requires run-id"
                    );
                }
                String runId = checkedRunId(args[3]);
                assertNoMinecraftClient();
                BenchmarkConfigTransaction.Applied transaction =
                    BenchmarkConfigTransaction.apply(
                        instance,
                        repository,
                        runId
                    );
                PreparedRun run = prepareSuite(
                    instance,
                    repository,
                    runId,
                    transaction
                );
                System.out.println("runId=" + run.runId());
                System.out.println("runCopy=" + run.runCopy());
                System.out.println(
                    "runDirectory=" + run.runDirectory()
                );
                System.out.println(
                    "configTransaction="
                        + transaction.transactionDirectory()
                );
                System.out.println("launchStatus=READY_TO_LAUNCH");
                System.out.println(
                    "externalLaunchDeadlineSeconds="
                        + EXTERNAL_LAUNCH_DEADLINE_SECONDS
                );
            }
            case "verify-start-profile" ->
                System.out.println(
                    GSON.toJson(
                        BenchmarkConfigTransaction.verifyApplied(
                            instance,
                            repository
                        )
                    )
                );
            case "finalize" -> {
                if (args.length != 4) {
                    throw new IllegalArgumentException(
                        "finalize requires an existing run-id"
                    );
                }
                Path result = finalizeRun(
                    instance,
                    checkedRunId(args[3])
                );
                System.out.println("result=" + result);
            }
            case "finalize-suite" -> {
                if (args.length != 4) {
                    throw new IllegalArgumentException(
                        "finalize-suite requires an existing run-id"
                    );
                }
                String runId = checkedRunId(args[3]);
                Path result = finalizeRun(instance, runId);
                BenchmarkConfigTransaction.Restored restored =
                    BenchmarkConfigTransaction.restore(
                        instance,
                        runId
                    );
                System.out.println("result=" + result);
                System.out.println(
                    "restoredTransaction="
                        + restored.transactionDirectory()
                );
                System.out.println(
                    "postRunSnapshot=" + restored.postRunSnapshot()
                );
                System.out.println(
                    "postRunRawConfigHash="
                        + restored.postRunRawConfigHash()
                );
                System.out.println(
                    "restoredRawConfigHash="
                        + restored.restoredRawConfigHash()
                );
                System.out.println(
                    "configDelta=" + restored.deltaManifest()
                );
            }
            case "restore-config" -> {
                if (args.length != 4) {
                    throw new IllegalArgumentException(
                        "restore-config requires an existing run-id"
                    );
                }
                BenchmarkConfigTransaction.Restored restored =
                    BenchmarkConfigTransaction.restore(
                        instance,
                        checkedRunId(args[3])
                    );
                System.out.println(
                    "restoredTransaction="
                        + restored.transactionDirectory()
                );
                System.out.println(
                    "postRunSnapshot=" + restored.postRunSnapshot()
                );
                System.out.println(
                    "postRunRawConfigHash="
                        + restored.postRunRawConfigHash()
                );
                System.out.println(
                    "restoredRawConfigHash="
                        + restored.restoredRawConfigHash()
                );
                System.out.println(
                    "configDelta=" + restored.deltaManifest()
                );
            }
            case "audit" -> {
                Audit audit = audit(instance, repository);
                System.out.println(GSON.toJson(audit));
            }
            case "raw-config-hash" ->
                System.out.println(
                    "rawConfigHash="
                        + BenchmarkConfigTransaction.rawConfigHash(instance)
                );
            case "topology" ->
                System.out.println(GSON.toJson(CpuTopologyProbe.detect()));
            case "offline-audit" ->
                System.out.println(
                    "offlineReport="
                        + Phase2a0bOfflineAudit.run(
                            repository,
                            instance
                        )
                );
            default -> throw new IllegalArgumentException(
                "unknown command: " + command
            );
        }
    }

    public static Audit audit(Path instance, Path repository)
        throws IOException {
        StaticAudit base = auditGoldenAndMods(instance, repository);
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(repository);
        BenchmarkConfigProfile.Verification profileVerification =
            profile.verifyConfiguration(instance);
        String rawConfigHash =
            BenchmarkConfigTransaction.rawConfigHash(instance);
        String sceneHash = sceneManifestHash(repository);
        return new Audit(
            base.fixtureFiles(),
            base.fixtureBytes(),
            base.fixtureSha256(),
            base.modFiles(),
            base.modHash(),
            configurationFiles(instance).size(),
            rawConfigHash,
            profileVerification.benchmarkStartProfileHash(),
            sceneHash
        );
    }

    /**
     * Runtime-safe subset: Minecraft may hold configuration files with
     * exclusive locks. The complete raw/config profile verification remains
     * a mandatory pre-process transaction gate.
     */
    public static RuntimeStaticAudit auditRuntimeStatic(
        Path instance,
        Path repository
    ) throws IOException {
        StaticAudit base = auditGoldenAndMods(instance, repository);
        return new RuntimeStaticAudit(
            base.fixtureFiles(),
            base.fixtureBytes(),
            base.fixtureSha256(),
            base.modFiles(),
            base.modHash()
        );
    }

    private static StaticAudit auditGoldenAndMods(
        Path instance,
        Path repository
    ) throws IOException {
        requireDirectory(instance, "instance");
        Path golden = instance.resolve("saves").resolve(GOLDEN_DIRECTORY);
        Path active = instance.resolve("saves")
            .resolve(ACTIVE_WORLD_DIRECTORY);
        if (golden.equals(active)) {
            throw new IOException("Golden and active construction world alias");
        }
        FixtureInventory inventory = FixtureInventory.scan(golden);
        verifyGolden(inventory);
        verifyLocalGoldenManifest(instance, inventory);
        JsonObject currentContract = readObject(
            repository.resolve(
                Phase2a0bOfflineAudit.DATASET
            )
        ).getAsJsonObject("currentArtifactContract");
        String expectedMod = currentContract
            .get("modProfileHash")
            .getAsString();
        String modHash = currentModHash(instance);
        if (!expectedMod.equals(modHash)) {
            throw new IOException(
                "mod hash mismatch: expected "
                    + expectedMod
                    + " actual "
                    + modHash
            );
        }
        return new StaticAudit(
            inventory.fileCount(),
            inventory.totalBytes(),
            inventory.canonicalSha256(),
            modFiles(instance).size(),
            modHash
        );
    }

    public static PreparedRun prepareScout(
        Path instance,
        Path repository,
        String runId
    ) throws IOException {
        return prepare(
            instance,
            repository,
            runId,
            "SCOUT",
            "BlockFrame_DTC_Scout_2A0B_",
            List.of(),
            null
        );
    }

    public static PreparedRun prepareReplay(
        Path instance,
        Path repository,
        String runId,
        String sceneId
    ) throws IOException {
        SceneManifest manifest = SceneManifest.load(
            repository.resolve(SCENE_MANIFEST_RELATIVE)
        );
        manifest.requireReadyScene(sceneId);
        return prepare(
            instance,
            repository,
            runId,
            "REPLAY",
            "BlockFrame_DTC_Replay_2A0B_",
            List.of(sceneId),
            null
        );
    }

    public static PreparedRun prepareSuite(
        Path instance,
        Path repository,
        String runId,
        BenchmarkConfigTransaction.Applied transaction
    ) throws IOException {
        SceneManifest manifest = SceneManifest.load(
            repository.resolve(SCENE_MANIFEST_RELATIVE)
        );
        List<String> sceneIds = List.of(
            "DTC_DENSE_STATIC",
            "DTC_POI_SWEEP",
            "DTC_CHUNK_TRAVERSE",
            "DTC_IMAGE_REFERENCE"
        );
        for (String sceneId : sceneIds) {
            manifest.requireReadyScene(sceneId);
        }
        return prepare(
            instance,
            repository,
            runId,
            "REPLAY_SUITE",
            "BlockFrame_DTC_ReplaySuite_2A0B_",
            sceneIds,
            transaction
        );
    }

    private static PreparedRun prepare(
        Path instance,
        Path repository,
        String runId,
        String mode,
        String copyPrefix,
        List<String> sceneIds,
        BenchmarkConfigTransaction.Applied transaction
    ) throws IOException {
        assertNoMinecraftClient();
        Audit audit = audit(instance, repository);
        Path saves = instance.resolve("saves");
        Path golden = saves.resolve(GOLDEN_DIRECTORY);
        Path runCopy = saves.resolve(
            copyPrefix + checkedRunId(runId)
        );
        Path temporary = saves.resolve(
            "."
                + runCopy.getFileName()
                + ".copying."
                + UUID.randomUUID()
        );
        Path runDirectory = instance.resolve("benchmark-2a0b")
            .resolve("runs")
            .resolve(runId);
        Path activeManifest = instance.resolve("benchmark-2a0b")
            .resolve("active-run.json");
        if (
            Files.exists(runCopy, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(runDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(activeManifest, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw new IOException(
                "run target, run manifest or active-run already exists"
            );
        }
        FixtureInventory sourceBefore = FixtureInventory.scan(golden);
        verifyGolden(sourceBefore);
        FixtureInventory.copyPhysical(
            golden,
            temporary,
            runCopy,
            sourceBefore,
            null
        );
        FixtureInventory copy = FixtureInventory.scan(runCopy);
        FixtureInventory sourceAfter = FixtureInventory.scan(golden);
        if (
            !sourceBefore.contentEquals(copy)
                || !sourceBefore.contentEquals(sourceAfter)
        ) {
            throw new IOException(
                "source/copy/source verification failed; run copy preserved"
            );
        }
        Files.createDirectories(runDirectory);
        writeInventory(
            runDirectory.resolve("initial-inventory.jsonl"),
            copy
        );
        JsonObject run = new JsonObject();
        run.addProperty("schemaVersion", 1);
        run.addProperty("phase", "2A.0B");
        run.addProperty("mode", mode);
        run.addProperty("status", "PREPARED");
        run.addProperty("runId", runId);
        run.addProperty("runCopyDirectoryName", runCopy.getFileName().toString());
        run.addProperty("fixtureFiles", audit.fixtureFiles());
        run.addProperty("fixtureBytes", audit.fixtureBytes());
        run.addProperty("fixtureSha256", audit.fixtureSha256());
        run.addProperty("modFiles", audit.modFiles());
        run.addProperty("modHash", audit.modHash());
        run.addProperty("rawConfigHash", audit.rawConfigHash());
        run.addProperty(
            "benchmarkStartProfileHash",
            audit.benchmarkStartProfileHash()
        );
        run.addProperty("sceneHash", audit.sceneHash());
        run.addProperty("preparedAtUtc", Instant.now().toString());
        run.addProperty("cpuTopology", "PENDING_BENCHMARK_PROCESS");
        run.addProperty("goldenOpenedByMinecraft", false);
        run.addProperty("activeConstructionWorldOpened", false);
        run.addProperty("autoDeleteRunCopy", false);
        run.addProperty("launchStatus", "READY_TO_LAUNCH");
        run.addProperty(
            "externalLaunchDeadlineSeconds",
            EXTERNAL_LAUNCH_DEADLINE_SECONDS
        );
        run.addProperty("externalDeadlineOwner", "EXTERNAL_LAUNCHER");
        run.addProperty(
            "worldSelectionOwner",
            "EXTERNAL_LAUNCHER_OR_COMPUTER_USE"
        );
        run.addProperty(
            "expectedRenderHeartbeat",
            "render-heartbeat-generation-1.json"
        );
        run.addProperty(
            "expectedReplayArmedReceipt",
            "replay-armed-generation-1.json"
        );
        run.addProperty(
            "measurementRequested",
            mode.startsWith("REPLAY")
        );
        JsonArray scenes = new JsonArray();
        for (String sceneId : sceneIds) {
            scenes.add(sceneId);
        }
        run.add("sceneIds", scenes);
        if (sceneIds.size() == 1) {
            run.addProperty("sceneId", sceneIds.get(0));
        }
        if (transaction != null) {
            run.addProperty(
                "configTransaction",
                transaction.transactionDirectory().toString()
            );
            run.addProperty(
                "configTransactionReceipt",
                transaction.receipt().toString()
            );
            run.addProperty(
                "configTransactionReceiptContentHash",
                transaction.receiptContentHash()
            );
            run.addProperty(
                "preRunRawConfigHash",
                transaction.preRunRawConfigHash()
            );
            run.addProperty(
                "appliedRawConfigHash",
                transaction.appliedRawConfigHash()
            );
            run.addProperty(
                "transactionModProfileHash",
                transaction.modProfileHash()
            );
        }
        writeObject(runDirectory.resolve("run-manifest.json"), run);
        JsonObject activeRun = run.deepCopy();
        activeRun.addProperty(
            "runDirectory",
            runDirectory.toString()
        );
        activeRun.addProperty(
            "runCopy",
            runCopy.toString()
        );
        activeRun.addProperty(
            "scenesManifest",
            repository.resolve(SCENE_MANIFEST_RELATIVE).toString()
        );
        writeObject(activeManifest, activeRun);
        return new PreparedRun(runId, runCopy, runDirectory);
    }

    public static Path finalizeRun(Path instance, String runId)
        throws IOException {
        assertNoMinecraftClient();
        Path root = instance.resolve("benchmark-2a0b");
        Path runDirectory = root.resolve("runs").resolve(runId);
        JsonObject run = readObject(
            runDirectory.resolve("run-manifest.json")
        );
        if (!runId.equals(run.get("runId").getAsString())) {
            throw new IOException("run-id mismatch in run manifest");
        }
        String mode = run.get("mode").getAsString();
        Path runCopy = instance.resolve("saves").resolve(
            run.get("runCopyDirectoryName").getAsString()
        );
        FixtureInventory initial = readInventory(
            runDirectory.resolve("initial-inventory.jsonl")
        );
        FixtureInventory current = FixtureInventory.scan(runCopy);
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("runId", runId);
        result.addProperty(
            "status",
            "SCOUT".equals(mode)
                ? "SCOUT_COMPLETED"
                : "REPLAY_FINALIZED"
        );
        result.addProperty("mode", mode);
        result.addProperty("completedAtUtc", Instant.now().toString());
        result.addProperty("runCopyPreserved", true);
        result.addProperty("runCopyDirectoryName", runCopy.getFileName().toString());
        result.addProperty(
            "initialCanonicalSha256",
            initial.canonicalSha256()
        );
        result.addProperty(
            "finalCanonicalSha256",
            current.canonicalSha256()
        );
        JsonArray added = new JsonArray();
        JsonArray deleted = new JsonArray();
        JsonArray changed = new JsonArray();
        diff(initial, current, added, deleted, changed);
        result.add("addedFiles", added);
        result.add("deletedFiles", deleted);
        result.add("changedFiles", changed);
        Path suiteResult = runDirectory.resolve("suite-result.json");
        if (Files.isRegularFile(suiteResult, LinkOption.NOFOLLOW_LINKS)) {
            JsonObject live = readObject(suiteResult);
            result.addProperty(
                "liveHarnessState",
                live.get("state").getAsString()
            );
            result.addProperty(
                "liveScenesCompleted",
                live.get("scenesCompleted").getAsInt()
            );
        } else if ("REPLAY_SUITE".equals(mode)) {
            result.addProperty(
                "liveHarnessState",
                "NOT_AVAILABLE: suite-result.json missing"
            );
            result.addProperty("liveScenesCompleted", 0);
        }
        result.addProperty(
            "worldChangesContract",
            "Minecraft save/session metadata may change; the harness never "
                + "intentionally changes blocks or other fixture content"
        );
        Path resultFile = runDirectory.resolve("run-result.json");
        writeObject(resultFile, result);
        Path active = root.resolve("active-run.json");
        if (Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
            JsonObject activeJson = readObject(active);
            if (runId.equals(activeJson.get("runId").getAsString())) {
                Files.delete(active);
            }
        }
        return resultFile;
    }

    static String instanceFingerprint(Path root, List<Path> files)
        throws IOException {
        ArrayList<String> lines = new ArrayList<>(files.size());
        Path normalizedRoot = root.toAbsolutePath().normalize();
        for (Path file : files) {
            String relative = normalizedRoot.relativize(
                    file.toAbsolutePath().normalize()
                )
                .toString()
                .replace('\\', '/');
            lines.add(
                relative
                    + "\t"
                    + Files.size(file)
                    + "\t"
                    + FixtureInventory.sha256(file)
            );
        }
        lines.sort(String::compareTo);
        return FixtureInventory.sha256(
            String.join("\n", lines).getBytes(StandardCharsets.UTF_8)
        );
    }

    static String currentModHash(Path instance) throws IOException {
        return instanceFingerprint(
            instance.resolve("mods"),
            modFiles(instance)
        );
    }

    private static void verifyGolden(FixtureInventory inventory)
        throws IOException {
        if (
            inventory.fileCount() != 115
                || inventory.totalBytes() != 55_962_095L
                || !GOLDEN_SHA256.equals(inventory.canonicalSha256())
        ) {
            throw new IOException(
                "Golden fixture mismatch: files="
                    + inventory.fileCount()
                    + " bytes="
                    + inventory.totalBytes()
                    + " hash="
                    + inventory.canonicalSha256()
            );
        }
    }

    private static void verifyLocalGoldenManifest(
        Path instance,
        FixtureInventory inventory
    ) throws IOException {
        Path manifest = instance.resolve("saves")
            .resolve(GOLDEN_DIRECTORY + ".fixture-manifest.jsonl");
        FixtureInventory local = readInventory(manifest);
        if (!inventory.contentEquals(local)) {
            throw new IOException(
                "Golden local verification manifest mismatch"
            );
        }
    }

    private static List<Path> modFiles(Path instance) throws IOException {
        Path mods = instance.resolve("mods");
        try (var stream = Files.list(mods)) {
            return stream.filter(
                    path ->
                        Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                        )
                        && !path.getFileName()
                            .toString()
                            .contains(HARNESS_JAR_MARKER)
                )
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private static List<Path> configurationFiles(Path instance)
        throws IOException {
        ArrayList<Path> files = new ArrayList<>();
        collectRegularFiles(instance.resolve("config"), files);
        collectRegularFiles(instance.resolve("defaultconfigs"), files);
        for (
            String name : List.of(
                "options.txt",
                "user_jvm_args.txt",
                "user_jvm_args"
            )
        ) {
            Path path = instance.resolve(name);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                files.add(path);
            }
        }
        return files;
    }

    private static void collectRegularFiles(Path root, List<Path> output)
        throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException(
                        "configuration link rejected: " + path
                    );
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    output.add(path);
                }
            }
        }
    }

    private static String sceneManifestHash(Path repository)
        throws IOException {
        Path scenes = repository.resolve(
            SCENE_MANIFEST_RELATIVE
        );
        return Files.isRegularFile(scenes, LinkOption.NOFOLLOW_LINKS)
            ? FixtureInventory.sha256(scenes)
            : "NOT_AVAILABLE: scout manifest not yet created";
    }

    private static void assertNoMinecraftClient() throws IOException {
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            ProcessHandle.Info info = process.info();
            String command = info.commandLine().orElse("");
            if (
                command.contains("net.neoforged.fml.startup.Client")
                    || command.contains("net.minecraft.client.main.Main")
            ) {
                throw new IOException(
                    "Minecraft client process is still running: "
                        + process.pid()
                );
            }
        }
    }

    private static void diff(
        FixtureInventory initial,
        FixtureInventory current,
        JsonArray added,
        JsonArray deleted,
        JsonArray changed
    ) {
        Map<String, FixtureInventory.Entry> before = new HashMap<>();
        for (FixtureInventory.Entry entry : initial.entries()) {
            before.put(entry.path(), entry);
        }
        Map<String, FixtureInventory.Entry> after = new HashMap<>();
        for (FixtureInventory.Entry entry : current.entries()) {
            after.put(entry.path(), entry);
        }
        for (FixtureInventory.Entry entry : current.entries()) {
            FixtureInventory.Entry previous = before.get(entry.path());
            if (previous == null) {
                added.add(entry.path());
            } else if (!previous.equals(entry)) {
                changed.add(entry.path());
            }
        }
        for (FixtureInventory.Entry entry : initial.entries()) {
            if (!after.containsKey(entry.path())) {
                deleted.add(entry.path());
            }
        }
    }

    static void writeInventory(
        Path path,
        FixtureInventory inventory
    ) throws IOException {
        try (
            BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        ) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("recordType", "metadata");
            metadata.addProperty("schemaVersion", 1);
            metadata.addProperty("fileCount", inventory.fileCount());
            metadata.addProperty("totalBytes", inventory.totalBytes());
            metadata.addProperty(
                "canonicalManifestHash",
                inventory.canonicalSha256()
            );
            writer.write(GSON.toJson(metadata));
            writer.newLine();
            for (FixtureInventory.Entry entry : inventory.entries()) {
                JsonObject file = new JsonObject();
                file.addProperty("recordType", "file");
                file.addProperty("path", entry.path());
                file.addProperty("size", entry.size());
                file.addProperty("sha256", entry.sha256());
                writer.write(GSON.toJson(file));
                writer.newLine();
            }
        }
    }

    static FixtureInventory readInventory(Path path)
        throws IOException {
        ArrayList<FixtureInventory.Entry> entries = new ArrayList<>();
        long expectedBytes = -1L;
        int expectedCount = -1;
        String expectedHash = null;
        try (
            BufferedReader reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
            )
        ) {
            JsonStreamParser parser = new JsonStreamParser(reader);
            while (parser.hasNext()) {
                JsonObject object = parser.next().getAsJsonObject();
                String type = object.get("recordType").getAsString();
                if ("metadata".equals(type)) {
                    expectedCount = object.get("fileCount").getAsInt();
                    expectedBytes = object.get("totalBytes").getAsLong();
                    expectedHash = object.get("canonicalManifestHash")
                        .getAsString();
                } else if ("file".equals(type)) {
                    entries.add(
                        new FixtureInventory.Entry(
                            object.get("path").getAsString(),
                            object.get("size").getAsLong(),
                            object.get("sha256").getAsString()
                        )
                    );
                }
            }
        }
        entries.sort(Comparator.comparing(FixtureInventory.Entry::path));
        String canonical = entries.stream()
            .map(FixtureInventory.Entry::canonicalLine)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        long bytes = entries.stream()
            .mapToLong(FixtureInventory.Entry::size)
            .sum();
        String hash = FixtureInventory.sha256(
            canonical.getBytes(StandardCharsets.UTF_8)
        );
        if (
            entries.size() != expectedCount
                || bytes != expectedBytes
                || !hash.equals(expectedHash)
        ) {
            throw new IOException("invalid local inventory manifest: " + path);
        }
        return inventoryFromEntries(entries, bytes, hash);
    }

    private static FixtureInventory inventoryFromEntries(
        List<FixtureInventory.Entry> entries,
        long bytes,
        String hash
    ) {
        return FixtureInventory.verified(entries, bytes, hash);
    }

    private static JsonObject readObject(Path path) throws IOException {
        try (
            var reader = Files.newBufferedReader(
                path,
                StandardCharsets.UTF_8
            )
        ) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void writeObject(Path path, JsonObject object)
        throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (
            var writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        ) {
            GSON.toJson(object, writer);
        }
    }

    private static void requireDirectory(Path path, String label)
        throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " directory unavailable: " + path);
        }
    }

    private static String checkedRunId(String runId) {
        if (
            runId == null
                || !runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")
        ) {
            throw new IllegalArgumentException("invalid run-id");
        }
        return runId;
    }

    public record Audit(
        int fixtureFiles,
        long fixtureBytes,
        String fixtureSha256,
        int modFiles,
        String modHash,
        int configFiles,
        String rawConfigHash,
        String benchmarkStartProfileHash,
        String sceneHash
    ) {
    }

    public record RuntimeStaticAudit(
        int fixtureFiles,
        long fixtureBytes,
        String fixtureSha256,
        int modFiles,
        String modHash
    ) {
    }

    private record StaticAudit(
        int fixtureFiles,
        long fixtureBytes,
        String fixtureSha256,
        int modFiles,
        String modHash
    ) {
    }

    public record PreparedRun(
        String runId,
        Path runCopy,
        Path runDirectory
    ) {
    }
}
