package de.morau.blockframe.benchmark.phase2a0b;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * Allocation/GC/heap boundary snapshot. It is never called per frame.
 */
public record JvmBoundarySnapshot(
    long renderThreadAllocatedBytes,
    String allocationStatus,
    long gcCollections,
    long gcPauseMillis,
    long heapUsedBytes,
    long heapCommittedBytes,
    long nonHeapUsedBytes,
    String processRssStatus
) {
    public static JvmBoundarySnapshot capture(long renderThreadId) {
        long allocation = -1L;
        String allocationStatus = "NOT_AVAILABLE";
        java.lang.management.ThreadMXBean base =
            ManagementFactory.getThreadMXBean();
        if (base instanceof com.sun.management.ThreadMXBean bean) {
            try {
                if (bean.isThreadAllocatedMemorySupported()) {
                    if (!bean.isThreadAllocatedMemoryEnabled()) {
                        allocationStatus =
                            "NOT_AVAILABLE: allocation tracking disabled";
                    } else {
                        allocation = bean.getThreadAllocatedBytes(
                            renderThreadId
                        );
                        allocationStatus = allocation >= 0L
                            ? "AVAILABLE"
                            : "NOT_AVAILABLE: thread ended";
                    }
                }
            } catch (RuntimeException error) {
                allocation = -1L;
                allocationStatus =
                    "ERROR: " + error.getClass().getSimpleName();
            }
        }
        long collections = 0L;
        long pause = 0L;
        boolean collectionAvailable = false;
        boolean pauseAvailable = false;
        for (
            GarbageCollectorMXBean bean :
                ManagementFactory.getGarbageCollectorMXBeans()
        ) {
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            if (count >= 0L) {
                collections += count;
                collectionAvailable = true;
            }
            if (time >= 0L) {
                pause += time;
                pauseAvailable = true;
            }
        }
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return new JvmBoundarySnapshot(
            allocation,
            allocationStatus,
            collectionAvailable ? collections : -1L,
            pauseAvailable ? pause : -1L,
            memory.getHeapMemoryUsage().getUsed(),
            memory.getHeapMemoryUsage().getCommitted(),
            memory.getNonHeapMemoryUsage().getUsed(),
            "NOT_AVAILABLE: no reliable dependency-free process RSS counter"
        );
    }
}
