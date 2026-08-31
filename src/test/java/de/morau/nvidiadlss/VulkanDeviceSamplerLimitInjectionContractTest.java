package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VulkanDeviceSamplerLimitInjectionContractTest {
    @Test
    void captureRunsAfterSuperAndBeforePhysicalProbeClose() throws Exception {
        String source = Files.readString(
            Path.of(
                "src/main/java/de/morau/nvidiadlss/mixin/",
                "VulkanDeviceMixin.java"
            ),
            StandardCharsets.UTF_8
        );
        int method = source.indexOf("blockframe$captureSamplerLimits");
        assertTrue(method >= 0, "sampler-limit capture hook missing");
        String annotation = source.substring(
            source.lastIndexOf("@Inject", method),
            method
        );

        assertFalse(
            annotation.contains("@At(\"HEAD\")"),
            "a non-static constructor HEAD hook is rejected before super()"
        );
        assertTrue(
            annotation.contains(
                "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;close()V"
            ),
            "the probe must still own its VkPhysicalDeviceProperties storage"
        );
        assertTrue(annotation.contains("shift = At.Shift.BEFORE"));
        assertTrue(annotation.contains("require = 1"));
        assertTrue(
            source.indexOf("maxSamplerLodBias()", method) > method,
            "the hook must read the device limit at that injection point"
        );
    }
}
