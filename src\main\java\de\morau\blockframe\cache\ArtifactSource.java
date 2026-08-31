package de.morau.blockframe.cache;

import java.io.IOException;
import java.io.InputStream;

/** Reopenable source for manifest-declared immutable artifact bytes. */
@FunctionalInterface
public interface ArtifactSource {
    InputStream open(String artifactName) throws IOException;
}
