package shit.lizz.patch;

import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import shit.lizz.LizzClient;
import shit.lizz.asm.Invocation;
import shit.lizz.event.impl.UseItemRayTraceEvent;

@Patch(Item.class)
public class ItemPatch {
    private static final String DEBUG_PREFIX = "YRot: ";

    @WrapInvoke(
            method = "getPlayerPOVHitResult",
            desc = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;",
            target = "net/minecraft/world/entity/Entity/getXRot",
            targetDesc = "()F"
    )
    public static float onGetPOVHitXRot(Level level, Player player, ClipContext.Fluid fluidContext, Invocation<Player, Float> original) throws Exception {
        UseItemRayTraceEvent event = new UseItemRayTraceEvent(player.getYRot(), player.getXRot());
        if (LizzClient.isReady()) {
            LizzClient.getInstance().getEventBus().call(event);
        }
        return event.getPitch();
    }

    @WrapInvoke(
            method = "getPlayerPOVHitResult",
            desc = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;",
            target = "net/minecraft/world/entity/Entity/getYRot",
            targetDesc = "()F"
    )
    public static float onGetPOVHitYRot(Level level, Player player, ClipContext.Fluid fluidContext, Invocation<Player, Float> original) throws Exception {
        UseItemRayTraceEvent event = new UseItemRayTraceEvent(player.getYRot(), player.getXRot());
        if (LizzClient.isReady()) {
            // Don't use system out to print your shit retard.
            // System.out.println(DEBUG_PREFIX + player.getYRot());
            LizzClient.getInstance().getEventBus().call(event);
        }
        return event.getYaw();
    }
}
