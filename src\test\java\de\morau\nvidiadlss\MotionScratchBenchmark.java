package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reproducible allocation benchmark for the isolated fixed motion-object
 * history and transport scratch.
 *
 * <p>The scratch measurement performs primitive history rotation, lookup and
 * insertion plus the per-frame batch clear, add and packed write loop with
 * one reused target buffer. It deliberately excludes Minecraft entity
 * enumeration, interpolation, bounding-box access and GPU buffer mapping.
 * The legacy list is a clearly labelled allocation reference and uses fewer
 * frames while retaining the same objects and payload bytes per frame. The
 * allocation gate is the median of the benchmark thread's measured samples;
 * timings are illustrative and have no absolute or speedup assertion.</p>
 */
public final class MotionScratchBenchmark {
    private static final int SAMPLE_COUNT = 5;
    private static final int SCRATCH_WARMUP_FRAMES = 20_000;
    private static final int SCRATCH_MEASURED_FRAMES = 100_000;
    private static final int LEGACY_WARMUP_FRAMES = 2_000;
    private static final int LEGACY_MEASURED_FRAMES = 10_000;
    private static final int OBJECTS_PER_FRAME =
        MotionVectorGenerator.MAX_OBJECTS;
    private static final int HISTORY_CAPACITY = 128;
    private static final int PAYLOAD_BYTES_PER_FRAME =
        OBJECTS_PER_FRAME * MotionObjectBatch.PACKED_BYTES;
    private static final int CHECKSUM_LANES =
        MotionObjectBatch.PACKED_BYTES / Long.BYTES;
    private static final long CHECKSUM_SEED = 0x6A09E667F3BCC909L;

    private static volatile long blackhole;

