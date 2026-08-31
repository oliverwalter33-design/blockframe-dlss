package de.morau.blockframe.benchmark.phase2a0c.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Projection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The primitive overload updates Mojang's mutable camera position without
 * allocating a new Vec3 for each route sample.
 */
@Mixin(Camera.class)
public interface Phase2a0cCameraInvoker {
    @Invoker("setPosition")
    void blockframe$phase2a0cSetPosition(double x, double y, double z);

    @Invoker("setRotation")
    void blockframe$phase2a0cSetRotation(float yaw, float pitch);

    @Accessor("projection")
    Projection blockframe$phase2a1Projection();

    @Accessor("depthFar")
    float blockframe$phase2a1DepthFar();
}
