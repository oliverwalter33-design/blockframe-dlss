package de.morau.blockframe.render.terrain.nativeengine;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.CompiledPayloadBatch;
import de.morau.blockframe.render.terrain.nativeengine
    .BlockFrameSectionCompiler.PublishedPayload;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.Cause;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.CleanupDecision;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainSectionLifecycle.RetirementPermit;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.GenerationStamp;
import de.morau.blockframe.render.terrain.nativeengine
    .TerrainMeshProducerABI.SectionIdentity;
import java.util.Objects;
import java.util.Optional;

/**
 * Budgeted owner of one compiler-produced CPU payload generation.
 *
 * <p>This Foundation owner proves atomic publish, retirement, rollback and
 * cleanup without a Minecraft {@code MeshData} or borrowed upload buffer. It
 * deliberately accounts only BlockFrame-owned RAM. It is not a device-local
 * geometry arena and makes no VRAM, Vulkan-upload or submission claim; the
 * next GeometryOwner subphase replaces the transfer side while preserving
 * this publication/lifecycle contract.</p>
 */
public final class NativeTerrainPayloadOwner implements AutoCloseable {
    public enum FailureReason {
        DEFERRED_OR_UNSUPPORTED_CHANNEL,
        PAYLOAD_SIZE_OVERFLOW,
        PAYLOAD_LIMIT_EXCEEDED,
        RAM_BUDGET_REJECTED,
        PUBLICATION_FAILED
    }

    public static final class Publication {
        private final NativeTerrainPayloadOwner owner;
        private final PublishedPayload payload;
        private final NativeTerrainSectionLifecycle lifecycle;
        private final long ramLease;
        private final long ownedBytes;
        private boolean payloadReleased;
        private boolean leaseReleased;
        private volatile boolean retired;

        private Publication(
            NativeTerrainPayloadOwner owner,
            PublishedPayload payload,
            NativeTerrainSectionLifecycle lifecycle,
            long ramLease,
            long ownedBytes
        ) {
            this.owner = owner;
            this.payload = payload;
            this.lifecycle = lifecycle;
            this.ramLease = ramLease;
            this.ownedBytes = ownedBytes;
        }

        public GenerationStamp generations() {
            return this.payload.generations();
        }

        public SectionIdentity section() {
            return this.payload.section();
        }

        public long ownedBytes() {
            return this.ownedBytes;
        }

        public synchronized boolean retired() {
            return this.retired;
        }
    }

    public record PublishResult(
        Publication publication,
        FailureReason failureReason,
        String detail
    ) {
        public PublishResult {
            detail = Objects.requireNonNull(detail, "detail");
            if ((publication == null) == (failureReason == null)) {
                throw new IllegalArgumentException(
                    "publish result must contain exactly one outcome"
                );
            }
        }

        public boolean successful() {
            return this.publication != null;
        }

        public Optional<Publication> publicationOptional() {
            return Optional.ofNullable(this.publication);
        }

        public Optional<FailureReason> failureOptional() {
            return Optional.ofNullable(this.failureReason);
        }
    }

    public record Snapshot(
        boolean closed,
        boolean active,
        long ownedBytes,
        long highWaterBytes,
        long successfulPublications,
        long failedPublications,
        long retirements
    ) {
    }

    private final MemoryBudgetManager budgets;
    private final long maximumPayloadBytes;
    private Publication active;
    private long highWaterBytes;
    private long successfulPublications;
    private long failedPublications;
    private long retirements;
    private boolean closed;

