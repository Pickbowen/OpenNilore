package shit.lizz.modules.impl.misc.ai.path;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

public class MovementHelper {

    public static boolean canWalkThrough(ClientLevel level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        Block block = state.getBlock();
        if (state.isAir()) return true;
        // Impassable blocks
        if (block == Blocks.COBWEB || block instanceof FireBlock) return false;
        if (block instanceof BasePressurePlateBlock) return true;
        if (block instanceof CarpetBlock) return true;
        // Fluids - can walk through but costly
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) return true;
        // Doors, fence gates
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) return true;
        // Trapdoors
        if (block instanceof TrapDoorBlock) return true;
        // Snow layers
        if (block instanceof SnowLayerBlock) return true;
        // Flowers, grass, etc
        if (state.canBeReplaced()) return true;
        return false;
    }

    public static boolean canWalkOn(ClientLevel level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        Block block = state.getBlock();
        // Normal solid blocks
        if (state.isSolid()) return true;
        // Slabs and stairs
        if (block instanceof SlabBlock || block instanceof StairBlock) return true;
        // Soul sand
        if (block == Blocks.SOUL_SAND) return true;
        // Farmland, dirt path
        if (block instanceof FarmBlock) return true;
        // Ladders and vines
        if (block instanceof LadderBlock || block instanceof VineBlock) return true;
        // Glass, chests
        if (block instanceof StainedGlassBlock || block instanceof ChestBlock) return true;
        // Water/lava - technically can walk on with special handling
        return false;
    }

    public static boolean isSafeToWalkOn(ClientLevel level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        Block block = state.getBlock();
        if (block == Blocks.CACTUS || block == Blocks.MAGMA_BLOCK) return false;
        return canWalkOn(level, x, y, z);
    }

    public static boolean avoidWalkingInto(ClientLevel level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        Block block = state.getBlock();
        if (block == Blocks.CACTUS || block == Blocks.MAGMA_BLOCK || block == Blocks.SWEET_BERRY_BUSH) return true;
        if (block == Blocks.FIRE) return true;
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return true;
        return false;
    }

    public static boolean needsBreaking(ClientLevel level, int x, int y, int z) {
        return !canWalkThrough(level, x, y, z);
    }
}
