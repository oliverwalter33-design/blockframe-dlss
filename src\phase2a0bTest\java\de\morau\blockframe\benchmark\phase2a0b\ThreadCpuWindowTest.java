package de.morau.blockframe.benchmark.phase2a0b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadCpuWindowTest {
    @Test
    void takesTwoSnapshotsAndRestoresPriorCpuTimeState() {
        FakeAccess access = new FakeAccess(false);
        access.add(1, "Render thread", Thread.State.RUNNABLE, 100, 80);
        access.add(2, "Server thread", Thread.State.WAITING, 200, 150);
        access.add(3, "Worker-Main-1", Thread.State.RUNNABLE, 300, 200);
        ThreadCpuWindow window = new ThreadCpuWindow(access, true);

        window.prepare();
        window.begin();
        access.advance(1, 50, 40);
        access.advance(2, 20, 10);
        access.advance(3, 80, 50);
        ThreadCpuWindow.Result result = window.end(8);

        assertTrue(result.enabled());
        assertEquals(50L, result.renderCpuNanos());
        assertEquals(20L, result.serverCpuNanos());
        assertEquals(80L, result.mojangWorkerCpuNanos());
        assertEquals(150L, result.totalCpuNanos());
        assertFalse(access.enabled);
        assertEquals(2, access.enableChanges);
        assertEquals(3, window.stableSlotCount());
        assertEquals(2, access.allThreadIdQueries);
        assertEquals(2, result.boundarySnapshotCount());
    }

    @Test
    void unsupportedAndDisabledModesAreSafeNoOps() {
        FakeAccess unsupported = new FakeAccess(false);
        unsupported.supported = false;
        unsupported.add(1, "main", Thread.State.RUNNABLE, 1, 1);
        ThreadCpuWindow first = new ThreadCpuWindow(unsupported, true);
        first.prepare();
        first.begin();
        ThreadCpuWindow.Result unsupportedResult = first.end(-1);
        assertFalse(unsupportedResult.enabled());
        assertTrue(unsupportedResult.status().contains("NOT_AVAILABLE"));

        FakeAccess disabled = new FakeAccess(false);
        ThreadCpuWindow second = new ThreadCpuWindow(disabled, false);
        second.prepare();
        second.begin();
        ThreadCpuWindow.Result disabledResult = second.end(-1);
        assertFalse(disabledResult.enabled());
        assertEquals(0, disabled.allThreadIdQueries);
    }

    @Test
    void discoveryFailureIsFieldLevelUnavailable() {
        FakeAccess access = new FakeAccess(false);
        access.failDiscovery = true;
        ThreadCpuWindow window = new ThreadCpuWindow(access, true);
        window.prepare();
        window.begin();
        ThreadCpuWindow.Result result = window.end(8);
        assertFalse(result.enabled());
        assertTrue(result.status().startsWith("ERROR:"));
    }

    @Test
    void threadBirthDeathUnknownAndSingleValueErrorAreIsolated() {
        FakeAccess access = new FakeAccess(true);
        access.add(10, "", Thread.State.RUNNABLE, 10, 5);
        access.add(20, "Worker-Main-2", Thread.State.RUNNABLE, 20, 10);
        ThreadCpuWindow window = new ThreadCpuWindow(access, true);
        window.prepare();
        window.begin();
        access.failCpuId = 10;
        access.remove(20);
        access.add(30, "late thread", Thread.State.RUNNABLE, 1, 1);
        ThreadCpuWindow.Result result = window.end(8);

        assertTrue(result.threadSetChanged());
        assertEquals(1, result.bornThreads());
        assertEquals(1, result.endedThreads());
        assertEquals(
            ThreadCategory.UNKNOWN,
            result.threadCategories()[0]
        );
        assertTrue(result.perThreadStatus()[0].startsWith("ERROR:"));
    }

    @Test
    void stableClassifierKeepsUncertainAndKnownCategoriesSeparate() {
        assertEquals(
            ThreadCategory.RENDER,
            ThreadCpuWindow.classify("Render thread")
        );
        assertEquals(
            ThreadCategory.INTEGRATED_SERVER,
            ThreadCpuWindow.classify("Server thread")
        );
        assertEquals(
            ThreadCategory.BLOCKFRAME_WORKER,
            ThreadCpuWindow.classify("BlockFrame job worker 1")
        );
        assertEquals(ThreadCategory.UNKNOWN, ThreadCpuWindow.classify(""));
        assertEquals(
            ThreadCategory.OTHER,
            ThreadCpuWindow.classify("Netty Client IO #0")
        );
    }

    private static final class FakeAccess implements ThreadCpuWindow.Access {
        private final Map<Long, Slot> slots = new HashMap<>();
        private boolean supported = true;
        private boolean enabled;
        private int enableChanges;
        private int allThreadIdQueries;
        private long failCpuId = Long.MIN_VALUE;
        private boolean failDiscovery;

        private FakeAccess(boolean enabled) {
            this.enabled = enabled;
        }

        void add(
            long id,
            String name,
            Thread.State state,
            long cpu,
            long user
        ) {
            this.slots.put(id, new Slot(name, state, cpu, user));
        }

        void remove(long id) {
            this.slots.remove(id);
        }

        void advance(long id, long cpu, long user) {
            Slot slot = this.slots.get(id);
            slot.cpu += cpu;
            slot.user += user;
        }

        @Override
        public boolean cpuTimeSupported() {
            return this.supported;
        }

        @Override
        public boolean cpuTimeEnabled() {
            return this.enabled;
        }

        @Override
        public void cpuTimeEnabled(boolean enabled) {
            this.enabled = enabled;
            this.enableChanges++;
        }

        @Override
        public long[] allThreadIds() {
            this.allThreadIdQueries++;
            if (this.failDiscovery) {
                throw new IllegalStateException("discovery failed");
            }
            return this.slots.keySet().stream()
                .mapToLong(Long::longValue)
                .toArray();
        }

        @Override
        public ThreadCpuWindow.Descriptor descriptor(long threadId) {
            Slot slot = this.slots.get(threadId);
            return slot == null
                ? null
                : new ThreadCpuWindow.Descriptor(slot.name, slot.state);
        }

        @Override
        public long cpuTime(long threadId) {
            if (threadId == this.failCpuId) {
                throw new IllegalStateException("one field failed");
            }
            Slot slot = this.slots.get(threadId);
            return slot == null ? -1L : slot.cpu;
        }

        @Override
        public long userTime(long threadId) {
            Slot slot = this.slots.get(threadId);
            return slot == null ? -1L : slot.user;
        }
    }

    private static final class Slot {
        private final String name;
        private final Thread.State state;
        private long cpu;
        private long user;

        private Slot(
            String name,
            Thread.State state,
            long cpu,
            long user
        ) {
            this.name = name;
            this.state = state;
            this.cpu = cpu;
            this.user = user;
        }
    }
}
