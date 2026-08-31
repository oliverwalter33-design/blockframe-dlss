package de.morau.blockframe.render.terrain.gpuscene;

import java.util.Arrays;

/**
 * Fixed-storage CPU attribution for the already experimental GPU-scene path.
 *
 * <p>The owner creates this only when the process property
 * {@code blockframe.experimental.gpuSceneAudit=true} is present. Normal
 * production and the default-disabled GPU scene allocate nothing here.</p>
 */
final class OpaqueSolidGpuSceneAuditWindow {
    static final String ENABLE_PROPERTY =
        "blockframe.experimental.gpuSceneAudit";
    static final int MAX_SAMPLES = 65_536;

    static final int PREPARE_TOTAL = 0;
    static final int VISIBLE_AND_LAYER_SCAN = 1;
    static final int STAGING_AND_VISIBILITY_UPLOAD = 2;
    static final int COMPUTE_AND_BARRIERS = 3;
    static final int INDIRECT_CPU_SUBMISSION = 4;
    static final int MOJANG_RESIDUAL_ENCODING = 5;
    static final int LIFECYCLE_AND_RETIREMENT = 6;
    static final int GENERATION_TOKEN_PREFLIGHT = 7;
    static final int STAGE_COUNT = 8;

    private static final String[] STAGE_NAMES = {
        "prepareTotal",
        "visibleAndLayerScan",
        "stagingAndVisibilityUpload",
        "computeAndBarriers",
        "indirectCpuSubmission",
        "mojangResidualEncoding",
        "lifecycleAndRetirement",
        "generationTokenPreflight"
    };

    private final long[][] samples =
        new long[STAGE_COUNT][MAX_SAMPLES];
    private final long[] current = new long[STAGE_COUNT];
    private final long allocatedBytes =
        (long) STAGE_COUNT * MAX_SAMPLES * Long.BYTES
            + (long) STAGE_COUNT * Long.BYTES;

    private int sampleCount;
    private boolean overflow;
    private boolean frameOpen;
    private long uploadBytes;
    private long barriers;
    private long visibleRecords;
    private long eligibleRecords;
    private long pendingLifecycleNanos;

    void beginFrame() {
        if (frameOpen) {
            overflow = true;
        }
        Arrays.fill(current, 0L);
        current[LIFECYCLE_AND_RETIREMENT] =
            pendingLifecycleNanos;
        pendingLifecycleNanos = 0L;
        frameOpen = true;
    }

    void record(int stage, long nanos) {
        if (
            stage < 0
                || stage >= STAGE_COUNT
                || nanos < 0L
        ) {
            overflow = true;
            return;
        }
        if (!frameOpen) {
            if (stage == LIFECYCLE_AND_RETIREMENT) {
                pendingLifecycleNanos = saturatedAdd(
                    pendingLifecycleNanos,
                    nanos
                );
                return;
            }
            overflow = true;
            return;
        }
        current[stage] = saturatedAdd(current[stage], nanos);
    }

    void recordUploadBytes(long bytes) {
        if (bytes < 0L) {
            overflow = true;
            return;
        }
        uploadBytes = saturatedAdd(uploadBytes, bytes);
    }

    void recordBarriers(long count) {
        if (count < 0L) {
            overflow = true;
            return;
        }
        barriers = saturatedAdd(barriers, count);
    }

    void finishFrame(long visible, long eligible) {
        if (!frameOpen || visible < 0L || eligible < 0L) {
            overflow = true;
            return;
        }
        if (sampleCount >= MAX_SAMPLES) {
            overflow = true;
            frameOpen = false;
            return;
        }
        for (int stage = 0; stage < STAGE_COUNT; stage++) {
            samples[stage][sampleCount] = current[stage];
        }
        sampleCount++;
        visibleRecords = saturatedAdd(visibleRecords, visible);
        eligibleRecords = saturatedAdd(eligibleRecords, eligible);
        frameOpen = false;
    }

    void cancelFrame() {
        frameOpen = false;
    }

    String summary() {
        StringBuilder result = new StringBuilder(1_024);
        result.append("enabled=true frames=")
            .append(sampleCount)
            .append(" overflow=")
            .append(overflow)
            .append(" allocatedBytes=")
            .append(allocatedBytes)
            .append(" uploadBytes=")
            .append(uploadBytes)
            .append(" barriers=")
            .append(barriers)
            .append(" visibleRecords=")
            .append(visibleRecords)
            .append(" eligibleRecords=")
            .append(eligibleRecords)
            .append(" pendingLifecycleNanos=")
            .append(pendingLifecycleNanos)
            .append(" tokenValidationStatus=")
            .append("AVAILABLE_AGGREGATE_BUCKET_PREFLIGHT")
            .append(" gpuTimestampStatus=")
            .append(
                "NOT_AVAILABLE_NO_SAFE_GENERATION_BOUND_QUERY_POOL"
            );
        for (int stage = 0; stage < STAGE_COUNT; stage++) {
            appendDistribution(
                result,
                STAGE_NAMES[stage],
                samples[stage],
                sampleCount
            );
        }
        return result.toString();
    }

    int sampleCount() {
        return sampleCount;
    }

    boolean overflow() {
        return overflow;
    }

    long allocatedBytes() {
        return allocatedBytes;
    }

    private static void appendDistribution(
        StringBuilder output,
        String name,
        long[] source,
        int count
    ) {
        output.append(' ').append(name).append("Status=");
        if (count == 0) {
            output.append("NOT_AVAILABLE");
            return;
        }
        long[] values = Arrays.copyOf(source, count);
        Arrays.sort(values);
        output.append("AVAILABLE")
            .append(' ')
            .append(name)
            .append("P50=")
            .append(percentile(values, 0.50))
            .append(' ')
            .append(name)
            .append("P95=")
            .append(percentile(values, 0.95))
            .append(' ')
            .append(name)
            .append("P99=")
            .append(percentile(values, 0.99));
    }

    private static long percentile(long[] values, double percentile) {
        int index = (int) Math.ceil(percentile * values.length) - 1;
        return values[Math.max(0, Math.min(index, values.length - 1))];
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}
