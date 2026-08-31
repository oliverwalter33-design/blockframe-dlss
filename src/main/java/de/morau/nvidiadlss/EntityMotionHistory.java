package de.morau.nvidiadlss;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import de.morau.blockframe.core.memory.BudgetedNativeArena;
import de.morau.blockframe.core.memory.ReusablePrimitiveArena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * Fixed-capacity, double-buffered entity-motion history.
 *
 * <p>Production uses the established fixed heap arena by default. Native
 * storage is available only through the explicit experimental preference and
 * falls back to the heap arena if its budget or allocation fails. If the
 * selected fixed storage cannot be created, the caller retains its legacy
 * map/list collector. Entity IDs are stored in two open-addressed tables so
 * the current frame can be built without mutating the previous frame.
 * Capacity overflow is reported to the caller and never drops an already
 * accepted entry silently.</p>
 */
public final class EntityMotionHistory implements AutoCloseable {
    public static final int VALUES_PER_ENTITY = 4;

    private static final int TABLE_COUNT = 2;
    private static final long NATIVE_ALIGNMENT = 64L;
    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;
    private static final int YAW = 3;
    private static final ThreadLocal<BudgetedNativeArena>
        RETAINED_FAILED_NATIVE_CREATION = new ThreadLocal<>();

    private final Storage storage;
    private final int capacity;
    private final int mask;
    private final int maxEntries;
    private final int keyOffset;
    private final int stampOffset;
    private final int valueOffset;
    private int tableZeroEpoch;
    private int tableOneEpoch;
    private int currentTable;
    private int previousTable = -1;
    private int currentSize;
    private int previousLookup = -1;
    private boolean initialized;
    private boolean closed;

    private EntityMotionHistory(
        Storage storage,
        int capacity,
        int keyOffset,
        int stampOffset,
        int valueOffset
    ) {
        this.storage = storage;
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.maxEntries = Math.multiplyExact(capacity, 3) / 4;
        this.keyOffset = keyOffset;
        this.stampOffset = stampOffset;
        this.valueOffset = valueOffset;
    }

    /**
     * Creates the standard heap-backed entity history, or returns
     * {@code null} when its footprint cannot be reserved and allocated.
     */
    public static EntityMotionHistory tryCreate(
        MemoryBudgetManager budgets,
        int capacity
    ) {
        return tryCreate(
            budgets,
            capacity,
            BackendPreference.HEAP
        );
    }

    /**
     * Creates history with one explicit startup-bound backend preference.
     * Experimental native failure falls back to heap; heap-standard failure
     * returns {@code null} so the caller can retain the legacy collector.
     */
    public static EntityMotionHistory tryCreate(
        MemoryBudgetManager budgets,
        int capacity,
        BackendPreference preference
    ) {
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(preference, "preference");
        retryPendingCleanup();
        HistoryLayout layout = HistoryLayout.create(capacity);
        if (preference == BackendPreference.HEAP) {
            return tryCreateHeap(budgets, layout);
        }
        EntityMotionHistory nativeHistory = tryCreateNative(
            budgets,
            layout,
            BudgetedNativeArena::claim
        );
        if (nativeHistory != null) {
            return nativeHistory;
        }
        return tryCreateHeap(budgets, layout);
    }

    static EntityMotionHistory tryCreateExperimentalNative(
        MemoryBudgetManager budgets,
        int capacity,
        NativeStorageClaimer storageClaimer
    ) {
        Objects.requireNonNull(budgets, "budgets");
        retryPendingCleanup();
        HistoryLayout layout = HistoryLayout.create(capacity);
        EntityMotionHistory nativeHistory = tryCreateNative(
            budgets,
            layout,
            storageClaimer
        );
        return nativeHistory != null
            ? nativeHistory
            : tryCreateHeap(budgets, layout);
    }

    static EntityMotionHistory tryCreateNative(
        MemoryBudgetManager budgets,
        int capacity
    ) {
        Objects.requireNonNull(budgets, "budgets");
        retryPendingCleanup();
        return tryCreateNative(
            budgets,
            HistoryLayout.create(capacity),
            BudgetedNativeArena::claim
        );
    }

    static EntityMotionHistory tryCreateNative(
        MemoryBudgetManager budgets,
        int capacity,
        NativeStorageClaimer storageClaimer
    ) {
        Objects.requireNonNull(budgets, "budgets");
        retryPendingCleanup();
        return tryCreateNative(
            budgets,
            HistoryLayout.create(capacity),
            storageClaimer
        );
    }

