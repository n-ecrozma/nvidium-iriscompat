package me.cortex.nvidium.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import me.cortex.nvidium.iris.IrisTerrainPrograms;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline {
    /**
     * After Iris creates the shader pack's program for Sodium's terrain, build Nvidium's mesh shader versions of it
     * with exactly the same inputs (framebuffers, uniforms, alpha test, ...).
     * <p>
     * Nvidium draws solid and cutout terrain in one pass, so it uses the cutout program, whose alpha test is
     * harmless for solid blocks.
     */
    @WrapOperation(
            method = "createShader(Ljava/lang/String;Lnet/irisshaders/iris/pipeline/programs/ShaderKey;Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;Lnet/irisshaders/iris/shaderpack/loading/ProgramId;Lnet/irisshaders/iris/gl/blending/AlphaTest;Lcom/mojang/renderpearl/api/vertex/VertexFormat;Lnet/irisshaders/iris/gl/state/FogMode;ZZZZZLnet/irisshaders/iris/pipeline/transform/Patch;)Lnet/irisshaders/iris/pipeline/programs/ShaderSupplier;",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;create(Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/programs/ShaderKey;Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;Lnet/irisshaders/iris/shaderpack/loading/ProgramId;Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;Lnet/irisshaders/iris/gl/blending/AlphaTest;Lcom/mojang/renderpearl/api/vertex/VertexFormat;Lnet/irisshaders/iris/gl/state/ShaderAttributeInputs;Lnet/irisshaders/iris/uniforms/FrameUpdateNotifier;Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;Ljava/util/function/Supplier;Lnet/irisshaders/iris/gl/state/FogMode;ZZZZLnet/irisshaders/iris/uniforms/custom/CustomUniforms;Lnet/irisshaders/iris/pipeline/transform/Patch;)Lnet/irisshaders/iris/pipeline/programs/ShaderSupplier;")
    )
    private ShaderSupplier nvidium$buildTerrainPrograms(WorldRenderingPipeline pipeline, String name, ShaderKey key,
                                                         ProgramSource source, ProgramId programId,
                                                         GlFramebuffer beforeTranslucent, GlFramebuffer afterTranslucent,
                                                         AlphaTest fallbackAlpha, VertexFormat vertexFormat,
                                                         ShaderAttributeInputs inputs, FrameUpdateNotifier updateNotifier,
                                                         IrisRenderingPipeline parent, Supplier<ImmutableSet<Integer>> flipped,
                                                         FogMode fogMode, boolean isIntensity, boolean isFullbright,
                                                         boolean isShadowPass, boolean isLines,
                                                         CustomUniforms customUniforms, Patch patch,
                                                         Operation<ShaderSupplier> original) {
        ShaderSupplier result = original.call(pipeline, name, key, source, programId, beforeTranslucent, afterTranslucent,
                fallbackAlpha, vertexFormat, inputs, updateNotifier, parent, flipped, fogMode, isIntensity, isFullbright,
                isShadowPass, isLines, customUniforms, patch);

        if (key == ShaderKey.SODIUM_TERRAIN_CUTOUT && patch == Patch.SODIUM && IrisTerrainPrograms.shouldBuild()) {
            // Sodium keys have no fixed format: Iris uses the pack's terrain format, which it also returns from
            // ChunkMeshFormats.getCurrent()
            int stride = (vertexFormat != null ? vertexFormat : ChunkMeshFormats.getCurrent().getVertexFormat()).getVertexSize();
            IrisTerrainPrograms.build(parent, stride, pass -> original.call(pipeline, name, key, source, programId,
                    beforeTranslucent, afterTranslucent, fallbackAlpha, vertexFormat, inputs, updateNotifier, parent,
                    flipped, fogMode, isIntensity, isFullbright, isShadowPass, isLines, customUniforms, patch));
        }
        return result;
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void nvidium$releaseTerrainPrograms(CallbackInfo ci) {
        IrisTerrainPrograms.release((IrisRenderingPipeline) (Object) this);
    }
}
