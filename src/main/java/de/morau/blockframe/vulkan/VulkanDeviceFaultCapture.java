package de.morau.blockframe.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import de.morau.blockframe.core.diagnostics.DeviceFaultDiagnostics;
import de.morau.blockframe.core.diagnostics.DeviceFaultDiagnostics.AddressInfo;
import de.morau.blockframe.core.diagnostics.DeviceFaultDiagnostics.CaptureResult;
import de.morau.blockframe.core.diagnostics.DeviceFaultDiagnostics.VendorInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDeviceFault;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceFaultAddressInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultCountsEXT;
import org.lwjgl.vulkan.VkDeviceFaultInfoEXT;
import org.lwjgl.vulkan.VkDeviceFaultVendorInfoEXT;

/**
 * Bounded, one-shot Vulkan adapter used only after an actual device loss.
 */
public final class VulkanDeviceFaultCapture
    implements DeviceFaultDiagnostics.Capture {
    public static final int MAX_ADDRESS_INFOS = 32;
    public static final int MAX_VENDOR_INFOS = 32;
    public static final long MAX_VENDOR_BINARY_BYTES = 0L;

    private final Query query;

    private VulkanDeviceFaultCapture(Query query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public static Binding bind(VulkanDevice device, boolean enabled) {
        Objects.requireNonNull(device, "device");
        if (!enabled) {
            return Binding.unavailable("feature-not-enabled");
        }
        try {
            VkDevice vkDevice = device.vkDevice();
            boolean resolved =
                vkDevice.getCapabilities().VK_EXT_device_fault
                    && vkDevice.getCapabilities()
                        .vkGetDeviceFaultInfoEXT != 0L;
            if (!resolved) {
                return Binding.unavailable("function-unresolved");
            }
            return new Binding(
                true,
                new VulkanDeviceFaultCapture(
                    (counts, info) ->
                        EXTDeviceFault.vkGetDeviceFaultInfoEXT(
                            vkDevice,
                            counts,
                            info
                        )
                ),
                ""
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            return Binding.unavailable(
                "function-resolution-failed:"
                    + error.getClass().getSimpleName()
            );
        }
    }

    /** Package-visible test seam; no production polling or second owner. */
    static VulkanDeviceFaultCapture forQuery(Query query) {
        return new VulkanDeviceFaultCapture(query);
    }

    @Override
    public CaptureResult capture() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceFaultCountsEXT counts =
                VkDeviceFaultCountsEXT.calloc(stack).sType$Default();
            int countResult = this.query.invoke(counts, null);
            if (!successful(countResult)) {
                return CaptureResult.unavailable(
                    "counts-result:" + countResult
                );
            }

            long addressReported =
                Integer.toUnsignedLong(counts.addressInfoCount());
            long vendorReported =
                Integer.toUnsignedLong(counts.vendorInfoCount());
            long vendorBinaryReported = counts.vendorBinarySize();
            int addressCapacity = (int)Math.min(
                addressReported,
                MAX_ADDRESS_INFOS
            );
            int vendorCapacity = (int)Math.min(
                vendorReported,
                MAX_VENDOR_INFOS
            );
            VkDeviceFaultAddressInfoEXT.Buffer addresses =
                addressCapacity == 0
                    ? null
                    : VkDeviceFaultAddressInfoEXT.calloc(
                        addressCapacity,
                        stack
                    );
            VkDeviceFaultVendorInfoEXT.Buffer vendors =
                vendorCapacity == 0
                    ? null
                    : VkDeviceFaultVendorInfoEXT.calloc(
                        vendorCapacity,
                        stack
                    );
            VkDeviceFaultInfoEXT info =
                VkDeviceFaultInfoEXT.calloc(stack).sType$Default();
            if (addresses != null) {
                MemoryUtil.memPutAddress(
                    info.address()
                        + VkDeviceFaultInfoEXT.PADDRESSINFOS,
                    addresses.address()
                );
            }
            if (vendors != null) {
                MemoryUtil.memPutAddress(
                    info.address() + VkDeviceFaultInfoEXT.PVENDORINFOS,
                    vendors.address()
                );
            }
            // The calloc-null vendor-binary pointer is intentional.
            counts.addressInfoCount(addressCapacity);
            counts.vendorInfoCount(vendorCapacity);
            counts.vendorBinarySize(MAX_VENDOR_BINARY_BYTES);

            int infoResult = this.query.invoke(counts, info);
            if (!successful(infoResult)) {
                return CaptureResult.unavailable(
                    "info-result:" + infoResult
                );
            }

            int addressCaptured = (int)Math.min(
                Integer.toUnsignedLong(counts.addressInfoCount()),
                addressCapacity
            );
            int vendorCaptured = (int)Math.min(
                Integer.toUnsignedLong(counts.vendorInfoCount()),
                vendorCapacity
            );
            List<AddressInfo> addressInfos =
                new ArrayList<>(addressCaptured);
            for (int index = 0; index < addressCaptured; index++) {
                VkDeviceFaultAddressInfoEXT value =
                    addresses.get(index);
                addressInfos.add(
                    new AddressInfo(
                        value.addressType(),
                        value.reportedAddress(),
                        value.addressPrecision()
                    )
                );
            }
            List<VendorInfo> vendorInfos =
                new ArrayList<>(vendorCaptured);
            for (int index = 0; index < vendorCaptured; index++) {
                VkDeviceFaultVendorInfoEXT value = vendors.get(index);
                vendorInfos.add(
                    new VendorInfo(
                        decodeBounded(value.description()),
                        value.vendorFaultCode(),
                        value.vendorFaultData()
                    )
                );
            }
            boolean truncated =
                countResult == VK10.VK_INCOMPLETE
                    || infoResult == VK10.VK_INCOMPLETE
                    || addressReported > addressCapacity
                    || vendorReported > vendorCapacity
                    || vendorBinaryReported != 0L;
            return CaptureResult.captured(
                truncated,
                decodeBounded(info.description()),
                addressReported,
                addressInfos,
                vendorReported,
                vendorInfos,
                vendorBinaryReported
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            return CaptureResult.unavailable(
                "capture-failed:"
                    + error.getClass().getSimpleName()
            );
        }
    }

    private static boolean successful(int result) {
        return result == VK10.VK_SUCCESS
            || result == VK10.VK_INCOMPLETE;
    }

    private static String decodeBounded(ByteBuffer source) {
        ByteBuffer bytes = source.duplicate();
        int start = bytes.position();
        int limit = Math.min(bytes.limit(), start + 256);
        int end = start;
        while (end < limit && bytes.get(end) != 0) {
            end++;
        }
        bytes.limit(end);
        return StandardCharsets.UTF_8.decode(bytes).toString();
    }

    @FunctionalInterface
    interface Query {
        int invoke(
            VkDeviceFaultCountsEXT counts,
            VkDeviceFaultInfoEXT info
        );
    }

    public record Binding(
        boolean functionResolved,
        DeviceFaultDiagnostics.Capture capture,
        String unavailableReason
    ) {
        public Binding {
            unavailableReason = Objects.requireNonNull(
                unavailableReason,
                "unavailableReason"
            );
        }

        private static Binding unavailable(String reason) {
            return new Binding(false, null, reason);
        }
    }
}
