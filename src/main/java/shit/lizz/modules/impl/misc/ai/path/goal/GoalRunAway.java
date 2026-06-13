package shit.lizz.modules.impl.misc.ai.path.goal;

import net.minecraft.core.BlockPos;

public class GoalRunAway implements Goal {
    private final double distance;
    private final BlockPos[] positions;
    public GoalRunAway(double distance, BlockPos... positions) { this.distance = distance; this.positions = positions; }
    @Override public boolean isInGoal(int x, int y, int z) {
        for (BlockPos p : positions) {
            double d = Math.sqrt((x-p.getX())*(x-p.getX()) + (y-p.getY())*(y-p.getY()) + (z-p.getZ())*(z-p.getZ()));
            if (d < distance) return false;
        }
        return true;
    }
    @Override public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (BlockPos p : positions) {
            double d = Math.sqrt((x-p.getX())*(x-p.getX()) + (y-p.getY())*(y-p.getY()) + (z-p.getZ())*(z-p.getZ()));
            min = Math.min(min, d);
        }
        return Math.max(0, distance - min); // Bug 6 fix: non-negative heuristic
    }
}
