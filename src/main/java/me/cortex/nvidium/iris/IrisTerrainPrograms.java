package me.cortex.nvidium.iris;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import me.cortex.nvidium.Nvidium;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import org.lwjgl.opengl.GL31C;

import org.lwjgl.opengl.GL20C;

import java.util.EnumMap;
import java.util.List;
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
        OPAQUE("terrain", false),
        /** Translucent terrain such as water, from {@code SODIUM_TERRAIN_TRANSLUCENT} (gbuffers_water). */
        TRANSLUCENT("water", false),
        /** Solid and cutout terrain in the shadow map, from {@code SHADOW_SODIUM_TERRAIN_CUTOUT}. */
        SHADOW_OPAQUE("shadow terrain", true),
        /** Translucent terrain in the shadow map, from {@code SHADOW_SODIUM_TERRAIN_TRANSLUCENT} (shadow_water). */
        SHADOW_TRANSLUCENT("shadow water", true);

        public final String label;
        /**
         * Shadow programs are linked when the pack loads but only wrapped in an ExtendedShader on first use: creating
         * one creates Iris's shadow render targets, which would turn on a shadow pass for packs that have none.
         */
        final boolean lazy;

        Group(String label, boolean lazy) {
            this.label = label;
            this.lazy = lazy;
        }
    }

    /** The Nvidium passes that draw terrain; each needs its own program because the task shader differs. */
    public enum Pass {
        PRIMARY(Group.OPAQUE, "terrain/task.glsl", MeshShaderAssembler.Kind.OPAQUE, List.of()),
        TEMPORAL(Group.OPAQUE, "terrain/temporal_task.glsl", MeshShaderAssembler.Kind.OPAQUE, List.of()),
        TRANSLUCENT(Group.TRANSLUCENT, "terrain/translucent/task.glsl", MeshShaderAssembler.Kind.TRANSLUCENT, List.of()),
        // Faces pointing away from the player still cast shadows
        SHADOW(Group.SHADOW_OPAQUE, "terrain/task.glsl", MeshShaderAssembler.Kind.OPAQUE, List.of("NO_FACE_CULLING")),
        SHADOW_TRANSLUCENT(Group.SHADOW_TRANSLUCENT, "terrain/translucent/task.glsl", MeshShaderAssembler.Kind.TRANSLUCENT, List.of());

        public final Group group;
        final String taskShader;
        final MeshShaderAssembler.Kind kind;
        /** Extra defines for the task shader. */
        final List<String> taskDefines;

        Pass(Group group, String taskShader, MeshShaderAssembler.Kind kind, List<String> taskDefines) {
            this.group = group;
            this.taskShader = taskShader;
            this.kind = kind;
            this.taskDefines = taskDefines;
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
        /** Linked programs of lazy groups that haven't been wrapped in an ExtendedShader yet. */
        private final Map<Pass, ShaderSupplier> pending = new EnumMap<>(Pass.class);
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

        /** The program for a pass, creating it now if it is a lazy one; null if it isn't available. */
        public ExtendedShader get(Pass pass) {
            ExtendedShader shader = programs.get(pass);
            if (shader != null) {
                return shader;
            }
            ShaderSupplier supplier = pending.remove(pass);
            if (supplier == null) {
                return null;
            }
            try {
                shader = finish(supplier, pass.group);
                programs.put(pass, shader);
                return shader;
            } catch (Throwable t) {
                failures.put(pass.group, t.getMessage() != null ? t.getMessage() : t.toString());
                Nvidium.LOGGER.error("Nvidium: could not set up this shader pack's {} program, Nvidium won't draw it", pass.group.label, t);
                // Drop the rest of the group so it is used all or nothing
                for (Pass other : Pass.values()) {
                    if (other.group == pass.group) {
                        ShaderSupplier left = pending.remove(other);
                        if (left != null) {
                            deleteUnfinished(left);
                        }
                        ExtendedShader built = programs.remove(other);
                        if (built != null) {
                            built.close();
                        }
                    }
                }
                return null;
            }
        }

        /** Whether every pass of the group was built (lazy passes count once linked). */
        public boolean has(Group group) {
            for (Pass pass : Pass.values()) {
                if (pass.group == group && !programs.containsKey(pass) && !pending.containsKey(pass)) {
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
            pending.values().forEach(IrisTerrainPrograms::deleteUnfinished);
            pending.clear();
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
        var linked = new EnumMap<Pass, ShaderSupplier>(Pass.class);
        try {
            for (Pass pass : Pass.values()) {
                if (pass.group != group) {
                    continue;
                }
                ShaderSupplier supplier;
                BUILDING.set(pass);
                try {
                    // Compiles and links the program, so shader errors surface at pack load even for lazy groups
                    supplier = createAgain.apply(pass);
                } finally {
                    BUILDING.remove();
                }
                if (group.lazy) {
                    linked.put(pass, supplier);
                } else {
                    built.put(pass, finish(supplier, group));
                }
            }
            programs.programs.putAll(built);
            programs.pending.putAll(linked);
            programs.failures.remove(group);
            Nvidium.LOGGER.info("Nvidium: built shader pack {} programs ({} bytes per vertex)", group.label, vertexStride);
        } catch (Throwable t) {
            built.values().forEach(GlProgram::close);
            linked.values().forEach(IrisTerrainPrograms::deleteUnfinished);
            String reason = t.getMessage() != null ? t.getMessage() : t.toString();
            programs.failures.put(group, reason);
            Nvidium.LOGGER.error("Nvidium: could not run this shader pack's {} shaders, Nvidium's own shaders will be used for it",
                    group.label, t);
        }
    }

    /** Wraps a linked program in Iris's ExtendedShader and moves Nvidium's SceneData block off u_Globals' slot. */
    private static ExtendedShader finish(ShaderSupplier supplier, Group group) {
        if (!(supplier.shader().get() instanceof ExtendedShader shader)) {
            throw new IllegalStateException("Iris did not create an ExtendedShader for the " + group.label + " program");
        }
        // Creating the ExtendedShader applied Iris's fixed bindings, which put u_Globals on slot 0
        int sceneData = GL31C.glGetUniformBlockIndex(shader.getProgramId(), "SceneData");
        if (sceneData == GL31C.GL_INVALID_INDEX) {
            shader.close();
            throw new IllegalStateException("Nvidium's task shader has no SceneData block");
        }
        GL31C.glUniformBlockBinding(shader.getProgramId(), sceneData, SCENE_DATA_BINDING);
        return shader;
    }

    /** Frees a linked program that was never wrapped in an ExtendedShader. */
    private static void deleteUnfinished(ShaderSupplier supplier) {
        // getFinally() detaches and deletes the shader stages; then the program itself can go
        GL20C.glDeleteProgram(supplier.id().getFinally());
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
