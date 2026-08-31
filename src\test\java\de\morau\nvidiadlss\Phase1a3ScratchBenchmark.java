package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryBudgetSettings;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.core.memory.BudgetedNativeArena;
import de.morau.blockframe.core.memory.ReusableNativeBlockPool;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Phase 1A.3 microbenchmark for isolated reusable transform and native
 * scratch operations.
 *
 * <p>The transform comparison performs numerically equivalent projection,
 * view, inverse, clip-history, orientation-history and camera-vector work.
 * The legacy row is deliberately labelled as an allocation reference. The
 * native arena claims its four-long view once during setup; measured arena
 * iterations only reuse that view. The measured pool path performs one
 * bounded borrow/buffer/release cycle against a pre-created block.</p>
 *
 * <p>Exactly five samples are recorded after warm-up. Allocation counts come
 * exclusively from the Java 25 benchmark thread's
 * {@link com.sun.management.ThreadMXBean}. A zero-byte median is required
 * only for the isolated transform-scratch, native-view-reuse and
 * native-block-reuse operations. Timings make no renderer, GPU,
 * large-native-dataset or speedup claim.</p>
 */
public final class Phase1a3ScratchBenchmark {
    private static final int SAMPLE_COUNT = 5;
    private static final int TRANSFORM_WARMUP_ITERATIONS = 10_000;
    private static final int TRANSFORM_MEASURED_ITERATIONS = 50_000;
    private static final int NATIVE_WARMUP_ITERATIONS = 100_000;
    private static final int NATIVE_MEASURED_ITERATIONS = 500_000;
    private static final int QUERY_LONGS = 4;
    private static final int QUERY_BYTES = QUERY_LONGS * Long.BYTES;
    private static final int STAGING_BLOCK_BYTES = 32 * 1024;
    private static final long CHECKSUM_SEED = 0x243F6A8885A308D3L;
    private static final String ISOLATED_SCOPE =
        "isolated_benchmark_thread_no_renderer_gpu_large_native_or_speedup_claim";
    private static final String LEGACY_SCOPE =
        "legacy_allocation_reference_not_subject_to_zero_gate";

    private static volatile long blackhole;

