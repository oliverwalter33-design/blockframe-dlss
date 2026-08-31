package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuScenePolicy;
import de.morau.blockframe.render.terrain.gpuscene.OpaqueSolidGpuSceneRuntime;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact SectionMesh publication, replacement and reset boundaries. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class OpaqueSolidGpuSceneRenderSectionMixin {
    @Shadow public abstract SectionMesh getSectionMesh();

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void blockframe$invalidateBeforeSectionMeshReplace(
        SectionMesh replacement,
        CallbackInfoReturnable<SectionMesh> callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.sectionMeshInvalidating(
                this,
                this.getSectionMesh()
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "section-mesh-replace-head",
                error
            );
        }
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void blockframe$publishSectionMeshAfterAtomicReplace(
        SectionMesh replacement,
        CallbackInfoReturnable<SectionMesh> callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.sectionMeshPublished(
                (SectionRenderDispatcher.RenderSection)(Object)this,
                replacement
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "section-mesh-replace-return",
                error
            );
        }
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void blockframe$invalidateBeforeSectionReset(
        CallbackInfo callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.sectionMeshInvalidating(
                this,
                this.getSectionMesh()
            );
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "section-reset-head",
                error
            );
        }
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void blockframe$removeSectionAfterReset(
        CallbackInfo callback
    ) {
        if (!OpaqueSolidGpuScenePolicy.ownerHooksEnabled()) {
            return;
        }
        try {
            OpaqueSolidGpuSceneRuntime.sectionRemoved(this);
        } catch (
            RuntimeException | LinkageError | OutOfMemoryError error
        ) {
            OpaqueSolidGpuSceneRuntime.ownerHookFailed(
                "section-reset-return",
                error
            );
        }
    }
}
