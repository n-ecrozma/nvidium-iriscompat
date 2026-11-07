package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

/**
 * The terrain vertex layout chosen for the current world renderer.
 * <p>
 * It is decided once, when Sodium creates its {@code RenderSectionManager} (which also happens when Iris loads or
 * unloads a shader pack), and stays fixed until the next renderer reload. Everything that reads or writes terrain
 * vertices (chunk repacking, the GPU geometry arena and the shaders) must use this rather than reading the config,
 * because the config can change before the reload happens.
 */
public final class TerrainVertexLayout {
    /** Size in bytes of the fixed part of Sodium's compact vertex, which Iris's extended format starts with. */
    public static final int SODIUM_BASE_STRIDE = 20;

    private static volatile TerrainVertexLayout current = new TerrainVertexLayout(false, false, NvidiumCompactChunkVertex.STRIDE, null);

    /** True when an Iris shader pack is active and Nvidium renders anyway. */
    public final boolean irisMode;
    /** True when vertices are stored in Sodium's layout (optionally with Iris's extra fields) instead of Nvidium's. */
    public final boolean sodiumLayout;
    /** Size of one vertex in bytes. */
    public final int stride;
    /** The vertex type to hand to Sodium's chunk builder, or null to leave Sodium's choice alone. */
    public final ChunkVertexType vertexType;

    private TerrainVertexLayout(boolean irisMode, boolean sodiumLayout, int stride, ChunkVertexType vertexType) {
        this.irisMode = irisMode;
        this.sodiumLayout = sodiumLayout;
        this.stride = stride;
        this.vertexType = vertexType;
    }

    public static TerrainVertexLayout current() {
        return current;
    }

    /**
     * Picks the layout for the renderer that is being created. Call only while Nvidium is enabled.
     */
    public static TerrainVertexLayout select() {
        TerrainVertexLayout layout;
        if (IrisCompat.isShaderPackInUse()) {
            // Iris overwrites ChunkMeshFormats.getCurrent() to return its extended format for the active pack:
            // Sodium's 20 bytes followed by optional blockId, normal, midUV and midBlock fields.
            // Shader packs need those fields, so store terrain exactly as Iris lays it out.
            ChunkVertexType type = ChunkMeshFormats.getCurrent();
            layout = new TerrainVertexLayout(true, true, type.getVertexFormat().getVertexSize(), type);
        } else if (Nvidium.config.use_sodium_vertex_format) {
            layout = new TerrainVertexLayout(false, true, ChunkMeshFormats.COMPACT.getVertexFormat().getVertexSize(), null);
        } else {
            layout = new TerrainVertexLayout(false, false, NvidiumCompactChunkVertex.STRIDE, NvidiumCompactChunkVertex.INSTANCE);
        }

        if (layout.sodiumLayout && (layout.stride < SODIUM_BASE_STRIDE || layout.stride % 4 != 0)) {
            throw new IllegalStateException("Unsupported terrain vertex stride " + layout.stride
                    + " (expected at least " + SODIUM_BASE_STRIDE + " bytes and a multiple of 4)");
        }

        current = layout;
        Nvidium.LOGGER.info("Terrain vertex layout: {} format, {} bytes per vertex{}",
                layout.sodiumLayout ? "Sodium" : "Nvidium", layout.stride, layout.irisMode ? " (Iris shader pack active)" : "");
        return layout;
    }

    /** Number of 32-bit words after Sodium's 20-byte base (Iris's extra per-vertex fields). */
    public int extraWords() {
        return sodiumLayout ? (stride - SODIUM_BASE_STRIDE) / 4 : 0;
    }
}
