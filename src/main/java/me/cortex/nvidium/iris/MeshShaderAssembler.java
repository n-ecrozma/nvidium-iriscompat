package me.cortex.nvidium.iris;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the complete mesh shader source for a shader pack's terrain program: fills
 * {@code assets/nvidium/shaders/terrain/iris/mesh.glsl} with the pack's rewritten vertex stage and a vertex loader
 * generated for the terrain vertex layout. Has no Minecraft or Iris dependencies so it can be unit tested.
 */
public final class MeshShaderAssembler {
    private static final Pattern MARKER = Pattern.compile("\\$\\{([A-Z_]+)}");

    /** Uniform buffer binding for {@code _NvIrisScene}. Iris's fixed bindings use 0 to 6 (see IrisBindings). */
    public static final int SCENE_BINDING = 7;

    /**
     * The terrain vertex layout: Sodium's 20 fixed bytes followed by Iris's optional fields.
     *
     * @param vertexWords   size of one vertex in 32-bit words
     * @param extraFieldWords word index of each optional Iris field that is present ({@code mc_Entity},
     *                      {@code iris_Normal}, {@code mc_midTexCoord}, {@code at_midBlock})
     */
    public record VertexLayout(int vertexWords, Map<String, Integer> extraFieldWords) {
        public VertexLayout {
            if (vertexWords < 5) {
                throw new IllegalArgumentException("Terrain vertices must be at least 5 words (Sodium's layout), got " + vertexWords);
            }
            extraFieldWords = Map.copyOf(extraFieldWords);
        }
    }

    /** Which mesh shader template to fill, matching one of Nvidium's terrain task shaders. */
    public enum Kind {
        /** Two threads per quad, one triangle each (terrain/task.glsl and temporal_task.glsl). */
        OPAQUE("/assets/nvidium/shaders/terrain/iris/mesh.glsl", 2, 1),
        /** One thread per quad, four vertices and two triangles each (terrain/translucent/task.glsl). */
        TRANSLUCENT("/assets/nvidium/shaders/terrain/iris/translucent_mesh.glsl", 4, 2);

        final String template;
        final int verticesPerThread;
        final int primitivesPerThread;

        Kind(String template, int verticesPerThread, int primitivesPerThread) {
            this.template = template;
            this.verticesPerThread = verticesPerThread;
            this.primitivesPerThread = primitivesPerThread;
        }
    }

    /** Thrown for packs the mesh path can't run; the caller falls back. */
    public static final class UnsupportedPackException extends RuntimeException {
        public UnsupportedPackException(String message) {
            super(message);
        }
    }

    private MeshShaderAssembler() {}

    /** Opaque terrain without extra defines; see {@link #assemble(Kind, PackVertexStage, VertexLayout, int, List)}. */
    public static String assemble(PackVertexStage stage, VertexLayout layout, int threadsPerGroup) {
        return assemble(Kind.OPAQUE, stage, layout, threadsPerGroup, List.of());
    }

    /**
     * @param threadsPerGroup mesh workgroup size; must match the task shader's {@code MESH_WORKLOAD_PER_INVOCATION}
     * @param defines         names to {@code #define} in the generated shader (e.g. {@code _NV_SODIUM_SORT})
     */
    public static String assemble(Kind kind, PackVertexStage stage, VertexLayout layout, int threadsPerGroup, List<String> defines) {
        if (threadsPerGroup <= 0 || threadsPerGroup > 32 || threadsPerGroup % 2 != 0) {
            throw new IllegalArgumentException("Mesh workgroup size must be an even number up to 32, got " + threadsPerGroup);
        }

        var values = new LinkedHashMap<String, String>();
        values.put("PACK_EXTENSIONS", String.join("\n", stage.extensions()));
        values.put("DEFINES", String.join("\n", defines.stream().map(name -> "#define " + name).toList()));
        values.put("THREADS", String.valueOf(threadsPerGroup));
        values.put("MAX_VERTICES", String.valueOf(threadsPerGroup * kind.verticesPerThread));
        values.put("MAX_PRIMITIVES", String.valueOf(threadsPerGroup * kind.primitivesPerThread));
        values.put("SCENE_BINDING", String.valueOf(SCENE_BINDING));
        values.put("VERTEX_WORDS", String.valueOf(layout.vertexWords()));
        values.put("PACK_BODY", stage.body());
        values.put("OUTPUT_DECLARATIONS", String.join("\n", stage.outputDeclarations()));
        values.put("LOAD_INPUTS", indent(loadInputs(stage, layout)));
        values.put("INIT_GLOBALS", indent(stage.globalInitializers()));
        values.put("SECTION_SETUP", indent(sectionSetup(stage)));
        values.put("COPY_OUTPUTS", indent(stage.outputCopies()));

        return fill(template(kind.template), values);
    }

