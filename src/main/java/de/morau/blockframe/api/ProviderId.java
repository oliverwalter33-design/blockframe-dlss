package de.morau.blockframe.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Namespaced, stable identifier for a Blockframe provider. */
public record ProviderId(String value) {
    private static final Pattern ID_PATTERN =
        Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public ProviderId {
        value = Objects.requireNonNull(value, "value").trim();
        if (!ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Provider id must be a lowercase namespaced id: " + value);
        }
    }

    public String namespace() {
        return this.value.substring(0, this.value.indexOf(':'));
    }

    public String path() {
        return this.value.substring(this.value.indexOf(':') + 1);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
