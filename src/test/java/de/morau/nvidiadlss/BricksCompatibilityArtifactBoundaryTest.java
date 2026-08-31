package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

final class BricksCompatibilityArtifactBoundaryTest {
    private static final String JAR_PROPERTY =
        "blockframe.distributableJar";

    @Test
    void distributableContainsTheClientGateButNeverBundlesBricks()
            throws IOException {
        Path jarPath = Path.of(requiredProperty(JAR_PROPERTY))
            .toAbsolutePath()
            .normalize();
        assertTrue(Files.isRegularFile(jarPath), jarPath::toString);

        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            Set<String> entries = new HashSet<>();
            jar.stream().map(JarEntry::getName).forEach(entries::add);

            assertTrue(entries.contains(
                "de/morau/nvidiadlss/BricksCompatibility.class"
            ));
            assertTrue(entries.contains(
                "de/morau/nvidiadlss/BricksCompositeRenderDistancePolicy.class"
            ));
            assertTrue(entries.contains(
                "de/morau/nvidiadlss/BricksFarLodMesh.class"
            ));
            assertTrue(entries.contains(
                "de/morau/nvidiadlss/BricksFarLodRuntime.class"
            ));
            assertTrue(entries.contains(
                "de/morau/nvidiadlss/mixin/"
                    + "BricksCompositeBlockEntityDistanceMixin.class"
            ));
            assertTrue(entries.contains(
                "de/morau/nvidiadlss/mixin/"
                    + "BricksFarLodLevelRendererMixin.class"
            ));
            assertFalse(entries.stream().anyMatch(name ->
                name.startsWith("com/matnx/")
                    || name.toLowerCase().endsWith("bricks-1.0.1.jar")
            ));

            JarEntry mixinConfig = jar.getJarEntry("nvidia_dlss.mixins.json");
            assertNotNull(mixinConfig);
            String json;
            try (var input = jar.getInputStream(mixinConfig)) {
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            int clientList = json.indexOf("\"client\"");
            int compatibilityMixin = json.indexOf(
                "\"BricksCompositeBlockEntityDistanceMixin\""
            );
            assertTrue(clientList >= 0);
            assertTrue(compatibilityMixin > clientList);
            assertTrue(json.indexOf(
                "\"BricksFarLodLevelRendererMixin\""
            ) > clientList);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test property " + name);
        }
        return value;
    }
}
