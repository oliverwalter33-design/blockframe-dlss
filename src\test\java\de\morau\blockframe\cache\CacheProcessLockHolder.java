package de.morau.blockframe.cache;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Helper process used by the cross-JVM file-lock regression test. */
public final class CacheProcessLockHolder {
    private CacheProcessLockHolder() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                "Expected root, ready, release and marker paths"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path ready = Path.of(arguments[1]);
        Path release = Path.of(arguments[2]);
        Path marker = Path.of(arguments[3]);
        Files.createDirectories(root.resolve("entries"));
        try (FileChannel channel = FileChannel.open(
                 root.resolve(".blockframe-lock"),
                 StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE
             );
             FileLock ignored = channel.lock()) {
            Files.writeString(
                ready,
                "locked\n",
                StandardOpenOption.CREATE_NEW
            );
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!Files.exists(release)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                        "Timed out waiting for lock release"
                    );
                }
                Thread.sleep(10L);
            }
            Files.writeString(
                marker,
                "release-request-observed-while-locked\n",
                StandardOpenOption.CREATE_NEW
            );
        }
    }
}
