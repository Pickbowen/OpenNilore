package shit.nilore.modules.impl.misc.ai.path;

import java.util.ArrayList;
import java.util.List;

public class Path {
    private final List<BetterBlockPos> positions;
    private final double cost;
    private final boolean[] needsBridge;

    public Path(List<BetterBlockPos> positions, double cost) {
        this(positions, cost, null);
    }

    public Path(List<BetterBlockPos> positions, double cost, boolean[] needsBridge) {
        this.positions = positions;
        this.cost = cost;
        this.needsBridge = needsBridge;
    }

    public List<BetterBlockPos> positions() { return positions; }
    public int length() { return positions.size(); }
    public double getCost() { return cost; }
    public BetterBlockPos get(int index) { return positions.get(index); }
    public BetterBlockPos getDest() { return positions.get(positions.size() - 1); }
    public boolean needsBridgeAt(int index) { return needsBridge != null && index >= 0 && index < needsBridge.length && needsBridge[index]; }
    public boolean[] getNeedsBridge() { return needsBridge; }
}
