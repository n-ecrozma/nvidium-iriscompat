package me.cortex.nvidium.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.sodiumCompat.TerrainVertexLayout;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static org.lwjgl.opengl.NVMeshShader.glMultiDrawMeshTasksIndirectNV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.glBufferAddressRangeNV;

/**
 * Draws Nvidium's terrain with the shader pack's terrain program (see {@link IrisTerrainPrograms}).
 * <p>
 * Provides what the pack's Sodium-patched shaders expect from Sodium: the {@code u_Globals} uniform block and the
 * block, light and section-time textures on Iris's fixed units. Iris's {@link ExtendedShader} handles everything
 * else (pack uniforms and samplers, framebuffer, blending).
 * <p>
 * Uniform buffers are bound by GPU address because Nvidium keeps {@code GL_UNIFORM_BUFFER_UNIFIED_NV} enabled
 * while it renders. Only use when Iris is installed.
 */
public final class IrisTerrainPass {
    // Iris's fixed bindings (IrisBindings)
    private static final int GLOBALS_BINDING = 0;
    private static final int ALBEDO_UNIT = 0;
    private static final int LIGHTMAP_UNIT = 2;
    private static final int SECTION_TIME_UNIT = 3;

    /** std140 size of Sodium's u_Globals block (include/globals.glsl): 2 mat4, vec4, 4 vec2, float, bool. */
    private static final int GLOBALS_SIZE = 192;
    /** std140 size of _NvIrisScene (terrain/iris/mesh.glsl): vec4, pointer. */
    private static final int SCENE_SIZE = 32;

    private final IDeviceMappedBuffer globals;
    private final IDeviceMappedBuffer scene;
    private final int sectionTimeBuffer;
    private final int sectionTimeTexture;

    private IrisTerrainPrograms.Programs warnedAbout;

