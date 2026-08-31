package de.morau.nvidiadlss.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import de.morau.nvidiadlss.FoliageAudit;
import de.morau.nvidiadlss.DeveloperDiagnostics;
import de.morau.nvidiadlss.NvidiaDlssMod;
import de.morau.nvidiadlss.TemporalMaterialShaderPatcher;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Material marker plus opt-in terrain audit views. */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class ShaderCompilationCacheMixin {
    private static boolean nvidiaDlss$materialMarkerAnnounced;
    private static boolean nvidiaDlss$compositeMarkerAnnounced;

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private void nvidiaDlss$injectFoliageDebugView(Identifier id, ShaderType type,
        CallbackInfoReturnable<String> cir) {
        if (type != ShaderType.FRAGMENT) return;
        String shaderPath = id.getPath();
        boolean vanillaTransparencyComposite = "minecraft".equals(id.getNamespace())
            && ("post/transparency".equals(shaderPath)
                || "post/transparency.fsh".equals(shaderPath));
        if (vanillaTransparencyComposite) {
            String original = cir.getReturnValue();
            String patched = TemporalMaterialShaderPatcher.injectTransparencyCompositeMarkers(original);
            if (patched != original) {
                if (!nvidiaDlss$compositeMarkerAnnounced) {
                    nvidiaDlss$compositeMarkerAnnounced = true;
                    NvidiaDlssMod.LOGGER.info(
                        "DLSS-Materialmaske: Transparenz-/Partikel-Marker aktiv ({})", id);
                }
                cir.setReturnValue(patched);
            } else if (original != null) {
                NvidiaDlssMod.LOGGER.warn(
                    "DLSS-Materialmaske: Transparenz-Shader nicht erkannt; Original bleibt unverändert ({})", id);
            }
            return;
        }
        boolean vanillaTerrain = "minecraft".equals(id.getNamespace())
            && ("terrain".equals(shaderPath) || "terrain.fsh".equals(shaderPath));
        boolean sodiumTerrain = "sodium".equals(id.getNamespace())
            && ("blocks/block_layer_opaque".equals(shaderPath)
                || "blocks/block_layer_opaque.fsh".equals(shaderPath));
        boolean milkshadeSodiumTerrain = "milkshade".equals(id.getNamespace())
            && ("sodium/block_layer_opaque".equals(shaderPath)
                || "sodium/block_layer_opaque.fsh".equals(shaderPath));
        boolean milkshadeVanillaTerrain = "milkshade".equals(id.getNamespace())
            && ("terrain".equals(shaderPath) || "terrain.fsh".equals(shaderPath));
        if (!vanillaTerrain && !sodiumTerrain && !milkshadeSodiumTerrain
            && !milkshadeVanillaTerrain) return;
        String source = cir.getReturnValue();
        if (source == null) return;
        // Preserve a material bit in the otherwise unused world-target alpha
        // channel. The motion pass consumes it before DLSS and can therefore
        // identify cutout foliage even when its holes reveal other geometry.
        String vanillaOutputMarker =
            "    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);";
        String sodiumOutputMarker =
            "    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);";
        String outputMarker = sodiumTerrain || milkshadeSodiumTerrain
            ? sodiumOutputMarker
            : vanillaOutputMarker;
        // RGBA8 stores this as 254 while ordinary opaque terrain remains 255.
        // Keeping the marker one code point below opaque prevents blend-enabled
        // modded cutout layers from becoming visibly translucent.
        String cutoutDefine = sodiumTerrain || milkshadeSodiumTerrain
            ? "NVIDIA_DLSS_CUTOUT"
            : "ALPHA_CUTOUT";
        String markedOutput = outputMarker
            + "\n#ifdef " + cutoutDefine
            + "\n    fragColor.a = 254.0 / 255.0;\n#endif";
        if (source.contains(outputMarker)) {
            source = source.replace(outputMarker, markedOutput);
            if (!nvidiaDlss$materialMarkerAnnounced) {
                nvidiaDlss$materialMarkerAnnounced = true;
                NvidiaDlssMod.LOGGER.info("DLSS-Materialmaske: Cutout-Alpha-Marker aktiv ({})", id);
            }
        } else {
            NvidiaDlssMod.LOGGER.warn("DLSS-Materialmaske: Shader-Ausgabe nicht erkannt ({})", id);
        }

        // The audit-only shader rewrites below target Mojang's terrain source.
        // Sodium/Milkshade compatibility is intentionally limited to the
        // production alpha marker above.
        if (!vanillaTerrain && !milkshadeVanillaTerrain) {
            cir.setReturnValue(source);
            return;
        }

        if (
            !DeveloperDiagnostics.ENABLED
                || !FoliageAudit.enabled()
        ) {
            cir.setReturnValue(source);
            return;
        }
        if (FoliageAudit.debugView() == FoliageAudit.DebugView.ALPHA) {
            String marker = "    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);\n";
            String view = "#ifdef ALPHA_CUTOUT\n"
                // Black: safely below cutoff. Blue: just below cutoff and normally discarded.
                // Red: just above cutoff and normally retained. White: safely above cutoff.
                + "    float auditDelta = color.a - ALPHA_CUTOUT;\n"
                + "    vec3 auditAlphaColor = auditDelta < -0.10 ? vec3(0.0) : "
                + "(auditDelta < 0.0 ? vec3(0.0, 0.25, 1.0) : "
                + "(auditDelta < 0.10 ? vec3(1.0, 0.05, 0.0) : vec3(1.0)));\n"
                + "    fragColor = vec4(auditAlphaColor, 1.0);\n"
                + "    return;\n"
                + "#endif\n";
            if (source.contains(marker)) source = source.replace(marker, marker + view);
        } else if (FoliageAudit.debugView() == FoliageAudit.DebugView.MIP) {
            String marker = "    fragColor = apply_fog";
            String bias = String.format(Locale.ROOT, "%.8gf", FoliageAudit.visualizedMipBias());
            String uv = "texCoord0";
            String size = "vec2(TextureSize)";
            String minPixelSize = "min(1.0 / float(TextureSize.x), 1.0 / float(TextureSize.y))";
            String rgss = "UseRgss == 1";
            int configuredMaxMip = Math.max(0, Minecraft.getInstance().options.mipmapLevels().get());
            String view = "#ifdef ALPHA_CUTOUT\n"
                + "    vec2 auditDu = dFdx(" + uv + ");\n"
                + "    vec2 auditDv = dFdy(" + uv + ");\n"
                + "    float auditImplicitMip = log2(max(length(auditDu * " + size + "), length(auditDv * " + size + ")));\n"
                + "    float auditMinPixelSize = " + minPixelSize + ";\n"
                + "    float auditRgssMip = log2(sqrt(min(length(auditDu), length(auditDv)) * max(length(auditDu), length(auditDv))) / auditMinPixelSize);\n"
                + "    float auditMip = (" + rgss + " ? auditRgssMip : auditImplicitMip) + " + bias + ";\n"
                // The configured atlas mip count is the actual upper bound used for this audit run.
                + "    auditMip = clamp(auditMip, 0.0, " + configuredMaxMip + ".0);\n"
                + "    float auditBand = floor(auditMip + 0.5);\n"
                + "    vec3 auditColor = 0.5 + 0.5 * cos(6.2831853 * (auditBand / 8.0 + vec3(0.0, 0.3333333, 0.6666667)));\n"
                + "    fragColor = vec4(auditColor, 1.0);\n"
                + "    return;\n"
                + "#endif\n";
            if (source.contains(marker)) source = source.replace(marker, view + marker);
        }
        cir.setReturnValue(source);
    }
}
