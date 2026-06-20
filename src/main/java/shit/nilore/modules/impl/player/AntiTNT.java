package shit.nilore.modules.impl.player;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.utils.animation.TickTimer;
import shit.nilore.utils.game.BlockUtil;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.math.MathUtil;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.event.EventTarget;

public class AntiTNT
extends Module {
    public static AntiTNT INSTANCE;
    private final TickTimer placementTimer = new TickTimer();
    private final List<BlockPos> blockPositionQueue = new ArrayList<>();
    private PrimedTnt targetTnt = null;
    private int savedHotbarSlot = -1;
    private BlockPos lastPlacedPos = null;
    public static Rotation targetRotation;

    public AntiTNT() {
        super("AntiTNT", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.blockPositionQueue.clear();
        this.targetTnt = null;
        this.savedHotbarSlot = -1;
        this.lastPlacedPos = null;
        this.placementTimer.reset();
        targetRotation = null;
    }

    @Override
    public void onDisable() {
        this.blockPositionQueue.clear();
        this.targetTnt = null;
        this.restoreSlot();
        targetRotation = null;
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (this.isMoving()) {
            if (!this.blockPositionQueue.isEmpty()) {
                this.blockPositionQueue.clear();
                this.restoreSlot();
            }
            targetRotation = null;
            this.targetTnt = null;
            return;
        }
        if (this.blockPositionQueue.isEmpty()) {
            this.targetTnt = this.findNearestTNT();
        }
        if (!this.blockPositionQueue.isEmpty()) {
            this.placeNextBlock();
            return;
        }
        if (this.targetTnt != null) {
            this.collectBlockPositions();
        } else {
            targetRotation = null;
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 vec3 = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-vec3.x, -vec3.y, -vec3.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        if (this.lastPlacedPos != null) {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 0.8f);
            RenderUtil.drawOutlineBox(new AABB(this.lastPlacedPos), poseStack);
        }
        if (this.targetTnt != null && this.targetTnt.isAlive()) {
            AABB aABB = this.targetTnt.getBoundingBox();
            RenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 0.25f);
            RenderUtil.drawSolidBox(aABB, poseStack);
            RenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 0.8f);
            RenderUtil.drawOutlineBox(aABB, poseStack);
            float fuseSeconds = (float)this.targetTnt.getFuse() / 20.0f;
            if (fuseSeconds > 0.0f) {
                String fuseLabel = String.format("%.1fs", new Object[]{fuseSeconds});
                BlockPos tntPos = this.targetTnt.blockPosition();
                poseStack.pushPose();
                poseStack.translate((double)tntPos.getX() + 0.5 - vec3.x, (double)tntPos.getY() + 1.1 - vec3.y, (double)tntPos.getZ() + 0.5 - vec3.z);
                poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
                poseStack.scale(-0.025f, -0.025f, 0.025f);
                float textWidth = mc.font.width(fuseLabel);
                mc.font.drawInBatch(fuseLabel, -textWidth / 2.0f, 0.0f, -1, false, poseStack.last().pose(), mc.renderBuffers().bufferSource(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
                poseStack.popPose();
            }
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    private boolean isMoving() {
        if (mc.options == null) {
            return false;
        }
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown() || mc.player.isSprinting();
    }

    private PrimedTnt findNearestTNT() {
        return mc.level.getEntitiesOfClass(PrimedTnt.class, mc.player.getBoundingBox().inflate(20.0)).stream().filter(primedTnt -> primedTnt.getFuse() > 0).filter(primedTnt -> this.isMovingTowardsPlayer(primedTnt) || this.hasLineOfSight(primedTnt)).min(Comparator.comparingDouble(primedTnt -> primedTnt.distanceToSqr(mc.player))).orElse(null);
    }

    private boolean hasLineOfSight(PrimedTnt primedTnt) {
        Vec3 eyePos;
        if (mc.player == null || mc.level == null) {
            return false;
        }
        float maxRange = 8.0f;
        if (primedTnt.distanceToSqr(mc.player) > 64.0) {
            return false;
        }
        Vec3 tntPos = primedTnt.position();
        BlockHitResult hit = mc.level.clip(new ClipContext(tntPos, eyePos = mc.player.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean isMovingTowardsPlayer(PrimedTnt primedTnt) {
        Vec3 toPlayer = mc.player.position().subtract(primedTnt.position()).normalize();
        return primedTnt.getDeltaMovement().dot(toPlayer) > 0.05;
    }

    private void collectBlockPositions() {
        BlockPos sidePos;
        if (!this.blockPositionQueue.isEmpty()) {
            return;
        }
        if (mc.screen != null) {
            mc.player.closeContainer();
            mc.setScreen(null);
        }
        BlockPos playerPos = mc.player.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            sidePos = playerPos.relative(direction);
            if (!this.canPlaceAt(sidePos)) continue;
            this.blockPositionQueue.add(sidePos);
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            sidePos = playerPos.above().relative(direction);
            if (!this.canPlaceAt(sidePos)) continue;
            this.blockPositionQueue.add(sidePos);
        }
        BlockPos abovePos = playerPos.above(2);
        if (this.canPlaceAt(abovePos)) {
            this.blockPositionQueue.add(abovePos);
        }
        this.placementTimer.reset();
    }

    private void placeNextBlock() {
        if (!this.placementTimer.hasPassed(1)) {
            return;
        }
        if (this.blockPositionQueue.isEmpty()) {
            return;
        }
        BlockPos placePos = this.blockPositionQueue.get(0);
        BlockHitResult hit = this.getPlacementHitResult(placePos);
        if (hit == null) {
            this.blockPositionQueue.remove(0);
            return;
        }
        int blockSlot = this.findBlockSlot();
        if (blockSlot == -1) {
            this.blockPositionQueue.clear();
            this.restoreSlot();
            return;
        }
        if (this.savedHotbarSlot == -1) {
            this.savedHotbarSlot = mc.player.getInventory().selected;
        }
        mc.player.getInventory().selected = blockSlot;
        targetRotation = RotationUtil.rotationToBlock(hit.getBlockPos(), 0.0f);
        InteractionResult interactionResult = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (interactionResult.consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        this.lastPlacedPos = placePos;
        this.blockPositionQueue.remove(0);
        this.placementTimer.reset();
        if (this.blockPositionQueue.isEmpty()) {
            this.restoreSlot();
            targetRotation = null;
        }
    }

    private void restoreSlot() {
        if (this.savedHotbarSlot != -1) {
            mc.player.getInventory().selected = this.savedHotbarSlot;
            this.savedHotbarSlot = -1;
        }
    }

    private boolean canPlaceAt(BlockPos blockPos) {
        return mc.level.getBlockState(blockPos).canBeReplaced() && !mc.player.getBoundingBox().intersects(new AABB(blockPos));
    }

    private int findBlockSlot() {
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack itemStack = mc.player.getInventory().getItem(slot);
            if (itemStack.isEmpty() || !(itemStack.getItem() instanceof BlockItem) || !BlockUtil.isPlaceable(itemStack)) continue;
            return slot;
        }
        return -1;
    }

    private boolean isSolidBlock(BlockPos blockPos) {
        return mc.level.getBlockState(blockPos).isSolidRender(mc.level, blockPos);
    }

    private BlockHitResult getPlacementHitResult(BlockPos placePos) {
        BlockPos belowPos = placePos.below();
        if (this.isSolidBlock(belowPos)) {
            return new BlockHitResult(this.getHitVec(belowPos, Direction.UP), Direction.UP, belowPos, false);
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sidePos = placePos.relative(direction);
            if (!this.isSolidBlock(sidePos)) continue;
            Direction hitFace = direction.getOpposite();
            return new BlockHitResult(this.getHitVec(sidePos, hitFace), hitFace, sidePos, false);
        }
        return null;
    }

    private Vec3 getHitVec(BlockPos blockPos, Direction direction) {
        double hitX = (double)blockPos.getX() + 0.5;
        double hitY = (double)blockPos.getY() + 0.5;
        double hitZ = (double)blockPos.getZ() + 0.5;
        if (direction == Direction.UP || direction == Direction.DOWN) {
            hitX += MathUtil.randomDouble(-0.3, 0.3);
            hitZ += MathUtil.randomDouble(-0.3, 0.3);
        } else {
            hitY += MathUtil.randomDouble(-0.25, 0.25);
        }
        if (direction == Direction.WEST || direction == Direction.EAST) {
            hitZ += MathUtil.randomDouble(-0.3, 0.3);
        }
        if (direction == Direction.SOUTH || direction == Direction.NORTH) {
            hitX += MathUtil.randomDouble(-0.3, 0.3);
        }
        double clampedX = Math.max(blockPos.getX(), Math.min(blockPos.getX() + 1, hitX));
        double clampedY = Math.max(blockPos.getY(), Math.min(blockPos.getY() + 1, hitY));
        double clampedZ = Math.max(blockPos.getZ(), Math.min(blockPos.getZ() + 1, hitZ));
        return new Vec3(clampedX, clampedY, clampedZ);
    }

    static {
        targetRotation = null;
    }
}