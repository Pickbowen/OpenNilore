package client.nilore.modules.impl.world;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.AutoWebPlace;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.RayTraceUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.rotation.Rotation;

public class BlockIn extends Module {
    public static BlockIn INSTANCE;

    public final BooleanSetting autoDisable = new BooleanSetting("Auto Disable", true);
    public final ModeSetting placeOrder = new ModeSetting("Place Order", "Normal", "Normal", "Random", "BottomTop", "TopBottom").withDefault("Normal");
    public final BooleanSetting autoBlock = new BooleanSetting("Auto Block", true);
    public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
    public final BooleanSetting useOffhand = new BooleanSetting("Use Offhand", false);
    public final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
    public final NumberSetting cooldown = new NumberSetting("Cooldown", 1, 0, 20, 1);
    public final BooleanSetting rotation = new BooleanSetting("Rotation", true);
    public final BooleanSetting smooth = new BooleanSetting("Smooth", false, () -> this.rotation.getValue());
    public final NumberSetting rotationTicks = new NumberSetting("Rotation Ticks", 3, 1, 6, 1, () -> this.rotation.getValue() && this.smooth.getValue());
    public final BooleanSetting roofSupport = new BooleanSetting("Roof Support", true);
    public final BooleanSetting diagonalSupport = new BooleanSetting("Diagonal Support", true);
    public final BooleanSetting pillarSupport = new BooleanSetting("Pillar Support", true);
    public final BooleanSetting useJump = new BooleanSetting("Use Jump", true);
    public final NumberSetting jumpTicks = new NumberSetting("Jump Ticks", 2, 1, 8, 1);
    public final BooleanSetting debug = new BooleanSetting("Debug", false);


    public Rotation targetRotation = null;
    private static final int OFFHAND_SLOT = 40;
    private BlockPos startPos;
    private boolean rotateClockwise;
    private int oldSlot = -1;

    private int cooldownTimer;


    private RoofPhase roofPhase = RoofPhase.NORMAL;
    private BlockPos roofPos;
    private BlockPos roofSupportPos;
    private int jumpTicksRemaining;
    private boolean moduleJumpDown;
    private boolean waitingLanding;

    public BlockIn() {
        super("BlockIn", Category.WORLD);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            Scaffold.INSTANCE.setEnabled(false);
        }
        if (AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled()) {
            AutoWebPlace.INSTANCE.setEnabled(false);
        }
        if (mc.player != null) {
            this.startPos = mc.player.blockPosition();
            this.rotateClockwise = ThreadLocalRandom.current().nextBoolean();
            this.oldSlot = mc.player.getInventory().selected;
            this.resetRoofState();
        }
        this.targetRotation = null;

