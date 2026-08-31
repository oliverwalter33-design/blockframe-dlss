package de.morau.blockframe.core.budget;

import java.util.Arrays;
import java.util.Objects;

/**
 * Central logical RAM/VRAM accounting for BlockFrame-owned resources.
 *
 * <p>The fixed lease table lets successful no-eviction steady-state
 * reserve/touch/pin/release operations avoid intentional allocation.
 * Eviction selection and callbacks are outside that claim. LRU callbacks may
 * only release BlockFrame-owned resources and are never considered while a
 * lease is pinned or waiting for GPU retirement.</p>
 */
public final class MemoryBudgetManager implements AutoCloseable {
    public static final int MAX_RESERVATIONS = 256;
    private static final int RETIREMENT_FRAMES = 3;
    private static final MemoryKind[] KINDS = MemoryKind.values();
    private static final MemoryCategory[] CATEGORIES =
        MemoryCategory.values();
    private static final int CATEGORY_COUNT = CATEGORIES.length;

    @FunctionalInterface
    public interface Evictable {
        /**
         * Releases the associated physical resource synchronously.
         *
         * @return true only when the resource no longer owns its lease
         */
        boolean evict();
    }

    private final boolean[] active = new boolean[MAX_RESERVATIONS];
    private final boolean[] retiring = new boolean[MAX_RESERVATIONS];
    private final boolean[] evicting = new boolean[MAX_RESERVATIONS];
    private final int[] generations = new int[MAX_RESERVATIONS];
    private final byte[] kinds = new byte[MAX_RESERVATIONS];
    private final byte[] categories = new byte[MAX_RESERVATIONS];
    private final int[] pins = new int[MAX_RESERVATIONS];
    private final long[] requestedBytes = new long[MAX_RESERVATIONS];
    private final long[] committedBytes = new long[MAX_RESERVATIONS];
    private final long[] lastUse = new long[MAX_RESERVATIONS];
    private final long[] retireEpoch = new long[MAX_RESERVATIONS];
    private final long[] attemptedEvictionRounds =
        new long[MAX_RESERVATIONS];
    private final Evictable[] evictables = new Evictable[MAX_RESERVATIONS];
    private final long[] usedByKind = new long[KINDS.length];
    private final long[] peakByKind = new long[KINDS.length];
    private final long[] requestedByKind = new long[KINDS.length];
    private final long[] usedByCategory =
        new long[KINDS.length * CATEGORY_COUNT];
    private final long[] peakByCategory =
        new long[KINDS.length * CATEGORY_COUNT];

    private MemoryBudgetSettings settings;
    private long clock;
    private long epoch;
    private long rejectionCount;
    private long evictionCount;
    private long reclaimedBytes;
    private long deniedInFlightReleases;
    private long staleReleaseCount;
    private long leakCount;
    private long evictionRound;
    private int outstanding;
    private int inFlightEvictions;
    private final ThreadLocal<int[]> evictionCallbackDepth =
        ThreadLocal.withInitial(() -> new int[1]);
    private boolean closing;
    private boolean closed;

