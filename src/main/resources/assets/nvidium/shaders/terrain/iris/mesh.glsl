#version 460 core
// Mesh shader that runs a shader pack's terrain vertex shader on Nvidium's terrain data.
// Assembled by me.cortex.nvidium.iris.MeshShaderAssembler, which replaces the dollar-brace markers and inserts
// the pack's code (rewritten by MeshShaderTransformer) where marked below.
//
// Deliberately does NOT enable NVIDIA's gpu_shader5 extension (unlike Nvidium's own shaders): it adds float64_t
// overloads that make ordinary pack code such as clamp(x, 0, 1.0) ambiguous. Vertices are read as 32-bit words.
#extension GL_NV_mesh_shader : require
#extension GL_NV_shader_buffer_load : require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_arithmetic : require
${PACK_EXTENSIONS}
${DEFINES}

// Two threads per quad, each emitting one triangle
layout(local_size_x = ${THREADS}) in;
layout(triangles, max_vertices = ${MAX_VERTICES}, max_primitives = ${MAX_PRIMITIVES}) out;

// Written by Nvidium's terrain task shaders (terrain/task_common2.glsl). The block name and members must match.
taskNV in Task {
    uvec4 binStarts;
    uvec4 binOffsets;
    vec3 origin;
    uint quadCount;
    uint transformationId;
} _nv_task;

// Position of the section being drawn, relative to the chunk the camera is in
vec3 _nv_section_origin() {
    return _nv_task.origin;
}

layout(std140, binding = ${SCENE_BINDING}) uniform _NvIrisScene {
    // Camera position within its chunk, negated: added to a section's origin to make it camera-relative
    vec4 _nv_subchunkOffset;
    // Terrain vertices, ${VERTEX_WORDS} words each, in the layout Iris chose for the active pack
    readonly restrict uint *_nv_terrainData;
};

uint _nv_vid;
uint _nv_vertexBase;

// Which quads to draw, set by Nvidium per draw: 0 = all, 1 = solid only, 2 = cutout only. Used when the pack's
// solid and cutout programs differ; cutout quads are marked with bit 17 of word 4 (see SodiumResultCompatibility).
uniform int _nv_quadFilter;

uint _nv_word(uint index) {
    return _nv_terrainData[_nv_vertexBase + index];
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

// Same binary search as terrain/mesh.glsl: find this thread's quad from the task's face bins
uint _nv_quad_offset() {
    uint gii = gl_GlobalInvocationID.x >> 1;
    bvec4 le = lessThan(uvec4(gii), _nv_task.binStarts);
    uint retval = _nv_task.binOffsets.w;
    if (le.y) {
        retval = _nv_task.binOffsets.x;
    } else if (le.z) {
        retval = _nv_task.binOffsets.y;
    } else if (le.w) {
        retval = _nv_task.binOffsets.z;
    }
    return retval + gii;
}

void main() {
    if (gl_LocalInvocationIndex == 0u) {
        gl_PrimitiveCountNV = 0u;
    }

    if (_nv_task.quadCount <= (gl_GlobalInvocationID.x >> 1)) {
        return;
    }

    uint quadId = _nv_quad_offset();
    if (quadId == uint(-1)) {
        return;
    }

    if (_nv_quadFilter != 0) {
        // Both threads of a quad read the same flag, so they skip it together
        bool cutout = ((_nv_terrainData[(quadId << 2) * ${VERTEX_WORDS}u + 4u] >> 17) & 1u) != 0u;
        if (cutout != (_nv_quadFilter == 2)) {
            return;
        }
    }

    // Each pair of threads emits one quad as two triangles: (v0, v1, v2) and (v2, v3, v0)
    bool triangle1 = (gl_LocalInvocationIndex & 1u) == 1u;
    uint pairBase = (gl_LocalInvocationIndex & ~1u) * 2u;
    uint commonSlot = pairBase + uint(triangle1);
    uint uniqueSlot = commonSlot + 2u;

    _nv_run_vertex((quadId << 2) + (triangle1 ? 2u : 0u), commonSlot);
    _nv_run_vertex((quadId << 2) + (triangle1 ? 3u : 1u), uniqueSlot);

    uint triId = subgroupExclusiveAdd(1u);
    gl_PrimitiveIndicesNV[triId * 3u + 0u] = commonSlot;
    gl_PrimitiveIndicesNV[triId * 3u + 1u] = uniqueSlot;
    gl_PrimitiveIndicesNV[triId * 3u + 2u] = pairBase + uint(!triangle1);
    gl_MeshPrimitivesNV[triId].gl_PrimitiveID = int(quadId << 1) | int(triangle1);

    uint triCount = subgroupMax(triId);
    if (subgroupElect()) {
        gl_PrimitiveCountNV = triCount + 1u;
    }
}
