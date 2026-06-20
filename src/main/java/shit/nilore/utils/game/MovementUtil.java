package shit.nilore.utils.game;

import lombok.Generated;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.nilore.ClientBase;
import shit.nilore.event.impl.StrafeEvent;
import shit.nilore.modules.impl.movement.TargetStrafe;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.rotation.Rotation;

public final class MovementUtil
extends ClientBase {
    private static final String UTILITY_MSG = "This is a utility class and cannot be instantiated";

    public static boolean isMoving() {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    public static void strafeWithYaw(double yawRad, double speed) {
        if (!MovementUtil.isInputActive()) {
            return;
        }
        Vec3 deltaMovement = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(deltaMovement.x + (double)(-Mth.sin((float)yawRad)) * speed, deltaMovement.y, deltaMovement.z + (double)Mth.cos((float)yawRad) * speed);
    }

    public static void strafeForward(double speed) {
        if (!MovementUtil.isInputActive()) {
            return;
        }
        double movementYaw = MovementUtil.getMovementYaw();
        Vec3 deltaMovement = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(deltaMovement.x + (double)(-Mth.sin((float)movementYaw)) * speed, deltaMovement.y, deltaMovement.z + (double)Mth.cos((float)movementYaw) * speed);
    }

    public static double getMovementYaw() {
        float yaw = mc.player.getYRot();
        if (mc.player.zza < 0.0f) {
            yaw += 180.0f;
        }
        float strafeFactor = 1.0f;
        if (mc.player.zza < 0.0f) {
            strafeFactor = -0.5f;
        } else if (mc.player.zza > 0.0f) {
            strafeFactor = 0.5f;
        }
        if (mc.player.xxa > 0.0f) {
            yaw -= 90.0f * strafeFactor;
        } else if (mc.player.xxa < 0.0f) {
            yaw += 90.0f * strafeFactor;
        }
        return Math.toRadians(yaw);
    }

    public static double getBaseSpeed() {
        double baseSpeed = 0.2875;
        if (mc.player.hasEffect(MobEffects.MOVEMENT_SPEED)) {
            int amplifier = mc.player.getEffect(MobEffects.MOVEMENT_SPEED).getAmplifier();
            baseSpeed *= 1.0 + 0.2 * (double)(amplifier + 1);
        }
        return baseSpeed;
    }

    public static double hypot(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    public static double getSpeed() {
        return MovementUtil.hypot(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z);
    }

    public static void setSpeed(double speed) {
        float forward = mc.player.input.forwardImpulse;
        float strafe = mc.player.input.leftImpulse;
        float yaw = mc.player.getYRot();
        if (forward == 0.0f && strafe == 0.0f) {
            mc.player.setDeltaMovement(0.0, mc.player.getDeltaMovement().y, 0.0);
            return;
        }
        if (forward != 0.0f && strafe != 0.0f) {
            forward = (float)((double)forward * Math.sin(0.7853981633974483));
            strafe = (float)((double)strafe * Math.cos(0.7853981633974483));
        }
        double motionX = (double)forward * speed * -Math.sin(Math.toRadians(yaw)) + (double)strafe * speed * Math.cos(Math.toRadians(yaw));
        double motionZ = (double)forward * speed * Math.cos(Math.toRadians(yaw)) - (double)strafe * speed * -Math.sin(Math.toRadians(yaw));
        mc.player.setDeltaMovement(motionX, mc.player.getDeltaMovement().y, motionZ);
    }

    public static boolean isAboveVoid(double x, double y, double z) {
        while (y > 0.0) {
            Vec3 startPos = new Vec3(x, y, z);
            Vec3 endPos = new Vec3(x, y - 1.0, z);
            BlockHitResult blockHitResult = mc.level.clip(new ClipContext(startPos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (blockHitResult != null && blockHitResult.getType() != HitResult.Type.MISS) {
                return false;
            }
            y -= 1.0;
        }
        return true;
    }

    public static void stop() {
        mc.player.setDeltaMovement(0.0, mc.player.getDeltaMovement().y, 0.0);
    }

    public static double getSpeedHypot() {
        return Math.hypot(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z);
    }

    public static void handleStrafe(StrafeEvent strafeEvent, float partialTicks) {
        float forward = strafeEvent.getForward();
        float strafe = strafeEvent.getStrafe();
        float yaw = mc.player.getYRot();
        if (TargetStrafe.strafeTarget != null && TargetStrafe.INSTANCE.isEnabled() && (!TargetStrafe.isSmartStrafe() || mc.options.keyJump.isDown())) {
            float yawOffset = (float)(MovementUtil.getBaseSpeed() / ((double)TargetStrafe.getRange() * Math.PI * 2.0) * 360.0) * (float)TargetStrafe.strafeDirectionSign;
            Rotation rotation = RotationUtil.rotationToForBow(new Vec3(TargetStrafe.strafeTarget.getX(), TargetStrafe.strafeTarget.getY(), TargetStrafe.strafeTarget.getZ()), new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            rotation.setYaw(rotation.getYaw() + yawOffset);
            float yawRad = rotation.getYaw() * ((float)Math.PI / 180);
            double circleX = TargetStrafe.strafeTarget.getX() - Math.sin(yawRad) * (double)TargetStrafe.getRange();
            double circleZ = TargetStrafe.strafeTarget.getZ() + Math.cos(yawRad) * (double)TargetStrafe.getRange();
            yaw = (float)Math.toDegrees(RotationUtil.rotationToForBow(new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ()), new Vec3(circleX, TargetStrafe.strafeTarget.getY(), circleZ)).getYaw() * ((float)Math.PI / 180));
        }
        double targetDirection = Mth.wrapDegrees(Math.toDegrees(MovementUtil.getDirectionYaw(yaw, forward, strafe)));
        if (forward == 0.0f && strafe == 0.0f) {
            return;
        }
        int bestForward = 0;
        int bestStrafe = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = -1; i <= 1; ++i) {
            for (int j = -1; j <= 1; ++j) {
                double candidateDir;
                double diff;
                if (j == 0 && i == 0 || !((diff = Math.abs(targetDirection - (candidateDir = Mth.wrapDegrees(Math.toDegrees(MovementUtil.getDirectionYaw(partialTicks, i, j)))))) < (double)bestDiff)) continue;
                bestDiff = (float)diff;
                bestForward = i;
                bestStrafe = j;
            }
        }
        strafeEvent.setForward(bestForward);
        strafeEvent.setStrafe(bestStrafe);
    }

    public static boolean isInputActive() {
        return mc.player != null && mc.level != null && ((double)mc.player.input.forwardImpulse != 0.0 || (double)mc.player.input.leftImpulse != 0.0);
    }

    public static double getDirectionYaw(float yaw, double forward, double strafe) {
        if (forward < 0.0) {
            yaw += 180.0f;
        }
        float strafeFactor = 1.0f;
        if (forward < 0.0) {
            strafeFactor = -0.5f;
        } else if (forward > 0.0) {
            strafeFactor = 0.5f;
        }
        if (strafe > 0.0) {
            yaw -= 90.0f * strafeFactor;
        }
        if (strafe < 0.0) {
            yaw += 90.0f * strafeFactor;
        }
        return Math.toRadians(yaw);
    }

    private static float getDirectionAngle(float forward, float strafe) {
        boolean hasForward;
        float yaw = mc.player.getYRot();
        boolean forwardPositive = forward > 0.0f;
        boolean forwardNegative = forward < 0.0f;
        boolean strafePositive = strafe > 0.0f;
        boolean strafeNegative = strafe < 0.0f;
        boolean hasStrafe = strafePositive || strafeNegative;
        boolean hasForwardTmp = hasForward = forwardPositive || forwardNegative;
        if (forward != 0.0f || strafe != 0.0f) {
            if (forwardNegative && !hasStrafe) {
                return yaw + 180.0f;
            }
            if (forwardPositive && strafeNegative) {
                return yaw + 45.0f;
            }
            if (forwardPositive && strafePositive) {
                return yaw - 45.0f;
            }
            if (!hasForward && strafeNegative) {
                return yaw + 90.0f;
            }
            if (!hasForward && strafePositive) {
                return yaw - 90.0f;
            }
            if (forwardNegative && strafeNegative) {
                return yaw + 135.0f;
            }
            if (forwardNegative) {
                return yaw - 135.0f;
            }
        }
        return yaw;
    }

    public static double getEntitySpeed(Entity entity) {
        return Math.hypot(entity.getX() - entity.xo, entity.getZ() - entity.zo);
    }

    public static double getSpeedBps() {
        return MovementUtil.getEntitySpeed(mc.player) * 20.0 * 1.0;
    }

    @Generated
    private MovementUtil() {
        throw new UnsupportedOperationException(UTILITY_MSG);
    }
}
