package shit.lizz.modules.impl.misc;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.combat.KillAura;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.math.MathUtil;
import shit.lizz.utils.rotation.Rotation;
import shit.lizz.event.EventTarget;

public class AimAssist
extends Module {
    private final NumberSetting randomYawOffset = new NumberSetting("Random Yaw Offset", 2, 0, 10, 0.01);
    private final NumberSetting randomPitchOffset = new NumberSetting("Random Pitch Offset", 0.075f, 0, 1, 0.01);
    private final NumberSetting range = new NumberSetting("Range", 5, 3, 30, 0.1);
    private final NumberSetting fov = new NumberSetting("Fov", 120, 1, 360, 1);
    private final BooleanSetting mouseDown = new BooleanSetting("Mouse down", true);
    private final BooleanSetting adaptive = new BooleanSetting("Adaptive", true);
    private final NumberSetting adaptiveOffset = new NumberSetting("Adaptive Offset", 3, 0.1f, 15.0f, 0.01);
    private final NumberSetting smoothAmount = new NumberSetting("Smooth amount", 15, 1.0f, 90.0f, 0.1);
    private final BooleanSetting breakBlock = new BooleanSetting("Break Block", true);
    private Vec3 targetOffset;
    private Vec3 aimOffset;
    private boolean isPitchAdjusting = false;

    public AimAssist() {
        super("AimAssist", Category.MISC);
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        double range = this.range.getValue().doubleValue();
        boolean mouseDownOnly = this.mouseDown.getValue();
        double fov = this.fov.getValue().doubleValue();
        double randYaw = this.randomYawOffset.getValue().doubleValue();
        double randPitch = this.randomPitchOffset.getValue().doubleValue();
        boolean adaptive = this.adaptive.getValue();
        double adaptiveOff = this.adaptiveOffset.getValue().doubleValue();
        double smooth = this.smoothAmount.getValue().doubleValue();
        boolean breakBlock = this.breakBlock.getValue();
        if (mouseDownOnly && !mc.options.keyAttack.isDown()) {
            this.targetOffset = new Vec3(0.0, 0.0, 0.0);
            return;
        }
        if (breakBlock && mc.options.keyAttack.isDown()
                && mc.hitResult != null
                && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            this.targetOffset = new Vec3(0.0, 0.0, 0.0);
            return;
        }
        float frameTime = mc.getFrameTime();
        List<? extends Player> list = mc.level.players();
        LocalPlayer localPlayer = mc.player;
        Vec3 eye = localPlayer.position().add(new Vec3(0.0, localPlayer.getEyeHeight(), 0.0));
        Rotation currentRot = this.getCurrentRotation();
        Player closest = null;
        float bestDist = Float.MAX_VALUE;
        for (Player candidate : list) {
            if (candidate == null || candidate.equals(localPlayer) || !this.isValidTarget(candidate)) continue;
            float eyeHeight = candidate.getEyeHeight();
            Rotation eyeRot = RotationUtil.normalizeRotation(currentRot.negate().subtract(
                    RotationUtil.rotationTo(eye, candidate.position().add(new Vec3(0.0, eyeHeight, 0.0))).negate()));
            if (eyeRot.getYaw() < 0.0f) eyeRot.setYaw(-eyeRot.getYaw());
            if (eyeRot.getPitch() < 0.0f) eyeRot.setPitch(-eyeRot.getPitch());
            float dist = (float) localPlayer.position().subtract(candidate.position()).length();
            if (Math.abs(eyeRot.getYaw()) <= fov && dist <= range && bestDist > dist) {
                closest = candidate;
                bestDist = dist;
            }
        }
        if (closest == null || !this.isValidTarget(closest)) {
            this.targetOffset = new Vec3(0.0, 0.0, 0.0);
            return;
        }
        Vec3 closestPos = closest.position();
        Vec3 closestInterp = closest.getPosition(frameTime);
        float closestEyeHeight = closest.getEyeHeight();
        Vec3 closestEye = closestPos.add(new Vec3(0.0, closestEyeHeight, 0.0));
        Vec3 closestInterpEye = closestInterp.add(new Vec3(0.0, closestEyeHeight, 0.0));
        Rotation rotToFeet = RotationUtil.rotationTo(eye, closestPos);
        Rotation rotToEye = RotationUtil.rotationTo(eye, closestEye);
        Rotation rotEyeDelta = RotationUtil.normalizeRotation(currentRot.negate().subtract(rotToEye.negate()));
        Rotation rotFeetDelta = RotationUtil.normalizeRotation(currentRot.negate().subtract(rotToFeet.negate()));
        double yawOffset = MathUtil.randomDouble(-randYaw, randYaw);
        if (adaptive) {
            if (mc.options.keyRight.isDown() && !mc.options.keyLeft.isDown()) {
                yawOffset -= adaptiveOff;
            }
            if (mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown()) {
                yawOffset += adaptiveOff;
            }
        }
        float targetYaw = currentRot.getYaw() + (float)(((double)rotEyeDelta.getYaw() + yawOffset) / smooth);
        Vec3 playerEye = mc.player.getEyePosition(1.0f);
        if (currentRot.getPitch() > rotToFeet.getPitch() || currentRot.getPitch() < rotToEye.getPitch()) {
            float feetPitch = currentRot.getPitch() + (float)((double)rotFeetDelta.getPitch() / smooth);
            float eyePitch = currentRot.getPitch() + (float)((double)rotEyeDelta.getPitch() / smooth);
            float feetDiff = Math.abs(currentRot.getPitch() - feetPitch);
            float eyeDiff = Math.abs(currentRot.getPitch() - eyePitch);
            float targetPitch;
            if (feetDiff > eyeDiff) {
                targetPitch = eyePitch;
                this.targetOffset = playerEye.subtract(new Vec3(0.0, 0.21, 0.0)).subtract(closestInterpEye);
            } else {
                targetPitch = feetPitch;
                this.targetOffset = playerEye.subtract(new Vec3(0.0, 0.23, 0.0)).subtract(closestInterp);
            }
            this.isPitchAdjusting = true;
            this.applyRotation(new Rotation(targetYaw, targetPitch + (float) MathUtil.randomDouble(-randPitch, randPitch)));
        } else {
            this.targetOffset = playerEye.subtract(closestPos);
            this.isPitchAdjusting = false;
            this.applyRotation(new Rotation(targetYaw, currentRot.getPitch() + (float) MathUtil.randomDouble(-randPitch, randPitch)));
        }
    }

    private void applyRotation(Rotation rotation) {
        rotation.snapToSensitivity((float) mc.options.sensitivity().get().doubleValue());
        float yaw = rotation.getYaw();
        float pitch = rotation.getPitch();
        mc.player.setXRot(pitch);
        mc.player.setYRot(yaw);
        mc.player.setXRot(Mth.clamp(mc.player.getXRot(), -90.0f, 90.0f));
        mc.player.xRotO = pitch;
        mc.player.yRotO = yaw;
        mc.player.xRotO = Mth.clamp(mc.player.xRotO, -90.0f, 90.0f);
    }

    public boolean isValidTarget(LivingEntity livingEntity) {
        if (mc.player.distanceTo(livingEntity) > this.range.getValue().floatValue() || !KillAura.INSTANCE.isValidTarget(livingEntity)) {
            return false;
        }
        if (!mc.player.hasLineOfSight(livingEntity)) {
            return false;
        }
        return !livingEntity.isDeadOrDying() && !(livingEntity.getHealth() <= 0.0f);
    }

    private Rotation getCurrentRotation() {
        return new Rotation(mc.player.getRotationVector().y, mc.player.getRotationVector().x);
    }

    public static float getAngleDifference(double a, double b) {
        return Float.parseFloat(Double.valueOf(Mth.wrapDegrees(a - b)).toString());
    }

    private boolean isInFov(Rotation rotation, Entity entity) {
        float diff = AimAssist.getAngleDifference(entity.getYRot(), rotation.getYaw());
        return (double)Math.abs(diff) <= this.fov.getValue().doubleValue() / 2.0;
    }
}