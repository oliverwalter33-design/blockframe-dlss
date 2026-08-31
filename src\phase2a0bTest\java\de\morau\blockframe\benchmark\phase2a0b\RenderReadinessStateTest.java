package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class RenderReadinessStateTest {
    @Test
    void oneHundredCallbacksWithoutWorldStayAtClientCallbackSeen() {
        RenderReadinessState readiness = new RenderReadinessState();
        for (int index = 0; index < 100; index++) {
            readiness.observe(
                41L,
                1_000L + index,
                index == 0 ? 10_000L : 0L,
                false,
                false,
                false
            );
        }
        assertEquals(
            RenderReadinessState.State.CLIENT_RENDER_CALLBACK_SEEN,
            readiness.state()
        );
        assertEquals(100L, readiness.totalCallbackCount());
        assertEquals(41L, readiness.renderThreadId());
        assertEquals(0, readiness.ownerPublications());
    }

    @Test
    void readinessAdvancesOnlyFromOneCompleteCallbackContext() {
        RenderReadinessState readiness = new RenderReadinessState();
        assertEquals(
            RenderReadinessState.Decision.STATE_CHANGED,
            readiness.observe(
                7L,
                1L,
                100L,
                true,
                false,
                false
            )
        );
        assertEquals(
            RenderReadinessState.State.WORLD_PRESENT,
            readiness.state()
        );
        readiness.observe(7L, 2L, 0L, true, true, false);
        assertEquals(
            RenderReadinessState.State.PLAYER_PRESENT,
            readiness.state()
        );
        assertEquals(
            RenderReadinessState.Decision.BIND_OWNER,
            readiness.observe(7L, 3L, 0L, true, true, true)
        );
        assertEquals(
            RenderReadinessState.State.RENDER_OWNER_BOUND,
            readiness.state()
        );
        readiness.markReplayArmed(7L);
        assertEquals(
            RenderReadinessState.State.REPLAY_ARMED,
            readiness.state()
        );
        assertEquals(1, readiness.ownerPublications());
    }

    @Test
    void warmArmedHeartbeatPublishesNoSecondOwner() {
        RenderReadinessState readiness = armed(13L);
        for (int index = 0; index < 100_000; index++) {
            assertTrue(readiness.heartbeat(13L));
        }
        assertEquals(1, readiness.ownerPublications());
        assertEquals(
            RenderReadinessState.State.REPLAY_ARMED,
            readiness.state()
        );
        assertEquals(100_001L, readiness.totalCallbackCount());
    }

    @Test
    void warmArmedHeartbeatAllocatesNoJavaObjects() {
        RenderReadinessState readiness = armed(19L);
        com.sun.management.ThreadMXBean bean =
            (com.sun.management.ThreadMXBean)
                ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        long threadId = Thread.currentThread().threadId();
        for (int index = 0; index < 100_000; index++) {
            readiness.heartbeat(19L);
        }
        bean.getThreadAllocatedBytes(threadId);
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < 100_000; index++) {
            readiness.heartbeat(19L);
        }
        long after = bean.getThreadAllocatedBytes(threadId);
        assertEquals(0L, after - before);
    }

    @Test
    void unloadInvalidatesAndNewWorldUsesNewGeneration() {
        RenderReadinessState readiness = new RenderReadinessState();
        readiness.onWorldLifecyclePresent();
        readiness.observe(23L, 1L, 1L, true, true, true);
        readiness.markReplayArmed(23L);
        assertTrue(readiness.invalidateWorld());
        assertEquals(2L, readiness.generation());
        assertEquals(
            RenderReadinessState.State.BOOTSTRAPPED,
            readiness.state()
        );
        assertEquals(0, readiness.ownerPublications());
        readiness.onWorldLifecyclePresent();
        assertEquals(
            RenderReadinessState.Decision.BIND_OWNER,
            readiness.observe(29L, 2L, 0L, true, true, true)
        );
        readiness.markReplayArmed(29L);
        assertEquals(1, readiness.ownerPublications());
        assertEquals(29L, readiness.renderThreadId());
    }

    @Test
    void callbackOnDifferentThreadIsRejected() {
        RenderReadinessState readiness = new RenderReadinessState();
        readiness.observe(31L, 1L, 1L, false, false, false);
        assertEquals(
            RenderReadinessState.Decision.WRONG_THREAD,
            readiness.observe(32L, 2L, 0L, true, true, true)
        );
        assertEquals(31L, readiness.renderThreadId());
        assertEquals(1L, readiness.rejectedWrongThreadCallbacks());
        assertFalse(readiness.ownerAttempted());
    }

    private static RenderReadinessState armed(long threadId) {
        RenderReadinessState readiness = new RenderReadinessState();
        readiness.onWorldLifecyclePresent();
        assertEquals(
            RenderReadinessState.Decision.BIND_OWNER,
            readiness.observe(
                threadId,
                1L,
                1L,
                true,
                true,
                true
            )
        );
        readiness.markReplayArmed(threadId);
        return readiness;
    }
}
