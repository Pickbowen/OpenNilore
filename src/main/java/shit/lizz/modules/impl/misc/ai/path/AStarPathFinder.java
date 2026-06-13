package shit.lizz.modules.impl.misc.ai.path;

import shit.lizz.modules.impl.misc.ai.path.goal.Goal;
import net.minecraft.client.multiplayer.ClientLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AStarPathFinder {
    private static final long TIMEOUT_MS = 200;
    private static final int MAX_NODES = 1000;

    public static Path findPath(ClientLevel level, BetterBlockPos start, Goal goal) {
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        Long2ObjectOpenHashMap<PathNode> map = new Long2ObjectOpenHashMap<>();

        PathNode startNode = new PathNode(start.x, start.y, start.z, goal.heuristic(start.x, start.y, start.z));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        map.put(BetterBlockPos.longHash(start.x, start.y, start.z), startNode);
        openSet.insert(startNode);

        PathNode bestNode = startNode;
        double bestHeuristic = startNode.estimatedCostToGoal;
        long startTime = System.currentTimeMillis();
        int nodesExpanded = 0;

        // Movement offsets: dx, dy, dz
        int[][] moves = {
            {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0},  // flat NESW
            {0, 1, -1}, {0, 1, 1}, {-1, 1, 0}, {1, 1, 0},   // ascend
            {0, -1, -1}, {0, -1, 1}, {-1, -1, 0}, {1, -1, 0}, // descend
            {0, 1, 0},  // pillar up
            {-1, 0, -1}, {1, 0, -1}, {-1, 0, 1}, {1, 0, 1}, // diagonal
        };

        while (!openSet.isEmpty()) {
            if (nodesExpanded % 64 == 0 && System.currentTimeMillis() - startTime > TIMEOUT_MS) break;
            if (nodesExpanded > MAX_NODES) break;

            PathNode current = openSet.removeLowest();
            nodesExpanded++;

            if (goal.isInGoal(current.x, current.y, current.z)) {
                return reconstructPath(current);
            }

            double h = current.estimatedCostToGoal;
            if (h < bestHeuristic) {
                bestHeuristic = h;
                bestNode = current;
            }

            for (int[] move : moves) {
                int nx = current.x + move[0];
                int ny = current.y + move[1];
                int nz = current.z + move[2];

                double moveCost = calculateMoveCost(level, current.x, current.y, current.z, nx, ny, nz);
                if (moveCost >= ActionCosts.COST_INF) continue;

                double newCost = current.cost + moveCost;
                long hash = BetterBlockPos.longHash(nx, ny, nz);
                PathNode existing = map.get(hash);

                if (existing == null || newCost < existing.cost) {
                    if (existing == null) {
                        existing = new PathNode(nx, ny, nz, goal.heuristic(nx, ny, nz));
                        map.put(hash, existing);
                    }
                    existing.cost = newCost;
                    existing.combinedCost = newCost + existing.estimatedCostToGoal;
                    existing.previous = current;
                    if (existing.heapPosition >= 0) {
                        openSet.update(existing);
                    } else {
                        openSet.insert(existing);
                    }
                }
            }
        }

        // Return best partial path if goal not reached
        if (bestNode != startNode) {
            return reconstructPath(bestNode);
        }
        return null;
    }

    private static double calculateMoveCost(ClientLevel level, int x, int y, int z, int nx, int ny, int nz) {
        int dx = nx - x;
        int dy = ny - y;
        int dz = nz - z;

        // Check destination is passable
        if (!MovementHelper.canWalkThrough(level, nx, ny, nz)) return ActionCosts.COST_INF;
        // Check above destination (player is 2 blocks tall)
        if (!MovementHelper.canWalkThrough(level, nx, ny + 1, nz)) return ActionCosts.COST_INF;
        // Check standing surface
        if (!MovementHelper.canWalkOn(level, nx, ny - 1, nz)) {
            // Can bridge if we have blocks
            // For simplicity, assume we can bridge
            return ActionCosts.WALK_ONE_BLOCK_COST + ActionCosts.PLACE_ONE_BLOCK_COST;
        }

        // Avoid dangerous blocks
        if (MovementHelper.avoidWalkingInto(level, nx, ny, nz)) return ActionCosts.COST_INF;

        if (dy == 0) {
            // Flat move
            if (dx != 0 && dz != 0) {
                return ActionCosts.SPRINT_ONE_BLOCK_COST * 1.414; // diagonal
            }
            return ActionCosts.SPRINT_ONE_BLOCK_COST;
        } else if (dy == 1) {
            // Ascend
            return ActionCosts.SPRINT_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST;
        } else if (dy == -1) {
            // Descend
            return ActionCosts.WALK_OFF_BLOCK_COST;
        }
        return ActionCosts.COST_INF;
    }

    private static Path reconstructPath(PathNode end) {
        List<BetterBlockPos> positions = new ArrayList<>();
        PathNode current = end;
        while (current != null) {
            positions.add(new BetterBlockPos(current.x, current.y, current.z));
            current = current.previous;
        }
        Collections.reverse(positions);
        return new Path(positions, end.cost);
    }
}
