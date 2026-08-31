package de.morau.blockframe.core.state;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Helper process for the real cross-JVM non-blocking lock test. */
public final class RunStateProcessLockHolder {
    private RunStateProcessLockHolder() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                "Expected state directory, ready marker and release marker"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path ready = Path.of(arguments[1]);
        Path release = Path.of(arguments[2]);
        Files.createDirectories(root);
        try (
            FileChannel channel = FileChannel.open(
                root.resolve(RunStateStore.LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            );
            FileLock ignored = channel.lock()
        ) {
            Files.writeString(
                ready,
                "locked\n",
                StandardOpenOption.CREATE_NEW
            );
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!Files.exists(release)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                        "timed out waiting for release marker"
                    );
                }
                Thread.sleep(10L);
            }
        }
    }
}
