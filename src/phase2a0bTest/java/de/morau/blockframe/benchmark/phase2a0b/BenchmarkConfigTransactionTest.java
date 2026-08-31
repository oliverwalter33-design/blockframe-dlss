package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkConfigTransactionTest {
    private static final String MOD_HASH =
        "2c62dafa41b3ee9a66369af32ba549f1869dca3463baa4e872e466bd180a9209";
    private final Path project = Path.of(
        System.getProperty("blockframe.projectDir")
    );
    @TempDir
    Path temporary;
    private Path repository;
    private Path instance;
    private String previousJavaVersion;

    @BeforeEach
    void setUp() throws Exception {
        this.previousJavaVersion = System.getProperty("java.version");
        System.setProperty("java.version", "25.0.3");
        this.repository = this.temporary.resolve("repository");
        Path manifest = this.repository.resolve(
            BenchmarkConfigProfile.REPOSITORY_MANIFEST
        );
        Files.createDirectories(manifest.getParent());
        Files.copy(
            this.project.resolve(
                BenchmarkConfigProfile.REPOSITORY_MANIFEST
            ),
            manifest
        );
        this.instance = this.temporary.resolve("instance");
        Files.createDirectories(this.instance.resolve("defaultconfigs"));
        Files.createDirectories(this.instance.resolve("mods"));
        BenchmarkConfigProfileTest.writeSnapshot(
            this.instance,
            true,
            false
        );
        Files.writeString(
            this.instance.resolve("defaultconfigs/kept.txt"),
            "pre-run",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            this.instance.resolve("user_jvm_args.txt"),
            "-Xmx8G",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            this.instance.resolve("minecraftinstance.json"),
            """
            {
              "gameVersion": "26.2",
              "baseModLoader": {"forgeVersion": "26.2.0.23-beta"}
            }
            """,
            StandardCharsets.UTF_8
        );
        BenchmarkConfigTransaction.createGoldenProfile(
            this.instance,
            this.repository
        );
    }

    @AfterEach
    void restoreSystemPropertyAndClearReadOnly() throws Exception {
        System.setProperty("java.version", this.previousJavaVersion);
        if (Files.exists(this.temporary)) {
            try (var stream = Files.walk(this.temporary)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    try {
                        Files.setAttribute(file, "dos:readonly", false);
                    } catch (
                        IOException | UnsupportedOperationException ignored
                    ) {
                        // Non-Windows test files do not expose DOS attributes.
                    }
                }
            }
        }
    }

    @Test
    void physicalProfileApplyAndRestoreAreExactAndJournaled()
        throws Exception {
        String before =
            BenchmarkConfigTransaction.rawConfigHash(this.instance);
        BenchmarkConfigTransaction.Applied applied =
            BenchmarkConfigTransaction.apply(
                this.instance,
                this.repository,
                "success",
                null,
                MOD_HASH
            );
        assertEquals(before, applied.preRunRawConfigHash());
        assertNotEquals(before, applied.appliedRawConfigHash());
        assertEquals(
            "3a78ec4a863e3e10c7bcf57179e57f2889953b0700681d49e6f2ece710e1f40d",
            applied.benchmarkStartProfileHash()
        );
        Files.writeString(
            this.instance.resolve("config/runtime-cache.txt"),
            "live-delta",
            StandardCharsets.UTF_8
        );

        BenchmarkConfigTransaction.Restored restored =
            BenchmarkConfigTransaction.restore(this.instance, "success");

        assertEquals(before, restored.restoredRawConfigHash());
        assertEquals(
            before,
            BenchmarkConfigTransaction.rawConfigHash(this.instance)
        );
        assertTrue(Files.isRegularFile(Path.of(restored.deltaManifest())));
        assertTrue(Files.isDirectory(applied.preRunBackup()));
        assertTrue(Files.isDirectory(restored.postRunSnapshot()));
    }

    @Test
    void interruptedApplyRollsBackExactlyAndRetainsOwnership()
        throws Exception {
        String before =
            BenchmarkConfigTransaction.rawConfigHash(this.instance);
        IOException error = assertThrows(
            IOException.class,
            () ->
                BenchmarkConfigTransaction.apply(
                    this.instance,
                    this.repository,
                    "apply-interrupted",
                    (count, relative) -> {
                        if (count >= 1) {
                            throw new IOException("injected apply interruption");
                        }
                    },
                    MOD_HASH
                )
        );
        assertTrue(error.getMessage().contains("injected"));
        assertEquals(
            before,
            BenchmarkConfigTransaction.rawConfigHash(this.instance)
        );
        assertTrue(
            Files.isDirectory(
                this.instance.resolve(
                    "benchmark-2a0b/config-transactions/"
                        + "apply-interrupted/pre-run-backup"
                )
            )
        );
    }

    @Test
    void interruptedRestoreKeepsAppliedStateAndExactBackup()
        throws Exception {
        String before =
            BenchmarkConfigTransaction.rawConfigHash(this.instance);
        BenchmarkConfigTransaction.Applied applied =
            BenchmarkConfigTransaction.apply(
                this.instance,
                this.repository,
                "restore-interrupted",
                null,
                MOD_HASH
            );
        IOException error = assertThrows(
            IOException.class,
            () ->
                BenchmarkConfigTransaction.restore(
                    this.instance,
                    "restore-interrupted",
                    (count, relative) -> {
                        if (count >= 1) {
                            throw new IOException(
                                "injected restore interruption"
                            );
                        }
                    }
                )
        );
        assertTrue(error.getMessage().contains("injected"));
        assertEquals(
            applied.appliedRawConfigHash(),
            BenchmarkConfigTransaction.rawConfigHash(this.instance)
        );
        assertEquals(
            before,
            FixtureInventory.scan(applied.preRunBackup())
                .canonicalSha256()
        );
    }

    @Test
    void physicalProfileHashDeviationBlocksApply() throws Exception {
        Path projection = this.instance.resolve(
            BenchmarkConfigProfile.PROFILE_DIRECTORY
        ).resolve("canonical-projection.txt");
        Files.setAttribute(projection, "dos:readonly", false);
        Files.writeString(
            projection,
            Files.readString(projection) + "\nchanged",
            StandardCharsets.UTF_8
        );
        IOException error = assertThrows(
            IOException.class,
            () ->
                BenchmarkConfigTransaction.apply(
                    this.instance,
                    this.repository,
                    "hash-deviation",
                    null,
                    MOD_HASH
                )
        );
        assertTrue(
            error.getMessage().contains(
                "physical benchmark config profile hash mismatch"
            )
        );
    }
}