    public IrisTerrainPass(RenderDevice device) {
        this.globals = device.createDeviceOnlyMappedBuffer(GLOBALS_SIZE);
        this.scene = device.createDeviceOnlyMappedBuffer(SCENE_SIZE);

        // Iris's chunk fade reads u_SectionTimeInfo at index 0 (Nvidium forces the section index to 0). A negative
        // build time means "no fade", so terrain is always fully visible.
        this.sectionTimeBuffer = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, this.sectionTimeBuffer);
        GL15C.glBufferData(GL31C.GL_TEXTURE_BUFFER, new int[]{-1, -1, -1, -1}, GL15C.GL_STATIC_DRAW);
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, 0);
        this.sectionTimeTexture = GL15C.glGenTextures();
        // Go through GlStateManager for the active unit so Minecraft's cached state stays correct
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + SECTION_TIME_UNIT);
        GL15C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, this.sectionTimeTexture);
        GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL31C.GL_R32I, this.sectionTimeBuffer);
        GL15C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
    }

    /**
     * The pack programs to draw this frame with, or null to use Nvidium's own terrain shaders for everything.
     * Check {@link IrisTerrainPrograms.Programs#has} for each group before using it.
     */
    public IrisTerrainPrograms.Programs programsForFrame() {
        var programs = IrisTerrainPrograms.current();
        if (programs == null) {
            return null;
        }
        String problem = mismatch(programs);
        if (problem != null) {
            if (warnedAbout != programs) {
                warnedAbout = programs;
                Nvidium.LOGGER.warn("Nvidium: not using the shader pack's terrain programs: {}", problem);
            }
            return null;
        }
        return programs;
    }

    private static String mismatch(IrisTerrainPrograms.Programs programs) {
        if (programs.vertexStride() != TerrainVertexLayout.current().stride) {
            return "they were built for " + programs.vertexStride() + "-byte vertices but terrain uses "
                    + TerrainVertexLayout.current().stride + " (reload the world or the shader pack)";
        }
        if (!programs.settings().equals(IrisTerrainPrograms.TaskShaderSettings.current())) {
            return "Nvidium's fog, statistics or translucency sorting setting changed since the shader pack was loaded (reload the shader pack)";
        }
        return null;
    }

    /** Lines for the F3 screen saying which shaders draw terrain and water. */
    public void addDebugInfo(List<String> info) {
        var programs = IrisTerrainPrograms.current();
        if (programs == null) {
            info.add("Shader pack terrain: not built (enable Iris support and reload the shader pack)");
            return;
        }
        String mismatch = mismatch(programs);
        for (var group : IrisTerrainPrograms.Group.values()) {
            String status;
            if (mismatch != null) {
                status = "Nvidium shaders (settings changed, reload the shader pack)";
            } else if (programs.has(group)) {
                status = "pack shaders (" + programs.vertexStride() + " B/vertex)";
            } else {
                String reason = programs.failure(group);
                if (reason == null) {
                    reason = "pack has no program for it";
                }
                int newline = reason.indexOf('\n');
                status = "Nvidium shaders (" + (newline >= 0 ? reason.substring(0, newline) : reason) + ")";
            }
            info.add("Shader pack " + group.label + ": " + status);
        }
    }

    /**
     * Writes this frame's uniforms. Call before the upload stream is committed.
     *
     * @param cameraOffset   camera position within its chunk, negated (Nvidium's subchunk offset)
     * @param terrainAddress GPU address of the terrain vertex buffer
     */
    public void upload(UploadingBufferStream stream, ChunkRenderMatrices matrices, FogParameters fog,
                       Vector3fc cameraOffset, long terrainAddress,
                       float texelWidth, float texelHeight, float texCoordShrinkX, float texCoordShrinkY, boolean rgss) {
        // Same contents and order as Sodium's UniformBufferManager.GlobalUniforms
        long ptr = stream.upload(this.globals, 0, GLOBALS_SIZE);
        new Matrix4f(matrices.projection()).getToAddress(ptr);
        new Matrix4f(matrices.modelView()).getToAddress(ptr + 64);
        MemoryUtil.memPutFloat(ptr + 128, fog.red());
        MemoryUtil.memPutFloat(ptr + 132, fog.green());
        MemoryUtil.memPutFloat(ptr + 136, fog.blue());
        MemoryUtil.memPutFloat(ptr + 140, fog.alpha());
        MemoryUtil.memPutFloat(ptr + 144, fog.environmentalStart());
        MemoryUtil.memPutFloat(ptr + 148, fog.environmentalEnd());
        MemoryUtil.memPutFloat(ptr + 152, fog.renderStart());
        MemoryUtil.memPutFloat(ptr + 156, fog.renderEnd());
        MemoryUtil.memPutFloat(ptr + 160, texelWidth);
        MemoryUtil.memPutFloat(ptr + 164, texelHeight);
        MemoryUtil.memPutFloat(ptr + 168, texCoordShrinkX);
        MemoryUtil.memPutFloat(ptr + 172, texCoordShrinkY);
        MemoryUtil.memPutFloat(ptr + 176, 0.0f); // fade period: unused, fading is disabled through u_SectionTimeInfo
        MemoryUtil.memPutInt(ptr + 180, rgss ? 1 : 0);
        MemoryUtil.memSet(ptr + 184, 0, GLOBALS_SIZE - 184);

        ptr = stream.upload(this.scene, 0, SCENE_SIZE);
        MemoryUtil.memPutFloat(ptr, cameraOffset.x());
        MemoryUtil.memPutFloat(ptr + 4, cameraOffset.y());
        MemoryUtil.memPutFloat(ptr + 8, cameraOffset.z());
        MemoryUtil.memPutFloat(ptr + 12, 0.0f);
        MemoryUtil.memPutLong(ptr + 16, terrainAddress);
        MemoryUtil.memPutLong(ptr + 24, 0);
    }

    /**
     * Draws translucent terrain with the pack's water program. Sets vanilla's translucent blending first, the
     * pipeline state Iris expects for this program; the pack's own blend overrides are applied on top by Iris.
     */
    public void drawTranslucent(IrisTerrainPrograms.Programs programs, TerrainRenderPass pass, int regionCount,
                                long commandAddress, long sceneDataAddress, long sceneDataSize) {
        GlStateManager._enableBlend(0);
        GlStateManager._blendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA, GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
        draw(programs, IrisTerrainPrograms.Pass.TRANSLUCENT, pass, regionCount, commandAddress, sceneDataAddress, sceneDataSize);
    }

    /** Draws terrain for one of Nvidium's passes; see {@link #draw(ExtendedShader, TerrainRenderPass, int, long, long, long)}. */
    public void draw(IrisTerrainPrograms.Programs programs, IrisTerrainPrograms.Pass nvidiumPass, TerrainRenderPass pass,
                     int regionCount, long commandAddress, long sceneDataAddress, long sceneDataSize) {
        ExtendedShader program = programs.get(nvidiumPass);
        if (program == null) {
            return; // a lazy program that failed to set up; the failure is logged and shown in F3
        }
        draw(program, pass, regionCount, commandAddress, sceneDataAddress, sceneDataSize);
    }

    /**
     * Draws terrain with one of the pack's programs. Afterwards uniform buffer slot 0 holds u_Globals; the caller
     * must point it back at Nvidium's scene data before running its own shaders.
     *
     * @param sceneDataAddress GPU address of Nvidium's SceneData, read by the task shader
     */
    public void draw(ExtendedShader program, TerrainRenderPass pass, int regionCount, long commandAddress,
                     long sceneDataAddress, long sceneDataSize) {
        // Binds the program, the pack's uniforms and samplers, its G-buffer framebuffer and blend state
        program.iris$setupState(List.of());

        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, GLOBALS_BINDING, this.globals.getDeviceAddress(), GLOBALS_SIZE);
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, MeshShaderAssembler.SCENE_BINDING, this.scene.getDeviceAddress(), SCENE_SIZE);
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, IrisTerrainPrograms.SCENE_DATA_BINDING, sceneDataAddress, sceneDataSize);

        // Iris draws shader pack terrain with nearest filtering and mipmaps (see Iris's MixinDefaultChunkRenderer)
        setTexture(pass.getAtlas(), ALBEDO_UNIT, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true));
        setTexture(Minecraft.getInstance().gameRenderer.lightmap(), LIGHTMAP_UNIT,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + SECTION_TIME_UNIT);
        GL15C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, this.sectionTimeTexture);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0);

        glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddress, regionCount * 8L);
        glMultiDrawMeshTasksIndirectNV(0, regionCount, 0);

        GL33C.glBindSampler(ALBEDO_UNIT, 0);
        GL33C.glBindSampler(LIGHTMAP_UNIT, 0);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + SECTION_TIME_UNIT);
        GL15C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0);

        program.iris$clearState();
    }

    private static void setTexture(GpuTextureView view, int unit, GpuSampler sampler) {
        GlTexture texture = (GlTexture) view.texture();
        GlStateManager._activeTexture(GL32C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture.glId());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_BASE_LEVEL, view.baseMipLevel());
        GlStateManager._texParameter(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAX_LEVEL, view.baseMipLevel() + view.mipLevels() - 1);
        GL33C.glBindSampler(unit, ((GlSampler) sampler).getId());
    }

    public void delete() {
        this.globals.delete();
        this.scene.delete();
        GL15C.glDeleteTextures(this.sectionTimeTexture);
        GL15C.glDeleteBuffers(this.sectionTimeBuffer);
    }
}
