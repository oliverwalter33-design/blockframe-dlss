package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.morau.blockframe.core.EngineConfig;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FeatureConfigFingerprintTest {
    @Test
    void semanticFingerprintIsDeterministicAndOverrideFree() {
        EngineConfig.Settings settings = EngineConfig.Settings.defaults();
        String first = FeatureConfigFingerprint.compute(
            settings,
            "quality",
            "auto",
            20,
            "heap"
        );
        String second = FeatureConfigFingerprint.compute(
            settings,
            " QUALITY ",
            "AUTO",
            20,
            "HEAP"
        );

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertFalse(first.contains("config"));
        assertFalse(first.contains("\\"));
        assertFalse(first.contains("/"));
    }

    @Test
    void changingOneSemanticValueChangesTheDigest() {
        EngineConfig.Settings settings = EngineConfig.Settings.defaults();
        assertNotEquals(
            FeatureConfigFingerprint.compute(
                settings,
                "quality",
                "auto",
                20,
                "heap"
            ),
            FeatureConfigFingerprint.compute(
                settings,
                "dlaa",
                "auto",
                20,
                "heap"
            )
        );
    }

    @Test
    void sharpeningModeAndAmountEachChangeTheDigest() {
        EngineConfig.Settings settings = EngineConfig.Settings.defaults();
        String automatic = FeatureConfigFingerprint.compute(
            settings,
            "quality",
            "auto",
            20,
            "heap"
        );
        assertNotEquals(
            automatic,
            FeatureConfigFingerprint.compute(
                settings,
                "quality",
                "manual",
                20,
                "heap"
            )
        );
        assertNotEquals(
            automatic,
            FeatureConfigFingerprint.compute(
                settings,
                "quality",
                "auto",
                21,
                "heap"
            )
        );
    }

    @Test
    void restartOnlyTerrainBackendChangesTheFingerprint() {
        EngineConfig.Settings mojang = EngineConfig.Settings.defaults();
        Properties nativeProperties = mojang.toProperties();
        nativeProperties.setProperty(
            EngineConfig.TERRAIN_BACKEND_KEY,
            EngineConfig.TERRAIN_BACKEND_NATIVE_EXPERIMENTAL
        );
        EngineConfig.Settings nativeExperimental =
            EngineConfig.Settings.from(nativeProperties);

        assertNotEquals(
            FeatureConfigFingerprint.compute(
                mojang,
                "quality",
                "auto",
                20,
                "heap"
            ),
            FeatureConfigFingerprint.compute(
                nativeExperimental,
                "quality",
                "auto",
                20,
                "heap"
            )
        );
    }

    @Test
    void canonicalEncodingSortsKeysAndRejectsMultilineValues() {
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("z", "last");
        reverse.put("a", "first");
        assertEquals(
            "a=first\nz=last\n",
            new String(
                FeatureConfigFingerprint.canonicalBytes(reverse),
                StandardCharsets.UTF_8
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FeatureConfigFingerprint.canonicalBytes(
                Map.of("a", "line\nbreak")
            )
        );
    }
}
