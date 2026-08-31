package de.morau.blockframe.render.terrain.nativeengine;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, allocation-free counters for one exclusive native world-resource
 * generation.
 *
 * <p>The Mojang counters are incremented from the actual compiler, arena,
 * upload and opaque-submission entry points. They are evidence, not control
 * flow: a non-zero Mojang counter always invalidates the native fixture gate
 * and can never be used to suppress the original operation.</p>
 */
public final class NativeTerrainOwnershipEvidence {
    public record Snapshot(
        long generation,
        boolean exclusivePeriod,
        boolean sealed,
        long blockFrameSectionCompiles,
        long blockFramePayloadPublishes,
        long blockFrameGpuUploads,
        long blockFrameSceneEntries,
        long blockFrameComputeDispatchesEncoded,
        long blockFrameIndirectSubmissionsEncoded,
        long mojangSectionCompiles,
        long mojangGpuAllocations,
        long mojangGpuUploads,
        long mojangOpaqueSubmissions
    ) {
        public Snapshot {
            if (exclusivePeriod && sealed) {
                throw new IllegalArgumentException(
                    "active ownership evidence cannot be sealed"
                );
            }
        }

        public boolean blockFramePathObserved() {
            return this.blockFrameSectionCompiles > 0L
                && this.blockFramePayloadPublishes > 0L
                && this.blockFrameGpuUploads > 0L
                && this.blockFrameSceneEntries > 0L
                && this.blockFrameComputeDispatchesEncoded > 0L
                && this.blockFrameIndirectSubmissionsEncoded > 0L;
        }

        public boolean mojangTerrainWorkAbsent() {
            return this.mojangSectionCompiles == 0L
                && this.mojangGpuAllocations == 0L
                && this.mojangGpuUploads == 0L
                && this.mojangOpaqueSubmissions == 0L;
        }

        public boolean exclusiveFixtureGatePassed() {
            return this.sealed
                && blockFramePathObserved()
                && mojangTerrainWorkAbsent();
        }
    }

    private static final AtomicReference<State> ACTIVE =
        new AtomicReference<>();
    private static volatile State lastCompleted;

    public static final class GenerationToken {
        private final State state;

        private GenerationToken(State state) {
            this.state = state;
        }

        public long generation() {
            return this.state.generation;
        }
    }

    private NativeTerrainOwnershipEvidence() {
    }

