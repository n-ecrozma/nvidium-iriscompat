#version 460 core
// Mesh shader that runs a shader pack's translucent terrain (gbuffers_water) vertex shader on Nvidium's terrain
// data. Assembled by me.cortex.nvidium.iris.MeshShaderAssembler like terrain/iris/mesh.glsl; see that file.
// Follows terrain/translucent/mesh.glsl: one thread per quad, emitting its 4 vertices and 2 triangles, in the
// order Sodium sorted them (or in stored order when Sodium's sorting isn't used).
#extension GL_NV_mesh_shader : require
#extension GL_NV_shader_buffer_load : require
${PACK_EXTENSIONS}
${DEFINES}

layout(local_size_x = ${THREADS}) in;
layout(triangles, max_vertices = ${MAX_VERTICES}, max_primitives = ${MAX_PRIMITIVES}) out;

// Written by terrain/translucent/task.glsl (without quad sorting). The block name and members must match.
taskNV in Task {
    vec4 originAndBaseData;
    uint quadCount;
    int translucencyIndex;
    int sectionFadeStart;
} _nv_task;

layout(std140, binding = ${SCENE_BINDING}) uniform _NvIrisScene {
    vec4 _nv_subchunkOffset;
    // Terrain vertices, ${VERTEX_WORDS} words each. Sodium's sorted quad order lives in the same buffer.
    readonly restrict uint *_nv_terrainData;
    int _nv_currentTime;
};

uint _nv_vid;
uint _nv_vertexBase;
int _nv_sectionTime;

uint _nv_word(uint index) {
    return _nv_terrainData[_nv_vertexBase + index];
}

vec3 _nv_section_origin() {
    return _nv_task.originAndBaseData.xyz;
}

int _nv_section_fade_start() {
    return _nv_task.sectionFadeStart;
}

// ---- shader pack vertex stage ----
${PACK_BODY}
// ---- end of shader pack vertex stage ----

${OUTPUT_DECLARATIONS}

void _nv_load_vertex() {
${LOAD_INPUTS}
}

void _nv_init_globals() {
${INIT_GLOBALS}
}

void _nv_run_vertex(uint vertexIndex, uint slot) {
    _nv_vid = slot;
    _nv_vertexBase = vertexIndex * ${VERTEX_WORDS}u;
    _nv_load_vertex();
${SECTION_SETUP}
    _nv_vs_Position = vec4(0.0);
    _nv_init_globals();
    _nv_pack_main();
    gl_MeshVerticesNV[slot].gl_Position = _nv_vs_Position;
${COPY_OUTPUTS}
}

void main() {
    uint quadIndex = gl_GlobalInvocationID.x;
    if (quadIndex >= _nv_task.quadCount) {
        return;
    }

    // Index of the section's first quad in the terrain buffer
    uint baseQuad = floatBitsToUint(_nv_task.originAndBaseData.w);
    uint quad = baseQuad + quadIndex;
#ifdef _NV_SODIUM_SORT
    // Sodium's sorted quad order, back to front; -1 when the section has no sorting data
    if (_nv_task.translucencyIndex != -1) {
        quad = baseQuad + _nv_terrainData[uint(_nv_task.translucencyIndex) + quadIndex];
    }
#endif

    uint vertexBase = gl_LocalInvocationID.x << 2;
    uint primitiveBase = gl_LocalInvocationID.x * 6u;
    gl_PrimitiveIndicesNV[primitiveBase + 0u] = vertexBase + 0u;
    gl_PrimitiveIndicesNV[primitiveBase + 1u] = vertexBase + 1u;
    gl_PrimitiveIndicesNV[primitiveBase + 2u] = vertexBase + 2u;
    gl_PrimitiveIndicesNV[primitiveBase + 3u] = vertexBase + 2u;
    gl_PrimitiveIndicesNV[primitiveBase + 4u] = vertexBase + 3u;
    gl_PrimitiveIndicesNV[primitiveBase + 5u] = vertexBase + 0u;

    for (uint corner = 0u; corner < 4u; corner++) {
        _nv_run_vertex((quad << 2) + corner, vertexBase + corner);
    }

    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x << 1)].gl_PrimitiveID = int(quad << 1);
    gl_MeshPrimitivesNV[(gl_LocalInvocationID.x << 1) | 1u].gl_PrimitiveID = int(quad << 1) | 1;

    if (gl_LocalInvocationID.x == 0u) {
        // Quads left for this workgroup, two triangles each
        uint remaining = uint(int(_nv_task.quadCount) - int(gl_WorkGroupID.x * ${THREADS}u));
        gl_PrimitiveCountNV = min(remaining << 1, ${MAX_PRIMITIVES}u);
    }
}
