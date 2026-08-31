package de.morau.blockframe.core.memory;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Fixed, render-thread-confined storage for reusable heap objects.
 *
 * <p>The slab reserves its complete conservative RAM footprint before the
 * reference array or any object factory is invoked. Every factory result is
 * retained in a stable slot for the lifetime of the slab. Acquiring is a
 * bounded sequential cursor operation; {@link #reset()} only rewinds that
 * cursor and never replaces the objects.</p>
 */
public final class ReusableObjectSlab<T> implements AutoCloseable {
    static final long CONSERVATIVE_ARRAY_HEADER_BYTES = 16L;
    static final long CONSERVATIVE_REFERENCE_BYTES = Long.BYTES;
    static final long COMMITTED_ALIGNMENT_BYTES = 64L;

    private static final ObjectArrayAllocator JVM_ARRAY_ALLOCATOR =
        Object[]::new;
    private static final ThreadLocal<PendingCleanup> PENDING_CLEANUP =
        ThreadLocal.withInitial(PendingCleanup::new);

    private final LeaseController leases;
    private final Layout layout;
    private final Thread ownerThread;
    private long budgetLease;
    private Object[] objects;
    private int cursor;
    private boolean closed;

    private ReusableObjectSlab(
        LeaseController leases,
        Layout layout,
        long budgetLease,
        Object[] objects
    ) {
        this.leases = leases;
        this.layout = layout;
        this.ownerThread = Thread.currentThread();
        this.budgetLease = budgetLease;
        this.objects = objects;
    }

    /**
     * Creates and fills a completely budgeted slab, or returns {@code null}
     * when the budget rejects it or physical allocation runs out of memory.
     */
    public static <T> ReusableObjectSlab<T> tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        Layout layout,
        IntFunction<? extends T> factory
    ) {
        return tryCreate(
            budgets,
            category,
            layout,
            factory,
            JVM_ARRAY_ALLOCATOR
        );
    }

    static <T> ReusableObjectSlab<T> tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        Layout layout,
        IntFunction<? extends T> factory,
        ObjectArrayAllocator arrayAllocator
    ) {
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(arrayAllocator, "arrayAllocator");

        return tryCreate(
            new ManagerLeaseController(budgets),
            category,
            layout,
            factory,
            arrayAllocator
        );
    }

    static <T> ReusableObjectSlab<T> tryCreate(
        LeaseController leases,
        MemoryCategory category,
        Layout layout,
        IntFunction<? extends T> factory,
        ObjectArrayAllocator arrayAllocator
    ) {
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(arrayAllocator, "arrayAllocator");

        PendingCleanup pending = PENDING_CLEANUP.get();
        pending.retryOrThrow(
            "pending object slab creation cleanup failed"
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
            Object[] created = Objects.requireNonNull(
                arrayAllocator.allocate(layout.capacity()),
                "object slab allocator returned null"
            );
            pending.recordObjects(created);
            if (created.length != layout.capacity()) {
                throw new IllegalArgumentException(
                    "object slab allocator returned the wrong capacity"
                );
            }
            for (int index = 0; index < created.length; index++) {
                created[index] = Objects.requireNonNull(
                    factory.apply(index),
                    "object slab factory returned null at index " + index
                );
            }
            ReusableObjectSlab<T> slab = new ReusableObjectSlab<>(
                leases,
                layout,
                lease,
                created
            );
            pending.transferOwnership();
            return slab;
        } catch (OutOfMemoryError allocationFailure) {
            if (!pending.retry(allocationFailure)) {
                throw allocationFailure;
            }
            return null;
        } catch (RuntimeException | Error creationFailure) {
            pending.retry(creationFailure);
            throw creationFailure;
        }
    }

    /**
     * Retries a failed creation cleanup retained on the current owner thread.
     *
     * <p>This shutdown/reload hook must run before the associated budget
     * manager closes. It is also run before every new slab reservation.</p>
     */
    public static void retryPendingCleanup() {
        PENDING_CLEANUP.get().retryOrThrow(
            "pending object slab creation cleanup failed"
        );
    }

    /**
     * Returns the next stable object, or {@code null} when the fixed slab is
     * exhausted.
     */
    @SuppressWarnings("unchecked")
    public T tryAcquire() {
        this.requireAccessible();
        if (this.cursor == this.layout.capacity()) {
            return null;
        }
        return (T)this.objects[this.cursor++];
    }

    /** Rewinds acquisition to the first stable slot. */
    public void reset() {
        this.requireAccessible();
        this.cursor = 0;
    }

    public int capacity() {
        this.requireAccessible();
        return this.layout.capacity();
    }

    public int used() {
        this.requireAccessible();
        return this.cursor;
    }

    public Layout layout() {
        this.requireAccessible();
        return this.layout;
    }

    /**
     * Releases accounting first. References are cleared only after the
     * budget manager confirms that the lease no longer exists.
     */
    @Override
    public void close() {
        this.requireOwnerThread();
        if (this.closed) {
            return;
        }
        if (!this.leases.release(this.budgetLease)) {
            throw new IllegalStateException(
                "object slab RAM lease could not be released"
            );
        }

        Arrays.fill(this.objects, null);
        this.budgetLease = 0L;
        this.objects = null;
        this.cursor = 0;
        this.closed = true;
    }

    private void requireAccessible() {
        this.requireOwnerThread();
        if (this.closed) {
            throw new IllegalStateException("object slab is closed");
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "object slab accessed from a non-owner thread"
            );
        }
    }

    private static long requestedBytes(
        int capacity,
        long requestedObjectBytes
    ) {
        return Math.addExact(
            requestedObjectBytes,
            Math.multiplyExact(
                (long)capacity,
                CONSERVATIVE_REFERENCE_BYTES
            )
        );
    }

    private static long committedBytes(
        int capacity,
        long committedObjectBytes
    ) {
        long referencePayload = Math.multiplyExact(
            (long)capacity,
            CONSERVATIVE_REFERENCE_BYTES
        );
        long referenceArray = align(
            Math.addExact(
                CONSERVATIVE_ARRAY_HEADER_BYTES,
                referencePayload
            ),
            COMMITTED_ALIGNMENT_BYTES
        );
        return Math.addExact(committedObjectBytes, referenceArray);
    }

    private static long align(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    /**
     * Fixed capacity plus the aggregate logical footprint of all factory
     * objects. Reference-array accounting is added by this layout.
     */
    public record Layout(
        int capacity,
        long requestedObjectBytes,
        long committedObjectBytes
    ) {
        public Layout {
            if (capacity <= 0) {
                throw new IllegalArgumentException(
                    "object slab capacity must be positive"
                );
            }
            if (requestedObjectBytes < 0L) {
                throw new IllegalArgumentException(
                    "requested object bytes must not be negative"
                );
            }
            if (committedObjectBytes < requestedObjectBytes) {
                throw new IllegalArgumentException(
                    "committed object bytes must cover requested bytes"
                );
            }
            long requested = ReusableObjectSlab.requestedBytes(
                capacity,
                requestedObjectBytes
            );
            long committed = ReusableObjectSlab.committedBytes(
                capacity,
                committedObjectBytes
            );
            if (committed < requested) {
                throw new IllegalArgumentException(
                    "committed slab footprint must cover requested bytes"
                );
            }
        }

        public long requestedBytes() {
            return ReusableObjectSlab.requestedBytes(
                this.capacity,
                this.requestedObjectBytes
            );
        }

        public long committedBytes() {
            return ReusableObjectSlab.committedBytes(
                this.capacity,
                this.committedObjectBytes
            );
        }
    }

    @FunctionalInterface
    interface ObjectArrayAllocator {
        Object[] allocate(int capacity);
    }

    interface LeaseController {
        long tryReserve(
            MemoryCategory category,
            long requestedBytes,
            long committedBytes
        );

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
        public boolean release(long token) {
            return this.budgets.release(token);
        }
    }

    /**
     * Owner-thread holder initialized before reservation. It retains both
     * the token and any partially populated backing array until accounting
     * release succeeds.
     */
    private static final class PendingCleanup {
        private final Thread ownerThread = Thread.currentThread();
        private LeaseController leases;
        private long lease;
        private Object[] objects;

        private void prepare(LeaseController preparedLeases) {
            this.requireOwnerThread();
            if (!this.empty()) {
                throw new IllegalStateException(
                    "object slab cleanup owner is already active"
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
                    "object slab cleanup lease was not prepared"
                );
            }
            this.lease = reservedLease;
        }

        private void recordObjects(Object[] createdObjects) {
            this.requireOwnerThread();
            if (this.lease == 0L || this.objects != null) {
                throw new IllegalStateException(
                    "object slab cleanup objects are invalid"
                );
            }
            this.objects = Objects.requireNonNull(
                createdObjects,
                "createdObjects"
            );
        }

        private void transferOwnership() {
            this.requireOwnerThread();
            if (
                this.leases == null
                    || this.lease == 0L
                    || this.objects == null
            ) {
                throw new IllegalStateException(
                    "object slab cleanup ownership is incomplete"
                );
            }
            this.clear();
        }

        private void cancelPreparation() {
            this.requireOwnerThread();
            if (this.lease != 0L || this.objects != null) {
                throw new IllegalStateException(
                    "object slab cleanup reservation is still active"
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

            try {
                if (!this.leases.release(this.lease)) {
                    if (failure != null) {
                        failure.addSuppressed(
                            new IllegalStateException(
                                "object slab RAM lease survived"
                                    + " failed creation"
                            )
                        );
                    }
                    return false;
                }
            } catch (Throwable releaseFailure) {
                if (failure != releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
                return false;
            }

            this.lease = 0L;
            if (this.objects != null) {
                Arrays.fill(this.objects, null);
            }
            this.clear();
            return true;
        }

        private boolean empty() {
            return this.leases == null
                && this.lease == 0L
                && this.objects == null;
        }

        private void clear() {
            this.leases = null;
            this.lease = 0L;
            this.objects = null;
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != this.ownerThread) {
                throw new IllegalStateException(
                    "object slab cleanup retried from a non-owner thread"
                );
            }
        }
    }
}
