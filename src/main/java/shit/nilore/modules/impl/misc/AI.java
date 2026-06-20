package shit.nilore.modules.impl.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.level.block.Blocks;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.ChatEvent;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.RotationEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.misc.ai.AIRender;
import shit.nilore.modules.impl.misc.ai.BaritoneBridge;
import shit.nilore.modules.impl.misc.ai.Blackboard;
import shit.nilore.modules.impl.misc.ai.MovementHelper;
import shit.nilore.modules.impl.misc.ai.btree.*;
import shit.nilore.modules.impl.misc.ai.tasks.*;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.rotation.RotationHandler;

public class AI extends Module {

    public final NumberSetting enemyRange = new NumberSetting("Enemy Range", 20, 4, 50, 1);
    public final NumberSetting lowHealth = new NumberSetting("Low Health", 8, 2, 18, 1);
    public final BooleanSetting autoEat = new BooleanSetting("Auto Eat", true);
    public final BooleanSetting autoLoot = new BooleanSetting("Auto Loot", true);
    public final BooleanSetting log = new BooleanSetting("Log", true);

    private Blackboard blackboard;
    private BTNode behaviorTree;
    private BlockPos gotoTarget = null;
    private boolean scaffoldModeOverridden = false;

    public AI() {
        super("AI", Category.MISC);
    }

    @Override
    protected void onEnable() {
        blackboard = new Blackboard();
        behaviorTree = buildTree();
        sendMsg("§aEnabled - SkyWars Bot");
    }

    @Override
    protected void onDisable() {
        if (mc.player != null) {
            MovementHelper.clearMovement();
            mc.options.keyShift.setDown(false);
            mc.options.keyUse.setDown(false);
        }

        SurvivalTasks.reset();
        LootTasks.reset();
        CombatTasks.reset();
        InventoryTasks.reset();
        ExploreTasks.reset();
        BridgeTasks.reset();

        try {
            if (Scaffold.INSTANCE != null) {
                if (scaffoldModeOverridden) {
                    Scaffold.INSTANCE.mode.setValue("Telly Bridge");
                    scaffoldModeOverridden = false;
                }
                Scaffold.INSTANCE.setEnabled(false);
            }
        } catch (Exception ignored) {}

        BaritoneBridge.restoreDefaults();
        BaritoneBridge.cancel();
        gotoTarget = null;
        blackboard = null;
        behaviorTree = null;
        sendMsg("§cDisabled");
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (blackboard == null) return;
        AIRender.render(blackboard, event.poseStack());
    }

