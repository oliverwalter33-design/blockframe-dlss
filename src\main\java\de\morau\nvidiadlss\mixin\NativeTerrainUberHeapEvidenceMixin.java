package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainOwnershipEvidence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records construction of an actual Mojang terrain Uber-buffer heap. */
@Mixin(UberGpuBuffer.UberGpuBufferHeap.class)
public abstract class NativeTerrainUberHeapEvidenceMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void blockframe$recordMojangTerrainHeap(
        long size,
        GpuDevice device,
        int usage,
        String name,
        CallbackInfo callback
    ) {
        if (
            NativeTerrainOwnershipEvidence
                .isMojangSolidCutoutHeapName(name)
        ) {
            NativeTerrainOwnershipEvidence.mojangGpuAllocated();
        }
    }
}
