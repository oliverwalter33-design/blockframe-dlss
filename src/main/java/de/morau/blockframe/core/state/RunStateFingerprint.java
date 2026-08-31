package de.morau.blockframe.core.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical SHA-256 helper for bounded semantic configuration values. */
public final class RunStateFingerprint {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_TOKEN_CHARS = 256;

    private RunStateFingerprint() {
    }

    public static String sha256Canonical(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty() || values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                "fingerprint input must contain 1.." + MAX_ENTRIES + " entries"
            );
        }
        StringBuilder canonical = new StringBuilder(values.size() * 48);
        for (Map.Entry<String, String> entry :
            new TreeMap<>(values).entrySet()) {
            String key = token(entry.getKey(), "fingerprint key");
            String value = token(entry.getValue(), "fingerprint value");
            if (key.indexOf('=') >= 0) {
                throw new IllegalArgumentException(
                    "fingerprint keys must not contain '='"
                );
            }
            canonical.append(key).append('=').append(value).append('\n');
        }
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String token(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (
            checked.isEmpty()
                || checked.length() > MAX_TOKEN_CHARS
                || checked.indexOf('\n') >= 0
                || checked.indexOf('\r') >= 0
                || checked.indexOf('\0') >= 0
        ) {
            throw new IllegalArgumentException(
                label + " must be a bounded single-line value"
            );
        }
        return checked;
    }
}
