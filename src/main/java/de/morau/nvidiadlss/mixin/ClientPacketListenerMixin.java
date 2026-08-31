package de.morau.nvidiadlss.mixin;

import de.morau.nvidiadlss.DlssRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Authoritative client-thread teleport boundaries. Both target handlers call
 * PacketUtils.ensureRunningOnSameThread before returning, so RETURN never
 * mutates temporal state from the network thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void nvidiaDlss$resetAfterPlayerTeleport(
        ClientboundPlayerPositionPacket packet,
        CallbackInfo ci
    ) {
        DlssRenderer.requestReset("Spieler-Teleport");
    }

    @Inject(method = "handleTeleportEntity", at = @At("RETURN"))
    private void nvidiaDlss$resetAfterEntityTeleport(
        ClientboundTeleportEntityPacket packet,
        CallbackInfo ci
    ) {
        DlssRenderer.requestReset("Entity-Teleport");
    }
}