    private MotionScratchBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected output CSV path");
        }

        com.sun.management.ThreadMXBean allocationBean =
            requiredAllocationBean();
        long threadId = Thread.currentThread().threadId();
        MemoryBudgetManager budgets = new MemoryBudgetManager(
            MemoryBudgetSettings.defaults()
        );
        MotionObjectBatch batch = MotionObjectBatch.tryCreate(
            budgets,
            OBJECTS_PER_FRAME
        );
        if (batch == null) {
            budgets.close();
            throw new IllegalStateException(
                "motion scratch batch could not be reserved"
            );
        }
        EntityMotionHistory history = EntityMotionHistory.tryCreate(
            budgets,
            HISTORY_CAPACITY
        );
        if (history == null) {
            batch.close();
            budgets.close();
            throw new IllegalStateException(
                "motion scratch history could not be reserved"
            );
        }

        Samples scratch;
        MemoryBudgetManager.Snapshot closedSnapshot;
        try {
            ByteBuffer scratchTarget = targetBuffer();
            primeHistory(history);
            publish(runScratch(
                history,
                batch,
                scratchTarget,
                SCRATCH_WARMUP_FRAMES
            ));
            scratch = measureScratch(
                history,
                batch,
                scratchTarget,
                allocationBean,
                threadId
            );
        } finally {
            try {
                history.close();
            } finally {
                batch.close();
            }
        }

        closedSnapshot = budgets.snapshot();
        requireCleanClose(closedSnapshot);
        budgets.close();

        if (scratch.medianAllocatedBytes() != 0L) {
            throw new IllegalStateException(
                "isolated scratch_history_batch median allocated "
                    + scratch.medianAllocatedBytes()
                    + " bytes; expected a zero-byte median for the "
                    + "benchmark thread"
            );
        }

        ByteBuffer legacyTarget = targetBuffer();
        publish(runLegacy(legacyTarget, LEGACY_WARMUP_FRAMES));
        Samples legacy = measureLegacy(
            legacyTarget,
            allocationBean,
            threadId
        );

        String csv = createCsv(scratch, legacy, closedSnapshot);
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        System.out.print(csv);
        System.out.printf(
            Locale.ROOT,
            "isolated scratch_history_batch median: %.3f ns/frame, "
                + "%.3f allocated B/frame on benchmark thread%n",
            scratch.medianElapsedNanos()
                / (double)SCRATCH_MEASURED_FRAMES,
            scratch.medianAllocatedBytes()
                / (double)SCRATCH_MEASURED_FRAMES
        );
        System.out.println("CSV: " + output);
    }

    private static Samples measureScratch(
        EntityMotionHistory history,
        MotionObjectBatch batch,
        ByteBuffer target,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        Samples samples = new Samples(SCRATCH_MEASURED_FRAMES);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long started = System.nanoTime();
            long checksum = runScratch(
                history,
                batch,
                target,
                SCRATCH_MEASURED_FRAMES
            );
            long elapsed = System.nanoTime() - started;
            long allocatedAfter = allocatedBytes(
                allocationBean,
                threadId
            );
            samples.record(
                sample,
                elapsed,
                Math.max(0L, allocatedAfter - allocatedBefore),
                checksum
            );
            publish(checksum);
        }
        return samples;
    }

    private static Samples measureLegacy(
        ByteBuffer target,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        Samples samples = new Samples(LEGACY_MEASURED_FRAMES);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long started = System.nanoTime();
            long checksum = runLegacy(
                target,
                LEGACY_MEASURED_FRAMES
            );
            long elapsed = System.nanoTime() - started;
            long allocatedAfter = allocatedBytes(
                allocationBean,
                threadId
            );
            samples.record(
                sample,
                elapsed,
                Math.max(0L, allocatedAfter - allocatedBefore),
                checksum
            );
            publish(checksum);
        }
        return samples;
    }

    private static long runScratch(
        EntityMotionHistory history,
        MotionObjectBatch batch,
        ByteBuffer target,
        int frames
    ) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < frames; frame++) {
            history.beginFrame();
            batch.clear();
            target.clear();
            for (int object = 0; object < OBJECTS_PER_FRAME; object++) {
                double base = valueBase(frame, object);
                int entityId = object + 1;
                if (!history.findPrevious(entityId)) {
                    throw new IllegalStateException(
                        "scratch history lost entity " + entityId
                    );
                }
                double previousX = history.previousX();
                double previousY = history.previousY();
                double previousZ = history.previousZ();
                float previousYaw = history.previousYaw();
                if (
                    !history.putCurrent(
                        entityId,
                        base + 9.0D,
                        base + 10.0D,
                        base + 11.0D,
                        (float)(base + 12.0D)
                    )
                ) {
                    throw new IllegalStateException(
                        "scratch history rejected entity " + entityId
                    );
                }
                boolean added = batch.add(
                    base,
                    base + 1.0D,
                    base + 2.0D,
                    base + 3.0D,
                    base + 4.0D,
                    base + 5.0D,
                    previousX,
                    previousY,
                    previousZ,
                    base + 9.0D,
                    base + 10.0D,
                    base + 11.0D,
                    (float)(base + 12.0D),
                    previousYaw
                );
                if (!added) {
                    throw new IllegalStateException(
                        "scratch batch rejected object " + object
                    );
                }
            }
            if (batch.size() != OBJECTS_PER_FRAME) {
                throw new IllegalStateException(
                    "scratch batch did not retain the full frame"
                );
            }
            for (int object = 0; object < OBJECTS_PER_FRAME; object++) {
                batch.writeObject(object, target);
            }
            requireCompletePayload(target);
            checksum = consume(target, frame, checksum);
        }
        return checksum;
    }

    private static void primeHistory(EntityMotionHistory history) {
        history.beginFrame();
        for (int object = 0; object < OBJECTS_PER_FRAME; object++) {
            double base = valueBase(-1, object);
            if (
                !history.putCurrent(
                    object + 1,
                    base + 9.0D,
                    base + 10.0D,
                    base + 11.0D,
                    (float)(base + 12.0D)
                )
            ) {
                throw new IllegalStateException(
                    "could not prime scratch history"
                );
            }
        }
    }

    private static long runLegacy(ByteBuffer target, int frames) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < frames; frame++) {
            List<LegacyMotionObject> objects = new ArrayList<>(
                OBJECTS_PER_FRAME
            );
            for (int object = 0; object < OBJECTS_PER_FRAME; object++) {
                double base = valueBase(frame, object);
                objects.add(new LegacyMotionObject(
                    base,
                    base + 1.0D,
                    base + 2.0D,
                    base + 3.0D,
                    base + 4.0D,
                    base + 5.0D,
                    base + 6.0D,
                    base + 7.0D,
                    base + 8.0D,
                    base + 9.0D,
                    base + 10.0D,
                    base + 11.0D,
                    (float)(base + 12.0D),
                    (float)(base + 13.0D)
                ));
            }

            target.clear();
            for (int object = 0; object < objects.size(); object++) {
                writeLegacyObject(target, objects.get(object));
            }
            requireCompletePayload(target);
            checksum = consume(target, frame, checksum);
        }
        return checksum;
    }

    private static void writeLegacyObject(
        ByteBuffer target,
        LegacyMotionObject object
    ) {
        target
            .putFloat((float)object.minX())
            .putFloat((float)object.minY())
            .putFloat((float)object.minZ())
            .putFloat(0.0F);
        target
            .putFloat((float)object.maxX())
            .putFloat((float)object.maxY())
            .putFloat((float)object.maxZ())
            .putFloat(0.0F);
        target
            .putFloat((float)object.previousX())
            .putFloat((float)object.previousY())
            .putFloat((float)object.previousZ())
            .putFloat(0.0F);
        target
            .putFloat((float)object.currentX())
            .putFloat((float)object.currentY())
            .putFloat((float)object.currentZ())
            .putFloat(0.0F);
        target
            .putFloat(object.currentYaw())
            .putFloat(object.previousYaw())
            .putFloat(0.0F)
            .putFloat(0.0F);
    }

    private static long consume(
        ByteBuffer target,
        int frame,
        long checksum
    ) {
        int laneOffset = (frame % CHECKSUM_LANES) * Long.BYTES;
        long result = checksum;
        for (int object = 0; object < OBJECTS_PER_FRAME; object++) {
            int offset =
                object * MotionObjectBatch.PACKED_BYTES + laneOffset;
            result = Long.rotateLeft(
                result ^ target.getLong(offset),
                7
            );
        }
        return result;
    }

    private static void requireCompletePayload(ByteBuffer target) {
        if (target.position() != PAYLOAD_BYTES_PER_FRAME) {
            throw new IllegalStateException(
                "motion payload wrote "
                    + target.position()
                    + " of "
                    + PAYLOAD_BYTES_PER_FRAME
                    + " bytes"
            );
        }
    }

    private static double valueBase(int frame, int object) {
        return frame * 0.125D + object * 16.0D;
    }

    private static ByteBuffer targetBuffer() {
        return ByteBuffer
            .allocate(PAYLOAD_BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder());
    }

    private static String createCsv(
        Samples scratch,
        Samples legacy,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        StringBuilder csv = new StringBuilder(2048);
        csv.append(
            "operation,sample,frames,objects_per_frame,"
                + "payload_bytes_per_frame,total_nanos,nanos_per_frame,"
                + "allocated_bytes,allocated_bytes_per_frame,checksum,"
                + "rejections,outstanding\n"
        );
        appendSamples(
            csv,
            "scratch_history_batch",
            scratch,
            snapshot
        );
        appendSamples(csv, "legacy_list_reference", legacy, snapshot);
        return csv.toString();
    }

    private static void appendSamples(
        StringBuilder csv,
        String operation,
        Samples samples,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            appendRow(
                csv,
                operation,
                Integer.toString(sample + 1),
                samples.frames(),
                samples.elapsedNanos(sample),
                samples.allocatedBytes(sample),
                samples.checksum(sample),
                snapshot
            );
        }
        appendRow(
            csv,
            operation,
            "median",
            samples.frames(),
            samples.medianElapsedNanos(),
            samples.medianAllocatedBytes(),
            samples.aggregateChecksum(),
            snapshot
        );
    }

    private static void appendRow(
        StringBuilder csv,
        String operation,
        String sample,
        int frames,
        long elapsedNanos,
        long allocatedBytes,
        long checksum,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        csv.append(operation).append(',');
        csv.append(sample).append(',');
        csv.append(frames).append(',');
        csv.append(OBJECTS_PER_FRAME).append(',');
        csv.append(PAYLOAD_BYTES_PER_FRAME).append(',');
        csv.append(elapsedNanos).append(',');
        csv.append(elapsedNanos / (double)frames).append(',');
        csv.append(allocatedBytes).append(',');
        csv.append(allocatedBytes / (double)frames).append(',');
        csv.append(Long.toUnsignedString(checksum)).append(',');
        csv.append(snapshot.rejections()).append(',');
        csv.append(snapshot.outstanding()).append('\n');
    }

    private static void requireCleanClose(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (snapshot.outstanding() != 0 || snapshot.rejections() != 0L) {
            throw new IllegalStateException(
                "motion scratch close left outstanding="
                    + snapshot.outstanding()
                    + ", rejections="
                    + snapshot.rejections()
            );
        }
    }

    private static com.sun.management.ThreadMXBean requiredAllocationBean() {
        java.lang.management.ThreadMXBean bean =
            ManagementFactory.getThreadMXBean();
        if (
            !(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()
        ) {
            throw new IllegalStateException(
                "ThreadMXBean allocated-memory accounting is unavailable"
            );
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    private static long allocatedBytes(
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        long allocated = allocationBean.getThreadAllocatedBytes(threadId);
        if (allocated < 0L) {
            throw new IllegalStateException(
                "ThreadMXBean returned no allocation count"
            );
        }
        return allocated;
    }

    private static void publish(long checksum) {
        blackhole = blackhole ^ checksum;
    }

    private static final class Samples {
        private final int frames;
        private final long[] elapsedNanos = new long[SAMPLE_COUNT];
        private final long[] allocatedBytes = new long[SAMPLE_COUNT];
        private final long[] checksums = new long[SAMPLE_COUNT];

        private Samples(int frames) {
            this.frames = frames;
        }

        private void record(
            int sample,
            long elapsed,
            long allocated,
            long checksum
        ) {
            this.elapsedNanos[sample] = elapsed;
            this.allocatedBytes[sample] = allocated;
            this.checksums[sample] = checksum;
        }

        private int frames() {
            return this.frames;
        }

        private long elapsedNanos(int sample) {
            return this.elapsedNanos[sample];
        }

        private long allocatedBytes(int sample) {
            return this.allocatedBytes[sample];
        }

        private long checksum(int sample) {
            return this.checksums[sample];
        }

        private long medianElapsedNanos() {
            return median(this.elapsedNanos);
        }

        private long medianAllocatedBytes() {
            return median(this.allocatedBytes);
        }

        private long aggregateChecksum() {
            long aggregate = 0L;
            for (long checksum : this.checksums) {
                aggregate ^= checksum;
            }
            return aggregate;
        }

        private static long median(long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }
    }

    private record LegacyMotionObject(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double previousX,
        double previousY,
        double previousZ,
        double currentX,
        double currentY,
        double currentZ,
        float currentYaw,
        float previousYaw
    ) {
    }
}