    @EventTarget
    public void onRotation(RotationEvent event) {
        if (blackboard == null) return;
        if (RotationHandler.isRotating) return;
        if (BaritoneBridge.isPathing()) return;
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (blackboard == null) return;
        if (!event.isPre()) return;
        if (RotationHandler.isRotating) return;
        if (BaritoneBridge.isPathing()) return;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (blackboard == null || !event.isIncoming()) return;
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundBlockEventPacket bep) {
            if ((bep.getBlock() == Blocks.CHEST || bep.getBlock() == Blocks.TRAPPED_CHEST)
                    && bep.getB0() == 1 && bep.getB1() == 1) {
                blackboard.markChestOpened(bep.getPos());
            }
        }
    }

    @EventTarget
    public void onChat(ChatEvent event) {
        String msg = event.getMessage();
        if (msg == null || !msg.startsWith("#")) return;

        String[] parts = msg.substring(1).split(" ");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "goto" -> {
                if (parts.length >= 2 && parts[1].equalsIgnoreCase("stop")) {
                    gotoTarget = null;
                    BridgeTasks.lastGotoTarget = null;
                    BaritoneBridge.cancel();
                    sendMsg("§eGoto stopped");
                } else if (parts.length >= 4) {
                    try {
                        int x = Integer.parseInt(parts[1]);
                        int y = Integer.parseInt(parts[2]);
                        int z = Integer.parseInt(parts[3]);
                        gotoTarget = new BlockPos(x, y, z);
                        BaritoneBridge.cancel();
                        sendMsg("§aNavigating to " + x + " " + y + " " + z);
                    } catch (NumberFormatException e) {
                        sendMsg("§cUsage: #goto <x> <y> <z> | #goto stop");
                    }
                } else {
                    sendMsg("§cUsage: #goto <x> <y> <z> | #goto stop");
                }
            }
            case "stop" -> {
                gotoTarget = null;
                BridgeTasks.lastGotoTarget = null;
                BaritoneBridge.cancel();
                sendMsg("§eStopped");
            }
            case "status" -> {
                if (blackboard != null) {
                    sendMsg("§eHP: " + (int) blackboard.health + " Blocks: " + blackboard.blockCount
                            + " Enemies: " + blackboard.nearbyEnemies.size()
                            + " Chests: " + blackboard.nearbyChests.size());
                }
            }
            default -> sendMsg("§cCommands: #goto <x> <y> <z> | #stop | #status");
        }
        event.setCancelled(true);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (blackboard == null || behaviorTree == null) return;

        syncSettings();
        blackboard.update();
        BaritoneBridge.tick();

        if (Scaffold.INSTANCE != null) {
            boolean shouldScaffold = BaritoneBridge.needsBridgeNearby() && blackboard.hasBlocks();
            if (Scaffold.INSTANCE.isEnabled() != shouldScaffold) {
                Scaffold.INSTANCE.setEnabled(shouldScaffold);
            }
            boolean ascendingBridge = shouldScaffold && BaritoneBridge.needsAscendingNearby();
            if (ascendingBridge && !scaffoldModeOverridden) {
                Scaffold.INSTANCE.mode.setValue("Normal");
                scaffoldModeOverridden = true;
            } else if (!ascendingBridge && scaffoldModeOverridden) {
                Scaffold.INSTANCE.mode.setValue("Telly Bridge");
                scaffoldModeOverridden = false;
            }
            Scaffold.INSTANCE.forceJump = ascendingBridge;
        }

        behaviorTree.tick(blackboard);
    }

    private void syncSettings() {
        blackboard.log = log.getValue();
        blackboard.enemyRange = enemyRange.getValue().doubleValue();
        blackboard.lowHealthThreshold = lowHealth.getValue().floatValue();
        blackboard.autoEat = autoEat.getValue();
        blackboard.autoLoot = autoLoot.getValue();
        blackboard.autoInv = true;
        blackboard.wander = true;
        blackboard.gotoTarget = gotoTarget;
    }

    /**
     * 行为树结构（优先级从高到低）:
     *
     * Selector:
     *   [1] 生存 — 低血量吃食物（最高优先级）
     *   [2] 搜刮 — 启用 ChestStealer → 搜刮逻辑 → 关闭 ChestStealer
     *       敌人凑上来(≤5格)时：关箱子 → FAILURE → fall through 到战斗
     *   [3] 战斗 — 近战/追踪 → 退出时关闭 KillAura
     *   [4] goto 命令导航
     *   [5] 背包整理 — 启用 InvManager → 整理逻辑 → 关闭 InvManager
     *   [6] 空闲 — 随机游荡
     */
    private BTNode buildTree() {
        return new Selector(
                // [1] 生存
                new Selector(
                        SurvivalTasks.criticalEat(),
                        SurvivalTasks.eatFood()
                ),

                // [2] 搜刮（模块生命周期：enable → loot → disable）
                new Sequence(
                        LootTasks.enableChestStealer(),
                        new Sequence(
                                // 敌人中断：近距离敌人出现时关箱子退出搜刮
                                new Action(bb -> {
                                    if (bb.nearestEnemy != null && bb.nearestEnemyDist <= 5) {
                                        if (bb.isContainerOpen()) {
                                            mc.player.closeContainer();
                                        }
                                        return BTNode.Status.FAILURE;
                                    }
                                    return BTNode.Status.SUCCESS;
                                }),
                                new Selector(
                                        LootTasks.waitForChestStealer(),
                                        LootTasks.openChest(),
                                        LootTasks.markChestDone(),
                                        LootTasks.navigateToChest(),
                                        LootTasks.pickupItems()
                                )
                        ),
                        LootTasks.disableChestStealer()
                ),

                // [3] 战斗
                new Selector(
                        // 有敌人 → 追踪/近战 → 战斗结束时清理
                        new Sequence(
                                new Selector(
                                        CombatTasks.meleeCombat(),
                                        CombatTasks.trackEnemy()
                                ),
                                CombatTasks.disableKillAura()
                        ),
                        // 无敌人 → 仅清理残留战斗状态
                        CombatTasks.disableKillAura()
                ),

                // [4] goto 命令
                BridgeTasks.gotoCommand(),

                // [5] 背包整理（模块生命周期：enable → sort → disable）
                new Sequence(
                        InventoryTasks.enableInvManager(),
                        new Selector(
                                InventoryTasks.openInventory(),
                                InventoryTasks.waitForSorting()
                        ),
                        InventoryTasks.disableInvManager()
                ),

                // [6] 空闲
                new Selector(
                        ExploreTasks.wander(),
                        ExploreTasks.stopMovement()
                )
        );
    }

    private void sendMsg(String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§e[AI] §r" + msg), true);
        }
    }
}
