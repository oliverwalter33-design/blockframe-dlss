package de.morau.blockframe.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhaseOneApiIsolationTest {
    @Test
    void requiredContractsShareThePureProviderBoundary() {
        List<Class<?>> contracts = List.of(
            RenderBackend.class,
            GeometryFrontend.class,
            ShaderBridge.class,
            TemporalUpscalerProvider.class,
            FrameGenerationProvider.class,
            BlockframeCacheManager.class,
            WorldStreamingProvider.class,
            ChunkMeshingProvider.class
        );

        for (Class<?> contract : contracts) {
            assertTrue(contract.isInterface(), contract.getName());
            assertTrue(BlockframeProvider.class.isAssignableFrom(contract), contract.getName());
        }
    }

    @Test
    void apiSourcesHaveNoMinecraftNeoForgeOrLwjglImports() throws IOException {
        Path apiDirectory = Path.of(
            System.getProperty("blockframe.projectDir"),
            "src",
            "main",
            "java",
            "de",
            "morau",
            "blockframe",
            "api"
        );
        try (var paths = Files.list(apiDirectory)) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                assertFalse(source.contains("import net.minecraft"), path.toString());
                assertFalse(source.contains("import net.neoforged"), path.toString());
                assertFalse(source.contains("import org.lwjgl"), path.toString());
                assertFalse(source.contains("import com.mojang"), path.toString());
            }
        }
    }
}
