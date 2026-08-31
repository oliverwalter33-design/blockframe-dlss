package de.morau.nvidiadlss.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.morau.blockframe.core.BlockframeRuntime;
import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainBackendFoundation;
import de.morau.nvidiadlss.DlssRenderer;
import de.morau.nvidiadlss.NvidiaDlssMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Closes the static BlockFrame runtime only with the client itself. GPU
 * devices may be recreated during one client generation and therefore must
 * never irreversibly close the shared engine or its budget manager.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftLifecycleMixin {
    @WrapMethod(method = "close")
    private void blockframe$closeRuntimeWithClient(Operation<Void> original) {
        boolean originalReturnedNormally = false;
        try {
            original.call();
            originalReturnedNormally = true;
        } finally {
            blockframe$closeRuntimeAfterClient(
                originalReturnedNormally
            );
        }
    }

    private static void blockframe$closeRuntimeAfterClient(
        boolean originalReturnedNormally
    ) {
        boolean dlssCleanupSucceeded = false;
        boolean terrainCleanupSucceeded = false;
        try {
            dlssCleanupSucceeded =
                DlssRenderer.closeClientResourcesAndReport();
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "DLSS-Client-Scratch konnte beim finalen Client-Shutdown nicht sauber geschlossen werden",
                error
            );
        }
        try {
            terrainCleanupSucceeded =
                NativeTerrainBackendFoundation.closeClient();
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "Native terrain foundation could not close cleanly",
                error
            );
        }
        try {
            BlockframeRuntime.clientCloseReturned(
                originalReturnedNormally,
                dlssCleanupSucceeded && terrainCleanupSucceeded
            );
        } catch (Throwable error) {
            NvidiaDlssMod.LOGGER.warn(
                "BlockFrame-Lifecycle konnte beim finalen Client-Shutdown nicht sauber abgeschlossen werden",
                error
            );
        }
    }
}
