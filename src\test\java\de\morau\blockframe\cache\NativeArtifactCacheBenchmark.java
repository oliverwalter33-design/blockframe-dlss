package de.morau.blockframe.cache;

import de.morau.blockframe.profiler.CacheStatistics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reproducible micro-benchmark for the exact packaged 62,557,070-byte native
 * bundle. It performs five independent empty-cache writes and five verified
 * reads from one primed cache. There is deliberately no speed assertion.
 */
public final class NativeArtifactCacheBenchmark {
    private static final int RUNS = 5;
    private static final long EXPECTED_ARTIFACT_BYTES = 62_557_070L;
    private static final long BUDGET_BYTES = 256L * 1024L * 1024L;
    private static final String RESOURCE_PREFIX =
        "/assets/nvidia_dlss/native/win-x64/";

    private NativeArtifactCacheBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        Path output = arguments.length == 0
            ? Path.of("benchmarks", "phase2-native-cache.csv")
            : Path.of(arguments[0]);
        Path scratch = Files.createTempDirectory(
            "blockframe-native-cache-benchmark-"
        ).toAbsolutePath().normalize();
        List<Row> rows = new ArrayList<>(RUNS * 2);
        try {
            CacheKey key = readKey();
            ImmutableArtifactManifest manifest = readManifest();
            if (manifest.totalBytes() != EXPECTED_ARTIFACT_BYTES) {
                throw new IllegalStateException(
                    "Unexpected native bundle size: " + manifest.totalBytes()
                );
            }
            if (!manifest.bundleSha256().equals(
                key.dimensions().get("artifact-bundle-sha256")
            )) {
                throw new IllegalStateException(
                    "Generated key and native manifest are not bound together"
                );
            }
            ArtifactSource source = name ->
                requiredResource(RESOURCE_PREFIX + name);

            for (int iteration = 1; iteration <= RUNS; iteration++) {
                Path root = scratch.resolve("cold-" + iteration);
                CacheStatistics statistics = new CacheStatistics();
                PersistentArtifactCache cache = new PersistentArtifactCache(
                    root,
                    BUDGET_BYTES,
                    statistics
                );
                rows.add(run(
                    "cold",
                    iteration,
                    cache,
                    key,
                    manifest,
                    source,
                    statistics,
                    PersistentArtifactCache.Status.MISS_WRITTEN
                ));
            }

            Path warmRoot = scratch.resolve("warm");
            PersistentArtifactCache primingCache = new PersistentArtifactCache(
                warmRoot,
                BUDGET_BYTES,
                new CacheStatistics()
            );
            PersistentArtifactCache.Result prime = primingCache.materialize(
                key,
                manifest,
                source
            );
            requireStatus(
                "warm prime",
                prime,
                PersistentArtifactCache.Status.MISS_WRITTEN
            );

            CacheStatistics warmStatistics = new CacheStatistics();
            PersistentArtifactCache warmCache = new PersistentArtifactCache(
                warmRoot,
                BUDGET_BYTES,
                warmStatistics
            );
            for (int iteration = 1; iteration <= RUNS; iteration++) {
                rows.add(run(
                    "warm",
                    iteration,
                    warmCache,
                    key,
                    manifest,
                    source,
                    warmStatistics,
                    PersistentArtifactCache.Status.HIT
                ));
            }

            writeCsv(output, rows);
            printSummary(rows, output);
        } finally {
            deleteOwnedScratch(scratch);
        }
    }

    private static Row run(
        String phase,
        int iteration,
        PersistentArtifactCache cache,
        CacheKey key,
        ImmutableArtifactManifest manifest,
        ArtifactSource source,
        CacheStatistics statistics,
        PersistentArtifactCache.Status expected
    ) throws IOException {
        long started = System.nanoTime();
        PersistentArtifactCache.Result result = cache.materialize(
            key,
            manifest,
            source
        );
        long wallNanos = System.nanoTime() - started;
        requireStatus(phase + " " + iteration, result, expected);
        CacheStatistics.Snapshot snapshot = statistics.snapshot();
        return new Row(
            phase,
            iteration,
            result.status(),
            wallNanos,
            result.lookupNanos(),
            result.writeNanos(),
            result.artifactBytes(),
            snapshot.bytesOnDisk(),
            result.persistent()
        );
    }

    private static void requireStatus(
        String label,
        PersistentArtifactCache.Result result,
        PersistentArtifactCache.Status expected
    ) {
        if (result.status() != expected || !result.persistent()) {
            throw new IllegalStateException(
                label
                    + " expected persistent "
                    + expected
                    + " but got "
                    + result
            );
        }
    }

    private static CacheKey readKey() throws IOException {
        try (InputStream input = requiredResource(
            "/META-INF/blockframe/native-runtime-v1.key"
        )) {
            return CacheKey.parseCanonical(input.readAllBytes());
        }
    }

    private static ImmutableArtifactManifest readManifest()
        throws IOException {
        try (InputStream input = requiredResource(
            "/META-INF/blockframe/native-runtime-v1.manifest"
        )) {
            return ImmutableArtifactManifest.parseCanonical(
                input.readAllBytes()
            );
        }
    }

    private static InputStream requiredResource(String name)
        throws IOException {
        InputStream input =
            NativeArtifactCacheBenchmark.class.getResourceAsStream(name);
        if (input == null) {
            throw new IOException("Required benchmark resource is missing: " + name);
        }
        return input;
    }

    private static void writeCsv(Path output, List<Row> rows)
        throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder csv = new StringBuilder(1024);
        csv.append(
            "phase,iteration,status,wall_nanos,lookup_nanos,write_nanos,"
        );
        csv.append("artifact_bytes,cache_bytes_on_disk,persistent\n");
        for (Row row : rows) {
            csv.append(row.phase()).append(',');
            csv.append(row.iteration()).append(',');
            csv.append(row.status()).append(',');
            csv.append(row.wallNanos()).append(',');
            csv.append(row.lookupNanos()).append(',');
            csv.append(row.writeNanos()).append(',');
            csv.append(row.artifactBytes()).append(',');
            csv.append(row.cacheBytesOnDisk()).append(',');
            csv.append(row.persistent()).append('\n');
        }
        Files.writeString(absolute, csv, StandardCharsets.UTF_8);
    }

    private static void printSummary(List<Row> rows, Path output) {
        System.out.printf(
            Locale.ROOT,
            "context java=%s vendor=%s os=%s %s arch=%s%n",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch")
        );
        for (String phase : List.of("cold", "warm")) {
            long[] samples = rows.stream()
                .filter(row -> phase.equals(row.phase()))
                .mapToLong(Row::wallNanos)
                .sorted()
                .toArray();
            long hits = rows.stream()
                .filter(row -> phase.equals(row.phase()))
                .filter(row -> row.status() == PersistentArtifactCache.Status.HIT)
                .count();
            long median = samples[samples.length / 2];
            System.out.printf(
                Locale.ROOT,
                "%s runs=%d hit-rate=%.1f%% median=%.3f ms min=%.3f ms max=%.3f ms%n",
                phase,
                samples.length,
                hits * 100.0D / samples.length,
                median / 1_000_000.0D,
                samples[0] / 1_000_000.0D,
                samples[samples.length - 1] / 1_000_000.0D
            );
        }
        System.out.println("CSV: " + output.toAbsolutePath().normalize());
    }

    private static void deleteOwnedScratch(Path scratch) throws IOException {
        Path name = scratch.getFileName();
        if (name == null || !name.toString().startsWith(
            "blockframe-native-cache-benchmark-"
        )) {
            throw new IOException("Refusing to remove unexpected path: " + scratch);
        }
        Files.walkFileTree(
            scratch,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
                ) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException failure
                ) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    private record Row(
        String phase,
        int iteration,
        PersistentArtifactCache.Status status,
        long wallNanos,
        long lookupNanos,
        long writeNanos,
        long artifactBytes,
        long cacheBytesOnDisk,
        boolean persistent
    ) {
    }
}
