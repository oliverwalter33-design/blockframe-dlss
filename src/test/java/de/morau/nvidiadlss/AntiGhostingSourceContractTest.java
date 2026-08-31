package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AntiGhostingSourceContractTest {
    private static final float MAX_FINITE_MOTION_COMPONENT = 65_024.0F;

    @Test
    void nativeBridgeTagsOnlyTheSupportedOptionalTransparencyHint() throws Exception {
        String source = source("native/nvidia_dlss_bridge.cpp");
        assertTrue(!source.contains("kBufferTypeBiasCurrentColorHint"));
        assertTrue(!source.contains("kBufferTypeInvalidDepthMotionHint"));
        assertTrue(!source.contains("sl::Resource historyBias"));
        assertTrue(source.contains("makeImage(transparencyHintImage, transparencyHintView, inputWidth, inputHeight, VK_FORMAT_R8G8B8A8_UNORM"));
        assertTrue(source.contains("{&transparencyHint, sl::kBufferTypeTransparencyHint"));
        assertTrue(source.contains("auditHintMode == 1"));
        assertTrue(source.contains("tagsWithTransparency, 5"));
        assertTrue(source.contains("tagsWithoutTransparency, 4"));
        assertTrue(source.contains("static_cast<void>(historyBiasImage)"));
        assertTrue(source.contains("static_cast<void>(historyBiasView)"));
        assertTrue(source.contains("constants.cameraMotionIncluded = sl::Boolean::eTrue"));
        assertTrue(source.contains("constants.motionVectorsJittered = sl::Boolean::eFalse"));
    }

    @Test
    void shaderWritesDenseOwnPixelMotionWithoutHistoryGating() throws Exception {
        String source = source("native/shaders/motion_vectors.comp");
        assertTrue(source.contains("frame.flags.y != 0"));
        assertTrue(source.contains(
            "float depth = texelFetch(CurrentDepth, pixel, 0).r"
        ));
        assertTrue(source.contains(
            "frame.currentClipToPreviousClip * currentClip"
        ));
        assertTrue(source.contains(
            "vec2 motionPixels = (previousUv - currentUv) * vec2(size)"
        ));
        assertTrue(!source.contains("PreviousDepth"));
        assertTrue(!source.contains("previousDepthNeighborhoodMatches"));
        assertTrue(!source.contains("findCutoutNeighbor"));
        assertTrue(!source.contains("HistoryBiasOutput"));
        assertTrue(!source.contains("binding = 6"));
        assertTrue(source.contains("binding = 7, rgba8"));
        assertTrue(source.contains("const float compositionMarkerAlpha = 253.0 / 255.0"));
        assertTrue(source.contains("const float particleMarkerAlpha = 252.0 / 255.0"));
        assertTrue(source.contains("float hint = isTransparencyCompositePixel(pixel)"));
    }

    @Test
    void rg16fMotionStorageCannotOverflowAndPreservesDirection() throws Exception {
        String source = source("native/shaders/motion_vectors.comp");
        assertTrue(source.contains("const uint MOTION_CLASS_STORAGE_SATURATED = 4u"));
        assertTrue(source.contains("const float maxFiniteMotionComponent = 65024.0"));
        assertTrue(source.contains(
            "float largestComponent = max(abs(motion.x), abs(motion.y))"
        ));
        assertTrue(source.contains(
            "motion *= maxFiniteMotionComponent / largestComponent"
        ));
        assertTrue(source.contains(
            "motionClass = MOTION_CLASS_STORAGE_SATURATED"
        ));
        assertTrue(!source.contains("clamp(motion"));

        assertFiniteHalfAndDirection(65_000.0F, -32_500.0F);
        assertFiniteHalfAndDirection(65_504.0F, -32_752.0F);
        assertFiniteHalfAndDirection(131_008.0F, -65_504.0F);
        assertFiniteHalfAndDirection(Float.MAX_VALUE, -Float.MAX_VALUE);

        float previousClipW = 1.0e-7F;
        assertFiniteHalfAndDirection(2.0F / previousClipW, -1.0F / previousClipW);
    }

    @Test
    void productionShaderLookupRewritesOnlyKnownTerrainShaders() throws Exception {
        String source = source("src/main/java/de/morau/nvidiadlss/mixin/ShaderCompilationCacheMixin.java");
        assertTrue(source.contains("\"minecraft\".equals(id.getNamespace())"));
        assertTrue(source.contains("\"terrain.fsh\".equals(shaderPath)"));
        assertTrue(source.contains("\"sodium\".equals(id.getNamespace())"));
        assertTrue(source.contains("\"blocks/block_layer_opaque.fsh\".equals(shaderPath)"));
        assertTrue(source.contains("\"milkshade\".equals(id.getNamespace())"));
        assertTrue(source.contains("\"sodium/block_layer_opaque.fsh\".equals(shaderPath)"));
        assertTrue(source.contains("NVIDIA_DLSS_CUTOUT"));
        assertTrue(source.contains("fragColor.a = 254.0 / 255.0"));
        assertTrue(!source.contains("fragColor.a = 0.25"));
        assertTrue(source.contains("!milkshadeVanillaTerrain) return;"));
        assertTrue(source.contains("Cutout-Alpha-Marker aktiv"));
        assertTrue(source.contains("\"post/transparency.fsh\".equals(shaderPath)"));
        assertTrue(source.contains("injectTransparencyCompositeMarkers"));
    }

    @Test
    void sodiumCompatibilityMarksOnlyTheHalfAlphaCutoutPipeline() throws Exception {
        String source = source("src/main/java/de/morau/nvidiadlss/mixin/SodiumShaderChunkRendererMixin.java");
        assertTrue(source.contains("Float.compare(value, 0.5F) == 0"));
        assertTrue(source.contains("result.withShaderDefine(\"NVIDIA_DLSS_CUTOUT\")"));
        assertTrue(source.contains("targets = \"net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer\""));
        assertTrue(!source.contains("import net.caffeinemc"));
    }

    @Test
    void transparencyHintDoesNotTreatCutoutMarkerAsComposition() throws Exception {
        String source = source("native/shaders/motion_vectors.comp");
        assertTrue(!source.contains("cutoutMarkerAlpha"));
        assertTrue(source.contains("isTransparencyCompositePixel(pixel)"));
    }

    @Test
    void optimalSizeIsRequeriedForStartupOutputModeAndExplicitReloads()
        throws Exception {
        String source = source("src/main/java/de/morau/nvidiadlss/DlssRenderer.java");
        int cacheGuard = source.indexOf("boolean currentResourcesMatchOutput");
        int optimalSizeQuery = source.indexOf("NativeStreamline.optimalSize");
        assertTrue(cacheGuard >= 0);
        assertTrue(optimalSizeQuery > cacheGuard);
        assertTrue(source.contains("width == outputWidth"));
        assertTrue(source.contains("height == outputHeight"));
        assertTrue(source.contains("mode == allocatedMode"));
        assertTrue(source.contains("!optimalSettingsRefreshRequested"));
        assertTrue(source.contains("requestOptimalSettingsRefresh(String reason)"));
    }

    private static String source(String relativePath) throws Exception {
        Path root = Path.of(System.getProperty("blockframe.projectDir"));
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static void assertFiniteHalfAndDirection(float x, float y) {
        float largest = Math.max(Math.abs(x), Math.abs(y));
        float scale = largest > MAX_FINITE_MOTION_COMPONENT
            ? MAX_FINITE_MOTION_COMPONENT / largest
            : 1.0F;
        float storedX = Float.float16ToFloat(Float.floatToFloat16(x * scale));
        float storedY = Float.float16ToFloat(Float.floatToFloat16(y * scale));

        assertTrue(Float.isFinite(storedX));
        assertTrue(Float.isFinite(storedY));
        assertTrue(Math.abs(storedX) <= 65_504.0F);
        assertTrue(Math.abs(storedY) <= 65_504.0F);
        assertEquals(Math.signum(x), Math.signum(storedX));
        assertEquals(Math.signum(y), Math.signum(storedY));
        assertEquals(y / x, storedY / storedX, 0.002F);
    }
}
