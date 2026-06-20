package shit.nilore.modules.impl.player;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.ItemUtil;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.event.EventTarget;

public class AutoMLG
extends Module {
    public static AutoMLG INSTANCE;
    private final NumberSetting triggerDistanceSetting = new NumberSetting("Fall distance", 3.0f, 1.0f, 10.0f, 0.1f);
    private final NumberSetting predictTicksSetting = new NumberSetting("Predict Ticks", 2.0f, 1.0f, 5.0f, 1.0f);
    private final BooleanSetting solidCheckSetting = new BooleanSetting("Solid check", true);
    private final BooleanSetting recoverySetting = new BooleanSetting("Recorvey", true);
    public Rotation targetRotation = null;
    private float accumulatedFall;
    private double lastY;
    private Integer slotToRestore;
    private boolean waterPlaced;
    private boolean recoveryActive;
    private int recoveryDelay;
    private int recoveryCountdown;
    private Integer waterBucketSlot;
    private BlockPos placedWaterPos;
    private boolean readyToPlace;
    private int postPlaceCooldown;
    private int postActionCooldown;
    private int extraCooldown;

    public AutoMLG() {
        super("AutoMLG", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.slotToRestore = null;
        this.waterPlaced = false;
        this.recoveryActive = false;
        this.recoveryDelay = 0;
        this.recoveryCountdown = 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = null;
        this.readyToPlace = false;
        this.postPlaceCooldown = 0;
        this.postActionCooldown = 0;
        this.extraCooldown = 0;
        this.accumulatedFall = 0.0f;
        this.lastY = mc.player != null ? mc.player.getY() : 0.0;
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.slotToRestore = null;
        this.waterPlaced = false;
        this.recoveryActive = false;
        this.recoveryDelay = 0;
        this.recoveryCountdown = 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = null;
        this.readyToPlace = false;
        this.postPlaceCooldown = 0;
        this.postActionCooldown = 0;
        this.extraCooldown = 0;
        this.accumulatedFall = 0.0f;
        super.onDisable();
    }

    public boolean isInCooldown() {
        return this.postPlaceCooldown > 0;
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        Rotation rotation;
        BlockHitResult hit;
        BlockPos bucketPos;
        int slot;
        double deltaY;
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mc.player.isFallFlying()) {
            return;
        }
        if (mc.player.onGround() || mc.player.getAbilities().flying || mc.player.isInWaterRainOrBubble() || mc.player.isInLava()) {
            this.accumulatedFall = 0.0f;
        } else {
            deltaY = mc.player.getY() - this.lastY;
            if (deltaY < 0.0) {
                this.accumulatedFall -= (float)deltaY;
            }
        }
        this.lastY = mc.player.getY();
        if (this.postPlaceCooldown > 0) {
            --this.postPlaceCooldown;
        }
        if (this.postActionCooldown > 0) {
            --this.postActionCooldown;
        }
        if (this.extraCooldown > 0) {
            --this.extraCooldown;
        }
        if (this.slotToRestore != null) {
            mc.player.getInventory().selected = this.slotToRestore;
            this.slotToRestore = null;
        }
        if (mc.player.onGround() || this.accumulatedFall <= 0.0f) {
            this.waterPlaced = false;
            this.readyToPlace = false;
        }
        if (this.recoveryActive) {
            if (this.recoveryDelay > 0) {
                --this.recoveryDelay;
                return;
            }
            if (this.recoveryCountdown-- <= 0) {
                this.recoveryActive = false;
                return;
            }
            if (this.waterBucketSlot == null) {
                this.waterBucketSlot = ItemUtil.findItemInRange(0, 9, Items.BUCKET);
                if (this.waterBucketSlot == null) {
                    this.recoveryActive = false;
                    return;
                }
            }
            if (mc.player.getInventory().items.get(this.waterBucketSlot.intValue()).getItem() == Items.WATER_BUCKET) {
                this.recoveryActive = false;
                this.waterBucketSlot = null;
                this.placedWaterPos = null;
                this.postPlaceCooldown = Math.max(this.postPlaceCooldown, 1);
                return;
            }
            if (this.placedWaterPos == null || !this.isWaterSource(this.placedWaterPos)) {
                this.recoveryActive = false;
                this.waterBucketSlot = null;
                this.placedWaterPos = null;
                return;
            }
            Rotation recoveryRotation = RotationUtil.rotationToBlock(this.placedWaterPos, 0.0f);
            BlockHitResult recoveryHit = this.raycastFluid(recoveryRotation, 4.5);
            if (recoveryHit.getType() == HitResult.Type.MISS || !recoveryHit.getBlockPos().equals(this.placedWaterPos)) {
                this.recoveryActive = false;
                this.waterBucketSlot = null;
                this.placedWaterPos = null;
                return;
            }
            this.setTargetRotation(recoveryRotation);
            this.selectSlot(this.waterBucketSlot);
            this.useItem(recoveryRotation);
            return;
        }
        if (!this.waterPlaced && !this.recoveryActive && this.placedWaterPos == null && this.postPlaceCooldown == 0 && this.postActionCooldown == 0 && this.accumulatedFall <= 0.5f && ItemUtil.findItemInRange(0, 9, Items.WATER_BUCKET) < 0 && (slot = ItemUtil.findItemInRange(0, 9, Items.BUCKET)) >= 0 && (bucketPos = this.findBucketPos()) != null && (hit = this.raycastFluid(rotation = RotationUtil.rotationToBlock(bucketPos, 0.0f), 4.5)).getType() != HitResult.Type.MISS && hit.getBlockPos().equals(bucketPos)) {
            this.setTargetRotation(rotation);
            this.selectSlot(slot);
            this.useItem(rotation);
            this.postActionCooldown = 8;
            this.postPlaceCooldown = Math.max(this.postPlaceCooldown, 1);
            return;
        }
        if (this.waterPlaced && !this.readyToPlace && mc.player.getDeltaMovement().y < 0.0 && (deltaY = this.distanceToGround(2.5)) > 0.0 && deltaY <= 1.05) {
            this.readyToPlace = true;
        }
        if (this.waterPlaced) {
            return;
        }
        if (this.accumulatedFall < this.triggerDistanceSetting.getValue().floatValue()) {
            return;
        }
        slot = ItemUtil.findItemInRange(0, 9, Items.WATER_BUCKET);
        if (slot < 0) {
            return;
        }
        int ticksLeft = this.ticksUntilGround();
        if (ticksLeft <= this.predictTicksSetting.getValue().intValue()) {
            if (this.solidCheckSetting.getValue() && !this.hasSolidBelow(BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()))) {
                return;
            }
            rotation = new Rotation(mc.player.getYRot(), 90.0f);
            hit = this.raycastSolid(rotation, 5.0);
            if (hit.getType() == HitResult.Type.MISS) {
                return;
            }
            this.placeWaterBucket(slot, true);
        }
    }

    private int ticksUntilGround() {
        if (mc.player.getDeltaMovement().y >= 0.0) {
            return 999;
        }
        double distance = this.distanceToGround(30.0);
        if (distance == Double.POSITIVE_INFINITY) {
            return 999;
        }
        double simulatedDrop = 0.0;
        double simulatedVelocity = mc.player.getDeltaMovement().y;
        for (int i = 1; i <= 20; ++i) {
            simulatedDrop += simulatedVelocity;
            simulatedVelocity = (simulatedVelocity - 0.08) * 0.98;
            if (!(Math.abs(simulatedDrop) >= distance)) continue;
            return i;
        }
        return 999;
    }

    private void useItem(Rotation rotation) {
        if (mc.gameMode != null && mc.player != null) {
            float originalPitch = mc.player.getXRot();
            float originalYaw = mc.player.getYRot();
            if (rotation != null) {
                mc.player.setXRot(rotation.getPitch());
                mc.player.setYRot(rotation.getYaw());
            }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (rotation != null) {
                mc.player.setXRot(originalPitch);
                mc.player.setYRot(originalYaw);
            }
        }
    }

    private BlockPos findBucketPos() {
        BlockPos playerPos = BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        BlockPos closestPos = null;
        double closestDistSq = Double.POSITIVE_INFINITY;
        int radius = 4;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    Rotation rotation;
                    BlockHitResult hit;
                    double distSq;
                    BlockPos candidatePos = playerPos.offset(dx, dy, dz);
                    if (!this.isWaterSource(candidatePos) || (distSq = mc.player.position().distanceToSqr((double)candidatePos.getX() + 0.5, (double)candidatePos.getY() + 0.5, (double)candidatePos.getZ() + 0.5)) >= closestDistSq || (hit = this.raycastFluid(rotation = RotationUtil.rotationToBlock(candidatePos, 0.0f), 4.5)).getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(candidatePos)) continue;
                    closestPos = candidatePos;
                    closestDistSq = distSq;
                }
            }
        }
        return closestPos;
    }

    private void setTargetRotation(Rotation rotation) {
        this.targetRotation = rotation;
    }

    private void selectSlot(int slot) {
        this.slotToRestore = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
    }

    private void placeWaterBucket(int slot, boolean markPlaced) {
        Rotation rotation = new Rotation(mc.player.getYRot(), 90.0f);
        this.setTargetRotation(rotation);
        this.selectSlot(slot);
        this.useItem(rotation);
        if (markPlaced) {
            this.waterPlaced = true;
        }
        this.recoveryActive = this.recoverySetting.getValue();
        this.recoveryDelay = 3;
        this.recoveryCountdown = this.recoveryActive ? 2 : 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = this.getPlacementBlockPos(rotation);
    }

    private BlockPos getPlacementBlockPos(Rotation rotation) {
        BlockHitResult hit = this.raycastSolid(rotation, 4.5);
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        return hit.getBlockPos().relative(hit.getDirection());
    }

    private BlockHitResult raycastSolid(Rotation rotation, double range) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 direction = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
        Vec3 endPos = eyePos.add(direction.scale(range));
        return mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }

    private BlockHitResult raycastFluid(Rotation rotation, double range) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 direction = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
        Vec3 endPos = eyePos.add(direction.scale(range));
        return mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
    }

    private boolean isWaterSource(BlockPos blockPos) {
        FluidState fluidState = mc.level.getFluidState(blockPos);
        return fluidState.getType() == Fluids.WATER && fluidState.isSource();
    }

    private boolean hasSolidBelow(BlockPos blockPos) {
        return this.isSolidNonMenu(blockPos.below()) || this.isSolidNonMenu(blockPos.below(2));
    }

    private boolean isSolidNonMenu(BlockPos blockPos) {
        BlockState blockState = mc.level.getBlockState(blockPos);
        boolean hasCollision = !blockState.getCollisionShape(mc.level, blockPos).isEmpty();
        boolean noMenu = blockState.getMenuProvider(mc.level, blockPos) == null;
        return hasCollision && noMenu;
    }

    private double distanceToGround(double maxDist) {
        Vec3 endPos;
        Vec3 startPos = new Vec3(mc.player.getX(), mc.player.getBoundingBox().minY, mc.player.getZ());
        BlockHitResult hit = mc.level.clip(new ClipContext(startPos, endPos = startPos.add(0.0, -maxDist, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return Double.POSITIVE_INFINITY;
        }
        return startPos.y - hit.getLocation().y;
    }
}