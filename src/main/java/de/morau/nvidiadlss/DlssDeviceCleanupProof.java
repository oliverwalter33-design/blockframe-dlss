package de.morau.nvidiadlss;

/**
 * Generation-scoped sticky proof for DLSS Vulkan cleanup.
 *
 * <p>A later successful retry can finish mechanical cleanup, but it cannot
 * erase an earlier uncertain result from the normal-shutdown proof. Only a
 * genuinely accepted new device generation resets the proof.</p>
 */
final class DlssDeviceCleanupProof {
    private boolean failed;

    synchronized void beginGeneration() {
        this.failed = false;
    }

    synchronized boolean recordPrepare(boolean complete) {
        if (!complete) {
            this.failed = true;
        }
        return complete;
    }

    synchronized boolean recordFinish(boolean complete) {
        if (!complete) {
            this.failed = true;
        }
        return complete;
    }

    synchronized void recordDeviceClosed(boolean finishConfirmed) {
        if (!finishConfirmed) {
            this.failed = true;
        }
    }

    synchronized boolean reportClientClose(
        boolean clientResourcesClosed
    ) {
        return clientResourcesClosed && !this.failed;
    }

    synchronized boolean failed() {
        return this.failed;
    }
}
