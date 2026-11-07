package client.nilore.modules.impl.movement;

import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.event.EventTarget;

/**
 * Sprint — 回到 60ad1c0 之前的「每拍现算」版本。
 *
 * <p>60ad1c0 把它换成 Naven 的 sticky 标记（{@code sprintCancelled} + 单一复位点挂在
 * KillAura.onTick 上），结果是打完人目标被击退、或者落地那一拍 onGround 还没翻成 true 时，
 * 标记就永远粘着，疾跑再也回不来 —— 也就是「落地还是 nosprint」。
 *
 * <p>现在恢复旧行为：每一拍重新问一次 {@link KillAura#canCritUnderVelocity()}，条件一不成立
 * （目标走出 3.5 格 / velocity 接管 / keepSprint 关掉 / 落地）就立刻把疾跑还回去，不再有
 * 跨 tick 的状态。
 *
 * <p>唯一保留的新行为：只有真正在前进时才 {@code setSprinting(true)}。旧的 {@code zza != 0}
 * 把后退（{@code zza < 0}）也算成「在动」，于是按 S 后退时疾跑会被重新压起来。
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
        // 只前进才强制疾跑：后退 / 只按左右不重启疾跑，否则按 S 也会被当成在动。
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
