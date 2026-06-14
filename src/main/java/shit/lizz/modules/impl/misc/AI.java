package shit.lizz.modules.impl.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.level.block.Blocks;
import shit.lizz.event.EventTarget;
import shit.lizz.event.impl.ChatEvent;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.event.impl.RotationEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.misc.ai.AIRender;
import shit.lizz.modules.impl.misc.ai.BaritoneBridge;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.misc.ai.btree.*;
import shit.lizz.modules.impl.misc.ai.tasks.*;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.rotation.RotationHandler;

public class AI extends Module {

    public final NumberSetting enemyRange = new NumberSetting("Enemy Range", 20, 4, 50, 1);
    public final NumberSetting chestRange = new NumberSetting("Chest Range", 8, 3, 20, 1);
    public final NumberSetting lowHealth = new NumberSetting("Low Health", 8, 2, 18, 1);
    public final BooleanSetting autoEat = new BooleanSetting("Auto Eat", true);
    public final BooleanSetting autoLoot = new BooleanSetting("Auto Loot", true);
    public final BooleanSetting log = new BooleanSetting("Log", true);

    private Blackboard blackboard;
    private BTNode behaviorTree;
    private int tickCounter;
    private int nextRunTick;
    private int runCount;
    private BlockPos gotoTarget = null;

    public AI() {
        super("AI", Category.MISC);
    }

    @Override
    protected void onEnable() {
        blackboard = new Blackboard();
        behaviorTree = buildTree();
        tickCounter = 0;
        nextRunTick = 0;
        runCount = 0;

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
            if (Scaffold.INSTANCE != null) Scaffold.INSTANCE.setEnabled(false);
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
        // Don't override rotation when baritone is handling movement
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

        // Bug 8 fix: Update awareness every tick for responsive combat
        syncSettings();
        blackboard.update();

        // Tick path executor every tick for smooth movement
        BaritoneBridge.tick();

        // Scaffold auto: enable when pathing, disable when not
        if (Scaffold.INSTANCE != null) {
            boolean shouldScaffold = BaritoneBridge.needsBridgeNearby() && blackboard.hasBlocks();
            if (Scaffold.INSTANCE.isEnabled() != shouldScaffold) {
                Scaffold.INSTANCE.setEnabled(shouldScaffold);
            }
        }

        // Tick behavior tree at intervals (every 2-3 ticks)
        tickCounter++;
        if (tickCounter < nextRunTick) return;
        int interval = (runCount % 2 == 0) ? 2 : 3;
        nextRunTick = tickCounter + interval;
        runCount++;

        behaviorTree.tick(blackboard);
    }

    private void syncSettings() {
        blackboard.log = log.getValue();
        blackboard.enemyRange = enemyRange.getValue().doubleValue();
        blackboard.chestRange = chestRange.getValue().doubleValue();
        blackboard.lowHealthThreshold = lowHealth.getValue().floatValue();
        blackboard.autoEat = autoEat.getValue();
        blackboard.autoLoot = autoLoot.getValue();
        blackboard.autoInv = true;
        blackboard.wander = true;
        blackboard.gotoTarget = gotoTarget;
    }

    private BTNode buildTree() {
        return new Selector(
                // [0] 虚空自救 — 绝对最高优先级
                SurvivalTasks.voidRescue(),
                // [1] 自救 — 吃食物
                new Selector(
                        SurvivalTasks.criticalEat(),
                        SurvivalTasks.eatFood()
                ),
                // [2] 打人 — 主动出击
                new Selector(
                        CombatTasks.meleeCombat(),
                        CombatTasks.trackEnemy()
                ),
                // [3] 搜刮 — 找箱子
                new Selector(
                        LootTasks.waitForChestStealer(),
                        LootTasks.openChest(),
                        LootTasks.markChestDone(),
                        LootTasks.navigateToChest(),
                        LootTasks.pickupItems()
                ),
                LootTasks.ensureChestStealer(),
                // [4] goto 命令
                BridgeTasks.gotoCommand(),
                // [5] 背包整理
                InventoryTasks.ensureInvManager(),
                new Selector(
                        InventoryTasks.openInventory(),
                        InventoryTasks.waitForSorting()
                ),
                // [6] 空闲 — 随便逛
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
