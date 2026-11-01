package client.nilore.modules.impl.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.utils.rotation.RotationSmoother;

public class PacketBreak
extends Module {
    public static PacketBreak INSTANCE;

    private static final double FACE_INSET = 0.06;
    private static final double[] FACE_FRACTIONS = {0.5, 0.22, 0.78};
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double MAX_SERVER_BLOCK_DISTANCE = 5.5;

    private final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final BooleanSetting rotate = new BooleanSetting("Rotate", true);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 45, 1, 180, 1,
            () -> this.rotate.getValue());
    private final BooleanSetting swingSetting = new BooleanSetting("Swing", true);
    private final BooleanSetting hold = new BooleanSetting("Hold", true);
    private final BooleanSetting esp = new BooleanSetting("ESP", true);
    private final BooleanSetting showProgress = new BooleanSetting("Progress", true);
    private final ModeSetting progressMode = new ModeSetting("Mode", "Up", "In", "Out");
    private final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting green = new NumberSetting("Green", 255, 0, 255, 1);
    private final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1);

    private BlockPos targetPos;
    private float accumulated;
    private boolean landed;
    private boolean finishPending;

    public Rotation targetRotation;
    private Vec3 aimPoint;
    private Direction aimFace;
    private BlockPos aimPos;
    private final RotationSmoother smoother = new RotationSmoother(new Random());

    public PacketBreak() {
        super("PacketBreak", Category.WORLD);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.clearTarget();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        this.clearTarget();
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!(packetEvent.getPacket() instanceof ServerboundPlayerActionPacket actionPacket)) {
            return;
        }
        BlockPos pos = actionPacket.getPos();
        if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            if (!this.isRealViewTarget(pos)) {
                return;
            }
            this.targetPos = pos.immutable();
            this.accumulated = 0.0f;
            this.landed = false;
            this.finishPending = false;
            this.resetAim();
            if (mc.player.onGround()) {
                this.landed = true;
            }
        } else if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && this.hold.getValue()
                && this.targetPos != null
                && this.targetPos.equals(pos)) {
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        BlockPos pos = this.targetPos;
        if (pos == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (!this.isValid(pos)) {
            this.clearTarget();
            return;
        }
        if (this.rotate.getValue()) {
            if (!this.updateAim(pos)) {
                this.clearTarget();
                return;
            }
        } else if (this.targetRotation != null) {
            this.resetAim();
        }
        if (mc.player.onGround()) {
            this.landed = true;
        }
        //
        if (this.rotate.getValue() && !this.hitsBlock(pos, mc.player.getEyePosition(1.0f), this.wireRotation())) {
            return;
        }
        float damage = this.tickDamage(pos, this.landed);
        if (damage <= 0.0f) {
            this.clearTarget();
            return;
        }
        this.accumulated = Math.min(1.0f, this.accumulated + damage);
        if (this.swingSetting.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            PacketUtil.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
        if (!this.finishPending && this.accumulated >= 1.0f) {
            this.finishPending = true;
            return;
        }
        if (this.finishPending) {
            Direction face = this.resolveFace(pos);
            PacketUtil.sendPredictiveDirect(sequence -> new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, sequence));
            this.clearTarget();
        }
    }

    private boolean updateAim(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        boolean stale = this.aimPoint == null
                || !pos.equals(this.aimPos)
                || !this.hitsBlock(pos, eye, RotationUtil.rotationToPoint(eye, this.aimPoint));
        if (stale) {
            Vec3 found = this.findAimPoint(pos, eye);
            if (found == null) {
                return false;
            }
            this.aimPoint = found;
            this.aimPos = pos.immutable();
            this.smoother.reset();
        }
        Rotation aim = RotationUtil.rotationToPoint(eye, this.aimPoint);
        Rotation from = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Rotation smoothed = this.smoother.tick(from, aim, this.rotationSpeed.getValue().doubleValue());
        this.targetRotation = RotationSmoother.patchConstantRotation(smoothed, from);
        return true;
    }

    private Vec3 findAimPoint(BlockPos pos, Vec3 eye) {
        AABB box = BlockUtil.getBoundingBox(pos);
        Vec3 center = box.getCenter();
        double reach = this.range.getValue().doubleValue();
        Rotation current = RotationHandler.sentRotation != null
                ? RotationHandler.sentRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Vec3 best = null;
        Direction bestFace = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : DIRECTIONS) {
            double toward = face.getStepX() * (eye.x - center.x)
                    + face.getStepY() * (eye.y - center.y)
                    + face.getStepZ() * (eye.z - center.z);
            if (toward <= 0.0) {
                continue;
            }
            for (double u : FACE_FRACTIONS) {
                for (double v : FACE_FRACTIONS) {
                    Vec3 point = facePoint(box, face, u, v);
                    if (point.distanceTo(eye) > reach) {
                        continue;
                    }
                    Rotation candidate = RotationUtil.rotationToPoint(eye, point);
                    BlockHitResult hit = this.traceBlock(pos, eye, candidate);
                    if (hit == null) {
                        continue;
                    }
                    if (hit.getDirection() != face) {
                        continue;
                    }
                    double score = Math.abs(Mth.wrapDegrees(candidate.getYaw() - current.getYaw()))
                            + Math.abs(candidate.getPitch() - current.getPitch()) * 1.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = point;
                        bestFace = face;
                    }
                }
            }
        }
        this.aimFace = bestFace;
        return best;
    }

    private static Vec3 facePoint(AABB box, Direction face, double u, double v) {
        double minX = box.minX + FACE_INSET;
        double maxX = box.maxX - FACE_INSET;
        double minY = box.minY + FACE_INSET;
        double maxY = box.maxY - FACE_INSET;
        double minZ = box.minZ + FACE_INSET;
        double maxZ = box.maxZ - FACE_INSET;
        return switch (face) {
            case DOWN -> new Vec3(lerp(minX, maxX, u), box.minY, lerp(minZ, maxZ, v));
            case UP -> new Vec3(lerp(minX, maxX, u), box.maxY, lerp(minZ, maxZ, v));
            case NORTH -> new Vec3(lerp(minX, maxX, u), lerp(minY, maxY, v), box.minZ);
            case SOUTH -> new Vec3(lerp(minX, maxX, u), lerp(minY, maxY, v), box.maxZ);
            case WEST -> new Vec3(box.minX, lerp(minY, maxY, u), lerp(minZ, maxZ, v));
            case EAST -> new Vec3(box.maxX, lerp(minY, maxY, u), lerp(minZ, maxZ, v));
        };
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private BlockHitResult traceBlock(BlockPos pos, Vec3 eye, Rotation rotation) {
        Vec3 direction = RotationUtil.directionFromRotation(rotation);
        Vec3 end = eye.add(direction.scale(this.range.getValue().doubleValue() + 0.5));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }

    private boolean hitsBlock(BlockPos pos, Vec3 eye, Rotation rotation) {
        return this.traceBlock(pos, eye, rotation) != null;
    }

    private Rotation wireRotation() {
        if (RotationHandler.sentRotation != null) {
            return RotationHandler.sentRotation;
        }
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private void resetAim() {
        this.aimPoint = null;
        this.aimFace = null;
        this.aimPos = null;
        this.targetRotation = null;
        this.smoother.reset();
    }

    private float tickDamage(BlockPos pos, boolean forceGround) {
        BlockState blockState = mc.level.getBlockState(pos);
        float damage = blockState.getDestroyProgress(mc.player, mc.level, pos);
        if (forceGround && !mc.player.onGround()) {
            damage *= 5.0f;
        }
        return damage;
    }

    private boolean isRealViewTarget(BlockPos pos) {
        if (mc.hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            return true;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double reach = this.range.getValue().doubleValue();
        Vec3 end = eye.add(RotationUtil.directionFromRotation(this.realRotation()).scale(reach));
        return BlockUtil.getBoundingBox(pos).clip(eye, end).isPresent();
    }

    private Rotation realRotation() {
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private Direction resolveFace(BlockPos pos) {
        if (this.aimFace != null && pos.equals(this.aimPos)) {
            return this.aimFace;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return dy >= 0.0 ? Direction.UP : Direction.DOWN;
        }
        return dz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isValid(BlockPos pos) {
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }
        if (blockState.getDestroySpeed(mc.level, pos) < 0.0f) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double distance = RotationUtil.closestPoint(eye, BlockUtil.getBoundingBox(pos)).distanceTo(eye);
        if (distance > this.range.getValue().doubleValue()) {
            return false;
        }
        return eye.distanceTo(Vec3.atCenterOf(pos)) <= MAX_SERVER_BLOCK_DISTANCE;
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        BlockPos pos = this.targetPos;
        if (!this.esp.getValue() || pos == null || mc.level == null || mc.player == null) {
            return;
        }
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        AABB box = BlockUtil.getBoundingBox(pos);
        float red = this.red.getValue().floatValue() / 255.0f;
        float green = this.green.getValue().floatValue() / 255.0f;
        float blue = this.blue.getValue().floatValue() / 255.0f;
        poseStack.pushPose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.setShaderColor(red, green, blue, 0.85f);
        RenderUtil.drawOutlineBox(box, poseStack);
        if (this.showProgress.getValue() && this.accumulated > 0.0f) {
            RenderSystem.setShaderColor(red, green, blue, 0.35f);
            RenderUtil.drawSolidBox(this.progressBox(box), poseStack);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        poseStack.popPose();
    }

    private AABB progressBox(AABB box) {
        float progress = Math.max(0.0f, Math.min(1.0f, this.accumulated));
        if (this.progressMode.is("In") || this.progressMode.is("Out")) {
            double scale = Math.max(0.001, this.progressMode.is("In") ? 1.0 - progress : progress);
            double centerX = (box.minX + box.maxX) / 2.0;
            double centerY = (box.minY + box.maxY) / 2.0;
            double centerZ = (box.minZ + box.maxZ) / 2.0;
            double halfX = (box.maxX - box.minX) / 2.0 * scale;
            double halfY = (box.maxY - box.minY) / 2.0 * scale;
            double halfZ = (box.maxZ - box.minZ) / 2.0 * scale;
            return new AABB(centerX - halfX, centerY - halfY, centerZ - halfZ,
                    centerX + halfX, centerY + halfY, centerZ + halfZ).inflate(0.002);
        }
        double height = box.maxY - box.minY;
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + height * progress, box.maxZ)
                .inflate(0.002);
    }

    private void clearTarget() {
        this.targetPos = null;
        this.accumulated = 0.0f;
        this.landed = false;
        this.finishPending = false;
        this.resetAim();
    }
}
