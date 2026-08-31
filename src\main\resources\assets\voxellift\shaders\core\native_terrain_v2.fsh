#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 nativeTerrainNormal;

out vec4 fragColor;

vec4 sampleNearest(
    sampler2D source,
    vec2 uv,
    vec2 pixelSize,
    vec2 du,
    vec2 dv,
    vec2 texelScreenSize
) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset =
        (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);
    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float blendFactor = smoothstep(
        minPixelSize,
        minPixelSize * 2.0,
        maxTexelSize
    );
    float minDerivative = min(length(du), length(dv));
    float maxDerivative = max(length(du), length(dv));
    float mipLevelExact = max(
        0.0,
        log2(sqrt(minDerivative * maxDerivative) / minPixelSize)
    );
    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);
    const vec2 offsets[4] = vec2[](
        vec2(0.125, 0.375),
        vec2(-0.125, -0.375),
        vec2(0.375, -0.125),
        vec2(-0.375, 0.125)
    );
    vec4 low = vec4(0.0);
    vec4 high = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        low += textureLod(source, sampleUV, mipLevelLow);
        high += textureLod(source, sampleUV, mipLevelHigh);
    }
    low *= 0.25;
    high *= 0.25;
    vec4 nearest = sampleNearest(
        source,
        uv,
        pixelSize,
        du,
        dv,
        texelScreenSize
    );
    return mix(nearest, mix(low, high, mipBlend), blendFactor);
}

void main() {
    vec2 pixelSize = 1.0f / vec2(textureSize(Sampler0, 0));
    vec4 sampled = UseRgss == 1
        ? sampleRGSS(Sampler0, texCoord0, pixelSize)
        : sampleNearest(Sampler0, texCoord0, pixelSize);
    vec4 color = sampled * vertexColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = apply_fog(
        color,
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
#ifdef ALPHA_CUTOUT
    // Match BlockFrame's Mojang-terrain material marker exactly. RGBA8
    // encodes this as 254 while opaque Solid remains 255.
    fragColor.a = 254.0 / 255.0;
#endif
}
