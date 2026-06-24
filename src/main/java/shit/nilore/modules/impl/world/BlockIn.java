package shit.nilore.modules.impl.world;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.modules.impl.player.AutoWebPlace;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.BlockUtil;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.utils.rotation.Rotation;

public class BlockIn extends Module {
    public static BlockIn INSTANCE;

    private static final double JUMP_SUPPORT_MIN_PEAK_Y = 0.85;
    private static final double JUMP_SUPPORT_MAX_UP_VY = 0.12;
    private static final double JUMP_SUPPORT_MAX_DOWN_VY = -0.08;

    // Settings
    public final BooleanSetting autoDisable = new BooleanSetting("Auto Disable", true);
    public final ModeSetting placeOrder = new ModeSetting("Place Order", "Normal", "Normal", "Random", "BottomTop", "TopBottom").withDefault("Normal");
    public final BooleanSetting autoBlock = new BooleanSetting("Auto Block", true);
    public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
    public final BooleanSetting useOffhand = new BooleanSetting("Use Offhand", false);
    public final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
    public final NumberSetting cooldown = new NumberSetting("Cooldown", 1, 0, 20, 1);
    public final BooleanSetting rotation = new BooleanSetting("Rotation", true);
    public final BooleanSetting roofSupport = new BooleanSetting("Roof Support", true);
    public final BooleanSetting diagonalSupport = new BooleanSetting("Diagonal Support", true);
    public final BooleanSetting pillarSupport = new BooleanSetting("Pillar Support", true);
    public final BooleanSetting useJump = new BooleanSetting("Use Jump", false);
    public final NumberSetting jumpTicks = new NumberSetting("Jump Ticks", 3, 1, 8, 1);
    public final BooleanSetting debug = new BooleanSetting("Debug", false);

    // State
    private BlockPos startPos;
    private boolean rotateClockwise;
    private int oldSlot = -1;
    private int tickCounter;
    private int cooldownTimer;

    // Roof support state
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
        this.tickCounter = 0;
        this.cooldownTimer = 0;
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.releaseJumpKey();
        this.restoreSlot();
        this.resetRoofState();
        this.startPos = null;
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

        this.tickCounter++;
        this.updateJumpControl();

        if (this.cooldownTimer > 0) {
            this.cooldownTimer--;
            return;
        }

        List<BlockPos> positions = this.generatePositions();
        if (positions.isEmpty()) {
            if (this.autoDisable.getValue()) {
                this.setEnabled(false);
            }
            return;
        }

        // Try to place one block per tick
        for (BlockPos pos : positions) {
            if (this.tryPlaceBlock(pos)) {
                this.cooldownTimer = this.cooldown.getValue().intValue();
                break;
            }
        }

        // Check completion
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

        // Floor
        result.add(this.startPos.below());

        // Walls (4 directions × player height)
        for (Direction dir : this.getOrderedDirections()) {
            BlockPos wallBase = this.startPos.relative(dir);
            for (int i = 0; i < playerHeight; i++) {
                result.add(wallBase.above(i));
            }
        }

        // Roof
        result.add(this.roofPos);

        // Order
        List<BlockPos> ordered = this.orderPositions(new ArrayList<>(result));

