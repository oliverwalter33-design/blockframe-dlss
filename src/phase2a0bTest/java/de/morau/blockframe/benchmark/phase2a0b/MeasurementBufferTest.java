package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeasurementBufferTest {
    @TempDir
    Path temporary;

    @Test
    void recordsPrimitiveSamplesWritesAfterwardAndFailsClosedOnOverflow()
        throws Exception {
        MeasurementBuffer buffer = new MeasurementBuffer(2);
        assertTrue(buffer.record(1, 2, 3, 4, -1, -1, -1, -1, -1, -1, -1, -1));
        assertTrue(buffer.record(5, 6, 7, 8, -1, -1, -1, -1, -1, -1, -1, -1));
        assertFalse(buffer.record(9, 10, 11, 12, -1, -1, -1, -1, -1, -1, -1, -1));
        assertEquals(2, buffer.size());
        assertTrue(buffer.overflowed());
        assertEquals(1L, buffer.frameId(0));
        assertEquals(8L, buffer.cameraHash64(1));
        Path output = this.temporary.resolve("samples.csv");
        buffer.writeCsv(output);
        assertEquals(3L, Files.lines(output).count());
    }
}
