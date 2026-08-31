package de.morau.blockframe.cache;

import de.morau.blockframe.profiler.CacheStatistics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Persistent cache for immutable, manifest-declared multi-file artifacts.
 *
 * <p>Entries are validated in full before use and published only by a
 * same-filesystem atomic directory move. Cache infrastructure failures fall
 * back to a verified process-private directory; source-integrity failures are
 * never hidden by the fallback.</p>
 */
public final class PersistentArtifactCache {
    private static final String ENTRY_FILE = ".blockframe-entry";
    private static final String KEY_FILE = ".blockframe-key";
    private static final String MANIFEST_FILE = ".blockframe-manifest";
    private static final String LOCK_FILE = ".blockframe-lock";
    private static final String INDEX_FILE = ".blockframe-lru-v1";
    private static final String ENTRIES_DIRECTORY = "entries";
    private static final String TRANSIENT_PREFIX =
        "blockframe-native-cache-bypass-";
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int SMALL_FILE_LIMIT = 512 * 1024;
    private static final int CLEANUP_LIMIT = 32;
    private static final int MAX_INDEX_ENTRIES = 10_000;
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS =
        new ConcurrentHashMap<>();

    private final Path root;
    private final Path entriesDirectory;
    private final long maxBytes;
    private final CacheStatistics statistics;
    private final Object jvmLock;
    private final AtomicMoveStrategy atomicMoves;

    public PersistentArtifactCache(
        Path root,
        long maxBytes,
        CacheStatistics statistics
    ) {
        this(root, maxBytes, statistics, PersistentArtifactCache::atomicMove);
    }

