package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MixinDefaultChunkRenderer {

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = ((IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                renderer.renderOpaque(matrices, camera.x, camera.y, camera.z);
            }

            var overworldRenderer = ((IGetVoxyRenderSystem) MinecraftClient.getInstance().worldRenderer).getVoxyOverworldRenderSystem();
            if (overworldRenderer != null) {
                overworldRenderer.renderOpaque(new ChunkRenderMatrices(matrices.projection(),
                                new Matrix4f(matrices.modelView()).scale(1/8f).scale(1,-1,1)),
                        camera.x*8, (300-camera.y)*8, (camera.z)*8);
            }
        }
    }
}
