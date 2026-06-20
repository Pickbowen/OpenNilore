package shit.nilore.modules.impl.misc.ai.tasks;

import net.minecraft.world.entity.player.Player;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.combat.KillAura;
import shit.nilore.modules.impl.misc.ai.BaritoneBridge;
import shit.nilore.modules.impl.misc.ai.Blackboard;
import shit.nilore.modules.impl.misc.ai.MovementHelper;
import shit.nilore.modules.impl.misc.ai.btree.*;

public class CombatTasks {

    private static int strafeDir = 1;
    private static int strafeSwitchTick = 0;

    /**
     * 近战：开启 KillAura（自动旋转+攻击），AI 只负责贴身走位。
     * 不控制 yaw — KillAura 自己处理旋转。
     */
    public static BTNode meleeCombat() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist <= 4),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    // cancel 彻底清除寻路状态和运动键，避免残留输入覆盖走位
                    BaritoneBridge.cancel();

                    if (!KillAura.INSTANCE.isEnabled()) {
                        bb.log("Fighting: " + bb.nearestEnemy.getName().getString());
                    }
                    KillAura.INSTANCE.setEnabled(true);

                    // 随机切换左右走位方向
                    strafeSwitchTick++;
                    if (strafeSwitchTick > 20 + (int) (Math.random() * 15)) {
                        strafeDir = -strafeDir;
                        strafeSwitchTick = 0;
                    }

                    // KillAura 会自动转向敌人，W 向前靠近，A/D 左右走位
                    ClientBase.mc.options.keyUp.setDown(true);
                    ClientBase.mc.options.keyDown.setDown(false);
                    ClientBase.mc.options.keyLeft.setDown(strafeDir == -1);
                    ClientBase.mc.options.keyRight.setDown(strafeDir == 1);
                    ClientBase.mc.options.keySprint.setDown(true);
                    ClientBase.mc.options.keyJump.setDown(bb.nearestEnemyDist < 1.5);

                    return BTNode.Status.RUNNING;
                })
        );
    }

    public static BTNode trackEnemy() {
        return new Sequence(
                new Condition(bb -> bb.hasSword),
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist > 4 && bb.nearestEnemyDist <= 50),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    BaritoneBridge.resume();
                    Player enemy = bb.nearestEnemy;

                    if (!BaritoneBridge.isPathing()) {
                        BaritoneBridge.setGoalAndPath("near", enemy.blockPosition(), 2);
                    } else if (bb.tickCount % 20 == 0) {
                        BaritoneBridge.setGoalAndPath("near", enemy.blockPosition(), 2);
                    }

                    return BaritoneBridge.isPathing() ? BTNode.Status.RUNNING : BTNode.Status.FAILURE;
                })
        );
    }

    public static BTNode disableKillAura() {
        return new Action(bb -> {
            if (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()) {
                KillAura.INSTANCE.setEnabled(false);
            }
            BaritoneBridge.resume();
            MovementHelper.clearMovement();
            strafeSwitchTick = 0;
            return BTNode.Status.SUCCESS;
        });
    }

    public static void reset() {
        strafeDir = 1;
        strafeSwitchTick = 0;
    }
}
