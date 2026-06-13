package shit.lizz.modules.impl.misc.ai.path;

import shit.lizz.modules.impl.misc.ai.path.goal.Goal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

public class Pathfinder {
    private static Goal lastGoal;
    private static long lastGoalTime;
    private static final long CACHE_MS = 800; // 40 ticks

    public static Path findPath(ClientLevel level, BlockPos playerPos, Goal goal) {
        long now = System.currentTimeMillis();
        if (goal != null && goal.equals(lastGoal) && now - lastGoalTime < CACHE_MS) {
            return null; // Skip re-pathing to same goal within cache window
        }
        lastGoal = goal;
        lastGoalTime = now;

        return AStarPathFinder.findPath(level, new BetterBlockPos(playerPos), goal);
    }

    public static void invalidateCache() {
        lastGoal = null;
    }

    public static void cancel() {
        invalidateCache();
    }
}