    public NativeTerrainPayloadOwner(
        MemoryBudgetManager budgets,
        long maximumPayloadBytes
    ) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        if (maximumPayloadBytes <= 0L) {
            throw new IllegalArgumentException(
                "maximumPayloadBytes must be positive"
            );
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    /**
     * Atomically transfers compiler ownership after the lifecycle reached
     * COMPILED. No partial channel is exposed on any failure.
     */
    public synchronized PublishResult publish(
        CompiledPayloadBatch batch,
        NativeTerrainSectionLifecycle lifecycle
    ) {
        requireOpen();
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (this.active != null) {
            throw new IllegalStateException(
                "owner already has an active publication"
            );
        }
        if (
            !batch.generations().equals(lifecycle.generations())
                || !batch.section().equals(lifecycle.section())
        ) {
            throw new IllegalArgumentException(
                "batch and lifecycle identity differ"
            );
        }
        if (!batch.fullySubmittable()) {
            lifecycle.quarantine(Cause.BACKEND_FAILURE, 0L);
            return failure(
                FailureReason.DEFERRED_OR_UNSUPPORTED_CHANNEL,
                "a required channel has no native submission lane"
            );
        }

        long ownedBytes;
        try {
            ownedBytes = batch.channels()
                .values()
                .stream()
                .mapToLong(channel -> channel.byteLength())
                .reduce(0L, Math::addExact);
        } catch (ArithmeticException error) {
            lifecycle.cancelBeforePublish(Cause.COMPILE_FAILURE, 0L);
            return failure(
                FailureReason.PAYLOAD_SIZE_OVERFLOW,
                "compiled payload size overflow"
            );
        }
        if (ownedBytes > this.maximumPayloadBytes) {
            lifecycle.rejectBudgetBeforeUpload();
            return failure(
                FailureReason.PAYLOAD_LIMIT_EXCEEDED,
                "compiled payload exceeds the bounded owner limit"
            );
        }

        long lease = 0L;
        if (ownedBytes != 0L) {
            lease = this.budgets.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.TERRAIN,
                ownedBytes
            );
            if (lease == 0L) {
                lifecycle.rejectBudgetBeforeUpload();
                return failure(
                    FailureReason.RAM_BUDGET_REJECTED,
                    "RAM budget rejected the complete payload"
                );
            }
        }

        NativeTerrainSectionLifecycle.UploadPermit uploadPermit =
            lifecycle.beginUpload();
        PublishedPayload payload = null;
        try {
            payload = batch.publish();
            Publication publication = new Publication(
                this,
                payload,
                lifecycle,
                lease,
                ownedBytes
            );
            lifecycle.publish(uploadPermit);
            this.active = publication;
            this.successfulPublications++;
            this.highWaterBytes = Math.max(
                this.highWaterBytes,
                ownedBytes
            );
            return new PublishResult(publication, null, "");
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            if (payload != null) {
                try {
                    payload.retire();
                } catch (RuntimeException | LinkageError ignored) {
                    // The lifecycle remains fail-closed below.
                }
            } else if (
                batch.state()
                    == BlockFrameSectionCompiler.BatchState.COMPILED
            ) {
                try {
                    batch.close();
                } catch (RuntimeException | LinkageError ignored) {
                    // Lifecycle cleanup remains authoritative.
                }
            }
            if (lease != 0L) {
                this.budgets.release(lease);
            }
            CleanupDecision cleanup = lifecycle.failUpload(
                uploadPermit,
                0L
            );
            completeEmptyCleanup(lifecycle, cleanup);
            return failure(
                FailureReason.PUBLICATION_FAILED,
                error.getClass().getSimpleName()
            );
        }
    }

    /**
     * Retires CPU ownership only after the caller's lifecycle retirement
     * permit and completion serial agree.
     */
    public synchronized void retire(
        Publication publication,
        CleanupDecision cleanup,
        long completedSubmissionSerial
    ) {
        requireOpen();
        requireActive(publication);
        Objects.requireNonNull(cleanup, "cleanup");
        RetirementPermit permit = cleanup.retirementPermit();
        if (permit == null) {
            throw new IllegalArgumentException(
                "retirement requires a cleanup permit"
            );
        }
        if (
            completedSubmissionSerial
                < permit.minimumCompletedSubmission()
        ) {
            throw new IllegalStateException(
                "GPU completion proof is too old"
            );
        }

        try {
            if (!publication.payloadReleased) {
                publication.payload.retire();
                publication.payloadReleased = true;
            }
            if (
                !publication.leaseReleased
                    && publication.ramLease != 0L
            ) {
                if (!this.budgets.release(publication.ramLease)) {
                    throw new IllegalStateException(
                        "RAM lease could not be released"
                    );
                }
                publication.leaseReleased = true;
            }
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            publication.lifecycle.cleanupFailed(permit);
            throw error;
        }
        publication.lifecycle.completeRetirement(
            permit,
            completedSubmissionSerial
        );
        publication.retired = true;
        this.active = null;
        this.retirements++;
    }

    /**
     * Resumes only unfinished idempotent cleanup steps after a reported
     * cleanup failure. A fresh lifecycle permit prevents stale retries.
     */
    public synchronized void retryRetirement(
        Publication publication,
        long completedSubmissionSerial
    ) {
        requireOpen();
        requireActive(publication);
        RetirementPermit permit =
            publication.lifecycle.retryCleanup();
        this.retire(
            publication,
            new CleanupDecision(
                NativeTerrainSectionLifecycle.State.RETIRING,
                permit
            ),
            completedSubmissionSerial
        );
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.closed,
            this.active != null,
            this.active == null ? 0L : this.active.ownedBytes,
            this.highWaterBytes,
            this.successfulPublications,
            this.failedPublications,
            this.retirements
        );
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        Publication publication = this.active;
        if (publication != null) {
            CleanupDecision cleanup =
                publication.lifecycle.shutdown(0L);
            this.retire(
                publication,
                cleanup,
                cleanup.retirementPermit()
                    .minimumCompletedSubmission()
            );
            publication.lifecycle.close();
        }
        this.closed = true;
    }

    private PublishResult failure(
        FailureReason reason,
        String detail
    ) {
        this.failedPublications++;
        return new PublishResult(null, reason, detail);
    }

    private static void completeEmptyCleanup(
        NativeTerrainSectionLifecycle lifecycle,
        CleanupDecision cleanup
    ) {
        RetirementPermit permit = cleanup.retirementPermit();
        if (permit != null) {
            lifecycle.completeRetirement(
                permit,
                permit.minimumCompletedSubmission()
            );
        }
    }

    private void requireActive(Publication publication) {
        Objects.requireNonNull(publication, "publication");
        if (
            publication.owner != this
                || this.active != publication
                || publication.retired
        ) {
            throw new IllegalArgumentException(
                "publication is not active in this owner"
            );
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("payload owner is closed");
        }
    }
}
