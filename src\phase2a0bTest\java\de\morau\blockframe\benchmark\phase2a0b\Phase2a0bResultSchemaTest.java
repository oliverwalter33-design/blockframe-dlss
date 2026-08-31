package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase2a0bResultSchemaTest {
    @TempDir
    Path temporary;

    @Test
    void availableAcceptsOnlyFiniteValues() {
        assertEquals(
            1.25,
            Phase2a0bResultSchema.NumericValue.available(1.25).value()
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Phase2a0bResultSchema.NumericValue.available(Double.NaN)
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Phase2a0bResultSchema.NumericValue.available(
                    Double.POSITIVE_INFINITY
                )
        );
    }

    @Test
    void optionalNonFiniteValuesBecomeTypedUnavailableStates() {
        var nan = Phase2a0bResultSchema.NumericValue.optional(
            Double.NaN,
            Phase2a0bResultSchema.NumericStatus.NOT_AVAILABLE,
            "OPTIONAL_VALUE_UNAVAILABLE"
        );
        var positive = Phase2a0bResultSchema.NumericValue.optional(
            Double.POSITIVE_INFINITY,
            Phase2a0bResultSchema.NumericStatus.NOT_APPLICABLE,
            "OPTIONAL_VALUE_NOT_APPLICABLE"
        );
        var negative = Phase2a0bResultSchema.NumericValue.optional(
            Double.NEGATIVE_INFINITY,
            Phase2a0bResultSchema.NumericStatus.ERROR,
            "OPTIONAL_VALUE_ERROR"
        );
        assertEquals(
            Phase2a0bResultSchema.NumericStatus.NOT_AVAILABLE,
            nan.status()
        );
        assertEquals(
            Phase2a0bResultSchema.NumericStatus.NOT_APPLICABLE,
            positive.status()
        );
        assertEquals(
            Phase2a0bResultSchema.NumericStatus.ERROR,
            negative.status()
        );
        assertNull(nan.value());
        assertNull(positive.value());
        assertNull(negative.value());
    }

    @Test
    void mandatoryPerformanceValuesRejectEveryNonFiniteForm() {
        assertThrows(
            IOException.class,
            () ->
                Phase2a0bResultSchema.requiredPerformance(
                    "required",
                    Double.NaN
                )
        );
        assertThrows(
            IOException.class,
            () ->
                Phase2a0bResultSchema.requiredPerformance(
                    "required",
                    Double.POSITIVE_INFINITY
                )
        );
        assertThrows(
            IOException.class,
            () ->
                Phase2a0bResultSchema.requiredPerformance(
                    "required",
                    Double.NEGATIVE_INFINITY
                )
        );
    }

    @Test
    void imageCpuContractHasNoInventedMeasurement() throws Exception {
        JsonObject result = new JsonObject();
        Phase2a0bResultSchema.addCpuContract(
            result,
            Phase2a0bContracts.SceneType.IMAGE_REFERENCE,
            null
        );
        assertEquals(
            "NOT_APPLICABLE",
            result.get("cpuWindowStatus").getAsString()
        );
        assertEquals(0, result.get("cpuBoundaryCount").getAsInt());
        assertFalse(result.has("cpuTotalSeconds"));
        assertFalse(result.has("cpuUserSeconds"));
        assertFalse(result.has("cpuRatio"));
    }

    @Test
    void allUnavailableStatesSerializeWithoutNanOrInfinity()
        throws Exception {
        Gson gson = new Gson();
        JsonObject result = new JsonObject();
        result.add(
            "notApplicable",
            gson.toJsonTree(
                Phase2a0bResultSchema.NumericValue.notApplicable(
                    "SCENE_TYPE_NOT_APPLICABLE"
                )
            )
        );
        result.add(
            "notAvailable",
            gson.toJsonTree(
                Phase2a0bResultSchema.NumericValue.notAvailable(
                    "METRIC_NOT_AVAILABLE"
                )
            )
        );
        result.add(
            "error",
            gson.toJsonTree(
                Phase2a0bResultSchema.NumericValue.error(
                    "METRIC_READ_ERROR"
                )
            )
        );
        Path target = this.temporary.resolve("typed.json");
        Phase2a0bResultSchema.publishNew(target, result);
        String json = Files.readString(target);
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(
            "NOT_APPLICABLE",
            parsed.getAsJsonObject("notApplicable")
                .get("status").getAsString()
        );
        assertEquals(
            "NOT_AVAILABLE",
            parsed.getAsJsonObject("notAvailable")
                .get("status").getAsString()
        );
        assertEquals(
            "ERROR",
            parsed.getAsJsonObject("error").get("status").getAsString()
        );
    }

    @Test
    void publicationIsTemporaryValidatedAtomicAndNeverOverwrites()
        throws Exception {
        JsonObject first = new JsonObject();
        first.addProperty("schemaVersion", 2);
        first.addProperty("state", "COMPLETE");
        Path target = this.temporary.resolve("result.json");
        Phase2a0bResultSchema.publishNew(target, first);
        String original = Files.readString(target);
        try (var entries = Files.list(this.temporary)) {
            assertTrue(
                entries.noneMatch(
                    path -> path.getFileName().toString().contains("temporary")
                )
            );
        }
        JsonObject replacement = new JsonObject();
        replacement.addProperty("state", "REPLACED");
        assertThrows(
            IOException.class,
            () ->
                Phase2a0bResultSchema.publishNew(target, replacement)
        );
        assertEquals(original, Files.readString(target));
    }

    @Test
    void strictBoundaryRejectsNumericAndStringEncodedNonFiniteValues() {
        JsonObject numeric = new JsonObject();
        numeric.addProperty("value", Double.NaN);
        assertThrows(
            IOException.class,
            () ->
                Phase2a0bResultSchema.publishNew(
                    this.temporary.resolve("nan.json"),
                    numeric
                )
        );
        JsonObject string = new JsonObject();
        string.addProperty("value", "Infinity");
        assertThrows(
            IOException.class,
            () ->
                Phase2a0bResultSchema.publishNew(
                    this.temporary.resolve("infinity.json"),
                    string
                )
        );
    }
}
