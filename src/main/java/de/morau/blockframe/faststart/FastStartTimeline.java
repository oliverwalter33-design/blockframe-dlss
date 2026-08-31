package de.morau.blockframe.faststart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Low-volume startup timeline.
 *
 * <p>The recorder performs no sampling and writes at most once per accepted
 * phase. JSON and CSV are replaced atomically. All phase durations use
 * {@link System#nanoTime()}, while epoch time is metadata only.</p>
 */
public final class FastStartTimeline {
    public record Entry(
        FastStartPhase phase,
        long nanoTime,
        long epochMillis,
        String thread,
        String detail
    ) {}

    private static final int SCHEMA_VERSION = 1;
    private final Object lock = new Object();
    private final Object persistenceLock = new Object();
    private final Path outputDirectory;
    private final Path jsonPath;
    private final Path csvPath;
    private final String sessionId;
    private final String profile;
    private final LongSupplier nanoClock;
    private final LongSupplier epochClock;
    private final EnumMap<FastStartPhase, Entry> entries =
        new EnumMap<>(FastStartPhase.class);
    private volatile List<String> cachedDebugLines;
    private volatile String lastWriteError = "keiner";
    private volatile CompletableFuture<Void> pendingPersistence =
        CompletableFuture.completedFuture(null);

    public FastStartTimeline(
        Path gameDirectory,
        String sessionId,
        String profile
    ) {
        this(
            gameDirectory,
            sessionId,
            profile,
            System::nanoTime,
            System::currentTimeMillis
        );
    }

    FastStartTimeline(
        Path gameDirectory,
        String sessionId,
        String profile,
        LongSupplier nanoClock,
        LongSupplier epochClock
    ) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.sessionId = safeToken(sessionId, defaultSessionId());
        this.profile = safeToken(profile, "C_TELEMETRY_ONLY");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.epochClock = Objects.requireNonNull(epochClock, "epochClock");
        this.outputDirectory = gameDirectory
            .resolve("blockframe")
            .resolve("faststart")
            .resolve("timelines");
        this.jsonPath = this.outputDirectory.resolve(
            this.sessionId + ".internal.json"
        );
        this.csvPath = this.outputDirectory.resolve(
            this.sessionId + ".internal.csv"
        );
        this.cachedDebugLines = List.of(
            "FastStart: warte auf erste interne Phase",
            "Profil: " + this.profile,
            "Optimierungen: AUS (Telemetrieprofil C)"
        );
    }

    public boolean record(FastStartPhase phase, String detail) {
        Objects.requireNonNull(phase, "phase");
        Entry entry;
        synchronized (this.lock) {
            if (this.entries.containsKey(phase)) {
                return false;
            }
            entry = new Entry(
                phase,
                this.nanoClock.getAsLong(),
                this.epochClock.getAsLong(),
                Thread.currentThread().getName(),
                detail == null ? "" : detail
            );
            this.entries.put(phase, entry);
            this.refreshDebugLinesLocked();
        }
        return true;
    }

    /**
     * Publishes the latest successful-start snapshot outside the render path.
     *
     * <p>Milestone recording itself performs no file I/O. Only the completed
     * T16 boundary requests this coalesced background publication; shutdown
     * performs one final synchronous flush.</p>
     */
    public void persistAsync() {
        synchronized (this.persistenceLock) {
            if (!this.pendingPersistence.isDone()) {
                return;
            }
            this.pendingPersistence = CompletableFuture.runAsync(
                this::persistBestEffort
            );
        }
    }

    public void flush() {
        CompletableFuture<Void> pending = this.pendingPersistence;
        try {
            pending.join();
        } catch (RuntimeException ignored) {
            // persistBestEffort reports the bounded diagnostic state.
        }
        this.persistBestEffort();
    }

    public boolean recorded(FastStartPhase phase) {
        synchronized (this.lock) {
            return this.entries.containsKey(phase);
        }
    }

    public long nanoTime(FastStartPhase phase) {
        synchronized (this.lock) {
            Entry entry = this.entries.get(phase);
            return entry == null ? Long.MIN_VALUE : entry.nanoTime();
        }
    }

    public List<Entry> snapshot() {
        synchronized (this.lock) {
            return List.copyOf(this.entries.values());
        }
    }

    public List<String> debugLines() {
        return this.cachedDebugLines;
    }

    public Path jsonPath() {
        return this.jsonPath;
    }

    public Path csvPath() {
        return this.csvPath;
    }

    private void refreshDebugLinesLocked() {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("BlockFrame FastStart [F8 Seite 2/2]");
        lines.add("Session: " + this.sessionId);
        lines.add("Profil: " + this.profile);
        lines.add("Optimierungen: AUS (Telemetrieprofil C)");
        lines.add("AOT/CDS: INAKTIV / nicht freigegeben");
        lines.add("Safe Mode: inaktiv (keine Optimierung aktiv)");
        long origin = this.entries.isEmpty()
            ? Long.MIN_VALUE
            : this.entries.values().iterator().next().nanoTime();
        for (Map.Entry<FastStartPhase, Entry> item : this.entries.entrySet()) {
            Entry entry = item.getValue();
            double milliseconds = origin == Long.MIN_VALUE
                ? 0.0
                : (entry.nanoTime() - origin) / 1_000_000.0;
            lines.add(
                String.format(
                    Locale.ROOT,
                    "%s +%.3f ms %s",
                    entry.phase().name(),
                    milliseconds,
                    entry.phase().label()
                )
            );
        }
        lines.add("Cache: keine FastStart-Optimierungscaches");
        lines.add("Timeline-Schreibfehler: " + this.lastWriteError);
        this.cachedDebugLines = List.copyOf(lines);
    }

    private void persistBestEffort() {
        String json;
        String csv;
        synchronized (this.lock) {
            json = this.toJsonLocked();
            csv = this.toCsvLocked();
        }
        synchronized (this.persistenceLock) {
            try {
                Files.createDirectories(this.outputDirectory);
                writeAtomically(this.jsonPath, json);
                writeAtomically(this.csvPath, csv);
                this.lastWriteError = "keiner";
            } catch (IOException error) {
                this.lastWriteError =
                    error.getClass().getSimpleName() + ": " + error.getMessage();
            }
        }
        synchronized (this.lock) {
            this.refreshDebugLinesLocked();
        }
    }

    private String toJsonLocked() {
        StringBuilder out = new StringBuilder(2048);
        out.append("{\n");
        out.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        out.append("  \"clock\": \"System.nanoTime\",\n");
        out.append("  \"sessionId\": \"").append(json(this.sessionId)).append("\",\n");
        out.append("  \"profile\": \"").append(json(this.profile)).append("\",\n");
        out.append("  \"processId\": ").append(ProcessHandle.current().pid()).append(",\n");
        out.append("  \"events\": [\n");
        int index = 0;
        for (Entry entry : this.entries.values()) {
            if (index++ > 0) {
                out.append(",\n");
            }
            out.append("    {\"phase\":\"")
                .append(entry.phase().name())
                .append("\",\"id\":\"")
                .append(json(entry.phase().id()))
                .append("\",\"owner\":\"")
                .append(entry.phase().owner())
                .append("\",\"nanoTime\":")
                .append(entry.nanoTime())
                .append(",\"epochMillis\":")
                .append(entry.epochMillis())
                .append(",\"thread\":\"")
                .append(json(entry.thread()))
                .append("\",\"detail\":\"")
                .append(json(entry.detail()))
                .append("\"}");
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private String toCsvLocked() {
        StringBuilder out = new StringBuilder(1024);
        out.append(
            "schema_version,session_id,profile,phase,id,owner,nano_time,"
                + "epoch_millis,thread,detail\n"
        );
        for (Entry entry : this.entries.values()) {
            out.append(SCHEMA_VERSION).append(',')
                .append(csv(this.sessionId)).append(',')
                .append(csv(this.profile)).append(',')
                .append(entry.phase().name()).append(',')
                .append(csv(entry.phase().id())).append(',')
                .append(entry.phase().owner()).append(',')
                .append(entry.nanoTime()).append(',')
                .append(entry.epochMillis()).append(',')
                .append(csv(entry.thread())).append(',')
                .append(csv(entry.detail())).append('\n');
        }
        return out.toString();
    }

    private static void writeAtomically(Path target, String content)
        throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (
            FileChannel channel = FileChannel.open(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        ) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        if (Files.size(temp) != bytes.length) {
            throw new IOException("unvollständige temporäre Timeline");
        }
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                temp,
                target,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(
                            String.format(Locale.ROOT, "\\u%04x", (int) ch)
                        );
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String safeToken(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value;
        String safe = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    private static String defaultSessionId() {
        return Instant.now().toString().replace(':', '-')
            + "-"
            + ProcessHandle.current().pid();
    }
}
