package de.morau.blockframe.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, machine-readable explanation for an unavailable or degraded
 * provider. Codes are intended for logs, configuration UIs, and tests while
 * {@link #detail()} remains human-readable.
 */
public record Reason(String code, String detail) {
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_.-]*");

    public Reason {
        code = Objects.requireNonNull(code, "code").trim();
        detail = Objects.requireNonNull(detail, "detail").trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Reason code must be uppercase and machine-readable: " + code);
        }
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("Reason detail must not be blank");
        }
    }
}
