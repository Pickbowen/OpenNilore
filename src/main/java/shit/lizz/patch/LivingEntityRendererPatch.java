package shit.lizz.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Slice;
import asm.patchify.annotation.WrapInvoke;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import shit.lizz.ClientBase;
import shit.lizz.LizzClient;
import shit.lizz.asm.Invocation;
import shit.lizz.event.impl.RenderEntityEvent;
import shit.lizz.event.impl.RotationAnimationEvent;

@Patch(LivingEntityRenderer.class)
public class LivingEntityRendererPatch {
    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderPre(
            LivingEntityRenderer<?, ?> renderer, LivingEntity entity, float yaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo callbackInfo) {
        if (!LizzClient.isReady()) return;
        RenderEntityEvent.Post pre = new RenderEntityEvent.Post(renderer, entity, poseStack, bufferSource, partialTick, packedLight);
        LizzClient.getInstance().getEventBus().call(pre);
        if (pre.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(At.Type.TAIL)
    )
    public static void onRenderPost(
            LivingEntityRenderer<?, ?> renderer, LivingEntity entity, float yaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo callbackInfo) {
        if (!LizzClient.isReady()) return;
        RenderEntityEvent.Pre post = new RenderEntityEvent.Pre(renderer, entity, poseStack, bufferSource, partialTick, packedLight);
        LizzClient.getInstance().getEventBus().call(post);
    }

    // PatchTransformer.wrapInvoke now tries strict owner+name+desc matching first,
    // so this slice index is independent of the other Mth.* wraps in this patch.
    // LivingEntityRenderer.render holds 3 Mth.rotLerp(FFF)F calls in order:
    //   1: body yaw    (Mth.rotLerp(g, yBodyRotO, yBodyRot))
    //   2: head yaw    (Mth.rotLerp(g, yHeadRotO, yHeadRot))      <- we want this
    //   3: head yaw recalc in the conditional branch
    @WrapInvoke(
            method = "render",
            desc = "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            target = "net/minecraft/util/Mth/rotLerp",
            targetDesc = "(FFF)F",
            slice = @Slice(startIndex = 2, endIndex = 2)
    )
    public static float onRenderHeadYawLerp(
            LivingEntityRenderer<?, ?> renderer, LivingEntity entity, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            Invocation<Object, Float> original) throws Exception {
        float delta = (Float) original.args().get(0);
        float start = (Float) original.args().get(1);
        float end   = (Float) original.args().get(2);
        RotationAnimationEvent event = new RotationAnimationEvent(end, start, 0.0f, 0.0f);
        if (LizzClient.isReady() && entity == ClientBase.mc.player) {
            LizzClient.getInstance().getEventBus().call(event);
        }
        return Mth.rotLerp(delta, event.getLastYaw(), event.getYaw());
    }

    // Only one Mth.lerp(FFF)F call site in LivingEntityRenderer.render (pitch
    // lerp: Mth.lerp(g, xRotO, getXRot())). The strict matcher in
    // PatchTransformer.wrapInvoke filters out the three Mth.rotLerp calls, so
    // slice (1,1) is the right pick.
    @WrapInvoke(
            method = "render",
            desc = "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            target = "net/minecraft/util/Mth/lerp",
            targetDesc = "(FFF)F",
            slice = @Slice(startIndex = 1, endIndex = 1)
    )
    public static float onRenderPitchLerp(
            LivingEntityRenderer<?, ?> renderer, LivingEntity entity, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            Invocation<Object, Float> original) throws Exception {
        float delta = (Float) original.args().get(0);
        float start = (Float) original.args().get(1);
        float end   = (Float) original.args().get(2);
        RotationAnimationEvent event = new RotationAnimationEvent(0.0f, 0.0f, end, start);
        if (LizzClient.isReady() && entity == ClientBase.mc.player) {
            LizzClient.getInstance().getEventBus().call(event);
        }
        return Mth.lerp(delta, event.getLastPitch(), event.getPitch());
    }
}
