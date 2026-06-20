package shit.nilore.modules.impl.misc.ai.path;

public class PathNode {
    public final int x, y, z;
    public final double estimatedCostToGoal;
    public double cost = 0;
    public double combinedCost;
    public PathNode previous;
    public int heapPosition = -1;

    public PathNode(int x, int y, int z, double estimatedCostToGoal) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.estimatedCostToGoal = estimatedCostToGoal;
        this.combinedCost = estimatedCostToGoal;
    }
}
