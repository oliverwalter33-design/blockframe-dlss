package de.morau.blockframe.benchmark.phase2a0b;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Canonical ordinal file inventory used for both Golden verification and
 * physical copy verification.
 */
public final class FixtureInventory {
    public interface CopyCheckpoint {
        void beforeCopy(int fileIndex, Entry entry) throws IOException;
    }

    public record Entry(String path, long size, String sha256) {
        public String canonicalLine() {
            return this.path + "\t" + this.size + "\t" + this.sha256;
        }
    }

    private final List<Entry> entries;
    private final long totalBytes;
    private final String canonicalSha256;

    private FixtureInventory(
        List<Entry> entries,
        long totalBytes,
        String canonicalSha256
    ) {
        this.entries = List.copyOf(entries);
        this.totalBytes = totalBytes;
        this.canonicalSha256 = canonicalSha256;
    }

    static FixtureInventory verified(
        List<Entry> entries,
        long totalBytes,
        String canonicalSha256
    ) {
        return new FixtureInventory(
            entries,
            totalBytes,
            canonicalSha256
        );
    }

    public static FixtureInventory scan(Path root) throws IOException {
        Path normalized = Objects.requireNonNull(root, "root")
            .toAbsolutePath()
            .normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("inventory root is not a directory: " + root);
        }
        ArrayList<Entry> entries = new ArrayList<>();
        long[] bytes = {0L};
        Files.walkFileTree(
            normalized,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
                ) throws IOException {
                    rejectLinkOrSpecial(normalized, directory, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
                ) throws IOException {
                    rejectLinkOrSpecial(normalized, file, attributes);
                    if (!attributes.isRegularFile()) {
                        throw new IOException(
                            "non-regular fixture entry: "
                                + normalized.relativize(file)
                        );
                    }
                    String relative = normalized.relativize(file)
                        .toString()
                        .replace('\\', '/');
                    String digest = sha256(file);
                    entries.add(
                        new Entry(relative, attributes.size(), digest)
                    );
                    bytes[0] = Math.addExact(bytes[0], attributes.size());
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        entries.sort(Comparator.comparing(Entry::path));
        StringBuilder canonical = new StringBuilder(entries.size() * 96);
        for (int index = 0; index < entries.size(); index++) {
            if (index != 0) {
                canonical.append('\n');
            }
            canonical.append(entries.get(index).canonicalLine());
        }
        return new FixtureInventory(
            entries,
            bytes[0],
            sha256(canonical.toString().getBytes(StandardCharsets.UTF_8))
        );
    }

    public static void copyPhysical(
        Path source,
        Path temporaryTarget,
        Path finalTarget,
        FixtureInventory expected,
        CopyCheckpoint checkpoint
    ) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (Files.exists(temporaryTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "temporary copy path already exists: " + temporaryTarget
            );
        }
        if (Files.exists(finalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "run target already exists: " + finalTarget
            );
        }
        Files.createDirectory(temporaryTarget);
        for (int index = 0; index < expected.entries.size(); index++) {
            Entry entry = expected.entries.get(index);
            if (checkpoint != null) {
                checkpoint.beforeCopy(index, entry);
            }
            Path input = safeResolve(source, entry.path());
            Path output = safeResolve(temporaryTarget, entry.path());
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(
                input,
                output,
                StandardCopyOption.COPY_ATTRIBUTES
            );
        }
        FixtureInventory copied = scan(temporaryTarget);
        if (!expected.contentEquals(copied)) {
            throw new IOException(
                "physical copy inventory mismatch; temporary copy preserved at "
                    + temporaryTarget
            );
        }
        try {
            Files.move(
                temporaryTarget,
                finalTarget,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException(
                "atomic run-copy publication unavailable; temporary copy preserved at "
                    + temporaryTarget,
                error
            );
        }
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public int fileCount() {
        return this.entries.size();
    }

    public long totalBytes() {
        return this.totalBytes;
    }

    public String canonicalSha256() {
        return this.canonicalSha256;
    }

    public boolean contentEquals(FixtureInventory other) {
        return other != null
            && this.totalBytes == other.totalBytes
            && this.canonicalSha256.equals(other.canonicalSha256)
            && this.entries.equals(other.entries);
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    public static Path safeResolve(Path root, String relative)
        throws IOException {
        Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar))
            .toAbsolutePath()
            .normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("path escapes fixture root: " + relative);
        }
        return resolved;
    }

    private static void rejectLinkOrSpecial(
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
                "link, reparse point or special entry rejected: "
                    + root.relativize(path)
            );
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
