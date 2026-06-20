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
            Blackboard.clearMovement();
            mc.options.keyShift.setDown(false);
            mc.options.keyUse.setDown(false);
        }
        SurvivalTasks.resetEatingState();
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
        BridgeTasks.lastGotoTarget = null;
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
     * 行为树结构（扁平 Selector，优先级从高到低）:
     *
     * [1] 自救 — 吃食物（最高优先级）
     * [2] 搜刮 — 找箱子（核心循环：装备 > 打架）
     * [3] 背包整理 — 开完箱子后立刻整理
     * [4] 打人 — 主动出击（有装备了再打）
     * [5] goto 命令导航
     * [6] 空闲 — 随便逛
     */
    private BTNode buildTree() {
        return new Selector(
                // [1] 自救
                new Selector(
                        SurvivalTasks.criticalEat(),
                        SurvivalTasks.eatFood()
                ),
                // [2] 搜刮
                new Selector(
                        LootTasks.waitForChestStealer(),
                        LootTasks.openChest(),
                        LootTasks.markChestDone(),
                        LootTasks.navigateToChest(),
                        LootTasks.pickupItems()
                ),
                LootTasks.ensureChestStealer(),
                // [3] 背包整理（开完箱子后立刻整理）
                InventoryTasks.ensureInvManager(),
                new Selector(
                        InventoryTasks.openInventory(),
                        InventoryTasks.waitForSorting()
                ),
                // [4] 打人
                new Selector(
                        CombatTasks.meleeCombat(),
                        CombatTasks.trackEnemy()
                ),
                // [5] goto 命令
                BridgeTasks.gotoCommand(),
                // [6] 空闲
                ExploreTasks.wander(),
                ExploreTasks.stopMovement()
        );
    }

    private void sendMsg(String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§e[AI] §r" + msg), true);
        }
    }
}
