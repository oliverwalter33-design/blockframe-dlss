package de.morau.blockframe.cache;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Canonical, content-addressed identity for one persistent cache entry. */
public record CacheKey(
    String kind,
    int schemaVersion,
    Map<String, String> dimensions
) {
    private static final String MAGIC = "BLOCKFRAME_CACHE_KEY_V1";
    private static final int MAX_ENCODED_BYTES = 256 * 1024;
    private static final int MAX_DIMENSIONS = 128;
    private static final Pattern KIND =
        Pattern.compile("[a-z0-9][a-z0-9._/-]{0,63}");
    private static final Pattern DIMENSION =
        Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public CacheKey {
        kind = requireKind(kind);
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(dimensions, "dimensions");
        if (dimensions.size() > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Too many cache-key dimensions");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : dimensions.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "dimension name");
            String value = Objects.requireNonNull(entry.getValue(), "dimension value");
            if (!DIMENSION.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid dimension name: " + name);
            }
            validateValue(value);
            if (sorted.put(name, value) != null) {
                throw new IllegalArgumentException("Duplicate dimension: " + name);
            }
        }
        dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public byte[] canonicalBytes() {
        StringBuilder text = new StringBuilder(256);
        text.append(MAGIC).append('\n');
        text.append("schema=").append(this.schemaVersion).append('\n');
        text.append("kind=").append(this.kind).append('\n');
        text.append("count=").append(this.dimensions.size()).append('\n');
        for (Map.Entry<String, String> entry : this.dimensions.entrySet()) {
            text.append("dimension=")
                .append(entry.getKey())
                .append('\t')
                .append(entry.getValue())
                .append('\n');
        }
        byte[] encoded = text.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Canonical cache key is too large");
        }
        return encoded;
    }

    public String digestHex() {
        return sha256Hex(this.canonicalBytes());
    }

    public static CacheKey parseCanonical(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid canonical cache-key size");
        }
        String text = decodeStrict(encoded);
        if (text.indexOf('\r') >= 0 || !text.endsWith("\n")) {
            throw new IllegalArgumentException("Cache key must use canonical LF lines");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 5 || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("Truncated cache key");
        }
        if (!MAGIC.equals(lines[0])) {
            throw new IllegalArgumentException("Unknown cache-key format");
        }
        int schema = parsePositive(lines[1], "schema=");
        String kind = exactValue(lines[2], "kind=");
        int count = parseNonNegative(lines[3], "count=");
        if (count > MAX_DIMENSIONS || lines.length != count + 5) {
            throw new IllegalArgumentException("Cache-key dimension count mismatch");
        }
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String line = lines[index + 4];
            if (!line.startsWith("dimension=")) {
                throw new IllegalArgumentException("Invalid cache-key dimension line");
            }
            int separator = line.indexOf('\t', "dimension=".length());
            if (separator < 0 || line.indexOf('\t', separator + 1) >= 0) {
                throw new IllegalArgumentException("Invalid cache-key dimension separator");
            }
            String name = line.substring("dimension=".length(), separator);
            String value = line.substring(separator + 1);
            if (dimensions.put(name, value) != null) {
                throw new IllegalArgumentException("Duplicate cache-key dimension");
            }
        }
        CacheKey parsed = new CacheKey(kind, schema, dimensions);
        if (!Arrays.equals(encoded, parsed.canonicalBytes())) {
            throw new IllegalArgumentException("Cache key is not canonical");
        }
        return parsed;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String hex(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }

    private static String decodeStrict(byte[] encoded) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Cache key is not valid UTF-8", exception);
        }
    }

    private static String requireKind(String value) {
        value = Objects.requireNonNull(value, "kind");
        if (!KIND.matcher(value).matches()
            || value.contains("//")
            || value.endsWith("/")) {
            throw new IllegalArgumentException("Invalid cache kind: " + value);
        }
        return value;
    }

    private static void validateValue(String value) {
        int encodedLength = value.getBytes(StandardCharsets.UTF_8).length;
        if (encodedLength == 0 || encodedLength > 1024) {
            throw new IllegalArgumentException("Invalid dimension value length");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r'
                || character == '\n'
                || character == '\t'
                || character == '\0') {
                throw new IllegalArgumentException("Dimension values cannot contain separators");
            }
        }
    }

    private static int parsePositive(String line, String prefix) {
        int value = parseNonNegative(line, prefix);
        if (value == 0) {
            throw new IllegalArgumentException(prefix + " must be positive");
        }
        return value;
    }

    private static int parseNonNegative(String line, String prefix) {
        String value = exactValue(line, prefix);
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Non-canonical integer: " + line);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Integer out of range: " + line, exception);
        }
    }

    private static String exactValue(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new IllegalArgumentException("Expected " + prefix);
        }
        return line.substring(prefix.length());
    }
}
