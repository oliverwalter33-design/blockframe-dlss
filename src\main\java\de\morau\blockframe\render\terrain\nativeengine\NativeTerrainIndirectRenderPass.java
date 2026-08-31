package de.morau.blockframe.render.terrain.nativeengine;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Vulkan-only commands implemented by the existing render-pass mixin. */
public interface NativeTerrainIndirectRenderPass {
    void blockframe$prepareNativeTerrainDescriptors(
        long sceneBufferView
    );

    void blockframe$drawNativeTerrainIndirectCount(
        GpuBufferSlice commands,
        GpuBufferSlice counts,
        int maximumDrawCount,
        int commandStride
    );
}
