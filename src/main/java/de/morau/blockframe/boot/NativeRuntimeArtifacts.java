package de.morau.blockframe.boot;

import de.morau.blockframe.cache.ArtifactSource;
import de.morau.blockframe.cache.CacheKey;
import de.morau.blockframe.cache.ImmutableArtifactManifest;
import de.morau.blockframe.cache.PersistentArtifactCache;
import de.morau.blockframe.core.BlockframeRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Materializes the packaged, immutable Windows native runtime through the
 * Phase-2 cache. No shader, registry, resource-pack, mod-list or world data is
 * stored by this slice.
 */
public final class NativeRuntimeArtifacts {
    public static final String CACHE_ROOT_PROPERTY = "blockframe.cache.root";

    private static final String RESOURCE_PREFIX =
        "/assets/nvidia_dlss/native/win-x64/";
    private static final String MANIFEST_RESOURCE =
        "/META-INF/blockframe/native-runtime-v1.manifest";
    private static final String KEY_RESOURCE =
        "/META-INF/blockframe/native-runtime-v1.key";
    private static final String EXPECTED_KIND =
        "native-runtime/windows-x86_64";
    private static final List<String> EXPECTED_FILES = List.of(
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
    );

    private NativeRuntimeArtifacts() {
    }

    public static PersistentArtifactCache.Result materialize()
        throws IOException {
        ImmutableArtifactManifest manifest = loadManifest();
        CacheKey key = loadKey();
        validateGeneratedResources(key, manifest);

        PersistentArtifactCache cache = new PersistentArtifactCache(
            cacheRoot(),
            BlockframeRuntime.engine().config().settings().cacheMaxBytes(),
            BlockframeRuntime.engine().cacheStatistics()
        );
        ArtifactSource source = name -> openRequired(RESOURCE_PREFIX + name);
        return cache.materialize(key, manifest, source);
    }

    static ImmutableArtifactManifest loadManifest() throws IOException {
        try (InputStream input = openRequired(MANIFEST_RESOURCE)) {
            return ImmutableArtifactManifest.parseCanonical(input.readAllBytes());
        }
    }

    static CacheKey loadKey() throws IOException {
        try (InputStream input = openRequired(KEY_RESOURCE)) {
            return CacheKey.parseCanonical(input.readAllBytes());
        }
    }

    static void validateGeneratedResources(
        CacheKey key,
        ImmutableArtifactManifest manifest
    ) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(manifest, "manifest");
        if (key.schemaVersion() != 1 || !EXPECTED_KIND.equals(key.kind())) {
            throw new IOException("Unexpected native cache key identity");
        }
        if (!manifest.bundleSha256().equals(
            key.dimensions().get("artifact-bundle-sha256")
        )) {
            throw new IOException(
                "Native cache key does not match the artifact manifest"
            );
        }
        List<String> actualFiles = manifest.artifacts()
            .stream()
            .map(ImmutableArtifactManifest.Artifact::name)
            .toList();
        if (!EXPECTED_FILES.equals(actualFiles)) {
            throw new IOException(
                "Native manifest has unexpected artifact coverage: "
                    + actualFiles
            );
        }
    }

    static Path cacheRoot() {
        String override = System.getProperty(CACHE_ROOT_PROPERTY);
        if (override == null || override.isBlank()) {
            return Path.of("cache", "blockframe", "immutable-v1");
        }
        return Path.of(override.trim());
    }

    private static InputStream openRequired(String resource)
        throws IOException {
        InputStream input =
            NativeRuntimeArtifacts.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("Required native resource is missing: " + resource);
        }
        return input;
    }
}
