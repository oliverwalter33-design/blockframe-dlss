package de.morau.nvidiadlss.mixin;

import de.morau.blockframe.render.terrain.nativeengine
    .NativeTerrainOwnershipEvidence;
import java.util.List;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback
    .CallbackInfoReturnable;

/** Records entry into Mojang's real terrain compiler during a native gate. */
@Mixin(SectionCompiler.class)
public abstract class NativeTerrainSectionCompilerEvidenceMixin {
    @Inject(
        method = "compile("
            + "Lnet/minecraft/core/SectionPos;"
            + "Lnet/minecraft/client/renderer/chunk/"
            + "RenderSectionRegion;"
            + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
            + "Lnet/minecraft/client/renderer/"
            + "SectionBufferBuilderPack;"
            + "Ljava/util/List;)"
            + "Lnet/minecraft/client/renderer/chunk/"
            + "SectionCompiler$Results;",
        at = @At("HEAD")
    )
    private void blockframe$recordMojangTerrainCompile(
        SectionPos section,
        RenderSectionRegion region,
        VertexSorting sorting,
        SectionBufferBuilderPack builders,
        List<
            AddSectionGeometryEvent.AdditionalSectionRenderer
        > additionalRenderers,
        CallbackInfoReturnable<SectionCompiler.Results> callback
    ) {
        NativeTerrainOwnershipEvidence.mojangSectionCompiled();
    }
}
