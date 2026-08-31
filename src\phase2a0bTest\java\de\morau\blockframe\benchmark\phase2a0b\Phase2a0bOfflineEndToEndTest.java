package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase2a0bOfflineEndToEndTest {
    private static final BenchmarkState[] PERFORMANCE_STATES = {
        BenchmarkState.PREFLIGHT,
        BenchmarkState.WORLD_WAIT,
        BenchmarkState.CHUNK_WARMUP,
        BenchmarkState.WARMUP,
        BenchmarkState.MEASURE,
        BenchmarkState.REFERENCE_CAPTURE,
        BenchmarkState.COMPLETE
    };
    private static final BenchmarkState[] IMAGE_STATES = {
        BenchmarkState.PREFLIGHT,
        BenchmarkState.WORLD_WAIT,
        BenchmarkState.CHUNK_WARMUP,
        BenchmarkState.WARMUP,
        BenchmarkState.REFERENCE_CAPTURE,
        BenchmarkState.COMPLETE
    };
    private final Path project = Path.of(
        System.getProperty("blockframe.projectDir")
    );
    @TempDir
    Path temporary;

    @Test
    void capturedSecondRunValuesAndCurrentContractCompleteOffline()
        throws Exception {
        JsonObject dataset =
            Phase2a0bOfflineAudit.loadDataset(this.project);
        JsonObject runtime = dataset.getAsJsonObject("capturedRuntime");
        JsonObject current = dataset.getAsJsonObject(
            "currentArtifactContract"
        );
        assertEquals("Vulkan", runtime.get("backend").getAsString());
        assertEquals(
            "0.3.14",
            runtime.get("blockframeReleaseVersion").getAsString()
        );
        assertEquals(
            "7e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5",
            runtime.get("blockframeCodeSourceSha256").getAsString()
        );
        assertEquals(
            "a3d3efbe90c8138bd38d340aacd8397db3991469545c176ac9b0936bd83b0a2a",
            runtime.get("sceneManifestHash").getAsString()
        );
        Phase2a0bPreflight.Report report =
            Phase2a0bPreflight.evaluate(
                Phase2a0bOfflineAudit.input(
                    runtime,
                    current,
                    true,
                    2,
                    0,
                    0
                )
            );
        assertEquals("OFFLINE_PREFLIGHT_COMPLETE", report.status());
        assertEquals(
            Phase2a0bGateInventory.all().length,
            report.passed()
        );
        assertEquals(0, report.failed());
    }

    @Test
    void everyInventoriedGateFailsClosedIndividually()
        throws Exception {
        JsonObject dataset =
            Phase2a0bOfflineAudit.loadDataset(this.project);
        Phase2a0bPreflight.Input input =
            Phase2a0bOfflineAudit.input(
                dataset.getAsJsonObject("capturedRuntime"),
                dataset.getAsJsonObject("currentArtifactContract"),
                true,
                2,
                0,
                0
            );
        for (Phase2a0bGateInventory.Gate gate :
            Phase2a0bGateInventory.all()) {
            Phase2a0bPreflight.Report failed =
                Phase2a0bPreflight.evaluate(
                    input,
                    Phase2a0bPreflight.failOnly(gate.id())
                );
            assertEquals(
                "OFFLINE_PREFLIGHT_FAILED",
                failed.status(),
                gate.id().name()
            );
            assertEquals(1, failed.failed(), gate.id().name());
            assertFalse(
                java.util.Arrays.stream(failed.outcomes())
                    .filter(
                        outcome -> outcome.gateId() == gate.id()
                    )
                    .findFirst()
                    .orElseThrow()
                    .passed(),
                gate.id().name()
            );
        }
    }

    @Test
    void allFourScenesReachCompleteWithOneOwnerAndTwoBoundaries()
        throws Exception {
        Path fixtureDirectory = this.temporary.resolve(
            "benchmarks/fixtures"
        );
        Files.createDirectories(fixtureDirectory);
        Files.copy(
            this.project.resolve(Phase2a0bOfflineAudit.DATASET),
            this.temporary.resolve(Phase2a0bOfflineAudit.DATASET)
        );
        Files.copy(
            this.project.resolve(FixtureRunManager.SCENE_MANIFEST_RELATIVE),
            this.temporary.resolve(
                FixtureRunManager.SCENE_MANIFEST_RELATIVE
            )
        );
        Files.copy(
            this.project.resolve(
                "benchmarks/fixtures/blockframe-dtc-scenes-v1.json"
            ),
            this.temporary.resolve(
                "benchmarks/fixtures/blockframe-dtc-scenes-v1.json"
            )
        );
        Path instance = this.temporary.resolve("Vulkan 7 days");
        Files.createDirectories(instance);
        Path reportPath =
            Phase2a0bOfflineAudit.run(this.temporary, instance);
        JsonObject report = JsonParser.parseString(
            Files.readString(reportPath)
        ).getAsJsonObject();
        assertEquals(
            "OFFLINE_PREFLIGHT_COMPLETE",
            report.get("status").getAsString()
        );
        assertEquals(1, report.get("replayOwnerCount").getAsInt());
        assertEquals(
            6,
            report.get("threadMxBeanBoundarySnapshots").getAsInt()
        );
        assertEquals(0, report.get("fileIoDuringMeasure").getAsInt());
        assertEquals(0, report.get("perFrameThreadScans").getAsInt());
        assertEquals(4, report.getAsJsonArray("scenes").size());
        int index = 0;
        for (var element : report.getAsJsonArray("scenes")) {
            JsonObject scene = element.getAsJsonObject();
            BenchmarkState[] observed =
                scene.getAsJsonArray("states")
                    .asList()
                    .stream()
                    .map(value -> BenchmarkState.valueOf(value.getAsString()))
                    .toArray(BenchmarkState[]::new);
            assertArrayEquals(
                index < 3 ? PERFORMANCE_STATES : IMAGE_STATES,
                observed
            );
            assertEquals(
                index < 3 ? "PERFORMANCE" : "IMAGE_REFERENCE",
                scene.get("sceneType").getAsString()
            );
            assertEquals(
                index < 3 ? 2 : 0,
                scene.get("threadMxBeanBoundarySnapshots").getAsInt()
            );
            assertEquals("COMPLETE", scene.get("status").getAsString());
            index++;
        }
        assertTrue(
            report.get("testDatasetSha256").getAsString()
                .matches("[0-9a-f]{64}")
        );
        assertTrue(
            report.get("runtimeProfileHash").getAsString()
                .matches("[0-9a-f]{64}")
        );
    }
}
