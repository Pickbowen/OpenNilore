package shit.nilore.modules.impl.misc.ai.tasks;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.misc.ai.Blackboard;
import shit.nilore.modules.impl.misc.ai.btree.*;
import shit.nilore.modules.impl.player.InventoryManager;

public class InventoryTasks {

    private static int lastSortTick = -100;
    private static int waitTicks = 0;

    public static BTNode ensureInvManager() {
        return new Action(bb -> {
            if (InventoryManager.INSTANCE != null && !InventoryManager.INSTANCE.isEnabled()) {
                InventoryManager.INSTANCE.setEnabled(true);
            }
            return BTNode.Status.FAILURE;
        });
    }

    public static BTNode openInventory() {
        return new Sequence(
                new Condition(bb -> bb.nearestEnemy == null || bb.nearestEnemyDist > 10),
                new Condition(bb -> !bb.isContainerOpen()),
                new Condition(bb -> !(ClientBase.mc.screen instanceof InventoryScreen)),
                new Condition(bb -> LootTasks.needsSort || bb.tickCount - lastSortTick >= 100),
                new Action(bb -> {
                    LootTasks.needsSort = false;
                    ClientBase.mc.setScreen(new InventoryScreen(ClientBase.mc.player));
                    lastSortTick = bb.tickCount;
                    waitTicks = 0;
                    bb.log("Sorting inventory...");
                    return BTNode.Status.SUCCESS;
                })
        );
    }

    public static BTNode waitForSorting() {
        return new Action(bb -> {
            if (!(ClientBase.mc.screen instanceof InventoryScreen)) return BTNode.Status.FAILURE;
            waitTicks++;
            if ((!InventoryManager.isPerformingAction && waitTicks > 5) || waitTicks > 200) {
                ClientBase.mc.setScreen(null);
                lastSortTick = bb.tickCount;
                return BTNode.Status.SUCCESS;
            }
            return BTNode.Status.RUNNING;
        });
    }

    public static void reset() {
        lastSortTick = -100;
        waitTicks = 0;
    }
}
