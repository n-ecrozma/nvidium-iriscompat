#version 330 core
#extension GL_ARB_explicit_attrib_location : enable
layout(std140) uniform u_Globals {
    mat4 u_ProjectionMatrix;
    mat4 u_ModelViewMatrix;
    vec4 u_FogColor;
    vec2 u_EnvironmentFog;
    vec2 u_RenderFog;
    vec2 u_TexelSize;
    vec2 u_TexCoordShrink;
    float u_FadePeriodInv;
    bool u_UseRGSS;
};
uniform mat4 iris_ModelViewMatInverse;
uniform mat4 iris_ProjMatInverse;
uniform mat3 iris_NormalMat;
layout(std140) uniform iris_SodiumPushConstants {
    vec3 iris_RegionOffset;
    int iris_CurrentTime;
    uint iris_RegionID;
};
in uvec2 a_Position;
in uvec2 a_TexCoord;
in vec4 a_Color;
in uvec4 a_LightAndData;
in uint mc_Entity;
vec2 iris_Entity = vec2(int(mc_Entity >> 1u) - 1, mc_Entity & 1u);
in uvec2 mc_midTexCoord;
vec2 iris_MidTex = (vec2(mc_midTexCoord.xy) * 3.0517578E-5).xy;
vec3 _vert_position;
vec2 _vert_tex_diffuse_coord;
vec2 _vert_tex_diffuse_coord_bias;
vec2 _vert_tex_light_coord;
vec4 _vert_color;
const uint POSITION_BITS = 20u;
const uint POSITION_MAX_COORD = 1u << POSITION_BITS;
const uint TEXTURE_BITS = 15u;
const uint TEXTURE_MAX_COORD = 1u << TEXTURE_BITS;
const uint TEXTURE_MAX_VALUE = TEXTURE_MAX_COORD - 1u;
const float VERTEX_SCALE = 32.0 / POSITION_MAX_COORD;
const float VERTEX_OFFSET = -8.0;
float mc_chunkFade;
uint _draw_id;
vec3 irs_Normal;
vec4 irs_Tangent;
uniform isamplerBuffer u_SectionTimeInfo;
uvec3 _deinterleave_u20x3(uvec2 data) {
    uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    return (hi << 10u) | lo;
}
vec2 _get_texcoord() {
    return vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);
}
void _vert_init() {
    _vert_position = ((_deinterleave_u20x3(a_Position) * VERTEX_SCALE) + VERTEX_OFFSET);
    _vert_tex_diffuse_coord = _get_texcoord();
    _vert_tex_light_coord = vec2(a_LightAndData.xy);
    _vert_color = a_Color;
    _draw_id = a_LightAndData[3];
    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((iris_RegionID * 256u) + uint(chunkId))).r;
    float fade = clamp(float(iris_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    mc_chunkFade = (chunkFade < 0) ? 1.0 : fade;
}
uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}
vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0f);
}
mat4 iris_undoRevZ(mat4 projection) {
    return projection;
}
vec4 getVertexPosition() {
    return vec4(_vert_position + iris_RegionOffset + _get_draw_translation(_draw_id), 1.0);
}
uniform float frameTimeCounter;
out vec2 texcoord;
flat out vec2 lmcoord;
out vec4 glcolor;
out VertexData {
    vec3 viewPos;
    flat int blockId;
} vs_out;
invariant gl_Position;
void main() {
    _vert_init();
    texcoord = _vert_tex_diffuse_coord;
    lmcoord = _vert_tex_light_coord / 256.0;
    glcolor = _vert_color;
    vec4 viewPos = u_ModelViewMatrix * getVertexPosition();
    vs_out.viewPos = viewPos.xyz;
    vs_out.blockId = int(iris_Entity.x) + gl_VertexID * 0;
    gl_Position = iris_undoRevZ(u_ProjectionMatrix) * viewPos;
}
