package shit.nilore.modules.impl.combat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.SwordItem;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.NumberSetting;

/**
 * 自动格挡：手持剑且附近有敌人时自动右键格挡（1.8 风格）
 */
public class Blocking extends Module {

    public static Blocking INSTANCE;

    public final NumberSetting enemyRange = new NumberSetting("Enemy Range", 6, 2, 16, 1);

    public Blocking() {
        super("Blocking", Category.COMBAT);
        INSTANCE = this;
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!event.isPost()) return;
        if (mc.player == null || mc.level == null) return;

        // 手持剑才格挡
        if (!(mc.player.getMainHandItem().getItem() instanceof SwordItem)) {
            return;
        }

        // 附近有敌人才格挡
        double range = enemyRange.getValue().doubleValue();
        boolean enemyNearby = mc.level.players().stream()
                .filter(p -> p != mc.player)
                .filter(p -> p.isAlive() && !p.isSpectator())
                .anyMatch(p -> mc.player.distanceTo(p) <= range);

        if (enemyNearby) {
            mc.options.keyUse.setDown(true);
        } else {
            mc.options.keyUse.setDown(false);
        }
    }

    @Override
    protected void onDisable() {
        if (mc.player != null) {
            mc.options.keyUse.setDown(false);
        }
    }
}
