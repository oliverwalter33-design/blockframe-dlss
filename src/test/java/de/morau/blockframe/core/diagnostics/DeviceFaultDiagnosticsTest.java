package de.morau.blockframe.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeviceFaultDiagnosticsTest {
    @Test
    void unsupportedAndMissingFunctionStatesRemainExplicit() {
        DeviceFaultDiagnostics diagnostics = new DeviceFaultDiagnostics();
        Object device = new Object();

        diagnostics.publishNegotiation(
            true,
            false,
            false,
            false,
            "extension-not-supported"
        );
        var unsupported = diagnostics.snapshot();
        assertTrue(unsupported.requested());
        assertFalse(unsupported.extensionSupported());
        assertFalse(unsupported.enabled());
        assertEquals(
            DeviceFaultDiagnostics.CaptureStatus.UNAVAILABLE,
            unsupported.captureStatus()
        );

        diagnostics.publishNegotiation(true, true, true, true, "");
        diagnostics.attachVulkanDevice(
            device,
            false,
            null,
            "function-unresolved"
        );
        var missingFunction = diagnostics.snapshot();
        assertTrue(missingFunction.enabled());
        assertFalse(missingFunction.functionResolved());
        assertEquals(
            "function-unresolved",
            missingFunction.unavailableReason()
        );
    }

    @Test
    void onlyExactDeviceLossCapturesAndOnlyOncePerGeneration() {
        DeviceFaultDiagnostics diagnostics = new DeviceFaultDiagnostics();
        Object device = new Object();
        AtomicInteger calls = new AtomicInteger();
        diagnostics.publishNegotiation(true, true, true, true, "");
        diagnostics.attachVulkanDevice(
            device,
            true,
            () -> {
                calls.incrementAndGet();
                return captured("fault");
            },
            ""
        );

        diagnostics.recordResult(device, 0, "success");
        diagnostics.recordResult(device, -13, "unknown");
        assertEquals(0, calls.get());
        assertEquals(
            DeviceFaultDiagnostics.CaptureStatus.READY_NOT_CAPTURED,
            diagnostics.snapshot().captureStatus()
        );

        diagnostics.recordResult(
            device,
            DeviceFaultDiagnostics.VK_ERROR_DEVICE_LOST,
            "present"
        );
        diagnostics.recordResult(
            device,
            DeviceFaultDiagnostics.VK_ERROR_DEVICE_LOST,
            "second"
        );
        assertEquals(1, calls.get());
        assertTrue(diagnostics.snapshot().captureAttempted());
        assertEquals(
            DeviceFaultDiagnostics.CaptureStatus.CAPTURED,
            diagnostics.snapshot().captureStatus()
        );
        assertEquals("present", diagnostics.snapshot().captureContext());
    }

    @Test
    void staleCloseAndLossCannotAffectANewerGeneration() {
        DeviceFaultDiagnostics diagnostics = new DeviceFaultDiagnostics();
        Object first = new Object();
        Object second = new Object();
        AtomicInteger secondCalls = new AtomicInteger();

        diagnostics.publishNegotiation(true, true, true, true, "");
        diagnostics.attachVulkanDevice(
            first,
            true,
            () -> captured("first"),
            ""
        );
        long firstGeneration = diagnostics.snapshot().generation();
        diagnostics.vulkanDeviceClosing(first);

        diagnostics.publishNegotiation(true, true, true, true, "");
        diagnostics.attachVulkanDevice(
            second,
            true,
            () -> {
                secondCalls.incrementAndGet();
                return captured("second");
            },
            ""
        );
        long secondGeneration = diagnostics.snapshot().generation();
        assertTrue(secondGeneration > firstGeneration);

        diagnostics.vulkanDeviceClosing(first);
        diagnostics.recordResult(
            first,
            DeviceFaultDiagnostics.VK_ERROR_DEVICE_LOST,
            "stale"
        );
        assertEquals(secondGeneration, diagnostics.snapshot().generation());
        assertTrue(diagnostics.snapshot().functionResolved());
        assertEquals(0, secondCalls.get());

        diagnostics.recordResult(
            second,
            DeviceFaultDiagnostics.VK_ERROR_DEVICE_LOST,
            "current"
        );
        assertEquals(1, secondCalls.get());
        assertTrue(diagnostics.snapshot().staleDeviceEvents() >= 2L);
    }

    @Test
    void competingLiveOwnerFailsClosed() {
        DeviceFaultDiagnostics diagnostics = new DeviceFaultDiagnostics();
        diagnostics.publishNegotiation(true, true, true, true, "");
        diagnostics.attachVulkanDevice(
            new Object(),
            true,
            () -> captured("first"),
            ""
        );
        diagnostics.attachVulkanDevice(
            new Object(),
            true,
            () -> captured("second"),
            ""
        );

        assertFalse(diagnostics.snapshot().enabled());
        assertFalse(diagnostics.snapshot().functionResolved());
        assertEquals(
            "device-owner-conflict",
            diagnostics.snapshot().unavailableReason()
        );
    }

    @Test
    void captureFailureCloseAndOpenGlRemainFailOpen() {
        DeviceFaultDiagnostics diagnostics = new DeviceFaultDiagnostics();
        Object device = new Object();
        diagnostics.publishNegotiation(true, true, true, true, "");
        diagnostics.attachVulkanDevice(
            device,
            true,
            () -> {
                throw new OutOfMemoryError("injected");
            },
            ""
        );
        diagnostics.recordResult(
            device,
            DeviceFaultDiagnostics.VK_ERROR_DEVICE_LOST,
            "wait"
        );
        assertEquals(
            DeviceFaultDiagnostics.CaptureStatus.UNAVAILABLE,
            diagnostics.snapshot().captureStatus()
        );
        assertEquals(
            "capture-threw:OutOfMemoryError",
            diagnostics.snapshot().unavailableReason()
        );

        diagnostics.vulkanDeviceClosing(device);
        assertEquals(
            DeviceFaultDiagnostics.CaptureStatus.CLOSED,
            diagnostics.snapshot().captureStatus()
        );
        diagnostics.notVulkanBackend();
        assertFalse(diagnostics.snapshot().requested());
        assertEquals("not-vulkan", diagnostics.snapshot().unavailableReason());

        diagnostics.close();
        diagnostics.publishNegotiation(true, true, true, true, "");
        assertEquals("closed", diagnostics.snapshot().unavailableReason());
    }

    private static DeviceFaultDiagnostics.CaptureResult captured(
        String description
    ) {
        return DeviceFaultDiagnostics.CaptureResult.captured(
            false,
            description,
            1L,
            List.of(new DeviceFaultDiagnostics.AddressInfo(1, 2L, 3L)),
            0L,
            List.of(),
            0L
        );
    }
}
