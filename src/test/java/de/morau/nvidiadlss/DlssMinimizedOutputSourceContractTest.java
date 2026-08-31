package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssMinimizedOutputSourceContractTest {
    @Test
    void zeroSizedFramebufferReturnsBeforeOptimalSettingsOrResources() throws Exception {
        String source = Files.readString(
            Path.of(
                "src/main/java/de/morau/nvidiadlss/DlssRenderer.java"
            )
        );
        int begin = source.indexOf(
            "public static RenderTarget beginFrame("
        );
        int minimized = source.indexOf(
            "boolean minimizedOutput",
            begin
        );
        int earlyReturn = source.indexOf(
            "return highTarget;",
            minimized
        );
        int ensure = source.indexOf(
            "ensureResources(",
            begin
        );

        assertTrue(begin >= 0);
        assertTrue(minimized > begin);
        assertTrue(earlyReturn > minimized);
        assertTrue(ensure > earlyReturn);
    }
}
