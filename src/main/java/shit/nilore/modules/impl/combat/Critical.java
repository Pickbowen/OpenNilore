package shit.nilore.modules.impl.combat;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import shit.nilore.event.impl.EntityRemoveEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.event.EventTarget;

public class Critical
extends Module {
    public static Critical INSTANCE;

    public final NumberSetting range = new NumberSetting("Range", 3.0, 1.0, 3.2, 0.1);
    public final BooleanSetting autoJump = new BooleanSetting("Auto Jump", true);

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @EventTarget
    public void onEntityRemove(EntityRemoveEvent entityRemoveEvent) {
        if (mc.player == null) {
            return;
        }
        boolean canCrit = mc.player.fallDistance > 0.0f && !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater() && !mc.player.hasEffect(MobEffects.BLINDNESS) && !mc.player.isPassenger() && entityRemoveEvent.entity() instanceof LivingEntity;
        boolean wasSprinting = mc.player.isSprinting();
        if (canCrit && !entityRemoveEvent.dead()) {
            mc.player.resetAttackStrengthTicker();
        }
        if (canCrit && wasSprinting && entityRemoveEvent.dead()) {
            mc.options.keySprint.setDown(false);
        }
    }
}