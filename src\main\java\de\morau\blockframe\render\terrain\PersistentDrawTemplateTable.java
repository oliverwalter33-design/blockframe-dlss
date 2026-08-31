package de.morau.blockframe.render.terrain;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.util.Arrays;
import java.util.Objects;

/**
 * Fixed-capacity, RAM-budgeted ownership table for persistent draw templates.
 *
 * <p>The table owns only its Java arrays and template payloads. Every GPU
 * object stored in a key remains externally owned and is compared by identity;
 * it is never closed here. A single evictable RAM lease accounts the complete
 * fixed-capacity table.</p>
 */
final class PersistentDrawTemplateTable implements AutoCloseable {
    static final int DEFAULT_CAPACITY = 16_384;
    static final long DEFAULT_ACCOUNTED_BYTES = 8L * 1024L * 1024L;

    enum State {
        MOJANG_ONLY,
        CANDIDATE,
        BUILDING,
        READY,
        SUBMITTED,
        DIRTY,
        RETIRED,
        QUARANTINED
    }

    enum Failure {
        NONE,
        POLICY_DISABLED,
        OPENGL,
        WRONG_THREAD,
        BUDGET_REJECTED,
        ALLOCATION_FAILED,
        EVICTABLE_REGISTRATION_FAILED,
        CAPACITY_OVERFLOW,
        PIPELINE_ABI_UNSUPPORTED,
        SECTION_IDENTITY_MISMATCH,
        VERTEX_BUFFER_INVALID,
        VERTEX_LAYOUT_INVALID,
        DRAW_RANGE_INVALID,
        INDEX_BUFFER_INVALID,
        VALIDATION_FAILED,
        LIFECYCLE_INVALIDATION,
        PRE_SUBMISSION_FAILURE,
        POST_SUBMISSION_FAILURE,
        CLEANUP_RETRY
    }

    private static final byte EMPTY = 0;
    private static final byte OCCUPIED = 1;
    private static final byte TOMBSTONE = 2;
    private static final State[] STATES = State.values();

    private final MemoryBudgetManager budgets;
    private final int capacity;
    private final long accountedBytes;
    private final int mask;

    private Thread ownerThread;
    private long lease;
    private byte[] occupancy;
    private byte[] states;
    private long[] sectionNodes;
    private long[] worldGenerations;
    private long[] rendererGenerations;
    private long[] deviceGenerations;
    private long[] reloadEpochs;
    private long[] meshRevisions;
    private long[] vertexOffsets;
    private long[] indexOffsets;
    private long[] lastSeenFrames;
    private int[] sectionX;
    private int[] sectionY;
    private int[] sectionZ;
    private int[] firstIndices;
    private int[] indexCounts;
    private int[] baseVertices;
    private int[] indexTypeKeys;
    private int[] pipelineKeys;
    private int[] descriptorKeys;
    private int[] materialKeys;
    private int[] activeSlots;
    private Object[] meshOwners;
    private Object[] vertexBuffers;
    private Object[] indexBuffers;
    private Object[] pipelineOwners;
    private Object[] vertexFormats;
    private Object[] materialOwners;
    private Object[] templates;

    private long frameId;
    private long currentWorldGeneration;
    private long currentRendererGeneration;
    private long currentDeviceGeneration;
    private long currentReloadEpoch;
    private Object frameMarker;
    private boolean frameActive;
    private boolean submissionStarted;
    private int solidSubmissionCount;
    private int encodedSolidSubmissions;
    private int activeSlotCount;
    private int entryCount;
    private int cleanupFailureCountdown;
    private boolean allocationFailureForTest;