    static EntityMotionHistory tryCreateHeap(
        MemoryBudgetManager budgets,
        int capacity
    ) {
        Objects.requireNonNull(budgets, "budgets");
        return tryCreateHeap(
            budgets,
            HistoryLayout.create(capacity)
        );
    }

    public static long nativeRequestedBytes(int capacity) {
        return HistoryLayout.create(capacity).nativeRequestedBytes();
    }

    public static long nativeCommittedBytes(int capacity) {
        return HistoryLayout.create(capacity).nativeCommittedBytes();
    }

    public StorageKind storageKind() {
        this.requireAccessible();
        return this.storage.kind();
    }

    public long requestedBytes() {
        this.requireAccessible();
        return this.storage.requestedBytes();
    }

    public long committedBytes() {
        this.requireAccessible();
        return this.storage.committedBytes();
    }

    /**
     * Retries ownership retained by an otherwise unreachable failed native
     * construction on the creating thread.
     */
    public static void retryPendingCleanup() {
        BudgetedNativeArena retained =
            RETAINED_FAILED_NATIVE_CREATION.get();
        if (retained != null) {
            retained.close();
            RETAINED_FAILED_NATIVE_CREATION.remove();
        }
        BudgetedNativeArena.retryPendingCleanup();
    }

    static boolean hasPendingCleanup() {
        return RETAINED_FAILED_NATIVE_CREATION.get() != null;
    }

    private static EntityMotionHistory tryCreateNative(
        MemoryBudgetManager budgets,
        HistoryLayout layout,
        NativeStorageClaimer storageClaimer
    ) {
        Objects.requireNonNull(storageClaimer, "storageClaimer");
        BudgetedNativeArena arena = BudgetedNativeArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            new BudgetedNativeArena.Layout(
                layout.nativeRequestedBytes(),
                NATIVE_ALIGNMENT
            )
        );
        if (arena == null) {
            return null;
        }

