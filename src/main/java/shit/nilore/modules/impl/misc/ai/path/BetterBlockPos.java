package shit.nilore.modules.impl.misc.ai.path;

import net.minecraft.core.BlockPos;

public class BetterBlockPos extends BlockPos {
    public final int x, y, z;

    public BetterBlockPos(int x, int y, int z) {
        super(x, y, z);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BetterBlockPos(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long longHash(int x, int y, int z) {
        long hash = x;
        hash = 3457689L * hash + y;
        hash = 8734625L * hash + z;
        return hash;
    }

    public long longHash() {
        return longHash(x, y, z);
    }

    @Override public BetterBlockPos above() { return new BetterBlockPos(x, y + 1, z); }
    @Override public BetterBlockPos below() { return new BetterBlockPos(x, y - 1, z); }
    @Override public BetterBlockPos north() { return new BetterBlockPos(x, y, z - 1); }
    @Override public BetterBlockPos south() { return new BetterBlockPos(x, y, z + 1); }
    @Override public BetterBlockPos east()  { return new BetterBlockPos(x + 1, y, z); }
    @Override public BetterBlockPos west()  { return new BetterBlockPos(x - 1, y, z); }
}
