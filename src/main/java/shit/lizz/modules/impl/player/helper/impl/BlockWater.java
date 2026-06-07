package shit.lizz.modules.impl.player.helper.impl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.impl.player.Helper;
import shit.lizz.modules.impl.player.helper.HelperBase;
import shit.lizz.utils.game.ItemUtil;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.utils.rotation.Rotation;

public class BlockWater
extends HelperBase {

    public enum State { NONE, WATER, WATER_SUPPORT }

    public record PlacementData(BlockPos blockPos, Direction direction, Vec3 hitVec) {
    }

    public Rotation targetRotation;
    private BlockPos targetPos;
    public BlockWater.State currentState = BlockWater.State.NONE;
    private BlockWater.PlacementData currentPlacement = null;
    private int blockSlot = -1;
    private int savedSlot = -1;
    private static final String NAME = "Block Water";

    public BlockWater() {
        super(NAME);
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    private void reset() {
        this.targetPos = null;
        this.currentState = BlockWater.State.NONE;
        this.targetRotation = null;
        if (this.savedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.savedSlot;
        }
        this.savedSlot = -1;
        this.blockSlot = -1;
    }

    @Override
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (mc.player.isInWater()) {
            this.reset();
            return;
        }
        if (motionEvent.isPost()) {
            if (this.currentPlacement != null) {
                if (Helper.isRotationNearTarget()) {
                    return;
                }
                this.placeBlock();
                this.currentPlacement = null;
                if (this.currentState != BlockWater.State.WATER_SUPPORT) {
                    this.reset();
                }
                return;
            }
            switch (this.currentState) {
                case WATER: {
                    if (this.targetPos == null || !mc.level.getBlockState(this.targetPos).is(Blocks.WATER)) {
                        this.reset();
                        return;
                    }
                    if (mc.level.getBlockState(this.targetPos.below()).isAir()) {
                        this.currentState = BlockWater.State.WATER_SUPPORT;
                        break;
                    }
                    if (this.tryFindPlacement()) break;
                    this.currentState = BlockWater.State.WATER_SUPPORT;
                    break;
                }
                case WATER_SUPPORT: {
                    if (this.targetPos == null || !mc.level.getBlockState(this.targetPos).is(Blocks.WATER)) {
                        this.reset();
                        return;
                    }
                    BlockPos blockPos = this.targetPos.below();
                    if (mc.level.getBlockState(blockPos).isSolid()) {
                        this.currentState = BlockWater.State.WATER;
                        this.tryFindPlacement();
                        return;
                    }
                    this.findSuitableFace(blockPos).ifPresentOrElse(placementData -> {
                        this.targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), placementData.hitVec());
                        this.currentPlacement = placementData;
                    }, () -> this.reset());
                    break;
                }
                case NONE: {
                    this.findTargetPos();
                    if (this.targetPos == null) break;
                    this.currentState = BlockWater.State.WATER;
                }
            }
        }
    }

    private boolean tryFindPlacement() {
        Optional<BlockWater.PlacementData> placementOpt = this.findSuitableFace(this.targetPos);
        if (placementOpt.isPresent()) {
            BlockWater.PlacementData placementData = placementOpt.get();
            this.targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), placementData.hitVec());
            this.currentPlacement = placementData;
            return true;
        }
        return false;
    }

    private void placeBlock() {
        if (this.currentPlacement == null) {
            return;
        }
        this.blockSlot = this.findBlockSlot();
        if (this.blockSlot == -1) {
            this.reset();
            return;
        }
        if (this.savedSlot == -1) {
            this.savedSlot = mc.player.getInventory().selected;
        }
        mc.player.getInventory().selected = this.blockSlot;
        BlockHitResult blockHitResult = new BlockHitResult(this.currentPlacement.hitVec(), this.currentPlacement.direction(), this.currentPlacement.blockPos(), false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHitResult);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        if (this.currentState == BlockWater.State.NONE || mc.gameRenderer == null || this.targetPos == null) {
            return;
        }
        Color color = new Color(0, 128, 255);
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 vec3 = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-vec3.x, -vec3.y, -vec3.z);
        AABB aABB = new AABB(this.targetPos);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor((float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, 0.25f);
        RenderUtil.drawSolidBox(aABB, poseStack);
        RenderSystem.setShaderColor((float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, 0.75f);
        RenderUtil.drawOutlineBox(aABB, poseStack);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    private void findTargetPos() {
        if (mc.player == null || mc.level == null) {
            this.targetPos = null;
            return;
        }
        BlockPos playerPos = mc.player.blockPosition();
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; ++dx) {
            for (int dy = -2; dy <= 2; ++dy) {
                for (int dz = -3; dz <= 3; ++dz) {
                    BlockPos candidatePos = playerPos.offset(dx, dy, dz);
                    if (Helper.hasWaterPlacement(candidatePos) || !mc.level.getBlockState(candidatePos).is(Blocks.WATER) || !mc.level.getFluidState(candidatePos).isSource() || !Helper.isPositionInFov(Vec3.atCenterOf(candidatePos))) continue;
                    candidates.add(candidatePos);
                }
            }
        }
        Vec3 eyePos = mc.player.getEyePosition();
        this.targetPos = (BlockPos)candidates.stream().min(Comparator.comparingDouble(blockPos -> Vec3.atCenterOf((Vec3i)blockPos).distanceToSqr(eyePos))).orElse(null);
    }

    private int findBlockSlot() {
        int cobblestoneSlot = ItemUtil.getSlot(Items.COBBLESTONE);
        if (cobblestoneSlot != -1 && cobblestoneSlot < 9) {
            return cobblestoneSlot;
        }
        for (int slot = 0; slot < 9; ++slot) {
            Block block;
            ItemStack itemStack = mc.player.getInventory().getItem(slot);
            if (!(itemStack.getItem() instanceof BlockItem) || !(block = ((BlockItem)itemStack.getItem()).getBlock()).defaultBlockState().isSolid()) continue;
            return slot;
        }
        return -1;
    }

    private Optional<BlockWater.PlacementData> findSuitableFace(BlockPos blockPos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = blockPos.relative(direction);
            if (mc.level.getBlockState(neighborPos).isSolid() && !mc.level.getBlockState(neighborPos).is(Blocks.WATER)) {
                Vec3 hitVec = Vec3.atCenterOf(neighborPos).add(direction.getOpposite().getStepX() * 0.5, direction.getOpposite().getStepY() * 0.5, direction.getOpposite().getStepZ() * 0.5);
                if (mc.player.getEyePosition().distanceToSqr(hitVec) <= 25.0 && this.canSeeBlockFace(neighborPos, direction.getOpposite())) {
                    return Optional.of(new BlockWater.PlacementData(neighborPos, direction.getOpposite(), hitVec));
                }
            }
            if (direction != Direction.DOWN) continue;
            BlockPos belowPos = neighborPos;
            for (int i = 0; i < 8 && mc.level.getBlockState(belowPos).is(Blocks.WATER); ++i) {
                belowPos = belowPos.below();
            }
            if (!mc.level.getBlockState(belowPos).isSolid() || mc.level.getBlockState(belowPos).is(Blocks.WATER)) continue;
            Vec3 topHitVec = Vec3.atCenterOf(belowPos).add(new Vec3((double)Direction.UP.getStepX() * 0.5, (double)Direction.UP.getStepY() * 0.5, (double)Direction.UP.getStepZ() * 0.5));
            if (!(mc.player.getEyePosition().distanceToSqr(topHitVec) <= 25.0) || !this.canSeeBlockFace(belowPos, Direction.UP)) continue;
            return Optional.of(new BlockWater.PlacementData(belowPos, Direction.UP, topHitVec));
        }
        return Optional.empty();
    }

    private boolean canSeeBlockFace(BlockPos blockPos, Direction direction) {
        Vec3 targetVec;
        if (mc.player == null || mc.level == null) {
            return false;
        }
        Vec3 eyePos = mc.player.getEyePosition();
        BlockHitResult hit = mc.level.clip(new ClipContext(eyePos, targetVec = Vec3.atCenterOf(blockPos).add(new Vec3((double)direction.getStepX() * 0.49, (double)direction.getStepY() * 0.49, (double)direction.getStepZ() * 0.49)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (!hit.getBlockPos().equals(blockPos)) {
            return false;
        }
        return hit.getLocation().distanceToSqr(targetVec) < 0.25;
    }

    @Override
    public boolean isActive() {
        return this.targetRotation != null;
    }

    @Override
    public Rotation getTargetRotation() {
        return this.targetRotation;
    }
}