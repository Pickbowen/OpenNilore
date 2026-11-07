package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import client.nilore.NiloreClient;
import client.nilore.asm.Invocation;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.impl.render.BetterFPS;

@Patch(LevelRenderer.class)
public class LevelRendererPatch {
    @Inject(
            method = "renderLevel",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(At.Type.TAIL)
    )
    public static void onRenderLevel(
            LevelRenderer renderer,
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projection,
            CallbackInfo callbackInfo) {
        if (NiloreClient.isReady()) {
            // Frame boundary for the frame-scoped blur capture: everything drawn after this point
            // (vanilla GUI, HUD, screens) shares one downsample of the framebuffer.
            client.nilore.utils.render.RenderUtil.beginFrame();
            NiloreClient.getInstance().getEventBus().call(new RenderEvent(poseStack, partialTick));
        }
    }

    @WrapInvoke(
            method = "renderChunkLayer",
            desc = "(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            target = "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk/getCompiledChunk",
            targetDesc = "()Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;"
    )
    public static ChunkRenderDispatcher.CompiledChunk onRenderSectionChunk(
            LevelRenderer renderer,
            RenderType renderType,
            PoseStack poseStack,
            double camX,
            double camY,
            double camZ,
            Matrix4f projection,
            Invocation<ChunkRenderDispatcher.RenderChunk, ChunkRenderDispatcher.CompiledChunk> original)
            throws Exception {
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        ChunkRenderDispatcher.RenderChunk chunk = original.instance();
        if (chunk != null && betterFPS != null) {
            BlockPos origin = chunk.getOrigin();
            double dx = origin.getX() + 8.0 - camX;
            double dz = origin.getZ() + 8.0 - camZ;
            if (betterFPS.covers(origin, dx * dx + dz * dz)) {
                return ChunkRenderDispatcher.CompiledChunk.UNCOMPILED;
            }
        }
        return original.call();
    }
}
