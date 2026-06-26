package shit.nilore.modules.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.RandomUtils;
import shit.nilore.ClientBase;
import shit.nilore.event.impl.JumpEvent;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.event.impl.UpdateHeldItemEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.BlockUtil;
import shit.nilore.utils.game.MotionSimulator;
import shit.nilore.utils.game.MovementUtil;
import shit.nilore.utils.game.RayTraceUtil;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.math.MathUtil;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.utils.misc.PacketUtil;
import shit.nilore.utils.misc.ReflectionUtil;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.utils.rotation.RotationHandler;
import shit.nilore.event.EventTarget;

public class Scaffold extends Module {
    public static Scaffold INSTANCE;

    public final ModeSetting mode = new ModeSetting("Mode", "Normal", "Telly Bridge", "Old Telly", "Keep Y").withDefault("Telly Bridge");
    public final BooleanSetting eagle = new BooleanSetting("Eagle", false, () -> this.mode.is("Normal"));
    public final BooleanSetting sneak = new BooleanSetting("Sneak", false);
    public final BooleanSetting snap = new BooleanSetting("Snap", true, () -> this.mode.is("Normal"));
    public final BooleanSetting smoothTelly = new BooleanSetting("Smooth", true, () -> this.mode.is("Telly Bridge"));
    public final BooleanSetting renderItemSpoof = new BooleanSetting("Render Item Spoof", true);
    public final BooleanSetting renderAimPoint = new BooleanSetting("Render Aim Point", false);
    public final BooleanSetting interactBeforePlace = new BooleanSetting("Interact item before place", true);
    public final NumberSetting rotationTick = new NumberSetting("Rotation Tick", 4, 1, 6, 1);
    public final NumberSetting placeDelay = new NumberSetting("Place Delay", 1, 1, 5, 1);
    public final BooleanSetting clutch = new BooleanSetting("Clutch", true);
    public final BooleanSetting clutchTestMode = new BooleanSetting("Clutch Verify target", false, () -> clutch.getValue());

    public Rotation correctRotation = new Rotation();
    public Rotation rots = new Rotation();
    public Rotation lastRots = new Rotation();
    public int targetYLevel = -1;
    public int velocityDelay = 0;

    private int oldSlot;
    private PlacementTarget currentPlacement;
    private Vec3 hitVecSource;
    private int eagleTimer;
    private int groundTicks = 0;
    private int airTicks = 0;
    private int rotationDelay = 0;
    private final CopyOnWriteArrayList<CopyOnWriteArrayList<Packet<?>>> packetBatches = new CopyOnWriteArrayList<>();
    private int jitterCounter;
    private double yawDiff;
    private double pitchDiff;
    private double lastYawDiff = Double.NaN;
    private double lastPitchDiff = Double.NaN;
    private Rotation tickRotationSnapshot;
    private int placeDelayCounter;
    private boolean canBuildNow;
    private boolean needsLookAdjustment;
    private boolean jumpHeld = false;

    public Scaffold() {
        super("Scaffold", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        if (mc.player != null) {
            this.oldSlot = mc.player.getInventory().selected;
            this.rots.setYawPitch(mc.player.getYRot() - 180.0f, mc.player.getXRot());
            this.lastRots.setYawPitch(mc.player.yRotO - 180.0f, mc.player.xRotO);
            this.currentPlacement = null;
            this.hitVecSource = null;
            this.targetYLevel = 10000;
            this.velocityDelay = 0;
            this.jitterCounter = 0;
            this.yawDiff = 0.0;
            this.pitchDiff = 0.0;
            this.lastYawDiff = Double.NaN;
            this.lastPitchDiff = Double.NaN;
            this.tickRotationSnapshot = new Rotation();
            this.canBuildNow = true;
            this.needsLookAdjustment = false;
            this.packetBatches.clear();
            this.packetBatches.add(new CopyOnWriteArrayList<>());
            this.jumpHeld = false;
        }
        super.onEnable();
    }

    @Override
    public void onDisable() {
        if (mc != null && mc.player != null) {
            this.packetBatches.forEach(this::processBatchedPackets);
            this.packetBatches.clear();
            boolean jumpDown = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
            boolean shiftDown = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyShift.getKey().getValue());
            mc.options.keyJump.setDown(jumpDown);
            mc.options.keyShift.setDown(shiftDown);
            mc.options.keyUse.setDown(false);
            mc.player.getInventory().selected = this.oldSlot;
            this.canBuildNow = true;
            this.needsLookAdjustment = false;
            this.hitVecSource = null;
            this.jumpHeld = false;
            ClientBase.delayPackets.clear();
        }
        super.onDisable();
    }

