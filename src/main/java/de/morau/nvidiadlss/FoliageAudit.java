package de.morau.nvidiadlss;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/** Opt-in, case-labelled diagnostics for the grazing-angle foliage audit. */
public final class FoliageAudit {
    public enum DebugView { NONE, MIP, ALPHA }
    public enum JitterMode { FULL, HORIZONTAL_ONLY, OFF }
    public enum LeafMipmapMode { ORIGINAL, CUTOUT, STRICT_CUTOUT, MEAN, DARK_CUTOUT }

    public static final int HINT_NONE = 0;
    public static final int HINT_TRANSPARENCY = 1;

    private static final boolean ENABLED = DeveloperDiagnostics.enabled()
        && booleanSetting("NVIDIA_DLSS_FOLIAGE_AUDIT", "nvidia_dlss.foliageAudit", false);
    private static final String CASE_ID = sanitize(setting("NVIDIA_DLSS_AUDIT_CASE", "nvidia_dlss.auditCase", "manual"));
    private static final DebugView DEBUG_VIEW = enumSetting("NVIDIA_DLSS_AUDIT_VIEW", "nvidia_dlss.auditView", DebugView.NONE);
    private static final JitterMode JITTER_MODE = enumSetting("NVIDIA_DLSS_AUDIT_JITTER", "nvidia_dlss.auditJitter", JitterMode.FULL);
    private static final LeafMipmapMode LEAF_MIPMAP_MODE = enumSetting(
        "NVIDIA_DLSS_AUDIT_MIP_STRATEGY", "nvidia_dlss.auditMipStrategy", LeafMipmapMode.ORIGINAL
    );
    private static final int REQUESTED_HINT_MODE = hintSetting();
    private static final int HINT_MODE = effectiveHintMode(REQUESTED_HINT_MODE);
    private static final boolean CONSERVATIVE_CUTOUT_MOTION = booleanSetting(
        "NVIDIA_DLSS_AUDIT_CONSERVATIVE_MV", "nvidia_dlss.auditConservativeMv", false
    );
    private static final float VISUALIZED_MIP_BIAS = floatSetting("NVIDIA_DLSS_AUDIT_MIP_BIAS", "nvidia_dlss.auditMipBias", 0.0F);
    private static final boolean AUTO_CAPTURE = ENABLED
        && booleanSetting("NVIDIA_DLSS_AUDIT_AUTO_CAPTURE", "nvidia_dlss.auditAutoCapture", true);
    private static final Set<String> SEEN_SAMPLERS = new HashSet<>();
    private static final Set<String> SEEN_MIPMAPS = new HashSet<>();
    private static final Set<String> SEEN_MIPMAP_OVERRIDES = new HashSet<>();
    private static String solidAtlasSampler = "{\"observed\":false}";
    private static String cutoutAtlasSampler = "{\"observed\":false}";
    private static String translucentAtlasSampler = "{\"observed\":false}";
    private static Path directory;
    private static boolean headerWritten;
    private static boolean warned;
    private static boolean autoCaptureIssued;

    private FoliageAudit() {}

    public static boolean enabled() { return ENABLED; }
    public static String caseId() { return CASE_ID; }
    public static DebugView debugView() { return ENABLED ? DEBUG_VIEW : DebugView.NONE; }
    public static JitterMode jitterMode() { return ENABLED ? JITTER_MODE : JitterMode.FULL; }
    public static int streamlineHintMode() { return ENABLED ? HINT_MODE : HINT_TRANSPARENCY; }
    public static float visualizedMipBias() { return ENABLED ? VISUALIZED_MIP_BIAS : 0.0F; }
    /** Shipping default is enabled; audit cases may explicitly disable it for A/B baselines. */
    public static boolean conservativeCutoutMotion() {
        return !ENABLED || CONSERVATIVE_CUTOUT_MOTION;
    }

