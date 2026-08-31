package de.morau.blockframe.core.scheduling;

import java.util.Objects;

/**
 * The single central admission controller for recurring BlockFrame work.
 *
 * <p>Decisions change only concurrency and publication rate. They never
 * reduce terrain content, model fidelity, view distance or render quality.</p>
 */
public final class FrameBudgetController {
    public record Inputs(
        long cpuFrameNanos,
        long gpuFrameNanos,
        long frameP95Nanos,
        long frameP99Nanos,
        int snapshotBacklog,
        int compileBacklog,
        int uploadBacklog,
        long availableRamBytes,
        long availableVramBytes,
        long averageSnapshotNanos,
        long averageCompileNanos,
        long averageUploadNanos
    ) {
        public Inputs {
            requireNonNegative(cpuFrameNanos, "cpuFrameNanos");
            requireNonNegative(gpuFrameNanos, "gpuFrameNanos");
            requireNonNegative(frameP95Nanos, "frameP95Nanos");
            requireNonNegative(frameP99Nanos, "frameP99Nanos");
            requireNonNegative(snapshotBacklog, "snapshotBacklog");
            requireNonNegative(compileBacklog, "compileBacklog");
            requireNonNegative(uploadBacklog, "uploadBacklog");
            requireNonNegative(availableRamBytes, "availableRamBytes");
            requireNonNegative(availableVramBytes, "availableVramBytes");
            requireNonNegative(
                averageSnapshotNanos,
                "averageSnapshotNanos"
            );
            requireNonNegative(
                averageCompileNanos,
                "averageCompileNanos"
            );
            requireNonNegative(
                averageUploadNanos,
                "averageUploadNanos"
            );
        }

        public int totalBacklog() {
            return Math.addExact(
                this.snapshotBacklog,
                Math.addExact(
                    this.compileBacklog,
                    this.uploadBacklog
                )
            );
        }
    }

    public record Decision(
        int activeCompilerWorkers,
        int snapshotsToStart,
        long uploadBytesToRecord,
        int publicationsToComplete,
        boolean smtWorkersAllowed,
        String pressureReason
    ) {
        public Decision {
            if (
                activeCompilerWorkers < 0
                    || snapshotsToStart < 0
                    || uploadBytesToRecord < 0L
                    || publicationsToComplete < 0
            ) {
                throw new IllegalArgumentException(
                    "negative frame-budget decision"
                );
            }
            pressureReason = Objects.requireNonNull(
                pressureReason,
                "pressureReason"
            );
        }
    }

    private final long targetFrameNanos;
    private final int physicalWorkerLimit;
    private final int logicalWorkerLimit;
    private final long normalUploadBytes;
    private final long lowMemoryThresholdBytes;

    public FrameBudgetController(
        long targetFrameNanos,
        int physicalWorkerLimit,
        int logicalWorkerLimit,
        long normalUploadBytes,
        long lowMemoryThresholdBytes
    ) {
        if (
            targetFrameNanos <= 0L
                || physicalWorkerLimit <= 0
                || logicalWorkerLimit < physicalWorkerLimit
                || normalUploadBytes <= 0L
                || lowMemoryThresholdBytes <= 0L
        ) {
            throw new IllegalArgumentException(
                "invalid frame-budget controller limits"
            );
        }
        this.targetFrameNanos = targetFrameNanos;
        this.physicalWorkerLimit = physicalWorkerLimit;
        this.logicalWorkerLimit = logicalWorkerLimit;
        this.normalUploadBytes = normalUploadBytes;
        this.lowMemoryThresholdBytes = lowMemoryThresholdBytes;
    }

    public Decision decide(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        boolean memoryPressure =
            inputs.availableRamBytes() < this.lowMemoryThresholdBytes
                || inputs.availableVramBytes()
                    < this.lowMemoryThresholdBytes;
        boolean severeFramePressure =
            inputs.frameP99Nanos() > this.targetFrameNanos * 5L / 4L
                || inputs.cpuFrameNanos()
                    > this.targetFrameNanos * 5L / 4L;
        boolean moderateFramePressure =
            inputs.frameP95Nanos() > this.targetFrameNanos
                || inputs.cpuFrameNanos() > this.targetFrameNanos;
        boolean gpuLimited =
            inputs.gpuFrameNanos() > this.targetFrameNanos
                && inputs.cpuFrameNanos() < this.targetFrameNanos;
        int backlog = inputs.totalBacklog();

        if (memoryPressure) {
            return new Decision(
                0,
                0,
                0L,
                1,
                false,
                "memory-pressure"
            );
        }
        if (severeFramePressure) {
            return new Decision(
                Math.min(1, this.physicalWorkerLimit),
                0,
                Math.max(4096L, this.normalUploadBytes / 8L),
                1,
                false,
                "frame-p99-pressure"
            );
        }
        if (moderateFramePressure) {
            return new Decision(
                Math.min(2, this.physicalWorkerLimit),
                1,
                Math.max(4096L, this.normalUploadBytes / 4L),
                1,
                false,
                "frame-p95-pressure"
            );
        }

        int physicalWorkers = Math.min(
            this.physicalWorkerLimit,
            Math.max(1, Math.min(backlog, this.physicalWorkerLimit))
        );
        boolean allowSmt = backlog > this.physicalWorkerLimit * 2
            && gpuLimited
            && inputs.averageCompileNanos() != 0L;
        int workers = allowSmt
            ? Math.min(this.logicalWorkerLimit, backlog)
            : physicalWorkers;
        int snapshots = Math.min(
            Math.max(1, workers),
            inputs.snapshotBacklog() + 1
        );
        return new Decision(
            workers,
            snapshots,
            this.normalUploadBytes,
            Math.max(1, workers),
            allowSmt,
            allowSmt ? "gpu-slack-backlog" : "normal"
        );
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                name + " must not be negative"
            );
        }
    }
}
