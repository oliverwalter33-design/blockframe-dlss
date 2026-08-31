package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TemporalMaterialShaderPatcherTest {
    private static final String VANILLA_SOURCE = """
        void main() {
            color_layers[0] = vec4(texture(MainSampler, texCoord).rgb, 1.0);
            depth_layers[0] = texture(MainDepthSampler, texCoord).r;
            active_layers = 1;

            try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
            try_insert(texture(ItemEntitySampler, texCoord), texture(ItemEntityDepthSampler, texCoord).r);
            try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
            try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
            try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);

            vec3 texelAccum = color_layers[0].rgb;
            fragColor = vec4(texelAccum.rgb, 1.0);
        }
        """;

    @Test
    void reusesEveryExistingColorSampleExactlyOnceAndPreservesMainAlpha() {
        String patched = TemporalMaterialShaderPatcher.injectTransparencyCompositeMarkers(VANILLA_SOURCE);

        assertTrue(patched.contains("blockframeHasParticle ? 252.0 / 255.0"));
        assertTrue(patched.contains("blockframeHasComposition ? 253.0 / 255.0"));
        assertTrue(patched.contains(": blockframeMainLayer.a"));
        for (String sampler : new String[] {
            "MainSampler", "TranslucentSampler", "ItemEntitySampler",
            "ParticlesSampler", "WeatherSampler", "CloudsSampler"
        }) {
            assertEquals(1, occurrences(patched, "texture(" + sampler + ", texCoord)"), sampler);
        }
    }

    @Test
    void unknownReplacementShaderFailsClosedWithoutMutation() {
        String unknown = "void main() { fragColor = vec4(1.0); }";
        assertSame(unknown, TemporalMaterialShaderPatcher.injectTransparencyCompositeMarkers(unknown));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int offset = 0; (offset = source.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }
}
