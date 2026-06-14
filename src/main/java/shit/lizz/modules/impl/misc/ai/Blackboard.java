package shit.lizz.modules.impl.misc.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import shit.lizz.ClientBase;
import shit.lizz.utils.game.ItemUtil;
import shit.lizz.utils.game.MovementUtil;

import java.util.*;
import java.util.stream.Collectors;

public class Blackboard extends ClientBase {

    public static final double CHEST_INTERACT_RANGE = 4.5;

    public int tickCount;
    public boolean log;

    // Player state
    public float health;
    public float maxHealth;
    public float hunger;
    public int blockCount;
    public boolean hasSword;
    public boolean hasFood;
    public boolean isFalling;
    public boolean isAboveVoid;
    public boolean isOnGround;
    public boolean isNearEdge;
    public boolean isVoidRescue;
    public BlockPos playerPos;

    // Environment
    public List<Player> nearbyEnemies = new ArrayList<>();
    public Player nearestEnemy;
    public double nearestEnemyDist;
    public List<BlockPos> nearbyChests = new ArrayList<>();
    public BlockPos nearestChest;
    public double nearestChestDist;
    public List<ItemEntity> nearbyItems = new ArrayList<>();
    public ItemEntity nearestItem;
    public double nearestItemDist;

    // Opened chests tracking
    public Set<BlockPos> openedChests = new HashSet<>();

    // Chest scan cache (Bug 5 fix)
    private int lastChestScanTick = -40;
    private List<BlockPos> cachedChests = new ArrayList<>();

    // Render targets (set by tasks, read by AIRender)
    public BlockPos renderTarget;
    public BlockPos renderBridgeTarget;
    public List<BlockPos> renderPath = new ArrayList<>();

    // Active chest navigation target
    public BlockPos navigatingToChest;

    // goto command
    public BlockPos gotoTarget;

    // Settings
    public double enemyRange = 20;
    public double chestRange = 8;
    public double itemRange = 6;
    public float lowHealthThreshold = 8;
    public boolean autoEat = true;
    public boolean autoLoot = true;
    public boolean autoInv = true;
    public boolean wander = true;

