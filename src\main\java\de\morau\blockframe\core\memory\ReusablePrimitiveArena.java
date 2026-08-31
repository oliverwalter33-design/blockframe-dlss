package de.morau.blockframe.core.memory;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.budget.MemoryKind;
import java.util.Arrays;
import java.util.Objects;

/**
 * Fixed, render-thread-confined primitive scratch storage.
 *
 * <p>The arena reserves its complete conservative RAM footprint before any
 * backing array is allocated. Claims return element offsets into stable
 * arrays and never grow the storage. Byte alignment is relative to the start
 * of the selected primitive array.</p>
 */
public final class ReusablePrimitiveArena implements AutoCloseable {
    static final long CONSERVATIVE_ARRAY_HEADER_BYTES = 16L;
    static final long COMMITTED_ALIGNMENT_BYTES = 64L;
    static final long CONSERVATIVE_REFERENCE_BYTES = Long.BYTES;

    private static final ArrayAllocator JVM_ARRAY_ALLOCATOR =
        new ArrayAllocator() {
            @Override
            public byte[] bytes(int capacity) {
                return new byte[capacity];
            }

            @Override
            public int[] ints(int capacity) {
                return new int[capacity];
            }

            @Override
            public float[] floats(int capacity) {
                return new float[capacity];
            }

            @Override
            public double[] doubles(int capacity) {
                return new double[capacity];
            }

            @Override
            public Object[] references(int capacity) {
                return new Object[capacity];
            }
        };

    private final MemoryBudgetManager budgets;
    private final Layout layout;
    private final Thread ownerThread;
    private long budgetLease;
    private byte[] byteStorage;
    private int[] intStorage;
    private float[] floatStorage;
    private double[] doubleStorage;
    private Object[] referenceStorage;
    private int byteCursor;
    private int intCursor;
    private int floatCursor;
    private int doubleCursor;
    private int referenceCursor;
    private boolean closed;

    private ReusablePrimitiveArena(
        MemoryBudgetManager budgets,
        Layout layout,
        long budgetLease,
        byte[] byteStorage,
        int[] intStorage,
        float[] floatStorage,
        double[] doubleStorage,
        Object[] referenceStorage
    ) {
        this.budgets = budgets;
        this.layout = layout;
        this.ownerThread = Thread.currentThread();
        this.budgetLease = budgetLease;
        this.byteStorage = byteStorage;
        this.intStorage = intStorage;
        this.floatStorage = floatStorage;
        this.doubleStorage = doubleStorage;
        this.referenceStorage = referenceStorage;
    }

