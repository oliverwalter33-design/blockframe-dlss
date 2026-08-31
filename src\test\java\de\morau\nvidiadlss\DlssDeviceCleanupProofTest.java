package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DlssDeviceCleanupProofTest {
    @Test
    void prepareFailureStaysStickyUntilANewGeneration() {
        DlssDeviceCleanupProof proof = new DlssDeviceCleanupProof();
        proof.beginGeneration();

        assertFalse(proof.recordPrepare(false));
        assertTrue(proof.recordPrepare(true));
        assertTrue(proof.recordFinish(true));
        proof.recordDeviceClosed(true);

        assertTrue(proof.failed());
        assertFalse(proof.reportClientClose(true));

        proof.beginGeneration();
        assertFalse(proof.failed());
        assertTrue(proof.reportClientClose(true));
    }

    @Test
    void finishFailureAndUnconfirmedDeviceCloseCannotReportClean() {
        DlssDeviceCleanupProof finish = new DlssDeviceCleanupProof();
        finish.beginGeneration();
        assertTrue(finish.recordPrepare(true));
        assertFalse(finish.recordFinish(false));
        assertTrue(finish.recordFinish(true));
        assertFalse(finish.reportClientClose(true));

        DlssDeviceCleanupProof sealed = new DlssDeviceCleanupProof();
        sealed.beginGeneration();
        sealed.recordDeviceClosed(false);
        assertFalse(sealed.reportClientClose(true));
    }

    @Test
    void clientResourceFailureAlsoPreventsCleanReport() {
        DlssDeviceCleanupProof proof = new DlssDeviceCleanupProof();
        proof.beginGeneration();
        assertTrue(proof.recordPrepare(true));
        assertTrue(proof.recordFinish(true));
        proof.recordDeviceClosed(true);

        assertFalse(proof.reportClientClose(false));
        assertTrue(proof.reportClientClose(true));
    }

    @Test
    void nativeStreamlineShutdownFailureReachesTheClientProof() {
        assertTrue(
            DlssBootstrap.runNativeShutdownAndReport(() -> {})
        );
        assertFalse(
            DlssBootstrap.runNativeShutdownAndReport(
                () -> {
                    throw new IllegalStateException(
                        "injected native shutdown failure"
                    );
                }
            )
        );

        DlssDeviceCleanupProof proof = new DlssDeviceCleanupProof();
        proof.beginGeneration();
        assertTrue(proof.recordPrepare(true));
        assertFalse(proof.recordFinish(false));
        assertFalse(proof.reportClientClose(true));
    }
}
