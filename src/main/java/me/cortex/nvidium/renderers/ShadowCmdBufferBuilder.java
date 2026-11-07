package me.cortex.nvidium.renderers;

import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.minecraft.resources.Identifier;

import static me.cortex.nvidium.gl.shader.ShaderType.COMPUTE;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

/**
 * Builds the opaque and translucent draw lists for Iris's shadow pass: every non-empty section of each region in
 * the shadow frustum, without occlusion culling (see occlusion/command_buffer/shadow_command_buffer_builder.comp).
 */
public class ShadowCmdBufferBuilder extends Phase {
    private final Shader shader = Shader.make()
            .addSource(COMPUTE, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "occlusion/command_buffer/shadow_command_buffer_builder.comp")))
            .compile();

    public void dispatch(int regionCount) {
        shader.bind();
        timing.marker();
        glDispatchCompute(regionCount, 1, 1);
        timing.marker();
        timing.tick();
    }

    @Override
    public void delete() {
        super.delete();
        shader.delete();
    }
}
