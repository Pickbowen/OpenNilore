package shit.lizz.modules.impl.misc.ai.tasks;

import net.minecraft.core.BlockPos;
import shit.lizz.modules.impl.misc.ai.BaritoneBridge;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.misc.ai.btree.*;

import java.util.Random;

public class ExploreTasks {

    private static final Random random = new Random();
    private static int wanderTicks = 0;
    private static BlockPos wanderTarget = null;

    private static int idleTicks = 0;
    private static int idleTarget = 0;

    public static void startIdle() {
        idleTicks = 0;
        idleTarget = 20 + random.nextInt(30);
    }

    /**
     * 随便逛：走随机位置，Scaffold 自动搭路
     */
    public static BTNode wander() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy == null),
                new Condition(bb -> bb.gotoTarget == null),
                new Condition(bb -> bb.isOnGround),
                new Condition(bb -> !bb.isContainerOpen()),
                new Condition(bb -> {
                    if (idleTicks < idleTarget) {
                        idleTicks++;
                        Blackboard.clearMovement();
                        return false;
                    }
                    return true;
                }),
                new Action(bb -> {
                    wanderTicks++;

                    if (wanderTarget == null || wanderTicks > 80 + random.nextInt(80)) {
                        float yaw = random.nextFloat() * 360f;
                        int dist = 8 + random.nextInt(20);
                        int tx = bb.playerPos.getX() + (int) (dist * Math.cos(Math.toRadians(yaw)));
                        int tz = bb.playerPos.getZ() + (int) (dist * Math.sin(Math.toRadians(yaw)));
                        wanderTarget = new BlockPos(tx, bb.playerPos.getY(), tz);
                        wanderTicks = 1;
                    }

                    if (bb.isAboveVoid) {
                        wanderTarget = null;
                        return BTNode.Status.SUCCESS;
                    }

                    if (!BaritoneBridge.isPathing()) {
                        BaritoneBridge.setGoalAndPath("near", wanderTarget, 2);
                    }

                    return BaritoneBridge.isPathing() ? BTNode.Status.RUNNING : BTNode.Status.SUCCESS;
                })
        );
    }

    public static BTNode stopMovement() {
        return new Action(bb -> {
            if (bb.gotoTarget != null) return BTNode.Status.FAILURE;
            if (bb.nearestEnemy != null && bb.nearestEnemyDist <= 6) return BTNode.Status.FAILURE;
            if (bb.isContainerOpen()) return BTNode.Status.FAILURE;
            if (BaritoneBridge.isPathing()) return BTNode.Status.FAILURE;
            Blackboard.clearMovement();
            return BTNode.Status.SUCCESS;
        });
    }
}
