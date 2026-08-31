package de.morau.blockframe.benchmark.phase2a0b;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Version-2 Phase 2A.0B result boundary. Optional numeric values carry an
 * explicit state, mandatory performance values must be finite, and publication
 * is parse-validated and atomic.
 */
public final class Phase2a0bResultSchema {
    public static final int VERSION = 2;
    private static final Pattern REASON =
        Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public enum NumericStatus {
        AVAILABLE,
        NOT_APPLICABLE,
        NOT_AVAILABLE,
        ERROR
    }

    public record NumericValue(
        NumericStatus status,
        Double value,
        String reasonCode
    ) {
        public NumericValue {
            Objects.requireNonNull(status, "status");
            if (status == NumericStatus.AVAILABLE) {
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                        "AVAILABLE requires a finite numeric value"
                    );
                }
                if (reasonCode != null) {
                    throw new IllegalArgumentException(
                        "AVAILABLE cannot carry a reason code"
                    );
                }
            } else {
                if (value != null) {
                    throw new IllegalArgumentException(
                        status + " cannot carry a numeric value"
                    );
                }
                if (
                    reasonCode == null
                        || !REASON.matcher(reasonCode).matches()
                ) {
                    throw new IllegalArgumentException(
                        "non-available numeric value requires a stable reason code"
                    );
                }
            }
        }

        public static NumericValue available(double value) {
            return new NumericValue(
                NumericStatus.AVAILABLE,
                value,
                null
            );
        }

        public static NumericValue notApplicable(String reasonCode) {
            return unavailable(NumericStatus.NOT_APPLICABLE, reasonCode);
        }

        public static NumericValue notAvailable(String reasonCode) {
            return unavailable(NumericStatus.NOT_AVAILABLE, reasonCode);
        }

        public static NumericValue error(String reasonCode) {
            return unavailable(NumericStatus.ERROR, reasonCode);
        }

        public static NumericValue optional(
            double value,
            NumericStatus unavailableStatus,
            String reasonCode
        ) {
            return Double.isFinite(value)
                ? available(value)
                : unavailable(unavailableStatus, reasonCode);
        }

        private static NumericValue unavailable(
            NumericStatus status,
            String reasonCode
        ) {
            if (status == NumericStatus.AVAILABLE) {
                throw new IllegalArgumentException(
                    "unavailable status cannot be AVAILABLE"
                );
            }
            return new NumericValue(status, null, reasonCode);
        }
    }

    private Phase2a0bResultSchema() {
    }

    public static double requiredPerformance(
        String field,
        double value
    ) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IOException(
                "non-finite mandatory PERFORMANCE field: " + field
            );
        }
        return value;
    }

    public static void addCpuContract(
        JsonObject result,
        Phase2a0bContracts.SceneType type,
        ThreadCpuWindow.Result cpu
    ) throws IOException {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(type, "type");
        if (type == Phase2a0bContracts.SceneType.IMAGE_REFERENCE) {
            if (cpu != null) {
                throw new IOException(
                    "IMAGE_REFERENCE cannot carry a CPU window"
                );
            }
            result.addProperty(
                "cpuWindowStatus",
                NumericStatus.NOT_APPLICABLE.name()
            );
            result.addProperty("cpuBoundaryCount", 0);
            return;
        }
        if (
            cpu == null
                || !cpu.enabled()
                || cpu.boundarySnapshotCount() != 2
        ) {
            throw new IOException(
                "PERFORMANCE requires one complete two-boundary CPU window"
            );
        }
        double totalSeconds = requiredPerformance(
            "cpuTotalSeconds",
            cpu.totalCpuNanos() / 1_000_000_000.0
        );
        double userSeconds = requiredPerformance(
            "cpuUserSeconds",
            cpu.totalUserNanos() / 1_000_000_000.0
        );
        result.addProperty(
            "cpuWindowStatus",
            NumericStatus.AVAILABLE.name()
        );
        result.add(
            "cpuTotalSeconds",
            GSON.toJsonTree(NumericValue.available(totalSeconds))
        );
        result.add(
            "cpuUserSeconds",
            GSON.toJsonTree(NumericValue.available(userSeconds))
        );
        result.add("cpuRatio", GSON.toJsonTree(cpu.averageUtilizedCores()));
        result.addProperty(
            "cpuBoundaryCount",
            cpu.boundarySnapshotCount()
        );
    }

    public static void validateForSerialization(JsonElement element)
        throws IOException {
        validate(element, "$");
    }

    public static void publishNew(Path target, JsonObject object)
        throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(object, "object");
        validateForSerialization(object);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "refusing to overwrite existing evidence: " + target
            );
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("result parent directory unavailable");
        }
        Path temporary = parent.resolve(
            "."
                + target.getFileName()
                + ".temporary-"
                + UUID.randomUUID()
        );
        try {
            String json = GSON.toJson(object);
            Files.writeString(
                temporary,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            JsonElement parsed = JsonParser.parseString(
                Files.readString(temporary, StandardCharsets.UTF_8)
            );
            validateForSerialization(parsed);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                    "evidence target appeared before atomic publication"
                );
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException(
                    "atomic result publication is unsupported",
                    error
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validate(JsonElement element, String path)
        throws IOException {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                validate(entry.getValue(), path + "." + entry.getKey());
            }
            return;
        }
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                validate(child, path + "[" + index++ + "]");
            }
            return;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (
            primitive.isNumber()
                && !Double.isFinite(primitive.getAsDouble())
        ) {
            throw new IOException(
                "non-finite numeric JSON value at " + path
            );
        }
        if (primitive.isString()) {
            String value = primitive.getAsString();
            if (
                "NaN".equals(value)
                    || "Infinity".equals(value)
                    || "+Infinity".equals(value)
                    || "-Infinity".equals(value)
            ) {
                throw new IOException(
                    "string-encoded non-finite value at " + path
                );
            }
        }
    }
}