        try {
            MemorySegment segment = Objects.requireNonNull(
                storageClaimer.claim(
                    arena,
                    layout.nativeRequestedBytes(),
                    Long.BYTES
                ),
                "native entity history arena returned no fixed storage"
            );
            if (segment.byteSize() != layout.nativeRequestedBytes()) {
                throw new IllegalStateException(
                    "native entity history storage has unexpected size"
                );
            }
            return new EntityMotionHistory(
                new NativeStorage(
                    arena,
                    segment,
                    layout.intCount(),
                    layout.doubleCount(),
                    layout.nativeRequestedBytes(),
                    layout.nativeCommittedBytes()
                ),
                layout.capacity(),
                0,
                layout.tableEntries(),
                0
            );
        } catch (OutOfMemoryError allocationFailure) {
            if (!retainOrCloseFailedNativeCreation(
                arena,
                allocationFailure
            )) {
                throw allocationFailure;
            }
            return null;
        } catch (RuntimeException | Error creationFailure) {
            retainOrCloseFailedNativeCreation(arena, creationFailure);
            throw creationFailure;
        }
    }

    private static EntityMotionHistory tryCreateHeap(
        MemoryBudgetManager budgets,
        HistoryLayout layout
    ) {
        ReusablePrimitiveArena.Layout arenaLayout =
            new ReusablePrimitiveArena.Layout(
                0,
                layout.intCount(),
                0,
                layout.doubleCount(),
                0
            );
        ReusablePrimitiveArena arena = ReusablePrimitiveArena.tryCreate(
            budgets,
            MemoryCategory.ENTITIES,
            arenaLayout
        );
        if (arena == null) {
            return null;
        }

        try {
            int keyOffset = arena.claimInts(
                layout.tableEntries(),
                Integer.BYTES
            );
            int stampOffset = arena.claimInts(
                layout.tableEntries(),
                Integer.BYTES
            );
            int valueOffset = arena.claimDoubles(
                layout.doubleCount(),
                Double.BYTES
            );
            if (
                keyOffset < 0
                    || stampOffset < 0
                    || valueOffset < 0
            ) {
                throw new IllegalStateException(
                    "entity history arena does not cover its declared layout"
                );
            }
            return new EntityMotionHistory(
                new HeapStorage(
                    arena,
                    arenaLayout.requestedBytes(),
                    arenaLayout.committedBytes()
                ),
                layout.capacity(),
                keyOffset,
                stampOffset,
                valueOffset
            );
        } catch (OutOfMemoryError allocationFailure) {
            arena.close();
            return null;
        } catch (RuntimeException | Error creationFailure) {
            arena.close();
            throw creationFailure;
        }
    }

    /**
     * Rotates the complete current table into the previous-frame role and
     * starts an empty current table.
     */
    public void beginFrame() {
        this.requireAccessible();
        this.previousLookup = -1;
        this.currentSize = 0;
        if (!this.initialized) {
            this.initialized = true;
            this.previousTable = -1;
            this.currentTable = 0;
        } else {
            this.previousTable = this.currentTable;
            this.currentTable ^= 1;
        }
        this.advanceCurrentEpoch();
    }

    /**
     * Finds one ID in the immutable previous-frame table. Successful lookup
     * values remain available through the primitive {@code previous*}
     * accessors until the next lookup or frame rotation.
     */
    public boolean findPrevious(int entityId) {
        this.requireAccessible();
        this.previousLookup = -1;
        if (this.previousTable < 0) {
            return false;
        }

        int tableBase = this.previousTable * this.capacity;
        int epoch = this.epoch(this.previousTable);
        int slot = mix(entityId) & this.mask;
        for (int probe = 0; probe < this.capacity; probe++) {
            int tableIndex = tableBase + slot;
            if (
                this.storage.getInt(
                    this.stampOffset + tableIndex
                ) != epoch
            ) {
                return false;
            }
            if (
                this.storage.getInt(
                    this.keyOffset + tableIndex
                ) == entityId
            ) {
                this.previousLookup =
                    this.valueOffset
                        + tableIndex * VALUES_PER_ENTITY;
                return true;
            }
            slot = slot + 1 & this.mask;
        }
        return false;
    }

    /**
     * Adds or replaces one current-frame entry. Returns {@code false} before
     * inserting a new ID when the bounded 75-percent load limit is reached.
     */
    public boolean putCurrent(
        int entityId,
        double x,
        double y,
        double z,
        float yaw
    ) {
        this.requireAccessible();
        this.requireFrame();

        int tableBase = this.currentTable * this.capacity;
        int epoch = this.epoch(this.currentTable);
        int slot = mix(entityId) & this.mask;
        for (int probe = 0; probe < this.capacity; probe++) {
            int tableIndex = tableBase + slot;
            int stampIndex = this.stampOffset + tableIndex;
            if (this.storage.getInt(stampIndex) != epoch) {
                if (this.currentSize == this.maxEntries) {
                    return false;
                }
                this.storage.setInt(stampIndex, epoch);
                this.storage.setInt(
                    this.keyOffset + tableIndex,
                    entityId
                );
                this.writeValues(tableIndex, x, y, z, yaw);
                this.currentSize++;
                return true;
            }
            if (
                this.storage.getInt(
                    this.keyOffset + tableIndex
                ) == entityId
            ) {
                this.writeValues(tableIndex, x, y, z, yaw);
                return true;
            }
            slot = slot + 1 & this.mask;
        }
        return false;
    }

    public double previousX() {
        return this.previousValue(X);
    }

    public double previousY() {
        return this.previousValue(Y);
    }

    public double previousZ() {
        return this.previousValue(Z);
    }

    public float previousYaw() {
        return (float)this.previousValue(YAW);
    }

    public int currentSize() {
        this.requireAccessible();
        return this.currentSize;
    }

    public int maxEntries() {
        this.requireAccessible();
        return this.maxEntries;
    }

    public int capacity() {
        this.requireAccessible();
        return this.capacity;
    }

    /**
     * Invalidates both logical frames without clearing the full backing
     * arrays. Their epochs are advanced when each table is reused.
     */
    public void clear() {
        this.requireAccessible();
        this.initialized = false;
        this.currentTable = 0;
        this.previousTable = -1;
        this.currentSize = 0;
        this.previousLookup = -1;
    }

    @Override
    public void close() {
        this.storage.close();
        this.initialized = false;
        this.currentTable = 0;
        this.previousTable = -1;
        this.currentSize = 0;
        this.previousLookup = -1;
        this.closed = true;
    }

    private void writeValues(
        int tableIndex,
        double x,
        double y,
        double z,
        float yaw
    ) {
        int base =
            this.valueOffset
                + tableIndex * VALUES_PER_ENTITY;
        this.storage.setDouble(base + X, x);
        this.storage.setDouble(base + Y, y);
        this.storage.setDouble(base + Z, z);
        this.storage.setDouble(base + YAW, yaw);
    }

    private double previousValue(int field) {
        this.requireAccessible();
        if (this.previousLookup < 0) {
            throw new IllegalStateException(
                "no successful previous-frame lookup is active"
            );
        }
        return this.storage.getDouble(
            this.previousLookup + field
        );
    }

    private void requireFrame() {
        if (!this.initialized) {
            throw new IllegalStateException(
                "beginFrame must be called before inserting history"
            );
        }
    }

    private int epoch(int table) {
        return table == 0
            ? this.tableZeroEpoch
            : this.tableOneEpoch;
    }

    private void advanceCurrentEpoch() {
        if (this.currentTable == 0) {
            this.tableZeroEpoch = this.nextEpoch(
                this.tableZeroEpoch,
                0
            );
        } else {
            this.tableOneEpoch = this.nextEpoch(
                this.tableOneEpoch,
                this.capacity
            );
        }
    }

    private int nextEpoch(int epoch, int tableBase) {
        int next = epoch + 1;
        if (next != 0) {
            return next;
        }
        this.storage.clearInts(
            this.stampOffset + tableBase,
            this.stampOffset + tableBase + this.capacity
        );
        return 1;
    }

    private void requireAccessible() {
        if (this.closed) {
            throw new IllegalStateException(
                "entity motion history is closed"
            );
        }
        this.storage.ensureAccessible();
    }

    private static boolean retainOrCloseFailedNativeCreation(
        BudgetedNativeArena arena,
        Throwable failure
    ) {
        try {
            arena.close();
            return true;
        } catch (Throwable cleanupFailure) {
            RETAINED_FAILED_NATIVE_CREATION.set(arena);
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            return false;
        }
    }

    private static int mix(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7FEB352D;
        mixed ^= mixed >>> 15;
        mixed *= 0x846CA68B;
        return mixed ^ mixed >>> 16;
    }

    public enum StorageKind {
        NATIVE,
        HEAP
    }

    public enum BackendPreference {
        HEAP("heap"),
        NATIVE_EXPERIMENTAL("native-experimental");

        private final String id;

        BackendPreference(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public static BackendPreference byId(String id) {
            if (NATIVE_EXPERIMENTAL.id.equalsIgnoreCase(id)) {
                return NATIVE_EXPERIMENTAL;
            }
            return HEAP;
        }
    }

    @FunctionalInterface
    interface NativeStorageClaimer {
        MemorySegment claim(
            BudgetedNativeArena arena,
            long byteCount,
            long byteAlignment
        );
    }

    private interface Storage extends AutoCloseable {
        int getInt(int index);

        void setInt(int index, int value);

        double getDouble(int index);

        void setDouble(int index, double value);

        void clearInts(int fromIndex, int toIndex);

        void ensureAccessible();

        StorageKind kind();

        long requestedBytes();

        long committedBytes();

        @Override
        void close();
    }

    private static final class NativeStorage implements Storage {
        private final BudgetedNativeArena arena;
        private final int intCount;
        private final long requestedBytes;
        private final long committedBytes;
        private MemorySegment intStorage;
        private MemorySegment doubleStorage;

        private NativeStorage(
            BudgetedNativeArena arena,
            MemorySegment storage,
            int intCount,
            int doubleCount,
            long requestedBytes,
            long committedBytes
        ) {
            this.arena = arena;
            this.intCount = intCount;
            long intBytes = Math.multiplyExact(
                (long)intCount,
                Integer.BYTES
            );
            long doubleBytes = Math.multiplyExact(
                (long)doubleCount,
                Double.BYTES
            );
            this.intStorage = storage.asSlice(0L, intBytes);
            this.doubleStorage = storage.asSlice(
                intBytes,
                doubleBytes
            );
            this.requestedBytes = requestedBytes;
            this.committedBytes = committedBytes;
        }

        @Override
        public int getInt(int index) {
            return this.intStorage.getAtIndex(
                ValueLayout.JAVA_INT,
                index
            );
        }

        @Override
        public void setInt(int index, int value) {
            this.intStorage.setAtIndex(
                ValueLayout.JAVA_INT,
                index,
                value
            );
        }

        @Override
        public double getDouble(int index) {
            return this.doubleStorage.getAtIndex(
                ValueLayout.JAVA_DOUBLE,
                index
            );
        }

        @Override
        public void setDouble(int index, double value) {
            this.doubleStorage.setAtIndex(
                ValueLayout.JAVA_DOUBLE,
                index,
                value
            );
        }

        @Override
        public void clearInts(int fromIndex, int toIndex) {
            if (
                fromIndex < 0
                    || toIndex < fromIndex
                    || toIndex > this.intCount
            ) {
                throw new IndexOutOfBoundsException(
                    "native int clear range is out of bounds"
                );
            }
            long byteOffset = (long)fromIndex * Integer.BYTES;
            long byteCount =
                (long)(toIndex - fromIndex) * Integer.BYTES;
            this.intStorage.asSlice(
                byteOffset,
                byteCount
            ).fill((byte)0);
        }

        @Override
        public void ensureAccessible() {
            this.arena.capacityBytes();
            if (
                this.intStorage == null
                    || this.doubleStorage == null
            ) {
                throw new IllegalStateException(
                    "native entity history storage is closed"
                );
            }
        }

        @Override
        public StorageKind kind() {
            return StorageKind.NATIVE;
        }

        @Override
        public long requestedBytes() {
            return this.requestedBytes;
        }

        @Override
        public long committedBytes() {
            return this.committedBytes;
        }

        @Override
        public void close() {
            this.arena.close();
            this.intStorage = null;
            this.doubleStorage = null;
        }
    }

    private static final class HeapStorage implements Storage {
        private final ReusablePrimitiveArena arena;
        private final long requestedBytes;
        private final long committedBytes;
        private int[] ints;
        private double[] doubles;

        private HeapStorage(
            ReusablePrimitiveArena arena,
            long requestedBytes,
            long committedBytes
        ) {
            this.arena = arena;
            this.ints = arena.ints();
            this.doubles = arena.doubles();
            this.requestedBytes = requestedBytes;
            this.committedBytes = committedBytes;
        }

        @Override
        public int getInt(int index) {
            return this.ints[index];
        }

        @Override
        public void setInt(int index, int value) {
            this.ints[index] = value;
        }

        @Override
        public double getDouble(int index) {
            return this.doubles[index];
        }

        @Override
        public void setDouble(int index, double value) {
            this.doubles[index] = value;
        }

        @Override
        public void clearInts(int fromIndex, int toIndex) {
            Arrays.fill(this.ints, fromIndex, toIndex, 0);
        }

        @Override
        public void ensureAccessible() {
            this.arena.ints();
            if (this.ints == null || this.doubles == null) {
                throw new IllegalStateException(
                    "heap entity history storage is closed"
                );
            }
        }

        @Override
        public StorageKind kind() {
            return StorageKind.HEAP;
        }

        @Override
        public long requestedBytes() {
            return this.requestedBytes;
        }

        @Override
        public long committedBytes() {
            return this.committedBytes;
        }

        @Override
        public void close() {
            this.arena.close();
            this.ints = null;
            this.doubles = null;
        }
    }

    private record HistoryLayout(
        int capacity,
        int tableEntries,
        int intCount,
        int doubleCount,
        long nativeRequestedBytes,
        long nativeCommittedBytes
    ) {
        private static HistoryLayout create(int capacity) {
            if (
                capacity < 2
                    || (capacity & (capacity - 1)) != 0
            ) {
                throw new IllegalArgumentException(
                    "history capacity must be a power of two of at least two"
                );
            }
            int tableEntries = Math.multiplyExact(
                capacity,
                TABLE_COUNT
            );
            int intCount = Math.multiplyExact(tableEntries, 2);
            int doubleCount = Math.multiplyExact(
                tableEntries,
                VALUES_PER_ENTITY
            );
            long nativeRequestedBytes = Math.addExact(
                Math.multiplyExact(
                    (long)intCount,
                    Integer.BYTES
                ),
                Math.multiplyExact(
                    (long)doubleCount,
                    Double.BYTES
                )
            );
            long nativeCommittedBytes =
                new BudgetedNativeArena.Layout(
                    nativeRequestedBytes,
                    NATIVE_ALIGNMENT
                ).committedBytes();
            return new HistoryLayout(
                capacity,
                tableEntries,
                intCount,
                doubleCount,
                nativeRequestedBytes,
                nativeCommittedBytes
            );
        }
    }
}
