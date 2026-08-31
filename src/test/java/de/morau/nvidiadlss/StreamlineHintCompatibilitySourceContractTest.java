package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StreamlineHintCompatibilitySourceContractTest {
    @Test
    void unsupportedDlss212AuditHintsAreNormalizedAndReported() throws Exception {
        String source = source("src/main/java/de/morau/nvidiadlss/FoliageAudit.java");
        assertTrue(source.contains("effectiveHintMode(REQUESTED_HINT_MODE)"));
        assertTrue(source.contains("case HINT_NONE, HINT_TRANSPARENCY -> requested"));
        assertTrue(source.contains("default -> HINT_NONE"));
        assertTrue(source.contains("requestedHintSupportedByDlss212"));
        assertTrue(source.contains("fail-closed deaktiviert"));
    }

    @Test
    void nativeBridgeNeverTagsMaskTypesIgnoredByPinnedDlss() throws Exception {
        String source = source("native/nvidia_dlss_bridge.cpp");
        assertTrue(source.contains("auditHintMode == 1"));
        assertTrue(source.contains("tagsWithTransparency, 5"));
        assertTrue(source.contains("tagsWithoutTransparency, 4"));
        assertFalse(source.contains("sl::kBufferTypeReactiveMaskHint"));
        assertFalse(source.contains("sl::kBufferTypeTransparencyAndCompositionMaskHint"));
    }

    @Test
    void nativeFrameTokenPreservesTheJavaCountersUnsignedBits() throws Exception {
        String source = source("native/nvidia_dlss_bridge.cpp");
        assertTrue(source.contains("const uint32_t frame = static_cast<uint32_t>(frameIndex)"));
        assertTrue(source.contains("gGetNewFrameToken(token, &frame)"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
