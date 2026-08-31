package de.morau.nvidiadlss;

import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBaseInStructure;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSamplerReductionModeCreateInfo;

/**
 * Immutable, value-based copy of the Vulkan sampler state that is actually
 * passed to {@code vkCreateSampler}. Float values are retained as raw bits so
 * the cache and clone verifier never normalize the original state.
 *
 * <p>The only pNext structure replayed here is the core Vulkan 1.2
 * {@link VkSamplerReductionModeCreateInfo}. Any other structure makes the
 * descriptor non-replayable; callers must keep using the original sampler.</p>
 */
public record VulkanSamplerDescriptor(
    int sType,
    int flags,
    int magFilter,
    int minFilter,
    int mipmapMode,
    int addressModeU,
    int addressModeV,
    int addressModeW,
    int mipLodBiasBits,
    int anisotropyEnable,
    int maxAnisotropyBits,
    int compareEnable,
    int compareOp,
    int minLodBits,
    int maxLodBits,
    int borderColor,
    int unnormalizedCoordinates,
    boolean reductionModePresent,
    int reductionMode,
    int unsupportedPNextSType
) {
    static final int NO_UNSUPPORTED_PNEXT = Integer.MIN_VALUE;
    private static final int MALFORMED_PNEXT = Integer.MAX_VALUE;
    private static final int MAX_PNEXT_STRUCTURES = 16;

    public static VulkanSamplerDescriptor capture(
        VkSamplerCreateInfo createInfo
    ) {
        Objects.requireNonNull(createInfo, "createInfo");
        boolean reductionPresent = false;
        int capturedReductionMode =
            VK12.VK_SAMPLER_REDUCTION_MODE_WEIGHTED_AVERAGE;
        int unsupportedSType = NO_UNSUPPORTED_PNEXT;
        VkBaseInStructure next = VkBaseInStructure.createSafe(
            createInfo.pNext()
        );
        int traversed = 0;
        while (next != null) {
            if (traversed++ == MAX_PNEXT_STRUCTURES) {
                unsupportedSType = MALFORMED_PNEXT;
                break;
            }
            int nextSType = next.sType();
            if (
                nextSType
                        == VK12
                            .VK_STRUCTURE_TYPE_SAMPLER_REDUCTION_MODE_CREATE_INFO
                    && !reductionPresent
            ) {
                capturedReductionMode =
                    VkSamplerReductionModeCreateInfo
                        .create(next.address())
                        .reductionMode();
                reductionPresent = true;
            } else {
                unsupportedSType = nextSType;
                break;
            }
            next = next.pNext();
        }
        long address = createInfo.address();
        return new VulkanSamplerDescriptor(
            createInfo.sType(),
            createInfo.flags(),
            createInfo.magFilter(),
            createInfo.minFilter(),
            createInfo.mipmapMode(),
            createInfo.addressModeU(),
            createInfo.addressModeV(),
            createInfo.addressModeW(),
            Float.floatToRawIntBits(createInfo.mipLodBias()),
            VkSamplerCreateInfo.nanisotropyEnable(address),
            Float.floatToRawIntBits(createInfo.maxAnisotropy()),
            VkSamplerCreateInfo.ncompareEnable(address),
            createInfo.compareOp(),
            Float.floatToRawIntBits(createInfo.minLod()),
            Float.floatToRawIntBits(createInfo.maxLod()),
            createInfo.borderColor(),
            VkSamplerCreateInfo.nunnormalizedCoordinates(address),
            reductionPresent,
            capturedReductionMode,
            unsupportedSType
        );
    }

    public float mipLodBias() {
        return Float.intBitsToFloat(this.mipLodBiasBits);
    }

    public float maxAnisotropy() {
        return Float.intBitsToFloat(this.maxAnisotropyBits);
    }

    public float minLod() {
        return Float.intBitsToFloat(this.minLodBits);
    }

    public float maxLod() {
        return Float.intBitsToFloat(this.maxLodBits);
    }

    public boolean canReplay() {
        float bias = this.mipLodBias();
        float anisotropy = this.maxAnisotropy();
        float minimumLod = this.minLod();
        float maximumLod = this.maxLod();
        return this.unsupportedPNextSType == NO_UNSUPPORTED_PNEXT
            && this.sType == VK12.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO
            && (this.anisotropyEnable == 0 || this.anisotropyEnable == 1)
            && (this.compareEnable == 0 || this.compareEnable == 1)
            && (
                this.unnormalizedCoordinates == 0
                    || this.unnormalizedCoordinates == 1
            )
            && Float.isFinite(bias)
            && Float.isFinite(anisotropy)
            && Float.isFinite(minimumLod)
            && Float.isFinite(maximumLod)
            && minimumLod <= maximumLod
            && (this.anisotropyEnable == 0 || anisotropy >= 1.0F)
            && (!this.reductionModePresent || validReductionMode());
    }

    /** Replays every captured field, changing only the absolute LOD bias. */
    public void replayInto(
        VkSamplerCreateInfo target,
        float finalBias,
        MemoryStack stack
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(stack, "stack");
        if (!this.canReplay() || !Float.isFinite(finalBias)) {
            throw new IllegalStateException(
                "Vulkan sampler descriptor cannot be replayed safely"
            );
        }
        target
            .sType(this.sType)
            .pNext(0L)
            .flags(this.flags)
            .magFilter(this.magFilter)
            .minFilter(this.minFilter)
            .mipmapMode(this.mipmapMode)
            .addressModeU(this.addressModeU)
            .addressModeV(this.addressModeV)
            .addressModeW(this.addressModeW)
            .mipLodBias(finalBias)
            .anisotropyEnable(this.anisotropyEnable != 0)
            .maxAnisotropy(this.maxAnisotropy())
            .compareEnable(this.compareEnable != 0)
            .compareOp(this.compareOp)
            .minLod(this.minLod())
            .maxLod(this.maxLod())
            .borderColor(this.borderColor)
            .unnormalizedCoordinates(
                this.unnormalizedCoordinates != 0
            );
        if (this.reductionModePresent) {
            VkSamplerReductionModeCreateInfo reduction =
                VkSamplerReductionModeCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .reductionMode(this.reductionMode);
            target.pNext(reduction);
        }
    }

    public boolean matchesReplayOf(
        VulkanSamplerDescriptor original,
        float finalBias
    ) {
        if (original == null) {
            return false;
        }
        return this.sType == original.sType
            && this.flags == original.flags
            && this.magFilter == original.magFilter
            && this.minFilter == original.minFilter
            && this.mipmapMode == original.mipmapMode
            && this.addressModeU == original.addressModeU
            && this.addressModeV == original.addressModeV
            && this.addressModeW == original.addressModeW
            && this.mipLodBiasBits
                == Float.floatToRawIntBits(finalBias)
            && this.anisotropyEnable == original.anisotropyEnable
            && this.maxAnisotropyBits == original.maxAnisotropyBits
            && this.compareEnable == original.compareEnable
            && this.compareOp == original.compareOp
            && this.minLodBits == original.minLodBits
            && this.maxLodBits == original.maxLodBits
            && this.borderColor == original.borderColor
            && this.unnormalizedCoordinates
                == original.unnormalizedCoordinates
            && this.reductionModePresent
                == original.reductionModePresent
            && this.reductionMode == original.reductionMode
            && this.unsupportedPNextSType
                == original.unsupportedPNextSType;
    }

    private boolean validReductionMode() {
        return this.reductionMode
                == VK12.VK_SAMPLER_REDUCTION_MODE_WEIGHTED_AVERAGE
            || this.reductionMode
                == VK12.VK_SAMPLER_REDUCTION_MODE_MIN
            || this.reductionMode
                == VK12.VK_SAMPLER_REDUCTION_MODE_MAX;
    }
}