        // Insert roof support if needed
        BlockPos support = this.updateRoofSupport(playerHeight);
        if (support != null && this.roofPos != null) {
            List<BlockPos> withSupport = new ArrayList<>();
            boolean inserted = false;
            for (BlockPos pos : ordered) {
                if (!inserted && pos.equals(this.roofPos)) {
                    withSupport.add(support);
                    inserted = true;
                }
                if (!pos.equals(support)) {
                    withSupport.add(pos);
                }
            }
            if (!inserted) {
                withSupport.add(support);
            }
            return withSupport;
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

        // Skip if already solid
        if (BlockUtil.isSolid(targetPos)) {
            return false;
        }

        // Find slot with placeable block
        int slot = this.findPlaceableSlot();
        if (slot == -1) {
            return false;
        }

        // Switch to slot
        if (slot != mc.player.getInventory().selected) {
            mc.player.getInventory().selected = slot;
        }

        // Find adjacent solid block to place against
        Direction face = this.findPlacementFace(targetPos);
        if (face == null) {
            return false;
        }

        BlockPos againstPos = targetPos.relative(face.getOpposite());

        // Rotate if enabled
        if (this.rotation.getValue()) {
            Vec3 hitVec = new Vec3(
                    againstPos.getX() + 0.5 + face.getStepX() * 0.5,
                    againstPos.getY() + 0.5 + face.getStepY() * 0.5,
                    againstPos.getZ() + 0.5 + face.getStepZ() * 0.5);
            Rotation rot = RotationUtil.rotationFromVec(hitVec);
            mc.player.setYRot(rot.getYaw());
            mc.player.setXRot(rot.getPitch());
        }

        // Place block
        BlockHitResult hit = new BlockHitResult(
                new Vec3(
                        againstPos.getX() + 0.5 + face.getStepX() * 0.5,
                        againstPos.getY() + 0.5 + face.getStepY() * 0.5,
                        againstPos.getZ() + 0.5 + face.getStepZ() * 0.5),
                face, againstPos, false);

        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (this.debug.getValue()) {
                ChatUtil.print("[BlockIn] Placed at " + this.fmtPos(targetPos));
            }
            return true;
        }
        return false;
    }

    private Direction findPlacementFace(BlockPos targetPos) {
        if (mc.level == null) return null;
        // Check each face: find an adjacent solid block
        for (Direction face : Direction.values()) {
            BlockPos neighbor = targetPos.relative(face);
            if (BlockUtil.isSolid(neighbor)) {
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
                return 40; // offhand slot
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

    // --- Roof Support ---

    private BlockPos updateRoofSupport(int playerHeight) {
        if (mc.player == null || mc.level == null || this.startPos == null || this.roofPos == null) {
            this.clearRoofSupport("no-player");
            return null;
        }
        if (!this.roofSupport.getValue()) {
            this.clearRoofSupport("disabled");
            return null;
        }

        // Roof already placed?
        if (BlockUtil.isSolid(this.roofPos)) {
            this.clearRoofSupport("roof-done");
            return null;
        }

        // Can place roof directly?
        if (this.findPlacementFace(this.roofPos) != null) {
            this.clearRoofSupport("roof-direct");
            return null;
        }

        // Base structure complete?
        if (!this.isBaseComplete(playerHeight)) {
            this.clearRoofSupport("base-pending");
            return null;
        }

        // Keep existing support plan
        if (this.roofSupportPos != null && !BlockUtil.isSolid(this.roofSupportPos)) {
            return this.roofSupportPos;
        }

        // Try diagonal support
        if (this.diagonalSupport.getValue()) {
            BlockPos diag = this.findDiagonalSupport(playerHeight);
            if (diag != null) {
                this.roofPhase = RoofPhase.DIAGONAL;
                this.roofSupportPos = diag;
                return diag;
            }
        }

        // Try jump pillar support
        if (this.pillarSupport.getValue() && this.useJump.getValue()) {
            BlockPos jump = this.findPillarSupport(playerHeight);
            if (jump != null) {
                this.roofPhase = RoofPhase.JUMP_PILLAR;
                this.roofSupportPos = jump;
                return jump;
            }
        }

        // Try normal pillar support
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
                // Try placing support adjacent to diagonal
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
        // Check floor
        if (!BlockUtil.isSolid(this.startPos.below())) return false;
        // Check walls
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
        // Check floor
        if (!BlockUtil.isSolid(this.startPos.below())) return false;
        // Check walls
        for (Direction dir : this.getOrderedDirections()) {
            BlockPos wallBase = this.startPos.relative(dir);
            for (int i = 0; i < playerHeight; i++) {
                if (!BlockUtil.isSolid(wallBase.above(i))) return false;
            }
        }
        // Check roof
        if (!BlockUtil.isSolid(this.roofPos)) return false;
        return true;
    }

    // --- Jump Control ---

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

    // --- Helpers ---

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
