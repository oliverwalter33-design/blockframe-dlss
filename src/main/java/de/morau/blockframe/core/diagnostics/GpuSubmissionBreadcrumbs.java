package de.morau.blockframe.core.diagnostics;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.memory.BudgetedNativeArena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Fixed, owner-thread-confined CPU breadcrumbs for real Vulkan submit calls.
 *
 * <p>The ring retains primitive identifiers only. It never owns or retains a
 * Vulkan device, queue, command buffer, checkpoint or render-graph resource.
 * Encoding a pass is not treated as submission, and submission is not
 * treated as completion. Completion must be reported from Mojang's completed
 * submit index, which advances only after a successful timeline-semaphore
 * wait.</p>
 */
public final class GpuSubmissionBreadcrumbs implements AutoCloseable {
    public static final int CAPACITY = 64;
    public static final int PASS_MOTION_COMPUTE = 1;
    public static final int PASS_DLSS_EVALUATE = 2;
    public static final int PASS_GRAPHICS_SUBMIT = 3;

    public static final long REQUESTED_BYTES =
        CAPACITY * 6L * Long.BYTES;
    public static final long COMMITTED_BYTES = REQUESTED_BYTES;

    static final long STATE_EMPTY = 0L;
    static final long STATE_ENCODED = 1L;
    static final long STATE_SUBMITTED = 2L;
    static final long STATE_COMPLETED = 3L;
    static final long STATE_ABANDONED = 4L;

    private static final int LONGS_PER_ENTRY = 6;
    private static final int TOKEN = 0;
    private static final int FRAME_ID = 1;
    private static final int PASS_ID = 2;
    private static final int STATE = 3;
    private static final int SUBMIT_INDEX = 4;
    private static final int COMPLETION_INDEX = 5;
    private static final ThreadLocal<BudgetedNativeArena>
        RETAINED_FAILED_CREATION = new ThreadLocal<>();

    private final Thread ownerThread;
    private final BudgetedNativeArena arena;
    private LongBuffer entries;
    private int cursor;
    private long nextToken = 1L;
    private long recorded;
    private long overwritten;
    private long abandoned;
    private long lastEncodedFrame = -1L;
    private long lastSubmittedFrame = -1L;
    private long lastCompletedFrame = -1L;
    private long lastSubmittedIndex = -1L;
    private long lastCompletedIndex = -1L;
    private long deviceGeneration = 1L;
    private boolean deviceClosing;
    private boolean closing;
    private boolean closed;

    private GpuSubmissionBreadcrumbs(
        BudgetedNativeArena arena,
        LongBuffer entries
    ) {
        this.ownerThread = Thread.currentThread();
        this.arena = arena;
        this.entries = entries;
    }

    /**
     * Creates the complete budgeted ring, or returns {@code null} when the
     * diagnostic RAM budget or native allocation cannot satisfy it.
     */
    public static GpuSubmissionBreadcrumbs tryCreate(
        MemoryBudgetManager budgets
    ) {
        Objects.requireNonNull(budgets, "budgets");
        retryPendingCleanup();

        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.DIAGNOSTICS,
            new BudgetedNativeArena.Layout(
                REQUESTED_BYTES,
                64L
            )
        );
        if (arena == null) {
            return null;
        }

