package shit.nilore.modules.impl.player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.utils.game.BlockUtil;
import shit.nilore.utils.game.ChunkUtil;
import shit.nilore.event.EventTarget;

public class GhostHand
extends Module {
    public GhostHand() {
        super("GhostHand", Category.PLAYER);
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent preMotionEvent) {
        if (mc.options.keyUse.isDown() && this.isChestOpen()) {
            preMotionEvent.setCancelled(true);
        }
    }

    public boolean isChestOpen() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 lookVec = mc.player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.scale(4.5));
        ChestBlockEntity targetChest = null;
        BlockHitResult chestHit = null;
        double closestDist = Double.MAX_VALUE;
        ArrayList<BlockEntity> blockEntities = ChunkUtil.getLoadedBlockEntities().collect(Collectors.toCollection(ArrayList::new));
        for (BlockEntity blockEntity : blockEntities) {
            double dist;
            Optional<Vec3> clipResult;
            ChestBlockEntity candidateChest;
            AABB chestBox;
            if (!(blockEntity instanceof ChestBlockEntity) || (chestBox = this.getChestAABB(candidateChest = (ChestBlockEntity)blockEntity)) == null || !(clipResult = chestBox.clip(eyePos, endPos)).isPresent() || !((dist = clipResult.get().distanceTo(eyePos)) < closestDist)) continue;
            closestDist = dist;
            targetChest = candidateChest;
            chestHit = new BlockHitResult(clipResult.get(), Direction.UP, candidateChest.getBlockPos(), false);
        }
        if (targetChest != null && chestHit != null) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, chestHit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private AABB getChestAABB(ChestBlockEntity chestBlockEntity) {
        BlockPos pairedPos;
        BlockState blockState = chestBlockEntity.getBlockState();
        if (!blockState.hasProperty((Property)ChestBlock.TYPE)) {
            return null;
        }
        ChestType chestType = (ChestType)blockState.getValue((Property)ChestBlock.TYPE);
        if (chestType == ChestType.LEFT) {
            return null;
        }
        BlockPos chestPos = chestBlockEntity.getBlockPos();
        AABB chestBox = BlockUtil.getBoundingBox(chestPos);
        if (chestType != ChestType.SINGLE && BlockUtil.canBeClicked(pairedPos = chestPos.relative(ChestBlock.getConnectedDirection(blockState)))) {
            AABB pairedBox = BlockUtil.getBoundingBox(pairedPos);
            chestBox = chestBox.minmax(pairedBox);
        }
        return chestBox;
    }

    static {
        new HashSet<>();
    }
}