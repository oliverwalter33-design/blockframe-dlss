package de.morau.nvidiadlss;

import org.jspecify.annotations.Nullable;

/**
 * Type-free state machine for one render-thread-confined mutable pose stack.
 * The Minecraft adapter supplies the concrete stack operations.
 */
final class RenderThreadPoseStackScratch<S> {
    static final int STATUS_ACTIVE = 1;
    static final int STATUS_DISABLED = 2;
    static final int STATUS_CLEARED = 3;

    private static final int MAX_UNWIND_DEPTH = 64;

    interface Access<S> {
        S createFresh();

        void setIdentity(S stack);

        void push(S stack);

        void pop(S stack);

        boolean isEmpty(S stack);

        boolean isIdentity(S stack);
    }

    private final Thread ownerThread;
    private final Access<S> access;
    private @Nullable S reusable;
    private int status;
    private boolean inUse;
    private long reuseUses;
    private long freshFallbacks;
    private long disableCount;
    private long reentrantFallbacks;
    private long wrongThreadFallbacks;
    private long abortedUses;
    private long imbalanceDisables;
    private long unwoundPoses;

    private RenderThreadPoseStackScratch(
        Thread ownerThread,
        Access<S> access,
        @Nullable S reusable,
        int status
    ) {
        this.ownerThread = ownerThread;
        this.access = access;
        this.reusable = reusable;
        this.status = status;
    }

    static <S> RenderThreadPoseStackScratch<S> createForCurrentThread(
        Access<S> access
    ) {
        Thread ownerThread = Thread.currentThread();
        try {
            S stack = access.createFresh();
            access.setIdentity(stack);
            access.push(stack);
            access.pop(stack);
            access.setIdentity(stack);
            if (!isCanonicalBase(access, stack)) {
                throw new IllegalStateException(
                    "prewarmed outline pose stack is not canonical"
                );
            }
            return new RenderThreadPoseStackScratch<>(
                ownerThread,
                access,
                stack,
                STATUS_ACTIVE
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            RenderThreadPoseStackScratch<S> disabled =
                new RenderThreadPoseStackScratch<>(
                    ownerThread,
                    access,
                    null,
                    STATUS_DISABLED
                );
            disabled.disableCount = 1L;
            return disabled;
        }
    }

    public S beginUse() {
        if (Thread.currentThread() != this.ownerThread) {
            this.wrongThreadFallbacks =
                incrementSaturated(this.wrongThreadFallbacks);
            return this.freshFallback();
        }

        S stack = this.reusable;
        if (this.status != STATUS_ACTIVE || stack == null) {
            return this.freshFallback();
        }
        if (this.inUse) {
            this.reentrantFallbacks =
                incrementSaturated(this.reentrantFallbacks);
            return this.freshFallback();
        }

        try {
            if (!this.access.isEmpty(stack)) {
                this.imbalanceDisables =
                    incrementSaturated(this.imbalanceDisables);
                this.unwindAndReset(stack);
                this.disable();
                return this.freshFallback();
            }
            this.access.setIdentity(stack);
            if (!isCanonicalBase(this.access, stack)) {
                this.disable();
                return this.freshFallback();
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            this.disable();
            return this.freshFallback();
        }

        this.inUse = true;
        this.reuseUses = incrementSaturated(this.reuseUses);
        return stack;
    }

    public void endUse(S used, boolean submissionCompleted) {
        S stack = this.reusable;
        if (used != stack) {
            return;
        }
        if (!this.inUse || Thread.currentThread() != this.ownerThread) {
            this.disable();
            return;
        }

        try {
            if (!submissionCompleted) {
                this.abortedUses = incrementSaturated(this.abortedUses);
                this.disable();
                return;
            }
            if (!this.access.isEmpty(stack)) {
                this.imbalanceDisables =
                    incrementSaturated(this.imbalanceDisables);
                this.unwindAndReset(stack);
                this.disable();
                return;
            }
            this.access.setIdentity(stack);
            if (!isCanonicalBase(this.access, stack)) {
                this.disable();
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
            this.disable();
        } finally {
            this.inUse = false;
        }
    }

    void clear() {
        this.inUse = false;
        this.reusable = null;
        this.status = STATUS_CLEARED;
    }

    int status() {
        return this.status;
    }

    long reuseUses() {
        return this.reuseUses;
    }

    long freshFallbacks() {
        return this.freshFallbacks;
    }

    long disableCount() {
        return this.disableCount;
    }

    long reentrantFallbacks() {
        return this.reentrantFallbacks;
    }

    long wrongThreadFallbacks() {
        return this.wrongThreadFallbacks;
    }

    long abortedUses() {
        return this.abortedUses;
    }

    long imbalanceDisables() {
        return this.imbalanceDisables;
    }

    long unwoundPoses() {
        return this.unwoundPoses;
    }

    private S freshFallback() {
        this.freshFallbacks = incrementSaturated(this.freshFallbacks);
        return this.access.createFresh();
    }

    private void disable() {
        if (this.status == STATUS_ACTIVE) {
            this.disableCount = incrementSaturated(this.disableCount);
        }
        this.reusable = null;
        this.status = STATUS_DISABLED;
    }

    private void unwindAndReset(S stack) {
        int unwound = 0;
        while (!this.access.isEmpty(stack) && unwound < MAX_UNWIND_DEPTH) {
            this.access.pop(stack);
            unwound++;
            this.unwoundPoses = incrementSaturated(this.unwoundPoses);
        }
        if (!this.access.isEmpty(stack)) {
            throw new IllegalStateException(
                "outline pose stack exceeded bounded unwind depth"
            );
        }
        this.access.setIdentity(stack);
    }

    private static <S> boolean isCanonicalBase(
        Access<S> access,
        S stack
    ) {
        return access.isEmpty(stack) && access.isIdentity(stack);
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }
}
