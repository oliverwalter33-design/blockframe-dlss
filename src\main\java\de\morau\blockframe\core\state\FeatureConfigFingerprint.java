package de.morau.blockframe.core.state;

import de.morau.blockframe.core.EngineConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Stable fingerprint of normalized user-facing BlockFrame configuration.
 *
 * <p>The digest deliberately excludes file names, file bytes, comments,
 * timestamps, runtime capabilities and transient Safe-Start overrides.</p>
 */
public final class FeatureConfigFingerprint {
    private FeatureConfigFingerprint() {
    }

    public static String compute(
        EngineConfig.Settings engine,
        String dlssMode,
        String sharpeningMode,
        int sharpeningAmount,
        String entityHistoryBackend
    ) {
        Objects.requireNonNull(engine, "engine");
        TreeMap<String, String> values = new TreeMap<>();
        Properties engineProperties = engine.toProperties();
        for (String name : engineProperties.stringPropertyNames()) {
            values.put(
                "engine." + boundedToken(name, "engine property name"),
                boundedToken(
                    engineProperties.getProperty(name),
                    "engine property value"
                )
            );
        }
        values.put(
            "dlss.mode",
            boundedToken(dlssMode, "DLSS mode").toLowerCase(Locale.ROOT)
        );
        values.put(
            "dlss.sharpening",
            boundedToken(
                sharpeningMode,
                "sharpening mode"
            ).toLowerCase(Locale.ROOT)
        );
        values.put(
            "dlss.sharpeningAmount",
            Integer.toString(Math.max(0, Math.min(100, sharpeningAmount)))
        );
        values.put(
            "dlss.entityHistoryBackend",
            boundedToken(
                entityHistoryBackend,
                "entity history backend"
            ).toLowerCase(Locale.ROOT)
        );
        return sha256(canonicalBytes(values));
    }

    static byte[] canonicalBytes(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        StringBuilder canonical = new StringBuilder(values.size() * 48);
        for (Map.Entry<String, String> entry :
            new TreeMap<>(values).entrySet()) {
            String key = boundedToken(entry.getKey(), "fingerprint key");
            String value = boundedToken(
                entry.getValue(),
                "fingerprint value"
            );
            if (
                key.indexOf('=') >= 0
                    || key.indexOf('\n') >= 0
                    || key.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0
            ) {
                throw new IllegalArgumentException(
                    "fingerprint values must be single-line tokens"
                );
            }
            canonical.append(key).append('=').append(value).append('\n');
        }
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String boundedToken(String value, String label) {
        String token = Objects.requireNonNull(value, label).trim();
        if (token.isEmpty() || token.length() > 256) {
            throw new IllegalArgumentException(
                label + " must contain 1..256 characters"
            );
        }
        return token;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                "SHA-256 is unavailable",
                impossible
            );
        }
    }
}
