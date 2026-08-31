package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureInventoryTest {
    @TempDir
    Path temporary;

    @Test
    void physicalCopyIsVerifiedAndNeverOverwrites() throws Exception {
        Path source = fixture();
        FixtureInventory expected = FixtureInventory.scan(source);
        Path staging = this.temporary.resolve(".copying-a");
        Path target = this.temporary.resolve("run-a");

        FixtureInventory.copyPhysical(
            source,
            staging,
            target,
            expected,
            null
        );

        assertTrue(expected.contentEquals(FixtureInventory.scan(target)));
        assertFalse(Files.exists(staging));
        assertThrows(
            IOException.class,
            () ->
                FixtureInventory.copyPhysical(
                    source,
                    this.temporary.resolve(".copying-b"),
                    target,
                    expected,
                    null
                )
        );
    }

    @Test
    void interruptedCopyPreservesUniqueTemporaryDirectory() throws Exception {
        Path source = fixture();
        FixtureInventory expected = FixtureInventory.scan(source);
        Path staging = this.temporary.resolve(".copying-interrupted");
        Path target = this.temporary.resolve("run-interrupted");

        assertThrows(
            IOException.class,
            () ->
                FixtureInventory.copyPhysical(
                    source,
                    staging,
                    target,
                    expected,
                    (index, entry) -> {
                        if (index == 1) {
                            throw new IOException("simulated interruption");
                        }
                    }
                )
        );
        assertTrue(Files.isDirectory(staging));
        assertFalse(Files.exists(target));
    }

    @Test
    void contentChangeChangesCanonicalHash() throws Exception {
        Path source = fixture();
        String before = FixtureInventory.scan(source).canonicalSha256();
        Files.writeString(
            source.resolve("a.txt"),
            "changed",
            StandardCharsets.UTF_8
        );
        String after = FixtureInventory.scan(source).canonicalSha256();
        assertFalse(before.equals(after));
    }

    @Test
    void instanceFingerprintUsesOrdinalPathsAndContent() throws Exception {
        Path root = this.temporary.resolve("instance");
        Files.createDirectories(root.resolve("mods"));
        Path uppercase = root.resolve("mods/Z.jar");
        Path lowercase = root.resolve("mods/a.jar");
        Files.writeString(uppercase, "Z", StandardCharsets.UTF_8);
        Files.writeString(lowercase, "a", StandardCharsets.UTF_8);
        String first = FixtureRunManager.instanceFingerprint(
            root.resolve("mods"),
            java.util.List.of(lowercase, uppercase)
        );
        Files.writeString(lowercase, "different", StandardCharsets.UTF_8);
        String second = FixtureRunManager.instanceFingerprint(
            root.resolve("mods"),
            java.util.List.of(uppercase, lowercase)
        );
        assertFalse(first.equals(second));
    }

    @Test
    void formattedStreamingInventoryRoundTrips() throws Exception {
        FixtureInventory expected = FixtureInventory.scan(fixture());
        Path manifest = this.temporary.resolve("inventory.jsonl");
        FixtureRunManager.writeInventory(manifest, expected);
        FixtureInventory actual = FixtureRunManager.readInventory(manifest);
        assertTrue(expected.contentEquals(actual));
        assertEquals(
            expected.canonicalSha256(),
            actual.canonicalSha256()
        );
    }

    @Test
    void requiredStateSetIsCompleteAndOrdered() {
        assertEquals(BenchmarkState.PREFLIGHT, BenchmarkState.values()[0]);
        assertEquals(
            BenchmarkState.COMPLETE,
            BenchmarkState.values()[BenchmarkState.values().length - 2]
        );
        assertTrue(BenchmarkState.COMPLETE.terminal());
        assertTrue(BenchmarkState.FAILED.terminal());
    }

    private Path fixture() throws IOException {
        Path source = this.temporary.resolve("source");
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(
            source.resolve("a.txt"),
            "alpha",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            source.resolve("nested/b.txt"),
            "beta",
            StandardCharsets.UTF_8
        );
        return source;
    }
}
