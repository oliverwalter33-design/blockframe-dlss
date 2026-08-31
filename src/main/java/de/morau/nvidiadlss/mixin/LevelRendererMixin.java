package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendFoundation;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendSelector.WorldResourceCreationPermit;
import de.morau.nvidiadlss.DlssRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    private static final String SUBMIT_HIT_OUTLINE =
        "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(" +
        "Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lnet/minecraft/client/renderer/SubmitNodeCollector;" +
        "Lnet/minecraft/client/renderer/rendertype/RenderType;" +
        "Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V";

    /**
     * Enters Mojang's reference factory only after the post-reload census and
     * before its SectionCompiler, dispatcher, staging buffer or terrain
     * arenas. A selected native backend can never call the original through
     * this boundary.
     */
    @WrapMethod(method = "invalidateCompiledGeometry")
    private void blockframe$createSelectedTerrainWorldResources(
        ClientLevel level,
        Options options,
        Camera camera,
        BlockColors blockColors,
        Operation<Void> original
    ) {
        WorldResourceCreationPermit permit =
            NativeTerrainBackendFoundation
                .beginReferenceWorldResourceCreation();
        if (permit == null) {
            original.call(level, options, camera, blockColors);
            return;
        }
        boolean completed = false;
        try {
            original.call(level, options, camera, blockColors);
            NativeTerrainBackendFoundation
                .completeWorldResourceCreation(permit);
            completed = true;
        } finally {
            if (!completed) {
                NativeTerrainBackendFoundation
                    .abortReferenceWorldResourceCreation(permit);
            }
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void blockframe$terrainWorldResourcesClosed(
        CallbackInfo callback
    ) {
        NativeTerrainBackendFoundation.worldRendererClosed();
    }

    /** The native post-DLAA line should be solid enough to remain visibly crisp. */
    @ModifyArg(
        method = "submitBlockOutline",
        at = @At(value = "INVOKE", target = SUBMIT_HIT_OUTLINE, ordinal = 1),
        index = 4
    )
    private int nvidiaDlss$useCrispNativeOutlineColor(int color) {
        return DlssRenderer.isNativeBlockOutlinePass() ? 0xE6000000 : color;
    }

    /** Minecraft scales this to five physical pixels at 4K; two pixels are sharper and resolution-stable. */
    @ModifyArg(
        method = "submitBlockOutline",
        at = @At(value = "INVOKE", target = SUBMIT_HIT_OUTLINE, ordinal = 1),
        index = 5
    )
    private float nvidiaDlss$useCrispNativeOutlineWidth(float width) {
        return DlssRenderer.isNativeBlockOutlinePass() ? 2.0F : width;
    }
}
