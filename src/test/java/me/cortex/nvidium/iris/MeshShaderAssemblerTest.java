package me.cortex.nvidium.iris;

import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MeshShaderAssemblerTest {
    private static PackVertexStage stage;
    private static String mesh;

    @BeforeAll
    static void assemble() throws IOException {
        String source;
        try (InputStream in = MeshShaderAssemblerTest.class.getResourceAsStream("/iris/patched_terrain.vsh")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        stage = MeshShaderTransformer.transform(source);
        // Sodium's 5 words, then blockId and midUV (as Iris lays them out when a pack uses both)
        var layout = new MeshShaderAssembler.VertexLayout(7, Map.of("mc_Entity", 5, "mc_midTexCoord", 6));
        mesh = MeshShaderAssembler.assemble(stage, layout, 32);
        System.out.println(mesh);
    }

    @Test
    void allMarkersReplaced() {
        assertFalse(mesh.matches("(?s).*\\$\\{[A-Z_]+}.*"), "unreplaced template marker");
        assertTrue(mesh.startsWith("#version 460 core"));
        assertTrue(mesh.contains("layout(local_size_x = 32) in;"));
        assertTrue(mesh.contains("max_vertices = 64, max_primitives = 32"));
    }

    @Test
    void extensionsComeBeforeAnyCode() {
        int packExtension = mesh.indexOf("#extension GL_ARB_explicit_attrib_location");
        int firstCode = mesh.indexOf("layout(local_size_x");
        assertTrue(packExtension > 0 && packExtension < firstCode);
        assertFalse(mesh.contains("#extension GL_NV_gpu_shader5"), "gpu_shader5 makes pack code ambiguous");
        assertEquals(1, mesh.split("#version", -1).length - 1, "exactly one #version");
    }

    @Test
    void loaderMatchesLayout() {
        assertTrue(mesh.contains("a_Position = uvec2(uvec2(_nv_word(0u), _nv_word(1u)));"));
        assertTrue(mesh.contains("a_Color = vec4(unpackUnorm4x8(_nv_word(2u)));"));
        assertTrue(mesh.contains("mc_Entity = uint(_nv_word(5u));"));
        assertTrue(mesh.contains("mc_midTexCoord = uvec2(uvec2(_nv_word(6u) & 0xFFFFu, _nv_word(6u) >> 16u));"));
        assertTrue(mesh.contains(", 0u));"), "section index must be forced to zero");
        assertTrue(mesh.contains("_nv_vertexBase = vertexIndex * 7u;"));
    }

    @Test
    void sectionSetupAndOutputs() {
        assertTrue(mesh.contains("iris_RegionOffset = _nv_task.origin + _nv_subchunkOffset.xyz;"));
        assertTrue(mesh.contains("iris_RegionID = 0u;"));
        assertTrue(mesh.contains("_nv_VertexID = int(vertexIndex);"));
        assertTrue(mesh.contains("flat out vec2 lmcoord[];"));
        assertTrue(mesh.contains("texcoord[_nv_vid] = _nv_vs_texcoord;"));
        assertTrue(mesh.contains("iris_Entity = vec2("));
        // Initializers run after the inputs are loaded
        assertTrue(mesh.indexOf("_nv_load_vertex();\n") < mesh.indexOf("_nv_init_globals();\n    _nv_pack_main();"));
    }

    @Test
    void missingOptionalFieldGetsZero() {
        var layout = new MeshShaderAssembler.VertexLayout(5, Map.of());
        String noExtras = MeshShaderAssembler.assemble(stage, layout, 16);
        assertTrue(noExtras.contains("mc_Entity = uint(0);"));
        assertTrue(noExtras.contains("max_vertices = 32, max_primitives = 16"));
    }

    @Test
    void assembledShaderParses() {
        // glsl-transformer's grammar can't parse two NVIDIA-specific constructs that the driver accepts (Nvidium's
        // own shaders use both): pointer declarations, and "taskNV" combined with "in". Swap them for plain GLSL.
        String withoutPointer = mesh.replace("readonly restrict uint *_nv_terrainData;", "uint _nv_terrainData[64];");
        assertNotEquals(mesh, withoutPointer, "pointer declaration not found");
        String parseable = withoutPointer.replace("taskNV in Task", "in Task");
        assertNotEquals(withoutPointer, parseable, "task block not found");
        var transformer = new SingleASTTransformer<>(SingleASTTransformer.IDENTITY_TRANSFORMATION);
        transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        transformer.getLexer().version = Version.GLSL46;
        assertDoesNotThrow(() -> transformer.transform(parseable));
    }

    @Test
    void rejectsBadWorkgroupSize() {
        var layout = new MeshShaderAssembler.VertexLayout(5, Map.of());
        assertThrows(IllegalArgumentException.class, () -> MeshShaderAssembler.assemble(stage, layout, 33));
        assertThrows(IllegalArgumentException.class, () -> MeshShaderAssembler.assemble(stage, layout, 64));
    }
}
