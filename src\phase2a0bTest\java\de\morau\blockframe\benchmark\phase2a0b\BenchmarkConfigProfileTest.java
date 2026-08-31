package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkConfigProfileTest {
    private static final String EXPECTED_HASH =
        "3a78ec4a863e3e10c7bcf57179e57f2889953b0700681d49e6f2ece710e1f40d";
    private final Path project = Path.of(
        System.getProperty("blockframe.projectDir")
    );

    @Test
    void canonicalProjectionHasPinnedScopeAlgorithmAndHash()
        throws Exception {
        Path manifest = this.project.resolve(
            BenchmarkConfigProfile.REPOSITORY_MANIFEST
        );
        JsonObject root = JsonParser.parseString(
            Files.readString(manifest, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        TreeMap<String, String> fields = new TreeMap<>();
        for (
            Map.Entry<String, JsonElement> entry :
                root.getAsJsonObject("fields").entrySet()
        ) {
            fields.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(
            "SHA-256",
            root.getAsJsonObject("canonicalization")
                .get("algorithm")
                .getAsString()
        );
        assertEquals(
            EXPECTED_HASH,
            BenchmarkConfigProfile.projectionHash(fields)
        );
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(this.project);
        assertEquals(EXPECTED_HASH, profile.benchmarkStartProfileHash());
        assertEquals(34, profile.projectedFieldCount());
    }

    @Test
    void appliesOnlySemanticKeysAndPreservesVolatileMixedValues(
        @TempDir Path snapshot
    ) throws Exception {
        writeSnapshot(snapshot, true, false);
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(this.project);

        profile.applyToSnapshot(snapshot);
        BenchmarkConfigProfile.Verification verified =
            profile.verifyProjectedSnapshot(snapshot);

        assertEquals(EXPECTED_HASH, verified.benchmarkStartProfileHash());
        String options = Files.readString(snapshot.resolve("options.txt"));
        assertTrue(options.contains("lastServer:volatile.example"));
        assertTrue(options.contains("enableVsync:false"));
        assertTrue(options.contains("maxFps:260"));
        assertTrue(options.contains("graphicsPreset:\"custom\""));
        String properties = Files.readString(
            snapshot.resolve("config/voxellift.properties")
        );
        assertTrue(properties.contains("#volatile timestamp"));
        assertTrue(properties.contains("mode=off"));
    }

    @Test
    void unknownMalformedMixedLineFailsClosed(@TempDir Path snapshot)
        throws Exception {
        writeSnapshot(snapshot, false, true);
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(this.project);
        IOException error = assertThrows(
            IOException.class,
            () -> profile.verifyProjectedSnapshot(snapshot)
        );
        assertTrue(error.getMessage().contains("unparseable mixed"));
    }

    @Test
    void unparseableProjectedValueFailsClosed(@TempDir Path snapshot)
        throws Exception {
        writeSnapshot(snapshot, false, false);
        Path options = snapshot.resolve("options.txt");
        Files.writeString(
            options,
            Files.readString(options).replace(
                "renderDistance:12",
                "renderDistance:not-a-number"
            )
        );
        BenchmarkConfigProfile profile =
            BenchmarkConfigProfile.load(this.project);
        IOException error = assertThrows(
            IOException.class,
            () -> profile.verifyProjectedSnapshot(snapshot)
        );
        assertTrue(error.getMessage().contains("unparseable projected"));
    }

    static void writeSnapshot(
        Path snapshot,
        boolean preApplyValues,
        boolean malformedUnknownLine
    ) throws IOException {
        Files.createDirectories(snapshot.resolve("config"));
        String options = String.join(
            "\n",
            "ao:true",
            "biomeBlendRadius:2",
            "enableVsync:" + (preApplyValues ? "true" : "false"),
            "entityDistanceScaling:1.0",
            "fov:0.0",
            "fullscreen:false",
            "graphicsPreset:\"custom\"",
            "maxFps:" + (preApplyValues ? "120" : "260"),
            "mipmapLevels:4",
            "overrideHeight:" + (preApplyValues ? "0" : "1080"),
            "overrideWidth:" + (preApplyValues ? "0" : "1920"),
            "particles:0",
            "renderClouds:\"true\"",
            "renderDistance:" + (preApplyValues ? "10" : "12"),
            "simulationDistance:" + (preApplyValues ? "10" : "12"),
            "lastServer:volatile.example",
            malformedUnknownLine ? "malformed-volatile-line" : "guiScale:0"
        );
        Files.writeString(
            snapshot.resolve("options.txt"),
            options,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            snapshot.resolve("config/voxellift.properties"),
            String.join(
                "\n",
                "#volatile timestamp",
                "mode=" + (preApplyValues ? "dlaa" : "off"),
                "sharpening=auto",
                "sharpeningAmount=20"
            ),
            StandardCharsets.UTF_8
        );
    }
}
