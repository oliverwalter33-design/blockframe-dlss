package de.morau.blockframe.faststart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FastStartTimelineTest {
    @TempDir
    java.nio.file.Path temp;

    @Test
    void recordsEachPhaseOnceAndPersistsJsonAndCsvAtomically()
        throws Exception {
        AtomicLong nanos = new AtomicLong(100L);
        AtomicLong epoch = new AtomicLong(1_000L);
        FastStartTimeline timeline = new FastStartTimeline(
            this.temp,
            "test-session",
            "C_TELEMETRY_ONLY",
            () -> nanos.getAndAdd(25L),
            () -> epoch.getAndAdd(5L)
        );

        assertTrue(timeline.record(FastStartPhase.T4, "first"));
        assertFalse(timeline.record(FastStartPhase.T4, "duplicate"));
        assertTrue(timeline.record(FastStartPhase.T5, "second"));
        assertFalse(Files.exists(timeline.jsonPath()));
        timeline.flush();

        assertEquals(2, timeline.snapshot().size());
        assertEquals(100L, timeline.nanoTime(FastStartPhase.T4));
        assertEquals(125L, timeline.nanoTime(FastStartPhase.T5));
        String json = Files.readString(timeline.jsonPath());
        String csv = Files.readString(timeline.csvPath());
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"phase\":\"T4\""));
        assertTrue(json.contains("\"detail\":\"first\""));
        assertFalse(json.contains("duplicate"));
        assertTrue(csv.contains("T5"));
        assertFalse(Files.exists(
            timeline.jsonPath().resolveSibling(
                timeline.jsonPath().getFileName() + ".tmp"
            )
        ));
    }

    @Test
    void escapesUntrustedDetailInBothFormats() throws Exception {
        FastStartTimeline timeline = new FastStartTimeline(
            this.temp,
            "escape",
            "C",
            () -> 1L,
            () -> 2L
        );
        timeline.record(FastStartPhase.T6, "quote\" newline\ncomma,");
        timeline.flush();

        String json = Files.readString(timeline.jsonPath());
        String csv = Files.readString(timeline.csvPath());
        assertTrue(json.contains("quote\\\" newline\\ncomma,"));
        assertTrue(csv.contains("\"quote\"\" newline"));
    }
}
