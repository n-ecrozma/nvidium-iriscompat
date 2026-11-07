package me.cortex.nvidium.iris;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A shader pack's terrain vertex shader (as patched by Iris for Sodium), rewritten so it can run as a function
 * inside Nvidium's mesh shader. Produced by {@link MeshShaderTransformer}, consumed by {@link MeshShaderAssembler}.
 *
 * @param extensions         the pack's {@code #extension} lines; they must go right after {@code #version}
 * @param body               the pack's declarations and functions, with {@code main} renamed to {@value MeshShaderTransformer#PACK_MAIN}
 * @param inputs             former vertex inputs (now plain globals), name to GLSL type, in declaration order
 * @param outputDeclarations mesh shader per-vertex output declarations, e.g. {@code flat out vec3 normal[];}
 * @param outputCopies       statements copying one vertex's outputs into slot {@value MeshShaderTransformer#VERTEX_SLOT}
 * @param globalInitializers statements re-running the pack's global variable initializers, in order
 * @param globals            plain globals the pack declares (used to detect e.g. {@code iris_RegionOffset})
 */
public record PackVertexStage(
        List<String> extensions,
        String body,
        Map<String, String> inputs,
        List<String> outputDeclarations,
        List<String> outputCopies,
        List<String> globalInitializers,
        Set<String> globals
) {
}