    public static synchronized GenerationToken begin(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "ownership generation must be positive"
            );
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException(
                "exclusive ownership evidence is already active"
            );
        }
        lastCompleted = null;
        State state = new State(generation);
        ACTIVE.set(state);
        return new GenerationToken(state);
    }

    public static synchronized void end(GenerationToken token) {
        State state = requireActiveToken(token);
        state.accepting = false;
        if (!ACTIVE.compareAndSet(state, null)) {
            throw new IllegalStateException(
                "exclusive ownership evidence changed during close"
            );
        }
        lastCompleted = state;
    }

    public static boolean exclusivePeriod() {
        return ACTIVE.get() != null;
    }

    public static void blockFrameSectionCompiled(
        GenerationToken token
    ) {
        increment(token, Counter.BLOCKFRAME_SECTION_COMPILES);
    }

    public static void blockFramePayloadPublished(
        GenerationToken token
    ) {
        increment(token, Counter.BLOCKFRAME_PAYLOAD_PUBLISHES);
    }

    public static void blockFrameGpuUploaded(
        GenerationToken token
    ) {
        increment(token, Counter.BLOCKFRAME_GPU_UPLOADS);
    }

    public static void blockFrameSceneEntriesPublished(
        GenerationToken token,
        int entries
    ) {
        if (entries <= 0) {
            throw new IllegalArgumentException(
                "published scene entry count must be positive"
            );
        }
        State state = state(token);
        if (state != null && state.accepting) {
            saturatingAdd(state.blockFrameSceneEntries, entries);
        }
    }

    public static void blockFrameComputeCullEncoded(
        GenerationToken token
    ) {
        increment(token, Counter.BLOCKFRAME_COMPUTE_DISPATCHES);
    }

    public static void blockFrameIndirectSubmissionEncoded(
        GenerationToken token
    ) {
        increment(token, Counter.BLOCKFRAME_INDIRECT_SUBMISSIONS);
    }

    public static void mojangSectionCompiled() {
        State state = ACTIVE.get();
        if (state != null) {
            saturatingIncrement(state.mojangSectionCompiles);
        }
    }

    public static void mojangGpuAllocated() {
        State state = ACTIVE.get();
        if (state != null) {
            saturatingIncrement(state.mojangGpuAllocations);
        }
    }

    public static void mojangGpuUploaded() {
        State state = ACTIVE.get();
        if (state != null) {
            saturatingIncrement(state.mojangGpuUploads);
        }
    }

    public static void mojangOpaqueSubmitted() {
        State state = ACTIVE.get();
        if (state != null) {
            saturatingIncrement(state.mojangOpaqueSubmissions);
        }
    }

    public static boolean isMojangSolidCutoutHeapName(String name) {
        return name != null
            && (
                name.startsWith("UberBuffer solid ")
                    || name.startsWith("UberBuffer cutout ")
            );
    }

    public static synchronized Snapshot snapshot() {
        State active = ACTIVE.get();
        State state = active == null ? lastCompleted : active;
        return state == null
            ? new Snapshot(
                0L,
                false,
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
            )
            : state.snapshot(active == state, active == null);
    }

    static synchronized void resetForTests() {
        ACTIVE.set(null);
        lastCompleted = null;
    }

    private static State requireActiveToken(GenerationToken token) {
        if (token == null) {
            throw new IllegalArgumentException(
                "ownership evidence token is required"
            );
        }
        State state = ACTIVE.get();
        if (
            state == null
                || token.state != state
                || !state.accepting
        ) {
            throw new IllegalArgumentException(
                "stale ownership evidence generation"
            );
        }
        return state;
    }

    private static State state(GenerationToken token) {
        return token == null ? null : token.state;
    }

    private static void increment(
        GenerationToken token,
        Counter counter
    ) {
        State state = state(token);
        if (state == null || !state.accepting) {
            return;
        }
        AtomicLong value = switch (counter) {
            case BLOCKFRAME_SECTION_COMPILES ->
                state.blockFrameSectionCompiles;
            case BLOCKFRAME_PAYLOAD_PUBLISHES ->
                state.blockFramePayloadPublishes;
            case BLOCKFRAME_GPU_UPLOADS ->
                state.blockFrameGpuUploads;
            case BLOCKFRAME_COMPUTE_DISPATCHES ->
                state.blockFrameComputeDispatchesEncoded;
            case BLOCKFRAME_INDIRECT_SUBMISSIONS ->
                state.blockFrameIndirectSubmissionsEncoded;
        };
        saturatingIncrement(value);
    }

    private static void saturatingIncrement(AtomicLong counter) {
        saturatingAdd(counter, 1L);
    }

    private static void saturatingAdd(AtomicLong counter, long amount) {
        while (true) {
            long current = counter.get();
            if (current == Long.MAX_VALUE) {
                return;
            }
            long next = current > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE
                : current + amount;
            if (counter.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private static final class State {
        private final long generation;
        private volatile boolean accepting = true;
        private final AtomicLong blockFrameSectionCompiles =
            new AtomicLong();
        private final AtomicLong blockFramePayloadPublishes =
            new AtomicLong();
        private final AtomicLong blockFrameGpuUploads =
            new AtomicLong();
        private final AtomicLong blockFrameSceneEntries =
            new AtomicLong();
        private final AtomicLong blockFrameComputeDispatchesEncoded =
            new AtomicLong();
        private final AtomicLong blockFrameIndirectSubmissionsEncoded =
            new AtomicLong();
        private final AtomicLong mojangSectionCompiles =
            new AtomicLong();
        private final AtomicLong mojangGpuAllocations =
            new AtomicLong();
        private final AtomicLong mojangGpuUploads =
            new AtomicLong();
        private final AtomicLong mojangOpaqueSubmissions =
            new AtomicLong();

        private State(long generation) {
            this.generation = generation;
        }

        private Snapshot snapshot(
            boolean exclusivePeriod,
            boolean sealed
        ) {
            return new Snapshot(
                this.generation,
                exclusivePeriod,
                sealed,
                this.blockFrameSectionCompiles.get(),
                this.blockFramePayloadPublishes.get(),
                this.blockFrameGpuUploads.get(),
                this.blockFrameSceneEntries.get(),
                this.blockFrameComputeDispatchesEncoded.get(),
                this.blockFrameIndirectSubmissionsEncoded.get(),
                this.mojangSectionCompiles.get(),
                this.mojangGpuAllocations.get(),
                this.mojangGpuUploads.get(),
                this.mojangOpaqueSubmissions.get()
            );
        }
    }

    private enum Counter {
        BLOCKFRAME_SECTION_COMPILES,
        BLOCKFRAME_PAYLOAD_PUBLISHES,
        BLOCKFRAME_GPU_UPLOADS,
        BLOCKFRAME_COMPUTE_DISPATCHES,
        BLOCKFRAME_INDIRECT_SUBMISSIONS
    }
}
