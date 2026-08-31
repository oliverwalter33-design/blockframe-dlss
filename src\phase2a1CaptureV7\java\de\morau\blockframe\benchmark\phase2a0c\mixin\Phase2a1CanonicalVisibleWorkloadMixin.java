package de.morau.blockframe.benchmark.phase2a0c.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Development-capture-only canonical visible-section owner.
 *
 * <p>The list is normalized at Mojang's sole render call site immediately
 * before the complete {@code prepareChunkRenders} wrapper chain. The
 * call-site boundary is required because a productive BlockFrame cache is
 * allowed to satisfy that call without invoking Mojang's method body.
 * Mojang remains the producer and owner, so no queue, graph, mesh, upload,
 * Vulkan object or lifetime is replaced.</p>
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
abstract class Phase2a1CanonicalVisibleWorkloadMixin {
    @Shadow @Final
    private @Nullable ViewArea viewArea;

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;"
                + "prepareChunkRenders(Lorg/joml/Matrix4fc;)"
                + "Lnet/minecraft/client/renderer/chunk/"
                + "ChunkSectionsToRender;"
        )
    )
    private ChunkSectionsToRender
        blockframe$phase2a1CanonicalizeVisibleSections(
        LevelRenderer renderer,
        Matrix4fc modelViewMatrix,
        Operation<ChunkSectionsToRender> original
    ) {
        Phase2a0cCaptureRuntime.canonicalizeVisibleSections(
            renderer.visibleSections(),
            this.viewArea
        );
        return original.call(renderer, modelViewMatrix);
    }
}