    /**
     * Generates statements that fill the pack's former vertex inputs from the current vertex's words.
     * Word layout (see Iris's XHFPTerrainVertex): 0-1 interleaved position, 2 colour, 3 texture coordinates,
     * 4 block light, sky light, tangent sign and Sodium section index; then Iris's optional fields.
     */
    static List<String> loadInputs(PackVertexStage stage, VertexLayout layout) {
        var lines = new ArrayList<String>();
        stage.inputs().forEach((name, type) -> {
            String value = switch (name) {
                case "a_Position" -> "uvec2(_nv_word(0u), _nv_word(1u))";
                case "a_Color" -> "unpackUnorm4x8(_nv_word(2u))";
                case "a_TexCoord" -> "uvec2(_nv_word(3u) & 0xFFFFu, _nv_word(3u) >> 16u)";
                // The section index (w) is forced to 0: Nvidium positions each section through iris_RegionOffset,
                // so Sodium's per-section translation must be zero. It also makes the chunk fade lookup read
                // entry 0 of u_SectionTimeInfo, which Nvidium fills with "no fade".
                // z: Iris's tangent sign (bit 0); bit 1 is Nvidium's cutout quad mark, hidden from the pack
                case "a_LightAndData" -> "uvec4(_nv_word(4u) & 0xFFu, (_nv_word(4u) >> 8u) & 0xFFu, (_nv_word(4u) >> 16u) & 0xFDu, 0u)";
                default -> extraField(name, layout);
            };

            if (type.contains("[")) {
                throw new UnsupportedPackException("Arrayed vertex input '" + name + "' is not supported");
            }
            if (value == null) {
                // Not something Iris provides for terrain: give it a defined value
                lines.add(name + " = " + type + "(0);");
            } else {
                lines.add(name + " = " + type + "(" + value + ");");
            }
        });
        return lines;
    }

    private static String extraField(String name, VertexLayout layout) {
        Integer word = layout.extraFieldWords().get(name);
        if (word == null) {
            return null;
        }
        String w = "_nv_word(" + word + "u)";
        return switch (name) {
            case "mc_Entity", "iris_Normal" -> w;
            case "mc_midTexCoord" -> "uvec2(" + w + " & 0xFFFFu, " + w + " >> 16u)";
            case "at_midBlock" -> "unpackSnorm4x8(" + w + ")";
            default -> null;
        };
    }

    private static List<String> sectionSetup(PackVertexStage stage) {
        var lines = new ArrayList<String>();
        if (stage.globals().contains("iris_RegionOffset")) {
            lines.add("iris_RegionOffset = _nv_section_origin() + _nv_subchunkOffset.xyz;");
        }
        if (stage.globals().contains("iris_CurrentTime")) {
            lines.add("iris_CurrentTime = _nv_currentTime;");
        }
        lines.add(MeshShaderTransformer.SECTION_TIME + " = _nv_section_fade_start();");
        if (stage.globals().contains("iris_RegionID")) {
            lines.add("iris_RegionID = 0u;");
        }
        if (stage.body().contains(MeshShaderTransformer.VERTEX_ID)) {
            lines.add(MeshShaderTransformer.VERTEX_ID + " = int(vertexIndex);");
        }
        return lines;
    }

    private static String indent(List<String> lines) {
        var out = new StringBuilder();
        for (String line : lines) {
            out.append("    ").append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String fill(String template, Map<String, String> values) {
        Matcher matcher = MARKER.matcher(template);
        var out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                // Leave documentation mentions such as "${...}" alone; anything else is a template bug
                throw new IllegalStateException("No value for template marker ${" + key + "}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String template(String path) {
        try (InputStream in = MeshShaderAssembler.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + path);
            }
            // Git on Windows may check the template out with CRLF line endings; keep the output consistent
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
