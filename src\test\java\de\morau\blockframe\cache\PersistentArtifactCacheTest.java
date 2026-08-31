package de.morau.blockframe.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.morau.blockframe.profiler.CacheStatistics;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentArtifactCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void coldWriteThenWarmHitNeverReopensTheSource() throws Exception {
        byte[] payload = "immutable-native-runtime".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        CacheKey key = key("one");
        CacheStatistics statistics = new CacheStatistics();
        AtomicInteger opens = new AtomicInteger();
        PersistentArtifactCache cache = new PersistentArtifactCache(
            this.temporaryDirectory.resolve("cache"),
            1024L * 1024L,
            statistics
        );

        PersistentArtifactCache.Result cold = cache.materialize(
            key,
            manifest,
            name -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(payload);
            }
        );
        assertEquals(PersistentArtifactCache.Status.MISS_WRITTEN, cold.status());
        assertTrue(cold.persistent());
        assertArrayEquals(payload, Files.readAllBytes(cold.path().resolve("runtime.dll")));

        PersistentArtifactCache secondManagerView = new PersistentArtifactCache(
            this.temporaryDirectory.resolve("cache"),
            1024L * 1024L,
            statistics
        );
        PersistentArtifactCache.Result warm = secondManagerView.materialize(
            key,
            manifest,
            name -> {
                fail("A validated warm hit must not reopen artifact resources");
                return null;
            }
        );
        assertEquals(PersistentArtifactCache.Status.HIT, warm.status());
        assertEquals(cold.path(), warm.path());
        assertEquals(1, opens.get());

        CacheStatistics.Snapshot snapshot = statistics.snapshot();
        assertTrue(snapshot.attached());
        assertEquals(1L, snapshot.hits());
        assertEquals(1L, snapshot.misses());
        assertEquals(1L, snapshot.writtenEntries());
        assertTrue(snapshot.bytesOnDisk() >= payload.length);
    }

    @Test
    void sameLengthCorruptionAndExtraFilesAreRejectedAndRebuilt() throws Exception {
        byte[] payload = "original".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        CacheKey key = key("corrupt");
        CacheStatistics statistics = new CacheStatistics();
        PersistentArtifactCache cache = cache(statistics, 1024L * 1024L);

        PersistentArtifactCache.Result first =
            cache.materialize(key, manifest, source(payload));
        Files.write(first.path().resolve("runtime.dll"), "tampered".getBytes());
        PersistentArtifactCache.Result repaired =
            cache.materialize(key, manifest, source(payload));
        assertEquals(
            PersistentArtifactCache.Status.REJECTED_REBUILT,
            repaired.status()
        );
        assertArrayEquals(payload, Files.readAllBytes(repaired.path().resolve("runtime.dll")));

        Files.writeString(repaired.path().resolve("unexpected.txt"), "extra");
        PersistentArtifactCache.Result extraRepaired =
            cache.materialize(key, manifest, source(payload));
        assertEquals(
            PersistentArtifactCache.Status.REJECTED_REBUILT,
            extraRepaired.status()
        );
        assertFalse(Files.exists(extraRepaired.path().resolve("unexpected.txt")));
        assertEquals(2L, statistics.snapshot().rejectedEntries());
        assertEquals(1L, statistics.snapshot().misses());
    }

    @Test
    void oversizeBundleUsesVerifiedTransientDirectory() throws Exception {
        byte[] payload = "larger-than-limit".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        CacheStatistics statistics = new CacheStatistics();
        PersistentArtifactCache cache = cache(statistics, payload.length - 1L);

        PersistentArtifactCache.Result result =
            cache.materialize(key("oversize"), manifest, source(payload));
        assertEquals(PersistentArtifactCache.Status.BYPASSED, result.status());
        assertFalse(result.persistent());
        assertArrayEquals(payload, Files.readAllBytes(result.path().resolve("runtime.dll")));
        Path persistentEntry = this.temporaryDirectory
            .resolve("cache")
            .resolve("entries")
            .resolve(key("oversize").digestHex());
        assertFalse(Files.exists(persistentEntry, LinkOption.NOFOLLOW_LINKS));
        assertEquals(1L, statistics.snapshot().misses());
    }

    @Test
    void badSourceNeverPublishesAndCleansStaging() throws Exception {
        byte[] expected = "expected".getBytes();
        byte[] wrong = "tampered".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", expected);
        PersistentArtifactCache cache = cache(new CacheStatistics(), 1024L * 1024L);

        assertThrows(
            IOException.class,
            () -> cache.materialize(key("bad-source"), manifest, source(wrong))
        );
        Path entries = this.temporaryDirectory.resolve("cache").resolve("entries");
        try (var children = Files.list(entries)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void sourceCloseFailureFailsClosedAndNeverPublishes() throws Exception {
        byte[] payload = "expected".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        PersistentArtifactCache cache = cache(
            new CacheStatistics(),
            1024L * 1024L
        );

        assertThrows(
            IOException.class,
            () -> cache.materialize(
                key("bad-close"),
                manifest,
                ignored -> new ByteArrayInputStream(payload) {
                    @Override
                    public void close() throws IOException {
                        throw new IOException("injected close failure");
                    }
                }
            )
        );
        Path entries = this.temporaryDirectory
            .resolve("cache")
            .resolve("entries");
        try (var children = Files.list(entries)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void unavailableAtomicPublicationUsesVerifiedTransientFallback()
        throws Exception {
        byte[] payload = "atomic-fallback".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        CacheStatistics statistics = new CacheStatistics();
        AtomicInteger opens = new AtomicInteger();
        PersistentArtifactCache cache = new PersistentArtifactCache(
            this.temporaryDirectory.resolve("cache"),
            1024L * 1024L,
            statistics,
            (source, target, replace) -> {
                throw new AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "injected"
                );
            }
        );

        PersistentArtifactCache.Result result = cache.materialize(
            key("no-atomic-move"),
            manifest,
            ignored -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(payload);
            }
        );

        assertEquals(PersistentArtifactCache.Status.BYPASSED, result.status());
        assertFalse(result.persistent());
        assertArrayEquals(
            payload,
            Files.readAllBytes(result.path().resolve("runtime.dll"))
        );
        try (var children = Files.list(result.path())) {
            assertEquals(
                List.of("runtime.dll"),
                children.map(path -> path.getFileName().toString()).toList()
            );
        }
        assertEquals(2, opens.get());
        assertEquals(1L, statistics.snapshot().misses());
        assertEquals(0L, statistics.snapshot().writtenEntries());
    }

    @Test
    void metadataTamperAndSymlinkedArtifactAreRejectedWithoutFollowingLinks()
        throws Exception {
        byte[] payload = "original".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        CacheStatistics statistics = new CacheStatistics();
        PersistentArtifactCache cache = cache(statistics, 1024L * 1024L);
        CacheKey key = key("unsafe-entry");
        Path entry = cache.materialize(
            key,
            manifest,
            source(payload)
        ).path();

        Files.writeString(entry.resolve(".blockframe-key"), "tampered\n");
        PersistentArtifactCache.Result metadataRepair = cache.materialize(
            key,
            manifest,
            source(payload)
        );
        assertEquals(
            PersistentArtifactCache.Status.REJECTED_REBUILT,
            metadataRepair.status()
        );

        Path external = this.temporaryDirectory.resolve("external.dll");
        Files.write(external, "external".getBytes());
        Path artifact = metadataRepair.path().resolve("runtime.dll");
        Files.delete(artifact);
        try {
            Files.createSymbolicLink(artifact, external);
        } catch (IOException | UnsupportedOperationException |
                 SecurityException unavailable) {
            return;
        }

        PersistentArtifactCache.Result linkRepair = cache.materialize(
            key,
            manifest,
            source(payload)
        );
        assertEquals(
            PersistentArtifactCache.Status.REJECTED_REBUILT,
            linkRepair.status()
        );
        assertArrayEquals("external".getBytes(), Files.readAllBytes(external));
        assertFalse(Files.isSymbolicLink(
            linkRepair.path().resolve("runtime.dll")
        ));
        assertEquals(2L, statistics.snapshot().rejectedEntries());
    }

    @Test
    void ownedUnknownEntryChildrenAreRemovedAndDoNotEscapeTheBudget()
        throws Exception {
        byte[] payload = "payload".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        PersistentArtifactCache cache = cache(
            new CacheStatistics(),
            1024L * 1024L
        );
        CacheKey key = key("garbage-cleanup");
        PersistentArtifactCache.Result first = cache.materialize(
            key,
            manifest,
            source(payload)
        );
        Path garbage = first.path().getParent().resolve("not-an-entry");
        Files.writeString(garbage, "owned cache garbage");

        assertEquals(
            PersistentArtifactCache.Status.HIT,
            cache.materialize(key, manifest, source(payload)).status()
        );
        assertFalse(Files.exists(garbage, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void oversizeFallbackSurvivesAnUnusablePersistentRoot()
        throws Exception {
        byte[] payload = "oversize".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        Path unusableRoot = this.temporaryDirectory.resolve("plain-file");
        Files.writeString(unusableRoot, "not a directory");
        PersistentArtifactCache cache = new PersistentArtifactCache(
            unusableRoot,
            1L,
            new CacheStatistics()
        );

        PersistentArtifactCache.Result result = cache.materialize(
            key("oversize-unusable-root"),
            manifest,
            source(payload)
        );
        assertEquals(PersistentArtifactCache.Status.BYPASSED, result.status());
        assertArrayEquals(
            payload,
            Files.readAllBytes(result.path().resolve("runtime.dll"))
        );
    }

    @Test
    void processFileLockSerializesARealSecondJvm() throws Exception {
        byte[] payload = "cross-process".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        Path root = this.temporaryDirectory.resolve("process-cache");
        Path ready = this.temporaryDirectory.resolve("lock-ready");
        Path release = this.temporaryDirectory.resolve("lock-release");
        Path marker = this.temporaryDirectory.resolve("lock-marker");
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javaBinary = javaHome.resolve("bin").resolve(
            System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("windows")
                ? "java.exe"
                : "java"
        );
        Process process = new ProcessBuilder(
            javaBinary.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            CacheProcessLockHolder.class.getName(),
            root.toString(),
            ready.toString(),
            release.toString(),
            marker.toString()
        ).redirectErrorStream(true).start();
        try {
            long readyDeadline = System.nanoTime() + 10_000_000_000L;
            while (!Files.exists(ready)) {
                if (!process.isAlive()) {
                    fail(
                        "Lock holder exited early: "
                            + new String(process.getInputStream().readAllBytes())
                    );
                }
                if (System.nanoTime() >= readyDeadline) {
                    fail("Timed out waiting for cross-process cache lock");
                }
                Thread.sleep(10L);
            }

            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(200L);
                    Files.writeString(release, "release\n");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }, "blockframe-cache-lock-releaser");
            releaser.start();

            PersistentArtifactCache cache = new PersistentArtifactCache(
                root,
                1024L * 1024L,
                new CacheStatistics()
            );
            PersistentArtifactCache.Result result = cache.materialize(
                key("cross-process"),
                manifest,
                ignored -> {
                    assertTrue(
                        Files.exists(marker),
                        "Source opened before the other JVM released its lock"
                    );
                    return new ByteArrayInputStream(payload);
                }
            );
            releaser.join(5_000L);
            assertEquals(
                PersistentArtifactCache.Status.MISS_WRITTEN,
                result.status()
            );
            assertTrue(process.waitFor(5L, TimeUnit.SECONDS));
            assertEquals(
                0,
                process.exitValue(),
                () -> {
                    try {
                        return new String(
                            process.getInputStream().readAllBytes()
                        );
                    } catch (IOException exception) {
                        return exception.toString();
                    }
                }
            );
        } finally {
            Files.writeString(
                release,
                "release\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5L, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void invalidationRemovesOnlyTheExactKey() throws Exception {
        byte[] payload = "payload".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        PersistentArtifactCache cache = cache(new CacheStatistics(), 1024L * 1024L);
        CacheKey firstKey = key("first");
        CacheKey secondKey = key("second");
        Path first = cache.materialize(firstKey, manifest, source(payload)).path();
        Path second = cache.materialize(secondKey, manifest, source(payload)).path();

        assertTrue(cache.invalidate(firstKey));
        assertFalse(Files.exists(first, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(second, LinkOption.NOFOLLOW_LINKS));
        assertFalse(cache.invalidate(firstKey));
        assertEquals(
            PersistentArtifactCache.Status.HIT,
            cache.materialize(secondKey, manifest, source(payload)).status()
        );
    }

    @Test
    void deterministicLruProtectsCurrentAndEvictsLeastRecentlyUsed() throws Exception {
        byte[] payload = "payload".getBytes();
        ImmutableArtifactManifest manifest = manifest("runtime.dll", payload);
        Path cacheRoot = this.temporaryDirectory.resolve("cache");
        PersistentArtifactCache roomy = new PersistentArtifactCache(
            cacheRoot,
            1024L * 1024L,
            new CacheStatistics()
        );
        CacheKey a = key("a");
        CacheKey b = key("b");
        CacheKey c = key("c");
        Path aPath = roomy.materialize(a, manifest, source(payload)).path();
        Path bPath = roomy.materialize(b, manifest, source(payload)).path();
        long twoEntryLimit = treeBytes(aPath) + treeBytes(bPath);

        PersistentArtifactCache limited = new PersistentArtifactCache(
            cacheRoot,
            twoEntryLimit,
            new CacheStatistics()
        );
        assertEquals(
            PersistentArtifactCache.Status.HIT,
            limited.materialize(a, manifest, source(payload)).status()
        );
        Path cPath = limited.materialize(c, manifest, source(payload)).path();

        assertTrue(Files.isDirectory(aPath, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(bPath, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(cPath, LinkOption.NOFOLLOW_LINKS));
    }

    private PersistentArtifactCache cache(
        CacheStatistics statistics,
        long maxBytes
    ) {
        return new PersistentArtifactCache(
            this.temporaryDirectory.resolve("cache"),
            maxBytes,
            statistics
        );
    }

    private static CacheKey key(String id) {
        return new CacheKey(
            "native-runtime",
            1,
            Map.of("bundle", id, "blockframe", "test")
        );
    }

    private static ArtifactSource source(byte[] payload) {
        return ignored -> new ByteArrayInputStream(payload);
    }

    private static ImmutableArtifactManifest manifest(
        String name,
        byte[] payload
    ) throws Exception {
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(payload));
        return ImmutableArtifactManifest.of(
            List.of(
                new ImmutableArtifactManifest.Artifact(
                    name,
                    payload.length,
                    digest
                )
            )
        );
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    private static long treeBytes(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .sum();
        }
    }
}