    /** Audit-only override used to compare leaf alpha mipmap generation without changing other textures. */
    public static synchronized MipmapStrategy leafMipmapStrategy(Identifier id, MipmapStrategy original) {
        if (!ENABLED || id == null || original == null || !id.getPath().contains("leaves")
            || LEAF_MIPMAP_MODE == LeafMipmapMode.ORIGINAL) {
            return original;
        }
        MipmapStrategy selected = switch (LEAF_MIPMAP_MODE) {
            case CUTOUT -> MipmapStrategy.CUTOUT;
            case STRICT_CUTOUT -> MipmapStrategy.STRICT_CUTOUT;
            case MEAN -> MipmapStrategy.MEAN;
            case DARK_CUTOUT -> MipmapStrategy.DARK_CUTOUT;
            default -> original;
        };
        String key = id + "|" + original + "|" + selected;
        if (SEEN_MIPMAP_OVERRIDES.add(key)) {
            write("{\"event\":\"leaf_mipmap_strategy_override\",\"texture\":\"" + escape(id.toString())
                + "\",\"originalStrategy\":\"" + original + "\",\"selectedStrategy\":\"" + selected + "\"}");
        }
        return selected;
    }

    public static synchronized boolean shouldAutoCapture(int frame) {
        if (!AUTO_CAPTURE || autoCaptureIssued || frame < 120) return false;
        autoCaptureIssued = true;
        return true;
    }

    public static void announce() {
        if (!ENABLED) return;
        if (REQUESTED_HINT_MODE != HINT_MODE) {
            NvidiaDlssMod.LOGGER.warn(
                "Foliage-Audit: angeforderter Streamline-Hinweis {} wird vom gebündelten DLSS 2.12 nicht konsumiert; "
                    + "der Zusatz-Hinweis bleibt fail-closed deaktiviert",
                hintName(REQUESTED_HINT_MODE)
            );
        }
        NvidiaDlssMod.LOGGER.info("Foliage-Audit aktiv: Fall={} Ansicht={} Jitter={} Streamline-Hinweis={} Blatt-Mipmap={} konservative-Cutout-MV={}",
            CASE_ID, DEBUG_VIEW, JITTER_MODE, hintName(HINT_MODE), LEAF_MIPMAP_MODE, CONSERVATIVE_CUTOUT_MOTION);
    }

    public static synchronized void recordSampler(String backend, String pipeline, String bindingName, GpuTextureView view, GpuSampler sampler) {
        if (!ENABLED || view == null || sampler == null) return;
        String label = view.texture().getLabel();
        String lower = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (!lower.contains("atlas")) return;
        double maxLod = sampler.getMaxLod().isPresent() ? sampler.getMaxLod().getAsDouble() : Double.POSITIVE_INFINITY;
        float actualBias = DlssSamplerPolicy.biasFor(sampler);
        if (lower.contains("blocks.png")) {
            String samplerJson = samplerJson(backend, pipeline, view, sampler, maxLod, actualBias);
            String lowerPipeline = pipeline == null ? "" : pipeline.toLowerCase(Locale.ROOT);
            if (lowerPipeline.contains("cutout")) cutoutAtlasSampler = samplerJson;
            else if (lowerPipeline.contains("translucent")) translucentAtlasSampler = samplerJson;
            else if (lowerPipeline.contains("solid") || lowerPipeline.contains("terrain")) solidAtlasSampler = samplerJson;
        }
        String key = backend + '|' + pipeline + '|' + label + '|' + sampler.getAddressModeU() + '|' + sampler.getAddressModeV() + '|'
            + sampler.getMinFilter() + '|' + sampler.getMagFilter() + '|' + sampler.getMaxAnisotropy() + '|' + maxLod + '|' + actualBias
            + '|' + view.baseMipLevel() + '|' + view.mipLevels();
        if (!SEEN_SAMPLERS.add(key)) return;
        write("{\"event\":\"sampler_bound\",\"backend\":\"" + escape(backend) + "\",\"pipeline\":\"" + escape(pipeline)
            + "\",\"binding\":\"" + escape(bindingName) + "\","
            + "\"textureLabel\":\"" + escape(label) + "\",\"textureFormat\":\"" + view.texture().getFormat() + "\","
            + "\"textureMipLevels\":" + view.texture().getMipLevels() + ",\"viewBaseMip\":" + view.baseMipLevel()
            + ",\"viewMipLevels\":" + view.mipLevels() + ",\"addressU\":\"" + sampler.getAddressModeU()
            + "\",\"addressV\":\"" + sampler.getAddressModeV() + "\",\"minFilter\":\"" + sampler.getMinFilter()
            + "\",\"magFilter\":\"" + sampler.getMagFilter() + "\",\"maxAnisotropy\":" + sampler.getMaxAnisotropy()
            + ",\"maxLod\":" + (Double.isInfinite(maxLod) ? "null" : number(maxLod))
            + ",\"actualMipLodBias\":" + number(actualBias) + ",\"requestedWorldMipLodBias\":" + number(DlssRenderer.currentLodBias()) + "}");
    }

