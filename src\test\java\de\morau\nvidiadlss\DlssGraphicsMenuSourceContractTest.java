package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssGraphicsMenuSourceContractTest {
    @Test
    void videoSettingsExposeEveryDlssModeAndDlaa() throws Exception {
        String mode = source(
            "src/main/java/de/morau/nvidiadlss/DlssMode.java"
        );
        for (String entry : new String[] {
            "OFF(\"off\"",
            "QUALITY(\"quality\"",
            "BALANCED(\"balanced\"",
            "PERFORMANCE(\"performance\"",
            "DLAA(\"dlaa\"",
            "ULTRA_PERFORMANCE(\"ultra_performance\""
        }) {
            assertTrue(mode.contains(entry), entry);
        }

        String mixin = source(
            "src/main/java/de/morau/nvidiadlss/mixin/"
                + "VideoSettingsScreenMixin.java"
        );
        assertTrue(mixin.contains("entries.add(index, DlssOption.get())"));
    }

    @Test
    void modeTooltipReportsSelectionAndPinnedRuntimeVersions()
        throws Exception {
        String option = source(
            "src/main/java/de/morau/nvidiadlss/DlssOption.java"
        );
        String german = source(
            "src/main/resources/assets/nvidia_dlss/lang/de_de.json"
        );
        String english = source(
            "src/main/resources/assets/nvidia_dlss/lang/en_us.json"
        );

        assertTrue(option.contains(
            "options.nvidia_dlss.tooltip.selection"
        ));
        assertTrue(option.contains(
            "options.nvidia_dlss.tooltip.runtime_versions"
        ));
        for (String language : new String[] {german, english}) {
            assertTrue(language.contains("NVIDIA DLSS 310.7.0"));
            assertTrue(language.contains("Streamline 2.12.0"));
        }
    }

    @Test
    void sodiumSelectorUsesCompactReadableLabels() throws Exception {
        String german = source(
            "src/main/resources/assets/nvidia_dlss/lang/de_de.json"
        );
        String english = source(
            "src/main/resources/assets/nvidia_dlss/lang/en_us.json"
        );

        for (String language : new String[] {german, english}) {
            assertTrue(language.contains(
                "\"options.nvidia_dlss.mode\": \"DLSS\""
            ));
            assertFalse(language.contains(
                "BlockFrame DLSS – NVIDIA DLSS / DLAA"
            ));
        }
        assertTrue(german.contains(
            "\"options.nvidia_dlss.ultra_performance\": \"Ultra (4K)\""
        ));
        assertTrue(english.contains(
            "\"options.nvidia_dlss.ultra_performance\": \"Ultra (4K)\""
        ));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(relativePath),
            StandardCharsets.UTF_8
        );
    }
}