    private void processBatchedPackets(List<Packet<?>> batch) {
        batch.forEach(packet -> {
            batch.remove(packet);
            PacketUtil.sendQueued((Packet<ServerGamePacketListener>) packet);
        });
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            double length = new Vec3(motion.getXa() / 8000.0, 0.0, motion.getZa() / 8000.0).length();
            if (length >= 1.5) {
                this.velocityDelay = 60;
            }
        }
    }

    @EventTarget
    public void onUpdateHeldItem(UpdateHeldItemEvent event) {
        if (this.renderItemSpoof.getValue() && event.getHand() == InteractionHand.MAIN_HAND && mc.player != null) {
            event.setItemStack(mc.player.getInventory().getItem(this.oldSlot));
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (!this.canBuildNow && this.currentPlacement != null && this.rotationDelay > 0) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (event.isPost() && mc.player != null) {
            if (mc.player.onGround()) {
                this.airTicks = 0;
                this.groundTicks++;
            } else {
                this.groundTicks = 0;
                this.airTicks++;
            }
        }
    }
    private float getDynamicLimitMultiplier() {
        if (mc.player == null) return 1.0f;
        float yaw = Mth.wrapDegrees(mc.player.getYRot());
        if (yaw < 0) yaw += 360.0f;
        float angleInQuadrant = yaw % 90.0f;
        float distanceFromDiagonal = Math.abs(angleInQuadrant - 45.0f);
        float lerpFactor = 1.0f - (distanceFromDiagonal / 45.0f);
        float baseMultiplier = 1.0f;
        float maxMultiplier;
        if (this.jumpHeld && lerpFactor > 0.1){
            maxMultiplier = 2.0f;//ChatUtil.print("jumping "+lerpFactor);
        }else {
            maxMultiplier =1.5f;
        }

        return baseMultiplier + (maxMultiplier - baseMultiplier) * lerpFactor;
    }

    @EventTarget(value = 1)
    public void onTick(TickEvent event) {
        if (mc.player == null) return;
        this.packetBatches.add(new CopyOnWriteArrayList<>());
        if (this.velocityDelay > 0) this.velocityDelay--;
        if (mc.player.onGround() && this.velocityDelay <= 30) this.velocityDelay = 0;

        int placeableSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(stack)) {
                placeableSlot = i;
                break;
            }
        }
        if (placeableSlot != -1 && mc.player.getInventory().selected != placeableSlot) {
            mc.player.getInventory().selected = placeableSlot;
        }

        this.jumpHeld = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
        if (this.targetYLevel == -1
                || this.targetYLevel > (int) Math.floor(mc.player.getY()) - 1
                || mc.player.onGround()
                || !MovementUtil.isMoving()
                || jumpHeld
                || this.mode.is("Normal")) {
            this.targetYLevel = (int) Math.floor(mc.player.getY()) - 1;
        }

        this.applyRotations();

        if (this.currentPlacement != null) {
            this.updateLookAtStatus();
        }

        boolean firstGroundTick = false;
        this.canBuildNow = true;
        if (this.currentPlacement != null && placeableSlot != -1) {
            if (this.groundTicks == 1 && mc.options.keyJump.isDown()) {
                firstGroundTick = true;
            }
            if (this.clutch.getValue() && mc.player.getDeltaMovement().y < -0.1) {
                MotionSimulator sim = new MotionSimulator(mc.player);
                sim.simulateWithFriction(2);
                if (this.currentPlacement.position.getY() > sim.y) {
                    this.canBuildNow = false;
                }
            }
        }
        if (mc.player.onGround()) {
            this.canBuildNow = true;
        }
        this.correctRotation = this.mode.is("Telly Bridge") && this.canBuildNow
                ? this.getTargetRotation(firstGroundTick)
                : this.getPlayerYawRotation();

        if (this.currentPlacement == null) {
            this.hitVecSource = null;
            ClientBase.delayPackets.clear();
        } else if (this.clutch.getValue() && (!this.canBuildNow || this.velocityDelay > 0) && this.rotationDelay <= 8) {
            Rotation rotationToBlock = this.hitVecSource != null ? RotationUtil.rotationFromVec(this.hitVecSource) : RotationUtil.rotationToBlock(this.currentPlacement.position, 1.0f);
            Rotation previousTarget = RotationHandler.targetRotation;
            this.rots.setYawPitch(rotationToBlock.getYaw(), rotationToBlock.getPitch());
            RotationHandler.setTargetRotation(this.rots);
            this.rotationDelay++;

            ClientBase.delayPackets.add(() -> {});
            ClientBase.delayPackets.add(() -> {
                RotationHandler.prevSentRotation.setYawPitch(rotationToBlock.getYaw(), rotationToBlock.getPitch());
                float yaw = rotationToBlock.getYaw();
                if (yaw > -360.0f && yaw < 360.0f) {
                    yaw += 720.0f;
                }
                if (previousTarget != null) {
                    if (previousTarget.getYaw() != rotationToBlock.getYaw()) {
                        PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(yaw, rotationToBlock.getPitch(), mc.player.onGround()));
                    }
                } else {
                    PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(yaw, rotationToBlock.getPitch(), mc.player.onGround()));
                }
                this.doSnap();
                this.onTick(event);
            });
        } else {
            this.canBuildNow = true;
            ClientBase.delayPackets.clear();
            this.rotationDelay = 0;
            if (this.mode.is("Normal") && this.snap.getValue()) {
                this.rots.setYaw(this.correctRotation.getYaw());
            } else {
                this.rots.setYaw(RotationUtil.moveTowards((float) this.getBlockDistance(), this.rots.getYaw(), this.correctRotation.getYaw()));
            }
            this.rots.setPitch(this.correctRotation.getPitch());

            if (this.currentPlacement != null) {
                this.adjustLookAtRotation();
            }

            if (this.sneak.getValue()) {
                this.eagleTimer++;
                if (this.eagleTimer == 18) {
                    if (mc.player.isSprinting()) {
                        mc.options.keySprint.setDown(false);
                        mc.player.setSprinting(false);
                    }
                    mc.options.keyShift.setDown(true);
                } else if (this.eagleTimer >= 21) {
                    mc.options.keyShift.setDown(false);
                    this.eagleTimer = 0;
                }
            }
            if (this.mode.is("Telly Bridge") || this.mode.is("Old Telly")) {
                mc.options.keyJump.setDown(MovementUtil.isMoving() || jumpHeld);
                if (this.airTicks < 1
                        && MovementUtil.isMoving()
                        && !this.smoothTelly.getValue()) {
                    if (this.mode.is("Old Telly")) {
                        this.rots.setYaw(mc.player.getYRot());
                    }
                    this.lastRots.setYawPitch(this.rots.getYaw(), this.rots.getPitch());
                    return;
                }
            } else if (this.mode.is("Keep Y")) {
                mc.options.keyJump.setDown(MovementUtil.isMoving() || jumpHeld);
            } else {
                if (this.eagle.getValue()) {
                    mc.options.keyShift.setDown(mc.player.onGround() && isOnBlockEdge(0.3f));
                }
                if (this.snap.getValue() && !jumpHeld) {
                    this.resetSnap();
                }
            }
        }
        this.lastRots.setYawPitch(this.rots.getYaw(), this.rots.getPitch());
    }

    private double getBlockDistance() {
        if (this.mode.is("Old Telly")) return 180.0;
        double base = Math.max(60.0, 360.0 / this.rotationTick.getValue().doubleValue());
        return Math.max(base, 180.0);
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        this.tickRotationSnapshot = new Rotation(
                this.rots.getYaw(),
                this.rots.getPitch()
        );
        event.setCancelled(true);
        if (mc.screen != null || mc.player == null || this.currentPlacement == null || this.hitVecSource == null) return;
        if ((this.mode.is("Telly Bridge") || this.mode.is("Old Telly")) && this.airTicks < 1) return;

        BlockPos targetPos = BlockPos.containing(this.hitVecSource.x, this.hitVecSource.y, this.hitVecSource.z);
        boolean canRayTrace = RayTraceUtil.canRayTrace(RotationHandler.targetRotation, this.currentPlacement.facing, targetPos, false);

        if (!this.canBuildNow && !this.isPlacementReachable(this.currentPlacement)) return;
        if (this.rotationDelay <= 0 && !this.mode.is("Old Telly") && !canRayTrace) return;
        this.doSnap();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.currentPlacement == null || mc.gameRenderer == null || this.hitVecSource == null) return;
        PoseStack poseStack = event.poseStack();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        AABB box = new AABB(this.currentPlacement.position.relative(this.currentPlacement.facing));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        Color color = new Color(74, 144, 226);
        RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.25f);
        RenderUtil.drawSolidBox(box, poseStack);
        RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.75f);
        RenderUtil.drawOutlineBox(box, poseStack);

        if(renderAimPoint.getValue()) {
            Vec3 hitVec = this.hitVecSource;
            double size = 0.1;
            double half = size / 2.0;
            AABB pointBox = new AABB(hitVec.x - half, hitVec.y - half, hitVec.z - half,
                    hitVec.x + half, hitVec.y + half, hitVec.z + half);
            RenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 1.0f);
            RenderUtil.drawSolidBox(pointBox, poseStack);
            RenderUtil.drawOutlineBox(pointBox, poseStack);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;
        int blockCount = this.getBlockSlot();
        if (blockCount == 0) return;
        String countText = String.valueOf(blockCount);
        String suffix = " Blocks";
        GuiGraphics graphics = event.guiGraphics();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        float centerX = width / 2.0f;
        float y = height / 2.0f - 20.0f;
        int textWidth = mc.font.width(countText + suffix);
        int x = (int) (centerX - textWidth / 2.0f);
        graphics.drawString(mc.font, countText, x, (int) y, -11890462);
        graphics.drawString(mc.font, suffix, x + mc.font.width(countText), (int) y, -1);
    }

    private int getBlockSlot() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isPlacementReachable(PlacementTarget target) {
        if (target == null || mc.player == null || this.hitVecSource == null) return false;
        Rotation currentTarget = RotationHandler.targetRotation;
        if (currentTarget == null) return false;
        Vec3 eye = mc.player.getEyePosition();

        Vec3 toBlock = this.hitVecSource.subtract(eye);
        return toBlock.lengthSqr() <= 25.0
                && toBlock.normalize().dot(Vec3.atLowerCornerOf(target.facing.getNormal().multiply(-1)).normalize()) >= 0.0;
    }

    private void resetSnap() {
        if (mc.player == null || this.currentPlacement == null) return;
        boolean lookingAtBlock = false;
        HitResult result = RayTraceUtil.rayTrace(1.0f, this.rots);
        if (result.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) result;
            if (blockHit.getBlockPos().equals(this.currentPlacement.position)
                    && blockHit.getDirection() != Direction.UP) {
                lookingAtBlock = true;
            }
        }
        if (!lookingAtBlock && mc.player.tickCount % 4 == 0) {
            this.rots.setYaw(mc.player.getYRot() + RandomUtils.nextFloat(0.0f, 0.5f) - 0.25f);
        }
    }

    private Rotation getPlayerYawRotation() {
        if (mc.player == null || this.currentPlacement == null || this.hitVecSource == null) {
            return new Rotation(0.0f, 0.0f);
        }
        return RotationUtil.rotationFromVec(this.hitVecSource);
    }

    private void applyRotations() {
        if (mc.player == null || mc.level == null) return;
        Vec3 eye = mc.player.getEyePosition();
        if (!this.canBuildNow) {
            eye = mc.player.getEyePosition().add(mc.player.getDeltaMovement().multiply(2.0, 2.0, 2.0));
        }
        if (this.clutch.getValue() && mc.player.getDeltaMovement().y < 0.01) {
            MotionSimulator sim = new MotionSimulator(mc.player);
            sim.simulateWithFriction(2);
            eye = new Vec3(eye.x, Math.max(sim.y + mc.player.getEyeHeight(), eye.y), eye.z);
        }
        BlockPos belowFeet = BlockPos.containing(eye.x, this.targetYLevel + 0.1f, eye.z);
        int feetX = belowFeet.getX();
        int feetZ = belowFeet.getZ();
        if (mc.level.getBlockState(belowFeet).entityCanStandOn(mc.level, belowFeet, mc.player)) return;
        if (this.isAbovePlaceable(eye, belowFeet)) return;
        for (int radius = 1; radius <= 5; radius++) {
            for (int yOff = 0; yOff <= radius; yOff++) {
                int xySum = radius - yOff;
                for (int x = 0; x <= xySum; x++) {
                    int z = xySum - x;
                    if (x == 0 && z == 0 && yOff == 0) continue;
                    for (int signX = 0; signX <= (x == 0 ? 0 : 1); signX++) {
                        for (int signZ = 0; signZ <= (z == 0 ? 0 : 1); signZ++) {
                            BlockPos test = new BlockPos(
                                    feetX + (signX == 0 ? x : -x),
                                    this.targetYLevel - yOff,
                                    feetZ + (signZ == 0 ? z : -z));
                            if (this.isAbovePlaceable(eye, test)) return;
                        }
                    }
                }
            }
        }
    }

    private boolean isAbovePlaceable(Vec3 from, BlockPos pos) {
        if (mc.level == null || mc.player == null) return false;
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5f, pos.getZ() + 0.5);
        for (Direction direction : Direction.values()) {
            Vec3 offsetCenter = center.add(new Vec3(
                    direction.getNormal().getX() * 0.5,
                    direction.getNormal().getY() * 0.5,
                    direction.getNormal().getZ() * 0.5));
            BlockPos offset = pos.offset(direction.getNormal());
            if (mc.level.getBlockState(offset).entityCanStandOnFace(mc.level, offset, mc.player, direction)) {
                Vec3 delta = offsetCenter.subtract(from);
                if (delta.lengthSqr() <= 25.0
                        && delta.normalize().dot(Vec3.atLowerCornerOf(direction.getNormal()).normalize()) >= 0.0) {
                    this.currentPlacement = new PlacementTarget(new BlockPos(offset.getX(), offset.getY(), offset.getZ()), direction.getOpposite());

                    this.hitVecSource = getHitVec(this.currentPlacement.position, this.currentPlacement.facing);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean shouldBuild() {
        if (mc.player == null || mc.level == null) return false;
        BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
        return mc.level.getBlockState(below).canBeReplaced() && BlockUtil.isPlaceable(mc.player.getMainHandItem());
    }

    private boolean isValidPlacement(PlacementTarget target) {
        if (target == null || mc.player == null || this.hitVecSource == null) return false;
        if (!this.canBuildNow && this.clutch.getValue() && !this.clutchTestMode.getValue()) return true;

        BlockPos targetPos = BlockPos.containing(this.hitVecSource.x, this.hitVecSource.y, this.hitVecSource.z);
        return RayTraceUtil.canRayTrace(RotationHandler.targetRotation, target.facing, targetPos, false);
    }

    private PlacementTarget validateAndCorrectPlacement(PlacementTarget target) {
        if (target == null || mc.player == null || this.hitVecSource == null) return null;
        if (isValidPlacement(target)) return target;

        BlockPos targetPos = BlockPos.containing(this.hitVecSource.x, this.hitVecSource.y, this.hitVecSource.z);
        for (Direction dir : Direction.values()) {
            if (dir == target.facing) continue;
            if (RayTraceUtil.canRayTrace(RotationHandler.targetRotation, dir, targetPos, false)) {
                return new PlacementTarget(target.position, dir);
            }
        }
        return null;
    }

    private void doSnap() {
        if (this.currentPlacement == null || mc.player == null || mc.gameMode == null) return;
        PlacementTarget corrected = validateAndCorrectPlacement(this.currentPlacement);
        if (corrected == null) return;
        if (!corrected.facing.equals(this.currentPlacement.facing)) {
            this.currentPlacement = corrected;
        }
        doSnapInternal();
    }

    private void doSnapInternal() {
        if (this.currentPlacement == null || mc.player == null || mc.gameMode == null || this.hitVecSource == null) return;
        if (this.placeDelayCounter > 0) {
            this.placeDelayCounter--;
            return;
        }
        this.placeDelayCounter = this.placeDelay.getValue().intValue() - 1;
        if (!BlockUtil.isPlaceable(mc.player.getMainHandItem())) return;
        Direction facing = this.currentPlacement.facing;
        BlockPos targetBuildPos = this.currentPlacement.position.relative(facing);
        if (!mc.level.getBlockState(targetBuildPos).canBeReplaced()) {
            return;
        }

        boolean jumpHeld = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
        if (facing == null) return;
        if (facing == Direction.UP && !mc.player.onGround() && MovementUtil.isMoving() && !jumpHeld && !this.mode.is("Normal")) {
            return;
        }
        if (!this.shouldBuild()) return;
        if (this.mode.is("Telly Bridge")
                && this.smoothTelly.getValue()
                && this.hitVecSource != null
                && this.tickRotationSnapshot != null) {

            Rotation target =
                    RotationUtil.rotationFromVec(this.hitVecSource);

            Rotation sent = this.tickRotationSnapshot;

            double yawDiff =
                    RotationUtil.angleDiffDouble(
                            target.getYaw(),
                            sent.getYaw()
                    );

            double pitchDiff =
                    Math.abs(target.getPitch() - sent.getPitch());
            if (yawDiff > 12.0 || pitchDiff > 8.0) {
                this.rots.setYawPitch(target.getYaw(), target.getPitch());
                this.tickRotationSnapshot.setYawPitch(target.getYaw(), target.getPitch());
                RotationHandler.setTargetRotation(this.rots);
                ChatUtil.print("Corrected Rotation");
            }
        }
        if (this.interactBeforePlace.getValue()) {
            PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
        }
        BlockHitResult hit = new BlockHitResult(this.hitVecSource, facing, this.currentPlacement.position, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result == InteractionResult.SUCCESS) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private boolean isLookingAtTarget() {
        if (this.currentPlacement == null || RotationHandler.targetRotation == null || mc.player == null || this.hitVecSource == null) return false;

        Vec3 hitVec = this.hitVecSource;
        BlockPos targetPos = BlockPos.containing(hitVec.x, hitVec.y, hitVec.z);
        return RayTraceUtil.canRayTrace(RotationHandler.targetRotation, this.currentPlacement.facing, targetPos, false);
    }

    private Rotation getOptimalLookRotation() {
        if (this.currentPlacement == null || mc.player == null || this.hitVecSource == null) return null;
        return RotationUtil.rotationFromVec(this.hitVecSource);
    }

    private void adjustLookAtRotation() {
        if (this.currentPlacement == null || mc.player == null) return;
        if (!this.shouldBuild()) return;
        if (this.canBuildNow && this.needsLookAdjustment && this.rots != null) {
            Rotation optimal = this.getOptimalLookRotation();
            if (optimal != null) {
                Rotation smooth = RotationUtil.smoothRotation(this.rots, optimal,
                        (this.mode.is("Telly Bridge") && this.smoothTelly.getValue())
                                ? Math.max(8.0, 90.0 / this.rotationTick.getValue().doubleValue())
                                : 45.0);
                if (smooth != null) {
                    this.rots.setYawPitch(smooth.getYaw(), smooth.getPitch());
                    this.correctRotation.setYawPitch(smooth.getYaw(), smooth.getPitch());
                }
            }
        }
    }

    private void updateLookAtStatus() {
        if (this.currentPlacement == null) {
            this.needsLookAdjustment = false;
            return;
        }
        this.needsLookAdjustment = !this.isLookingAtTarget();
    }

    public static boolean isOnBlockEdge(float inflate) {
        if (mc.level == null || mc.player == null) return false;
        return !mc.level.getCollisions(mc.player,
                mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-inflate, 0.0, -inflate))
                .iterator().hasNext();
    }

    private Rotation getTargetRotation(boolean firstGround) {
        if (mc.player == null) return null;
        if (!MovementUtil.isInputActive()) return this.getPlayerYawRotation();
        if (this.currentPlacement == null || this.hitVecSource == null) return new Rotation(mc.player.getYRot(), mc.player.getXRot());

        Rotation rotation = RotationUtil.rotationFromVec(this.hitVecSource);
        double yawDelta = RotationUtil.angleDiffDouble(rotation.getYaw(), RotationHandler.prevRotation.getYaw());
        if (this.groundTicks > 0) {
            if (!mc.options.keyJump.isDown()) {
                return new Rotation(mc.player.getYRot(), 75.5f);
            }
            switch (this.groundTicks) {
                case 1:
                    if (!firstGround) {
                        rotation.setYaw(RotationHandler.prevRotation.getYaw()
                                + RotationUtil.clampAngle((float) yawDelta, (float) (yawDelta / 2.0)));
                        rotation.setPitch(75.5f);
                    } else {
                        rotation = RotationUtil.rotationFromVec(this.hitVecSource);
                    }
                    ReflectionUtil.setJumpDelay(2);
                    break;
                case 2:
                    return new Rotation(mc.player.getYRot(), 75.5f);
                default:
                    break;
            }
        } else {
            float clampLimit;

            if (this.mode.is("Telly Bridge")
                    && this.smoothTelly.getValue()) {

                clampLimit = (float) (
                        180.0 / Math.max(
                                1.0,
                                this.rotationTick.getValue().doubleValue()
                        )
                );

                clampLimit *= getDynamicLimitMultiplier();
                //ChatUtil.print("AngleLimit: change to" + " " + clampLimit);

            } else {
                clampLimit = this.airTicks == 1 ? 90.0f : 50.0f;
            }
            clampLimit -= RandomUtils.nextFloat(0.001f, 0.006f);
            rotation.setYaw(RotationHandler.prevRotation.getYaw()
                    + RotationUtil.clampAngle((float) yawDelta, clampLimit));
        }
        rotation = this.findValidRotation(rotation, firstGround);
        return this.getSnappedRotation(rotation);
    }

    private Rotation findValidRotation(Rotation rotation, boolean firstGround) {
        if (mc.player == null || firstGround) return rotation;
        Rotation reference = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : (RotationHandler.targetRotation != null
                ? RotationHandler.targetRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot()));
        Rotation optimal = this.getOptimalRotation(rotation, reference.getYaw());
        double maxStep = Math.max(45.0, 180.0 / Math.max(1.0, this.rotationTick.getValue().doubleValue()));
        if (this.mode.is("Telly Bridge")
                && !this.smoothTelly.getValue()) {
            maxStep = Math.max(maxStep, 75.0);
        }
        return RotationUtil.smoothRotation(reference, optimal, maxStep);
    }

    private Rotation getSnappedRotation(Rotation rotation) {
        if (mc.player == null) return rotation;
        Rotation reference = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : (RotationHandler.targetRotation != null
                ? RotationHandler.targetRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot()));
        this.yawDiff = Math.abs(Mth.wrapDegrees(rotation.getYaw() - reference.getYaw()));
        this.pitchDiff = Math.abs(rotation.getPitch() - reference.getPitch());
        boolean stuckPitch = this.pitchDiff > 2.0
                && !Double.isNaN(this.lastPitchDiff)
                && Math.abs(this.pitchDiff - this.lastPitchDiff) < 1.0e-4;
        boolean stuckYaw = this.yawDiff > 2.0
                && !Double.isNaN(this.lastYawDiff)
                && Math.abs(this.yawDiff - this.lastYawDiff) < 1.0e-4;
        if (stuckPitch || stuckYaw) {
            rotation = rotation.clone();
            float jitterYaw = MathUtil.randomFloat(0.095f, 0.19f);
            float jitterPitch = MathUtil.randomFloat(0.016f, 0.055f);
            if ((this.jitterCounter++ & 1) == 0) {
                jitterYaw = -jitterYaw;
            }
            rotation.setYaw(rotation.getYaw() + jitterYaw);
            rotation.setPitch(Mth.clamp(rotation.getPitch() + jitterPitch, -89.5f, 89.5f));
            this.yawDiff = Math.abs(Mth.wrapDegrees(rotation.getYaw() - reference.getYaw()));
            this.pitchDiff = Math.abs(rotation.getPitch() - reference.getPitch());
        }
        this.lastYawDiff = this.yawDiff;
        this.lastPitchDiff = this.pitchDiff;
        return rotation;
    }

    private Rotation getOptimalRotation(Rotation rotation, float referenceYaw) {
        if (mc.player == null) return rotation;
        Rotation optimal = rotation.clone();
        double limit = 90.0;
        double delta = Mth.wrapDegrees(optimal.getYaw() - referenceYaw);
        if (Math.abs(delta) > limit) {
            optimal.setYaw((float) (referenceYaw + Math.copySign(limit, delta)));
        }
        return optimal;
    }

    public static Vec3 getHitVec(BlockPos pos, Direction direction) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        if (direction != Direction.UP && direction != Direction.DOWN) {
            y += MathUtil.randomDouble(0.3, -0.3);
        } else {
            x += MathUtil.randomDouble(0.3, -0.3);
            z += MathUtil.randomDouble(0.3, -0.3);
        }
        if (direction == Direction.WEST || direction == Direction.EAST) {
            z += MathUtil.randomDouble(0.3, -0.3);
        }
        if (direction == Direction.SOUTH || direction == Direction.NORTH) {
            x += MathUtil.randomDouble(0.3, -0.3);
        }
        return new Vec3(x, y, z);
    }

    private record PlacementTarget(BlockPos position, Direction facing) {
    }
}