    PersistentArtifactCache(
        Path root,
        long maxBytes,
        CacheStatistics statistics,
        AtomicMoveStrategy atomicMoves
    ) {
        this.root = Objects.requireNonNull(root, "root")
            .toAbsolutePath()
            .normalize();
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.entriesDirectory = this.root.resolve(ENTRIES_DIRECTORY);
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.root, ignored -> new Object());
        this.atomicMoves = Objects.requireNonNull(atomicMoves, "atomicMoves");
        this.statistics.markAttached();
    }

    /**
     * Returns a directory whose direct children include the manifest
     * artifacts and, for persistent results, reserved dot-prefixed cache
     * metadata. The source is not opened on a valid persistent hit.
     */
    public Result materialize(
        CacheKey key,
        ImmutableArtifactManifest manifest,
        ArtifactSource source
    ) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(source, "source");

        if (this.estimatedEntryBytes(key, manifest) > this.maxBytes) {
            long started = System.nanoTime();
            try {
                this.removeOversizedPersistentEntry(key);
            } catch (IOException | OverlappingFileLockException ignored) {
                // An unusable persistent store must not block the verified
                // process-private fallback.
            }
            long lookupNanos = System.nanoTime() - started;
            this.statistics.recordMiss(lookupNanos);
            return this.transientResult(
                manifest,
                source,
                lookupNanos
            );
        }

        Attempt attempt = new Attempt();
        try {
            synchronized (this.jvmLock) {
                return this.withProcessLock(
                    () -> this.materializeLocked(key, manifest, source, attempt)
                );
            }
        } catch (SourceIntegrityException exception) {
            throw exception;
        } catch (IOException | OverlappingFileLockException exception) {
            if (!attempt.lookupRecorded) {
                long lookupNanos = Math.max(0L, System.nanoTime() - attempt.startedNanos);
                this.statistics.recordMiss(lookupNanos);
                attempt.lookupNanos = lookupNanos;
                attempt.lookupRecorded = true;
            }
            return this.transientResult(
                manifest,
                source,
                attempt.lookupNanos
            );
        }
    }

    /** Invalidates only the exact content-addressed key directory. */
    public boolean invalidate(CacheKey key) throws IOException {
        Objects.requireNonNull(key, "key");
        synchronized (this.jvmLock) {
            return this.withProcessLock(() -> {
                Path target = this.entryPath(key);
                LruIndex index = this.loadAndReconcileIndex();
                boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (existed) {
                    Path quarantine = this.quarantine(target, "invalidate");
                    deleteTreeNoFollow(quarantine);
                }
                index.entries.remove(key.digestHex());
                this.writeIndex(index);
                this.updateBytesOnDisk();
                return existed;
            });
        }
    }

    private Result materializeLocked(
        CacheKey key,
        ImmutableArtifactManifest manifest,
        ArtifactSource source,
        Attempt attempt
    ) throws IOException {
        this.cleanupAbandoned();
        LruIndex index = this.loadAndReconcileIndex();
        Path target = this.entryPath(key);
        boolean rejected = false;

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            long lookupStarted = System.nanoTime();
            try {
                this.validateEntry(target, key, manifest);
                long lookupNanos = System.nanoTime() - lookupStarted;
                index.touch(key.digestHex(), directorySizeNoFollow(target));
                this.prune(index, key.digestHex());
                this.writeIndex(index);
                this.updateBytesOnDisk();
                this.statistics.recordHit(manifest.totalBytes(), lookupNanos);
                attempt.lookupRecorded = true;
                attempt.lookupNanos = lookupNanos;
                return new Result(
                    Status.HIT,
                    target,
                    manifest.totalBytes(),
                    lookupNanos,
                    0L,
                    true
                );
            } catch (InvalidEntryException invalid) {
                long lookupNanos = System.nanoTime() - lookupStarted;
                this.statistics.recordRejectedEntry(lookupNanos);
                attempt.lookupRecorded = true;
                attempt.lookupNanos = lookupNanos;
                rejected = true;
                Path quarantine = this.quarantine(target, "corrupt");
                deleteTreeNoFollow(quarantine);
                index.entries.remove(key.digestHex());
            }
        } else {
            long lookupNanos = System.nanoTime() - attempt.startedNanos;
            this.statistics.recordMiss(lookupNanos);
            attempt.lookupRecorded = true;
            attempt.lookupNanos = lookupNanos;
        }

        long writeStarted = System.nanoTime();
        Path temporary = this.entriesDirectory.resolve(
            ".tmp-" + key.digestHex() + "-" + UUID.randomUUID()
        );
        boolean published = false;
        try {
            Files.createDirectory(temporary);
            ensurePlainDirectory(temporary);
            this.writePersistentEntry(temporary, key, manifest, source);
            try {
                this.validateEntry(temporary, key, manifest);
            } catch (InvalidEntryException exception) {
                throw new IOException(
                    "Staged cache entry failed self-validation",
                    exception
                );
            }
            this.atomicMoves.move(temporary, target, false);
            published = true;
        } finally {
            if (!published) {
                deleteTreeNoFollowIfExists(temporary);
            }
        }
        long writeNanos = System.nanoTime() - writeStarted;
        long entryBytes = directorySizeNoFollow(target);
        index.touch(key.digestHex(), entryBytes);
        this.prune(index, key.digestHex());
        this.writeIndex(index);
        this.updateBytesOnDisk();
        this.statistics.recordWrite(manifest.totalBytes(), writeNanos);
        return new Result(
            rejected ? Status.REJECTED_REBUILT : Status.MISS_WRITTEN,
            target,
            manifest.totalBytes(),
            attempt.lookupNanos,
            writeNanos,
            true
        );
    }

    private Result transientResult(
        ImmutableArtifactManifest manifest,
        ArtifactSource source,
        long lookupNanos
    ) throws IOException {
        long writeStarted = System.nanoTime();
        cleanupDeadProcessTransientDirectories();
        Path transientDirectory = Files.createTempDirectory(
            TRANSIENT_PREFIX + ProcessHandle.current().pid() + "-"
        ).toAbsolutePath().normalize();
        boolean complete = false;
        try {
            ensurePlainDirectory(transientDirectory);
            this.writeArtifacts(transientDirectory, manifest, source);
            try {
                this.validateTransientArtifacts(
                    transientDirectory,
                    manifest
                );
            } catch (InvalidEntryException exception) {
                throw new IOException(
                    "Transient cache materialization failed validation",
                    exception
                );
            }
            registerDeleteOnExit(transientDirectory, manifest);
            complete = true;
            return new Result(
                Status.BYPASSED,
                transientDirectory,
                manifest.totalBytes(),
                lookupNanos,
                System.nanoTime() - writeStarted,
                false
            );
        } finally {
            if (!complete) {
                deleteTreeNoFollowIfExists(transientDirectory);
            }
        }
    }

    private <T> T withProcessLock(IoSupplier<T> action) throws IOException {
        this.initializeLayout();
        Path lockPath = this.root.resolve(LOCK_FILE);
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
            && (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(lockPath))) {
            throw new IOException("Cache lock is not a plain file");
        }
        OpenOption[] options = {
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        };
        try (FileChannel channel = FileChannel.open(lockPath, options);
             FileLock ignored = channel.lock()) {
            return action.get();
        }
    }

    private void initializeLayout() throws IOException {
        Files.createDirectories(this.root);
        ensurePlainDirectory(this.root);
        if (!Files.exists(this.entriesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(this.entriesDirectory);
        }
        ensurePlainDirectory(this.entriesDirectory);
    }

    private Path entryPath(CacheKey key) throws IOException {
        String digest = key.digestHex();
        if (!DIGEST.matcher(digest).matches()) {
            throw new IOException("Invalid cache-key digest");
        }
        Path target = this.entriesDirectory.resolve(digest).normalize();
        if (!target.getParent().equals(this.entriesDirectory)) {
            throw new IOException("Cache entry escaped its root");
        }
        return target;
    }

    private void writePersistentEntry(
        Path directory,
        CacheKey key,
        ImmutableArtifactManifest manifest,
        ArtifactSource source
    ) throws IOException {
        writeSmallFile(directory.resolve(KEY_FILE), key.canonicalBytes());
        writeSmallFile(directory.resolve(MANIFEST_FILE), manifest.canonicalBytes());
        this.writeArtifacts(directory, manifest, source);
        writeSmallFile(
            directory.resolve(ENTRY_FILE),
            entryEnvelope(key, manifest)
        );
    }

    private void writeArtifacts(
        Path directory,
        ImmutableArtifactManifest manifest,
        ArtifactSource source
    ) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        for (ImmutableArtifactManifest.Artifact artifact : manifest.artifacts()) {
            Path target = directory.resolve(artifact.name()).normalize();
            if (!target.getParent().equals(directory)) {
                throw new SourceIntegrityException("Artifact escaped output directory");
            }
            MessageDigest digest = sha256();
            long written = 0L;
            try (FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS
                 )) {
                InputStream opened;
                try {
                    opened = source.open(artifact.name());
                } catch (IOException exception) {
                    throw new SourceIntegrityException(
                        "Could not open artifact source: " + artifact.name(),
                        exception
                    );
                }
                if (opened == null) {
                    throw new SourceIntegrityException(
                        "Artifact source returned null: " + artifact.name()
                    );
                }
                try {
                    while (true) {
                        int count;
                        try {
                            count = opened.read(buffer);
                        } catch (IOException exception) {
                            throw new SourceIntegrityException(
                                "Could not read artifact source: " + artifact.name(),
                                exception
                            );
                        }
                        if (count < 0) {
                            break;
                        }
                        if (count == 0) {
                            continue;
                        }
                        written = Math.addExact(written, count);
                        if (written > artifact.size()) {
                            throw new SourceIntegrityException(
                                "Artifact is larger than its manifest: " + artifact.name()
                            );
                        }
                        digest.update(buffer, 0, count);
                        ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                        while (bytes.hasRemaining()) {
                            output.write(bytes);
                        }
                    }
                } finally {
                    try {
                        opened.close();
                    } catch (IOException exception) {
                        throw new SourceIntegrityException(
                            "Could not close artifact source: " + artifact.name(),
                            exception
                        );
                    }
                }
                output.force(true);
            } catch (ArithmeticException exception) {
                throw new SourceIntegrityException(
                    "Artifact length overflow: " + artifact.name(),
                    exception
                );
            }
            String actualDigest = CacheKey.hex(digest.digest());
            if (written != artifact.size()
                || !actualDigest.equals(artifact.sha256())) {
                throw new SourceIntegrityException(
                    "Artifact does not match manifest: " + artifact.name()
                );
            }
        }
    }

    private void validateEntry(
        Path directory,
        CacheKey key,
        ImmutableArtifactManifest manifest
    ) throws InvalidEntryException {
        try {
            ensurePlainDirectory(directory);
            Set<String> expected = new HashSet<>();
            expected.add(ENTRY_FILE);
            expected.add(KEY_FILE);
            expected.add(MANIFEST_FILE);
            for (ImmutableArtifactManifest.Artifact artifact : manifest.artifacts()) {
                expected.add(artifact.name());
            }
            Set<String> actual = new HashSet<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (!actual.add(name)
                        || Files.isSymbolicLink(child)
                        || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw new InvalidEntryException("Entry has unsafe children");
                    }
                }
            }
            if (!actual.equals(expected)) {
                throw new InvalidEntryException("Entry file set does not match manifest");
            }

            byte[] keyBytes = readSmallFile(directory.resolve(KEY_FILE));
            byte[] manifestBytes = readSmallFile(directory.resolve(MANIFEST_FILE));
            byte[] envelopeBytes = readSmallFile(directory.resolve(ENTRY_FILE));
            CacheKey parsedKey = CacheKey.parseCanonical(keyBytes);
            ImmutableArtifactManifest parsedManifest =
                ImmutableArtifactManifest.parseCanonical(manifestBytes);
            if (!parsedKey.equals(key)
                || !Arrays.equals(keyBytes, key.canonicalBytes())
                || !parsedManifest.artifacts().equals(manifest.artifacts())
                || !Arrays.equals(manifestBytes, manifest.canonicalBytes())
                || !Arrays.equals(envelopeBytes, entryEnvelope(key, manifest))) {
                throw new InvalidEntryException("Entry metadata mismatch");
            }

            this.validateArtifactContents(directory, manifest);
        } catch (InvalidEntryException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidEntryException("Could not validate cache entry", exception);
        }
    }

    private void validateTransientArtifacts(
        Path directory,
        ImmutableArtifactManifest manifest
    ) throws InvalidEntryException {
        try {
            ensurePlainDirectory(directory);
            Set<String> expected = new HashSet<>();
            for (ImmutableArtifactManifest.Artifact artifact :
                manifest.artifacts()) {
                expected.add(artifact.name());
            }
            Set<String> actual = new HashSet<>();
            try (DirectoryStream<Path> children =
                Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (!actual.add(name)
                        || Files.isSymbolicLink(child)
                        || !Files.isRegularFile(
                            child,
                            LinkOption.NOFOLLOW_LINKS
                        )) {
                        throw new InvalidEntryException(
                            "Transient entry has unsafe children"
                        );
                    }
                }
            }
            if (!actual.equals(expected)) {
                throw new InvalidEntryException(
                    "Transient entry file set does not match manifest"
                );
            }
            this.validateArtifactContents(directory, manifest);
        } catch (InvalidEntryException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException |
                 NullPointerException exception) {
            throw new InvalidEntryException(
                "Could not validate transient cache entry",
                exception
            );
        }
    }

    private void validateArtifactContents(
        Path directory,
        ImmutableArtifactManifest manifest
    ) throws IOException, InvalidEntryException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        for (ImmutableArtifactManifest.Artifact artifact :
            manifest.artifacts()) {
            Path file = directory.resolve(artifact.name());
            BasicFileAttributes before = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (!before.isRegularFile()
                || before.isSymbolicLink()
                || before.size() != artifact.size()) {
                throw new InvalidEntryException(
                    "Artifact size/type mismatch"
                );
            }
            MessageDigest digest = sha256();
            long readBytes = 0L;
            try (InputStream input = Files.newInputStream(
                file,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
            )) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        if (count > artifact.size() - readBytes) {
                            throw new InvalidEntryException(
                                "Artifact grew during validation"
                            );
                        }
                        readBytes += count;
                        digest.update(buffer, 0, count);
                    }
                }
            }
            BasicFileAttributes after = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (!after.isRegularFile()
                || after.isSymbolicLink()
                || readBytes != artifact.size()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !CacheKey.hex(digest.digest()).equals(
                    artifact.sha256()
                )) {
                throw new InvalidEntryException(
                    "Artifact checksum mismatch"
                );
            }
        }
    }

    private LruIndex loadAndReconcileIndex() throws IOException {
        Map<String, Long> diskEntries = this.scanEntrySizes();
        LruIndex index;
        Path indexPath = this.root.resolve(INDEX_FILE);
        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            index = new LruIndex();
        } else {
            try {
                index = LruIndex.parse(readSmallFile(indexPath));
            } catch (IllegalArgumentException | IOException exception) {
                Path quarantine = this.quarantine(indexPath, "index");
                deleteTreeNoFollow(quarantine);
                this.statistics.recordRejectedEntry(0L);
                index = new LruIndex();
            }
        }
        index.entries.keySet().retainAll(diskEntries.keySet());
        for (Map.Entry<String, Long> entry : diskEntries.entrySet()) {
            LruRecord previous = index.entries.get(entry.getKey());
            index.entries.put(
                entry.getKey(),
                new LruRecord(
                    previous == null ? 0L : previous.sequence,
                    entry.getValue()
                )
            );
        }
        index.repairCounter();
        return index;
    }

    private Map<String, Long> scanEntrySizes() throws IOException {
        Map<String, Long> result = new HashMap<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(this.entriesDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (DIGEST.matcher(name).matches()
                    && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(child)) {
                    result.put(name, directorySizeNoFollow(child));
                }
            }
        }
        return result;
    }

    private void prune(LruIndex index, String protectedKey) throws IOException {
        long total = index.totalBytes();
        List<Map.Entry<String, LruRecord>> candidates =
            new ArrayList<>(index.entries.entrySet());
        candidates.sort(
            Comparator
                .comparingLong((Map.Entry<String, LruRecord> entry) ->
                    entry.getValue().sequence)
                .thenComparing(Map.Entry::getKey)
        );
        for (Map.Entry<String, LruRecord> candidate : candidates) {
            if (total <= this.maxBytes) {
                break;
            }
            if (candidate.getKey().equals(protectedKey)) {
                continue;
            }
            Path victim = this.entriesDirectory.resolve(candidate.getKey());
            Path quarantine = this.quarantine(victim, "lru");
            deleteTreeNoFollow(quarantine);
            total -= candidate.getValue().bytes;
            index.entries.remove(candidate.getKey());
        }
    }

    private void writeIndex(LruIndex index) throws IOException {
        byte[] encoded = index.canonicalBytes();
        Path temporary = this.root.resolve(
            ".tmp-index-" + UUID.randomUUID()
        );
        boolean moved = false;
        try {
            writeSmallFile(temporary, encoded);
            this.atomicMoves.move(
                temporary,
                this.root.resolve(INDEX_FILE),
                true
            );
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private Path quarantine(Path target, String reason) throws IOException {
        if (!target.normalize().startsWith(this.root)
            || target.equals(this.root)
            || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid quarantine target");
        }
        Path quarantine = target.resolveSibling(
            ".rejected-" + reason + "-" + UUID.randomUUID()
        );
        this.atomicMoves.move(target, quarantine, false);
        return quarantine;
    }

    private void cleanupAbandoned() throws IOException {
        int remaining = CLEANUP_LIMIT;
        remaining = cleanupEntryChildren(
            this.entriesDirectory,
            remaining
        );
        cleanupChildren(this.root, remaining);
    }

    private static int cleanupEntryChildren(
        Path directory,
        int limit
    ) throws IOException {
        if (limit <= 0) {
            return 0;
        }
        List<Path> abandoned = new ArrayList<>();
        try (DirectoryStream<Path> children =
            Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                boolean validEntryDirectory =
                    DIGEST.matcher(name).matches()
                        && Files.isDirectory(
                            child,
                            LinkOption.NOFOLLOW_LINKS
                        )
                        && !Files.isSymbolicLink(child);
                if (!validEntryDirectory) {
                    abandoned.add(child);
                }
            }
        }
        return deleteAbandoned(abandoned, limit);
    }

    private static int cleanupChildren(Path directory, int limit) throws IOException {
        if (limit <= 0) {
            return 0;
        }
        List<Path> abandoned = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".tmp-") || name.startsWith(".rejected-")) {
                    abandoned.add(child);
                }
            }
        }
        return deleteAbandoned(abandoned, limit);
    }

    private static int deleteAbandoned(
        List<Path> abandoned,
        int limit
    ) throws IOException {
        abandoned.sort(Comparator.comparing(path -> path.getFileName().toString()));
        int remaining = limit;
        for (Path child : abandoned) {
            if (remaining-- <= 0) {
                break;
            }
            deleteTreeNoFollow(child);
        }
        return Math.max(0, remaining);
    }

    private void updateBytesOnDisk() throws IOException {
        long entryBytes = 0L;
        try {
            for (long bytes : this.scanEntrySizes().values()) {
                entryBytes = Math.addExact(entryBytes, bytes);
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Cache entry size overflow", exception);
        }
        this.statistics.setBytesOnDisk(entryBytes);
    }

    private long estimatedEntryBytes(
        CacheKey key,
        ImmutableArtifactManifest manifest
    ) {
        try {
            return Math.addExact(
                manifest.totalBytes(),
                Math.addExact(
                    key.canonicalBytes().length,
                    Math.addExact(
                        manifest.canonicalBytes().length,
                        entryEnvelope(key, manifest).length
                    )
                )
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void removeOversizedPersistentEntry(CacheKey key) throws IOException {
        synchronized (this.jvmLock) {
            try {
                this.withProcessLock(() -> {
                    Path target = this.entryPath(key);
                    LruIndex index = this.loadAndReconcileIndex();
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        Path quarantine = this.quarantine(target, "oversize");
                        deleteTreeNoFollow(quarantine);
                    }
                    index.entries.remove(key.digestHex());
                    this.writeIndex(index);
                    this.updateBytesOnDisk();
                    return null;
                });
            } catch (OverlappingFileLockException exception) {
                throw new IOException("Cache lock overlaps in this JVM", exception);
            }
        }
    }

    private static byte[] entryEnvelope(
        CacheKey key,
        ImmutableArtifactManifest manifest
    ) {
        String text =
            "BLOCKFRAME_CACHE_ENTRY_V1\n"
                + "schema=1\n"
                + "key=" + key.digestHex() + "\n"
                + "manifest=" + CacheKey.sha256Hex(manifest.canonicalBytes()) + "\n"
                + "bundle=" + manifest.bundleSha256() + "\n"
                + "bytes=" + manifest.totalBytes() + "\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeSmallFile(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static byte[] readSmallFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path)) {
            throw new IOException("Metadata is not a regular file");
        }
        long size = Files.size(path);
        if (size < 0L || size > SMALL_FILE_LIMIT) {
            throw new IOException("Metadata file is too large");
        }
        return Files.readAllBytes(path);
    }

    private static void atomicMove(
        Path source,
        Path target,
        boolean replace
    ) throws IOException {
        try {
            if (replace) {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic cache publication is unsupported", exception);
        }
    }

    @FunctionalInterface
    interface AtomicMoveStrategy {
        void move(Path source, Path target, boolean replace)
            throws IOException;
    }

    private static void ensurePlainDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cache path is not a plain directory: " + directory);
        }
    }

    private static long directorySizeNoFollow(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        BasicFileAttributes attributes = Files.readAttributes(
            root,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink()) {
            return attributes.size();
        }
        if (attributes.isRegularFile()) {
            return attributes.size();
        }
        if (!attributes.isDirectory()) {
            return 0L;
        }
        long total = 0L;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                total = Math.addExact(total, directorySizeNoFollow(child));
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Cache size overflow", exception);
        }
        return total;
    }

    private static void deleteTreeNoFollowIfExists(Path root) throws IOException {
        if (root != null && Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeNoFollow(root);
        }
    }

    private static void deleteTreeNoFollow(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            root,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isDirectory() && !attributes.isSymbolicLink()) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    deleteTreeNoFollow(child);
                }
            }
        }
        Files.deleteIfExists(root);
    }

    private static void registerDeleteOnExit(
        Path directory,
        ImmutableArtifactManifest manifest
    ) {
        directory.toFile().deleteOnExit();
        for (ImmutableArtifactManifest.Artifact artifact : manifest.artifacts()) {
            directory.resolve(artifact.name()).toFile().deleteOnExit();
        }
    }

    private static void cleanupDeadProcessTransientDirectories() {
        Path temporaryRoot;
        try {
            temporaryRoot = Path.of(
                System.getProperty("java.io.tmpdir")
            ).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return;
        }
        long currentPid = ProcessHandle.current().pid();
        try (DirectoryStream<Path> children =
            Files.newDirectoryStream(
                temporaryRoot,
                TRANSIENT_PREFIX + "*"
            )) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                String suffix = name.substring(TRANSIENT_PREFIX.length());
                int separator = suffix.indexOf('-');
                if (separator <= 0) {
                    continue;
                }
                long ownerPid;
                try {
                    ownerPid = Long.parseLong(
                        suffix.substring(0, separator)
                    );
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (ownerPid == currentPid) {
                    continue;
                }
                boolean alive;
                try {
                    alive = ProcessHandle.of(ownerPid)
                        .map(ProcessHandle::isAlive)
                        .orElse(false);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (!alive) {
                    try {
                        deleteTreeNoFollow(child);
                    } catch (IOException | SecurityException ignored) {
                        // A live loader or filesystem policy can keep the
                        // directory; a later process will retry safely.
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best effort and must not disable the fallback.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public enum Status {
        HIT,
        MISS_WRITTEN,
        REJECTED_REBUILT,
        BYPASSED
    }

    public record Result(
        Status status,
        Path path,
        long artifactBytes,
        long lookupNanos,
        long writeNanos,
        boolean persistent
    ) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (artifactBytes < 0L || lookupNanos < 0L || writeNanos < 0L) {
                throw new IllegalArgumentException("Cache result measurements must be non-negative");
            }
            if (persistent != (status != Status.BYPASSED)) {
                throw new IllegalArgumentException("Cache status/persistence mismatch");
            }
        }
    }

    private static final class Attempt {
        private final long startedNanos = System.nanoTime();
        private boolean lookupRecorded;
        private long lookupNanos;
    }

    private static final class InvalidEntryException extends Exception {
        private InvalidEntryException(String message) {
            super(message);
        }

        private InvalidEntryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class SourceIntegrityException extends IOException {
        private SourceIntegrityException(String message) {
            super(message);
        }

        private SourceIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static final class LruRecord {
        private final long sequence;
        private final long bytes;

        private LruRecord(long sequence, long bytes) {
            if (sequence < 0L || bytes < 0L) {
                throw new IllegalArgumentException("Negative LRU metadata");
            }
            this.sequence = sequence;
            this.bytes = bytes;
        }
    }

    private static final class LruIndex {
        private static final String MAGIC = "BLOCKFRAME_CACHE_LRU_V1";
        private final Map<String, LruRecord> entries = new HashMap<>();
        private long nextSequence = 1L;

        private void touch(String key, long bytes) {
            if (this.nextSequence == Long.MAX_VALUE) {
                this.renumber();
            }
            this.entries.put(key, new LruRecord(this.nextSequence++, bytes));
        }

        private void repairCounter() {
            long maximum = 0L;
            for (LruRecord record : this.entries.values()) {
                maximum = Math.max(maximum, record.sequence);
            }
            if (maximum == Long.MAX_VALUE) {
                this.renumber();
            } else {
                this.nextSequence = Math.max(this.nextSequence, maximum + 1L);
            }
        }

        private void renumber() {
            List<Map.Entry<String, LruRecord>> sorted =
                new ArrayList<>(this.entries.entrySet());
            sorted.sort(
                Comparator
                    .comparingLong((Map.Entry<String, LruRecord> entry) ->
                        entry.getValue().sequence)
                    .thenComparing(Map.Entry::getKey)
            );
            long sequence = 1L;
            for (Map.Entry<String, LruRecord> entry : sorted) {
                this.entries.put(
                    entry.getKey(),
                    new LruRecord(sequence++, entry.getValue().bytes)
                );
            }
            this.nextSequence = sequence;
        }

        private long totalBytes() throws IOException {
            long total = 0L;
            try {
                for (LruRecord record : this.entries.values()) {
                    total = Math.addExact(total, record.bytes);
                }
            } catch (ArithmeticException exception) {
                throw new IOException("LRU size overflow", exception);
            }
            return total;
        }

        private byte[] canonicalBytes() {
            List<String> keys = new ArrayList<>(this.entries.keySet());
            keys.sort(String::compareTo);
            StringBuilder text = new StringBuilder(128 + keys.size() * 100);
            text.append(MAGIC).append('\n');
            text.append("schema=1\n");
            text.append("next=").append(this.nextSequence).append('\n');
            text.append("count=").append(keys.size()).append('\n');
            for (String key : keys) {
                LruRecord record = this.entries.get(key);
                text.append("entry=")
                    .append(key)
                    .append('\t')
                    .append(record.sequence)
                    .append('\t')
                    .append(record.bytes)
                    .append('\n');
            }
            return text.toString().getBytes(StandardCharsets.US_ASCII);
        }

        private static LruIndex parse(byte[] encoded) {
            if (encoded.length == 0 || encoded.length > SMALL_FILE_LIMIT) {
                throw new IllegalArgumentException("Invalid LRU index size");
            }
            String text = new String(encoded, StandardCharsets.US_ASCII);
            if (!Arrays.equals(encoded, text.getBytes(StandardCharsets.US_ASCII))
                || text.indexOf('\r') >= 0
                || !text.endsWith("\n")) {
                throw new IllegalArgumentException("Invalid LRU encoding");
            }
            String[] lines = text.split("\n", -1);
            if (lines.length < 5
                || !MAGIC.equals(lines[0])
                || !"schema=1".equals(lines[1])) {
                throw new IllegalArgumentException("Unknown LRU format");
            }
            long next = parseCanonicalLong(lines[2], "next=");
            long countLong = parseCanonicalLong(lines[3], "count=");
            if (next <= 0L
                || countLong > MAX_INDEX_ENTRIES
                || lines.length != countLong + 5L) {
                throw new IllegalArgumentException("LRU count mismatch");
            }
            LruIndex index = new LruIndex();
            index.nextSequence = next;
            String previous = null;
            for (int position = 0; position < (int)countLong; position++) {
                String line = lines[position + 4];
                if (!line.startsWith("entry=")) {
                    throw new IllegalArgumentException("Invalid LRU entry");
                }
                String body = line.substring("entry=".length());
                int first = body.indexOf('\t');
                int second = first < 0 ? -1 : body.indexOf('\t', first + 1);
                if (first <= 0
                    || second <= first + 1
                    || body.indexOf('\t', second + 1) >= 0) {
                    throw new IllegalArgumentException("Invalid LRU separators");
                }
                String key = body.substring(0, first);
                if (!DIGEST.matcher(key).matches()
                    || previous != null && previous.compareTo(key) >= 0) {
                    throw new IllegalArgumentException("Invalid LRU key order");
                }
                long sequence = parseCanonicalLong(
                    body.substring(first + 1, second)
                );
                long bytes = parseCanonicalLong(body.substring(second + 1));
                index.entries.put(key, new LruRecord(sequence, bytes));
                previous = key;
            }
            if (!Arrays.equals(encoded, index.canonicalBytes())) {
                throw new IllegalArgumentException("LRU index is not canonical");
            }
            return index;
        }

        private static long parseCanonicalLong(String line, String prefix) {
            if (!line.startsWith(prefix)) {
                throw new IllegalArgumentException("Missing LRU field");
            }
            return parseCanonicalLong(line.substring(prefix.length()));
        }

        private static long parseCanonicalLong(String value) {
            if (!value.matches("0|[1-9][0-9]*")) {
                throw new IllegalArgumentException("Non-canonical LRU integer");
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("LRU integer out of range", exception);
            }
        }
    }
}
