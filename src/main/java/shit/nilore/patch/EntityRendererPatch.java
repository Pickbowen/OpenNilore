package shit.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import shit.nilore.NiloreClient;
import shit.nilore.modules.impl.render.NameTags;

@Patch(EntityRenderer.class)
public class EntityRendererPatch {
    @Inject(
            method = "renderNameTag",
            desc = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderNameTag(EntityRenderer<?> renderer, Entity entity, Component component, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo callbackInfo) {
        if (entity instanceof LivingEntity && NiloreClient.isReady() && NameTags.INSTANCE != null && NameTags.INSTANCE.isEnabled()) {
            callbackInfo.cancel();
        }
    }
}