    public MemoryBudgetManager(MemoryBudgetSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void applySettings(MemoryBudgetSettings settings) {
        synchronized (this) {
            this.requireOpen();
            this.settings = Objects.requireNonNull(settings, "settings");
        }
        this.trimToLimits();
    }

    /**
     * Tries to reserve logical memory before physical allocation.
     *
     * @return a non-zero primitive lease token, or zero on bounded rejection
     */
    public long tryReserve(
        MemoryKind kind,
        MemoryCategory category,
        long requested,
        long committed,
        Evictable evictable
    ) {
        synchronized (this) {
            this.requireOpen();
        }
        requireFootprint(requested, committed);
        int kindIndex = Objects.requireNonNull(kind, "kind").ordinal();
        int categoryIndex =
            Objects.requireNonNull(category, "category").ordinal();

        int evictionPasses = 0;
        while (true) {
            int requiredKind;
            int requiredCategory;
            boolean categoryOnly;
            synchronized (this) {
                this.requireOpen();
                if (this.fits(kindIndex, categoryIndex, committed)) {
                    int slot = this.freeSlot();
                    if (slot >= 0) {
                        return this.addReservation(
                            slot,
                            kindIndex,
                            categoryIndex,
                            requested,
                            committed,
                            evictable
                        );
                    }
                    requiredKind = -1;
                    requiredCategory = -1;
                    categoryOnly = false;
                } else {
                    requiredKind = kindIndex;
                    requiredCategory = categoryIndex;
                    categoryOnly = !fitsWithin(
                        this.usedByCategory[
                            aggregateIndex(kindIndex, categoryIndex)
                        ],
                        committed,
                        this.settings.categoryBytes(kind, category)
                    );
                }
                if (evictionPasses >= MAX_RESERVATIONS) {
                    this.rejectionCount++;
                    return 0L;
                }
            }
            if (
                !this.evictLeastRecentlyUsed(
                    requiredKind,
                    requiredCategory,
                    categoryOnly,
                    false
                )
            ) {
                synchronized (this) {
                    this.requireOpen();
                    if (
                        !this.fits(kindIndex, categoryIndex, committed)
                            || this.freeSlot() < 0
                    ) {
                        this.rejectionCount++;
                        return 0L;
                    }
                }
            }
            evictionPasses++;
        }
    }

    public long tryReserve(
        MemoryKind kind,
        MemoryCategory category,
        long bytes
    ) {
        return this.tryReserve(kind, category, bytes, bytes, null);
    }

    /**
     * Makes an existing fully constructed resource eligible for LRU
     * eviction.
     *
     * <p>Registration is one-shot and rejected for stale, pinned, retiring,
     * or already-evicting leases. This permits physical owners to publish
     * their callback only after construction and rollback ownership are
     * complete.</p>
     */
    public synchronized boolean registerEvictable(
        long token,
        Evictable evictable
    ) {
        Objects.requireNonNull(evictable, "evictable");
        int slot = this.validSlot(token);
        if (
            this.closed
                || this.closing
                || slot < 0
                || this.retiring[slot]
                || this.evicting[slot]
                || this.pins[slot] != 0
                || this.evictables[slot] != null
        ) {
            return false;
        }
        this.evictables[slot] = evictable;
        this.lastUse[slot] = ++this.clock;
        return true;
    }

    public synchronized boolean touch(long token) {
        int slot = this.validSlot(token);
        if (
            this.closed
                || this.closing
                || slot < 0
                || this.retiring[slot]
                || this.evicting[slot]
        ) {
            return false;
        }
        this.lastUse[slot] = ++this.clock;
        return true;
    }

    public synchronized boolean pin(long token) {
        int slot = this.validSlot(token);
        if (
            this.closed
                || this.closing
                || slot < 0
                || this.retiring[slot]
                || this.evicting[slot]
        ) {
            return false;
        }
        if (this.pins[slot] == Integer.MAX_VALUE) {
            throw new IllegalStateException("memory lease pin count overflow");
        }
        this.pins[slot]++;
        this.lastUse[slot] = ++this.clock;
        return true;
    }

    public synchronized boolean unpin(long token) {
        int slot = this.validSlot(token);
        if (slot < 0 || this.pins[slot] == 0) {
            return false;
        }
        this.pins[slot]--;
        return true;
    }

    /**
     * Releases a lease only when no GPU or worker owner still pins it.
     */
    public synchronized boolean release(long token) {
        int slot = this.validSlot(token);
        if (slot < 0) {
            this.staleReleaseCount++;
            return false;
        }
        if (this.pins[slot] != 0 || this.retiring[slot]) {
            this.deniedInFlightReleases++;
            return false;
        }
        this.remove(slot);
        return true;
    }

    /**
     * Keeps accounting alive until Mojang's submit-rotated destruction queue
     * has had enough frames to retire a newly queued GPU resource.
     */
    public synchronized boolean retireAfterGpuUse(long token) {
        int slot = this.validSlot(token);
        if (
            this.closed
                || this.closing
                || slot < 0
                || this.pins[slot] != 0
                || this.evicting[slot]
        ) {
            if (slot >= 0) {
                this.deniedInFlightReleases++;
            }
            return false;
        }
        this.retiring[slot] = true;
        this.evictables[slot] = null;
        this.retireEpoch[slot] = this.epoch + RETIREMENT_FRAMES;
        return true;
    }

    /** Advances the fixed retirement ring once per completed client frame. */
    public synchronized void advanceFrame() {
        if (this.closed || this.closing) {
            return;
        }
        this.epoch++;
        for (int slot = 0; slot < MAX_RESERVATIONS; slot++) {
            if (
                this.active[slot]
                    && this.retiring[slot]
                    && this.retireEpoch[slot] <= this.epoch
            ) {
                this.remove(slot);
            }
        }
    }

    /**
     * Completes all deferred GPU retirements after the caller has guaranteed
     * that the backend command encoder and its destruction queue are drained.
     *
     * @return the number of leases whose accounting was released
     */
    public synchronized int completeGpuRetirements() {
        if (this.closed) {
            return 0;
        }
        int completed = 0;
        for (int slot = 0; slot < MAX_RESERVATIONS; slot++) {
            if (this.active[slot] && this.retiring[slot]) {
                this.remove(slot);
                completed++;
            }
        }
        return completed;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.settings,
            this.usedByKind.clone(),
            this.peakByKind.clone(),
            this.requestedByKind.clone(),
            this.usedByCategory.clone(),
            this.peakByCategory.clone(),
            this.outstanding,
            this.rejectionCount,
            this.evictionCount,
            this.reclaimedBytes,
            this.deniedInFlightReleases,
            this.staleReleaseCount,
            this.leakCount,
            this.closed
        );
    }

