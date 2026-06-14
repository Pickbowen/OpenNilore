package shit.lizz.modules.impl.misc.ai.tasks;

import net.minecraft.world.entity.player.Player;
import shit.lizz.ClientBase;
import shit.lizz.modules.impl.combat.KillAura;
import shit.lizz.modules.impl.combat.Critical;
import shit.lizz.modules.impl.combat.AntiKB;
import shit.lizz.modules.impl.misc.ai.BaritoneBridge;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.misc.ai.btree.*;
import shit.lizz.modules.impl.movement.Scaffold;

public class CombatTasks {

    private static int strafeDir = 1;
    private static int strafeSwitchTick = 0;

    /**
     * 近战：距离 <= 4 时绕着敌人打
     */
    public static BTNode meleeCombat() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist <= 4),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    BaritoneBridge.cancel();
                    Player enemy = bb.nearestEnemy;

                    if (!KillAura.INSTANCE.isEnabled()) {
                        bb.log("Fighting: " + enemy.getName().getString());
                    }
                    KillAura.INSTANCE.setEnabled(true);
                    Critical.INSTANCE.setEnabled(true);
                    AntiKB.INSTANCE.setEnabled(true);

                    // 绕着敌人走
                    strafeSwitchTick++;
                    if (strafeSwitchTick > 20 + (int) (Math.random() * 15)) {
                        strafeDir = -strafeDir;
                        strafeSwitchTick = 0;
                    }

                    double dx = ClientBase.mc.player.getX() - enemy.getX();
                    double dz = ClientBase.mc.player.getZ() - enemy.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    if (dist > 0.1) {
                        double nx = dx / dist;
                        double nz = dz / dist;
                        double sx = -nz * strafeDir;
                        double sz = nx * strafeDir;

                        double targetX = enemy.getX() + nx * 1.2 + sx * 0.6;
                        double targetZ = enemy.getZ() + nz * 1.2 + sz * 0.6;

                        double tdx = targetX - ClientBase.mc.player.getX();
                        double tdz = targetZ - ClientBase.mc.player.getZ();
                        float yaw = (float) (-Math.toDegrees(Math.atan2(tdx, tdz)));

                        Blackboard.smoothYaw(yaw, 30f);
                        ClientBase.mc.options.keyUp.setDown(true);
                        ClientBase.mc.options.keySprint.setDown(true);
                        ClientBase.mc.options.keyJump.setDown(bb.nearestEnemyDist < 1.5);
                    }

                    return BTNode.Status.SUCCESS;
                })
        );
    }

    /**
     * 追踪：距离 4-50 时主动走向敌人（Scaffold 自动启用搭路）
     */
    public static BTNode trackEnemy() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist > 4 && bb.nearestEnemyDist <= 50),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    KillAura.INSTANCE.setEnabled(false);
                    Player enemy = bb.nearestEnemy;

                    // 每 10 tick 更新目标位置（跟踪移动中的敌人）
                    if (!BaritoneBridge.isPathing() || bb.tickCount % 10 == 0) {
                        BaritoneBridge.setGoalAndPath("near", enemy.blockPosition(), 2);
                    }

                    return BTNode.Status.RUNNING;
                })
        );
    }

    /**
     * 战斗结束后关闭战斗模块
     */
    public static BTNode disableCombatModules() {
        return new Action(bb -> {
            boolean anyActive = KillAura.INSTANCE.isEnabled() || Critical.INSTANCE.isEnabled() || AntiKB.INSTANCE.isEnabled();
            if (!anyActive) return BTNode.Status.FAILURE;
            KillAura.INSTANCE.setEnabled(false);
            Critical.INSTANCE.setEnabled(false);
            AntiKB.INSTANCE.setEnabled(false);
            return BTNode.Status.SUCCESS;
        });
    }
}
