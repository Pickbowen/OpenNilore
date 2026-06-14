package shit.lizz.modules.impl.misc.ai.tasks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import shit.lizz.ClientBase;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.misc.ai.BaritoneBridge;
import shit.lizz.modules.impl.misc.ai.btree.*;
import shit.lizz.utils.game.ItemUtil;

public class SurvivalTasks {

    private static int eatingSlot = -1;
    private static int prevSlot = -1;

    public static BTNode eatFood() {
        return new Sequence(
                new Condition(bb -> bb.autoEat && bb.isHealthLow() && bb.hasFood),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    boolean enemyNearby = bb.nearestEnemy != null && bb.nearestEnemyDist <= 6;

                    if (eatingSlot != -1) {
                        // Currently eating — wait for completion
                        if (!ClientBase.mc.player.isUsingItem()) {
                            ClientBase.mc.options.keyUse.setDown(false);
                            if (prevSlot != -1) {
                                ClientBase.mc.player.getInventory().selected = prevSlot;
                            }
                            eatingSlot = -1;
                            prevSlot = -1;
                            return bb.isHealthLow() ? BTNode.Status.RUNNING : BTNode.Status.SUCCESS;
                        }
                        return BTNode.Status.RUNNING;
                    }

                    int slot = findFoodSlot(enemyNearby);
                    if (slot == -1) return BTNode.Status.FAILURE;

                    prevSlot = ClientBase.mc.player.getInventory().selected;
                    ClientBase.mc.player.getInventory().selected = slot;
                    eatingSlot = slot;
                    ClientBase.mc.options.keyUse.setDown(true);
                    bb.log("Eating food (slot " + slot + ")");
                    return BTNode.Status.RUNNING;
                })
        );
    }

    public static BTNode criticalEat() {
        return new Sequence(
                new Condition(bb -> bb.autoEat && bb.isHealthCritical() && bb.hasFood),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    boolean enemyNearby = bb.nearestEnemy != null && bb.nearestEnemyDist <= 6;

                    if (eatingSlot != -1) {
                        if (!ClientBase.mc.player.isUsingItem()) {
                            ClientBase.mc.options.keyUse.setDown(false);
                            if (prevSlot != -1) {
                                ClientBase.mc.player.getInventory().selected = prevSlot;
                            }
                            eatingSlot = -1;
                            prevSlot = -1;
                            return bb.isHealthCritical() ? BTNode.Status.RUNNING : BTNode.Status.SUCCESS;
                        }
                        return BTNode.Status.RUNNING;
                    }

                    int slot = findFoodSlot(enemyNearby);
                    if (slot == -1) return BTNode.Status.FAILURE;

                    prevSlot = ClientBase.mc.player.getInventory().selected;
                    ClientBase.mc.player.getInventory().selected = slot;
                    eatingSlot = slot;
                    ClientBase.mc.options.keyUse.setDown(true);
                    bb.log("CRITICAL HP! Eating (slot " + slot + ")");
                    return BTNode.Status.RUNNING;
                })
        );
    }

    public static void resetEatingState() {
        if (eatingSlot != -1) {
            ClientBase.mc.options.keyUse.setDown(false);
            if (prevSlot != -1 && ClientBase.mc.player != null) {
                ClientBase.mc.player.getInventory().selected = prevSlot;
            }
            eatingSlot = -1;
            prevSlot = -1;
        }
    }

    private static int findFoodSlot() {
        return findFoodSlot(false);
    }

    /**
     * Void self-rescue: when in void with no blocks to bridge.
     * Stops all movement and disconnects to save the player.
     */
    public static BTNode voidRescue() {
        return new Sequence(
                new Condition(bb -> bb.isVoidRescue),
                new Action(bb -> {
                    Blackboard.clearMovement();
                    BaritoneBridge.cancel();
                    bb.log("VOID! No blocks to bridge — disconnecting");
                    ClientBase.mc.player.connection.getConnection().disconnect(
                            Component.literal("§c[AI] Void rescue — disconnected to save"));
                    return BTNode.Status.SUCCESS;
                })
        );
    }

    private static int findFoodSlot(boolean enemyNearby) {
        Item[] foodPriority = {
                Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
                Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
                Items.COOKED_COD, Items.COOKED_SALMON, Items.COOKED_RABBIT,
                Items.BREAD, Items.BAKED_POTATO, Items.MUSHROOM_STEW,
                Items.APPLE, Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON,
                Items.COD, Items.SALMON, Items.RABBIT, Items.POTATO, Items.CARROT
        };
        for (Item food : foodPriority) {
            // When enemy is nearby, only allow enchanted golden apple
            if (enemyNearby && food != Items.ENCHANTED_GOLDEN_APPLE) continue;
            int slot = ItemUtil.getSlot(food);
            if (slot != -1) return slot;
        }
        return -1;
    }
}
