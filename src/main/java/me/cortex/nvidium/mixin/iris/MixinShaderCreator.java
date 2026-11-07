package me.cortex.nvidium.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import me.cortex.nvidium.iris.IrisTerrainPrograms;
import me.cortex.nvidium.iris.MeshProgramLinker;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ShaderCreator.class, remap = false)
public class MixinShaderCreator {
    /**
     * While Nvidium is asking Iris to create a terrain program (see IrisTerrainPrograms), link Nvidium's task and
     * mesh shaders with the pack's fragment shader instead of the vertex/fragment program Iris would link.
     * Iris's patched sources arrive here, after all of Iris's transformations.
     */
    @WrapOperation(
            method = "create",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;link(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/renderpearl/api/vertex/VertexFormat;Z)Lnet/irisshaders/iris/pipeline/programs/PartialShader;")
    )
    private static PartialShader nvidium$linkMeshProgram(String name, String vertex, String geometry, String tessControl,
                                                         String tessEval, String fragment, VertexFormat vertexFormat,
                                                         boolean isFallback, Operation<PartialShader> original) {
        IrisTerrainPrograms.Pass pass = IrisTerrainPrograms.currentRequest();
        if (pass == null) {
            return original.call(name, vertex, geometry, tessControl, tessEval, fragment, vertexFormat, isFallback);
        }
        return MeshProgramLinker.link(name, pass, vertex, geometry, tessControl, tessEval, fragment, vertexFormat);
    }
}
