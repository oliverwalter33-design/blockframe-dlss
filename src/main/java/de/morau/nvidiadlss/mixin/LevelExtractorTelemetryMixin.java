package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.core.BlockframeRuntime;
import java.util.List;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorTelemetryMixin {
    @Redirect(
        method = "applyFrustum",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;addSectionsInFrustum("
                + "Lnet/minecraft/client/renderer/culling/Frustum;"
                + "Ljava/util/List;Ljava/util/List;)V"
        )
    )
    private void blockframe$measureFrustumTraversal(
        SectionOcclusionGraph graph,
        Frustum frustum,
        List<SectionRenderDispatcher.RenderSection> visibleSections,
        List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections
    ) {
        long started = System.nanoTime();
        try {
            graph.addSectionsInFrustum(frustum, visibleSections, nearbyVisibleSections);
        } finally {
            BlockframeRuntime.recordCpuCull(System.nanoTime() - started);
        }
    }
}