    public synchronized long availableBytes(MemoryKind kind) {
        int index = Objects.requireNonNull(kind, "kind").ordinal();
        return Math.max(
            0L,
            this.settings.usableBytes(kind) - this.usedByKind[index]
        );
    }

    @Override
    public void close() {
        this.closeAndReport();
    }

    /**
     * Closes the manager and reports whether every lease was released.
     *
     * <p>{@link #close()} intentionally retains its existing non-throwing
     * leak-accounting contract. Final lifecycle owners use this method so an
     * outstanding conservatively retained lease cannot become a false clean
     * shutdown proof.</p>
     */
    public boolean closeAndReport() {
        if (this.inEvictionCallback()) {
            throw new IllegalStateException(
                "memory budget manager cannot close from an eviction callback"
            );
        }
        boolean interrupted = false;
        synchronized (this) {
            if (this.closed) {
                return this.closeReportedCleanly();
            }
            if (this.closing) {
                while (!this.closed) {
                    try {
                        this.wait();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return this.closeReportedCleanly();
            }
            this.closing = true;
            while (this.inFlightEvictions != 0) {
                try {
                    this.wait();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        try {
            this.trimAllEvictable();
        } finally {
            synchronized (this) {
                while (this.inFlightEvictions != 0) {
                    try {
                        this.wait();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
                this.leakCount += this.outstanding;
                this.closed = true;
                this.closing = false;
                this.notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            return this.closeReportedCleanly();
        }
    }

    private boolean closeReportedCleanly() {
        return this.closed
            && this.outstanding == 0
            && this.leakCount == 0L;
    }

    private void trimToLimits() {
        int attempts = 0;
        while (this.isOverAnyLimit() && attempts++ < MAX_RESERVATIONS) {
            if (!this.evictOneOverLimit()) {
                break;
            }
        }
    }

    private void trimAllEvictable() {
        for (int attempt = 0; attempt < MAX_RESERVATIONS; attempt++) {
            if (
                !this.evictLeastRecentlyUsed(
                    -1,
                    -1,
                    false,
                    true
                )
            ) {
                return;
            }
        }
    }

    private boolean evictOneOverLimit() {
        int requiredKind = -1;
        int requiredCategory = -1;
        boolean categoryOnly = false;
        synchronized (this) {
            if (this.closed || this.closing) {
                return false;
            }
            for (MemoryKind kind : KINDS) {
                int kindIndex = kind.ordinal();
                for (MemoryCategory category : CATEGORIES) {
                    if (
                        this.usedByCategory[
                                aggregateIndex(
                                    kindIndex,
                                    category.ordinal()
                                )
                            ]
                            > this.settings.categoryBytes(kind, category)
                    ) {
                        requiredKind = kindIndex;
                        requiredCategory = category.ordinal();
                        categoryOnly = true;
                        break;
                    }
                }
                if (requiredKind >= 0) {
                    break;
                }
                if (
                    this.usedByKind[kindIndex]
                        > this.settings.usableBytes(kind)
                ) {
                    requiredKind = kindIndex;
                    break;
                }
            }
        }
        return requiredKind >= 0
            && this.evictLeastRecentlyUsed(
                requiredKind,
                requiredCategory,
                categoryOnly,
                false
            );
    }

    private boolean evictLeastRecentlyUsed(
        int requiredKind,
        int requiredCategory,
        boolean categoryOnly,
        boolean allowClosing
    ) {
        long round;
        synchronized (this) {
            if (
                this.closed
                    || (this.closing && !allowClosing)
            ) {
                return false;
            }
            round = this.nextEvictionRound();
        }

        for (int attempt = 0; attempt < MAX_RESERVATIONS; attempt++) {
            EvictionCandidate candidate;
            synchronized (this) {
                candidate = this.selectEvictionCandidate(
                    requiredKind,
                    requiredCategory,
                    categoryOnly,
                    allowClosing,
                    round
                );
                if (candidate != null) {
                    this.inFlightEvictions++;
                }
            }
            if (candidate == null) {
                return false;
            }

            boolean enteredCallback = false;
            try {
                this.enterEvictionCallback();
                enteredCallback = true;
                boolean evicted;
                try {
                    evicted = candidate.evictable.evict();
                } catch (Throwable ignored) {
                    evicted = false;
                }
                synchronized (this) {
                    boolean sameGeneration =
                        this.active[candidate.slot]
                            && this.generations[candidate.slot]
                                == candidate.generation;
                    boolean sameEviction =
                        sameGeneration && this.evicting[candidate.slot];
                    boolean finalized = false;
                    if (
                        evicted
                            && sameEviction
                            && this.pins[candidate.slot] == 0
                            && !this.retiring[candidate.slot]
                            && !this.closed
                    ) {
                        this.remove(candidate.slot);
                        finalized = true;
                    } else if (evicted && !sameGeneration) {
                        // The callback released the original generation itself.
                        finalized = true;
                    }

                    if (sameEviction && !finalized) {
                        this.evicting[candidate.slot] = false;
                        if (!this.retiring[candidate.slot]) {
                            this.lastUse[candidate.slot] = ++this.clock;
                        }
                    }
                    if (finalized) {
                        this.evictionCount++;
                        this.reclaimedBytes = checkedAdd(
                            this.reclaimedBytes,
                            candidate.committedBytes
                        );
                        return true;
                    }
                }
            } finally {
                if (enteredCallback) {
                    this.exitEvictionCallback();
                }
                synchronized (this) {
                    this.inFlightEvictions--;
                    this.notifyAll();
                }
            }
        }
        return false;
    }

    private void enterEvictionCallback() {
        int[] depth = this.evictionCallbackDepth.get();
        depth[0]++;
    }

    private void exitEvictionCallback() {
        int[] depth = this.evictionCallbackDepth.get();
        depth[0]--;
        if (depth[0] == 0) {
            this.evictionCallbackDepth.remove();
        }
    }

    private boolean inEvictionCallback() {
        int[] depth = this.evictionCallbackDepth.get();
        boolean activeCallback = depth[0] != 0;
        if (!activeCallback) {
            this.evictionCallbackDepth.remove();
        }
        return activeCallback;
    }

    private EvictionCandidate selectEvictionCandidate(
        int requiredKind,
        int requiredCategory,
        boolean categoryOnly,
        boolean allowClosing,
        long round
    ) {
        if (
            this.closed
                || (this.closing && !allowClosing)
        ) {
            return null;
        }
        int selected = -1;
        for (int slot = 0; slot < MAX_RESERVATIONS; slot++) {
            if (
                !this.active[slot]
                    || this.retiring[slot]
                    || this.evicting[slot]
                    || this.pins[slot] != 0
                    || this.evictables[slot] == null
                    || this.attemptedEvictionRounds[slot] == round
                    || (requiredKind >= 0 && this.kinds[slot] != requiredKind)
                    || (
                        categoryOnly
                            && this.categories[slot] != requiredCategory
                    )
            ) {
                continue;
            }
            if (
                selected < 0
                    || this.lastUse[slot] < this.lastUse[selected]
                    || (
                        this.lastUse[slot] == this.lastUse[selected]
                            && slot < selected
                    )
            ) {
                selected = slot;
            }
        }
        if (selected < 0) {
            return null;
        }

        this.attemptedEvictionRounds[selected] = round;
        this.evicting[selected] = true;
        return new EvictionCandidate(
            selected,
            this.generations[selected],
            this.committedBytes[selected],
            this.evictables[selected]
        );
    }

    private boolean fits(int kind, int category, long bytes) {
        long globalLimit =
            this.settings.usableBytes(KINDS[kind]);
        long categoryLimit = this.settings.categoryBytes(
            KINDS[kind],
            CATEGORIES[category]
        );
        return fitsWithin(this.usedByKind[kind], bytes, globalLimit)
            && fitsWithin(
                this.usedByCategory[aggregateIndex(kind, category)],
                bytes,
                categoryLimit
            );
    }

    private synchronized boolean isOverAnyLimit() {
        return !this.closed && !this.closing && this.overAnyLimit();
    }

    private boolean overAnyLimit() {
        for (MemoryKind kind : KINDS) {
            int kindIndex = kind.ordinal();
            if (this.usedByKind[kindIndex] > this.settings.usableBytes(kind)) {
                return true;
            }
            for (MemoryCategory category : CATEGORIES) {
                if (
                    this.usedByCategory[
                            aggregateIndex(kindIndex, category.ordinal())
                        ]
                        > this.settings.categoryBytes(kind, category)
                ) {
                    return true;
                }
            }
        }
        return false;
    }

    private long addReservation(
        int slot,
        int kind,
        int category,
        long requested,
        long committed,
        Evictable evictable
    ) {
        int generation = this.generations[slot] + 1;
        if (generation == 0) {
            generation = 1;
        }
        this.generations[slot] = generation;
        this.active[slot] = true;
        this.retiring[slot] = false;
        this.evicting[slot] = false;
        this.kinds[slot] = (byte)kind;
        this.categories[slot] = (byte)category;
        this.pins[slot] = 0;
        this.requestedBytes[slot] = requested;
        this.committedBytes[slot] = committed;
        this.lastUse[slot] = ++this.clock;
        this.retireEpoch[slot] = 0L;
        this.evictables[slot] = evictable;
        this.outstanding++;

        int aggregate = aggregateIndex(kind, category);
        this.usedByKind[kind] = checkedAdd(
            this.usedByKind[kind],
            committed
        );
        this.requestedByKind[kind] = checkedAdd(
            this.requestedByKind[kind],
            requested
        );
        this.usedByCategory[aggregate] = checkedAdd(
            this.usedByCategory[aggregate],
            committed
        );
        this.peakByKind[kind] = Math.max(
            this.peakByKind[kind],
            this.usedByKind[kind]
        );
        this.peakByCategory[aggregate] = Math.max(
            this.peakByCategory[aggregate],
            this.usedByCategory[aggregate]
        );
        return token(slot, generation);
    }

    private int freeSlot() {
        for (int slot = 0; slot < MAX_RESERVATIONS; slot++) {
            if (!this.active[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private long nextEvictionRound() {
        this.evictionRound++;
        if (this.evictionRound == 0L) {
            Arrays.fill(this.attemptedEvictionRounds, 0L);
            this.evictionRound = 1L;
        }
        return this.evictionRound;
    }

    private int validSlot(long token) {
        int encodedSlot = (int)token;
        int slot = encodedSlot - 1;
        int generation = (int)(token >>> 32);
        return slot >= 0
                && slot < MAX_RESERVATIONS
                && this.active[slot]
                && this.generations[slot] == generation
            ? slot
            : -1;
    }

    private void remove(int slot) {
        int kind = this.kinds[slot];
        int category = this.categories[slot];
        long requested = this.requestedBytes[slot];
        long committed = this.committedBytes[slot];
        int aggregate = aggregateIndex(kind, category);
        this.usedByKind[kind] -= committed;
        this.requestedByKind[kind] -= requested;
        this.usedByCategory[aggregate] -= committed;
        this.active[slot] = false;
        this.retiring[slot] = false;
        this.evicting[slot] = false;
        this.pins[slot] = 0;
        this.requestedBytes[slot] = 0L;
        this.committedBytes[slot] = 0L;
        this.lastUse[slot] = 0L;
        this.retireEpoch[slot] = 0L;
        this.evictables[slot] = null;
        this.outstanding--;
    }

    private void requireOpen() {
        if (this.closed || this.closing) {
            throw new IllegalStateException("memory budget manager is closed");
        }
    }

    private static void requireFootprint(long requested, long committed) {
        if (requested <= 0L) {
            throw new IllegalArgumentException("requested bytes must be positive");
        }
        if (committed < requested) {
            throw new IllegalArgumentException(
                "committed bytes must cover requested bytes"
            );
        }
    }

    private static boolean fitsWithin(long used, long added, long limit) {
        return used <= limit && added <= limit - used;
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalStateException("memory accounting overflow", error);
        }
    }

    private static int aggregateIndex(int kind, int category) {
        return kind * CATEGORY_COUNT + category;
    }

    private static long token(int slot, int generation) {
        return (Integer.toUnsignedLong(generation) << 32)
            | Integer.toUnsignedLong(slot + 1);
    }

    private record EvictionCandidate(
        int slot,
        int generation,
        long committedBytes,
        Evictable evictable
    ) {}

    public static final class Snapshot {
        private final MemoryBudgetSettings settings;
        private final long[] usedByKind;
        private final long[] peakByKind;
        private final long[] requestedByKind;
        private final long[] usedByCategory;
        private final long[] peakByCategory;
        private final int outstanding;
        private final long rejections;
        private final long evictions;
        private final long reclaimedBytes;
        private final long deniedInFlightReleases;
        private final long staleReleases;
        private final long leaks;
        private final boolean closed;

        private Snapshot(
            MemoryBudgetSettings settings,
            long[] usedByKind,
            long[] peakByKind,
            long[] requestedByKind,
            long[] usedByCategory,
            long[] peakByCategory,
            int outstanding,
            long rejections,
            long evictions,
            long reclaimedBytes,
            long deniedInFlightReleases,
            long staleReleases,
            long leaks,
            boolean closed
        ) {
            this.settings = settings;
            this.usedByKind = usedByKind;
            this.peakByKind = peakByKind;
            this.requestedByKind = requestedByKind;
            this.usedByCategory = usedByCategory;
            this.peakByCategory = peakByCategory;
            this.outstanding = outstanding;
            this.rejections = rejections;
            this.evictions = evictions;
            this.reclaimedBytes = reclaimedBytes;
            this.deniedInFlightReleases = deniedInFlightReleases;
            this.staleReleases = staleReleases;
            this.leaks = leaks;
            this.closed = closed;
        }

        public long usedBytes(MemoryKind kind) {
            return this.usedByKind[kind.ordinal()];
        }

        public long requestedBytes(MemoryKind kind) {
            return this.requestedByKind[kind.ordinal()];
        }

        public long fragmentationBytes(MemoryKind kind) {
            return this.usedBytes(kind) - this.requestedBytes(kind);
        }

        public long peakBytes(MemoryKind kind) {
            return this.peakByKind[kind.ordinal()];
        }

        public long usableBytes(MemoryKind kind) {
            return this.settings.usableBytes(kind);
        }

        public long hardLimitBytes(MemoryKind kind) {
            return this.settings.maxBytes(kind);
        }

        public long safetyBytes(MemoryKind kind) {
            return this.settings.safetyBytes(kind);
        }

        public long usedBytes(
            MemoryKind kind,
            MemoryCategory category
        ) {
            return this.usedByCategory[
                aggregateIndex(kind.ordinal(), category.ordinal())
            ];
        }

        public long peakBytes(
            MemoryKind kind,
            MemoryCategory category
        ) {
            return this.peakByCategory[
                aggregateIndex(kind.ordinal(), category.ordinal())
            ];
        }

        public long limitBytes(
            MemoryKind kind,
            MemoryCategory category
        ) {
            return this.settings.categoryBytes(kind, category);
        }

        public int outstanding() {
            return this.outstanding;
        }

        public long rejections() {
            return this.rejections;
        }

        public long evictions() {
            return this.evictions;
        }

        public long reclaimedBytes() {
            return this.reclaimedBytes;
        }

        public long deniedInFlightReleases() {
            return this.deniedInFlightReleases;
        }

        public long staleReleases() {
            return this.staleReleases;
        }

        public long leaks() {
            return this.leaks;
        }

        public boolean closed() {
            return this.closed;
        }

        @Override
        public String toString() {
            return "MemoryBudgetSnapshot[used="
                + Arrays.toString(this.usedByKind)
                + ", peak="
                + Arrays.toString(this.peakByKind)
                + ", outstanding="
                + this.outstanding
                + ", rejections="
                + this.rejections
                + "]";
        }
    }
}
