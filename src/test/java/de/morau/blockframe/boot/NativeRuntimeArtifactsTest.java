package de.morau.blockframe.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.morau.blockframe.cache.CacheKey;
import de.morau.blockframe.cache.ImmutableArtifactManifest;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeRuntimeArtifactsTest {
    private static final String RESOURCE_PREFIX =
        "/assets/nvidia_dlss/native/win-x64/";

    @Test
    void generatedResourcesCoverAndHashTheExactImmutableBundle()
        throws Exception {
        ImmutableArtifactManifest manifest =
            NativeRuntimeArtifacts.loadManifest();
        CacheKey key = NativeRuntimeArtifacts.loadKey();

        NativeRuntimeArtifacts.validateGeneratedResources(key, manifest);
        assertEquals(62_721_934L, manifest.totalBytes());
        assertEquals(
            List.of(
                "NvLowLatencyVk.dll",
                "nis.license.txt",
                "nvidia_dlss_bridge.dll",
                "nvngx_dlss.dll",
                "nvngx_dlss.license.txt",
                "reflex.license.txt",
                "sl.common.dll",
                "sl.dlss.dll",
                "sl.interposer.dll",
                "sl.nis.dll"
            ),
            manifest.artifacts().stream()
                .map(ImmutableArtifactManifest.Artifact::name)
                .toList()
        );

        for (ImmutableArtifactManifest.Artifact artifact :
            manifest.artifacts()) {
            try (InputStream input =
                NativeRuntimeArtifactsTest.class.getResourceAsStream(
                    RESOURCE_PREFIX + artifact.name()
                )) {
                assertNotNull(input, artifact.name());
                MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
                long size = 0L;
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
                assertEquals(artifact.size(), size, artifact.name());
                assertEquals(
                    artifact.sha256(),
                    HexFormat.of().formatHex(digest.digest()),
                    artifact.name()
                );
            }
        }
    }

    @Test
    void nativeKeyUsesExactRuntimeIdentityAndExplicitDependencyMasks()
        throws Exception {
        CacheKey key = NativeRuntimeArtifacts.loadKey();
        Map<String, String> dimensions = key.dimensions();

        assertEquals(1, key.schemaVersion());
        assertEquals("native-runtime/windows-x86_64", key.kind());
        assertEquals("26.2", dimensions.get("minecraft-api-target"));
        assertEquals(
            "26.2.0.23-beta",
            dimensions.get("neoforge-api-target")
        );
        assertEquals(
            "0.3.16-neoforge-26.2",
            dimensions.get("blockframe-version")
        );
        assertEquals("vulkan", dimensions.get("backend"));
        assertEquals("x86_64", dimensions.get("windows-architecture"));
        for (String name : List.of(
            "mod-list",
            "resourcepacks",
            "shaderpack",
            "registry-state",
            "minecraft-runtime-version",
            "neoforge-runtime-version"
        )) {
            assertEquals(
                "not-a-dependency-of-packaged-native-bundle",
                dimensions.get(name),
                name
            );
        }
        for (String name : List.of(
            "gpu-vendor",
            "gpu-device-id",
            "driver-version"
        )) {
            assertEquals(
                "not-a-dependency-runtime-validated",
                dimensions.get(name),
                name
            );
        }
        for (String name : List.of("vulkan-version", "vulkan-features")) {
            assertEquals(
                "not-a-dependency-runtime-negotiated",
                dimensions.get(name),
                name
            );
        }
    }

    @Test
    void cacheRootHasAScopedDefaultAndAnExplicitOverride() {
        String previous = System.getProperty(
            NativeRuntimeArtifacts.CACHE_ROOT_PROPERTY
        );
        try {
            System.clearProperty(NativeRuntimeArtifacts.CACHE_ROOT_PROPERTY);
            assertEquals(
                Path.of("cache", "blockframe", "immutable-v1"),
                NativeRuntimeArtifacts.cacheRoot()
            );

            System.setProperty(
                NativeRuntimeArtifacts.CACHE_ROOT_PROPERTY,
                "target/native-cache-test"
            );
            assertEquals(
                Path.of("target", "native-cache-test"),
                NativeRuntimeArtifacts.cacheRoot()
            );
        } finally {
            if (previous == null) {
                System.clearProperty(
                    NativeRuntimeArtifacts.CACHE_ROOT_PROPERTY
                );
            } else {
                System.setProperty(
                    NativeRuntimeArtifacts.CACHE_ROOT_PROPERTY,
                    previous
                );
            }
        }
    }
}
