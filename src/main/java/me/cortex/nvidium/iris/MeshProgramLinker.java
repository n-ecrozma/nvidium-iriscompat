package me.cortex.nvidium.iris;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.NVMeshShader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Links a shader pack's terrain program for Nvidium: Nvidium's task shader, a mesh shader assembled from the pack's
 * (Iris-patched) vertex shader, and the pack's (Iris-patched) fragment shader, unchanged.
 */
public final class MeshProgramLinker {
    /** Iris's optional per-vertex fields that the mesh shader can load (see FormatAnalyzer in Iris). */
    private static final List<String> EXTRA_FIELDS = List.of("mc_Entity", "iris_Normal", "mc_midTexCoord", "at_midBlock");

    /**
     * Mesh workgroup sizes to try, largest first. Smaller groups emit fewer vertices each, which helps packs whose
     * per-vertex outputs don't fit the mesh shader output limit for 64 vertices.
     */
    private static final int[] WORKGROUP_SIZES = {32, 16, 8};

    private MeshProgramLinker() {}

    public static PartialShader link(String name, IrisTerrainPrograms.Pass pass, String vertex, String geometry,
                                     String tessControl, String tessEval, String fragment, VertexFormat vertexFormat) {
        if (geometry != null || tessControl != null || tessEval != null) {
            throw new MeshShaderAssembler.UnsupportedPackException(
                    "the pack's terrain program uses geometry or tessellation shaders, which can't run in a mesh shader");
        }

        var defines = new ArrayList<String>();
        if (pass.kind == MeshShaderAssembler.Kind.TRANSLUCENT) {
            switch (Nvidium.config.translucency_sorting_level) {
                // Quad sorting reorders vertices from inside Nvidium's own translucent mesh shader
                case QUADS -> throw new MeshShaderAssembler.UnsupportedPackException(
                        "the \"Quads\" translucency sorting setting isn't supported with shader packs; use \"Sodium\" or \"Sections\"");
                case SODIUM -> defines.add("_NV_SODIUM_SORT");
                default -> {}
            }
        }

        PackVertexStage stage = MeshShaderTransformer.transform(vertex);
        MeshShaderAssembler.VertexLayout layout = layoutOf(vertexFormat);

        String lastError = null;
        for (int threads : WORKGROUP_SIZES) {
            String task = ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", pass.taskShader),
                    ShaderDefines.builder().define("MESH_WORKLOAD_PER_INVOCATION", String.valueOf(threads)));
            String mesh = MeshShaderAssembler.assemble(pass.kind, stage, layout, threads, defines);

            var sources = new LinkedHashMap<String, String>();
            sources.put("task", task);
            sources.put("mesh", mesh);
            sources.put("frag", fragment);
            if (Nvidium.IS_DEBUG) {
                dump(name, pass, threads, sources);
            }

            try {
                return compileAndLink(name, task, mesh, fragment);
            } catch (ShaderBuildException e) {
                lastError = e.getMessage();
                Nvidium.LOGGER.warn("Nvidium: {} ({}) failed to build with {} threads per mesh workgroup{}",
                        name, pass, threads, threads == WORKGROUP_SIZES[WORKGROUP_SIZES.length - 1] ? "" : ", retrying with fewer");
                if (threads == WORKGROUP_SIZES[WORKGROUP_SIZES.length - 1]) {
                    Path dumped = dump(name, pass, threads, sources);
                    throw new ShaderBuildException(lastError + (dumped != null ? "\nSources written to " + dumped : ""));
                }
            }
        }
        throw new ShaderBuildException(lastError);
    }

    static MeshShaderAssembler.VertexLayout layoutOf(VertexFormat format) {
        int size = format.getVertexSize();
        if (size % 4 != 0) {
            throw new MeshShaderAssembler.UnsupportedPackException("terrain vertex size " + size + " is not a multiple of 4");
        }
        Map<String, Integer> extras = new LinkedHashMap<>();
        for (String field : EXTRA_FIELDS) {
            if (format.contains(field)) {
                VertexFormatElement element = format.getElement(field);
                if (element.offset() % 4 != 0) {
                    throw new MeshShaderAssembler.UnsupportedPackException("vertex field " + field + " is not 4-byte aligned");
                }
                extras.put(field, element.offset() / 4);
            }
        }
        return new MeshShaderAssembler.VertexLayout(size / 4, extras);
    }

    private static PartialShader compileAndLink(String name, String task, String mesh, String fragment) {
        int program = GL20C.glCreateProgram();
        int taskShader = -1, meshShader = -1, fragmentShader = -1;
        try {
            taskShader = compile(name, "task", NVMeshShader.GL_TASK_SHADER_NV, task);
            meshShader = compile(name, "mesh", NVMeshShader.GL_MESH_SHADER_NV, mesh);
            fragmentShader = compile(name, "fragment", GL20C.GL_FRAGMENT_SHADER, fragment);
            GL20C.glAttachShader(program, taskShader);
            GL20C.glAttachShader(program, meshShader);
            GL20C.glAttachShader(program, fragmentShader);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
                throw new ShaderBuildException("Linking " + name + " failed:\n" + GL20C.glGetProgramInfoLog(program));
            }
        } catch (ShaderBuildException e) {
            for (int shader : new int[]{taskShader, meshShader, fragmentShader}) {
                if (shader > 0) {
                    GL20C.glDeleteShader(shader);
                }
            }
            GL20C.glDeleteProgram(program);
            throw e;
        }
        // PartialShader only uses the stage slots to detach and delete the shaders once Iris has set up the program
        return new PartialShader(program, meshShader, fragmentShader, taskShader, -1, -1);
    }

    private static int compile(String name, String stage, int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL20C.GL_TRUE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new ShaderBuildException("Compiling the " + stage + " shader of " + name + " failed:\n" + log);
        }
        return shader;
    }

    private static Path dump(String name, IrisTerrainPrograms.Pass pass, int threads, Map<String, String> sources) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("nvidium-iris-debug");
            Files.createDirectories(dir);
            String base = (name + "_" + pass.name().toLowerCase() + "_" + threads).replaceAll("[^A-Za-z0-9_.-]", "_");
            for (var entry : sources.entrySet()) {
                Files.writeString(dir.resolve(base + "." + entry.getKey() + ".glsl"), entry.getValue());
            }
            return dir;
        } catch (IOException e) {
            Nvidium.LOGGER.warn("Nvidium: could not write shader sources for debugging", e);
            return null;
        }
    }

    public static final class ShaderBuildException extends RuntimeException {
        public ShaderBuildException(String message) {
            super(message);
        }
    }
}
