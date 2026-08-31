package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.morau.blockframe.render.terrain.OpaqueSolidTerrainBatchCache;
import de.morau.blockframe.render.terrain.OpaqueSolidTerrainBatchRuntime;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fail-open entry into Mojang's existing chunk-render preparation.
 */
@Mixin(LevelRenderer.class)
public abstract class OpaqueSolidTerrainLevelRendererMixin {
    @Shadow
    @Final
    private ObjectArrayList<
        SectionRenderDispatcher.RenderSection
    > visibleSections;

    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Unique
    private OpaqueSolidTerrainBatchCache
        blockframe$opaqueSolidTemplateCache;

    @WrapMethod(method = "prepareChunkRenders")
    private ChunkSectionsToRender blockframe$reuseOpaqueSolidDrawTemplates(
        Matrix4fc modelViewMatrix,
        Operation<ChunkSectionsToRender> original
    ) {
        if (
            !OpaqueSolidTerrainBatchRuntime.eligibleForPrepare()
                || this.sectionRenderDispatcher == null
                || Minecraft.getInstance().level == null
        ) {
            return original.call(modelViewMatrix);
        }
        if (this.blockframe$opaqueSolidTemplateCache == null) {
            this.blockframe$opaqueSolidTemplateCache =
                OpaqueSolidTerrainBatchRuntime.cacheOrCreate(null);
        }
        ChunkSectionsToRender cached =
            OpaqueSolidTerrainBatchRuntime.tryPrepare(
                this.blockframe$opaqueSolidTemplateCache,
                Minecraft.getInstance().level,
                this.visibleSections,
                this.sectionRenderDispatcher,
                this.textureManager,
                modelViewMatrix
            );
        return cached != null
            ? cached
            : original.call(modelViewMatrix);
    }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void blockframe$invalidateOpaqueSolidOnRendererReset(
        CallbackInfo callback
    ) {
        OpaqueSolidTerrainBatchRuntime.rendererInvalidated(
            this.blockframe$opaqueSolidTemplateCache
        );
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void blockframe$invalidateOpaqueSolidOnResize(
        int width,
        int height,
        CallbackInfo callback
    ) {
        OpaqueSolidTerrainBatchRuntime.rendererInvalidated(
            this.blockframe$opaqueSolidTemplateCache
        );
    }

    @WrapMethod(method = "close")
    private void blockframe$closeOpaqueSolidWithRenderer(
        Operation<Void> original
    ) {
        try {
            original.call();
        } finally {
            OpaqueSolidTerrainBatchRuntime.rendererClosed(
                this.blockframe$opaqueSolidTemplateCache
            );
            this.blockframe$opaqueSolidTemplateCache = null;
        }
    }
}
