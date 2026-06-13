package shit.lizz.modules.impl.misc.ai.path.goal;

public interface Goal {
    boolean isInGoal(int x, int y, int z);
    double heuristic(int x, int y, int z);
}
