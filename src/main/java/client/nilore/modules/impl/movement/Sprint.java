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
 *
 * <p>2026-11-21: 补上"被别处强制停疾跑"的那一拍。{@code Keep Sprint} 关闭时
 * {@code canCritUnderVelocity()} 恒为 false, 原来会走重压分支 —— 而疾跑中出刀时
 * {@code Player.attack} 已经把 {@code isSprinting} 置成 false, 同一 tick 再压键置回 true,
 * tick 头尾状态相同, {@code sendPosition()} 里的 {@code sendIsSprintingIfNeeded()} 一个包都不发;
 * 服务端自己按疾跑攻击处理（sprinting=false, 速度 0.10 预测）, 客户端却按 0.13 位移,
 * 每 tick 0.03 的 Simulation offset 就叠成了打架时的 VL。这一拍只松键、不开疾跑,
 * 让原版把 STOP_SPRINTING 发出去, 下一拍再正常恢复（并补发 START_SPRINTING）。
 */
public class Sprint extends Module {

    /**
     * 上一拍 {@code SprintEvent} 时刻的疾跑状态。
     *
     * <p>原版只在 {@code LocalPlayer.sendPosition()} 里比较"本 tick 末尾的 isSprinting()"
     * 与上一 tick 记录的状态来决定发 START/STOP; 一个 tick 内"开了又关"是发不出任何包的。
     * 所以用这个快照判定"这一拍的起点疾跑状态被谁抢掉了"。
     */
    private boolean sprintingLastTick;

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @Override
    public void onEnable() {
        this.sprintingLastTick = mc.player != null && mc.player.isSprinting();
    }

    @EventTarget(value = 0)
    public void onSprint(SprintEvent sprintEvent) {
        if (mc.player == null || InventoryManager.isPerformingAction) {
            this.sprintingLastTick = mc.player != null && mc.player.isSprinting();
            return;
        }
        boolean sprinting = mc.player.isSprinting();
        // 上一拍还在疾跑, 这一拍起点已经不是: 这是 Player.attack 的 sprint reset
        // （疾跑中出刀 + 蓄力 > 0.9, 或击退附魔）。这一拍必须让疾跑真的停住。
        boolean forcedStop = this.sprintingLastTick && !sprinting;
        this.sprintingLastTick = sprinting;

        if (forcedStop || this.shouldHoldSprint()) {
            mc.options.keySprint.setDown(false);
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
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
        this.sprintingLastTick = false;
        mc.options.keySprint.setDown(false);
    }
}
