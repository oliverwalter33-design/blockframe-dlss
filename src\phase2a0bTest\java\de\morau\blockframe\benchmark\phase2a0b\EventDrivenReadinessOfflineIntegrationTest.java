package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventDrivenReadinessOfflineIntegrationTest {
    private final Path project = Path.of(
        System.getProperty("blockframe.projectDir")
    );
    @TempDir
    Path temporary;

    @Test
    void replayArmedCallbackFeedsCompleteTypedFourSceneSuite()
        throws Exception {
        RenderReadinessState readiness = new RenderReadinessState();
        readiness.onWorldLifecyclePresent();
        assertEquals(
            RenderReadinessState.Decision.BIND_OWNER,
            readiness.observe(
                53L,
                1L,
                1L,
                true,
                true,
                true
            )
        );
        readiness.markReplayArmed(53L);

        Path fixtures = this.temporary.resolve("benchmarks/fixtures");
        Files.createDirectories(fixtures);
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
        Path reportPath = Phase2a0bOfflineAudit.run(
            this.temporary,
            instance
        );
        JsonObject report = JsonParser.parseString(
            Files.readString(reportPath)
        ).getAsJsonObject();
        assertEquals(
            RenderReadinessState.State.REPLAY_ARMED,
            readiness.state()
        );
        assertEquals(
            "OFFLINE_PREFLIGHT_COMPLETE",
            report.get("status").getAsString()
        );
        assertEquals(4, report.getAsJsonArray("scenes").size());
        assertEquals(
            6,
            report.get("threadMxBeanBoundarySnapshots").getAsInt()
        );
        assertEquals(0, report.get("fileIoDuringMeasure").getAsInt());
        assertEquals(
            "IMAGE_REFERENCE",
            report.getAsJsonArray("scenes")
                .get(3)
                .getAsJsonObject()
                .get("sceneType")
                .getAsString()
        );
        assertEquals(
            0,
            report.getAsJsonArray("scenes")
                .get(3)
                .getAsJsonObject()
                .get("threadMxBeanBoundarySnapshots")
                .getAsInt()
        );
    }
}