        try {
            MemorySegment storage = arena.claim(
                REQUESTED_BYTES,
                Long.BYTES
            );
            if (storage == null) {
                throw new IllegalStateException(
                    "GPU breadcrumb arena did not provide its fixed layout"
                );
            }
            LongBuffer entries = storage
                .asByteBuffer()
                .order(ByteOrder.nativeOrder())
                .asLongBuffer();
            if (entries.capacity() != CAPACITY * LONGS_PER_ENTRY) {
                throw new IllegalStateException(
                    "GPU breadcrumb view has unexpected capacity"
                );
            }
            return new GpuSubmissionBreadcrumbs(arena, entries);
        } catch (OutOfMemoryError allocationFailure) {
            retainOrCloseFailedCreation(arena, allocationFailure);
            return null;
        } catch (RuntimeException | Error creationFailure) {
            retainOrCloseFailedCreation(arena, creationFailure);
            throw creationFailure;
        }
    }

    /**
     * Retries an otherwise unreachable failed construction on its owner
     * thread, then delegates any arena-internal retained cleanup.
     */
    public static void retryPendingCleanup() {
        BudgetedNativeArena retained =
            RETAINED_FAILED_CREATION.get();
        if (retained != null) {
            retained.close();
            RETAINED_FAILED_CREATION.remove();
        }
        BudgetedNativeArena.retryPendingCleanup();
    }

    public long recordEncoded(long frameId, int passId) {
        this.requireAccessible();
        if (frameId < 0L) {
            throw new IllegalArgumentException(
                "frameId must not be negative"
            );
        }
        requirePassId(passId);
        this.beginNewDeviceGenerationIfNeeded();

        int base = this.cursor * LONGS_PER_ENTRY;
        if (this.entries.get(base + STATE) != STATE_EMPTY) {
            this.overwritten = incrementSaturated(this.overwritten);
        }
        long token = this.nextToken();
        this.entries.put(base + TOKEN, token);
        this.entries.put(base + FRAME_ID, frameId);
        this.entries.put(base + PASS_ID, passId);
        this.entries.put(base + STATE, STATE_ENCODED);
        this.entries.put(base + SUBMIT_INDEX, -1L);
        this.entries.put(base + COMPLETION_INDEX, -1L);
        this.cursor = (this.cursor + 1) % CAPACITY;
        this.recorded = incrementSaturated(this.recorded);
        this.lastEncodedFrame = frameId;
        return token;
    }

    /**
     * Associates every command recorded since the previous real submit with
     * Mojang's current submit index and adds one explicit submit breadcrumb.
     */
    public int recordSubmit(long frameId, long submitIndex) {
        this.requireAccessible();
        if (submitIndex < 0L) {
            throw new IllegalArgumentException(
                "submitIndex must not be negative"
            );
        }
        this.recordEncoded(frameId, PASS_GRAPHICS_SUBMIT);
        int submitted = 0;
        for (int slot = 0; slot < CAPACITY; slot++) {
            int base = slot * LONGS_PER_ENTRY;
            if (this.entries.get(base + STATE) == STATE_ENCODED) {
                this.entries.put(base + SUBMIT_INDEX, submitIndex);
                this.entries.put(base + STATE, STATE_SUBMITTED);
                submitted++;
            }
        }
        this.lastSubmittedFrame = frameId;
        this.lastSubmittedIndex = Math.max(
            this.lastSubmittedIndex,
            submitIndex
        );
        return submitted;
    }

    /**
     * Marks only entries whose actual submit index is known complete.
     */
    public int recordCompletion(long completedSubmitIndex) {
        this.requireAccessible();
        if (completedSubmitIndex < 0L) {
            throw new IllegalArgumentException(
                "completedSubmitIndex must not be negative"
            );
        }
        int completed = 0;
        for (int slot = 0; slot < CAPACITY; slot++) {
            int base = slot * LONGS_PER_ENTRY;
            if (
                this.entries.get(base + STATE) == STATE_SUBMITTED
                    && this.entries.get(base + SUBMIT_INDEX)
                        <= completedSubmitIndex
            ) {
                this.completeEntry(base, completedSubmitIndex);
                completed++;
            }
        }
        this.lastCompletedIndex = Math.max(
            this.lastCompletedIndex,
            completedSubmitIndex
        );
        return completed;
    }

    /**
     * Records the beginning of a device close. No breadcrumb is completed by
     * this signal alone.
     */
    public void deviceClosing() {
        this.requireAccessible();
        this.deviceClosing = true;
    }

    /**
     * Ends the device generation after Mojang destroyed its encoder.
     *
     * <p>Mojang's queue-idle wrapper currently discards Vulkan's result code,
     * so encoder destruction is not proof of successful GPU completion.
     * Pending entries therefore become abandoned rather than completed.</p>
     */
    public int encoderDestroyedWithoutCompletionProof() {
        this.requireAccessible();
        int abandonedCount = this.abandonPendingEntries();
        this.deviceClosing = false;
        this.resetDeviceLocalIndices();
        this.deviceGeneration = incrementSaturated(
            this.deviceGeneration
        );
        return abandonedCount;
    }

    /**
     * Allocates only for the explicitly requested diagnostic snapshot.
     */
    public Snapshot snapshot() {
        this.requireAccessible();
        int encoded = 0;
        int submitted = 0;
        int completed = 0;
        int abandonedCount = 0;
        for (int slot = 0; slot < CAPACITY; slot++) {
            long state =
                this.entries.get(slot * LONGS_PER_ENTRY + STATE);
            if (state == STATE_ENCODED) {
                encoded++;
            } else if (state == STATE_SUBMITTED) {
                submitted++;
            } else if (state == STATE_COMPLETED) {
                completed++;
            } else if (state == STATE_ABANDONED) {
                abandonedCount++;
            }
        }
        return new Snapshot(
            CAPACITY,
            this.recorded,
            this.overwritten,
            this.abandoned,
            encoded,
            submitted,
            completed,
            abandonedCount,
            this.lastEncodedFrame,
            this.lastSubmittedFrame,
            this.lastCompletedFrame,
            this.lastSubmittedIndex,
            this.lastCompletedIndex,
            this.deviceGeneration,
            this.deviceClosing,
            this.closed
        );
    }

    @Override
    public void close() {
        this.requireOwnerThread();
        if (this.closed) {
            return;
        }
        this.closing = true;
        this.entries = null;
        this.arena.close();
        this.closed = true;
        this.closing = false;
    }

    private void completeEntry(int base, long completionIndex) {
        this.entries.put(base + COMPLETION_INDEX, completionIndex);
        this.entries.put(base + STATE, STATE_COMPLETED);
        this.lastCompletedFrame = Math.max(
            this.lastCompletedFrame,
            this.entries.get(base + FRAME_ID)
        );
    }

    private void beginNewDeviceGenerationIfNeeded() {
        if (!this.deviceClosing) {
            return;
        }
        this.abandonPendingEntries();
        this.deviceClosing = false;
        this.resetDeviceLocalIndices();
        this.deviceGeneration = incrementSaturated(
            this.deviceGeneration
        );
    }

    private int abandonPendingEntries() {
        int abandonedCount = 0;
        for (int slot = 0; slot < CAPACITY; slot++) {
            int base = slot * LONGS_PER_ENTRY;
            long state = this.entries.get(base + STATE);
            if (
                state == STATE_ENCODED
                    || state == STATE_SUBMITTED
            ) {
                this.entries.put(base + STATE, STATE_ABANDONED);
                this.abandoned = incrementSaturated(this.abandoned);
                abandonedCount++;
            }
        }
        return abandonedCount;
    }

    private void resetDeviceLocalIndices() {
        this.lastSubmittedIndex = -1L;
        this.lastCompletedIndex = -1L;
    }

    private long nextToken() {
        long token = this.nextToken;
        this.nextToken = incrementSaturated(this.nextToken);
        if (this.nextToken == Long.MAX_VALUE) {
            this.nextToken = 1L;
        }
        return token;
    }

    private void requireAccessible() {
        this.requireOwnerThread();
        if (this.closed || this.closing || this.entries == null) {
            throw new IllegalStateException(
                "GPU breadcrumb ring is closed"
            );
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "GPU breadcrumb ring accessed from a non-owner thread"
            );
        }
    }

    private static void requirePassId(int passId) {
        if (
            passId != PASS_MOTION_COMPUTE
                && passId != PASS_DLSS_EVALUATE
                && passId != PASS_GRAPHICS_SUBMIT
        ) {
            throw new IllegalArgumentException(
                "unknown GPU breadcrumb pass " + passId
            );
        }
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static void retainOrCloseFailedCreation(
        BudgetedNativeArena arena,
        Throwable failure
    ) {
        try {
            arena.close();
        } catch (Throwable cleanupFailure) {
            RETAINED_FAILED_CREATION.set(arena);
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    public record Snapshot(
        int capacity,
        long recorded,
        long overwritten,
        long abandoned,
        int encodedEntries,
        int submittedEntries,
        int completedEntries,
        int abandonedEntries,
        long lastEncodedFrame,
        long lastSubmittedFrame,
        long lastCompletedFrame,
        long lastSubmittedIndex,
        long lastCompletedIndex,
        long deviceGeneration,
        boolean deviceClosing,
        boolean closed
    ) {}
}
