package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.core.BlockframeRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Developer-only CPU culling measurement. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDiagnosticsMixin {
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update("
                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                + "ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"
        )
    )
    private void blockframe$measureOcclusionUpdate(
        SectionOcclusionGraph graph,
        CameraRenderState camera,
        int fov,
        ChunkLoadingRenderState chunkLoadingState
    ) {
        long started = System.nanoTime();
        try {
            graph.update(camera, fov, chunkLoadingState);
        } finally {
            BlockframeRuntime.recordCpuCull(System.nanoTime() - started);
        }
    }
}
