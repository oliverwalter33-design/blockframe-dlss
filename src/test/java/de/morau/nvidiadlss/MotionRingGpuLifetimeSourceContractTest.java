package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class MotionRingGpuLifetimeSourceContractTest {
    @Test
    void threeBlockframeSlotsOutliveMojangsTwoSubmitsInFlight() throws Exception {
        String blockframe = source("src/main/java/de/morau/nvidiadlss/MotionVectorGenerator.java");
        assertTrue(blockframe.contains("private static final int FRAME_RING_SIZE = 3"));
        assertTrue(blockframe.contains("new long[FRAME_RING_SIZE]"));
        assertTrue(blockframe.contains("new GpuBuffer[FRAME_RING_SIZE]"));

        String encoder = minecraftSource("com/mojang/blaze3d/vulkan/VulkanCommandEncoder.java");
        assertTrue(encoder.contains("public static final int MAX_SUBMITS_IN_FLIGHT = 2"));
        assertTrue(encoder.contains("awaitSubmitCompletion(this.currentSubmitIndex - 2L, 5000000000L)"));
        assertTrue(encoder.contains("this.currentCommandPool().reset()"));
    }

    private static String minecraftSource(String entryName) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        Path artifacts = root.resolve("build/moddev/artifacts");
        Path sources;
        try (var files = Files.list(artifacts)) {
            sources = files
                .filter(path -> path.getFileName().toString().startsWith("minecraft-patched-"))
                .filter(path -> path.getFileName().toString().endsWith("-sources.jar"))
                .findFirst()
                .orElseThrow();
        }
        try (ZipFile zip = new ZipFile(sources.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            assertNotNull(entry);
            try (InputStream input = zip.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
