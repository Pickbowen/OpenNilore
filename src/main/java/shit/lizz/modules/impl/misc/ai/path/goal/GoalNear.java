package shit.lizz.modules.impl.misc.ai.path.goal;

import net.minecraft.core.BlockPos;

public class GoalNear implements Goal {
    private final int x, y, z, range;
    public GoalNear(BlockPos pos, int range) { this.x = pos.getX(); this.y = pos.getY(); this.z = pos.getZ(); this.range = range; }
    @Override public boolean isInGoal(int x, int y, int z) { return Math.abs(x-this.x) <= range && Math.abs(y-this.y) <= range && Math.abs(z-this.z) <= range; }
    @Override public double heuristic(int x, int y, int z) { return Math.max(0, Math.sqrt((x-this.x)*(x-this.x) + (y-this.y)*(y-this.y) + (z-this.z)*(z-this.z)) - range); }
}
