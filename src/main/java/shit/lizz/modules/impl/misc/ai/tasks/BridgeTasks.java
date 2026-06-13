package shit.lizz.modules.impl.misc.ai.tasks;

import net.minecraft.core.BlockPos;
import shit.lizz.ClientBase;
import shit.lizz.modules.impl.misc.ai.BaritoneBridge;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.misc.ai.btree.*;

public class BridgeTasks {

    public static BlockPos lastGotoTarget = null;

    /**
     * #goto 命令导航
     */
    public static BTNode gotoCommand() {
        return new Sequence(
                new Condition(bb -> bb.gotoTarget != null),
                new Action(bb -> {
                    BlockPos target = bb.gotoTarget;
                    double dx = target.getX() + 0.5 - ClientBase.mc.player.getX();
                    double dz = target.getZ() + 0.5 - ClientBase.mc.player.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    if (dist < 2) {
                        bb.log("Arrived at goto target");
                        BaritoneBridge.cancel();
                        bb.gotoTarget = null;
                        lastGotoTarget = null;
                        ExploreTasks.startIdle();
                        return BTNode.Status.SUCCESS;
                    }

                    if (!BaritoneBridge.isPathing() || !target.equals(lastGotoTarget)) {
                        BaritoneBridge.setGoalAndPath("block", target, 0);
                        lastGotoTarget = target;
                    }

                    bb.renderTarget = target;
                    return BaritoneBridge.isPathing() ? BTNode.Status.RUNNING : BTNode.Status.SUCCESS;
                })
        );
    }
}
