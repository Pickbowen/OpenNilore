package shit.lizz.patch;

import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Slice;
import asm.patchify.annotation.WrapInvoke;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import shit.lizz.ClientBase;
import shit.lizz.asm.Invocation;

@Patch(ClientLevel.class)
public class ClientLevelPatch {
    @WrapInvoke(
            method = "tickNonPassenger",
            desc = "(Lnet/minecraft/world/entity/Entity;)V",
            target = "net/minecraft/world/entity/Entity/tick",
            targetDesc = "()V",
            slice = @Slice(startIndex = 2, endIndex = 2)
    )
    public static void onTickEntity(ClientLevel level, Entity entity, Invocation<Entity, Void> original) throws Exception {
        if (!ClientBase.delayPackets.isEmpty() && entity == ClientBase.mc.player) {
            Runnable delayed = ClientBase.delayPackets.poll();
            if (delayed != null) {
                delayed.run();
            }
        } else {
            original.call();
        }
    }
}