    public void update() {
        tickCount++;
        if (mc.player == null || mc.level == null) return;

        health = mc.player.getHealth();
        maxHealth = mc.player.getMaxHealth();
        hunger = mc.player.getFoodData().getFoodLevel();
        isOnGround = mc.player.onGround();
        isFalling = !isOnGround && mc.player.getDeltaMovement().y < -0.05;
        playerPos = mc.player.blockPosition();
        isAboveVoid = MovementUtil.isAboveVoid(playerPos.getX(), playerPos.getY(), playerPos.getZ());
        isNearEdge = isOnNearEdge(0.3f);

        // Void rescue: in void with no blocks or unable to bridge to safety
        isVoidRescue = isAboveVoid && (blockCount <= 0 || isSurroundedByHigherBlocks());

        blockCount = ItemUtil.countBlocks();
        hasSword = ItemUtil.getBestSword() != null;
        hasFood = ItemUtil.countFood() > 0;

        // Enemies
        nearbyEnemies = mc.level.players().stream()
                .filter(p -> p != mc.player)
                .filter(p -> p.isAlive() && !p.isSpectator())
                .filter(p -> mc.player.distanceTo(p) <= enemyRange)
                .sorted(Comparator.comparingDouble(p -> mc.player.distanceTo(p)))
                .collect(Collectors.toList());
        nearestEnemy = nearbyEnemies.isEmpty() ? null : nearbyEnemies.get(0);
        nearestEnemyDist = nearestEnemy != null ? mc.player.distanceTo(nearestEnemy) : Double.MAX_VALUE;

        // Chests (cached scan every 20 ticks — Bug 5 fix)
        if (tickCount - lastChestScanTick >= 20) {
            lastChestScanTick = tickCount;
            int r = (int) chestRange;
            cachedChests = new ArrayList<>();
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        mutablePos.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                        if (openedChests.contains(mutablePos)) continue;
                        BlockState state = mc.level.getBlockState(mutablePos);
                        if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock) {
                            cachedChests.add(mutablePos.immutable());
                        }
                    }
                }
            }
        }
        nearbyChests = cachedChests;
        nearbyChests.sort(Comparator.comparingDouble(pos -> pos.distSqr(playerPos)));
        nearestChest = nearbyChests.isEmpty() ? null : nearbyChests.get(0);
        nearestChestDist = nearestChest != null ? Math.sqrt(nearestChest.distSqr(playerPos)) : Double.MAX_VALUE;

        // Items
        AABB itemBox = new AABB(
                playerPos.getX() - itemRange, playerPos.getY() - itemRange, playerPos.getZ() - itemRange,
                playerPos.getX() + itemRange, playerPos.getY() + itemRange, playerPos.getZ() + itemRange
        );
        nearbyItems = mc.level.getEntitiesOfClass(ItemEntity.class, itemBox,
                item -> item.isAlive() && mc.player.distanceTo(item) <= itemRange);
        nearbyItems.sort(Comparator.comparingDouble(item -> mc.player.distanceTo(item)));
        nearestItem = nearbyItems.isEmpty() ? null : nearbyItems.get(0);
        nearestItemDist = nearestItem != null ? mc.player.distanceTo(nearestItem) : Double.MAX_VALUE;

        // Render path from baritone
        renderPath = BaritoneBridge.getCurrentPathPositions();
        renderTarget = navigatingToChest;
        renderBridgeTarget = null;
    }

    public void markChestOpened(BlockPos pos) {
        openedChests.add(pos);
    }

    public static boolean isOnNearEdge(float inset) {
        return !mc.level.getCollisions(mc.player,
                mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-inset, 0.0, -inset)
        ).iterator().hasNext();
    }

    public boolean isContainerOpen() {
        return mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen;
    }

    public boolean isHealthLow() {
        return health < lowHealthThreshold;
    }

    public boolean isHealthCritical() {
        return health < 6;
    }

    public boolean hasBlocks() {
        return blockCount > 0;
    }

    /**
     * Check if surrounding blocks are higher than the player (can't bridge to reach them).
     * Scans a 5-block horizontal radius for solid blocks above player Y.
     */
    private boolean isSurroundedByHigherBlocks() {
        if (mc.level == null || mc.player == null) return false;
        int px = playerPos.getX();
        int py = playerPos.getY();
        int pz = playerPos.getZ();
        int radius = 5;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x == 0 && z == 0) continue;
                // Check blocks above player level (2-5 blocks up)
                for (int dy = 2; dy <= 5; dy++) {
                    BlockState state = mc.level.getBlockState(new BlockPos(px + x, py + dy, pz + z));
                    if (state.isSolid()) return true;
                }
            }
        }
        return false;
    }

    public void log(String msg) {
        if (log && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§e[AI] §r" + msg), true);
        }
    }

    /**
     * Move directly toward a position without pathfinding.
     * Used for simple short-distance movement (item pickup).
     */
    public static void moveToward(double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            clearMovement();
            return;
        }
        float yaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
        smoothYaw(yaw, 30f);
        mc.options.keyUp.setDown(true);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
    }

    /**
     * Smoothly rotate yaw toward target. Sensitivity-aware, ~0.1s to complete.
     * Reference: baritone's calculateMouseMove approach.
     */
    public static void smoothYaw(float targetYaw, float maxStep) {
        float current = mc.player.getYRot();
        float diff = Mth.wrapDegrees(targetYaw - current);
        if (Math.abs(diff) < 0.5f) {
            mc.player.setYRot(targetYaw);
            return;
        }
        float step = Math.max(-maxStep, Math.min(diff, maxStep));
        float result = current + step;
        float sens = mc.options.sensitivity().get().floatValue();
        float scaled = sens * 0.6f + 0.2f;
        float gcd = scaled * scaled * scaled * 1.2f;
        if (gcd > 0) {
            result = result - result % gcd;
        }
        mc.player.setYRot(result);
    }

    /**
     * Smoothly rotate pitch toward target. Sensitivity-aware.
     */
    public static void smoothPitch(float targetPitch, float maxStep) {
        float current = mc.player.getXRot();
        float diff = Mth.clamp(targetPitch - current, -90f, 90f);
        if (Math.abs(diff) < 0.5f) {
            mc.player.setXRot(targetPitch);
            return;
        }
        float step = Math.max(-maxStep, Math.min(diff, maxStep));
        float result = current + step;
        float sens = mc.options.sensitivity().get().floatValue();
        float scaled = sens * 0.6f + 0.2f;
        float gcd = scaled * scaled * scaled * 1.2f;
        if (gcd > 0) {
            result = result - result % gcd;
        }
        mc.player.setXRot(Mth.clamp(result, -90f, 90f));
    }

    public static void clearMovement() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }
}
