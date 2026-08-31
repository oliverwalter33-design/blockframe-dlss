package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GpuPassDiagnosticsStateTest {
    @AfterEach
    void restoreProductionDefaults() {
        GpuPassDiagnostics.configure(true, true);
    }

    @Test
    void disabledDiagnosticsPublishCachedNoOpState() {
        GpuPassDiagnostics.Snapshot configured =
            GpuPassDiagnostics.configure(false, false);

        assertSame(configured, GpuPassDiagnostics.snapshot());
        assertFalse(configured.debugLabelsRequested());
        assertFalse(configured.debugLabelsSupported());
        assertFalse(configured.debugLabelsEnabled());
        assertFalse(configured.debugLabelsEffective());
        assertFalse(configured.tracyRequested());
        assertFalse(configured.tracySupported());
        assertFalse(configured.tracyEnabled());
        assertFalse(configured.tracyEffective());
    }

    @Test
    void deviceChangeInvalidatesOnlyDeviceScopedLabelFacts() {
        GpuPassDiagnostics.Snapshot configured =
            GpuPassDiagnostics.configure(true, false);
        GpuPassDiagnostics.deviceGenerationChanged();
        GpuPassDiagnostics.Snapshot changed =
            GpuPassDiagnostics.snapshot();

        assertTrue(changed.debugLabelsRequested());
        assertFalse(changed.debugLabelsSupported());
        assertFalse(changed.debugLabelsEffective());
        assertFalse(changed.tracyRequested());
        assertFalse(changed.tracySupported());
        assertFalse(changed.tracyEffective());
        assertTrue(changed != configured);
    }

    @Test
    void disabledTracyNeverInvokesItsOptionalProbe() {
        AtomicInteger probes = new AtomicInteger();
        GpuPassDiagnostics.Snapshot configured =
            GpuPassDiagnostics.configure(
                false,
                false,
                () -> {
                    probes.incrementAndGet();
                    return true;
                }
            );

        assertEquals(0, probes.get());
        assertFalse(configured.tracyRequested());
        assertFalse(configured.tracySupported());
        assertFalse(configured.tracyEffective());
    }
}
