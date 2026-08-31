package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NativeBlockOutlinePoseStackScratchTest {
    @Test
    void keepsStableStackAndPrewarmsExactlyOnePushSlot() {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);
        FakePoseStack retained = access.firstCreated;

        assertEquals(1, retained.pushCalls);
        Object prewarmedPose = retained.poseSlots[1];
        FakePoseStack first = scratch.beginUse();
        assertSame(retained, first);
        first.push();
        assertSame(prewarmedPose, first.currentPose());
        first.pop();
        scratch.endUse(first, true);

        FakePoseStack second = scratch.beginUse();
        assertSame(first, second);
        scratch.endUse(second, true);

        assertEquals(
            RenderThreadPoseStackScratch.STATUS_ACTIVE,
            scratch.status()
        );
        assertEquals(2L, scratch.reuseUses());
        assertEquals(0L, scratch.freshFallbacks());
        assertEquals(0L, scratch.disableCount());
    }

    @Test
    void restoresEmptyIdentityBeforeNextUse() {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);

        FakePoseStack first = scratch.beginUse();
        first.identity = false;
        scratch.endUse(first, true);

        assertTrue(first.canonical());
        FakePoseStack second = scratch.beginUse();
        assertSame(first, second);
        assertTrue(second.canonical());
        scratch.endUse(second, true);
    }

    @Test
    void unwindsExtraDepthThenDisablesWithoutReplayingSubmission() {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);

        FakePoseStack reused = scratch.beginUse();
        reused.push();
        reused.push();
        reused.identity = false;
        scratch.endUse(reused, true);

        assertTrue(reused.canonical());
        assertEquals(
            RenderThreadPoseStackScratch.STATUS_DISABLED,
            scratch.status()
        );
        assertEquals(1L, scratch.disableCount());
        assertEquals(1L, scratch.imbalanceDisables());
        assertEquals(2L, scratch.unwoundPoses());

        FakePoseStack fallback = scratch.beginUse();
        assertNotSame(reused, fallback);
        assertTrue(fallback.canonical());
        assertEquals(1L, scratch.freshFallbacks());
        scratch.endUse(fallback, true);
    }

    @Test
    void reentrantUseGetsFreshFallbackAndKeepsOuterOwner() {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);

        FakePoseStack outer = scratch.beginUse();
        FakePoseStack nested = scratch.beginUse();

        assertNotSame(outer, nested);
        assertTrue(nested.canonical());
        assertEquals(1L, scratch.reentrantFallbacks());
        assertEquals(1L, scratch.freshFallbacks());
        assertEquals(
            RenderThreadPoseStackScratch.STATUS_ACTIVE,
            scratch.status()
        );

        scratch.endUse(nested, true);
        scratch.endUse(outer, true);
        FakePoseStack next = scratch.beginUse();
        assertSame(outer, next);
        scratch.endUse(next, true);
    }

    @Test
    void invariantFaultDisablesAndFallsBackBeforeSubmission() {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);
        FakePoseStack retained = access.firstCreated;
        retained.failIdentityReset = true;

        FakePoseStack fallback = scratch.beginUse();

        assertNotSame(retained, fallback);
        assertTrue(fallback.canonical());
        assertEquals(
            RenderThreadPoseStackScratch.STATUS_DISABLED,
            scratch.status()
        );
        assertEquals(1L, scratch.disableCount());
        assertEquals(1L, scratch.freshFallbacks());
        scratch.endUse(fallback, true);
    }

    @Test
    void submissionThrowablePropagatesUnchangedAndDisablesReuse() {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);
        FakePoseStack used = scratch.beginUse();
        RuntimeException expected = new RuntimeException("submit failure");

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> {
                boolean submissionCompleted = false;
                try {
                    throw expected;
                } finally {
                    scratch.endUse(used, submissionCompleted);
                }
            }
        );

        assertSame(expected, thrown);
        assertEquals(
            RenderThreadPoseStackScratch.STATUS_DISABLED,
            scratch.status()
        );
        assertEquals(1L, scratch.abortedUses());
        assertEquals(1L, scratch.disableCount());
        assertNotSame(used, scratch.beginUse());
    }

    @Test
    void wrongThreadGetsFreshFallbackWithoutCorruptingOwner()
        throws InterruptedException {
        FakeAccess access = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);
        FakePoseStack retained = access.firstCreated;
        AtomicReference<FakePoseStack> crossThread =
            new AtomicReference<>();

        Thread caller = new Thread(
            () -> {
                FakePoseStack fallback = scratch.beginUse();
                crossThread.set(fallback);
                scratch.endUse(fallback, true);
            },
            "outline-scratch-wrong-thread-test"
        );
        caller.start();
        caller.join();

        assertNotSame(retained, crossThread.get());
        assertEquals(1L, scratch.wrongThreadFallbacks());
        assertEquals(1L, scratch.freshFallbacks());
        assertEquals(
            RenderThreadPoseStackScratch.STATUS_ACTIVE,
            scratch.status()
        );
        FakePoseStack ownerUse = scratch.beginUse();
        assertSame(retained, ownerUse);
        scratch.endUse(ownerUse, true);
    }

    @Test
    void clearForcesFreshFallbackAndNewStateRearmsReuse() {
        FakeAccess firstAccess = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> first =
            RenderThreadPoseStackScratch.createForCurrentThread(firstAccess);
        FakePoseStack retained = first.beginUse();
        first.endUse(retained, true);

        first.clear();

        assertEquals(
            RenderThreadPoseStackScratch.STATUS_CLEARED,
            first.status()
        );
        FakePoseStack fallback = first.beginUse();
        assertNotSame(retained, fallback);
        assertEquals(1L, first.freshFallbacks());

        FakeAccess rearmedAccess = new FakeAccess();
        RenderThreadPoseStackScratch<FakePoseStack> rearmed =
            RenderThreadPoseStackScratch.createForCurrentThread(rearmedAccess);
        FakePoseStack rearmedUse = rearmed.beginUse();
        assertSame(rearmedAccess.firstCreated, rearmedUse);
        assertEquals(
            RenderThreadPoseStackScratch.STATUS_ACTIVE,
            rearmed.status()
        );
        rearmed.endUse(rearmedUse, true);
    }

    @Test
    void initialPrewarmFaultCreatesDisabledOwnerThenFreshFallback() {
        FakeAccess access = new FakeAccess();
        access.failFirstIdentityReset = true;
        RenderThreadPoseStackScratch<FakePoseStack> scratch =
            RenderThreadPoseStackScratch.createForCurrentThread(access);
        FakePoseStack rejected = access.firstCreated;

        assertEquals(
            RenderThreadPoseStackScratch.STATUS_DISABLED,
            scratch.status()
        );
        assertEquals(1L, scratch.disableCount());

        FakePoseStack fallback = scratch.beginUse();
        assertNotSame(rejected, fallback);
        assertTrue(fallback.canonical());
        assertEquals(1L, scratch.freshFallbacks());
    }

    private static final class FakeAccess
        implements RenderThreadPoseStackScratch.Access<FakePoseStack> {
        private FakePoseStack firstCreated;
        private boolean failFirstIdentityReset;

        @Override
        public FakePoseStack createFresh() {
            FakePoseStack created = new FakePoseStack();
            if (this.firstCreated == null) {
                this.firstCreated = created;
                if (this.failFirstIdentityReset) {
                    this.failFirstIdentityReset = false;
                    created.failIdentityReset = true;
                }
            }
            return created;
        }

        @Override
        public void setIdentity(FakePoseStack stack) {
            stack.setIdentity();
        }

        @Override
        public void push(FakePoseStack stack) {
            stack.push();
        }

        @Override
        public void pop(FakePoseStack stack) {
            stack.pop();
        }

        @Override
        public boolean isEmpty(FakePoseStack stack) {
            return stack.depth == 0;
        }

        @Override
        public boolean isIdentity(FakePoseStack stack) {
            return stack.identity;
        }
    }

    private static final class FakePoseStack {
        private final Object[] poseSlots = new Object[8];
        private int depth;
        private int pushCalls;
        private boolean identity = true;
        private boolean failIdentityReset;

        private FakePoseStack() {
            this.poseSlots[0] = new Object();
        }

        private void push() {
            this.pushCalls++;
            int next = this.depth + 1;
            if (this.poseSlots[next] == null) {
                this.poseSlots[next] = new Object();
            }
            this.depth = next;
        }

        private void pop() {
            if (this.depth == 0) {
                throw new IllegalStateException("fake stack underflow");
            }
            this.depth--;
        }

        private void setIdentity() {
            if (this.failIdentityReset) {
                this.failIdentityReset = false;
                throw new IllegalStateException("injected identity fault");
            }
            this.identity = true;
        }

        private Object currentPose() {
            return this.poseSlots[this.depth];
        }

        private boolean canonical() {
            return this.depth == 0 && this.identity;
        }
    }
}
