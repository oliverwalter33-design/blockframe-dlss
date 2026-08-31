package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuScenePolicy;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuSceneRuntime;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fail-open prepare entry. A marker is returned only after every solid owner
 * and the compute pass are ready; otherwise Mojang executes unchanged.
 */
@Mixin(LevelRenderer.class)
public abstract class OpaqueSolidGpuSceneLevelRendererMixin {
    @Shadow @Final
    private ObjectArrayList<
        SectionRenderDispatcher.RenderSection
    > visibleSections;
    @Shadow @Final private TextureManager textureManager;
    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void blockframe$registerGpuSceneRenderer(
        CallbackInfo callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.rendererCreated(this);
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "renderer-create",
                error
            );
        }
    }

    @WrapMethod(method = "prepareChunkRenders")
    private ChunkSectionsToRender blockframe$prepareGpuScene(
        Matrix4fc modelViewMatrix,
        Operation<ChunkSectionsToRender> original
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return original.call(modelViewMatrix);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (
            this.sectionRenderDispatcher == null
                || minecraft.level == null
        ) {
            return original.call(modelViewMatrix);
        }
        ChunkSectionsToRender candidate;
        try {
            candidate = OpaqueSolidGpuSceneRuntime.tryPrepare(
                this,
                minecraft.level,
                this.visibleSections,
                this.sectionRenderDispatcher,
                this.textureManager,
                modelViewMatrix
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "frame-prepare",
                error
            );
            candidate = null;
        }
        return candidate != null
            ? candidate
            : original.call(modelViewMatrix);
    }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void blockframe$invalidateGpuSceneRenderer(
        CallbackInfo callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.rendererReset(this);
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "renderer-reset",
                error
            );
        }
    }

    @WrapMethod(method = "close")
    private void blockframe$closeGpuSceneRenderer(
        Operation<Void> original
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            original.call();
            return;
        }
        try {
            original.call();
        } finally {
            try {
                OpaqueSolidGpuSceneRuntime.rendererClosed(this);
            } catch (
                RuntimeException
                    | LinkageError
                    | OutOfMemoryError error
            ) {
                OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                    "renderer-close",
                    error
                );
            }
        }
    }
}
