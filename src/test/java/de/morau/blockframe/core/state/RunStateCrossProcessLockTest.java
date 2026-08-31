package de.morau.blockframe.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStateCrossProcessLockTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void realSecondJvmLockConflictNeverWaitsForOwner() throws Exception {
        Path root = this.temporaryDirectory.resolve("cross-process");
        Path ready = this.temporaryDirectory.resolve("ready");
        Path release = this.temporaryDirectory.resolve("release");
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javaBinary = javaHome.resolve("bin").resolve(
            System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("windows")
                ? "java.exe"
                : "java"
        );
        Process process = new ProcessBuilder(
            javaBinary.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            RunStateProcessLockHolder.class.getName(),
            root.toString(),
            ready.toString(),
            release.toString()
        ).redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!Files.exists(ready)) {
                if (!process.isAlive()) {
                    fail(
                        "lock holder exited early: "
                            + new String(process.getInputStream().readAllBytes())
                    );
                }
                if (System.nanoTime() >= deadline) {
                    fail("timed out waiting for lock holder");
                }
                Thread.sleep(10L);
            }

            long started = System.nanoTime();
            try (RunStateStore contender = RunStateStore.open(
                root,
                new RunStateIdentity(
                    "1.0.0",
                    "1.21.8",
                    "c".repeat(64),
                    1L,
                    1L,
                    0L
                )
            )) {
                long elapsedMillis =
                    (System.nanoTime() - started) / 1_000_000L;
                assertTrue(
                    elapsedMillis < 1_000L,
                    "try-lock must not wait for the owner"
                );
                assertEquals(
                    RunStatePersistenceStatus.READ_ONLY_LOCK_CONFLICT,
                    contender.persistenceStatus()
                );
            }
        } finally {
            Files.writeString(release, "release\n");
            assertTrue(process.waitFor(5L, TimeUnit.SECONDS));
            if (process.exitValue() != 0) {
                fail(
                    "lock holder failed: "
                        + new String(process.getInputStream().readAllBytes())
                );
            }
        }
    }
}
