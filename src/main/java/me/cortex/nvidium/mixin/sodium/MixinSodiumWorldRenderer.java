package me.cortex.nvidium.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.IrisCompat;
import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.mixin.minecraft.FrontendCommandEncoderAccessor;
import me.cortex.nvidium.mixin.minecraft.GlCommandEncoderAccessor;
import me.cortex.nvidium.sodiumCompat.INvidiumWorldRendererGetter;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer implements INvidiumWorldRendererGetter {
    @Shadow
    private RenderSectionManager renderSectionManager;
    @Unique
    private Viewport viewport;
    @Unique
    private Viewport shadowViewport;

    @Override
    public NvidiumWorldRenderer getRenderer() {
        if (Nvidium.IS_ENABLED) {
            return ((INvidiumWorldRendererGetter) renderSectionManager).getRenderer();
        } else {
            return null;
        }
    }

    @Inject(method = "setupTerrain", at = @At(value = "HEAD"))
    public void trackViewport(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci) {
        // Iris also calls setupTerrain for its shadow pass, with the shadow frustum; keep the two apart
        if (IrisCompat.isRenderingShadowPass()) {
            this.shadowViewport = viewport;
            return;
        }
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At(value = "HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, RenderPass renderPass, OitStage stage, CallbackInfo ci) {
        if (Nvidium.IS_ENABLED) {
            ci.cancel();
            if (pass == DefaultTerrainRenderPasses.CUTOUT) // Early exit, cutout will be rendered with SOLID
                return;

            // Invalidate lastProgram to prevent B3D caching bad program
            ((GlCommandEncoderAccessor)
                    ((FrontendCommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder())
                            .nvidium$getBackend())
                    .nvidium$setLastProgram(null);

            // Iris's shadow pass: draw terrain into the shadow map with the pack's shadow programs. This doesn't run a
            // normal Nvidium frame, whose occlusion culling is built from the player's view and reused across frames.
            // During the shadow pass Iris makes getRenderLists() return Sodium's shadow render list.
            if (IrisCompat.isRenderingShadowPass()) {
                GlStateManager._disableScissorTest();
                GlStateManager._disableCull(); // as Iris does for terrain in its shadow pass
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(IrisCompat.isReverseZDuringShaders() ? GL33C.GL_GEQUAL : GL33C.GL_LEQUAL);
                GlStateManager._colorMask(0, 15);
                GlStateManager._depthMask(true);
                GlStateManager._disableBlend(0);
                if (pass == DefaultTerrainRenderPasses.SOLID) {
                    this.getRenderer().renderShadow(pass, shadowViewport, renderSectionManager.getRenderLists(), fogParameters, matrices, x, y, z, false);
                } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                    this.getRenderer().renderShadow(pass, shadowViewport, renderSectionManager.getRenderLists(), fogParameters, matrices, x, y, z, true);
                }
                return;
            }

            GlStateManager._disableScissorTest();
            GlStateManager._enableCull();
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL33C.GL_GEQUAL); // reverse-Z
            GlStateManager._colorMask(0, 15);
            GlStateManager._depthMask(true);

            if (pass == DefaultTerrainRenderPasses.SOLID) {
                GlStateManager._disableBlend(0);
                this.getRenderer().renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                this.getRenderer().renderTranslucent(pass, (FrontendRenderPass) renderPass, terrainSampler, stage);
            }
        }
    }
}
