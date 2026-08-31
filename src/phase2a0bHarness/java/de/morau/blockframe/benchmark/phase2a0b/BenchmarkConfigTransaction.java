package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Physical, journaled benchmark-config apply/restore owner. It keeps every
 * backup and displaced state and performs no recursive delete.
 */
public final class BenchmarkConfigTransaction {
    public interface Checkpoint {
        void afterInstalled(int installedEntryCount, String relative)
            throws IOException;
    }

    public record ProfileCreation(
        Path profileDirectory,
        Path sourceBackup,
        String sourceRawConfigHash,
        String benchmarkStartProfileHash,
        String physicalProfileInventoryHash
    ) {
    }

    public record Applied(
        Path transactionDirectory,
        Path preRunBackup,
        String preRunRawConfigHash,
        String appliedRawConfigHash,
        String benchmarkStartProfileHash,
        Path receipt,
        String receiptContentHash,
        String modProfileHash
    ) {
    }

    public record Restored(
        Path transactionDirectory,
        Path postRunSnapshot,
        String postRunRawConfigHash,
        String restoredRawConfigHash,
        String deltaManifest
    ) {
    }

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final List<String> MANAGED_ENTRIES = List.of(
        "config",
        "defaultconfigs",
        "options.txt",
        "user_jvm_args.txt",
        "user_jvm_args"
    );

    private BenchmarkConfigTransaction() {
    }

