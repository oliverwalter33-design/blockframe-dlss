package de.morau.blockframe.cache;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict manifest for the immutable native-runtime artifact bundle. */
public final class ImmutableArtifactManifest {
    public static final int SCHEMA_VERSION = 1;
    private static final String MAGIC = "BLOCKFRAME_IMMUTABLE_ARTIFACTS_V1";
    private static final String ARTIFACT_KIND = "native-runtime";
    private static final int MAX_ENCODED_BYTES = 256 * 1024;
    private static final int MAX_ARTIFACTS = 256;
    private static final long MAX_ARTIFACT_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L;
    private static final Pattern SAFE_NAME =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> METADATA_NAMES = Set.of(
        ".blockframe-entry",
        ".blockframe-key",
        ".blockframe-manifest"
    );

    private final List<Artifact> artifacts;
    private final long totalBytes;
    private final String bundleSha256;
    private final byte[] canonicalBytes;

    private ImmutableArtifactManifest(List<Artifact> source) {
        if (source.isEmpty() || source.size() > MAX_ARTIFACTS) {
            throw new IllegalArgumentException("Invalid artifact count");
        }
        List<Artifact> sorted = new ArrayList<>(source);
        sorted.sort((left, right) -> left.name().compareTo(right.name()));
        Set<String> caseInsensitiveNames = new HashSet<>();
        long total = 0L;
        for (Artifact artifact : sorted) {
            Objects.requireNonNull(artifact, "artifact");
            String folded = artifact.name().toLowerCase(Locale.ROOT);
            if (!caseInsensitiveNames.add(folded)) {
                throw new IllegalArgumentException(
                    "Case-insensitive duplicate artifact: " + artifact.name()
                );
            }
            try {
                total = Math.addExact(total, artifact.size());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Artifact sizes overflow", exception);
            }
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Artifact bundle is too large");
            }
        }
        this.artifacts = List.copyOf(sorted);
        this.totalBytes = total;
        byte[] fileLines = fileLines(this.artifacts);
        this.bundleSha256 = CacheKey.sha256Hex(fileLines);
        this.canonicalBytes = serialize(this.artifacts, this.bundleSha256);
    }

    public static ImmutableArtifactManifest of(List<Artifact> artifacts) {
        return new ImmutableArtifactManifest(
            List.copyOf(Objects.requireNonNull(artifacts, "artifacts"))
        );
    }

    public static ImmutableArtifactManifest parseCanonical(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid manifest size");
        }
        String text = decodeStrict(encoded);
        if (text.indexOf('\r') >= 0 || !text.endsWith("\n")) {
            throw new IllegalArgumentException("Manifest must use canonical LF lines");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 7 || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("Truncated artifact manifest");
        }
        if (!MAGIC.equals(lines[0])
            || !"schema=1".equals(lines[1])
            || !("artifact=" + ARTIFACT_KIND).equals(lines[2])) {
            throw new IllegalArgumentException("Unknown artifact manifest format");
        }
        int count = parseCount(lines[3]);
        if (count <= 0 || count > MAX_ARTIFACTS || lines.length != count + 6) {
            throw new IllegalArgumentException("Artifact count mismatch");
        }
        List<Artifact> artifacts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String line = lines[index + 4];
            if (!line.startsWith("file=")) {
                throw new IllegalArgumentException("Invalid artifact line");
            }
            String body = line.substring("file=".length());
            int first = body.indexOf('\t');
            int second = first < 0 ? -1 : body.indexOf('\t', first + 1);
            if (first <= 0
                || second <= first + 1
                || body.indexOf('\t', second + 1) >= 0) {
                throw new IllegalArgumentException("Invalid artifact separators");
            }
            String name = body.substring(0, first);
            long size = parseLong(body.substring(first + 1, second));
            String sha256 = body.substring(second + 1);
            artifacts.add(new Artifact(name, size, sha256));
        }
        String bundleLine = lines[count + 4];
        if (!bundleLine.startsWith("bundle=")) {
            throw new IllegalArgumentException("Missing bundle digest");
        }
        String bundle = bundleLine.substring("bundle=".length());
        if (!SHA256.matcher(bundle).matches()) {
            throw new IllegalArgumentException("Invalid bundle digest");
        }
        ImmutableArtifactManifest parsed = of(artifacts);
        if (!bundle.equals(parsed.bundleSha256)
            || !Arrays.equals(encoded, parsed.canonicalBytes)) {
            throw new IllegalArgumentException("Manifest is not canonical or has a bad bundle digest");
        }
        return parsed;
    }

    public List<Artifact> artifacts() {
        return this.artifacts;
    }

    public long totalBytes() {
        return this.totalBytes;
    }

    public String bundleSha256() {
        return this.bundleSha256;
    }

    public byte[] canonicalBytes() {
        return this.canonicalBytes.clone();
    }

    private static byte[] serialize(List<Artifact> artifacts, String bundle) {
        StringBuilder text = new StringBuilder(256);
        text.append(MAGIC).append('\n');
        text.append("schema=1\n");
        text.append("artifact=").append(ARTIFACT_KIND).append('\n');
        text.append("count=").append(artifacts.size()).append('\n');
        for (Artifact artifact : artifacts) {
            appendFileLine(text, artifact);
        }
        text.append("bundle=").append(bundle).append('\n');
        byte[] encoded = text.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Artifact manifest is too large");
        }
        return encoded;
    }

    private static byte[] fileLines(List<Artifact> artifacts) {
        StringBuilder text = new StringBuilder(artifacts.size() * 96);
        for (Artifact artifact : artifacts) {
            appendFileLine(text, artifact);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendFileLine(StringBuilder text, Artifact artifact) {
        text.append("file=")
            .append(artifact.name())
            .append('\t')
            .append(artifact.size())
            .append('\t')
            .append(artifact.sha256())
            .append('\n');
    }

    private static int parseCount(String line) {
        if (!line.startsWith("count=")) {
            throw new IllegalArgumentException("Missing artifact count");
        }
        long value = parseLong(line.substring("count=".length()));
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Artifact count out of range");
        }
        return (int)value;
    }

    private static long parseLong(String value) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Non-canonical non-negative integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Integer out of range", exception);
        }
    }

    private static String decodeStrict(byte[] encoded) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Manifest is not valid UTF-8", exception);
        }
    }

    public record Artifact(String name, long size, String sha256) {
        public Artifact {
            name = validateName(name);
            if (size < 0L || size > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("Invalid artifact size");
            }
            sha256 = Objects.requireNonNull(sha256, "sha256");
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Artifact SHA-256 must be lowercase hexadecimal");
            }
        }

        private static String validateName(String value) {
            value = Objects.requireNonNull(value, "name");
            String folded = value.toLowerCase(Locale.ROOT);
            String base = folded.contains(".")
                ? folded.substring(0, folded.indexOf('.'))
                : folded;
            boolean windowsReserved =
                Set.of("con", "prn", "aux", "nul").contains(base)
                    || base.matches("com[1-9]")
                    || base.matches("lpt[1-9]");
            if (!SAFE_NAME.matcher(value).matches()
                || ".".equals(value)
                || "..".equals(value)
                || value.endsWith(".")
                || METADATA_NAMES.contains(folded)
                || windowsReserved) {
                throw new IllegalArgumentException("Unsafe artifact name: " + value);
            }
            return value;
        }
    }
}