    public static synchronized void recordMipmap(Identifier id, NativeImage[] mips, int requestedLevel,
        MipmapStrategy requestedStrategy, float alphaCutoffBias, Transparency transparency) {
        if (!ENABLED || id == null || mips == null || !id.getPath().contains("leaves")) return;
        String key = id + "|" + requestedLevel + "|" + requestedStrategy + "|" + alphaCutoffBias;
        if (!SEEN_MIPMAPS.add(key)) return;
        MipmapStrategy effective = requestedStrategy == MipmapStrategy.AUTO
            ? (transparency.hasTransparent() ? MipmapStrategy.CUTOUT : MipmapStrategy.MEAN)
            : requestedStrategy;
        StringBuilder coverage = new StringBuilder("[");
        for (int level = 0; level < mips.length; level++) {
            if (level > 0) coverage.append(',');
            NativeImage image = mips[level];
            long accepted = 0;
            long total = (long)image.getWidth() * image.getHeight();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (ARGB.alpha(image.getPixel(x, y)) >= 128) accepted++;
                }
            }
            coverage.append(number(total == 0 ? 0.0 : (double)accepted / total));
        }
        coverage.append(']');
        write("{\"event\":\"leaf_mipmap_chain\",\"texture\":\"" + escape(id.toString()) + "\","
            + "\"requestedStrategy\":\"" + requestedStrategy + "\",\"effectiveStrategy\":\"" + effective
            + "\",\"alphaCutoffBias\":" + number(alphaCutoffBias) + ",\"hasTransparent\":" + transparency.hasTransparent()
            + ",\"hasTranslucent\":" + transparency.hasTranslucent() + ",\"requestedMaxLevel\":" + requestedLevel
            + ",\"generatedLevels\":" + mips.length + ",\"alphaCoverageAt0_5\":" + coverage + "}");
    }

    public static String debugMetadataJson() {
        return "{\"enabled\":" + ENABLED + ",\"case\":\"" + escape(CASE_ID) + "\",\"debugView\":\"" + DEBUG_VIEW
            + "\",\"jitterMode\":\"" + JITTER_MODE + "\",\"requestedStreamlineHint\":\"" + hintName(REQUESTED_HINT_MODE)
            + "\",\"streamlineHint\":\"" + hintName(streamlineHintMode()) + "\",\"requestedHintSupportedByDlss212\":"
            + (REQUESTED_HINT_MODE == HINT_MODE)
            + ",\"leafMipmapMode\":\"" + LEAF_MIPMAP_MODE + "\",\"conservativeCutoutMotion\":"
            + conservativeCutoutMotion() + ",\"visualizedMipBias\":" + number(VISUALIZED_MIP_BIAS) + "}";
    }

    /** The sampler values actually observed on the level texture atlas, never configuration guesses. */
    public static synchronized String atlasSamplersJson() {
        return "{\"solid\":" + solidAtlasSampler + ",\"cutout\":" + cutoutAtlasSampler
            + ",\"translucent\":" + translucentAtlasSampler + "}";
    }

    private static String samplerJson(String backend, String pipeline, GpuTextureView view, GpuSampler sampler,
        double maxLod, float actualBias) {
        return "{\"observed\":true,\"backend\":\"" + escape(backend) + "\",\"pipeline\":\""
            + escape(pipeline) + "\",\"minFilter\":\"" + sampler.getMinFilter() + "\",\"magFilter\":\""
            + sampler.getMagFilter() + "\",\"maxAnisotropy\":" + sampler.getMaxAnisotropy()
            + ",\"maxLod\":" + (Double.isInfinite(maxLod) ? "null" : number(maxLod))
            + ",\"actualMipLodBias\":" + number(actualBias) + ",\"viewBaseMip\":" + view.baseMipLevel()
            + ",\"viewMipLevels\":" + view.mipLevels() + "}";
    }

    public static String hintName(int mode) {
        return switch (mode) {
            case HINT_NONE -> "None";
            default -> "TransparencyHint";
        };
    }

    private static synchronized void write(String line) {
        if (!ENABLED) return;
        try {
            if (directory == null) {
                directory = Minecraft.getInstance().gameDirectory.toPath().resolve("foliage-audit").resolve(CASE_ID);
                Files.createDirectories(directory);
            }
            Path file = directory.resolve("audit.jsonl");
            if (!headerWritten) {
                headerWritten = true;
                String header = "{\"event\":\"audit_start\",\"case\":\"" + escape(CASE_ID) + "\",\"time\":\"" + Instant.now()
                    + "\",\"debugView\":\"" + DEBUG_VIEW + "\",\"jitterMode\":\"" + JITTER_MODE
                    + "\",\"requestedStreamlineHint\":\"" + hintName(REQUESTED_HINT_MODE)
                    + "\",\"streamlineHint\":\"" + hintName(HINT_MODE) + "\",\"requestedHintSupportedByDlss212\":"
                    + (REQUESTED_HINT_MODE == HINT_MODE) + ",\"leafMipmapMode\":\"" + LEAF_MIPMAP_MODE
                    + "\",\"conservativeCutoutMotion\":" + conservativeCutoutMotion()
                    + ",\"visualizedMipBias\":" + number(VISUALIZED_MIP_BIAS) + "}"
                    + System.lineSeparator();
                Files.writeString(file, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) {
            if (!warned) {
                warned = true;
                NvidiaDlssMod.LOGGER.warn("Foliage-Audit konnte nicht geschrieben werden", error);
            }
        }
    }

    private static int hintSetting() {
        String value = setting("NVIDIA_DLSS_AUDIT_HINT", "nvidia_dlss.auditHint", "transparency")
            .toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (value) {
            case "none", "off" -> HINT_NONE;
            case "transparency", "transparencyhint" -> HINT_TRANSPARENCY;
            default -> HINT_NONE;
        };
    }

    /**
     * The pinned runtime receives only its supported transparency input.
     * Every other audit value fails closed instead of changing tag semantics.
     */
    static int effectiveHintMode(int requested) {
        return switch (requested) {
            case HINT_NONE, HINT_TRANSPARENCY -> requested;
            default -> HINT_NONE;
        };
    }

    private static String setting(String environment, String property, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean booleanSetting(String environment, String property, boolean fallback) {
        return Boolean.parseBoolean(setting(environment, property, Boolean.toString(fallback)));
    }

    private static float floatSetting(String environment, String property, float fallback) {
        try { return Float.parseFloat(setting(environment, property, Float.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static <E extends Enum<E>> E enumSetting(String environment, String property, E fallback) {
        try {
            @SuppressWarnings("unchecked") Class<E> type = (Class<E>)fallback.getDeclaringClass();
            return Enum.valueOf(type, setting(environment, property, fallback.name()).toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "manual" : sanitized;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.8g", value); }
}
