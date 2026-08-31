package de.morau.blockframe.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImmutableArtifactManifestTest {
    @Test
    void serializesTheBuildFormatAndRoundTrips() {
        ImmutableArtifactManifest manifest = ImmutableArtifactManifest.of(
            List.of(
                artifact("z.dll", 2L, "b"),
                artifact("A.dll", 1L, "a")
            )
        );
        String canonical = new String(
            manifest.canonicalBytes(),
            StandardCharsets.UTF_8
        );
        String fileLines =
            "file=A.dll\t1\t" + "a".repeat(64) + "\n"
                + "file=z.dll\t2\t" + "b".repeat(64) + "\n";
        String expectedBundle = CacheKey.sha256Hex(
            fileLines.getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(
            "BLOCKFRAME_IMMUTABLE_ARTIFACTS_V1\n"
                + "schema=1\n"
                + "artifact=native-runtime\n"
                + "count=2\n"
                + fileLines
                + "bundle=" + expectedBundle + "\n",
            canonical
        );
        ImmutableArtifactManifest reparsed =
            ImmutableArtifactManifest.parseCanonical(manifest.canonicalBytes());
        assertEquals(manifest.artifacts(), reparsed.artifacts());
        assertEquals(3L, reparsed.totalBytes());
        assertEquals(expectedBundle, reparsed.bundleSha256());
        assertArrayEquals(manifest.canonicalBytes(), reparsed.canonicalBytes());
    }

    @Test
    void rejectsCaseInsensitiveDuplicatesAndUnsafeWindowsNames() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ImmutableArtifactManifest.of(
                List.of(
                    artifact("same.dll", 1L, "a"),
                    artifact("SAME.DLL", 1L, "b")
                )
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> artifact("../escape.dll", 1L, "a")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> artifact("CON.dll", 1L, "a")
        );
    }

    @Test
    void rejectsWindowsTrailingPeriodAlias() {
        assertThrows(
            IllegalArgumentException.class,
            () -> artifact("native.dll.", 1L, "a")
        );
    }

    @Test
    void rejectsWrongBundleNonCanonicalOrderAndTrailingBytes() {
        ImmutableArtifactManifest manifest = ImmutableArtifactManifest.of(
            List.of(artifact("native.dll", 4L, "a"))
        );
        String canonical = new String(
            manifest.canonicalBytes(),
            StandardCharsets.UTF_8
        );
        byte[] wrongBundle = canonical
            .replace(manifest.bundleSha256(), "0".repeat(64))
            .getBytes(StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> ImmutableArtifactManifest.parseCanonical(wrongBundle)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ImmutableArtifactManifest.parseCanonical(
                (canonical + "\n").getBytes(StandardCharsets.UTF_8)
            )
        );

        ImmutableArtifactManifest two = ImmutableArtifactManifest.of(
            List.of(
                artifact("a.dll", 1L, "a"),
                artifact("b.dll", 1L, "b")
            )
        );
        String ordered = new String(two.canonicalBytes(), StandardCharsets.UTF_8);
        String a = "file=a.dll\t1\t" + "a".repeat(64) + "\n";
        String b = "file=b.dll\t1\t" + "b".repeat(64) + "\n";
        byte[] reversed = ordered.replace(a + b, b + a)
            .getBytes(StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> ImmutableArtifactManifest.parseCanonical(reversed)
        );
    }

    private static ImmutableArtifactManifest.Artifact artifact(
        String name,
        long size,
        String digestCharacter
    ) {
        return new ImmutableArtifactManifest.Artifact(
            name,
            size,
            digestCharacter.repeat(64)
        );
    }
}
