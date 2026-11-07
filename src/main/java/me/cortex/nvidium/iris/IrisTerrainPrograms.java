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
 * When Iris creates the pack's programs for Sodium's terrain, the mixin in {@code MixinIrisRenderingPipeline} asks
 * Iris to create each one again for the matching Nvidium passes, with {@link #currentRequest()} set so that
 * {@code MixinShaderCreator} links Nvidium's task and mesh shaders instead of the vertex shader. The result is a
 * regular Iris {@link ExtendedShader}, which sets up the pack's uniforms, samplers, framebuffer and blending exactly
 * as it does for Sodium.
 * <p>
 * Only touch this class when Iris is installed. Render thread only.
 */
public final class IrisTerrainPrograms {
    /** Passes that are built from the same Iris program and are used together (or not at all). */
    public enum Group {
        /** Solid and cutout terrain, from {@code SODIUM_TERRAIN_CUTOUT} (Nvidium draws both in one pass). */
        OPAQUE("terrain"),
        /** Translucent terrain such as water, from {@code SODIUM_TERRAIN_TRANSLUCENT} (gbuffers_water). */
        TRANSLUCENT("water");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** The Nvidium passes that draw terrain; each needs its own program because the task shader differs. */
    public enum Pass {
        PRIMARY(Group.OPAQUE, "terrain/task.glsl", MeshShaderAssembler.Kind.OPAQUE),
        TEMPORAL(Group.OPAQUE, "terrain/temporal_task.glsl", MeshShaderAssembler.Kind.OPAQUE),
        TRANSLUCENT(Group.TRANSLUCENT, "terrain/translucent/task.glsl", MeshShaderAssembler.Kind.TRANSLUCENT);

        public final Group group;
        final String taskShader;
        final MeshShaderAssembler.Kind kind;

        Pass(Group group, String taskShader, MeshShaderAssembler.Kind kind) {
            this.group = group;
            this.taskShader = taskShader;
            this.kind = kind;
        }
    }

    /**
     * Uniform buffer binding for Nvidium's {@code SceneData} block in these programs. Its own shaders use 0, but
     * Iris binds the pack's {@code u_Globals} there (see IrisBindings), and the task shader's block shares the program.
     */
    public static final int SCENE_DATA_BINDING = 8;

    /**
     * Nvidium settings baked into the programs: fog changes the layout of {@code SceneData}, statistics add writes
     * to the task shaders, and the translucency sorting level changes the translucent task and mesh shaders.
     */
    public record TaskShaderSettings(boolean renderFog, int statisticsLevel, int translucencySorting) {
        public static TaskShaderSettings current() {
            return new TaskShaderSettings(Nvidium.config.render_fog, Nvidium.config.statistics_level.ordinal(),
                    Nvidium.config.translucency_sorting_level.ordinal());
        }
    }

    /** The programs built for one pipeline, and why any group couldn't be built. */
    public static final class Programs {
        private final Map<Pass, ExtendedShader> programs = new EnumMap<>(Pass.class);
        private final Map<Group, String> failures = new EnumMap<>(Group.class);
        private final int vertexStride;
        private final TaskShaderSettings settings;

        private Programs(int vertexStride, TaskShaderSettings settings) {
            this.vertexStride = vertexStride;
            this.settings = settings;
        }

        public TaskShaderSettings settings() {
            return settings;
        }

        public int vertexStride() {
            return vertexStride;
        }

        public ExtendedShader get(Pass pass) {
            return programs.get(pass);
        }

        /** Whether every pass of the group was built. */
        public boolean has(Group group) {
            for (Pass pass : Pass.values()) {
                if (pass.group == group && !programs.containsKey(pass)) {
                    return false;
                }
            }
            return true;
        }

        /** Why the group couldn't be built, or null if it was built or never attempted. */
        public String failure(Group group) {
            return failures.get(group);
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
     * Builds the passes of one group for a pipeline. Each group is built from its own Iris program, at the moment
     * Iris creates that program; a failed group doesn't affect the others.
     *
     * @param vertexStride vertex size the programs are built for
     * @param createAgain  runs Iris's program creation again for the given pass
     */
    public static void build(IrisRenderingPipeline pipeline, Group group, int vertexStride, Function<Pass, ShaderSupplier> createAgain) {
        var settings = TaskShaderSettings.current();
        Programs programs = BY_PIPELINE.get(pipeline);
        if (programs == null || programs.vertexStride != vertexStride || !programs.settings.equals(settings)) {
            release(pipeline);
            programs = new Programs(vertexStride, settings);
            BY_PIPELINE.put(pipeline, programs);
        }

        var built = new EnumMap<Pass, ExtendedShader>(Pass.class);
        try {
            for (Pass pass : Pass.values()) {
                if (pass.group != group) {
                    continue;
                }
                ShaderSupplier supplier;
                BUILDING.set(pass);
                try {
                    supplier = createAgain.apply(pass);
                } finally {
                    BUILDING.remove();
                }
                // Create the ExtendedShader now so that problems surface at pack load, not mid-frame
                if (!(supplier.shader().get() instanceof ExtendedShader shader)) {
                    throw new IllegalStateException("Iris did not create an ExtendedShader for the " + group.label + " program");
                }
                built.put(pass, shader);
                // Creating the ExtendedShader applied Iris's fixed bindings; move Nvidium's block off u_Globals' slot
                int sceneData = GL31C.glGetUniformBlockIndex(shader.getProgramId(), "SceneData");
                if (sceneData == GL31C.GL_INVALID_INDEX) {
                    throw new IllegalStateException("Nvidium's task shader has no SceneData block");
                }
                GL31C.glUniformBlockBinding(shader.getProgramId(), sceneData, SCENE_DATA_BINDING);
            }
            programs.programs.putAll(built);
            programs.failures.remove(group);
            Nvidium.LOGGER.info("Nvidium: built shader pack {} programs ({} bytes per vertex)", group.label, vertexStride);
        } catch (Throwable t) {
            built.values().forEach(GlProgram::close);
            String reason = t.getMessage() != null ? t.getMessage() : t.toString();
            programs.failures.put(group, reason);
            Nvidium.LOGGER.error("Nvidium: could not run this shader pack's {} shaders, Nvidium's own shaders will be used for it",
                    group.label, t);
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
