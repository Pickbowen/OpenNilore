package shit.nilore.modules.impl.misc.ai.tasks;

import net.minecraft.world.entity.player.Player;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.combat.KillAura;
import shit.nilore.modules.impl.misc.ai.BaritoneBridge;
import shit.nilore.modules.impl.misc.ai.Blackboard;
import shit.nilore.modules.impl.misc.ai.MovementHelper;
import shit.nilore.modules.impl.misc.ai.btree.*;

public class CombatTasks {

    private static int trackStuckTick = 0;

    /**
     * 近战：开启 KillAura，AI 只负责向前靠近。
     * KillAura 处理旋转和攻击。
     */
    public static BTNode meleeCombat() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist <= 4),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    BaritoneBridge.cancel();

                    if (!KillAura.INSTANCE.isEnabled()) {
                        bb.log("Fighting: " + bb.nearestEnemy.getName().getString());
                    }
                    KillAura.INSTANCE.setEnabled(true);

                    // W 向前靠近，KillAura 负责旋转和攻击
                    ClientBase.mc.options.keyUp.setDown(true);
                    ClientBase.mc.options.keyDown.setDown(false);
                    ClientBase.mc.options.keyLeft.setDown(false);
                    ClientBase.mc.options.keyRight.setDown(false);
                    ClientBase.mc.options.keySprint.setDown(true);
                    ClientBase.mc.options.keyJump.setDown(false);

                    return BTNode.Status.RUNNING;
                })
        );
    }

    /**
     * 追踪远处敌人。用 Baritone 寻路靠近。
     * 寻路卡住超过 40 tick 或寻路失败则返回 FAILURE。
     */
    public static BTNode trackEnemy() {
        return new Sequence(
                new Condition(bb -> bb.hasSword),
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist > 4 && bb.nearestEnemyDist <= 50),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    Player enemy = bb.nearestEnemy;

                    if (BaritoneBridge.isPathFailed()) {
                        BaritoneBridge.cancel();
                        MovementHelper.clearMovement();
                        return BTNode.Status.FAILURE;
                    }

                    if (!BaritoneBridge.isPathing()) {
                        trackStuckTick = 0;
                        boolean ok = BaritoneBridge.setGoalAndPath("near", enemy.blockPosition(), 2);
                        if (!ok) return BTNode.Status.FAILURE;
                    } else {
                        trackStuckTick++;
                        if (trackStuckTick > 40) {
                            BaritoneBridge.cancel();
                            trackStuckTick = 0;
                            return BTNode.Status.FAILURE;
                        }
                        if (bb.tickCount % 15 == 0) {
                            BaritoneBridge.setGoalAndPath("near", enemy.blockPosition(), 2);
                            trackStuckTick = 0;
                        }
                    }

                    return BTNode.Status.RUNNING;
                })
        );
    }

    /**
     * 关闭 KillAura，清理战斗状态。
     */
    public static BTNode disableKillAura() {
        return new Action(bb -> {
            if (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()) {
                KillAura.INSTANCE.setEnabled(false);
            }
            BaritoneBridge.cancel();
            MovementHelper.clearMovement();
            trackStuckTick = 0;
            return BTNode.Status.SUCCESS;
        });
    }

    public static void reset() {
        trackStuckTick = 0;
    }
}
