package de.morau.nvidiadlss;

import java.util.Map;

/**
 * Fail-closed master gate for development-only diagnostics.
 *
 * <p>Legacy capture, sequence, audit and debug-hint switches are deliberately
 * subordinate to this gate. A normal game launch therefore cannot activate
 * readbacks, image writes, deterministic movement or diagnostic overrides by
 * accidentally retaining one of the old individual switches.</p>
 */
public final class DeveloperDiagnostics {
    public static final String PROPERTY =
        "nvidia_dlss.developerDiagnostics";
    public static final String ENVIRONMENT =
        "NVIDIA_DLSS_DEVELOPER_DIAGNOSTICS";

    /**
     * Immutable process-start gate for hot-path call sites. Reading this
     * field adds no method call and the JIT can fold the normal false path.
     */
    public static final boolean ENABLED = enabled(
        System.getProperties(),
        System.getenv()
    );

    private DeveloperDiagnostics() {}

    /**
     * Cached startup decision. The static-final false path is JIT-eliminable
     * and performs no property, environment, file, image or device query per
     * frame.
     */
    public static boolean enabled() {
        return ENABLED;
    }

    static boolean enabled(Map<?, ?> properties, Map<?, ?> environment) {
        return explicitTrue(properties.get(PROPERTY))
            || explicitTrue(environment.get(ENVIRONMENT));
    }

    private static boolean explicitTrue(Object value) {
        return value instanceof String text
            && "true".equalsIgnoreCase(text.trim());
    }
}
