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

    /** 上一拍 SprintEvent 时读到的疾跑状态, 用来判断这一拍起点是不是"被强制停掉了"。 */
    private boolean wasSprinting;

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @EventTarget(value = 0)
    public void onSprint(SprintEvent sprintEvent) {
        if (mc.player == null || InventoryManager.isPerformingAction) {
            return;
        }
        boolean sprinting = mc.player.isSprinting();
        // 上一拍还在疾跑, 这一拍起点已经掉下来了 —— 只可能是别的东西把它停了
        // (攻击的 sprint reset, 或者 NoXZ 反打补的那一下 setSprinting(false))。
        boolean forceStopped = this.wasSprinting && !sprinting;
        this.wasSprinting = sprinting;
        if (forceStopped) {
            // 这一拍只松键、不重压。让 vanilla 的 sendIsSprintingIfNeeded 在 tick 末尾
            // 真的把 STOP_SPRINTING 发出去; 下一拍再正常重压, 由 aiStep 重开并补 START。
            //
            // 两边都压的话 isSprinting 在 tick 内"开了又关", 头尾相同, 一个疾跑包都不会发:
            // 服务端仍按疾跑算, 客户端却已经掉了速度, 每 tick 记一笔 .029 级的 Simulation。
            mc.options.keySprint.setDown(false);
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
