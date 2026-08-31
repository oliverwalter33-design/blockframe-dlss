package de.morau.blockframe.core.state;

import java.io.IOException;
import java.nio.file.Path;

/** Test seam for the bounded lifecycle-only run-state filesystem work. */
interface RunStateIo {
    void ensureDirectory(Path directory) throws IOException;

    /**
     * Attempts a non-blocking process-lifetime lock.
     *
     * @return a held lock, or {@code null} when another process/JVM owns it
     */
    LockHandle tryAcquire(Path lockFile) throws IOException;

    /** Returns {@code null} only when the path is absent. */
    byte[] readBounded(Path path, int maximumBytes) throws IOException;

    void writeForced(Path path, byte[] content) throws IOException;

    void atomicReplace(Path source, Path target) throws IOException;

    void replace(Path source, Path target) throws IOException;

    void deleteIfExists(Path path) throws IOException;

    interface LockHandle extends AutoCloseable {
        @Override
        void close() throws IOException;
    }
}
