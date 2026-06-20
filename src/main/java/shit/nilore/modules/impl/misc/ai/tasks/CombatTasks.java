package shit.nilore.modules.impl.misc.ai.tasks;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.misc.ai.BaritoneBridge;
import shit.nilore.modules.impl.misc.ai.Blackboard;
import shit.nilore.modules.impl.misc.ai.btree.*;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.rotation.Rotation;

public class CombatTasks {

    private static int strafeDir = 1;
    private static int strafeSwitchTick = 0;
    private static int attackTick = 0;
    private static boolean inCombat = false;

    /**
     * 近战：距离 <= 4 时直接砍 + 绕着敌人走
     * 不依赖 KillAura，自己处理旋转和攻击
     */
    public static BTNode meleeCombat() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist <= 4),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    BaritoneBridge.pause();
                    Player enemy = bb.nearestEnemy;

                    if (!inCombat) {
                        bb.log("Fighting: " + enemy.getName().getString());
                        inCombat = true;
                        attackTick = 0;
                    }

                    // === 旋转面向敌人 ===
                    Rotation rot = RotationUtil.entityRotation(enemy);
                    if (rot != null) {
                        Blackboard.smoothYaw(rot.getYaw(), 40f);
                        Blackboard.smoothPitch(rot.getPitch(), 40f);
                    }

                    // === 攻击（每 2 tick 一次，模拟 ~10 CPS） ===
                    attackTick++;
                    if (attackTick >= 2) {
                        attackTick = 0;
                        ClientBase.mc.gameMode.attack(ClientBase.mc.player, enemy);
                        ClientBase.mc.player.swing(InteractionHand.MAIN_HAND);
                    }

                    // === 绕着敌人走 ===
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
     * 追踪：距离 4-50 时主动走向敌人
     */
    public static BTNode trackEnemy() {
        return new Sequence(
                new Condition(bb -> bb.hasSword),
                new Condition(bb -> bb.nearestEnemy != null && bb.nearestEnemyDist > 4 && bb.nearestEnemyDist <= 50),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    inCombat = false;
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

    /**
     * 战斗结束后清理状态
     */
    public static BTNode disableCombatModules() {
        return new Action(bb -> {
            if (!inCombat) return BTNode.Status.FAILURE;
            inCombat = false;
            attackTick = 0;
            BaritoneBridge.resume();
            return BTNode.Status.SUCCESS;
        });
    }

    public static void reset() {
        strafeDir = 1;
        strafeSwitchTick = 0;
        attackTick = 0;
        inCombat = false;
    }
}
