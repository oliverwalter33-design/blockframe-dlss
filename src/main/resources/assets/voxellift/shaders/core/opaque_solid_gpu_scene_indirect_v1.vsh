#version 460

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;
uniform usamplerBuffer OpaqueSolidScene;
uniform usamplerBuffer OpaqueSolidVisibility;

layout(std140) uniform OpaqueSolidFrame {
    mat4 ModelViewMat;
    ivec2 TextureSize;
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
flat out float chunkVisibility;
flat out ivec2 textureSize;

void main() {
    uint slot = uint(gl_BaseInstance);
    uvec4 section = texelFetch(OpaqueSolidScene, int(slot * 2u + 1u));
    vec3 chunkPosition = vec3(
        int(section.y),
        int(section.z),
        int(section.w)
    );
    vec3 pos = Position + (chunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    chunkVisibility = uintBitsToFloat(
        texelFetch(OpaqueSolidVisibility, int(slot)).r
    );
    textureSize = TextureSize;
}
