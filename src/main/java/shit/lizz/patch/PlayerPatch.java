package shit.lizz.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import shit.lizz.ClientBase;
import shit.lizz.LizzClient;
import shit.lizz.asm.Invocation;
import shit.lizz.event.impl.EntityRemoveEvent;

@Patch(Player.class)
public class PlayerPatch {
    @WrapInvoke(
            method = "die",
            desc = "(Lnet/minecraft/world/damagesource/DamageSource;)V",
            target = "net/minecraft/world/entity/Entity/getYRot",
            targetDesc = "()F"
    )
    public static float onDieGetYRot(Player player, DamageSource source, Invocation<Player, Float> original) throws Exception {
        return ClientBase.yaw;
    }

    @WrapInvoke(
            method = "drop",
            desc = "(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            target = "net/minecraft/world/entity/Entity/getYRot",
            targetDesc = "()F"
    )
    public static float onDropGetYRot(Player player, ItemStack stack, boolean dropAll, boolean traceItem, Invocation<Player, Float> original) throws Exception {
        return ClientBase.yaw;
    }

    @WrapInvoke(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V", target = "net/minecraft/world/entity/Entity/getYRot", targetDesc = "()F")
    public static float onAttackGetYRot(Player player, Entity target, Invocation<Player, Float> original) throws Exception {
        return ClientBase.yaw;
    }

    @Inject(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V", at = @At(At.Type.HEAD))
    public static void onAttackPre(Player player, Entity target, CallbackInfo callbackInfo) {
        if (LizzClient.isReady()) {
            LizzClient.getInstance().getEventBus().call(new EntityRemoveEvent(false, target));
        }
    }

    @Inject(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V", at = @At(At.Type.TAIL))
    public static void onAttackPost(Player player, Entity target, CallbackInfo callbackInfo) {
        if (LizzClient.isReady()) {
            LizzClient.getInstance().getEventBus().call(new EntityRemoveEvent(true, target));
        }
    }
}
