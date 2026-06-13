package shit.lizz.modules.impl.misc.ai.path.goal;

import net.minecraft.core.BlockPos;

public class GoalBlock implements Goal {
    private final int x, y, z;
    public GoalBlock(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public GoalBlock(BlockPos pos) { this(pos.getX(), pos.getY(), pos.getZ()); }
    @Override public boolean isInGoal(int x, int y, int z) { return x == this.x && y == this.y && z == this.z; }
    @Override public double heuristic(int x, int y, int z) { return Math.abs(x-this.x) + Math.abs(y-this.y) + Math.abs(z-this.z); }
}
