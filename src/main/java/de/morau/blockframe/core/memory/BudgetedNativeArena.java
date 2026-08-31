package de.morau.blockframe.core.memory;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Fixed, owner-thread-confined native scratch storage with one RAM lease.
 *
 * <p>The complete conservative footprint is reserved before the physical
 * arena is opened. Claims are bounded slices from a checked bump cursor and
 * never grow the allocation. Claim creation is a setup/reload operation:
 * callers may retain a claimed segment as part of the owning component, but
 * must not call {@link #claim(long, long)} as a per-frame allocator. This
 * primitive alone therefore makes no per-frame allocation-free claim. The
 * class deliberately exposes no raw-address API.</p>
 */
public final class BudgetedNativeArena implements AutoCloseable {
    static final long COMMITTED_ALIGNMENT_BYTES = 64L;

    private static final ThreadLocal<PendingCleanup> PENDING_CLEANUP =
        ThreadLocal.withInitial(PendingCleanup::new);

    private static final NativeArenaFactory JVM_NATIVE_ARENA_FACTORY =
        () -> {
            Arena arena = Arena.ofConfined();
            return new NativeStorage() {
                @Override
                public MemorySegment allocate(
                    long byteSize,
                    long byteAlignment
                ) {
                    return arena.allocate(byteSize, byteAlignment);
                }

                @Override
                public void close() {
                    arena.close();
                }
            };
        };

    private final LeaseController leases;
    private final Layout layout;
    private final Thread ownerThread;
    private long budgetLease;
    private NativeStorage physicalArena;
    private MemorySegment storage;
    private long cursor;
    private boolean physicalClosed;
    private boolean closing;
    private boolean closed;

    private BudgetedNativeArena(
        LeaseController leases,
        Layout layout,
        long budgetLease,
        NativeStorage physicalArena,
        MemorySegment storage
    ) {
        this.leases = leases;
        this.layout = layout;
        this.ownerThread = Thread.currentThread();
        this.budgetLease = budgetLease;
        this.physicalArena = physicalArena;
        this.storage = storage;
    }

    /**
     * Creates a fully budgeted confined arena, or returns {@code null} when
     * the budget or native allocator cannot satisfy the fixed layout.
     */
    public static BudgetedNativeArena tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        Layout layout
    ) {
        return tryCreate(
            budgets,
            category,
            layout,
            JVM_NATIVE_ARENA_FACTORY
        );
    }

    static BudgetedNativeArena tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        Layout layout,
        NativeArenaFactory arenaFactory
    ) {
        Objects.requireNonNull(budgets, "budgets");
        return tryCreate(
            new ManagerLeaseController(budgets),
            category,
            layout,
            arenaFactory
        );
    }

    static BudgetedNativeArena tryCreate(
        LeaseController leases,
        MemoryCategory category,
        Layout layout,
        NativeArenaFactory arenaFactory
    ) {
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(arenaFactory, "arenaFactory");

        PendingCleanup pending = PENDING_CLEANUP.get();
        pending.retryOrThrow(
            "pending native arena creation cleanup failed"
        );
        pending.prepare(leases);

        long lease;
        try {
            lease = leases.tryReserve(
                category,
                layout.requestedBytes(),
                layout.committedBytes()
            );
        } catch (RuntimeException | Error reservationFailure) {
            pending.cancelPreparation();
            throw reservationFailure;
        }
        if (lease == 0L) {
            pending.cancelPreparation();
            return null;
        }
        pending.recordLease(lease);

        try {
            NativeStorage physicalArena = Objects.requireNonNull(
                arenaFactory.open(),
                "native arena factory returned null"
            );
            pending.recordPhysical(physicalArena);
            MemorySegment allocation = Objects.requireNonNull(
                physicalArena.allocate(
                    layout.requestedBytes(),
                    layout.allocationAlignment()
                ),
                "native arena returned null"
            );
            if (allocation.byteSize() < layout.requestedBytes()) {
                throw new IllegalStateException(
                    "native arena returned an undersized allocation"
                );
            }
            MemorySegment storage = allocation.asSlice(
                0L,
                layout.requestedBytes()
            );
            BudgetedNativeArena created = new BudgetedNativeArena(
                leases,
                layout,
                lease,
                physicalArena,
                storage
            );
            pending.transferOwnership();
            return created;
        } catch (OutOfMemoryError allocationFailure) {
            boolean rolledBack = pending.retry(allocationFailure);
            if (!rolledBack) {
                throw allocationFailure;
            }
            return null;
        } catch (RuntimeException | Error allocationFailure) {
            pending.retry(allocationFailure);
            throw allocationFailure;
        }
    }

    /**
     * Retries cleanup retained by a failed creation on the current owner
     * thread.
     *
     * <p>This is a shutdown/reload safety hook. It must run on the same
     * thread that attempted creation and before the associated budget manager
     * is closed. Failure is fail-closed: retained ownership is preserved and
     * an exception is thrown for a later retry.</p>
     */
    public static void retryPendingCleanup() {
        PENDING_CLEANUP.get().retryOrThrow(
            "pending native arena creation cleanup failed"
        );
    }

    /**
     * Claims a fixed native slice during component setup, or returns
     * {@code null} when the remaining capacity cannot satisfy it.
     *
     * <p>The returned segment is confined to this arena's owner thread and
     * lifetime. A component may retain its setup-time claim, but must stop
     * using every retained claim before close begins. This method is not a
     * per-frame allocator.</p>
     */
    public MemorySegment claim(long byteCount, long byteAlignment) {
        this.requireAccessible();
        if (byteCount < 0L) {
            throw new IllegalArgumentException(
                "claim byte count must not be negative"
            );
        }
        requireAlignment(byteAlignment);
        if (byteAlignment > this.layout.allocationAlignment()) {
            throw new IllegalArgumentException(
                "claim alignment exceeds the allocation alignment"
            );
        }

        long offset = align(this.cursor, byteAlignment);
        long end = Math.addExact(offset, byteCount);
        if (end > this.layout.requestedBytes()) {
            return null;
        }
        MemorySegment claim = this.storage.asSlice(offset, byteCount);
        this.cursor = end;
        return claim;
    }

    public long capacityBytes() {
        this.requireAccessible();
        return this.layout.requestedBytes();
    }

    public long claimedBytes() {
        this.requireAccessible();
        return this.cursor;
    }

    /**
     * Rewinds the bump cursor.
     *
     * <p>The caller must guarantee that no previously returned claim will be
     * read or written again. A reset cannot revoke individual FFM slices.</p>
     */
    public void reset() {
        this.requireAccessible();
        this.cursor = 0L;
    }

    boolean registerEvictable(
        MemoryBudgetManager.Evictable evictable
    ) {
        this.requireAccessible();
        return this.leases.registerEvictable(
            this.budgetLease,
            Objects.requireNonNull(evictable, "evictable")
        );
    }

    boolean touchLease() {
        this.requireAccessible();
        return this.leases.touch(this.budgetLease);
    }

    /**
     * Closes native storage before releasing its logical lease.
     *
     * <p>Close quarantines the arena before physical close is attempted. If
     * physical close throws, claim/reset/capacity operations stay blocked and
     * a later owner-thread close retries the same retained physical owner. If
     * lease release is rejected after confirmed physical close, the token is
     * retained and a later close retries only that release. Physical close is
     * never repeated after it returned successfully.</p>
     */
    @Override
    public void close() {
        this.requireOwnerThread();
        if (this.closed) {
            return;
        }

        this.closing = true;
        if (!this.physicalClosed) {
            this.physicalArena.close();
            this.physicalClosed = true;
            this.physicalArena = null;
            this.storage = null;
            this.cursor = 0L;
        }
        if (!this.leases.release(this.budgetLease)) {
            throw new IllegalStateException(
                "native arena RAM lease could not be released"
            );
        }

        this.budgetLease = 0L;
        this.closed = true;
    }

    private void requireAccessible() {
        this.requireOwnerThread();
        if (this.closed || this.closing || this.physicalClosed) {
            throw new IllegalStateException("native arena is closed");
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "native arena accessed from a non-owner thread"
            );
        }
    }

    private static void addSuppressed(
        Throwable failure,
        Throwable suppressed
    ) {
        if (failure != suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    private static void requireAlignment(long byteAlignment) {
        if (
            byteAlignment <= 0L
                || (byteAlignment & (byteAlignment - 1L)) != 0L
        ) {
            throw new IllegalArgumentException(
                "byte alignment must be a positive power of two"
            );
        }
    }

    private static long align(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    /**
     * Fixed requested payload and base allocation alignment.
     *
     * <p>Committed accounting rounds the payload to at least 64 bytes and to
     * the requested base alignment when that is larger.</p>
     */
    public record Layout(long requestedBytes, long allocationAlignment) {
        public Layout {
            if (requestedBytes <= 0L) {
                throw new IllegalArgumentException(
                    "native arena requested bytes must be positive"
                );
            }
            requireAlignment(allocationAlignment);
            committedBytes(requestedBytes, allocationAlignment);
        }

        public long committedBytes() {
            return committedBytes(
                this.requestedBytes,
                this.allocationAlignment
            );
        }

        private static long committedBytes(
            long requestedBytes,
            long allocationAlignment
        ) {
            return align(
                requestedBytes,
                Math.max(
                    COMMITTED_ALIGNMENT_BYTES,
                    allocationAlignment
                )
            );
        }
    }

    @FunctionalInterface
    interface NativeArenaFactory {
        NativeStorage open();
    }

    interface NativeStorage {
        MemorySegment allocate(long byteSize, long byteAlignment);

        void close();
    }

    interface LeaseController {
        long tryReserve(
            MemoryCategory category,
            long requestedBytes,
            long committedBytes
        );

        default boolean registerEvictable(
            long token,
            MemoryBudgetManager.Evictable evictable
        ) {
            return false;
        }

        default boolean touch(long token) {
            return false;
        }

        boolean release(long token);
    }

    private record ManagerLeaseController(
        MemoryBudgetManager budgets
    ) implements LeaseController {
        private ManagerLeaseController {
            Objects.requireNonNull(budgets, "budgets");
        }

        @Override
        public long tryReserve(
            MemoryCategory category,
            long requestedBytes,
            long committedBytes
        ) {
            return this.budgets.tryReserve(
                MemoryKind.RAM,
                category,
                requestedBytes,
                committedBytes,
                null
            );
        }

        @Override
        public boolean registerEvictable(
            long token,
            MemoryBudgetManager.Evictable evictable
        ) {
            return this.budgets.registerEvictable(token, evictable);
        }

        @Override
        public boolean touch(long token) {
            return this.budgets.touch(token);
        }

        @Override
        public boolean release(long token) {
            return this.budgets.release(token);
        }
    }

    /**
     * Mutable owner retained before reservation so even constructor OOME
     * cannot lose a later lease token or physical arena.
     */
    private static final class PendingCleanup {
        private final Thread ownerThread = Thread.currentThread();
        private LeaseController leases;
        private long lease;
        private NativeStorage physicalArena;
        private boolean physicalClosed;

        private void prepare(LeaseController preparedLeases) {
            this.requireOwnerThread();
            if (!this.empty()) {
                throw new IllegalStateException(
                    "native arena cleanup owner is already active"
                );
            }
            this.leases = Objects.requireNonNull(
                preparedLeases,
                "leases"
            );
        }

        private void recordLease(long reservedLease) {
            this.requireOwnerThread();
            if (this.leases == null || reservedLease == 0L) {
                throw new IllegalStateException(
                    "native arena cleanup lease was not prepared"
                );
            }
            this.lease = reservedLease;
        }

        private void recordPhysical(NativeStorage openedArena) {
            this.requireOwnerThread();
            if (this.lease == 0L || this.physicalArena != null) {
                throw new IllegalStateException(
                    "native arena cleanup physical owner is invalid"
                );
            }
            this.physicalArena = Objects.requireNonNull(
                openedArena,
                "openedArena"
            );
            this.physicalClosed = false;
        }

        private void transferOwnership() {
            this.requireOwnerThread();
            if (
                this.leases == null
                    || this.lease == 0L
                    || this.physicalArena == null
                    || this.physicalClosed
            ) {
                throw new IllegalStateException(
                    "native arena cleanup ownership is incomplete"
                );
            }
            this.clear();
        }

        private void cancelPreparation() {
            this.requireOwnerThread();
            if (this.lease != 0L || this.physicalArena != null) {
                throw new IllegalStateException(
                    "native arena cleanup reservation is still active"
                );
            }
            this.clear();
        }

        private void retryOrThrow(String message) {
            this.requireOwnerThread();
            if (this.empty()) {
                return;
            }
            IllegalStateException cleanupFailure =
                new IllegalStateException(message);
            if (!this.retry(cleanupFailure)) {
                throw cleanupFailure;
            }
        }

        private boolean retry(Throwable failure) {
            this.requireOwnerThread();
            if (this.lease == 0L) {
                this.clear();
                return true;
            }

            if (!this.physicalClosed && this.physicalArena != null) {
                try {
                    this.physicalArena.close();
                    this.physicalClosed = true;
                    this.physicalArena = null;
                } catch (Throwable closeFailure) {
                    addSuppressed(failure, closeFailure);
                    return false;
                }
            }

            try {
                if (!this.leases.release(this.lease)) {
                    addSuppressed(
                        failure,
                        new IllegalStateException(
                            "native arena RAM lease survived failed creation"
                        )
                    );
                    return false;
                }
            } catch (Throwable releaseFailure) {
                addSuppressed(failure, releaseFailure);
                return false;
            }

            this.clear();
            return true;
        }

        private boolean empty() {
            return this.leases == null
                && this.lease == 0L
                && this.physicalArena == null
                && !this.physicalClosed;
        }

        private void clear() {
            this.leases = null;
            this.lease = 0L;
            this.physicalArena = null;
            this.physicalClosed = false;
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != this.ownerThread) {
                throw new IllegalStateException(
                    "native arena cleanup retried from a non-owner thread"
                );
            }
        }
    }
}
