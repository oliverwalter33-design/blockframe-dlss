package de.morau.blockframe.benchmark.phase2a0c.mixin;

import de.morau.blockframe.benchmark.phase2a0c.Phase2a0cCaptureRuntime;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev-capture-only deterministic static-gate condition. The byte-identical
 * capture JAR is installed in both profiles and production code is untouched.
 */
@Mixin(ParticleEngine.class)
abstract class Phase2a1ParticleSuppressionMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void blockframe$phase2a1SuppressStaticGateParticle(
        Particle particle,
        CallbackInfo callback
    ) {
        if (Phase2a0cCaptureRuntime.suppressDynamicParticles()) {
            callback.cancel();
        }
    }
}
