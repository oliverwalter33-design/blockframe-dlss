package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable hand-off from the external config apply/restore owner to the
 * in-process replay. Minecraft reads this receipt once; it never acquires the
 * config lock and never hashes or restores the managed config tree.
 */
public record ConfigTransactionReceipt(
    int schemaVersion,
    Phase2a0bContracts.HashAlgorithm hashAlgorithm,
    Phase2a0bContracts.ConfigOwner owner,
    Status status,
    String transactionId,
    Phase2a0bContracts.Sha256 benchmarkStartProfileHash,
    Phase2a0bContracts.Sha256 appliedRawFileHash,
    Phase2a0bContracts.Sha256 semanticHash,
    Phase2a0bContracts.Sha256 backupHash,
    Instant appliedAt,
    Path expectedInstance,
    Phase2a0bContracts.Sha256 modProfileHash,
    Phase2a0bContracts.Sha256 receiptContentHash
) {
    public enum Status {
        APPLIED_VERIFIED,
        COMPLETED,
        UNKNOWN;

        static Status parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final String CANONICAL_HEADER =
        "BLOCKFRAME_CONFIG_TRANSACTION_RECEIPT_V1";

    public ConfigTransactionReceipt {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported receipt schema");
        }
        Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(status, "status");
        transactionId = checkedToken(transactionId, "transactionId");
        Objects.requireNonNull(
            benchmarkStartProfileHash,
            "benchmarkStartProfileHash"
        );
        Objects.requireNonNull(appliedRawFileHash, "appliedRawFileHash");
        Objects.requireNonNull(semanticHash, "semanticHash");
        Objects.requireNonNull(backupHash, "backupHash");
        Objects.requireNonNull(appliedAt, "appliedAt");
        expectedInstance = Objects.requireNonNull(
            expectedInstance,
            "expectedInstance"
        ).toAbsolutePath().normalize();
        Objects.requireNonNull(modProfileHash, "modProfileHash");
        Objects.requireNonNull(receiptContentHash, "receiptContentHash");
    }

    public static ConfigTransactionReceipt create(
        String transactionId,
        String benchmarkStartProfileHash,
        String appliedRawFileHash,
        String semanticHash,
        String backupHash,
        Instant appliedAt,
        Path expectedInstance,
        String modProfileHash
    ) throws IOException {
        Phase2a0bContracts.Sha256 start =
            Phase2a0bContracts.Sha256.parse(benchmarkStartProfileHash);
        Phase2a0bContracts.Sha256 applied =
            Phase2a0bContracts.Sha256.parse(appliedRawFileHash);
        Phase2a0bContracts.Sha256 semantic =
            Phase2a0bContracts.Sha256.parse(semanticHash);
        Phase2a0bContracts.Sha256 backup =
            Phase2a0bContracts.Sha256.parse(backupHash);
        Phase2a0bContracts.Sha256 mods =
            Phase2a0bContracts.Sha256.parse(modProfileHash);
        Path instance = expectedInstance.toAbsolutePath().normalize();
        String canonical = canonical(
            transactionId,
            start,
            applied,
            semantic,
            backup,
            appliedAt,
            instance,
            mods,
            Status.APPLIED_VERIFIED
        );
        return new ConfigTransactionReceipt(
            1,
            Phase2a0bContracts.HashAlgorithm.SHA_256,
            Phase2a0bContracts.ConfigOwner.EXTERNAL_LAUNCHER,
            Status.APPLIED_VERIFIED,
            transactionId,
            start,
            applied,
            semantic,
            backup,
            appliedAt,
            instance,
            mods,
            new Phase2a0bContracts.Sha256(
                FixtureInventory.sha256(
                    canonical.getBytes(StandardCharsets.UTF_8)
                )
            )
        );
    }

    public static ConfigTransactionReceipt readOnce(Path path)
        throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("config transaction receipt missing");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)
            ).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("invalid config transaction receipt", error);
        }
        try {
            ConfigTransactionReceipt receipt =
                new ConfigTransactionReceipt(
                    requiredInt(root, "schemaVersion"),
                    Phase2a0bContracts.HashAlgorithm.parse(
                        requiredString(root, "hashAlgorithm")
                    ),
                    Phase2a0bContracts.ConfigOwner.valueOf(
                        requiredString(root, "owner")
                    ),
                    Status.parse(requiredString(root, "status")),
                    requiredString(root, "transactionId"),
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(
                            root,
                            "benchmarkStartProfileHash"
                        )
                    ),
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(root, "appliedRawFileHash")
                    ),
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(root, "semanticHash")
                    ),
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(root, "backupHash")
                    ),
                    Instant.parse(requiredString(root, "appliedAtUtc")),
                    Path.of(requiredString(root, "expectedInstance")),
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(root, "modProfileHash")
                    ),
                    Phase2a0bContracts.Sha256.parse(
                        requiredString(root, "receiptContentHash")
                    )
                );
            receipt.verifySelfHash();
            return receipt;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid config transaction receipt", error);
        }
    }

    public void writeImmutable(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", this.schemaVersion);
        root.addProperty(
            "hashAlgorithm",
            this.hashAlgorithm.wireValue()
        );
        root.addProperty("owner", this.owner.name());
        root.addProperty("status", this.status.name());
        root.addProperty("transactionId", this.transactionId);
        root.addProperty(
            "benchmarkStartProfileHash",
            this.benchmarkStartProfileHash.value()
        );
        root.addProperty(
            "appliedRawFileHash",
            this.appliedRawFileHash.value()
        );
        root.addProperty("semanticHash", this.semanticHash.value());
        root.addProperty("backupHash", this.backupHash.value());
        root.addProperty("appliedAtUtc", this.appliedAt.toString());
        root.addProperty(
            "expectedInstance",
            this.expectedInstance.toString()
        );
        root.addProperty("modProfileHash", this.modProfileHash.value());
        root.addProperty(
            "receiptContentHash",
            this.receiptContentHash.value()
        );
        Files.createDirectories(path.getParent());
        try (
            var writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        ) {
            GSON.toJson(root, writer);
        }
        try {
            Files.setAttribute(path, "dos:readonly", true);
        } catch (UnsupportedOperationException error) {
            throw new IOException(
                "cannot make config transaction receipt immutable",
                error
            );
        }
    }

    public void validateForReplay(
        String expectedTransactionId,
        Path instance,
        String expectedStartProfileHash,
        String expectedAppliedRawFileHash,
        String expectedModProfileHash
    ) throws IOException {
        verifySelfHash();
        require(
            this.owner
                == Phase2a0bContracts.ConfigOwner.EXTERNAL_LAUNCHER,
            "config receipt has wrong owner"
        );
        require(
            this.status == Status.APPLIED_VERIFIED,
            this.status == Status.COMPLETED
                ? "config receipt already completed"
                : "config receipt is not APPLIED_VERIFIED"
        );
        require(
            this.transactionId.equals(expectedTransactionId),
            "config receipt transaction mismatch"
        );
        require(
            pathsEqual(this.expectedInstance, instance),
            "config receipt instance mismatch"
        );
        require(
            this.benchmarkStartProfileHash.equals(
                Phase2a0bContracts.Sha256.parse(
                    expectedStartProfileHash
                )
            ),
            "config receipt start-profile mismatch"
        );
        require(
            this.semanticHash.equals(this.benchmarkStartProfileHash),
            "config receipt semantic hash mismatch"
        );
        require(
            this.appliedRawFileHash.equals(
                Phase2a0bContracts.Sha256.parse(
                    expectedAppliedRawFileHash
                )
            ),
            "config receipt applied-file hash mismatch"
        );
        require(
            this.modProfileHash.equals(
                Phase2a0bContracts.Sha256.parse(
                    expectedModProfileHash
                )
            ),
            "config receipt mod-profile mismatch"
        );
    }

    public void verifySelfHash() throws IOException {
        String canonical = canonical(
            this.transactionId,
            this.benchmarkStartProfileHash,
            this.appliedRawFileHash,
            this.semanticHash,
            this.backupHash,
            this.appliedAt,
            this.expectedInstance,
            this.modProfileHash,
            this.status
        );
        Phase2a0bContracts.Sha256 actual =
            new Phase2a0bContracts.Sha256(
                FixtureInventory.sha256(
                    canonical.getBytes(StandardCharsets.UTF_8)
                )
            );
        require(
            actual.equals(this.receiptContentHash),
            "config receipt content hash mismatch"
        );
    }

    ConfigTransactionReceipt withStatus(Status replacement) {
        String canonical = canonical(
            this.transactionId,
            this.benchmarkStartProfileHash,
            this.appliedRawFileHash,
            this.semanticHash,
            this.backupHash,
            this.appliedAt,
            this.expectedInstance,
            this.modProfileHash,
            replacement
        );
        return new ConfigTransactionReceipt(
            this.schemaVersion,
            this.hashAlgorithm,
            this.owner,
            replacement,
            this.transactionId,
            this.benchmarkStartProfileHash,
            this.appliedRawFileHash,
            this.semanticHash,
            this.backupHash,
            this.appliedAt,
            this.expectedInstance,
            this.modProfileHash,
            new Phase2a0bContracts.Sha256(
                FixtureInventory.sha256(
                    canonical.getBytes(StandardCharsets.UTF_8)
                )
            )
        );
    }

    private static String canonical(
        String transactionId,
        Phase2a0bContracts.Sha256 start,
        Phase2a0bContracts.Sha256 applied,
        Phase2a0bContracts.Sha256 semantic,
        Phase2a0bContracts.Sha256 backup,
        Instant appliedAt,
        Path expectedInstance,
        Phase2a0bContracts.Sha256 modProfile,
        Status status
    ) {
        return String.join(
            "\n",
            CANONICAL_HEADER,
            "appliedAtUtc=" + appliedAt,
            "appliedRawFileHash=" + applied.value(),
            "backupHash=" + backup.value(),
            "benchmarkStartProfileHash=" + start.value(),
            "expectedInstance="
                + expectedInstance.toAbsolutePath().normalize(),
            "hashAlgorithm=SHA-256",
            "modProfileHash=" + modProfile.value(),
            "owner=EXTERNAL_LAUNCHER",
            "schemaVersion=1",
            "semanticHash=" + semantic.value(),
            "status=" + status.name(),
            "transactionId=" + transactionId
        );
    }

    private static boolean pathsEqual(Path left, Path right) {
        String leftValue = left.toAbsolutePath().normalize().toString();
        String rightValue = right.toAbsolutePath().normalize().toString();
        if (
            System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows")
        ) {
            return leftValue.equalsIgnoreCase(rightValue);
        }
        return leftValue.equals(rightValue);
    }

    private static String requiredString(JsonObject root, String name)
        throws IOException {
        JsonElement value = root.get(name);
        if (
            value == null
                || value.isJsonNull()
                || value.getAsString().isBlank()
        ) {
            throw new IOException("missing receipt field: " + name);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject root, String name)
        throws IOException {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IOException("missing receipt field: " + name);
        }
        return value.getAsInt();
    }

    private static String checkedToken(String value, String label) {
        Objects.requireNonNull(value, label);
        if (
            value.isBlank()
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
        ) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static void require(boolean condition, String message)
        throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}
