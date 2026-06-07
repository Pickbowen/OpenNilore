package shit.lizz.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import shit.lizz.LizzClient;
import shit.lizz.event.impl.RenderEvent;

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
        if (LizzClient.isReady()) {
            LizzClient.getInstance().getEventBus().call(new RenderEvent(poseStack, partialTick));
        }
    }
}
