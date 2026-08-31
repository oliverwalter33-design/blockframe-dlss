#version 460

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec4 Normal;

uniform sampler2D Sampler2;
uniform usamplerBuffer NativeTerrainScene;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 nativeTerrainNormal;

void main() {
    uint slot = uint(gl_BaseInstance);
    uvec4 scene1 = texelFetch(
        NativeTerrainScene,
        int(slot * 5u + 1u)
    );
    uvec4 scene2 = texelFetch(
        NativeTerrainScene,
        int(slot * 5u + 2u)
    );
    vec3 sectionPosition = vec3(
        int(scene1.z),
        int(scene1.w),
        int(scene2.x)
    );
    vec3 pos = Position
        + (sectionPosition - CameraBlockPos)
        + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    nativeTerrainNormal = normalize(Normal.xyz);
}
