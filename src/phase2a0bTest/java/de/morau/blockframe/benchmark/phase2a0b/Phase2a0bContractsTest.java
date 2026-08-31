package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Phase2a0bContractsTest {
    @Test
    void backendNormalizationOccursOnceAtTheTypedBoundary() {
        assertEquals(
            Phase2a0bContracts.Backend.VULKAN,
            Phase2a0bContracts.Backend.parse("Vulkan")
        );
        assertEquals(
            Phase2a0bContracts.Backend.VULKAN,
            Phase2a0bContracts.Backend.parse("  VULKAN  ")
        );
        assertEquals(
            Phase2a0bContracts.Backend.OPENGL,
            Phase2a0bContracts.Backend.parse("OpenGL")
        );
        assertEquals(
            Phase2a0bContracts.Backend.UNKNOWN,
            Phase2a0bContracts.Backend.parse("vk")
        );
        assertEquals(
            Phase2a0bContracts.Backend.UNKNOWN,
            Phase2a0bContracts.Backend.parse(null)
        );
    }

    @Test
    void exactArtifactVersionAccepts0314AndRejects037() {
        assertTrue(
            Phase2a0bPreflight.acceptsBlockframeVersion(
                "0.3.14",
                "0.3.14-neoforge-26.2"
            )
        );
        assertFalse(
            Phase2a0bPreflight.acceptsBlockframeVersion(
                "0.3.7",
                "0.3.7-neoforge-26.2"
            )
        );
    }

    @Test
    void sceneTypeIsClosedAndLocaleIndependent() throws Exception {
        assertEquals(
            Phase2a0bContracts.SceneType.PERFORMANCE,
            Phase2a0bContracts.SceneType.parse(" performance ")
        );
        assertEquals(
            Phase2a0bContracts.SceneType.IMAGE_REFERENCE,
            Phase2a0bContracts.SceneType.parse("IMAGE_REFERENCE")
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            java.io.IOException.class,
            () -> Phase2a0bContracts.SceneType.parse("benchmark")
        );
    }

    @Test
    void fullPinnedHashesAcceptExactAndRejectChangedValues()
        throws Exception {
        assertTrue(
            Phase2a0bPreflight.acceptsHash(
                Phase2a0bGateInventory.GateId.BLOCKFRAME_JAR_HASH,
                Phase2a0bPreflight.EXPECTED_JAR_HASH
            )
        );
        assertTrue(
            Phase2a0bPreflight.acceptsHash(
                Phase2a0bGateInventory.GateId.FIXTURE_HASH,
                Phase2a0bPreflight.EXPECTED_FIXTURE_HASH
            )
        );
        assertTrue(
            Phase2a0bPreflight.acceptsHash(
                Phase2a0bGateInventory.GateId.SCENE_MANIFEST_HASH,
                Phase2a0bPreflight.EXPECTED_SCENE_MANIFEST_HASH
            )
        );
        assertFalse(
            Phase2a0bPreflight.acceptsHash(
                Phase2a0bGateInventory.GateId.BLOCKFRAME_JAR_HASH,
                "8e9b6b7130f5d6bce3c0c158897a4eeb5f2aa3f9d08c2d908b56112a70d463a5"
            )
        );
        assertFalse(
            Phase2a0bPreflight.acceptsHash(
                Phase2a0bGateInventory.GateId.FIXTURE_HASH,
                "9218b992c5af65f2c86286e491e2f218f6d9bf4e00990566dfa53fc82ab39a10"
            )
        );
        assertFalse(
            Phase2a0bPreflight.acceptsHash(
                Phase2a0bGateInventory.GateId.SCENE_MANIFEST_HASH,
                "66484c6fc31292995aa54da732e8a54098167fb2b7745e95233ea87a2d00832c"
            )
        );
    }
}
