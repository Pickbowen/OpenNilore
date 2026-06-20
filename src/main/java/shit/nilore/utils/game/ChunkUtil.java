package shit.nilore.utils.game;

import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import shit.nilore.ClientBase;

public final class ChunkUtil extends ClientBase {
    public static Stream<BlockEntity> getLoadedBlockEntities() {
        return getLoadedChunks().flatMap(chunk -> chunk.getBlockEntities().values().stream());
    }

    public static Stream<LevelChunk> getLoadedChunks() {
        if (mc.player == null || mc.level == null) {
            return Stream.empty();
        }
        final int radius = Math.max(2, mc.options.getEffectiveRenderDistance()) + 3;
        final int side = radius * 2 + 1;
        final ChunkPos center = mc.player.chunkPosition();
        final ChunkPos min = new ChunkPos(center.x - radius, center.z - radius);
        final ChunkPos max = new ChunkPos(center.x + radius, center.z + radius);
        return Stream.iterate(min, current -> {
                    int x = current.x;
                    int z = current.z;
                    if (++x > max.x) {
                        x = min.x;
                        ++z;
                    }
                    if (z > max.z) {
                        throw new IllegalStateException("Stream limit didn't work.");
                    }
                    return new ChunkPos(x, z);
                })
                .limit((long) side * side)
                .filter(pos -> mc.level.hasChunk(pos.x, pos.z))
                .map(pos -> mc.level.getChunk(pos.x, pos.z))
                .filter(Objects::nonNull);
    }

    private ChunkUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