    private long visibleThisFrame;
    private long mojangOnlyThisFrame;
    private long candidateThisFrame;
    private long readyThisFrame;
    private long reusedThisFrame;
    private long rebuiltThisFrame;
    private long dirtyThisFrame;
    private long evictedThisFrame;
    private long retiredThisFrame;
    private long quarantinedThisFrame;
    private long fullRecordBuildsThisFrame;
    private long reusedTemplatesThisFrame;
    private long drawRecordsThisFrame;
    private long uploadedBytesThisFrame;
    private long visibilityNanosThisFrame;
    private long buildNanosThisFrame;
    private long submissionNanosThisFrame;
    private long fallbackFrames;
    private long cleanupRetryCount;
    private long wrongThreadCount;
    private long totalEvictions;
    private Failure lastFailure = Failure.NONE;

    PersistentDrawTemplateTable(MemoryBudgetManager budgets) {
        this(
            budgets,
            DEFAULT_CAPACITY,
            DEFAULT_ACCOUNTED_BYTES
        );
    }

    PersistentDrawTemplateTable(
        MemoryBudgetManager budgets,
        int capacity,
        long accountedBytes
    ) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        if (
            capacity < 2
                || Integer.bitCount(capacity) != 1
                || accountedBytes <= 0L
        ) {
            throw new IllegalArgumentException(
                "capacity must be a power of two and bytes must be positive"
            );
        }
        this.capacity = capacity;
        this.accountedBytes = accountedBytes;
        this.mask = capacity - 1;
    }

    synchronized boolean beginFrame(
        Thread renderThread,
        long worldGeneration,
        long rendererGeneration,
        long deviceGeneration,
        long reloadEpoch
    ) {
        Objects.requireNonNull(renderThread, "renderThread");
        if (this.ownerThread == null) {
            this.ownerThread = renderThread;
        } else if (
            this.ownerThread != renderThread
                || Thread.currentThread() != renderThread
        ) {
            this.wrongThreadCount++;
            this.fail(Failure.WRONG_THREAD);
            return false;
        }
        if (this.frameActive) {
            this.quarantineCurrentFrame();
            this.finishFrameState();
            this.fail(Failure.PRE_SUBMISSION_FAILURE);
            return false;
        }
        if (!this.ensureStorage()) {
            return false;
        }

        this.resetFrameCounters();
        if (
            this.currentWorldGeneration != worldGeneration
                || this.currentRendererGeneration != rendererGeneration
                || this.currentDeviceGeneration != deviceGeneration
                || this.currentReloadEpoch != reloadEpoch
        ) {
            this.retiredThisFrame += this.entryCount;
            this.clearEntries(State.RETIRED);
            this.currentWorldGeneration = worldGeneration;
            this.currentRendererGeneration = rendererGeneration;
            this.currentDeviceGeneration = deviceGeneration;
            this.currentReloadEpoch = reloadEpoch;
        }

        this.frameId = incrementSaturated(this.frameId);
        this.frameMarker = null;
        this.frameActive = true;
        this.submissionStarted = false;
        this.solidSubmissionCount = 0;
        this.encodedSolidSubmissions = 0;
        this.lastFailure = Failure.NONE;
        return true;
    }

    synchronized void publishFrame(
        Object marker,
        int solidSubmissionCount,
        long visibilityNanos,
        long buildNanos,
        long drawRecords,
        long fullRecordBuilds
    ) {
        this.requireOwnerAndActive();
        this.frameMarker = Objects.requireNonNull(marker, "marker");
        this.solidSubmissionCount = Math.max(0, solidSubmissionCount);
        this.visibilityNanosThisFrame = Math.max(0L, visibilityNanos);
        this.buildNanosThisFrame = Math.max(0L, buildNanos);
        this.drawRecordsThisFrame = Math.max(0L, drawRecords);
        this.fullRecordBuildsThisFrame = addSaturated(
            this.fullRecordBuildsThisFrame,
            Math.max(0L, fullRecordBuilds)
        );
        this.reusedTemplatesThisFrame = this.reusedThisFrame;
        this.readyThisFrame = this.countSeenState(State.READY);
        this.budgets.touch(this.lease);
    }

    synchronized void abortBeforeSubmission(Failure failure) {
        if (!this.frameActive) {
            return;
        }
        this.fallbackFrames++;
        this.fail(
            failure == Failure.NONE
                ? Failure.PRE_SUBMISSION_FAILURE
                : failure
        );
        this.finishFrameState();
    }

    synchronized void abortBeforeSubmissionPreservingFailure() {
        this.abortBeforeSubmission(
            this.lastFailure == Failure.NONE
                ? Failure.PRE_SUBMISSION_FAILURE
                : this.lastFailure
        );
    }

    synchronized boolean beginSolidSubmission(Object marker) {
        if (
            !this.frameActive
                || marker == null
                || marker != this.frameMarker
                || this.encodedSolidSubmissions
                    >= this.solidSubmissionCount
        ) {
            return false;
        }
        if (!this.submissionStarted) {
            this.submissionStarted = true;
            for (
                int index = 0;
                index < this.activeSlotCount;
                index++
            ) {
                int slot = this.activeSlots[index];
                if (
                    this.occupancy[slot] == OCCUPIED
                        && this.lastSeenFrames[slot] == this.frameId
                        && this.state(slot) == State.READY
                ) {
                    this.states[slot] = (byte)State.SUBMITTED.ordinal();
                }
            }
        }
        this.encodedSolidSubmissions++;
        return true;
    }

    synchronized void recordSubmissionNanos(long nanos) {
        this.submissionNanosThisFrame = addSaturated(
            this.submissionNanosThisFrame,
            Math.max(0L, nanos)
        );
    }

    synchronized void finishOpaqueGroup(
        Object marker,
        boolean completedNormally
    ) {
        if (
            !this.frameActive
                || marker == null
                || marker != this.frameMarker
        ) {
            return;
        }
        boolean submissionCountMismatch =
            completedNormally
                && this.encodedSolidSubmissions
                    != this.solidSubmissionCount;
        if (
            (!completedNormally || submissionCountMismatch)
                && this.submissionStarted
        ) {
            this.fail(Failure.POST_SUBMISSION_FAILURE);
            this.quarantineCurrentFrame();
        } else if (!completedNormally || submissionCountMismatch) {
            this.fallbackFrames++;
            this.fail(Failure.PRE_SUBMISSION_FAILURE);
        }
        this.finishFrameState();
    }

    synchronized int acquireSlot(long sectionNode) {
        this.requireOwnerAndActive();
        int slot = this.find(sectionNode);
        if (slot >= 0) {
            return slot;
        }
        int insertion = ~slot;
        if (
            insertion < 0
                || insertion >= this.capacity
                || this.occupancy[insertion] == OCCUPIED
        ) {
            insertion = this.evictOldestSlot();
            if (insertion < 0) {
                this.fail(Failure.CAPACITY_OVERFLOW);
                return -1;
            }
        }
        this.occupancy[insertion] = OCCUPIED;
        this.sectionNodes[insertion] = sectionNode;
        this.states[insertion] = (byte)State.CANDIDATE.ordinal();
        this.entryCount++;
        this.candidateThisFrame++;
        return insertion;
    }

    synchronized boolean compatible(
        int slot,
        long worldGeneration,
        long rendererGeneration,
        long deviceGeneration,
        long reloadEpoch,
        Object meshOwner,
        long meshRevision,
        Object vertexBuffer,
        long vertexOffset,
        Object indexBuffer,
        long indexOffset,
        int firstIndex,
        int indexCount,
        int baseVertex,
        int indexTypeKey,
        Object pipelineOwner,
        int pipelineKey,
        Object vertexFormat,
        int descriptorKey,
        Object materialOwner,
        int materialKey,
        int x,
        int y,
        int z
    ) {
        this.requireOwnerAndActive();
        if (
            slot < 0
                || slot >= this.capacity
                || this.occupancy[slot] != OCCUPIED
                || this.state(slot) != State.READY
        ) {
            return false;
        }
        boolean compatible =
            this.worldGenerations[slot] == worldGeneration
                && this.rendererGenerations[slot] == rendererGeneration
                && this.deviceGenerations[slot] == deviceGeneration
                && this.reloadEpochs[slot] == reloadEpoch
                && this.meshOwners[slot] == meshOwner
                && this.meshRevisions[slot] == meshRevision
                && this.vertexBuffers[slot] == vertexBuffer
                && this.vertexOffsets[slot] == vertexOffset
                && this.indexBuffers[slot] == indexBuffer
                && this.indexOffsets[slot] == indexOffset
                && this.firstIndices[slot] == firstIndex
                && this.indexCounts[slot] == indexCount
                && this.baseVertices[slot] == baseVertex
                && this.indexTypeKeys[slot] == indexTypeKey
                && this.pipelineOwners[slot] == pipelineOwner
                && this.pipelineKeys[slot] == pipelineKey
                && this.vertexFormats[slot] == vertexFormat
                && this.descriptorKeys[slot] == descriptorKey
                && this.materialOwners[slot] == materialOwner
                && this.materialKeys[slot] == materialKey
                && this.sectionX[slot] == x
                && this.sectionY[slot] == y
                && this.sectionZ[slot] == z
                && this.templates[slot] != null;
        if (!compatible) {
            this.states[slot] = (byte)State.DIRTY.ordinal();
            this.dirtyThisFrame++;
        }
        return compatible;
    }

    synchronized Object reuse(int slot) {
        this.requireOwnerAndActive();
        if (
            slot < 0
                || slot >= this.capacity
                || this.state(slot) != State.READY
                || this.templates[slot] == null
        ) {
            return null;
        }
        this.markActive(slot);
        this.lastSeenFrames[slot] = this.frameId;
        this.reusedThisFrame++;
        this.visibleThisFrame++;
        return this.templates[slot];
    }

    synchronized void beginBuild(int slot) {
        this.requireOwnerAndActive();
        if (
            slot < 0
                || slot >= this.capacity
                || this.occupancy[slot] != OCCUPIED
        ) {
            throw new IllegalArgumentException("invalid cache slot");
        }
        this.clearPayload(slot);
        this.states[slot] = (byte)State.BUILDING.ordinal();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    synchronized void publishReady(
        int slot,
        long worldGeneration,
        long rendererGeneration,
        long deviceGeneration,
        long reloadEpoch,
        Object meshOwner,
        long meshRevision,
        Object vertexBuffer,
        long vertexOffset,
        Object indexBuffer,
        long indexOffset,
        int firstIndex,
        int indexCount,
        int baseVertex,
        int indexTypeKey,
        Object pipelineOwner,
        int pipelineKey,
        Object vertexFormat,
        int descriptorKey,
        Object materialOwner,
        int materialKey,
        int x,
        int y,
        int z,
        Object template
    ) {
        this.requireOwnerAndActive();
        if (
            slot < 0
                || slot >= this.capacity
                || this.occupancy[slot] != OCCUPIED
                || this.state(slot) != State.BUILDING
        ) {
            throw new IllegalStateException("slot is not building");
        }
        this.worldGenerations[slot] = worldGeneration;
        this.rendererGenerations[slot] = rendererGeneration;
        this.deviceGenerations[slot] = deviceGeneration;
        this.reloadEpochs[slot] = reloadEpoch;
        this.meshOwners[slot] = Objects.requireNonNull(
            meshOwner,
            "meshOwner"
        );
        this.meshRevisions[slot] = meshRevision;
        this.vertexBuffers[slot] = Objects.requireNonNull(
            vertexBuffer,
            "vertexBuffer"
        );
        this.vertexOffsets[slot] = vertexOffset;
        this.indexBuffers[slot] = indexBuffer;
        this.indexOffsets[slot] = indexOffset;
        this.firstIndices[slot] = firstIndex;
        this.indexCounts[slot] = indexCount;
        this.baseVertices[slot] = baseVertex;
        this.indexTypeKeys[slot] = indexTypeKey;
        this.pipelineOwners[slot] = Objects.requireNonNull(
            pipelineOwner,
            "pipelineOwner"
        );
        this.pipelineKeys[slot] = pipelineKey;
        this.vertexFormats[slot] = Objects.requireNonNull(
            vertexFormat,
            "vertexFormat"
        );
        this.descriptorKeys[slot] = descriptorKey;
        this.materialOwners[slot] = Objects.requireNonNull(
            materialOwner,
            "materialOwner"
        );
        this.materialKeys[slot] = materialKey;
        this.sectionX[slot] = x;
        this.sectionY[slot] = y;
        this.sectionZ[slot] = z;
        this.templates[slot] = Objects.requireNonNull(
            template,
            "template"
        );
        this.markActive(slot);
        this.lastSeenFrames[slot] = this.frameId;
        this.states[slot] = (byte)State.READY.ordinal();
        this.rebuiltThisFrame++;
        this.fullRecordBuildsThisFrame++;
        this.visibleThisFrame++;
    }

    synchronized void quarantine(int slot) {
        if (
            slot >= 0
                && slot < this.capacity
                && this.occupancy != null
                && this.occupancy[slot] == OCCUPIED
        ) {
            this.states[slot] = (byte)State.QUARANTINED.ordinal();
            this.quarantinedThisFrame++;
        }
    }

    synchronized void recordMojangOnly() {
        this.mojangOnlyThisFrame++;
        this.visibleThisFrame++;
    }

    synchronized void recordUploadedBytes(long bytes) {
        this.uploadedBytesThisFrame = addSaturated(
            this.uploadedBytesThisFrame,
            Math.max(0L, bytes)
        );
    }

    synchronized void invalidate(Failure reason) {
        if (this.frameActive) {
            this.quarantineCurrentFrame();
            this.finishFrameState();
        }
        if (this.occupancy != null) {
            this.retiredThisFrame = addSaturated(
                this.retiredThisFrame,
                this.entryCount
            );
            this.clearEntries(State.RETIRED);
        }
        this.fail(reason);
    }

    synchronized boolean closeAndReport() {
        if (this.frameActive) {
            this.quarantineCurrentFrame();
            this.finishFrameState();
        }
        if (this.lease == 0L && this.occupancy == null) {
            return true;
        }
        if (!this.clearPhysicalStorage()) {
            this.cleanupRetryCount++;
            this.fail(Failure.CLEANUP_RETRY);
            return false;
        }
        if (this.lease == 0L) {
            return true;
        }
        long token = this.lease;
        if (!this.budgets.release(token)) {
            this.cleanupRetryCount++;
            this.fail(Failure.CLEANUP_RETRY);
            return false;
        }
        this.lease = 0L;
        return true;
    }

    @Override
    public void close() {
        this.closeAndReport();
    }

    synchronized Snapshot snapshot() {
        long ready = 0L;
        long submitted = 0L;
        long dirty = 0L;
        long retired = 0L;
        long quarantined = 0L;
        if (this.occupancy != null) {
            for (int slot = 0; slot < this.capacity; slot++) {
                if (this.occupancy[slot] != OCCUPIED) {
                    continue;
                }
                switch (this.state(slot)) {
                    case READY -> ready++;
                    case SUBMITTED -> submitted++;
                    case DIRTY -> dirty++;
                    case RETIRED -> retired++;
                    case QUARANTINED -> quarantined++;
                    default -> {
                    }
                }
            }
        }
        return new Snapshot(
            this.capacity,
            this.entryCount,
            this.accountedBytes,
            0L,
            this.lease != 0L,
            this.frameActive,
            this.submissionStarted,
            this.visibleThisFrame,
            this.mojangOnlyThisFrame,
            this.candidateThisFrame,
            ready,
            submitted,
            this.reusedThisFrame,
            this.rebuiltThisFrame,
            this.dirtyThisFrame,
            this.evictedThisFrame,
            this.retiredThisFrame,
            this.quarantinedThisFrame,
            this.fullRecordBuildsThisFrame,
            this.reusedTemplatesThisFrame,
            this.drawRecordsThisFrame,
            this.solidSubmissionCount,
            this.encodedSolidSubmissions,
            this.uploadedBytesThisFrame,
            this.visibilityNanosThisFrame,
            this.buildNanosThisFrame,
            this.submissionNanosThisFrame,
            this.fallbackFrames,
            this.cleanupRetryCount,
            this.wrongThreadCount,
            this.totalEvictions,
            this.lastFailure
        );
    }

    synchronized State stateForSection(long sectionNode) {
        if (this.occupancy == null) {
            return State.RETIRED;
        }
        int slot = this.find(sectionNode);
        return slot >= 0 ? this.state(slot) : State.RETIRED;
    }

    synchronized void failNextCleanupForTest() {
        this.cleanupFailureCountdown++;
    }

    synchronized void failAllocationForTest(boolean fail) {
        this.allocationFailureForTest = fail;
    }

    private boolean ensureStorage() {
        if (this.occupancy != null) {
            return true;
        }
        long token;
        try {
            token = this.budgets.tryReserve(
                MemoryKind.RAM,
                MemoryCategory.CACHES,
                this.accountedBytes,
                this.accountedBytes,
                null
            );
        } catch (RuntimeException | LinkageError error) {
            this.fail(Failure.BUDGET_REJECTED);
            this.fallbackFrames++;
            return false;
        }
        if (token == 0L) {
            this.fail(Failure.BUDGET_REJECTED);
            this.fallbackFrames++;
            return false;
        }
        try {
            if (this.allocationFailureForTest) {
                throw new OutOfMemoryError("injected template allocation");
            }
            this.allocateStorage();
        } catch (OutOfMemoryError | RuntimeException error) {
            this.clearPhysicalStorageUnchecked();
            this.budgets.release(token);
            this.fail(Failure.ALLOCATION_FAILED);
            this.fallbackFrames++;
            return false;
        }
        this.lease = token;
        if (
            !this.budgets.registerEvictable(
                token,
                this::evictFromBudget
            )
        ) {
            this.clearPhysicalStorageUnchecked();
            this.budgets.release(token);
            this.lease = 0L;
            this.fail(Failure.EVICTABLE_REGISTRATION_FAILED);
            this.fallbackFrames++;
            return false;
        }
        return true;
    }

    private void allocateStorage() {
        this.occupancy = new byte[this.capacity];
        this.states = new byte[this.capacity];
        this.sectionNodes = new long[this.capacity];
        this.worldGenerations = new long[this.capacity];
        this.rendererGenerations = new long[this.capacity];
        this.deviceGenerations = new long[this.capacity];
        this.reloadEpochs = new long[this.capacity];
        this.meshRevisions = new long[this.capacity];
        this.vertexOffsets = new long[this.capacity];
        this.indexOffsets = new long[this.capacity];
        this.lastSeenFrames = new long[this.capacity];
        this.sectionX = new int[this.capacity];
        this.sectionY = new int[this.capacity];
        this.sectionZ = new int[this.capacity];
        this.firstIndices = new int[this.capacity];
        this.indexCounts = new int[this.capacity];
        this.baseVertices = new int[this.capacity];
        this.indexTypeKeys = new int[this.capacity];
        this.pipelineKeys = new int[this.capacity];
        this.descriptorKeys = new int[this.capacity];
        this.materialKeys = new int[this.capacity];
        this.activeSlots = new int[this.capacity];
        this.meshOwners = new Object[this.capacity];
        this.vertexBuffers = new Object[this.capacity];
        this.indexBuffers = new Object[this.capacity];
        this.pipelineOwners = new Object[this.capacity];
        this.vertexFormats = new Object[this.capacity];
        this.materialOwners = new Object[this.capacity];
        this.templates = new Object[this.capacity];
        this.entryCount = 0;
    }

    private boolean evictFromBudget() {
        synchronized (this) {
            if (this.frameActive) {
                return false;
            }
            if (!this.clearPhysicalStorage()) {
                this.cleanupRetryCount++;
                this.fail(Failure.CLEANUP_RETRY);
                return false;
            }
            this.lease = 0L;
            this.totalEvictions++;
            return true;
        }
    }

    private boolean clearPhysicalStorage() {
        if (this.cleanupFailureCountdown > 0) {
            this.cleanupFailureCountdown--;
            return false;
        }
        this.clearPhysicalStorageUnchecked();
        return true;
    }

    private void clearPhysicalStorageUnchecked() {
        this.occupancy = null;
        this.states = null;
        this.sectionNodes = null;
        this.worldGenerations = null;
        this.rendererGenerations = null;
        this.deviceGenerations = null;
        this.reloadEpochs = null;
        this.meshRevisions = null;
        this.vertexOffsets = null;
        this.indexOffsets = null;
        this.lastSeenFrames = null;
        this.sectionX = null;
        this.sectionY = null;
        this.sectionZ = null;
        this.firstIndices = null;
        this.indexCounts = null;
        this.baseVertices = null;
        this.indexTypeKeys = null;
        this.pipelineKeys = null;
        this.descriptorKeys = null;
        this.materialKeys = null;
        this.activeSlots = null;
        this.meshOwners = null;
        this.vertexBuffers = null;
        this.indexBuffers = null;
        this.pipelineOwners = null;
        this.vertexFormats = null;
        this.materialOwners = null;
        this.templates = null;
        this.entryCount = 0;
        this.frameMarker = null;
        this.frameActive = false;
        this.submissionStarted = false;
        this.activeSlotCount = 0;
    }

    private int find(long sectionNode) {
        int slot = mix(sectionNode) & this.mask;
        int tombstone = -1;
        for (int attempt = 0; attempt < this.capacity; attempt++) {
            byte occupancyState = this.occupancy[slot];
            if (occupancyState == EMPTY) {
                return ~(tombstone >= 0 ? tombstone : slot);
            }
            if (
                occupancyState == OCCUPIED
                    && this.sectionNodes[slot] == sectionNode
            ) {
                return slot;
            }
            if (occupancyState == TOMBSTONE && tombstone < 0) {
                tombstone = slot;
            }
            slot = (slot + 1) & this.mask;
        }
        return tombstone >= 0 ? ~tombstone : Integer.MIN_VALUE;
    }

    private int evictOldestSlot() {
        int candidate = -1;
        long oldestFrame = Long.MAX_VALUE;
        for (int slot = 0; slot < this.capacity; slot++) {
            if (
                this.occupancy[slot] == OCCUPIED
                    && this.lastSeenFrames[slot] != this.frameId
                    && this.lastSeenFrames[slot] < oldestFrame
            ) {
                candidate = slot;
                oldestFrame = this.lastSeenFrames[slot];
            }
        }
        if (candidate < 0) {
            return -1;
        }
        this.clearSlot(candidate, State.RETIRED);
        this.evictedThisFrame++;
        this.totalEvictions++;
        return candidate;
    }

    private void clearEntries(State finalState) {
        for (int slot = 0; slot < this.capacity; slot++) {
            if (this.occupancy[slot] == OCCUPIED) {
                this.clearSlot(slot, finalState);
            }
        }
        Arrays.fill(this.occupancy, EMPTY);
        this.entryCount = 0;
    }

    private void clearSlot(int slot, State finalState) {
        this.states[slot] = (byte)finalState.ordinal();
        this.clearPayload(slot);
        this.occupancy[slot] = TOMBSTONE;
        this.sectionNodes[slot] = 0L;
        this.lastSeenFrames[slot] = 0L;
        this.entryCount = Math.max(0, this.entryCount - 1);
    }

    private void clearPayload(int slot) {
        this.meshOwners[slot] = null;
        this.vertexBuffers[slot] = null;
        this.indexBuffers[slot] = null;
        this.pipelineOwners[slot] = null;
        this.vertexFormats[slot] = null;
        this.materialOwners[slot] = null;
        this.templates[slot] = null;
    }

    private void quarantineCurrentFrame() {
        if (this.occupancy == null) {
            return;
        }
        for (
            int index = 0;
            index < this.activeSlotCount;
            index++
        ) {
            int slot = this.activeSlots[index];
            if (
                this.occupancy[slot] == OCCUPIED
                    && this.lastSeenFrames[slot] == this.frameId
            ) {
                this.states[slot] =
                    (byte)State.QUARANTINED.ordinal();
                this.quarantinedThisFrame++;
            }
        }
    }

    private void finishFrameState() {
        if (this.occupancy != null) {
            for (
                int index = 0;
                index < this.activeSlotCount;
                index++
            ) {
                int slot = this.activeSlots[index];
                if (
                    this.occupancy[slot] == OCCUPIED
                        && this.lastSeenFrames[slot] == this.frameId
                        && this.state(slot) == State.SUBMITTED
                ) {
                    this.states[slot] = (byte)State.READY.ordinal();
                }
            }
        }
        this.frameMarker = null;
        this.frameActive = false;
        this.submissionStarted = false;
    }

    private long countSeenState(State expected) {
        long count = 0L;
        for (
            int index = 0;
            index < this.activeSlotCount;
            index++
        ) {
            int slot = this.activeSlots[index];
            if (
                this.occupancy[slot] == OCCUPIED
                    && this.lastSeenFrames[slot] == this.frameId
                    && this.state(slot) == expected
            ) {
                count++;
            }
        }
        return count;
    }

    private void markActive(int slot) {
        if (this.lastSeenFrames[slot] == this.frameId) {
            return;
        }
        if (this.activeSlotCount >= this.activeSlots.length) {
            throw new IllegalStateException(
                "active template slot capacity exceeded"
            );
        }
        this.activeSlots[this.activeSlotCount++] = slot;
    }

    private void requireOwnerAndActive() {
        if (
            this.ownerThread != Thread.currentThread()
                || !this.frameActive
        ) {
            throw new IllegalStateException(
                "template cache requires its active render-thread frame"
            );
        }
    }

    private State state(int slot) {
        return STATES[this.states[slot]];
    }

    private void resetFrameCounters() {
        this.activeSlotCount = 0;
        this.visibleThisFrame = 0L;
        this.mojangOnlyThisFrame = 0L;
        this.candidateThisFrame = 0L;
        this.readyThisFrame = 0L;
        this.reusedThisFrame = 0L;
        this.rebuiltThisFrame = 0L;
        this.dirtyThisFrame = 0L;
        this.evictedThisFrame = 0L;
        this.retiredThisFrame = 0L;
        this.quarantinedThisFrame = 0L;
        this.fullRecordBuildsThisFrame = 0L;
        this.reusedTemplatesThisFrame = 0L;
        this.drawRecordsThisFrame = 0L;
        this.uploadedBytesThisFrame = 0L;
        this.visibilityNanosThisFrame = 0L;
        this.buildNanosThisFrame = 0L;
        this.submissionNanosThisFrame = 0L;
    }

    private void fail(Failure failure) {
        this.lastFailure = Objects.requireNonNull(failure, "failure");
    }

    private static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int)value;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long addSaturated(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return Long.MAX_VALUE - left < right
            ? Long.MAX_VALUE
            : left + right;
    }

    record Snapshot(
        int capacity,
        int entries,
        long ramBytes,
        long vramBytes,
        boolean budgetActive,
        boolean frameActive,
        boolean submissionStarted,
        long visible,
        long mojangOnly,
        long candidates,
        long ready,
        long submitted,
        long reused,
        long rebuilt,
        long dirty,
        long evicted,
        long retired,
        long quarantined,
        long fullRecordBuilds,
        long reusedTemplates,
        long drawRecords,
        int solidSubmissionCount,
        int encodedSolidSubmissions,
        long uploadedBytes,
        long visibilityNanos,
        long buildNanos,
        long submissionNanos,
        long fallbackFrames,
        long cleanupRetries,
        long wrongThreadCount,
        long totalEvictions,
        Failure lastFailure
    ) {
    }
}
