package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.asm.Invocation;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.EventAttackSlowdown;
import client.nilore.modules.impl.combat.KillAura;

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

    @WrapInvoke(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/player/Player/setSprinting", targetDesc = "(Z)V")
    public static void onAttackSetSprinting(Player player, Entity target, Invocation<Player, Void> original) throws Exception {
        if (NiloreClient.isReady()) {
            EventAttackSlowdown event = new EventAttackSlowdown(true);
            NiloreClient.getInstance().getEventBus().call(event);
            if (event.isCancelled()) {
                return;
            }
        }
        original.call();
    }

    @WrapInvoke(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/Entity/setDeltaMovement", targetDesc = "(Lnet/minecraft/world/phys/Vec3;)V")
    public static void onAttackSetDeltaMovement(Player player, Entity target, Invocation<Player, Void> original) throws Exception {
        if (NiloreClient.isReady()) {
            EventAttackSlowdown event = new EventAttackSlowdown(false);
            NiloreClient.getInstance().getEventBus().call(event);
            if (event.isCancelled()) {
                return;
            }
        }
        original.call();
    }

    @Inject(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V", at = @At(At.Type.HEAD))
    public static void onAttackPre(Player player, Entity target, CallbackInfo callbackInfo) {
        if (NiloreClient.isReady()) {
            // 自动化(aura / NoXZ 反打)刚发起的那一次: 放行。
            // 否则这一拍已经出过刀, 原版这次点击就是重复包 —— 同一 tick 两个 ATTACK 会被
            // 服务端当成非法攻击, 表现就是"手动打、或手动+aura 一起打的时候被拉回"。
            if (!KillAura.consumeAutomatedAttackMark() && !KillAura.attackAndLock()) {
                callbackInfo.cancel();
                return;
            }
            NiloreClient.getInstance().getEventBus().call(new EntityRemoveEvent(false, target));
        }
    }

    @Inject(method = "attack", desc = "(Lnet/minecraft/world/entity/Entity;)V", at = @At(At.Type.TAIL))
    public static void onAttackPost(Player player, Entity target, CallbackInfo callbackInfo) {
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new EntityRemoveEvent(true, target));
        }
    }
}