        this.cooldownTimer = 0;
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.releaseJumpKey();
        this.restoreSlot();
        this.resetRoofState();
        this.startPos = null;
        this.targetRotation = null;
        super.onDisable();
    }

    @EventTarget(value = 1)
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || this.startPos == null) {
            return;
        }

        // Auto disable if player moved away
        BlockPos currentPos = mc.player.blockPosition();
        if (!currentPos.equals(this.startPos) && !currentPos.equals(this.startPos.above())) {
            if (this.debug.getValue()) {
                ChatUtil.print("[BlockIn] Moved away, disabling");
            }
            this.setEnabled(false);
            return;
        }

        this.updateJumpControl();

        if (this.cooldownTimer > 0) {
            this.cooldownTimer--;
            return;
        }

        List<BlockPos> positions = this.generatePositions();
        if (positions.isEmpty()) {
            if (!useJump.getValue()){
                this.setEnabled(false);
                return;
            }
            return;
        }

        for (BlockPos pos : positions) {
            if (this.tryPlaceBlock(pos)) {
                this.cooldownTimer = this.cooldown.getValue().intValue();
                break;
            }
        }

        if (this.autoDisable.getValue() && this.isComplete()) {
            if (this.debug.getValue()) {
                ChatUtil.print("[BlockIn] Complete, disabling");
            }
            this.setEnabled(false);
        }
    }

    private List<BlockPos> generatePositions() {
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        if (mc.player == null || this.startPos == null) {
            return List.of();
        }

        int playerHeight = Mth.ceil(mc.player.getBbHeight());
        this.roofPos = this.startPos.above(playerHeight);

        if (this.waitingLanding && !mc.player.onGround()) {
            return List.of();
        }

        boolean baseComplete = this.isBaseComplete(playerHeight);

        List<BlockPos> baseBlocks = new ArrayList<>();

        baseBlocks.add(this.startPos.below());

        for (Direction dir : this.getOrderedDirections()) {
            BlockPos wallBase = this.startPos.relative(dir);
            for (int i = 0; i < playerHeight; i++) {
                baseBlocks.add(wallBase.above(i));
            }
        }

        List<BlockPos> ordered = this.orderPositions(baseBlocks);

        if (baseComplete) {
            BlockPos support = this.updateRoofSupport(playerHeight);
            if (support != null) {
                ordered.add(support);
            }
            ordered.add(this.roofPos);
        }

        return ordered;
    }

    private List<BlockPos> orderPositions(List<BlockPos> positions) {
        String mode = this.placeOrder.getValue();
        if ("Random".equals(mode)) {
            Collections.shuffle(positions);
        } else if ("BottomTop".equals(mode)) {
            positions.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        } else if ("TopBottom".equals(mode)) {
            positions.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        }
        return positions;
    }

    private boolean tryPlaceBlock(BlockPos targetPos) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return false;
        }

        if (!mc.level.getBlockState(targetPos).canBeReplaced()) {
            return false;
        }

        boolean isRoofSupport = this.roofSupportPos != null && targetPos.equals(this.roofSupportPos);
        boolean isRoof = this.roofPos != null && targetPos.equals(this.roofPos);

        // Roof support can only be placed when player is in the air (jumping)
        if (isRoofSupport && mc.player.onGround()) {
            return false;
        }

        if (isRoof) {
            if (!mc.player.onGround()) {
                return false;
            }
            // Check if roof support is ready
            if (this.roofSupportPos != null && !BlockUtil.isSolid(this.roofSupportPos)) {
                return false;
            }
        }

        int slot = this.findPlaceableSlot();
        if (slot == -1) {
            return false;
        }

        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (slot == OFFHAND_SLOT) {
            hand = InteractionHand.OFF_HAND;
        } else if (slot != mc.player.getInventory().selected) {
            mc.player.getInventory().selected = slot;
        }

        Direction face = this.findPlacementFace(targetPos);
        if (face == null) {
            return false;
        }

        BlockPos againstPos = targetPos.relative(face.getOpposite());

        if (this.rotation.getValue()) {
            Vec3 hitVec = new Vec3(
                    againstPos.getX() + 0.5 + face.getStepX() * 0.5,
                    againstPos.getY() + 0.5 + face.getStepY() * 0.5,
                    againstPos.getZ() + 0.5 + face.getStepZ() * 0.5);
            Rotation targetRot = RotationUtil.rotationFromVec(hitVec);

            if (this.smooth.getValue() && mc.player != null) {
                Rotation currentRot = this.targetRotation != null
                        ? this.targetRotation
                        : new Rotation(mc.player.getYRot(), mc.player.getXRot());
                double speed = 180.0 / this.rotationTicks.getValue().doubleValue();
                this.targetRotation = RotationUtil.smoothRotation(currentRot, targetRot, speed);
            } else {
                this.targetRotation = targetRot;
            }

            if (this.targetRotation == null
                    || !RayTraceUtil.canRayTrace(this.targetRotation, face, againstPos, false)) {
                return false;
            }
            PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(
                    this.targetRotation.getYaw(), this.targetRotation.getPitch(), mc.player.onGround()));
        } else {
            this.targetRotation = null;

        }

        BlockHitResult hit = new BlockHitResult(
                new Vec3(
                        againstPos.getX() + 0.5 + face.getStepX() * 0.5,
                        againstPos.getY() + 0.5 + face.getStepY() * 0.5,
                        againstPos.getZ() + 0.5 + face.getStepZ() * 0.5),
                face, againstPos, false);

        var result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (result.consumesAction()) {
            mc.player.swing(hand);
            if (this.debug.getValue()) {
                ChatUtil.print("[BlockIn] Placed at " + this.fmtPos(targetPos));
            }
            return true;
        }
        return false;
    }

    private Direction findPlacementFace(BlockPos targetPos) {
        if (mc.level == null) return null;
        for (Direction face : Direction.values()) {
            BlockPos neighbor = targetPos.relative(face);
            if (!mc.level.getBlockState(neighbor).canBeReplaced()) {
                return face.getOpposite();
            }
        }
        return null;
    }

    private int findPlaceableSlot() {
        if (mc.player == null) return -1;

        int selected = mc.player.getInventory().selected;
        if (this.isPlaceableSlot(selected)) {
            return selected;
        }

        if (this.autoBlock.getValue()) {
            int best = -1;
            int bestCount = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(stack) && stack.getCount() > bestCount) {
                    best = i;
                    bestCount = stack.getCount();
                }
            }
            if (best != -1) return best;
        }

        if (this.useOffhand.getValue()) {
            ItemStack offhand = mc.player.getOffhandItem();
            if (offhand.getItem() instanceof BlockItem && BlockUtil.isPlaceable(offhand)) {
                return OFFHAND_SLOT;
            }
        }

        return -1;
    }

    private boolean isPlaceableSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        ItemStack stack = mc.player.getInventory().getItem(slot);
        return stack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(stack);
    }

    private void restoreSlot() {
        if (mc.player != null && this.oldSlot >= 0 && this.switchBack.getValue()) {
            mc.player.getInventory().selected = this.oldSlot;
        }
        this.oldSlot = -1;
    }

    private BlockPos updateRoofSupport(int playerHeight) {
        if (mc.player == null || mc.level == null || this.startPos == null || this.roofPos == null) {
            this.clearRoofSupport("no-player");
            return null;
        }
        if (!this.roofSupport.getValue()) {
            this.clearRoofSupport("disabled");
            return null;
        }

        if (BlockUtil.isSolid(this.roofPos)) {
            this.clearRoofSupport("roof-done");
            return null;
        }

        if (this.findPlacementFace(this.roofPos) != null) {
            this.clearRoofSupport("roof-direct");
            return null;
        }

        if (!this.isBaseComplete(playerHeight)) {
            this.clearRoofSupport("base-pending");
            return null;
        }

        if (this.roofSupportPos != null && !BlockUtil.isSolid(this.roofSupportPos)) {
            return this.roofSupportPos;
        }

        if (this.diagonalSupport.getValue()) {
            BlockPos diag = this.findDiagonalSupport(playerHeight);
            if (diag != null) {
                this.roofPhase = RoofPhase.DIAGONAL;
                this.roofSupportPos = diag;
                return diag;
            }
        }

        if (this.pillarSupport.getValue() && this.useJump.getValue()) {
            BlockPos jump = this.findPillarSupport(playerHeight);
            if (jump != null) {
                this.roofPhase = RoofPhase.JUMP_PILLAR;
                this.roofSupportPos = jump;
                return jump;
            }
        }

        if (this.pillarSupport.getValue()) {
            BlockPos pillar = this.findPillarSupport(playerHeight);
            if (pillar != null) {
                this.roofPhase = RoofPhase.PILLAR;
                this.roofSupportPos = pillar;
                return pillar;
            }
        }

        this.clearRoofSupport("no-support");
        return null;
    }

    private BlockPos findDiagonalSupport(int playerHeight) {
        int[] offsets = {-1, 1};
        for (int dx : offsets) {
            for (int dz : offsets) {
                BlockPos diagonal = this.startPos.offset(dx, playerHeight, dz);
                if (!BlockUtil.isSolid(diagonal)) continue;
                BlockPos xSupport = this.startPos.offset(dx, playerHeight, 0);
                if (!BlockUtil.isSolid(xSupport) && this.findPlacementFace(xSupport) != null) {
                    return xSupport;
                }
                BlockPos zSupport = this.startPos.offset(0, playerHeight, dz);
                if (!BlockUtil.isSolid(zSupport) && this.findPlacementFace(zSupport) != null) {
                    return zSupport;
                }
            }
        }
        return null;
    }

    private BlockPos findPillarSupport(int playerHeight) {
        for (Direction dir : this.getOrderedDirections()) {
            BlockPos support = this.startPos.relative(dir).above(playerHeight);
            if (!BlockUtil.isSolid(support) && BlockUtil.isSolid(support.below()) && this.findPlacementFace(support) != null) {
                return support;
            }
        }
        return null;
    }

    private boolean isBaseComplete(int playerHeight) {
        if (mc.player == null || this.startPos == null) return false;
        if (!BlockUtil.isSolid(this.startPos.below())) return false;
        for (Direction dir : this.getOrderedDirections()) {
            BlockPos wallBase = this.startPos.relative(dir);
            for (int i = 0; i < playerHeight; i++) {
                if (!BlockUtil.isSolid(wallBase.above(i))) return false;
            }
        }
        return true;
    }

    private boolean isComplete() {
        if (mc.player == null || this.startPos == null) return false;
        int playerHeight = Mth.ceil(mc.player.getBbHeight());
        if (!BlockUtil.isSolid(this.startPos.below())) return false;
        for (Direction dir : this.getOrderedDirections()) {
            BlockPos wallBase = this.startPos.relative(dir);
            for (int i = 0; i < playerHeight; i++) {
                if (!BlockUtil.isSolid(wallBase.above(i))) return false;
            }
        }
        if (!BlockUtil.isSolid(this.roofPos)) return false;
        return true;
    }

    private void updateJumpControl() {
        if (mc.player == null || mc.options == null) return;

        if (this.roofPhase == RoofPhase.WAIT_LANDING) {
            this.releaseJumpKey();
            if (mc.player.onGround()) {
                this.waitingLanding = false;
                this.clearRoofSupport("landed");
            }
            return;
        }

        if (this.roofPhase != RoofPhase.JUMP_PILLAR) {
            this.releaseJumpKey();
            return;
        }

        if (this.roofSupportPos != null && BlockUtil.isSolid(this.roofSupportPos)) {
            this.releaseJumpKey();
            this.roofPhase = RoofPhase.WAIT_LANDING;
            this.waitingLanding = true;
            return;
        }

        if (!this.moduleJumpDown && mc.player.onGround()) {
            this.jumpTicksRemaining = Math.max(1, this.jumpTicks.getValue().intValue());
            this.setKeyDown(mc.options.keyJump, true);
            this.moduleJumpDown = true;
        }

        if (this.moduleJumpDown) {
            this.jumpTicksRemaining--;
            if (this.jumpTicksRemaining <= 0) {
                this.releaseJumpKey();
            }
        }
    }

    private void releaseJumpKey() {
        if (this.moduleJumpDown && mc != null && mc.options != null) {
            boolean physical = mc.getWindow() != null
                    && InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
            this.setKeyDown(mc.options.keyJump, physical);
            this.moduleJumpDown = false;
        }
        this.jumpTicksRemaining = 0;
    }

    private void setKeyDown(KeyMapping key, boolean down) {
        KeyMapping.set(key.getKey(), down);
        key.setDown(down);
    }

    private Direction[] getOrderedDirections() {
        Direction[] result = new Direction[4];
        Direction dir = mc.player == null ? Direction.NORTH : mc.player.getDirection();
        for (int i = 0; i < 4; i++) {
            result[i] = dir;
            dir = this.rotateClockwise ? dir.getClockWise() : dir.getCounterClockWise();
        }
        return result;
    }

    private void clearRoofSupport(String reason) {
        this.roofPhase = RoofPhase.NORMAL;
        this.roofSupportPos = null;
        this.waitingLanding = false;
        this.jumpTicksRemaining = 0;
        this.releaseJumpKey();
    }

    private void resetRoofState() {
        this.roofPhase = RoofPhase.NORMAL;
        this.roofPos = null;
        this.roofSupportPos = null;
        this.jumpTicksRemaining = 0;
        this.moduleJumpDown = false;
        this.waitingLanding = false;
    }

    private String fmtPos(BlockPos pos) {
        if (pos == null) return "null";
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private enum RoofPhase {
        NORMAL,
        DIAGONAL,
        PILLAR,
        JUMP_PILLAR,
        WAIT_LANDING
    }
}