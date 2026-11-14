package client.nilore.modules.impl.movement;

import client.nilore.event.EventTarget;
import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.modules.impl.player.Stuck;

/**
 * Sprint — 基线（0cb3311）的实现: 每拍现算, 不留跨 tick 的疾跑状态。
 *
 * <p>{@code shouldHoldSprint()} 里问一次 {@link KillAura#canCritUnderVelocity()}, 成立就
 * 松键并清疾跑标记, 让这一拍真的发出 STOP_SPRINTING; 条件一不成立（目标走出 3.5 格 /
 * velocity 接管 / keepSprint 关掉 / 落地）就立刻把疾跑还回去。
 */
public class Sprint extends Module {

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @EventTarget(value = 0)
    public void onSprint(SprintEvent sprintEvent) {
        if (mc.player == null || InventoryManager.isPerformingAction) {
            return;
        }
        if (this.shouldHoldSprint()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            return;
        }
        mc.options.toggleSprint().set(false);
        mc.options.keySprint.setDown(true);
        if (mc.player.input.forwardImpulse > 0.0f) {
            mc.player.setSprinting(true);
        }
    }

    private boolean shouldHoldSprint() {
        if (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) {
            return true;
        }
        return KillAura.INSTANCE != null && KillAura.INSTANCE.canCritUnderVelocity();
    }

    @Override
    public void onDisable() {
        mc.options.keySprint.setDown(false);
    }
}
