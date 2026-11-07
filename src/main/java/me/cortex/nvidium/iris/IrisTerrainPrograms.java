package me.cortex.nvidium.iris;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import me.cortex.nvidium.Nvidium;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;

import org.lwjgl.opengl.GL31C;

import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Shader pack terrain programs built for Nvidium, one set per Iris pipeline.
 * <p>
 * When Iris creates the program for Sodium's terrain ({@code SODIUM_TERRAIN_CUTOUT}), the mixin in
 * {@code MixinIrisRenderingPipeline} asks Iris to create it again for each Nvidium pass, with
 * {@link #currentRequest()} set so that {@code MixinShaderCreator} links Nvidium's task and mesh shaders instead of
 * the vertex shader. The result is a regular Iris {@link ExtendedShader}, which sets up the pack's uniforms,
 * samplers, framebuffer and blending exactly as it does for Sodium.
 * <p>
 * Only touch this class when Iris is installed. Render thread only.
 */
public final class IrisTerrainPrograms {
    /** The Nvidium passes that draw terrain; each needs its own program because the task shader differs. */
    public enum Pass {
        PRIMARY("terrain/task.glsl"),
        TEMPORAL("terrain/temporal_task.glsl");

        final String taskShader;

        Pass(String taskShader) {
            this.taskShader = taskShader;
        }
    }

    /**
     * Uniform buffer binding for Nvidium's {@code SceneData} block in these programs. Its own shaders use 0, but
     * Iris binds the pack's {@code u_Globals} there (see IrisBindings), and the task shader's block shares the program.
     */
    public static final int SCENE_DATA_BINDING = 8;

    /**
     * Nvidium settings baked into the task shader: fog changes the layout of {@code SceneData}, and statistics add
     * writes to a buffer that only exists when they are enabled.
     */
    public record TaskShaderSettings(boolean renderFog, int statisticsLevel) {
        public static TaskShaderSettings current() {
            return new TaskShaderSettings(Nvidium.config.render_fog, Nvidium.config.statistics_level.ordinal());
        }
    }

    /** Built programs, or why they couldn't be built. */
    public static final class Programs {
        private final Map<Pass, ExtendedShader> programs;
        private final int vertexStride;
        private final TaskShaderSettings settings;
        private final String failure;

        private Programs(Map<Pass, ExtendedShader> programs, int vertexStride, TaskShaderSettings settings, String failure) {
            this.programs = programs;
            this.vertexStride = vertexStride;
            this.settings = settings;
            this.failure = failure;
        }

        public TaskShaderSettings settings() {
            return settings;
        }

        public ExtendedShader get(Pass pass) {
            return programs.get(pass);
        }

        public int vertexStride() {
            return vertexStride;
        }

        public String failure() {
            return failure;
        }

        public boolean isUsable() {
            return failure == null;
        }

        private void close() {
            programs.values().forEach(GlProgram::close);
            programs.clear();
        }
    }

    private static final ThreadLocal<Pass> BUILDING = new ThreadLocal<>();
    private static final Map<IrisRenderingPipeline, Programs> BY_PIPELINE = new WeakHashMap<>();

    private IrisTerrainPrograms() {}

    /** Whether Nvidium wants pack programs at all. Checked when Iris loads a pack. */
    public static boolean shouldBuild() {
        return Nvidium.IS_COMPATIBLE && Nvidium.config.iris_support;
    }

    /** The pass being built, while Iris is creating a program on Nvidium's behalf; otherwise null. */
    public static Pass currentRequest() {
        return BUILDING.get();
    }

    /**
     * Builds Nvidium's programs for a pipeline.
     *
     * @param createAgain runs Iris's program creation again for the given pass
     * @param vertexStride vertex size the programs were built for
     */
    public static void build(IrisRenderingPipeline pipeline, int vertexStride, Function<Pass, ShaderSupplier> createAgain) {
        release(pipeline);
        var settings = TaskShaderSettings.current();
        var built = new EnumMap<Pass, ExtendedShader>(Pass.class);
        try {
            for (Pass pass : Pass.values()) {
                ShaderSupplier supplier;
                BUILDING.set(pass);
                try {
                    supplier = createAgain.apply(pass);
                } finally {
                    BUILDING.remove();
                }
                // Create the ExtendedShader now so that problems surface at pack load, not mid-frame
                if (!(supplier.shader().get() instanceof ExtendedShader shader)) {
                    throw new IllegalStateException("Iris did not create an ExtendedShader for the terrain program");
                }
                built.put(pass, shader);
                // Creating the ExtendedShader applied Iris's fixed bindings; move Nvidium's block off u_Globals' slot
                int sceneData = GL31C.glGetUniformBlockIndex(shader.getProgramId(), "SceneData");
                if (sceneData == GL31C.GL_INVALID_INDEX) {
                    throw new IllegalStateException("Nvidium's task shader has no SceneData block");
                }
                GL31C.glUniformBlockBinding(shader.getProgramId(), sceneData, SCENE_DATA_BINDING);
            }
            BY_PIPELINE.put(pipeline, new Programs(built, vertexStride, settings, null));
            Nvidium.LOGGER.info("Nvidium: built shader pack terrain programs ({} bytes per vertex)", vertexStride);
        } catch (Throwable t) {
            built.values().forEach(GlProgram::close);
            String reason = t.getMessage() != null ? t.getMessage() : t.toString();
            BY_PIPELINE.put(pipeline, new Programs(new EnumMap<>(Pass.class), vertexStride, settings, reason));
            Nvidium.LOGGER.error("Nvidium: could not run this shader pack's terrain shaders, terrain will use Nvidium's own shaders", t);
        }
    }

    public static void release(IrisRenderingPipeline pipeline) {
        Programs programs = BY_PIPELINE.remove(pipeline);
        if (programs != null) {
            programs.close();
        }
    }

    /** Programs for the active Iris pipeline, or null if there are none (no pack, or not built). */
    public static Programs current() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof IrisRenderingPipeline irisPipeline) {
            return BY_PIPELINE.get(irisPipeline);
        }
        return null;
    }
}
