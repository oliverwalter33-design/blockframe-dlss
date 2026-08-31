package de.morau.nvidiadlss;

/**
 * Exact, fail-closed shader rewrites for temporal material metadata carried in
 * the otherwise unused alpha channel of Minecraft's pre-DLSS world target.
 */
public final class TemporalMaterialShaderPatcher {
    private static final String MAIN_SAMPLE =
        "    color_layers[0] = vec4(texture(MainSampler, texCoord).rgb, 1.0);";
    private static final String COMPOSITE_SAMPLES =
        "    try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);\n"
            + "    try_insert(texture(ItemEntitySampler, texCoord), texture(ItemEntityDepthSampler, texCoord).r);\n"
            + "    try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);\n"
            + "    try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);\n"
            + "    try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);";
    private static final String FINAL_OUTPUT =
        "    fragColor = vec4(texelAccum.rgb, 1.0);";

    private TemporalMaterialShaderPatcher() {}

    /**
     * Reuses the samples already required by Mojang's transparency compositor.
     * 254 is reserved for cutout, 253 for composition and 252 for particles.
     * No texture lookup or full-screen pass is added.
     */
    public static String injectTransparencyCompositeMarkers(String source) {
        if (source == null
            || !source.contains(MAIN_SAMPLE)
            || !source.contains(COMPOSITE_SAMPLES)
            || !source.contains(FINAL_OUTPUT)) {
            return source;
        }

        String mainSample =
            "    vec4 blockframeMainLayer = texture(MainSampler, texCoord);\n"
                + "    color_layers[0] = vec4(blockframeMainLayer.rgb, 1.0);";
        String compositeSamples =
            "    vec4 blockframeTranslucentLayer = texture(TranslucentSampler, texCoord);\n"
                + "    vec4 blockframeItemEntityLayer = texture(ItemEntitySampler, texCoord);\n"
                + "    vec4 blockframeParticleLayer = texture(ParticlesSampler, texCoord);\n"
                + "    vec4 blockframeWeatherLayer = texture(WeatherSampler, texCoord);\n"
                + "    vec4 blockframeCloudLayer = texture(CloudsSampler, texCoord);\n"
                + "    try_insert(blockframeTranslucentLayer, texture(TranslucentDepthSampler, texCoord).r);\n"
                + "    try_insert(blockframeItemEntityLayer, texture(ItemEntityDepthSampler, texCoord).r);\n"
                + "    try_insert(blockframeParticleLayer, texture(ParticlesDepthSampler, texCoord).r);\n"
                + "    try_insert(blockframeWeatherLayer, texture(WeatherDepthSampler, texCoord).r);\n"
                + "    try_insert(blockframeCloudLayer, texture(CloudsDepthSampler, texCoord).r);";
        String finalOutput =
            "    bool blockframeHasParticle = blockframeParticleLayer.a != 0.0;\n"
                + "    bool blockframeHasComposition = blockframeTranslucentLayer.a != 0.0\n"
                + "        || blockframeItemEntityLayer.a != 0.0\n"
                + "        || blockframeWeatherLayer.a != 0.0\n"
                + "        || blockframeCloudLayer.a != 0.0;\n"
                + "    float blockframeMaterialAlpha = blockframeHasParticle ? 252.0 / 255.0\n"
                + "        : (blockframeHasComposition ? 253.0 / 255.0 : blockframeMainLayer.a);\n"
                + "    fragColor = vec4(texelAccum.rgb, blockframeMaterialAlpha);";
        return source
            .replace(MAIN_SAMPLE, mainSample)
            .replace(COMPOSITE_SAMPLES, compositeSamples)
            .replace(FINAL_OUTPUT, finalOutput);
    }
}
