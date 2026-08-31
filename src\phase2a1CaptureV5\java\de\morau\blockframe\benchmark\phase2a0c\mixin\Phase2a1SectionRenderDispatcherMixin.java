package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Measures Mojang's existing terrain upload call. No buffer, queue, allocator
 * or submission ownership is acquired.
 */
@Mixin(SectionRenderDispatcher.class)
abstract class Phase2a1SectionRenderDispatcherMixin {
    @WrapMethod(method = "uploadTerrainBuffersToGpu")
    private void blockframe$phase2a1MeasureTerrainUpload(
        Operation<Void> original
    ) {
        long started = System.nanoTime();
        try {
            original.call();
        } finally {
            Phase2a0cCaptureRuntime.onTerrainUpload(
                System.nanoTime() - started
            );
        }
    }
}
