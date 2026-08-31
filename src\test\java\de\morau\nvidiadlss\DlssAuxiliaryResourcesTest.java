package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssAuxiliaryResourcesTest {
    @Test
    void completenessContractIncludesEveryTextureView() throws IOException {
        String complete = section(
            source(),
            "    boolean complete() {",
            "    /**\n     * Queues all images"
        );

        assertTrue(complete.contains("!this.motionView.isClosed()"));
        assertTrue(complete.contains("!this.historyBiasView.isClosed()"));
        assertTrue(complete.contains("openOrAbsent(this.depthDebugView)"));
        assertTrue(complete.contains("openOrAbsent(this.motionDebugView)"));
        assertTrue(complete.contains("openOrAbsent(this.motionValidityView)"));
        assertTrue(complete.contains("!this.transparencyHintView.isClosed()"));
        assertTrue(complete.contains("!this.outputView.isClosed()"));
        assertTrue(complete.contains("!this.sharpenView.isClosed()"));
        assertFalse(source().contains("previousDepthTexture"));
        assertTrue(source().contains("GpuFormat.RGBA8_UNORM"));
        assertFalse(source().contains("GpuFormat.R8_UNORM"));
        assertTrue(source().contains("developerDiagnostics ? 16 : 10"));
    }

    @Test
    void cleanupContractCatchesErrorsAndCanConservativelyRetainLease()
        throws IOException {
        String source = source();
        String conservativeEntry = section(
            source,
            "    void closeRetainingLease() {",
            "    private void close(boolean retainLease) {"
        );
        String cleanup = section(
            source,
            "    private void close(boolean retainLease) {",
            "    private static boolean close(AutoCloseable resource) {"
        );
        String closeHelper = source.substring(
            source.indexOf(
                "    private static boolean close(AutoCloseable resource) {"
            )
        );

        assertTrue(conservativeEntry.contains("this.close(true);"));
        assertTrue(cleanup.contains("if (retainLease)"));
        assertTrue(cleanup.contains("return;"));
        assertTrue(cleanup.indexOf("if (retainLease)")
            < cleanup.indexOf("retireAfterGpuUse"));
        assertTrue(closeHelper.contains("catch (Throwable error)"));
    }

    private static String source() throws IOException {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(
            root.resolve(
                "src/main/java/de/morau/nvidiadlss/DlssAuxiliaryResources.java"
            ),
            StandardCharsets.UTF_8
        );
    }

    private static String section(
        String source,
        String startMarker,
        String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0, "missing source marker: " + startMarker);
        assertTrue(end > start, "missing source marker: " + endMarker);
        return source.substring(start, end);
    }
}
