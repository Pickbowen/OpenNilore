package shit.lizz.modules.impl.misc.ai.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import shit.lizz.ClientBase;
import shit.lizz.modules.impl.misc.ai.BaritoneBridge;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.misc.ai.btree.*;
import shit.lizz.modules.impl.player.ChestStealer;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.rotation.Rotation;

public class LootTasks {

    private static int lootingTicks = 0;
    private static BlockPos lootingChest = null;
    private static BlockPos lastChestTarget = null;

    public static BTNode ensureChestStealer() {
        return new Action(bb -> {
            if (ChestStealer.INSTANCE != null && !ChestStealer.INSTANCE.isEnabled()) {
                ChestStealer.INSTANCE.setEnabled(true);
            }
            return BTNode.Status.FAILURE;
        });
    }

    public static BTNode waitForChestStealer() {
        return new Sequence(
                new Condition(bb -> bb.isContainerOpen()),
                new Action(bb -> {
                    lootingTicks++;
                    if (lootingTicks > 200) {
                        ClientBase.mc.player.closeContainer();
                        if (lootingChest != null) {
                            bb.markChestOpened(lootingChest);
                        }
                        lootingChest = null;
                        bb.navigatingToChest = null;
                        lastChestTarget = null;
                        lootingTicks = 0;
                        return BTNode.Status.SUCCESS;
                    }
                    return BTNode.Status.RUNNING;
                })
        );
    }

    public static BTNode openChest() {
        return new Sequence(
                new Condition(bb -> bb.autoLoot && bb.nearestChest != null),
                new Condition(bb -> bb.nearestChestDist <= Blackboard.CHEST_INTERACT_RANGE),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    BlockPos chest = bb.nearestChest;

                    BaritoneBridge.cancel();

                    Vec3 chestCenter = new Vec3(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
                    Vec3 eyePos = ClientBase.mc.player.position().add(0, ClientBase.mc.player.getEyeHeight(), 0);
                    Rotation rot = RotationUtil.rotationTo(eyePos, chestCenter);
                    Blackboard.smoothYaw(rot.getYaw(), 30f);
                    Blackboard.smoothPitch(rot.getPitch(), 30f);

                    if (!chest.equals(lootingChest)) {
                        lootingChest = chest;
                        lootingTicks = 0;
                    }
                    lootingTicks++;

                    if (lootingTicks == 1 || lootingTicks % 5 == 0) {
                        Vec3 hitVec = new Vec3(chest.getX() + 0.5, chest.getY() + 1.0, chest.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, chest, false);
                        ClientBase.mc.gameMode.useItemOn(ClientBase.mc.player, InteractionHand.MAIN_HAND, hitResult);
                        ClientBase.mc.player.swing(InteractionHand.MAIN_HAND);
                    }

                    if (bb.isContainerOpen()) {
                        bb.log("Opening chest " + chest.toShortString());
                        lootingTicks = 0;
                        return BTNode.Status.RUNNING;
                    }

                    if (lootingTicks > 60) {
                        bb.markChestOpened(chest);
                        bb.log("Failed to open chest, skipping");
                        lootingChest = null;
                        bb.navigatingToChest = null;
                        lastChestTarget = null;
                        lootingTicks = 0;
                        return BTNode.Status.FAILURE;
                    }

                    return BTNode.Status.RUNNING;
                })
        );
    }

    public static BTNode markChestDone() {
        return new Action(bb -> {
            if (lootingChest != null && !bb.isContainerOpen() && lootingTicks > 0) {
                bb.markChestOpened(lootingChest);
                bb.log("Chest looted: " + lootingChest.toShortString());
                lootingChest = null;
                bb.navigatingToChest = null;
                lastChestTarget = null;
                lootingTicks = 0;
                return BTNode.Status.SUCCESS;
            }
            return BTNode.Status.FAILURE;
        });
    }

    public static BTNode navigateToChest() {
        return new Sequence(
                new Condition(bb -> bb.autoLoot && bb.nearestChest != null),
                new Condition(bb -> bb.nearestChestDist > Blackboard.CHEST_INTERACT_RANGE),
                new Condition(bb -> !bb.isContainerOpen()),
                new Action(bb -> {
                    BlockPos chest = bb.nearestChest;
                    bb.navigatingToChest = chest;

                    if (!BaritoneBridge.isPathing() || !chest.equals(lastChestTarget)) {
                        BaritoneBridge.setGoalAndPath("block", chest, 0);
                        lastChestTarget = chest;
                    }

                    return bb.nearestChestDist > Blackboard.CHEST_INTERACT_RANGE ? BTNode.Status.RUNNING : BTNode.Status.SUCCESS;
                })
        );
    }

    public static BTNode pickupItems() {
        return new Sequence(
                new Condition(bb -> bb.autoLoot && bb.nearestItem != null && bb.nearestItemDist <= bb.itemRange),
                new Condition(bb -> bb.nearestEnemy == null || bb.nearestEnemyDist > 8),
                new Condition(bb -> !bb.isContainerOpen()),
                new Condition(bb -> !BaritoneBridge.isPathing()),
                new Action(bb -> {
                    ItemEntity item = bb.nearestItem;
                    double dx = item.getX() - ClientBase.mc.player.getX();
                    double dz = item.getZ() - ClientBase.mc.player.getZ();
                    Blackboard.moveToward(dx, dz);
                    return BTNode.Status.RUNNING;
                })
        );
    }
}
