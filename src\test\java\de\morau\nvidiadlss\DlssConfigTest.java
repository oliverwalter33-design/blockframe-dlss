package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DlssConfigTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void restoreSafePublishedDefaults() {
        DlssConfig.load(
            this.temporaryDirectory.resolve("restore-current-missing"),
            this.temporaryDirectory.resolve("restore-legacy-missing")
        );
    }

    @Test
    void missingFilesPublishSafeDefaultsWithoutWriting() {
        Path current = this.temporaryDirectory.resolve(
            "config/voxellift.properties"
        );
        Path legacy = this.temporaryDirectory.resolve(
            "config/nvidia_dlss.properties"
        );

        DlssConfig.Snapshot snapshot = DlssConfig.load(current, legacy);

        assertEquals(DlssMode.OFF, snapshot.mode());
        assertEquals(SharpeningMode.AUTO, snapshot.sharpening());
        assertEquals(20, snapshot.sharpeningAmount());
        assertEquals(
            EntityMotionHistory.BackendPreference.HEAP,
            snapshot.entityHistoryBackend()
        );
        assertEquals(
            DlssConfig.ConfigSource.DEFAULTS,
            snapshot.configSource()
        );
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void currentFileWinsOverLegacyAndPublishesItsSource()
        throws Exception {
        Path current = this.temporaryDirectory.resolve(
            "config/voxellift.properties"
        );
        Path legacy = this.temporaryDirectory.resolve(
            "config/nvidia_dlss.properties"
        );
        write(
            current,
            """
            mode=quality
            sharpening=manual
            sharpeningAmount=37
            entityHistoryBackend=heap
            """
        );
        write(
            legacy,
            """
            mode=performance
            sharpening=off
            sharpeningAmount=91
            entityHistoryBackend=native-experimental
            """
        );

        DlssConfig.Snapshot snapshot =
            DlssConfig.readSnapshot(current, legacy);

        assertEquals(DlssMode.QUALITY, snapshot.mode());
        assertEquals(SharpeningMode.MANUAL, snapshot.sharpening());
        assertEquals(37, snapshot.sharpeningAmount());
        assertEquals(
            EntityMotionHistory.BackendPreference.HEAP,
            snapshot.entityHistoryBackend()
        );
        assertEquals(
            DlssConfig.ConfigSource.CURRENT,
            snapshot.configSource()
        );
    }

    @Test
    void legacyReadDoesNotMigrateWriteOrModifyEitherPath()
        throws Exception {
        Path current = this.temporaryDirectory.resolve(
            "config/voxellift.properties"
        );
        Path legacy = this.temporaryDirectory.resolve(
            "config/nvidia_dlss.properties"
        );
        write(
            legacy,
            """
            mode=dlaa
            sharpening=auto
            sharpeningAmount=20
            entityHistoryBackend=heap
            """
        );
        byte[] before = Files.readAllBytes(legacy);
        FileTime modifiedBefore = Files.getLastModifiedTime(legacy);

        DlssConfig.Snapshot snapshot = DlssConfig.load(current, legacy);
        String fingerprint = snapshot.fingerprintMaterial();

        assertEquals(DlssMode.DLAA, snapshot.mode());
        assertEquals(
            DlssConfig.ConfigSource.LEGACY,
            snapshot.configSource()
        );
        assertTrue(fingerprint.contains("mode=dlaa"));
        assertFalse(Files.exists(current));
        assertFalse(
            Files.exists(
                current.resolveSibling(
                    current.getFileName().toString() + ".tmp"
                )
            )
        );
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(legacy));
    }

    @Test
    void malformedAndMissingValuesFallBackIndividually()
        throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mode", " quality ");
        properties.setProperty("sharpening", " manual ");
        properties.setProperty("sharpeningAmount", "not-a-number");
        properties.setProperty(
            "entityHistoryBackend",
            " native-experimental "
        );

        DlssConfig.Snapshot snapshot = DlssConfig.decode(
            properties,
            DlssConfig.ConfigSource.CURRENT
        );

        assertEquals(DlssMode.QUALITY, snapshot.mode());
        assertEquals(SharpeningMode.MANUAL, snapshot.sharpening());
        assertEquals(20, snapshot.sharpeningAmount());
        assertEquals(
            EntityMotionHistory.BackendPreference.NATIVE_EXPERIMENTAL,
            snapshot.entityHistoryBackend()
        );

        Properties invalid = new Properties();
        invalid.setProperty("mode", "unknown");
        invalid.setProperty("sharpening", "unknown");
        invalid.setProperty("entityHistoryBackend", "unknown");
        DlssConfig.Snapshot safe = DlssConfig.decode(
            invalid,
            DlssConfig.ConfigSource.CURRENT
        );
        assertEquals(DlssMode.OFF, safe.mode());
        assertEquals(SharpeningMode.AUTO, safe.sharpening());
        assertEquals(20, safe.sharpeningAmount());
        assertEquals(
            EntityMotionHistory.BackendPreference.HEAP,
            safe.entityHistoryBackend()
        );
    }

    @Test
    void sharpeningAmountIsBoundedAndSnapshotRejectsInvalidBounds() {
        Properties properties = new Properties();
        properties.setProperty("sharpeningAmount", "999");
        assertEquals(
            100,
            DlssConfig.decode(
                properties,
                DlssConfig.ConfigSource.CURRENT
            ).sharpeningAmount()
        );

        properties.setProperty("sharpeningAmount", "-9");
        assertEquals(
            0,
            DlssConfig.decode(
                properties,
                DlssConfig.ConfigSource.CURRENT
            ).sharpeningAmount()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new DlssConfig.Snapshot(
                DlssMode.OFF,
                SharpeningMode.AUTO,
                101,
                EntityMotionHistory.BackendPreference.HEAP,
                DlssConfig.ConfigSource.DEFAULTS,
                "not-canonical"
            )
        );
    }

    @Test
    void canonicalFingerprintDependsOnlyOnSemanticValues() {
        Properties first = new Properties();
        first.setProperty("mode", " QUALITY ");
        first.setProperty("sharpening", "manual");
        first.setProperty("sharpeningAmount", "020");
        first.setProperty("entityHistoryBackend", "HEAP");

        Properties second = new Properties();
        second.setProperty("entityHistoryBackend", "heap");
        second.setProperty("sharpeningAmount", "20");
        second.setProperty("sharpening", " MANUAL ");
        second.setProperty("mode", "quality");

        DlssConfig.Snapshot current = DlssConfig.decode(
            first,
            DlssConfig.ConfigSource.CURRENT
        );
        DlssConfig.Snapshot legacy = DlssConfig.decode(
            second,
            DlssConfig.ConfigSource.LEGACY
        );

        assertEquals(
            """
            blockframe-dlss-config-v1
            mode=quality
            sharpening=manual
            sharpeningAmount=20
            entityHistoryBackend=heap
            """,
            current.fingerprintMaterial()
        );
        assertEquals(
            current.fingerprintMaterial(),
            legacy.fingerprintMaterial()
        );
        assertFalse(current.fingerprintMaterial().contains("current"));
        assertFalse(current.fingerprintMaterial().contains("legacy"));
    }

    @Test
    void malformedPropertiesDocumentPublishesOneCompleteSafeSnapshot()
        throws Exception {
        Path current = this.temporaryDirectory.resolve(
            "config/voxellift.properties"
        );
        Path legacy = this.temporaryDirectory.resolve(
            "config/nvidia_dlss.properties"
        );
        write(current, "mode=quality\nbadUnicode=\\u12G4\n");
        write(
            legacy,
            """
            mode=performance
            sharpening=manual
            sharpeningAmount=88
            entityHistoryBackend=native-experimental
            """
        );

        DlssConfig.Snapshot snapshot = DlssConfig.load(current, legacy);

        assertEquals(DlssMode.OFF, snapshot.mode());
        assertEquals(SharpeningMode.AUTO, snapshot.sharpening());
        assertEquals(20, snapshot.sharpeningAmount());
        assertEquals(
            EntityMotionHistory.BackendPreference.HEAP,
            snapshot.entityHistoryBackend()
        );
        assertEquals(
            DlssConfig.ConfigSource.DEFAULTS,
            snapshot.configSource()
        );
    }

    @Test
    void concurrentReadersObserveOnlyWholePublishedSnapshots()
        throws Exception {
        Path firstPath = this.temporaryDirectory.resolve(
            "first/voxellift.properties"
        );
        Path secondPath = this.temporaryDirectory.resolve(
            "second/voxellift.properties"
        );
        Path missingLegacy = this.temporaryDirectory.resolve(
            "missing/nvidia_dlss.properties"
        );
        write(
            firstPath,
            """
            mode=quality
            sharpening=manual
            sharpeningAmount=13
            entityHistoryBackend=heap
            """
        );
        write(
            secondPath,
            """
            mode=dlaa
            sharpening=off
            sharpeningAmount=97
            entityHistoryBackend=native-experimental
            """
        );
        DlssConfig.Snapshot first =
            DlssConfig.readSnapshot(firstPath, missingLegacy);
        DlssConfig.Snapshot second =
            DlssConfig.readSnapshot(secondPath, missingLegacy);
        DlssConfig.load(firstPath, missingLegacy);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                DlssConfig.Snapshot observed = DlssConfig.snapshot();
                if (!observed.equals(first) && !observed.equals(second)) {
                    failure.compareAndSet(
                        null,
                        new AssertionError(
                            "partially published snapshot: " + observed
                        )
                    );
                    return;
                }
                reads.incrementAndGet();
                Thread.onSpinWait();
            }
        });

        try {
            for (int index = 0; index < 200; index++) {
                DlssConfig.load(
                    (index & 1) == 0 ? secondPath : firstPath,
                    missingLegacy
                );
            }
        } finally {
            running.set(false);
            reader.join();
        }

        assertNull(failure.get());
        assertTrue(reads.get() > 0);
    }

    private static void write(Path path, String content)
        throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.ISO_8859_1);
    }
}
