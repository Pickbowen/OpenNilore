package client.nilore.utils.game;

import lombok.Generated;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.ClientBase;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.modules.impl.movement.TargetStrafe;

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

    /**
     * 把这一拍的输入重映射成"相对 {@code targetYaw} 的等价八向输入"。
     *
     * <p>口径照 OpenMyau 的 {@code MoveUtil.fixStrafe}：先算出输入在世界里指向哪个
     * yaw，再减去即将发给服务端的视角 {@code targetYaw}，落到 45° 扇区取整，最后
     * 从八向表里取对应的 {@code (forward, strafe)}。这样客户端 {@code moveRelative}
     * 用 {@code targetYaw} 旋转出来的世界方向，和玩家按键时的真实朝向一致。
     *
     * <p>和旧实现的两点差别：
     * <ul>
     *   <li>旧实现用 {@code Math.abs(target - candidate)} 比角度差，没有 wrap：
     *       目标 179°、候选 -179° 会算成 358° 而不是 2°，直接挑到反向的输入。
     *       现在统一走 {@link Mth#wrapDegrees} 后的扇区编号。</li>
     *   <li>旧实现枚举 9 个组合，边界（正好 22.5°）会抖；扇区法每 tick 只有一个解。</li>
     * </ul>
     */
    public static void handleStrafe(StrafeEvent strafeEvent, float targetYaw) {
        if (strafeEvent.isMoveFixed()) {
            return;
        }
        if (TargetStrafe.isOrbiting()) {
            TargetStrafe.ensureOrbitComputed();
            strafeEvent.setForward(TargetStrafe.radialStep());
            strafeEvent.setStrafe(TargetStrafe.strafeSign());
            strafeEvent.setMoveFixed(true);
            return;
        }
        float forward = strafeEvent.getForward();
        float strafe = strafeEvent.getStrafe();
        if (forward == 0.0f && strafe == 0.0f) {
            return;
        }
        float inputYaw = MovementUtil.adjustYaw(mc.player.getYRot(), forward, strafe);
        float relative = Mth.wrapDegrees(inputYaw - targetYaw + 22.5f);
        int sector = Mth.floor((relative + 180.0f) / 45.0f) % 8;
        if (sector < 0) {
            sector += 8;
        }
        switch (sector) {
            case 0 -> {
                strafeEvent.setForward(-1.0f);
                strafeEvent.setStrafe(0.0f);
            }
            case 1 -> {
                strafeEvent.setForward(-1.0f);
                strafeEvent.setStrafe(1.0f);
            }
            case 2 -> {
                strafeEvent.setForward(0.0f);
                strafeEvent.setStrafe(1.0f);
            }
            case 3 -> {
                strafeEvent.setForward(1.0f);
                strafeEvent.setStrafe(1.0f);
            }
            case 4 -> {
                strafeEvent.setForward(1.0f);
                strafeEvent.setStrafe(0.0f);
            }
            case 5 -> {
                strafeEvent.setForward(1.0f);
                strafeEvent.setStrafe(-1.0f);
            }
            case 6 -> {
                strafeEvent.setForward(0.0f);
                strafeEvent.setStrafe(-1.0f);
            }
            default -> {
                strafeEvent.setForward(-1.0f);
                strafeEvent.setStrafe(-1.0f);
            }
        }
        strafeEvent.setMoveFixed(true);
    }

    /** 这一拍的前后输入：-1 / 0 / 1（口径同 OpenMyau 的 getForwardValue）。 */
    public static int getForwardValue() {
        int value = 0;
        if (mc.options.keyUp.isDown()) {
            value++;
        }
        if (mc.options.keyDown.isDown()) {
            value--;
        }
        return value;
    }

    /** 这一拍的左右输入：左 +1 / 右 -1。 */
    public static int getLeftValue() {
        int value = 0;
        if (mc.options.keyLeft.isDown()) {
            value++;
        }
        if (mc.options.keyRight.isDown()) {
            value--;
        }
        return value;
    }

    /** 有没有按下任意一个移动键（前后或左右两个轴上都成对抵消时算没有）。 */
    public static boolean isForwardPressed() {
        if (mc.options.keyUp.isDown() != mc.options.keyDown.isDown()) {
            return true;
        }
        return mc.options.keyLeft.isDown() != mc.options.keyRight.isDown();
    }

    /**
     * 输入方向换算成世界 yaw（口径同 OpenMyau 的 {@code MoveUtil.adjustYaw}）。
     * {@code forward<0} 先翻半圈，再按 strafe 方向偏 90°；前后键同时按下时
     * 偏移量减半，正好对上原版 {@code moveRelative} 的斜向缩放。
     */
    public static float adjustYaw(float yaw, float forward, float strafe) {
        if (forward < 0.0f) {
            yaw += 180.0f;
        }
        if (strafe != 0.0f) {
            float multiplier = forward == 0.0f ? 1.0f : 0.5f * Math.signum(forward);
            yaw += -90.0f * multiplier * Math.signum(strafe);
        }
        return Mth.wrapDegrees(yaw);
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
