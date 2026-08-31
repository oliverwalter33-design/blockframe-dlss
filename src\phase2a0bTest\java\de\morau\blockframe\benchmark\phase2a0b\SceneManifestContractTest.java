package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SceneManifestContractTest {
    private final Path project = Path.of(
        System.getProperty("blockframe.projectDir")
    );

    @Test
    void currentManifestHasThreePerformanceAndOneReferenceScene()
        throws Exception {
        SceneManifest manifest = SceneManifest.load(
            this.project.resolve(FixtureRunManager.SCENE_MANIFEST_RELATIVE)
        );
        SceneManifest.Scene[] scenes = manifest.requireReadyScenes(
            new String[] {
                "DTC_DENSE_STATIC",
                "DTC_POI_SWEEP",
                "DTC_CHUNK_TRAVERSE",
                "DTC_IMAGE_REFERENCE"
            }
        );
        assertEquals(
            Phase2a0bContracts.SceneType.PERFORMANCE,
            scenes[0].type()
        );
        assertEquals(
            Phase2a0bContracts.SceneType.PERFORMANCE,
            scenes[1].type()
        );
        assertEquals(
            Phase2a0bContracts.SceneType.PERFORMANCE,
            scenes[2].type()
        );
        assertEquals(
            Phase2a0bContracts.SceneType.IMAGE_REFERENCE,
            scenes[3].type()
        );
        assertEquals(0, scenes[3].measureSeconds());
    }

    @Test
    void performanceRequiresPositiveMeasureDuration() throws Exception {
        SceneManifest.validateSceneContract(
            Phase2a0bContracts.SceneId.DTC_DENSE_STATIC,
            Phase2a0bContracts.SceneType.PERFORMANCE,
            1
        );
        assertThrows(
            IOException.class,
            () ->
                SceneManifest.validateSceneContract(
                    Phase2a0bContracts.SceneId.DTC_DENSE_STATIC,
                    Phase2a0bContracts.SceneType.PERFORMANCE,
                    0
                )
        );
    }

    @Test
    void imageReferenceRequiresExactlyZeroMeasureDuration()
        throws Exception {
        SceneManifest.validateSceneContract(
            Phase2a0bContracts.SceneId.DTC_IMAGE_REFERENCE,
            Phase2a0bContracts.SceneType.IMAGE_REFERENCE,
            0
        );
        assertThrows(
            IOException.class,
            () ->
                SceneManifest.validateSceneContract(
                    Phase2a0bContracts.SceneId.DTC_IMAGE_REFERENCE,
                    Phase2a0bContracts.SceneType.IMAGE_REFERENCE,
                    1
                )
        );
    }
}
