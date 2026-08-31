package de.morau.blockframe.benchmark.phase2a0b;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed boundary types used by the Phase 2A.0B launcher and replay harness.
 * Text normalization happens only while constructing these values.
 */
public final class Phase2a0bContracts {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private Phase2a0bContracts() {
    }

    public enum Backend {
        VULKAN,
        OPENGL,
        UNKNOWN;

        public static Backend parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "VULKAN" -> VULKAN;
                case "OPENGL" -> OPENGL;
                default -> UNKNOWN;
            };
        }
    }

    public enum HashAlgorithm {
        SHA_256;

        public static HashAlgorithm parse(String raw) throws IOException {
            if (
                raw != null
                    && "SHA-256".equals(
                        raw.strip().toUpperCase(Locale.ROOT)
                    )
            ) {
                return SHA_256;
            }
            throw new IOException("unsupported hash algorithm");
        }

        public String wireValue() {
            return "SHA-256";
        }
    }

    public enum MeasurementMode {
        REPLAY,
        REPLAY_SUITE,
        UNKNOWN;

        public static MeasurementMode parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    public enum ConfigOwner {
        EXTERNAL_LAUNCHER
    }

    public enum RuntimeProfile {
        CAPTURED_SECOND_LIVE_RUN_20260728
    }

    public enum SceneType {
        PERFORMANCE,
        IMAGE_REFERENCE;

        public static SceneType parse(String raw) throws IOException {
            if (raw != null) {
                try {
                    return valueOf(raw.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Converted to a stable manifest error below.
                }
            }
            throw new IOException("unknown scene type");
        }
    }

    public enum SceneId {
        DTC_DENSE_STATIC,
        DTC_POI_SWEEP,
        DTC_CHUNK_TRAVERSE,
        DTC_IMAGE_REFERENCE,
        UNKNOWN;

        public static SceneId parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }

        public static SceneId[] requiredSuite() {
            return new SceneId[] {
                DTC_DENSE_STATIC,
                DTC_POI_SWEEP,
                DTC_CHUNK_TRAVERSE,
                DTC_IMAGE_REFERENCE
            };
        }
    }

    public record ArtifactVersion(String value) {
        public ArtifactVersion {
            value = checkedToken(value, "artifact version");
        }

        public static ArtifactVersion parse(String raw) {
            return new ArtifactVersion(raw == null ? "" : raw.strip());
        }
    }

    public record Sha256(String value) {
        public Sha256 {
            Objects.requireNonNull(value, "value");
            value = value.strip().toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid SHA-256");
            }
        }

        public static Sha256 parse(String raw) throws IOException {
            try {
                return new Sha256(raw);
            } catch (RuntimeException error) {
                throw new IOException("invalid SHA-256", error);
            }
        }
    }

    private static String checkedToken(String value, String label) {
        Objects.requireNonNull(value, label);
        if (
            value.isBlank()
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
        ) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }
}
