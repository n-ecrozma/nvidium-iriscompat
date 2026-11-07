package me.cortex.nvidium.iris;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MeshShaderTransformerTest {
    private static String source;
    private static PackVertexStage stage;

    @BeforeAll
    static void transform() throws IOException {
        try (InputStream in = MeshShaderTransformerTest.class.getResourceAsStream("/iris/patched_terrain.vsh")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        stage = MeshShaderTransformer.transform(source);
        System.out.println("---- transformed body ----\n" + stage.body());
        System.out.println("---- outputs ----\n" + String.join("\n", stage.outputDeclarations()));
        System.out.println("---- copies ----\n" + String.join("\n", stage.outputCopies()));
        System.out.println("---- initializers ----\n" + String.join("\n", stage.globalInitializers()));
    }

    private static boolean has(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }

    @Test
    void collectsExtensionsAndDropsVersion() {
        assertEquals(1, stage.extensions().size());
        assertTrue(stage.extensions().getFirst().startsWith("#extension GL_ARB_explicit_attrib_location"));
        assertFalse(stage.body().contains("#version"));
        assertFalse(stage.body().contains("#extension"));
    }

    @Test
    void inputsBecomeGlobals() {
        assertEquals("uvec2", stage.inputs().get("a_Position"));
        assertEquals("uvec2", stage.inputs().get("a_TexCoord"));
        assertEquals("vec4", stage.inputs().get("a_Color"));
        assertEquals("uvec4", stage.inputs().get("a_LightAndData"));
        assertEquals("uint", stage.inputs().get("mc_Entity"));
        assertEquals("uvec2", stage.inputs().get("mc_midTexCoord"));
        assertFalse(has(stage.body(), "\\bin\\s+uvec2\\s+a_Position"), "input qualifier should be removed");
        assertTrue(has(stage.body(), "uvec2\\s+a_Position\\s*;"));
    }

    @Test
    void outputsBecomeMeshArraysAndGlobals() {
        assertTrue(stage.outputDeclarations().contains("out vec2 texcoord[];"));
        assertTrue(stage.outputDeclarations().contains("flat out vec2 lmcoord[];"));
        assertTrue(stage.outputDeclarations().contains("out vec4 glcolor[];"));
        assertTrue(stage.outputDeclarations().stream().anyMatch(d ->
                d.matches("out VertexData \\{ vec3 viewPos ?; flat int blockId ?; \\} vs_out\\[\\];")), stage.outputDeclarations().toString());

        assertTrue(stage.outputCopies().contains("texcoord[_nv_vid] = _nv_vs_texcoord;"));
        assertTrue(stage.outputCopies().contains("vs_out[_nv_vid].viewPos = _nv_vs_vs_out.viewPos;"));
        assertTrue(stage.outputCopies().contains("vs_out[_nv_vid].blockId = _nv_vs_vs_out.blockId;"));

        String body = stage.body();
        assertFalse(has(body, "\\bout\\s"), "no output declarations may remain in the body");
        assertTrue(has(body, "_nv_vs_texcoord\\s*="));
        assertTrue(has(body, "_nv_vs_vs_out\\s*\\.\\s*viewPos\\s*="));
        assertTrue(has(body, "struct\\s+_nv_struct_VertexData"));
        assertFalse(has(body, "[^_.]\\btexcoord\\s*="), "references must be redirected");
    }

    @Test
    void pushConstantsBecomeGlobals() {
        assertFalse(stage.body().contains("iris_SodiumPushConstants"));
        assertTrue(has(stage.body(), "vec3\\s+iris_RegionOffset\\s*;"));
        assertTrue(stage.globals().contains("iris_RegionOffset"));
        assertTrue(stage.globals().contains("iris_RegionID"));
        // Other uniform blocks stay
        assertTrue(stage.body().contains("u_Globals"));
    }

    @Test
    void builtinsAndMainAreRedirected() {
        String body = stage.body();
        assertFalse(body.contains("gl_Position"));
        assertFalse(body.contains("gl_VertexID"));
        assertFalse(body.contains("invariant"));
        assertTrue(has(body, "vec4\\s+_nv_vs_Position\\s*;"));
        assertTrue(has(body, "_nv_vs_Position\\s*="));
        assertTrue(has(body, "int\\s+_nv_VertexID\\s*;"));
        assertTrue(has(body, "void\\s+_nv_pack_main\\s*\\("));
        assertFalse(has(body, "void\\s+main\\s*\\("));
    }

    @Test
    void globalInitializersAreMoved() {
        assertTrue(stage.globalInitializers().stream().anyMatch(s -> s.startsWith("iris_Entity = vec2")), stage.globalInitializers().toString());
        assertTrue(stage.globalInitializers().stream().anyMatch(s -> s.startsWith("iris_MidTex =")));
        assertTrue(has(stage.body(), "vec2\\s+iris_Entity\\s*;"), "declaration should lose its initializer");
        // const initializers must stay
        assertTrue(has(stage.body(), "const\\s+uint\\s+POSITION_BITS\\s*=\\s*20u"));
        assertFalse(stage.globalInitializers().stream().anyMatch(s -> s.startsWith("POSITION_BITS")));
    }

    @Test
    void anonymousOutputBlock() {
        String src = """
                #version 330 core
                out Data { vec2 uv; flat int id; };
                void main() { uv = vec2(1.0); id = 2; gl_Position = vec4(0.0); }
                """;
        PackVertexStage result = MeshShaderTransformer.transform(src);
        assertTrue(result.outputDeclarations().getFirst().endsWith("_nv_out_Data[];"), result.outputDeclarations().toString());
        assertTrue(result.outputCopies().contains("_nv_out_Data[_nv_vid].uv = _nv_vs_uv;"));
        assertTrue(has(result.body(), "_nv_vs_id\\s*=\\s*2"));
    }

    @Test
    void parametersAndLocalsShadowingOutputsAreLeftAlone() {
        // Complementary Reimagined: outputs named normal/lmCoord, and library functions with parameters and locals
        // of the same names. Renaming those references broke compilation ("undefined variable _nv_vs_normal").
        String src = """
                #version 330 core
                out vec3 normal;
                out vec2 lmCoord;
                struct Hit { vec3 normal; };
                vec3 useParam(vec3 normal) { return normal * 2.0; }
                float useLocal() { vec3 normal = vec3(1.0); return normal.x; }
                float useLoop() { float s = 0.0; for (int lmCoord = 0; lmCoord < 3; lmCoord++) { s += float(lmCoord); } return s; }
                float before() { float x = normal.x; vec3 normal = vec3(2.0); return x + normal.y; }
                float nested() { float r = 0.0; if (r < 1.0) { vec2 lmCoord = vec2(3.0); r = lmCoord.x; } return r + lmCoord.y; }
                float member(Hit h) { return h.normal.x; }
                void main() {
                    normal = useParam(vec3(1.0));
                    lmCoord = vec2(useLocal(), useLoop() + before() + nested());
                    gl_Position = vec4(normal, 1.0);
                }
                """;
        String body = MeshShaderTransformer.transform(src).body();
        System.out.println("---- shadowing ----\n" + body);
        assertTrue(has(body, "useParam\\s*\\(\\s*vec3\\s+normal\\s*\\)"), "parameter declaration must keep its name");
        assertTrue(has(body, "return\\s+normal\\s*\\*\\s*2\\.0"), "parameter reference must not be renamed");
        assertTrue(has(body, "vec3\\s+normal\\s*=\\s*vec3\\(1\\.0f?\\)\\s*;\\s*return\\s+normal\\.x"), "local reference must not be renamed");
        assertTrue(has(body, "for\\s*\\(\\s*int\\s+lmCoord\\s*=\\s*0\\s*;\\s*lmCoord\\s*<\\s*3"), "loop variable must not be renamed");
        assertTrue(has(body, "s\\s*\\+=\\s*float\\(lmCoord\\)"), "loop variable reference must not be renamed");
        assertTrue(has(body, "float\\s+x\\s*=\\s*_nv_vs_normal\\.x"), "reference before the local's declaration reads the output");
        assertTrue(has(body, "return\\s+x\\s*\\+\\s*normal\\.y"), "reference after the local's declaration reads the local");
        assertTrue(has(body, "r\\s*=\\s*lmCoord\\.x"), "inner block local must not be renamed");
        assertTrue(has(body, "return\\s+r\\s*\\+\\s*_nv_vs_lmCoord\\.y"), "outside the inner block the output is used");
        assertTrue(has(body, "h\\.normal\\.x"), "struct member access must not be renamed");
        assertTrue(has(body, "_nv_vs_normal\\s*=\\s*useParam"));
        assertTrue(has(body, "vec4\\(_nv_vs_normal,"));
    }

    @Test
    void arraysSizedByTheirInitializerKeepIt() {
        // Complementary's blocklightColors.glsl: "vec3[] specialTintColor = vec3[](...)" at global scope. Moving the
        // initializer left an unsized global array ("no size for unsized array" when linking).
        String src = """
                #version 330 core
                vec3[] specialTintColor = vec3[](vec3(1.0), vec3(2.0));
                float table[] = float[](1.0, 2.0, 3.0);
                vec2 sized[2] = vec2[](vec2(0.0), vec2(1.0));
                in uint mc_Entity;
                float entity = float(mc_Entity);
                void main() { gl_Position = vec4(specialTintColor[1], table[2] + sized[1].y + entity); }
                """;
        PackVertexStage result = MeshShaderTransformer.transform(src);
        System.out.println("---- unsized ----\n" + result.body() + "\n" + result.globalInitializers());
        assertTrue(has(result.body(), "specialTintColor\\s*=\\s*vec3\\s*\\[\\s*]\\s*\\("), "unsized type array keeps its initializer");
        assertTrue(has(result.body(), "table\\s*\\[\\s*]\\s*=\\s*float\\s*\\[\\s*]\\s*\\("), "unsized declarator array keeps its initializer");
        assertTrue(result.globalInitializers().stream().anyMatch(s -> s.startsWith("sized =")), "sized arrays are still moved");
        assertTrue(result.globalInitializers().stream().anyMatch(s -> s.startsWith("entity =")), "initializers reading inputs are moved");
        assertFalse(result.globalInitializers().stream().anyMatch(s -> s.startsWith("specialTintColor") || s.startsWith("table")));
    }

    @Test
    void rejectsShaderWithoutMain() {
        assertThrows(RuntimeException.class, () -> MeshShaderTransformer.transform("#version 330 core\nvoid notMain() {}\n"));
    }
}
