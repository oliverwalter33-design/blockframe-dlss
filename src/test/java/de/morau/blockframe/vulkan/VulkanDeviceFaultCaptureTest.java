package de.morau.blockframe.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDeviceFaultAddressInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultVendorInfoEXT;

class VulkanDeviceFaultCaptureTest {
    @Test
    void successfulCaptureIsStrictlyBoundedAndNeverRequestsBinary() {
        AtomicInteger calls = new AtomicInteger();
        VulkanDeviceFaultCapture capture =
            VulkanDeviceFaultCapture.forQuery((counts, info) -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    counts.addressInfoCount(100);
                    counts.vendorInfoCount(70);
                    counts.vendorBinarySize(4_096L);
                    return VK10.VK_SUCCESS;
                }

                assertEquals(
                    VulkanDeviceFaultCapture.MAX_ADDRESS_INFOS,
                    counts.addressInfoCount()
                );
                assertEquals(
                    VulkanDeviceFaultCapture.MAX_VENDOR_INFOS,
                    counts.vendorInfoCount()
                );
                assertEquals(0L, counts.vendorBinarySize());
                assertEquals(
                    0L,
                    MemoryUtil.memGetAddress(
                        info.address()
                            + VkDeviceFaultInfoEXT.PVENDORBINARYDATA
                    )
                );
                writeString(info.description(), "bounded fault");
                long addresses = MemoryUtil.memGetAddress(
                    info.address() + VkDeviceFaultInfoEXT.PADDRESSINFOS
                );
                long vendors = MemoryUtil.memGetAddress(
                    info.address() + VkDeviceFaultInfoEXT.PVENDORINFOS
                );
                assertNotEquals(0L, addresses);
                assertNotEquals(0L, vendors);
                MemoryUtil.memPutInt(
                    addresses + VkDeviceFaultAddressInfoEXT.ADDRESSTYPE,
                    2
                );
                MemoryUtil.memPutLong(
                    addresses
                        + VkDeviceFaultAddressInfoEXT.REPORTEDADDRESS,
                    0x1234L
                );
                MemoryUtil.memPutLong(
                    addresses
                        + VkDeviceFaultAddressInfoEXT.ADDRESSPRECISION,
                    64L
                );
                writeString(
                    MemoryUtil.memByteBuffer(
                        vendors
                            + VkDeviceFaultVendorInfoEXT.DESCRIPTION,
                        256
                    ),
                    "vendor metadata"
                );
                MemoryUtil.memPutLong(
                    vendors
                        + VkDeviceFaultVendorInfoEXT.VENDORFAULTCODE,
                    7L
                );
                MemoryUtil.memPutLong(
                    vendors
                        + VkDeviceFaultVendorInfoEXT.VENDORFAULTDATA,
                    9L
                );
                return VK10.VK_INCOMPLETE;
            });

        var result = capture.capture();

        assertEquals(2, calls.get());
        assertTrue(result.available());
        assertTrue(result.truncated());
        assertEquals("bounded fault", result.description());
        assertEquals(100L, result.addressInfoCountReported());
        assertEquals(
            VulkanDeviceFaultCapture.MAX_ADDRESS_INFOS,
            result.addressInfos().size()
        );
        assertEquals(2, result.addressInfos().getFirst().addressType());
        assertEquals(0x1234L, result.addressInfos().getFirst().reportedAddress());
        assertEquals(70L, result.vendorInfoCountReported());
        assertEquals(
            VulkanDeviceFaultCapture.MAX_VENDOR_INFOS,
            result.vendorInfos().size()
        );
        assertEquals(
            "vendor metadata",
            result.vendorInfos().getFirst().description()
        );
        assertEquals(4_096L, result.vendorBinaryBytesReported());
    }

    @Test
    void countsFailureStopsAfterOneCall() {
        AtomicInteger calls = new AtomicInteger();
        var result = VulkanDeviceFaultCapture.forQuery((counts, info) -> {
            calls.incrementAndGet();
            return VK10.VK_ERROR_UNKNOWN;
        }).capture();

        assertEquals(1, calls.get());
        assertFalse(result.available());
        assertEquals(
            "counts-result:" + VK10.VK_ERROR_UNKNOWN,
            result.unavailableReason()
        );
    }

    @Test
    void infoFailureStopsAfterSecondCallWithoutInterpretingData() {
        AtomicInteger calls = new AtomicInteger();
        var result = VulkanDeviceFaultCapture.forQuery((counts, info) -> {
            if (calls.incrementAndGet() == 1) {
                counts.addressInfoCount(1);
                return VK10.VK_SUCCESS;
            }
            return VK10.VK_ERROR_OUT_OF_HOST_MEMORY;
        }).capture();

        assertEquals(2, calls.get());
        assertFalse(result.available());
        assertTrue(result.addressInfos().isEmpty());
        assertEquals(
            "info-result:" + VK10.VK_ERROR_OUT_OF_HOST_MEMORY,
            result.unavailableReason()
        );
    }

    @Test
    void backendAllocationFailureBecomesUnavailable() {
        var result = VulkanDeviceFaultCapture.forQuery((counts, info) -> {
            throw new OutOfMemoryError("injected");
        }).capture();

        assertFalse(result.available());
        assertEquals(
            "capture-failed:OutOfMemoryError",
            result.unavailableReason()
        );
    }

    private static void writeString(ByteBuffer target, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        target.put(0, encoded, 0, encoded.length);
        target.put(encoded.length, (byte)0);
    }
}
