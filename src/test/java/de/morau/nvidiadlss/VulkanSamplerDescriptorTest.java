package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSamplerReductionModeCreateInfo;

class VulkanSamplerDescriptorTest {
    @Test
    void captureReplayPreservesEveryCoreFieldAndReductionMode() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerReductionModeCreateInfo reduction =
                VkSamplerReductionModeCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .reductionMode(
                        VK12.VK_SAMPLER_REDUCTION_MODE_MIN
                    );
            VkSamplerCreateInfo source = sampler(stack)
                .pNext(reduction);

            VulkanSamplerDescriptor original =
                VulkanSamplerDescriptor.capture(source);

            assertTrue(original.canReplay());
            assertEquals(37, original.flags());
            assertEquals(VK10.VK_FILTER_NEAREST, original.magFilter());
            assertEquals(VK10.VK_FILTER_LINEAR, original.minFilter());
            assertEquals(
                VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST,
                original.mipmapMode()
            );
            assertEquals(
                VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT,
                original.addressModeU()
            );
            assertEquals(
                VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                original.addressModeV()
            );
            assertEquals(
                VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
                original.addressModeW()
            );
            assertEquals(-0.375F, original.mipLodBias());
            assertEquals(1, original.anisotropyEnable());
            assertEquals(4.0F, original.maxAnisotropy());
            assertEquals(1, original.compareEnable());
            assertEquals(
                VK10.VK_COMPARE_OP_LESS_OR_EQUAL,
                original.compareOp()
            );
            assertEquals(0.5F, original.minLod());
            assertEquals(12.25F, original.maxLod());
            assertEquals(
                VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE,
                original.borderColor()
            );
            assertEquals(0, original.unnormalizedCoordinates());
            assertTrue(original.reductionModePresent());
            assertEquals(
                VK12.VK_SAMPLER_REDUCTION_MODE_MIN,
                original.reductionMode()
            );

            float finalBias = -1.9599625F;
            VkSamplerCreateInfo replayed =
                VkSamplerCreateInfo.calloc(stack).sType$Default();
            original.replayInto(replayed, finalBias, stack);
            VulkanSamplerDescriptor actual =
                VulkanSamplerDescriptor.capture(replayed);

            assertTrue(actual.canReplay());
            assertTrue(actual.matchesReplayOf(original, finalBias));
            assertEquals(finalBias, actual.mipLodBias());

            replayed.addressModeW(
                VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT
            );
            assertFalse(
                VulkanSamplerDescriptor
                    .capture(replayed)
                    .matchesReplayOf(original, finalBias)
            );
        }
    }

    @Test
    void unknownPNextFailsClosedWithoutReplayingIt() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerReductionModeCreateInfo unknown =
                VkSamplerReductionModeCreateInfo
                    .calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO);
            VulkanSamplerDescriptor descriptor =
                VulkanSamplerDescriptor.capture(
                    sampler(stack).pNext(unknown)
                );

            assertFalse(descriptor.canReplay());
            assertEquals(
                VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO,
                descriptor.unsupportedPNextSType()
            );
        }
    }

    private static VkSamplerCreateInfo sampler(MemoryStack stack) {
        return VkSamplerCreateInfo
            .calloc(stack)
            .sType$Default()
            .flags(37)
            .magFilter(VK10.VK_FILTER_NEAREST)
            .minFilter(VK10.VK_FILTER_LINEAR)
            .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
            .addressModeU(
                VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
            )
            .addressModeV(
                VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
            )
            .addressModeW(
                VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER
            )
            .mipLodBias(-0.375F)
            .anisotropyEnable(true)
            .maxAnisotropy(4.0F)
            .compareEnable(true)
            .compareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL)
            .minLod(0.5F)
            .maxLod(12.25F)
            .borderColor(VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
            .unnormalizedCoordinates(false);
    }
}
