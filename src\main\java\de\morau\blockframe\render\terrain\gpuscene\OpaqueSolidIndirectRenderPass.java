package de.morau.blockframe.render.terrain.gpuscene;

import com.mojang.blaze3d.buffers.GpuBuffer;

/** Vulkan-only extension implemented by the Mojang render-pass mixin. */
public interface OpaqueSolidIndirectRenderPass {
    void blockframe$prepareOpaqueSolidDescriptors(
        long sceneBufferView,
        long visibilityBufferView
    );

    void blockframe$drawIndexedIndirectCount(
        GpuBuffer commands,
        long commandOffset,
        GpuBuffer counts,
        long countOffset,
        int maximumDrawCount,
        int commandStride
    );
}
