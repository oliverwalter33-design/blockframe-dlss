package de.morau.blockframe.benchmark.phase2a0b;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Fixed-capacity primitive sample buffer. The MEASURE write path performs no
 * file I/O, collection operation or object construction.
 */
public final class MeasurementBuffer {
    public static final long NOT_AVAILABLE = -1L;
    private final long[] frameId;
    private final long[] replayNanos;
    private final long[] cpuFrameNanos;
    private final long[] cameraHash64;
    private final long[] gpuTimerNanos;
    private final long[] renderWaitNanos;
    private final long[] chunkBacklog;
    private final long[] uploadBacklog;
    private final long[] jobBacklog;
    private final long[] visibleSections;
    private final long[] drawCount;
    private final long[] submitCount;
    private int size;
    private boolean overflow;

    public MeasurementBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.frameId = new long[capacity];
        this.replayNanos = new long[capacity];
        this.cpuFrameNanos = new long[capacity];
        this.cameraHash64 = new long[capacity];
        this.gpuTimerNanos = new long[capacity];
        this.renderWaitNanos = new long[capacity];
        this.chunkBacklog = new long[capacity];
        this.uploadBacklog = new long[capacity];
        this.jobBacklog = new long[capacity];
        this.visibleSections = new long[capacity];
        this.drawCount = new long[capacity];
        this.submitCount = new long[capacity];
    }

    public boolean record(
        long frameId,
        long replayNanos,
        long cpuFrameNanos,
        long cameraHash64,
        long gpuTimerNanos,
        long renderWaitNanos,
        long chunkBacklog,
        long uploadBacklog,
        long jobBacklog,
        long visibleSections,
        long drawCount,
        long submitCount
    ) {
        if (this.size == this.frameId.length) {
            this.overflow = true;
            return false;
        }
        int index = this.size++;
        this.frameId[index] = frameId;
        this.replayNanos[index] = replayNanos;
        this.cpuFrameNanos[index] = cpuFrameNanos;
        this.cameraHash64[index] = cameraHash64;
        this.gpuTimerNanos[index] = gpuTimerNanos;
        this.renderWaitNanos[index] = renderWaitNanos;
        this.chunkBacklog[index] = chunkBacklog;
        this.uploadBacklog[index] = uploadBacklog;
        this.jobBacklog[index] = jobBacklog;
        this.visibleSections[index] = visibleSections;
        this.drawCount[index] = drawCount;
        this.submitCount[index] = submitCount;
        return true;
    }

    public int size() {
        return this.size;
    }

    public int capacity() {
        return this.frameId.length;
    }

    public boolean overflowed() {
        return this.overflow;
    }

    /**
     * Reuses the fixed primitive storage between suite scenes. This is called
     * only after the preceding MEASURE window has ended.
     */
    public void reset() {
        this.size = 0;
        this.overflow = false;
    }

    public long frameId(int index) {
        return this.frameId[checked(index)];
    }

    public long replayNanos(int index) {
        return this.replayNanos[checked(index)];
    }

    public long cpuFrameNanos(int index) {
        return this.cpuFrameNanos[checked(index)];
    }

    public long cameraHash64(int index) {
        return this.cameraHash64[checked(index)];
    }

    public void writeCsv(Path output) throws IOException {
        try (
            BufferedWriter writer = Files.newBufferedWriter(
                output,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        ) {
            writer.write(
                "frame_id,replay_nanos,cpu_frame_nanos,camera_hash64,"
                    + "gpu_timer_nanos,render_wait_nanos,chunk_backlog,"
                    + "upload_backlog,job_backlog,visible_sections,"
                    + "draw_count,submit_count"
            );
            writer.newLine();
            for (int index = 0; index < this.size; index++) {
                writer.write(Long.toString(this.frameId[index]));
                writer.write(',');
                writer.write(Long.toString(this.replayNanos[index]));
                writer.write(',');
                writer.write(Long.toString(this.cpuFrameNanos[index]));
                writer.write(',');
                writer.write(
                    Long.toUnsignedString(this.cameraHash64[index])
                );
                writeLong(writer, this.gpuTimerNanos[index]);
                writeLong(writer, this.renderWaitNanos[index]);
                writeLong(writer, this.chunkBacklog[index]);
                writeLong(writer, this.uploadBacklog[index]);
                writeLong(writer, this.jobBacklog[index]);
                writeLong(writer, this.visibleSections[index]);
                writeLong(writer, this.drawCount[index]);
                writeLong(writer, this.submitCount[index]);
                writer.newLine();
            }
        }
    }

    private static void writeLong(BufferedWriter writer, long value)
        throws IOException {
        writer.write(',');
        writer.write(Long.toString(value));
    }

    private int checked(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException(index);
        }
        return index;
    }
}
