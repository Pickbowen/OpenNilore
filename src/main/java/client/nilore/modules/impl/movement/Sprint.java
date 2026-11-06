package client.nilore.modules.impl.movement;

import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.event.EventTarget;

/**
 * Sprint — Naven (tech.naven.modules.impl.move.Sprint) 整体移植。
 *
 * <p>疾跑的<b>释放</b>只属于这里: {@code shouldStopSprint()} 为真时松掉并
 * {@code markSprintReleased()} 记一笔 (释放过就一直保持不跑, 直到落地复位),
 * 其余时候强制疾跑。旧实现用 canCritUnderVelocity 每 tick 判定, 地面上反复
 * "松/紧"就是疾跑一抽一抽的来源。
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

        KillAura aura = KillAura.INSTANCE;
        if ((Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled())
                || (aura != null && aura.shouldStopSprint())) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            if (aura != null) {
                aura.markSprintReleased();
            }
            return;
        }

        if (aura != null && aura.isSprintReleased()) {
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

    @Override
    public void onDisable() {
        mc.options.keySprint.setDown(false);
    }
}
