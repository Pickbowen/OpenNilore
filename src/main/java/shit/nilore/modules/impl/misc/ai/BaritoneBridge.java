package shit.nilore.modules.impl.misc.ai;

import net.minecraft.core.BlockPos;
import shit.nilore.modules.impl.misc.ai.path.BetterBlockPos;
import shit.nilore.modules.impl.misc.ai.path.Path;
import shit.nilore.modules.impl.misc.ai.path.PathExecutor;
import shit.nilore.modules.impl.misc.ai.path.Pathfinder;
import shit.nilore.modules.impl.misc.ai.path.goal.Goal;
import shit.nilore.modules.impl.misc.ai.path.goal.GoalBlock;
import shit.nilore.modules.impl.misc.ai.path.goal.GoalNear;
import shit.nilore.modules.impl.misc.ai.path.goal.GoalRunAway;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between the AI task system and the local pathfinding implementation.
 * All pathfinding requests go through this class.
 */
public class BaritoneBridge {

    private static PathExecutor currentExecutor = null;
    private static Path currentPath = null;
    private static boolean paused = false;

    public static boolean isAvailable() {
        return true; // Self-contained, always available
    }

    public static boolean setGoalAndPath(String goalType, BlockPos pos, int range) {
        Goal goal = switch (goalType) {
            case "block" -> new GoalBlock(pos);
            case "near" -> new GoalNear(pos, range);
            case "runaway" -> new GoalRunAway(range, pos);
            default -> null;
        };
        if (goal == null) return false;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;

        Path path = Pathfinder.findPath(mc.level, mc.player.blockPosition(), goal);
        if (path == null) return false;

        currentPath = path;
        currentExecutor = new PathExecutor(path);
        paused = false; // New path starts unpaused
        return true;
    }

    public static void cancel() {
        Pathfinder.cancel();
        if (currentExecutor != null) {
            // Clear movement keys
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                Blackboard.clearMovement();
            }
        }
        currentExecutor = null;
        currentPath = null;
    }

    /**
     * Tick the current path executor. Call this from the behavior tree tick.
     * Returns true if currently pathing (not done yet).
     */
    public static boolean tick() {
        if (currentExecutor == null || paused) return currentExecutor != null;
        boolean done = currentExecutor.onTick();
        if (done) {
            currentExecutor = null;
            currentPath = null;
            return false;
        }
        return true;
    }

    public static void pause() { paused = true; }
    public static void resume() { paused = false; }
    public static boolean isPaused() { return paused; }

    public static boolean isPathing() {
        return currentExecutor != null && !currentExecutor.isComplete() && !currentExecutor.isFailed();
    }

    public static boolean isPathFailed() {
        return currentExecutor != null && currentExecutor.isFailed();
    }

    /**
     * Check if the current path has bridge segments near the player's current position.
     * Returns true if any path node within LOOKAHEAD steps of the current position needs bridging.
     */
    public static boolean needsBridgeNearby() {
        if (currentExecutor == null || currentPath == null) return false;
        if (currentExecutor.isComplete() || currentExecutor.isFailed()) return false;
        // Check even when paused — scaffold must stay active over void
        int pos = currentExecutor.getPathPosition();
        int start = Math.max(0, pos - 1);
        int end = Math.min(pos + 5, currentPath.length());
        for (int i = start; i < end; i++) {
            if (currentPath.needsBridgeAt(i)) return true;
        }
        return false;
    }

    /**
     * Check if the current path has ascending segments near the player.
     * Returns true if any upcoming path node is higher than the previous one.
     */
    public static boolean needsAscendingNearby() {
        if (currentExecutor == null || currentPath == null) return false;
        if (currentExecutor.isComplete() || currentExecutor.isFailed()) return false;
        int pos = currentExecutor.getPathPosition();
        int end = Math.min(pos + 5, currentPath.length());
        for (int i = Math.max(1, pos); i < end; i++) {
            BetterBlockPos prev = currentPath.get(i - 1);
            BetterBlockPos curr = currentPath.get(i);
            if (curr.y > prev.y) return true;
        }
        return false;
    }

    public static List<BlockPos> getCurrentPathPositions() {
        if (currentPath == null) return new ArrayList<>();
        List<BlockPos> result = new ArrayList<>(currentPath.length());
        for (int i = 0; i < currentPath.length(); i++) {
            result.add(currentPath.get(i));
        }
        return result;
    }

    public static void configureForSkyWars() {
        // No external configuration needed for self-contained pathfinding
    }

    public static void restoreDefaults() {
        // No external configuration to restore
    }
}
