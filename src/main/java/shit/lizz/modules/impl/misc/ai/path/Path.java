package shit.lizz.modules.impl.misc.ai.path;

import java.util.ArrayList;
import java.util.List;

public class Path {
    private final List<BetterBlockPos> positions;
    private final double cost;

    public Path(List<BetterBlockPos> positions, double cost) {
        this.positions = positions;
        this.cost = cost;
    }

    public List<BetterBlockPos> positions() { return positions; }
    public int length() { return positions.size(); }
    public double getCost() { return cost; }
    public BetterBlockPos get(int index) { return positions.get(index); }
    public BetterBlockPos getDest() { return positions.get(positions.size() - 1); }
}