    private Phase1a3ScratchBenchmark() {
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
        DlssTransformScratch transformScratch = null;
        BudgetedNativeArena queryArena = null;
        ReusableNativeBlockPool stagingPool = null;
        boolean resourcesClosed = false;
        boolean managerClosed = false;

        try {
            transformScratch = DlssTransformScratch.tryCreate(budgets);
            if (transformScratch == null) {
                throw new IllegalStateException(
                    "transform object slab could not be reserved"
                );
            }

            queryArena = BudgetedNativeArena.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                new BudgetedNativeArena.Layout(QUERY_BYTES, 64L)
            );
            if (queryArena == null) {
                throw new IllegalStateException(
                    "four-long native arena could not be reserved"
                );
            }
            MemorySegment querySegment = queryArena.claim(
                QUERY_BYTES,
                Long.BYTES
            );
            if (querySegment == null) {
                throw new IllegalStateException(
                    "four-long native arena claim was rejected"
                );
            }
            LongBuffer queryResults = querySegment
                .asByteBuffer()
                .order(ByteOrder.nativeOrder())
                .asLongBuffer();

            stagingPool = ReusableNativeBlockPool.tryCreate(
                budgets,
                MemoryCategory.STAGING,
                1,
                STAGING_BLOCK_BYTES
            );
            if (stagingPool == null) {
                throw new IllegalStateException(
                    "native staging block pool could not be reserved"
                );
            }
            requireCleanSetup(budgets.snapshot());

            Matrix4f projectionInput = new Matrix4f();
            Matrix4f viewRotationInput = new Matrix4f();
            Quaternionf orientationInput = new Quaternionf();

            transformScratch.clearDeviceState();
            long transformWarmupChecksum = runTransformScratch(
                transformScratch,
                projectionInput,
                viewRotationInput,
                orientationInput,
                TRANSFORM_WARMUP_ITERATIONS
            );
            long legacyWarmupChecksum = runLegacyTransformReference(
                projectionInput,
                viewRotationInput,
                orientationInput,
                TRANSFORM_WARMUP_ITERATIONS
            );
            requireEquivalentChecksum(
                "transform warm-up",
                transformWarmupChecksum,
                legacyWarmupChecksum
            );
            publish(transformWarmupChecksum);
            publish(legacyWarmupChecksum);

            long arenaWarmupChecksum = runNativeArenaReuse(
                queryResults,
                NATIVE_WARMUP_ITERATIONS
            );
            long poolWarmupChecksum = runNativePoolReuse(
                stagingPool,
                NATIVE_WARMUP_ITERATIONS
            );
            requireEquivalentChecksum(
                "native warm-up",
                arenaWarmupChecksum,
                poolWarmupChecksum
            );
            publish(arenaWarmupChecksum);
            publish(poolWarmupChecksum);

            Samples transformSamples = measureTransformScratch(
                transformScratch,
                projectionInput,
                viewRotationInput,
                orientationInput,
                allocationBean,
                threadId
            );
            Samples legacySamples = measureLegacyTransformReference(
                projectionInput,
                viewRotationInput,
                orientationInput,
                allocationBean,
                threadId
            );
            Samples arenaSamples = measureNativeArenaReuse(
                queryResults,
                allocationBean,
                threadId
            );
            Samples poolSamples = measureNativePoolReuse(
                stagingPool,
                allocationBean,
                threadId
            );

            requireStableChecksums(
                "budgeted_transform_scratch",
                transformSamples
            );
            requireStableChecksums(
                "legacy_transform_allocation_reference",
                legacySamples
            );
            requireStableChecksums(
                "budgeted_native_arena_4_long_reuse",
                arenaSamples
            );
            requireStableChecksums(
                "reusable_native_block_pool_borrow_buffer_release",
                poolSamples
            );
            requireEquivalentSamples(
                "transform scratch and legacy allocation reference",
                transformSamples,
                legacySamples
            );
            requireEquivalentSamples(
                "native arena view and native block pool",
                arenaSamples,
                poolSamples
            );
            requireZeroMedianAllocation(
                "budgeted_transform_scratch",
                transformSamples
            );
            requireZeroMedianAllocation(
                "budgeted_native_arena_4_long_reuse",
                arenaSamples
            );
            requireZeroMedianAllocation(
                "reusable_native_block_pool_borrow_buffer_release",
                poolSamples
            );
            if (stagingPool.outstandingBorrows() != 0) {
                throw new IllegalStateException(
                    "native staging pool retained a measured borrow"
                );
            }

            closeOwnedResources(
                transformScratch,
                queryArena,
                stagingPool
            );
            resourcesClosed = true;
            MemoryBudgetManager.Snapshot releasedSnapshot =
                budgets.snapshot();
            requireCleanResourceClose(releasedSnapshot);

            budgets.close();
            managerClosed = true;
            MemoryBudgetManager.Snapshot closedSnapshot =
                budgets.snapshot();
            requireCleanManagerClose(closedSnapshot);

            String csv = createCsv(
                transformSamples,
                legacySamples,
                arenaSamples,
                poolSamples,
                closedSnapshot
            );
            Path output = Path.of(arguments[0])
                .toAbsolutePath()
                .normalize();
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, csv, StandardCharsets.UTF_8);
            System.out.print(csv);
            printMedian("budgeted_transform_scratch", transformSamples);
            printMedian(
                "legacy_transform_allocation_reference",
                legacySamples
            );
            printMedian(
                "budgeted_native_arena_4_long_reuse",
                arenaSamples
            );
            printMedian(
                "reusable_native_block_pool_borrow_buffer_release",
                poolSamples
            );
            System.out.println(
                "Scope: isolated benchmark-thread operations only; "
                    + "no renderer, GPU, large-native-dataset or speedup "
                    + "claim."
            );
            System.out.println(
                "Blackhole: " + Long.toUnsignedString(blackhole)
            );
            System.out.println("CSV: " + output);
        } finally {
            if (!resourcesClosed) {
                closeOwnedResources(
                    transformScratch,
                    queryArena,
                    stagingPool
                );
            }
            if (!managerClosed) {
                budgets.close();
            }
        }
    }

    private static Samples measureTransformScratch(
        DlssTransformScratch scratch,
        Matrix4f projectionInput,
        Matrix4f viewRotationInput,
        Quaternionf orientationInput,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        Samples samples = new Samples(TRANSFORM_MEASURED_ITERATIONS);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            scratch.clearDeviceState();
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long started = System.nanoTime();
            long checksum = runTransformScratch(
                scratch,
                projectionInput,
                viewRotationInput,
                orientationInput,
                TRANSFORM_MEASURED_ITERATIONS
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

    private static Samples measureLegacyTransformReference(
        Matrix4f projectionInput,
        Matrix4f viewRotationInput,
        Quaternionf orientationInput,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        Samples samples = new Samples(TRANSFORM_MEASURED_ITERATIONS);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long started = System.nanoTime();
            long checksum = runLegacyTransformReference(
                projectionInput,
                viewRotationInput,
                orientationInput,
                TRANSFORM_MEASURED_ITERATIONS
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

    private static Samples measureNativeArenaReuse(
        LongBuffer queryResults,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        Samples samples = new Samples(NATIVE_MEASURED_ITERATIONS);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long started = System.nanoTime();
            long checksum = runNativeArenaReuse(
                queryResults,
                NATIVE_MEASURED_ITERATIONS
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

    private static Samples measureNativePoolReuse(
        ReusableNativeBlockPool pool,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId
    ) {
        Samples samples = new Samples(NATIVE_MEASURED_ITERATIONS);
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long allocatedBefore = allocatedBytes(
                allocationBean,
                threadId
            );
            long started = System.nanoTime();
            long checksum = runNativePoolReuse(
                pool,
                NATIVE_MEASURED_ITERATIONS
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

    private static long runTransformScratch(
        DlssTransformScratch scratch,
        Matrix4f projectionInput,
        Matrix4f viewRotationInput,
        Quaternionf orientationInput,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int frame = 0; frame < iterations; frame++) {
            configureTransformInputs(
                frame,
                projectionInput,
                viewRotationInput,
                orientationInput
            );
            scratch.beginFrame();
            scratch.captureUnjitteredProjection(projectionInput);
            Matrix4f overlay = scratch.copyProjectionForOverlay();
            float orientationDot = scratch.hasPreviousOrientation()
                ? scratch.previousOrientationDot(orientationInput)
                : 1.0F;
            scratch.rememberOrientation(orientationInput);
            boolean reset = scratch.prepareCurrentTransforms(
                projectionInput,
                viewRotationInput,
                orientationInput,
                cameraX(frame),
                cameraY(frame),
                cameraZ(frame),
                resetRequested(frame)
            );
            if (reset != scratch.effectiveReset()) {
                throw new IllegalStateException(
                    "transform scratch reset state diverged"
                );
            }
            checksum = consumeTransform(
                checksum,
                frame,
                overlay,
                scratch.projection(),
                scratch.viewProjection(),
                scratch.previousViewProjectionForFrame(),
                scratch.inverseViewProjection(),
                scratch.clipToPrevious(),
                scratch.previousToClip(),
                scratch.inverseProjection(),
                scratch.up(),
                scratch.right(),
                scratch.forward(),
                orientationDot,
                reset
            );
            scratch.commitPreviousViewProjection();
        }
        return checksum;
    }

    private static long runLegacyTransformReference(
        Matrix4f projectionInput,
        Matrix4f viewRotationInput,
        Quaternionf orientationInput,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        Matrix4f previousViewProjection = null;
        Matrix4f previousProjection = null;
        Matrix4f previousViewRotation = null;
        double previousCameraX = 0.0D;
        double previousCameraY = 0.0D;
        double previousCameraZ = 0.0D;
        Quaternionf previousOrientation = null;
        for (int frame = 0; frame < iterations; frame++) {
            configureTransformInputs(
                frame,
                projectionInput,
                viewRotationInput,
                orientationInput
            );
            Matrix4f unjitteredProjection =
                new Matrix4f(projectionInput);
            Matrix4f overlay =
                new Matrix4f(unjitteredProjection);
            float orientationDot = previousOrientation == null
                ? 1.0F
                : previousOrientation.dot(orientationInput);
            previousOrientation = new Quaternionf(orientationInput);

            Matrix4f projection =
                new Matrix4f(unjitteredProjection);
            Matrix4f viewProjection = new Matrix4f(projection)
                .mul(viewRotationInput)
                .translate(
                    (float)-cameraX(frame),
                    (float)-cameraY(frame),
                    (float)-cameraZ(frame)
                );
            boolean reset =
                previousViewProjection == null || resetRequested(frame);
            Matrix4f previousForFrame =
                previousViewProjection == null
                    ? new Matrix4f(viewProjection)
                    : new Matrix4f(previousViewProjection);
            Matrix4f inverseViewProjection =
                new Matrix4f(viewProjection).invert();
            boolean hasPreviousTransform = previousProjection != null;
            Matrix4f previousProjectionForFrame = hasPreviousTransform
                ? new Matrix4f(previousProjection)
                : new Matrix4f(projection);
            Matrix4f previousViewRotationForFrame = hasPreviousTransform
                ? new Matrix4f(previousViewRotation)
                : new Matrix4f(viewRotationInput);
            double currentCameraX = cameraX(frame);
            double currentCameraY = cameraY(frame);
            double currentCameraZ = cameraZ(frame);
            double previousCameraXForFrame = hasPreviousTransform
                ? previousCameraX
                : currentCameraX;
            double previousCameraYForFrame = hasPreviousTransform
                ? previousCameraY
                : currentCameraY;
            double previousCameraZForFrame = hasPreviousTransform
                ? previousCameraZ
                : currentCameraZ;
            Matrix4f currentClipToWorldRotation =
                new Matrix4f(projection)
                    .mul(viewRotationInput)
                    .invert();
            Matrix4f clipToPrevious =
                new Matrix4f(previousProjectionForFrame)
                    .mul(previousViewRotationForFrame)
                    .translate(
                        (float)(currentCameraX - previousCameraXForFrame),
                        (float)(currentCameraY - previousCameraYForFrame),
                        (float)(currentCameraZ - previousCameraZForFrame)
                    )
                    .mul(currentClipToWorldRotation);
            Matrix4f previousToClip =
                new Matrix4f(clipToPrevious).invert();
            Matrix4f inverseProjection =
                new Matrix4f(projection).invert();
            Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F)
                .rotate(orientationInput);
            Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F)
                .rotate(orientationInput);
            Vector3f forward = new Vector3f(0.0F, 0.0F, -1.0F)
                .rotate(orientationInput);

            checksum = consumeTransform(
                checksum,
                frame,
                overlay,
                projection,
                viewProjection,
                previousForFrame,
                inverseViewProjection,
                clipToPrevious,
                previousToClip,
                inverseProjection,
                up,
                right,
                forward,
                orientationDot,
                reset
            );
            previousViewProjection = viewProjection;
            previousProjection = projection;
            previousViewRotation = new Matrix4f(viewRotationInput);
            previousCameraX = currentCameraX;
            previousCameraY = currentCameraY;
            previousCameraZ = currentCameraZ;
        }
        return checksum;
    }

    private static long runNativeArenaReuse(
        LongBuffer queryResults,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int iteration = 0; iteration < iterations; iteration++) {
            long value = nativeValue(iteration);
            queryResults.put(0, value);
            queryResults.put(1, value ^ 0x9E3779B97F4A7C15L);
            queryResults.put(2, Long.rotateLeft(value, 17));
            queryResults.put(3, ~value);
            checksum = consumeNativeLongs(queryResults, checksum);
        }
        return checksum;
    }

    private static long runNativePoolReuse(
        ReusableNativeBlockPool pool,
        int iterations
    ) {
        long checksum = CHECKSUM_SEED;
        for (int iteration = 0; iteration < iterations; iteration++) {
            long token = pool.tryBorrow(QUERY_BYTES);
            if (token == 0L) {
                throw new IllegalStateException(
                    "native staging pool unexpectedly exhausted"
                );
            }
            try {
                ByteBuffer buffer = pool.buffer(token, QUERY_BYTES);
                long value = nativeValue(iteration);
                buffer.putLong(0, value);
                buffer.putLong(
                    Long.BYTES,
                    value ^ 0x9E3779B97F4A7C15L
                );
                buffer.putLong(
                    2 * Long.BYTES,
                    Long.rotateLeft(value, 17)
                );
                buffer.putLong(3 * Long.BYTES, ~value);
                checksum = consumeNativeLongs(buffer, checksum);
            } finally {
                pool.release(token);
            }
        }
        return checksum;
    }

    private static void configureTransformInputs(
        int frame,
        Matrix4f projection,
        Matrix4f viewRotation,
        Quaternionf orientation
    ) {
        float phase = (frame & 1023) * 0.0001F;
        float pitch = phase * 0.37F - 0.018F;
        float yaw = phase * -0.53F + 0.026F;
        float roll = phase * 0.19F - 0.009F;
        float fov = 0.82F + (frame & 31) * 0.00075F;
        projection
            .identity()
            .perspective(
                fov,
                16.0F / 9.0F,
                0.05F,
                1024.0F,
                true
            );
        viewRotation.identity().rotateXYZ(pitch, yaw, roll);
        orientation.identity().rotateXYZ(-pitch, yaw, -roll);
    }

    private static double cameraX(int frame) {
        return frame * 0.03125D;
    }

    private static double cameraY(int frame) {
        return (frame & 255) * 0.015625D - 2.0D;
    }

    private static double cameraZ(int frame) {
        return -frame * 0.0234375D;
    }

    private static boolean resetRequested(int frame) {
        return frame % 997 == 0;
    }

    private static long consumeTransform(
        long checksum,
        int frame,
        Matrix4f overlay,
        Matrix4f projection,
        Matrix4f viewProjection,
        Matrix4f previousViewProjection,
        Matrix4f inverseViewProjection,
        Matrix4f clipToPrevious,
        Matrix4f previousToClip,
        Matrix4f inverseProjection,
        Vector3f up,
        Vector3f right,
        Vector3f forward,
        float orientationDot,
        boolean reset
    ) {
        long result = mixLong(checksum, frame);
        result = mixMatrix(result, overlay);
        result = mixMatrix(result, projection);
        result = mixMatrix(result, viewProjection);
        result = mixMatrix(result, previousViewProjection);
        result = mixMatrix(result, inverseViewProjection);
        result = mixMatrix(result, clipToPrevious);
        result = mixMatrix(result, previousToClip);
        result = mixMatrix(result, inverseProjection);
        result = mixVector(result, up);
        result = mixVector(result, right);
        result = mixVector(result, forward);
        result = mixFloat(result, orientationDot);
        return mixLong(result, reset ? 1L : 0L);
    }

    private static long mixMatrix(long checksum, Matrix4f matrix) {
        long result = checksum;
        result = mixFloat(result, matrix.m00());
        result = mixFloat(result, matrix.m01());
        result = mixFloat(result, matrix.m02());
        result = mixFloat(result, matrix.m03());
        result = mixFloat(result, matrix.m10());
        result = mixFloat(result, matrix.m11());
        result = mixFloat(result, matrix.m12());
        result = mixFloat(result, matrix.m13());
        result = mixFloat(result, matrix.m20());
        result = mixFloat(result, matrix.m21());
        result = mixFloat(result, matrix.m22());
        result = mixFloat(result, matrix.m23());
        result = mixFloat(result, matrix.m30());
        result = mixFloat(result, matrix.m31());
        result = mixFloat(result, matrix.m32());
        return mixFloat(result, matrix.m33());
    }

    private static long mixVector(long checksum, Vector3f vector) {
        long result = mixFloat(checksum, vector.x);
        result = mixFloat(result, vector.y);
        return mixFloat(result, vector.z);
    }

    private static long mixFloat(long checksum, float value) {
        return mixLong(
            checksum,
            Integer.toUnsignedLong(Float.floatToRawIntBits(value))
        );
    }

    private static long consumeNativeLongs(
        LongBuffer buffer,
        long checksum
    ) {
        long result = checksum;
        for (int index = 0; index < QUERY_LONGS; index++) {
            result = mixLong(result, buffer.get(index));
        }
        return result;
    }

    private static long consumeNativeLongs(
        ByteBuffer buffer,
        long checksum
    ) {
        long result = checksum;
        for (int index = 0; index < QUERY_LONGS; index++) {
            result = mixLong(
                result,
                buffer.getLong(index * Long.BYTES)
            );
        }
        return result;
    }

    private static long nativeValue(int iteration) {
        return CHECKSUM_SEED
            + iteration * 0xD1342543DE82EF95L;
    }

    private static long mixLong(long checksum, long value) {
        long mixed = Long.rotateLeft(
            checksum ^ value ^ 0x9E3779B97F4A7C15L,
            21
        );
        return mixed * 0x94D049BB133111EBL;
    }

    private static String createCsv(
        Samples transform,
        Samples legacy,
        Samples arena,
        Samples pool,
        MemoryBudgetManager.Snapshot snapshot
    ) {
        StringBuilder csv = new StringBuilder(4096);
        csv.append(
            "operation,sample,iterations,total_nanos,"
                + "nanos_per_iteration,allocated_bytes,"
                + "allocated_bytes_per_iteration,checksum,"
                + "budget_rejections,budget_outstanding,budget_leaks,"
                + "budget_closed,scope\n"
        );
        appendSamples(
            csv,
            "budgeted_transform_scratch",
            transform,
            snapshot,
            ISOLATED_SCOPE
        );
        appendSamples(
            csv,
            "legacy_transform_allocation_reference",
            legacy,
            snapshot,
            LEGACY_SCOPE
        );
        appendSamples(
            csv,
            "budgeted_native_arena_4_long_reuse",
            arena,
            snapshot,
            ISOLATED_SCOPE
        );
        appendSamples(
            csv,
            "reusable_native_block_pool_borrow_buffer_release",
            pool,
            snapshot,
            ISOLATED_SCOPE
        );
        return csv.toString();
    }

    private static void appendSamples(
        StringBuilder csv,
        String operation,
        Samples samples,
        MemoryBudgetManager.Snapshot snapshot,
        String scope
    ) {
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            appendRow(
                csv,
                operation,
                Integer.toString(sample + 1),
                samples.iterations(),
                samples.elapsedNanos(sample),
                samples.allocatedBytes(sample),
                samples.checksum(sample),
                snapshot,
                scope
            );
        }
        appendRow(
            csv,
            operation,
            "median",
            samples.iterations(),
            samples.medianElapsedNanos(),
            samples.medianAllocatedBytes(),
            samples.aggregateChecksum(),
            snapshot,
            scope
        );
    }

    private static void appendRow(
        StringBuilder csv,
        String operation,
        String sample,
        int iterations,
        long elapsedNanos,
        long allocatedBytes,
        long checksum,
        MemoryBudgetManager.Snapshot snapshot,
        String scope
    ) {
        csv.append(operation).append(',');
        csv.append(sample).append(',');
        csv.append(iterations).append(',');
        csv.append(elapsedNanos).append(',');
        csv.append(elapsedNanos / (double)iterations).append(',');
        csv.append(allocatedBytes).append(',');
        csv.append(allocatedBytes / (double)iterations).append(',');
        csv.append(Long.toUnsignedString(checksum)).append(',');
        csv.append(snapshot.rejections()).append(',');
        csv.append(snapshot.outstanding()).append(',');
        csv.append(snapshot.leaks()).append(',');
        csv.append(snapshot.closed()).append(',');
        csv.append(scope).append('\n');
    }

    private static void printMedian(
        String operation,
        Samples samples
    ) {
        System.out.printf(
            Locale.ROOT,
            "%s median: %.3f ns/iteration, "
                + "%.3f allocated B/iteration, checksum=%s%n",
            operation,
            samples.medianElapsedNanos()
                / (double)samples.iterations(),
            samples.medianAllocatedBytes()
                / (double)samples.iterations(),
            Long.toUnsignedString(samples.aggregateChecksum())
        );
    }

    private static void requireEquivalentSamples(
        String name,
        Samples left,
        Samples right
    ) {
        if (left.iterations() != right.iterations()) {
            throw new IllegalStateException(
                name + " used different iteration counts"
            );
        }
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            requireEquivalentChecksum(
                name + " sample " + (sample + 1),
                left.checksum(sample),
                right.checksum(sample)
            );
        }
    }

    private static void requireEquivalentChecksum(
        String name,
        long left,
        long right
    ) {
        if (left != right) {
            throw new IllegalStateException(
                name
                    + " checksum mismatch: "
                    + Long.toUnsignedString(left)
                    + " != "
                    + Long.toUnsignedString(right)
            );
        }
    }

    private static void requireStableChecksums(
        String operation,
        Samples samples
    ) {
        long expected = samples.checksum(0);
        for (int sample = 1; sample < SAMPLE_COUNT; sample++) {
            if (samples.checksum(sample) != expected) {
                throw new IllegalStateException(
                    operation
                        + " checksum changed in sample "
                        + (sample + 1)
                );
            }
        }
    }

    private static void requireZeroMedianAllocation(
        String operation,
        Samples samples
    ) {
        long median = samples.medianAllocatedBytes();
        if (median != 0L) {
            throw new IllegalStateException(
                operation
                    + " median allocated "
                    + median
                    + " bytes; expected zero only for this isolated "
                    + "reuse operation on the benchmark thread"
            );
        }
    }

    private static void requireCleanSetup(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            snapshot.outstanding() != 3
                || snapshot.rejections() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "phase 1A.3 benchmark setup accounting was unexpected: "
                    + "outstanding="
                    + snapshot.outstanding()
                    + ", rejections="
                    + snapshot.rejections()
                    + ", leaks="
                    + snapshot.leaks()
            );
        }
    }

    private static void requireCleanResourceClose(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            snapshot.outstanding() != 0
                || snapshot.rejections() != 0L
                || snapshot.evictions() != 0L
                || snapshot.reclaimedBytes() != 0L
                || snapshot.deniedInFlightReleases() != 0L
                || snapshot.staleReleases() != 0L
                || snapshot.leaks() != 0L
                || snapshot.usedBytes(MemoryKind.RAM) != 0L
                || snapshot.requestedBytes(MemoryKind.RAM) != 0L
        ) {
            throw new IllegalStateException(
                "phase 1A.3 resources did not close cleanly before "
                    + "the budget manager: "
                    + snapshot
                    + ", rejections="
                    + snapshot.rejections()
                    + ", leaks="
                    + snapshot.leaks()
            );
        }
    }

    private static void requireCleanManagerClose(
        MemoryBudgetManager.Snapshot snapshot
    ) {
        if (
            !snapshot.closed()
                || snapshot.outstanding() != 0
                || snapshot.rejections() != 0L
                || snapshot.leaks() != 0L
        ) {
            throw new IllegalStateException(
                "phase 1A.3 budget manager close was not clean: "
                    + "closed="
                    + snapshot.closed()
                    + ", outstanding="
                    + snapshot.outstanding()
                    + ", rejections="
                    + snapshot.rejections()
                    + ", leaks="
                    + snapshot.leaks()
            );
        }
    }

    private static void closeOwnedResources(
        DlssTransformScratch transformScratch,
        BudgetedNativeArena queryArena,
        ReusableNativeBlockPool stagingPool
    ) {
        Throwable failure = null;
        if (transformScratch != null) {
            try {
                transformScratch.close();
            } catch (RuntimeException | Error closeFailure) {
                failure = closeFailure;
            }
        }
        if (queryArena != null) {
            try {
                queryArena.close();
            } catch (RuntimeException | Error closeFailure) {
                failure = retainFailure(failure, closeFailure);
            }
        }
        if (stagingPool != null) {
            try {
                stagingPool.close();
            } catch (RuntimeException | Error closeFailure) {
                failure = retainFailure(failure, closeFailure);
            }
        }
        try {
            DlssTransformScratch.closeRetainedFailedCreation();
        } catch (RuntimeException | Error closeFailure) {
            failure = retainFailure(failure, closeFailure);
        }
        try {
            ReusableNativeBlockPool.retryPendingCleanup();
        } catch (RuntimeException | Error closeFailure) {
            failure = retainFailure(failure, closeFailure);
        }
        try {
            BudgetedNativeArena.retryPendingCleanup();
        } catch (RuntimeException | Error closeFailure) {
            failure = retainFailure(failure, closeFailure);
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable retainFailure(
        Throwable retained,
        Throwable added
    ) {
        if (retained == null) {
            return added;
        }
        if (retained != added) {
            retained.addSuppressed(added);
        }
        return retained;
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
        blackhole = mixLong(blackhole, checksum);
    }

    private static final class Samples {
        private final int iterations;
        private final long[] elapsedNanos = new long[SAMPLE_COUNT];
        private final long[] allocatedBytes = new long[SAMPLE_COUNT];
        private final long[] checksums = new long[SAMPLE_COUNT];

        private Samples(int iterations) {
            this.iterations = iterations;
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

        private int iterations() {
            return this.iterations;
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
}