    public static ProfileCreation createGoldenProfile(
        Path instance,
        Path repository
    ) throws IOException {
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(repository);
        Path absoluteInstance = instance.toAbsolutePath().normalize();
        Path profileDirectory = absoluteInstance.resolve(
            BenchmarkConfigProfile.PROFILE_DIRECTORY
        );
        if (Files.exists(profileDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "benchmark config profile already exists: "
                    + profileDirectory
            );
        }
        Path backupRoot = absoluteInstance.resolve(
            "benchmark-2a0b/config-backups"
        );
        Files.createDirectories(backupRoot);
        String backupId =
            "pre-profile-"
                + Instant.now().toString().replace(':', '-')
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path backupDirectory = backupRoot.resolve(backupId);
        Files.createDirectory(backupDirectory);
        Path sourceBackup = backupDirectory.resolve("raw-config");
        FixtureInventory source = copyManagedSnapshot(
            absoluteInstance,
            sourceBackup
        );
        JsonObject sourceManifest = new JsonObject();
        sourceManifest.addProperty("schemaVersion", 1);
        sourceManifest.addProperty(
            "status",
            "PRESERVED_BEFORE_PROFILE_CREATION"
        );
        sourceManifest.addProperty(
            "capturedAtUtc",
            Instant.now().toString()
        );
        sourceManifest.addProperty(
            "rawConfigHash",
            source.canonicalSha256()
        );
        sourceManifest.addProperty("fileCount", source.fileCount());
        sourceManifest.addProperty("totalBytes", source.totalBytes());
        sourceManifest.addProperty("autoDelete", false);
        writeNew(
            backupDirectory.resolve("backup-manifest.json"),
            sourceManifest
        );

        Path temporary = absoluteInstance.resolve(
            "."
                + BenchmarkConfigProfile.PROFILE_DIRECTORY
                + ".creating."
                + UUID.randomUUID()
        );
        Files.createDirectory(temporary);
        Files.write(
            temporary.resolve("benchmark-start-profile.json"),
            profile.manifestBytes(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        Files.writeString(
            temporary.resolve("canonical-projection.txt"),
            profile.canonicalProjection(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
        FixtureInventory profileInventory =
            FixtureInventory.scan(temporary);
        atomicMove(temporary, profileDirectory);

        Path localProfileManifests = absoluteInstance.resolve(
            "benchmark-2a0b/config-profiles"
        );
        Files.createDirectories(localProfileManifests);
        JsonObject local = new JsonObject();
        local.addProperty("schemaVersion", 1);
        local.addProperty(
            "profileId",
            BenchmarkConfigProfile.PROFILE_DIRECTORY
        );
        local.addProperty("status", "IMMUTABLE_LOCAL_PROFILE");
        local.addProperty(
            "profileDirectory",
            profileDirectory.toString()
        );
        local.addProperty(
            "benchmarkStartProfileHash",
            profile.benchmarkStartProfileHash()
        );
        local.addProperty(
            "physicalProfileInventoryHash",
            profileInventory.canonicalSha256()
        );
        local.addProperty(
            "physicalProfileFileCount",
            profileInventory.fileCount()
        );
        local.addProperty(
            "physicalProfileBytes",
            profileInventory.totalBytes()
        );
        local.addProperty(
            "sourceBackup",
            sourceBackup.toString()
        );
        local.addProperty(
            "sourceRawConfigHash",
            source.canonicalSha256()
        );
        local.addProperty("gameMayWriteProfile", false);
        local.addProperty("autoDelete", false);
        writeNew(
            localProfileManifests.resolve(
                BenchmarkConfigProfile.PROFILE_DIRECTORY
                    + ".manifest.json"
            ),
            local
        );
        makeFilesReadOnly(profileDirectory);
        return new ProfileCreation(
            profileDirectory,
            sourceBackup,
            source.canonicalSha256(),
            profile.benchmarkStartProfileHash(),
            profileInventory.canonicalSha256()
        );
    }

    public static Applied apply(
        Path instance,
        Path repository,
        String runId
    ) throws IOException {
        return apply(instance, repository, runId, null);
    }

    static Applied apply(
        Path instance,
        Path repository,
        String runId,
        Checkpoint checkpoint
    ) throws IOException {
        return apply(
            instance,
            repository,
            runId,
            checkpoint,
            null
        );
    }

    static Applied apply(
        Path instance,
        Path repository,
        String runId,
        Checkpoint checkpoint,
        String actualModHashOverride
    ) throws IOException {
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(repository);
        Path absoluteInstance = instance.toAbsolutePath().normalize();
        verifyPhysicalProfile(absoluteInstance, profile);
        Path transaction = absoluteInstance.resolve(
            "benchmark-2a0b/config-transactions"
        ).resolve(runId);
        if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "config transaction already exists: " + transaction
            );
        }
        Files.createDirectories(transaction);
        Path backup = transaction.resolve("pre-run-backup");
        FixtureInventory preRun = copyManagedSnapshot(
            absoluteInstance,
            backup
        );
        Path staging = transaction.resolve("applied-staging");
        copyManagedSnapshot(backup, staging);
        profile.applyToSnapshot(staging);
        profile.verifyProjectedSnapshot(staging);
        FixtureInventory applied = FixtureInventory.scan(staging);

        JsonObject journal = transactionJournal(
            runId,
            "BACKUP_VERIFIED",
            preRun.canonicalSha256(),
            applied.canonicalSha256(),
            profile.benchmarkStartProfileHash()
        );
        Path journalPath = transaction.resolve("transaction.json");
        writeReplacing(journalPath, journal);
        try {
            swapManaged(
                absoluteInstance,
                staging,
                transaction.resolve("displaced-pre-run"),
                transaction.resolve("failed-applied-state"),
                checkpoint
            );
            String modHash = actualModHashOverride == null
                ? FixtureRunManager.currentModHash(absoluteInstance)
                : actualModHashOverride;
            if (actualModHashOverride == null) {
                profile.verifyConfiguration(absoluteInstance);
            } else {
                profile.verifyInstance(absoluteInstance, modHash);
            }
            String observed = rawConfigHash(absoluteInstance);
            if (!applied.canonicalSha256().equals(observed)) {
                throw new IOException(
                    "applied raw config hash mismatch: expected "
                        + applied.canonicalSha256()
                        + " actual "
                        + observed
                );
            }
            Instant appliedAt = Instant.now();
            ConfigTransactionReceipt receipt =
                ConfigTransactionReceipt.create(
                    runId,
                    profile.benchmarkStartProfileHash(),
                    observed,
                    profile.benchmarkStartProfileHash(),
                    preRun.canonicalSha256(),
                    appliedAt,
                    absoluteInstance,
                    modHash
                );
            Path receiptPath = transaction.resolve(
                "config-transaction-receipt.json"
            );
            receipt.writeImmutable(receiptPath);
            journal.addProperty("status", "APPLIED_VERIFIED");
            journal.addProperty(
                "appliedAtUtc",
                appliedAt.toString()
            );
            journal.addProperty(
                "receipt",
                receiptPath.toString()
            );
            journal.addProperty(
                "receiptContentHash",
                receipt.receiptContentHash().value()
            );
            journal.addProperty(
                "modProfileHash",
                modHash
            );
            writeReplacing(journalPath, journal);
            return new Applied(
                transaction,
                backup,
                preRun.canonicalSha256(),
                observed,
                profile.benchmarkStartProfileHash(),
                receiptPath,
                receipt.receiptContentHash().value(),
                modHash
            );
        } catch (IOException | RuntimeException error) {
            journal.addProperty("status", "APPLY_FAILED");
            journal.addProperty(
                "error",
                bounded(error.getMessage())
            );
            writeReplacing(journalPath, journal);
            throw error;
        }
    }

    public static BenchmarkConfigProfile.Verification verifyApplied(
        Path instance,
        Path repository
    ) throws IOException {
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(repository);
        verifyPhysicalProfile(instance.toAbsolutePath().normalize(), profile);
        return profile.verifyConfiguration(instance);
    }

    public static Restored restore(
        Path instance,
        String runId
    ) throws IOException {
        return restore(instance, runId, null);
    }

    static Restored restore(
        Path instance,
        String runId,
        Checkpoint checkpoint
    ) throws IOException {
        Path absoluteInstance = instance.toAbsolutePath().normalize();
        Path transaction = absoluteInstance.resolve(
            "benchmark-2a0b/config-transactions"
        ).resolve(runId);
        Path journalPath = transaction.resolve("transaction.json");
        JsonObject journal = readObject(journalPath);
        String status = journal.get("status").getAsString();
        if (!"APPLIED_VERIFIED".equals(status)) {
            throw new IOException(
                "config transaction is not restorable from state " + status
            );
        }
        String expectedPreRun = journal.get("preRunRawConfigHash")
            .getAsString();
        Path backup = transaction.resolve("pre-run-backup");
        FixtureInventory backupInventory = FixtureInventory.scan(backup);
        if (!expectedPreRun.equals(backupInventory.canonicalSha256())) {
            throw new IOException(
                "pre-run backup hash mismatch; restore blocked"
            );
        }
        Path postRun = transaction.resolve("post-run-snapshot");
        FixtureInventory current = copyManagedSnapshot(
            absoluteInstance,
            postRun
        );
        Path deltaPath = transaction.resolve("config-delta.json");
        writeNew(
            deltaPath,
            delta(backupInventory, current)
        );
        Path staging = transaction.resolve("restore-staging");
        copyManagedSnapshot(backup, staging);
        try {
            swapManaged(
                absoluteInstance,
                staging,
                transaction.resolve("post-run-live-state"),
                transaction.resolve("failed-restore-state"),
                checkpoint
            );
            String restored = rawConfigHash(absoluteInstance);
            if (!expectedPreRun.equals(restored)) {
                throw new IOException(
                    "restored raw config hash mismatch: expected "
                        + expectedPreRun
                        + " actual "
                        + restored
                );
            }
            journal.addProperty("status", "RESTORED_VERIFIED");
            journal.addProperty(
                "postRunRawConfigHash",
                current.canonicalSha256()
            );
            journal.addProperty(
                "restoredRawConfigHash",
                restored
            );
            journal.addProperty(
                "restoredAtUtc",
                Instant.now().toString()
            );
            journal.addProperty(
                "deltaManifest",
                deltaPath.toString()
            );
            writeReplacing(journalPath, journal);
            return new Restored(
                transaction,
                postRun,
                current.canonicalSha256(),
                restored,
                deltaPath.toString()
            );
        } catch (IOException | RuntimeException error) {
            journal.addProperty("status", "RESTORE_FAILED");
            journal.addProperty(
                "restoreError",
                bounded(error.getMessage())
            );
            writeReplacing(journalPath, journal);
            throw error;
        }
    }

    static String rawConfigHash(Path instance) throws IOException {
        ArrayList<Path> files = new ArrayList<>();
        for (String relative : MANAGED_ENTRIES) {
            Path path = instance.resolve(relative);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(path)) {
                throw new IOException("config link rejected: " + path);
            }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                files.add(path);
                continue;
            }
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                    "non-regular config entry rejected: " + path
                );
            }
            try (var stream = Files.walk(path)) {
                for (Path entry : stream.toList()) {
                    if (Files.isSymbolicLink(entry)) {
                        throw new IOException(
                            "config link rejected: " + entry
                        );
                    }
                    if (
                        Files.isRegularFile(
                            entry,
                            LinkOption.NOFOLLOW_LINKS
                        )
                    ) {
                        files.add(entry);
                    }
                }
            }
        }
        return FixtureRunManager.instanceFingerprint(instance, files);
    }

    static FixtureInventory copyManagedSnapshot(
        Path sourceRoot,
        Path targetRoot
    ) throws IOException {
        Path source = sourceRoot.toAbsolutePath().normalize();
        Path target = targetRoot.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("snapshot target already exists: " + target);
        }
        Files.createDirectory(target);
        for (String relative : MANAGED_ENTRIES) {
            Path from = source.resolve(relative);
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path to = target.resolve(relative);
            copyPhysicalTree(from, to);
        }
        return FixtureInventory.scan(target);
    }

    private static void swapManaged(
        Path instance,
        Path staged,
        Path displaced,
        Path failedState,
        Checkpoint checkpoint
    ) throws IOException {
        Files.createDirectories(displaced);
        ArrayList<String> originals = new ArrayList<>();
        ArrayList<String> installed = new ArrayList<>();
        try {
            for (String relative : MANAGED_ENTRIES) {
                Path live = instance.resolve(relative);
                Path old = displaced.resolve(relative);
                Path replacement = staged.resolve(relative);
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(old.getParent());
                    atomicMove(live, old);
                    originals.add(relative);
                }
                if (
                    Files.exists(
                        replacement,
                        LinkOption.NOFOLLOW_LINKS
                    )
                ) {
                    Files.createDirectories(live.getParent());
                    atomicMove(replacement, live);
                    installed.add(relative);
                }
                if (checkpoint != null) {
                    checkpoint.afterInstalled(
                        installed.size(),
                        relative
                    );
                }
            }
        } catch (IOException | RuntimeException error) {
            rollbackSwap(
                instance,
                displaced,
                failedState,
                originals,
                installed
            );
            throw error;
        }
    }

    private static void rollbackSwap(
        Path instance,
        Path displaced,
        Path failedState,
        List<String> originals,
        List<String> installed
    ) throws IOException {
        Files.createDirectories(failedState);
        IOException rollbackError = null;
        for (int index = installed.size() - 1; index >= 0; index--) {
            String relative = installed.get(index);
            Path live = instance.resolve(relative);
            if (!Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path failed = failedState.resolve(relative);
            Files.createDirectories(failed.getParent());
            try {
                atomicMove(live, failed);
            } catch (IOException error) {
                rollbackError = error;
            }
        }
        for (int index = originals.size() - 1; index >= 0; index--) {
            String relative = originals.get(index);
            Path old = displaced.resolve(relative);
            if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path live = instance.resolve(relative);
            Files.createDirectories(live.getParent());
            try {
                atomicMove(old, live);
            } catch (IOException error) {
                rollbackError = error;
            }
        }
        if (rollbackError != null) {
            throw new IOException(
                "config swap failed and rollback was incomplete",
                rollbackError
            );
        }
    }

    private static FixtureInventory verifyPhysicalProfile(
        Path instance,
        BenchmarkConfigProfile profile
    ) throws IOException {
        Path profileDirectory = instance.resolve(
            BenchmarkConfigProfile.PROFILE_DIRECTORY
        );
        FixtureInventory actual = FixtureInventory.scan(profileDirectory);
        Path manifestPath = instance.resolve(
            "benchmark-2a0b/config-profiles"
        ).resolve(
            BenchmarkConfigProfile.PROFILE_DIRECTORY + ".manifest.json"
        );
        JsonObject local = readObject(manifestPath);
        String expectedInventory = local.get(
            "physicalProfileInventoryHash"
        ).getAsString();
        if (!expectedInventory.equals(actual.canonicalSha256())) {
            throw new IOException(
                "physical benchmark config profile hash mismatch"
            );
        }
        if (
            !profile.benchmarkStartProfileHash().equals(
                local.get("benchmarkStartProfileHash").getAsString()
            )
        ) {
            throw new IOException(
                "local benchmark profile semantic hash mismatch"
            );
        }
        return actual;
    }

    private static void copyPhysicalTree(Path source, Path target)
        throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("config link rejected: " + source);
        }
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent());
            Files.copy(
                source,
                target,
                StandardCopyOption.COPY_ATTRIBUTES
            );
            return;
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "non-regular config entry rejected: " + source
            );
        }
        Files.walkFileTree(
            source,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
                ) throws IOException {
                    rejectSpecial(source, directory, attributes);
                    Path relative = source.relativize(directory);
                    Files.createDirectories(target.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
                ) throws IOException {
                    rejectSpecial(source, file, attributes);
                    if (!attributes.isRegularFile()) {
                        throw new IOException(
                            "non-regular config file rejected: " + file
                        );
                    }
                    Path output = target.resolve(source.relativize(file));
                    Files.createDirectories(output.getParent());
                    Files.copy(
                        file,
                        output,
                        StandardCopyOption.COPY_ATTRIBUTES
                    );
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    private static void rejectSpecial(
        Path root,
        Path path,
        BasicFileAttributes attributes
    ) throws IOException {
        if (
            Files.isSymbolicLink(path)
                || attributes.isSymbolicLink()
                || attributes.isOther()
        ) {
            throw new IOException(
                "link, reparse point or special config entry rejected: "
                    + root.relativize(path)
            );
        }
    }

    private static JsonObject delta(
        FixtureInventory before,
        FixtureInventory after
    ) {
        Map<String, FixtureInventory.Entry> left = new HashMap<>();
        Map<String, FixtureInventory.Entry> right = new HashMap<>();
        for (FixtureInventory.Entry entry : before.entries()) {
            left.put(entry.path(), entry);
        }
        for (FixtureInventory.Entry entry : after.entries()) {
            right.put(entry.path(), entry);
        }
        JsonArray added = new JsonArray();
        JsonArray deleted = new JsonArray();
        JsonArray changed = new JsonArray();
        for (FixtureInventory.Entry entry : after.entries()) {
            FixtureInventory.Entry old = left.get(entry.path());
            if (old == null) {
                added.add(entry.path());
            } else if (!old.equals(entry)) {
                changed.add(entry.path());
            }
        }
        for (FixtureInventory.Entry entry : before.entries()) {
            if (!right.containsKey(entry.path())) {
                deleted.add(entry.path());
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty(
            "capturedAtUtc",
            Instant.now().toString()
        );
        result.addProperty(
            "preRunRawConfigHash",
            before.canonicalSha256()
        );
        result.addProperty(
            "postRunRawConfigHash",
            after.canonicalSha256()
        );
        result.add("addedFiles", added);
        result.add("deletedFiles", deleted);
        result.add("changedFiles", changed);
        result.addProperty("semanticInterpretation", "NOT_PERFORMED");
        return result;
    }

    private static JsonObject transactionJournal(
        String runId,
        String status,
        String preRun,
        String applied,
        String startProfile
    ) {
        JsonObject journal = new JsonObject();
        journal.addProperty("schemaVersion", 1);
        journal.addProperty("runId", runId);
        journal.addProperty("status", status);
        journal.addProperty("createdAtUtc", Instant.now().toString());
        journal.addProperty("preRunRawConfigHash", preRun);
        journal.addProperty("appliedRawConfigHash", applied);
        journal.addProperty(
            "benchmarkStartProfileHash",
            startProfile
        );
        journal.addProperty("autoDelete", false);
        return journal;
    }

    private static void atomicMove(Path source, Path target)
        throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException(
                "atomic config move unavailable: "
                    + source
                    + " -> "
                    + target,
                error
            );
        }
    }

    private static void makeFilesReadOnly(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                try {
                    Files.setAttribute(path, "dos:readonly", true);
                } catch (UnsupportedOperationException error) {
                    throw new IOException(
                        "cannot mark benchmark profile read-only",
                        error
                    );
                }
            }
        }
    }

    private static void writeNew(Path path, JsonObject object)
        throws IOException {
        Files.createDirectories(path.getParent());
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

    private static void writeReplacing(Path path, JsonObject object)
        throws IOException {
        Files.createDirectories(path.getParent());
        try (
            var writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        ) {
            GSON.toJson(object, writer);
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("cannot parse transaction file " + path, error);
        }
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
}
