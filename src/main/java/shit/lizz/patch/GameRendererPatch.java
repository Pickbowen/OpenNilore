package shit.lizz.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Overwrite;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import shit.lizz.LizzClient;
import shit.lizz.asm.Invocation;
import shit.lizz.event.impl.GlRenderEvent;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.modules.impl.render.AspectRatio;
import shit.lizz.modules.impl.render.FullBright;
import shit.lizz.modules.impl.render.NoHurtCam;
import shit.lizz.render.Renderer;
import shit.lizz.utils.misc.ReflectionUtil;

@Patch(GameRenderer.class)
public class GameRendererPatch {
    @Overwrite(method = "getNightVisionScale", desc = "(Lnet/minecraft/world/entity/LivingEntity;F)F")
    public static float overwriteGetNightVisionScale(LivingEntity entity, float partial) {
        if (FullBright.INSTANCE != null && FullBright.INSTANCE.isEnabled()) {
            return FullBright.INSTANCE.brightnessSetting.getValue().floatValue() / 100.0f;
        }
        return entity.hasEffect(MobEffects.NIGHT_VISION) ? 1.0f : 0.0f;
    }

    @Inject(
            method = "render",
            desc = "(FJZ)V",
            at = @At(value = At.Type.AFTER_INVOKE, method = "net/minecraft/client/gui/Gui/render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;F)V")
    )
    public static void onRender(GameRenderer gameRenderer, float partialTick, long nanoTime, boolean renderLevel, CallbackInfo callbackInfo) {
        GuiGraphics graphics = new GuiGraphics(gameRenderer.getMinecraft(), gameRenderer.getMinecraft().renderBuffers().bufferSource());
        Render2DEvent event = new Render2DEvent(graphics.pose(), graphics, partialTick);
        if (LizzClient.isReady()) {
            LizzClient.getInstance().getEventBus().call(event);
            graphics.pose().pushPose();
            Renderer.render(graphics, glEvent -> {
                GlRenderEvent glRender = new GlRenderEvent(graphics, graphics.pose(), glEvent);
                LizzClient.getInstance().getEventBus().call(glRender);
            });
            graphics.pose().popPose();
        }
    }

    @WrapInvoke(
            method = "getProjectionMatrix",
            desc = "(D)Lorg/joml/Matrix4f;",
            target = "org/joml/Matrix4f/setPerspective",
            targetDesc = "(FFFF)Lorg/joml/Matrix4f;"
    )
    public static Matrix4f onGetProjectionMatrix(GameRenderer gameRenderer, double fov, Invocation<GameRenderer, Matrix4f> original) throws Exception {
        if (!LizzClient.isReady() || AspectRatio.INSTANCE == null || !AspectRatio.INSTANCE.isEnabled()) {
            return original.call();
        }
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().identity();
        float zoom = (Float) ReflectionUtil.getStaticField(gameRenderer, "zoom", "net/minecraft/client/renderer/GameRenderer");
        float zoomX = (Float) ReflectionUtil.getStaticField(gameRenderer, "zoomX", "net/minecraft/client/renderer/GameRenderer");
        float zoomY = (Float) ReflectionUtil.getStaticField(gameRenderer, "zoomY", "net/minecraft/client/renderer/GameRenderer");
        if (zoom != 1.0f) {
            poseStack.translate(zoomX, -zoomY, 0.0f);
            poseStack.scale(zoom, zoom, 1.0f);
        }
        poseStack.last().pose().mul(new Matrix4f().setPerspective(
                (float) (fov * (float) (Math.PI / 180.0)),
                AspectRatio.INSTANCE.ratioSetting.getValue().floatValue(),
                0.05f,
                gameRenderer.getDepthFar()));
        return poseStack.last().pose();
    }

    @Inject(method = "bobHurt", desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At(At.Type.HEAD))
    public static void onBobHurt(GameRenderer gameRenderer, PoseStack poseStack, float partial, CallbackInfo callbackInfo) {
        if (LizzClient.isReady() && NoHurtCam.INSTANCE != null && NoHurtCam.INSTANCE.isEnabled()) {
            callbackInfo.cancel();
        }
    }
}
