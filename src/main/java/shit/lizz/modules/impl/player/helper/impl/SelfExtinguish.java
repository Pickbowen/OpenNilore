package shit.lizz.modules.impl.player.helper.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import shit.lizz.event.impl.PreMotionEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.impl.player.Helper;
import shit.lizz.modules.impl.player.helper.HelperBase;
import shit.lizz.utils.game.ItemUtil;
import shit.lizz.utils.game.PlayerUtil;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.utils.misc.ReflectionUtil;
import shit.lizz.utils.rotation.Rotation;

public class SelfExtinguish
extends HelperBase {

    public enum State { NONE, PLACING, BREAKING, COMPLETE }

    public record PlacementData(BlockPos blockPos, Direction direction, Vec3 hitVec) {
    }

    public Rotation targetRotation;
    private BlockPos targetPos;
    private BlockPos secondaryPos;
    public SelfExtinguish.State currentState = SelfExtinguish.State.NONE;
    private final PlacementData currentPlacement = null;
    private int blockSlot = -1;
    private int savedSlot = -1;
    private int waterBucketSlot = -1;
    private int bucketSlot = 0;
    private BlockPos placedWaterPos;
    private boolean isAiming = false;
    private boolean shouldPickupWater = false;
    private BlockPos waterBlockPos;
    private int aimCooldown = 0;

    public SelfExtinguish() {
        super("Self Extinguish");
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
        this.secondaryPos = null;
        this.placedWaterPos = null;
        this.currentState = SelfExtinguish.State.NONE;
        this.targetRotation = null;
        if (this.savedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.savedSlot;
        }
        this.savedSlot = -1;
        this.blockSlot = -1;
        this.waterBucketSlot = -1;
        this.bucketSlot = 0;
        this.isAiming = false;
        this.shouldPickupWater = false;
        this.waterBlockPos = null;
        this.aimCooldown = 0;
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (mc.player.isOnFire()) {
            if (this.isAiming && SelfExtinguish.willCollideBelow(mc.player.getDeltaMovement().y)) {
                this.shouldPickupWater = true;
            } else if (mc.player.onGround()) {
                this.waterBucketSlot = ItemUtil.getSlot(Items.WATER_BUCKET);
                if (this.waterBucketSlot != -1 && this.waterBucketSlot < 9) {
                    this.savedSlot = mc.player.getInventory().selected;
                    mc.player.getInventory().selected = this.waterBucketSlot;
                    this.isAiming = true;
                    this.targetRotation = new Rotation(mc.player.getYRot(), 90.0f);
                    this.aimCooldown = 5;
                }
            }
        }
        if (--this.aimCooldown == 0 && this.isAiming) {
            this.isAiming = false;
            this.targetRotation = null;
            if (this.savedSlot != -1) {
                mc.player.getInventory().selected = this.savedSlot;
            }
        }
    }

    public InteractionResult useItem(Player player, Level level, InteractionHand interactionHand) {
        if (ReflectionUtil.getStaticField(mc.gameMode, "localPlayerMode", "net/minecraft/client/multiplayer/MultiPlayerGameMode") == GameType.SPECTATOR) {
            return InteractionResult.PASS;
        }
        PlayerUtil.sendCarriedItem();
        PacketUtil.sendPredictive(seq -> new ServerboundUseItemPacket(interactionHand, seq));
        ItemStack heldStack = player.getItemInHand(interactionHand);
        if (player.getCooldowns().isOnCooldown(heldStack.getItem())) {
            return InteractionResult.PASS;
        }
        InteractionResult forgeResult = ForgeHooks.onItemRightClick(player, interactionHand);
        if (forgeResult != null) {
            return forgeResult;
        }
        InteractionResultHolder resultHolder = heldStack.use(level, player, interactionHand);
        ItemStack resultStack = (ItemStack)resultHolder.getObject();
        if (resultStack != heldStack) {
            player.setItemInHand(interactionHand, resultStack);
            if (resultStack.isEmpty()) {
                ForgeEventFactory.onPlayerDestroyItem(player, heldStack, interactionHand);
            }
        }
        return resultHolder.getResult();
    }

    @Override
    public boolean isActive() {
        return this.isAiming && this.targetRotation != null;
    }

    @Override
    public Rotation getTargetRotation() {
        return this.targetRotation;
    }

    public static boolean willCollideBelow(double deltaY) {
        if (mc.level == null || mc.player == null) {
            return false;
        }
        Iterable<VoxelShape> collisions = mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().move(0.0, deltaY, 0.0));
        return collisions.iterator().hasNext();
    }

    @Override
    public void onPreMotion(PreMotionEvent preMotionEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.shouldPickupWater) {
            if (Helper.isRotationNearTarget()) {
                return;
            }
            this.shouldPickupWater = false;
            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK && ((BlockHitResult)mc.hitResult).getDirection() == Direction.UP) {
                this.waterBlockPos = ((BlockHitResult)mc.hitResult).getBlockPos().above();
                Helper.markWaterPlaced(this.waterBlockPos);
                this.useItem(mc.player, mc.level, InteractionHand.MAIN_HAND);
            } else {
                ChatUtil.print("Failed to place water!");
                this.isAiming = false;
                this.targetRotation = null;
            }
        } else if (this.waterBlockPos != null) {
            this.isAiming = false;
            this.targetRotation = null;
            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK && ((BlockHitResult)mc.hitResult).getBlockPos().above().equals(this.waterBlockPos)) {
                int bucketSlot = ItemUtil.getSlot(Items.BUCKET);
                if (bucketSlot != -1 && bucketSlot < 9) {
                    mc.player.getInventory().selected = bucketSlot;
                    this.useItem(mc.player, mc.level, InteractionHand.MAIN_HAND);
                }
                Helper.removeWaterPlacement(this.waterBlockPos);
            } else {
                ChatUtil.print("Failed to recycle water due to moving!");
            }
            if (this.savedSlot != -1) {
                mc.player.getInventory().selected = this.savedSlot;
            }
            this.waterBlockPos = null;
        }
    }
}