    /**
     * Creates a fully budgeted arena, or returns {@code null} when the budget
     * rejects it or the JVM cannot allocate all backing arrays.
     */
    public static ReusablePrimitiveArena tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        Layout layout
    ) {
        return tryCreate(budgets, category, layout, JVM_ARRAY_ALLOCATOR);
    }

    static ReusablePrimitiveArena tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        Layout layout,
        ArrayAllocator allocator
    ) {
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(allocator, "allocator");

        long lease = budgets.tryReserve(
            MemoryKind.RAM,
            category,
            layout.requestedBytes(),
            layout.committedBytes(),
            null
        );
        if (lease == 0L) {
            return null;
        }

        try {
            byte[] bytes = allocator.bytes(layout.byteCapacity());
            int[] ints = allocator.ints(layout.intCapacity());
            float[] floats = allocator.floats(layout.floatCapacity());
            double[] doubles = allocator.doubles(layout.doubleCapacity());
            Object[] references = allocator.references(
                layout.referenceCapacity()
            );
            return new ReusablePrimitiveArena(
                budgets,
                layout,
                lease,
                bytes,
                ints,
                floats,
                doubles,
                references
            );
        } catch (OutOfMemoryError allocationFailure) {
            releaseFailedAllocation(budgets, lease, allocationFailure);
            return null;
        } catch (RuntimeException | Error allocationFailure) {
            releaseFailedAllocation(budgets, lease, allocationFailure);
            throw allocationFailure;
        }
    }

    public int claimBytes(int count, int byteAlignment) {
        this.requireAccessible();
        int offset = claimOffset(
            this.byteCursor,
            this.layout.byteCapacity(),
            count,
            Byte.BYTES,
            byteAlignment
        );
        if (offset >= 0) {
            this.byteCursor = Math.addExact(offset, count);
        }
        return offset;
    }

    public int claimInts(int count, int byteAlignment) {
        this.requireAccessible();
        int offset = claimOffset(
            this.intCursor,
            this.layout.intCapacity(),
            count,
            Integer.BYTES,
            byteAlignment
        );
        if (offset >= 0) {
            this.intCursor = Math.addExact(offset, count);
        }
        return offset;
    }

    public int claimFloats(int count, int byteAlignment) {
        this.requireAccessible();
        int offset = claimOffset(
            this.floatCursor,
            this.layout.floatCapacity(),
            count,
            Float.BYTES,
            byteAlignment
        );
        if (offset >= 0) {
            this.floatCursor = Math.addExact(offset, count);
        }
        return offset;
    }

    public int claimDoubles(int count, int byteAlignment) {
        this.requireAccessible();
        int offset = claimOffset(
            this.doubleCursor,
            this.layout.doubleCapacity(),
            count,
            Double.BYTES,
            byteAlignment
        );
        if (offset >= 0) {
            this.doubleCursor = Math.addExact(offset, count);
        }
        return offset;
    }

    public int claimReferences(int count, int byteAlignment) {
        this.requireAccessible();
        int offset = claimOffset(
            this.referenceCursor,
            this.layout.referenceCapacity(),
            count,
            CONSERVATIVE_REFERENCE_BYTES,
            byteAlignment
        );
        if (offset >= 0) {
            this.referenceCursor = Math.addExact(offset, count);
        }
        return offset;
    }

    public byte[] bytes() {
        this.requireAccessible();
        return this.byteStorage;
    }

    public int[] ints() {
        this.requireAccessible();
        return this.intStorage;
    }

    public float[] floats() {
        this.requireAccessible();
        return this.floatStorage;
    }

    public double[] doubles() {
        this.requireAccessible();
        return this.doubleStorage;
    }

    public Object[] references() {
        this.requireAccessible();
        return this.referenceStorage;
    }

    /**
     * Rewinds every primitive cursor and drops references stored in the
     * claimed reference range.
     */
    public void reset() {
        this.requireAccessible();
        Arrays.fill(
            this.referenceStorage,
            0,
            this.referenceCursor,
            null
        );
        this.byteCursor = 0;
        this.intCursor = 0;
        this.floatCursor = 0;
        this.doubleCursor = 0;
        this.referenceCursor = 0;
    }

    @Override
    public void close() {
        this.requireOwnerThread();
        if (this.closed) {
            return;
        }
        if (!this.budgets.release(this.budgetLease)) {
            throw new IllegalStateException(
                "primitive arena RAM lease could not be released"
            );
        }

        Arrays.fill(this.referenceStorage, null);
        this.budgetLease = 0L;
        this.byteStorage = null;
        this.intStorage = null;
        this.floatStorage = null;
        this.doubleStorage = null;
        this.referenceStorage = null;
        this.byteCursor = 0;
        this.intCursor = 0;
        this.floatCursor = 0;
        this.doubleCursor = 0;
        this.referenceCursor = 0;
        this.closed = true;
    }

    private void requireAccessible() {
        this.requireOwnerThread();
        if (this.closed) {
            throw new IllegalStateException("primitive arena is closed");
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "primitive arena accessed from a non-owner thread"
            );
        }
    }

    private static int claimOffset(
        int cursor,
        int capacity,
        int count,
        long elementBytes,
        int byteAlignment
    ) {
        if (count < 0) {
            throw new IllegalArgumentException("claim count must not be negative");
        }
        requireAlignment(byteAlignment);

        long cursorBytes = Math.multiplyExact((long)cursor, elementBytes);
        long alignedBytes = align(
            cursorBytes,
            Integer.toUnsignedLong(byteAlignment)
        );
        long offset = alignedBytes / elementBytes;
        long end = Math.addExact(offset, count);
        if (end > capacity) {
            return -1;
        }
        return Math.toIntExact(offset);
    }

    private static void requireAlignment(int byteAlignment) {
        if (
            byteAlignment <= 0
                || (byteAlignment & (byteAlignment - 1)) != 0
        ) {
            throw new IllegalArgumentException(
                "byte alignment must be a positive power of two"
            );
        }
    }

    private static void releaseFailedAllocation(
        MemoryBudgetManager budgets,
        long lease,
        Throwable allocationFailure
    ) {
        if (!budgets.release(lease)) {
            throw new IllegalStateException(
                "primitive arena RAM lease survived failed allocation",
                allocationFailure
            );
        }
    }

    private static long align(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    private static long requestedBytes(
        int byteCapacity,
        int intCapacity,
        int floatCapacity,
        int doubleCapacity,
        int referenceCapacity
    ) {
        long requested = byteCapacity;
        requested = Math.addExact(
            requested,
            Math.multiplyExact((long)intCapacity, Integer.BYTES)
        );
        requested = Math.addExact(
            requested,
            Math.multiplyExact((long)floatCapacity, Float.BYTES)
        );
        requested = Math.addExact(
            requested,
            Math.multiplyExact((long)doubleCapacity, Double.BYTES)
        );
        return Math.addExact(
            requested,
            Math.multiplyExact(
                (long)referenceCapacity,
                CONSERVATIVE_REFERENCE_BYTES
            )
        );
    }

    private static long committedBytes(
        int byteCapacity,
        int intCapacity,
        int floatCapacity,
        int doubleCapacity,
        int referenceCapacity
    ) {
        long committed = committedArrayBytes(byteCapacity, Byte.BYTES);
        committed = Math.addExact(
            committed,
            committedArrayBytes(intCapacity, Integer.BYTES)
        );
        committed = Math.addExact(
            committed,
            committedArrayBytes(floatCapacity, Float.BYTES)
        );
        committed = Math.addExact(
            committed,
            committedArrayBytes(doubleCapacity, Double.BYTES)
        );
        return Math.addExact(
            committed,
            committedArrayBytes(
                referenceCapacity,
                CONSERVATIVE_REFERENCE_BYTES
            )
        );
    }

    private static long committedArrayBytes(
        int capacity,
        long elementBytes
    ) {
        long payload = Math.multiplyExact((long)capacity, elementBytes);
        return align(
            Math.addExact(CONSERVATIVE_ARRAY_HEADER_BYTES, payload),
            COMMITTED_ALIGNMENT_BYTES
        );
    }

    /**
     * Fixed element capacities and their conservative logical RAM footprint.
     */
    public record Layout(
        int byteCapacity,
        int intCapacity,
        int floatCapacity,
        int doubleCapacity,
        int referenceCapacity
    ) {
        public Layout {
            if (
                byteCapacity < 0
                    || intCapacity < 0
                    || floatCapacity < 0
                    || doubleCapacity < 0
                    || referenceCapacity < 0
            ) {
                throw new IllegalArgumentException(
                    "primitive arena capacities must not be negative"
                );
            }
            if (
                ReusablePrimitiveArena.requestedBytes(
                    byteCapacity,
                    intCapacity,
                    floatCapacity,
                    doubleCapacity,
                    referenceCapacity
                ) == 0L
            ) {
                throw new IllegalArgumentException(
                    "primitive arena needs a positive payload"
                );
            }
            ReusablePrimitiveArena.committedBytes(
                byteCapacity,
                intCapacity,
                floatCapacity,
                doubleCapacity,
                referenceCapacity
            );
        }

        public long requestedBytes() {
            return ReusablePrimitiveArena.requestedBytes(
                this.byteCapacity,
                this.intCapacity,
                this.floatCapacity,
                this.doubleCapacity,
                this.referenceCapacity
            );
        }

        public long committedBytes() {
            return ReusablePrimitiveArena.committedBytes(
                this.byteCapacity,
                this.intCapacity,
                this.floatCapacity,
                this.doubleCapacity,
                this.referenceCapacity
            );
        }
    }

    interface ArrayAllocator {
        byte[] bytes(int capacity);

        int[] ints(int capacity);

        float[] floats(int capacity);

        double[] doubles(int capacity);

        Object[] references(int capacity);
    }
}
