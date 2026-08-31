package de.morau.blockframe.core.memory;

import de.morau.blockframe.core.budget.MemoryBudgetManager;
import de.morau.blockframe.core.budget.MemoryCategory;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Fixed owner-thread-confined pool of reusable native buffer blocks.
 *
 * <p>All blocks come from one {@link BudgetedNativeArena} and therefore one
 * RAM lease. A fully constructed, idle owner may register exactly one
 * eviction callback. Byte-buffer views and bookkeeping arrays are created
 * once. Borrow and release operations neither grow the pool nor intentionally
 * allocate. This raw-buffer API is intentionally narrow: borrowing is for
 * owner-thread setup/reload work such as resource upload, not for sharing or
 * retaining a {@link ByteBuffer} across a release. A borrower must finish
 * using the view before {@link #release(long)} and must not use a retained
 * view again after release or close.</p>
 */
public final class ReusableNativeBlockPool implements AutoCloseable {
    public static final long DEFAULT_BLOCK_ALIGNMENT = 64L;

    private static final ThreadLocal<PendingArenaCleanup> PENDING_CLEANUP =
        ThreadLocal.withInitial(PendingArenaCleanup::new);
    private static final PoolMetadataAllocator JVM_METADATA_ALLOCATOR =
        ReusableNativeBlockPool::createMetadata;

    private final Thread ownerThread;
    private final int blockCount;
    private final int blockBytes;
    private BudgetedNativeArena arena;
    private ByteBuffer[] buffers;
    private boolean[] borrowed;
    private int[] generations;
    private int outstandingBorrows;
    private boolean closing;
    private boolean closed;

    private ReusableNativeBlockPool(
        int blockCount,
        int blockBytes,
        BudgetedNativeArena arena,
        PoolMetadata metadata
    ) {
        this.ownerThread = Thread.currentThread();
        this.blockCount = blockCount;
        this.blockBytes = blockBytes;
        this.arena = arena;
        this.buffers = metadata.buffers();
        this.borrowed = metadata.borrowed();
        this.generations = metadata.generations();
    }

    public static ReusableNativeBlockPool tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        int blockCount,
        int blockBytes
    ) {
        return tryCreate(
            budgets,
            category,
            blockCount,
            blockBytes,
            DEFAULT_BLOCK_ALIGNMENT
        );
    }

    public static ReusableNativeBlockPool tryCreate(
        MemoryBudgetManager budgets,
        MemoryCategory category,
        int blockCount,
        int blockBytes,
        long blockAlignment
    ) {
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(category, "category");
        return tryCreate(
            blockCount,
            blockBytes,
            blockAlignment,
            layout -> BudgetedNativeArena.tryCreate(
                budgets,
                category,
                layout
            )
        );
    }

    static ReusableNativeBlockPool tryCreate(
        int blockCount,
        int blockBytes,
        long blockAlignment,
        ArenaAllocator arenaAllocator
    ) {
        return tryCreate(
            blockCount,
            blockBytes,
            blockAlignment,
            arenaAllocator,
            JVM_METADATA_ALLOCATOR
        );
    }

    static ReusableNativeBlockPool tryCreate(
        int blockCount,
        int blockBytes,
        long blockAlignment,
        ArenaAllocator arenaAllocator,
        PoolMetadataAllocator metadataAllocator
    ) {
        Objects.requireNonNull(arenaAllocator, "arenaAllocator");
        Objects.requireNonNull(metadataAllocator, "metadataAllocator");
        if (blockCount <= 0) {
            throw new IllegalArgumentException(
                "native pool block count must be positive"
            );
        }
        if (blockBytes <= 0) {
            throw new IllegalArgumentException(
                "native pool block bytes must be positive"
            );
        }

        long blockStride = align(blockBytes, blockAlignment);
        long requestedBytes = Math.addExact(
            Math.multiplyExact((long)blockCount - 1L, blockStride),
            blockBytes
        );

        retryPendingCleanup();
        PendingArenaCleanup pending = PENDING_CLEANUP.get();
        pending.prepare();
        try {
            BudgetedNativeArena arena = arenaAllocator.tryCreate(
                new BudgetedNativeArena.Layout(
                    requestedBytes,
                    blockAlignment
                )
            );
            if (arena == null) {
                pending.cancelPreparation();
                return null;
            }
            pending.recordArena(arena);

            PoolMetadata metadata = Objects.requireNonNull(
                metadataAllocator.allocate(
                    arena,
                    blockCount,
                    blockBytes,
                    blockAlignment
                ),
                "native pool metadata allocator returned null"
            );
            validateMetadata(metadata, blockCount);
            ReusableNativeBlockPool created =
                new ReusableNativeBlockPool(
                blockCount,
                blockBytes,
                arena,
                metadata
            );
            pending.transferOwnership();
            return created;
        } catch (OutOfMemoryError allocationFailure) {
            if (!pending.hasArena()) {
                pending.cancelPreparation();
                throw allocationFailure;
            }
            if (!pending.retry(allocationFailure)) {
                throw allocationFailure;
            }
            return null;
        } catch (RuntimeException | Error allocationFailure) {
            pending.retry(allocationFailure);
            throw allocationFailure;
        }
    }

    /**
     * Retries current-owner cleanup retained by failed pool or arena
     * creation.
     *
     * <p>This shutdown/reload hook must run before the associated budget
     * manager closes. A failed retry preserves ownership and throws instead
     * of allowing another native reservation.</p>
     */
    public static void retryPendingCleanup() {
        PENDING_CLEANUP.get().retryOrThrow(
            "pending native pool creation cleanup failed"
        );
        BudgetedNativeArena.retryPendingCleanup();
    }

    /**
     * Borrows one block or returns zero when the requirement is too large or
     * every fixed block is already borrowed.
     *
     * <p>The token and its raw view are owner-thread and setup-operation
     * scoped. Release the token in the same operation, normally in a
     * {@code finally} block.</p>
     */
    public long tryBorrow(int requiredBytes) {
        this.requireAccessible();
        if (requiredBytes < 0) {
            throw new IllegalArgumentException(
                "required bytes must not be negative"
            );
        }
        if (requiredBytes > this.blockBytes) {
            return 0L;
        }

        for (int block = 0; block < this.blockCount; block++) {
            if (this.borrowed[block]) {
                continue;
            }
            int generation = this.generations[block] + 1;
            if (generation == 0) {
                generation = 1;
            }
            this.generations[block] = generation;
            this.borrowed[block] = true;
            this.outstandingBorrows++;
            resetView(this.buffers[block], requiredBytes);
            return token(block, generation);
        }
        return 0L;
    }

    /**
     * Returns the stable pre-created view for a live token and resets its
     * position to zero, limit to {@code requiredBytes}, and byte order to
     * {@link ByteOrder#nativeOrder()}.
     *
     * <p>The view must not escape the live borrow. In particular, retaining
     * and using it after {@link #release(long)} is unsupported even though
     * the Java object identity is stable.</p>
     */
    public ByteBuffer buffer(long token, int requiredBytes) {
        this.requireAccessible();
        int block = this.requireBorrowedBlock(token);
        if (requiredBytes < 0 || requiredBytes > this.blockBytes) {
            throw new IllegalArgumentException(
                "required bytes exceed the fixed block"
            );
        }
        ByteBuffer buffer = this.buffers[block];
        resetView(buffer, requiredBytes);
        return buffer;
    }

    public void release(long token) {
        this.requireAccessible();
        int block = this.requireBorrowedBlock(token);
        resetView(this.buffers[block], this.blockBytes);
        this.borrowed[block] = false;
        this.outstandingBorrows--;
    }

    public int blockCount() {
        this.requireAccessible();
        return this.blockCount;
    }

    public int blockBytes() {
        this.requireAccessible();
        return this.blockBytes;
    }

    public int outstandingBorrows() {
        this.requireAccessible();
        return this.outstandingBorrows;
    }

    /**
     * Registers one eviction callback after construction is complete.
     *
     * <p>Registration while borrowed is rejected. The callback itself must
     * execute on this pool's owner thread and call {@link #close()} so native
     * storage is physically closed before its lease is released.</p>
     */
    public boolean registerEvictable(
        MemoryBudgetManager.Evictable evictable
    ) {
        this.requireAccessible();
        if (this.outstandingBorrows != 0) {
            return false;
        }
        return this.arena.registerEvictable(
            Objects.requireNonNull(evictable, "evictable")
        );
    }

    /**
     * Refreshes the lease's LRU timestamp at a setup/reload access boundary.
     */
    public boolean touchLease() {
        this.requireAccessible();
        return this.arena.touchLease();
    }

    /**
     * Rejects close while a block is borrowed. Otherwise closes native
     * storage before discarding the pre-created views and metadata.
     */
    @Override
    public void close() {
        this.requireOwnerThread();
        if (this.closed) {
            return;
        }
        if (!this.closing && this.outstandingBorrows != 0) {
            throw new IllegalStateException(
                "native pool still has outstanding borrows"
            );
        }

        this.closing = true;
        this.arena.close();
        this.arena = null;
        this.buffers = null;
        this.borrowed = null;
        this.generations = null;
        this.closed = true;
        this.closing = false;
    }

    private int requireBorrowedBlock(long token) {
        long encodedBlock = Integer.toUnsignedLong((int)token);
        long blockIndex = encodedBlock - 1L;
        int generation = (int)(token >>> 32);
        if (
            blockIndex < 0L
                || blockIndex >= this.blockCount
                || generation == 0
        ) {
            throw new IllegalStateException(
                "native pool borrow token is invalid or stale"
            );
        }

        int block = (int)blockIndex;
        if (
            !this.borrowed[block]
                || this.generations[block] != generation
        ) {
            throw new IllegalStateException(
                "native pool borrow token is invalid or stale"
            );
        }
        return block;
    }

    private void requireAccessible() {
        this.requireOwnerThread();
        if (this.closed || this.closing) {
            throw new IllegalStateException("native pool is closed");
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException(
                "native pool accessed from a non-owner thread"
            );
        }
    }

    private static void resetView(ByteBuffer buffer, int requiredBytes) {
        buffer.clear();
        buffer.order(ByteOrder.nativeOrder());
        buffer.limit(requiredBytes);
    }

    private static long token(int block, int generation) {
        return (Integer.toUnsignedLong(generation) << 32)
            | Integer.toUnsignedLong(block + 1);
    }

    private static long align(long value, long alignment) {
        if (
            alignment <= 0L
                || (alignment & (alignment - 1L)) != 0L
        ) {
            throw new IllegalArgumentException(
                "block alignment must be a positive power of two"
            );
        }
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    private static PoolMetadata createMetadata(
        BudgetedNativeArena arena,
        int blockCount,
        int blockBytes,
        long blockAlignment
    ) {
        ByteBuffer[] buffers = new ByteBuffer[blockCount];
        for (int block = 0; block < blockCount; block++) {
            MemorySegment segment = arena.claim(
                blockBytes,
                blockAlignment
            );
            if (segment == null) {
                throw new IllegalStateException(
                    "fixed native arena cannot satisfy its pool layout"
                );
            }
            buffers[block] = segment
                .asByteBuffer()
                .order(ByteOrder.nativeOrder());
        }
        return new PoolMetadata(
            buffers,
            new boolean[blockCount],
            new int[blockCount]
        );
    }

    private static void validateMetadata(
        PoolMetadata metadata,
        int blockCount
    ) {
        if (
            metadata.buffers().length != blockCount
                || metadata.borrowed().length != blockCount
                || metadata.generations().length != blockCount
        ) {
            throw new IllegalArgumentException(
                "native pool metadata has the wrong capacity"
            );
        }
        for (int block = 0; block < blockCount; block++) {
            Objects.requireNonNull(
                metadata.buffers()[block],
                "native pool metadata contains a null buffer"
            );
            if (metadata.borrowed()[block]) {
                throw new IllegalArgumentException(
                    "native pool metadata starts borrowed"
                );
            }
        }
    }

    @FunctionalInterface
    interface ArenaAllocator {
        BudgetedNativeArena tryCreate(BudgetedNativeArena.Layout layout);
    }

    @FunctionalInterface
    interface PoolMetadataAllocator {
        PoolMetadata allocate(
            BudgetedNativeArena arena,
            int blockCount,
            int blockBytes,
            long blockAlignment
        );
    }

    record PoolMetadata(
        ByteBuffer[] buffers,
        boolean[] borrowed,
        int[] generations
    ) {
        PoolMetadata {
            Objects.requireNonNull(buffers, "buffers");
            Objects.requireNonNull(borrowed, "borrowed");
            Objects.requireNonNull(generations, "generations");
        }
    }

    /**
     * Owner-thread holder initialized before the nested arena can reserve.
     */
    private static final class PendingArenaCleanup {
        private final Thread ownerThread = Thread.currentThread();
        private BudgetedNativeArena arena;
        private boolean prepared;

        private void prepare() {
            this.requireOwnerThread();
            if (this.prepared || this.arena != null) {
                throw new IllegalStateException(
                    "native pool cleanup owner is already active"
                );
            }
            this.prepared = true;
        }

        private void recordArena(BudgetedNativeArena createdArena) {
            this.requireOwnerThread();
            if (!this.prepared || this.arena != null) {
                throw new IllegalStateException(
                    "native pool cleanup owner was not prepared"
                );
            }
            this.arena = Objects.requireNonNull(
                createdArena,
                "createdArena"
            );
        }

        private boolean hasArena() {
            this.requireOwnerThread();
            return this.arena != null;
        }

        private void transferOwnership() {
            this.requireOwnerThread();
            if (!this.prepared || this.arena == null) {
                throw new IllegalStateException(
                    "native pool cleanup ownership is incomplete"
                );
            }
            this.arena = null;
            this.prepared = false;
        }

        private void cancelPreparation() {
            this.requireOwnerThread();
            if (this.arena != null) {
                throw new IllegalStateException(
                    "native pool cleanup still owns an arena"
                );
            }
            this.prepared = false;
        }

        private void retryOrThrow(String message) {
            this.requireOwnerThread();
            if (this.arena == null) {
                this.prepared = false;
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
            if (this.arena == null) {
                this.prepared = false;
                return true;
            }
            try {
                this.arena.close();
            } catch (Throwable closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                return false;
            }
            this.arena = null;
            this.prepared = false;
            return true;
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != this.ownerThread) {
                throw new IllegalStateException(
                    "native pool cleanup retried from a non-owner thread"
                );
            }
        }
    }
}
