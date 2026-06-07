package shit.lizz.utils.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.lizz.ClientBase;
import shit.lizz.utils.game.RayTraceUtil;

public class MotionSimulator {
    public double x;
    public double y;
    public double z;
    private double motionX;
    private double motionY;
    private double motionZ;
    private final float yaw;
    private final float strafeSpeed;
    private final float forwardSpeed;
    private float jumpPower;

    public MotionSimulator(double x, double y, double z, double motionX, double motionY, double motionZ, float yaw, float strafeSpeed, float forwardSpeed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.motionX = motionX;
        this.motionY = motionY;
        this.motionZ = motionZ;
        this.yaw = yaw;
        this.strafeSpeed = strafeSpeed;
        this.forwardSpeed = forwardSpeed;
    }

    public MotionSimulator(Player player) {
        this(player.getX(), player.getY(), player.getZ(), player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z, player.getYRot(), player.xxa, player.zza);
        float jumpPower;
        float currentJumpFactor = player.level().getBlockState(player.blockPosition()).getBlock().getJumpFactor();
        float belowJumpFactor = player.level().getBlockState(player.getOnPos()).getBlock().getJumpFactor();
        this.jumpPower = jumpPower = 0.42f * ((double)currentJumpFactor == 1.0 ? belowJumpFactor : currentJumpFactor) + player.getJumpBoostPower();
    }

    private void tick() {
        float strafe = this.strafeSpeed;
        float forward = this.forwardSpeed;
        float magSqr = strafe * strafe + forward * forward;
        if (magSqr >= 1.0E-4f) {
            if ((magSqr = Mth.sqrt(magSqr)) < 1.0f) {
                magSqr = 1.0f;
            }
            float speed = this.jumpPower;
            if (ClientBase.mc.player.isSprinting()) {
                speed *= 1.3f;
            }
            magSqr = speed / magSqr;
            float sinYaw = Mth.sin(this.yaw * (float)Math.PI / 180.0f);
            float cosYaw = Mth.cos(this.yaw * (float)Math.PI / 180.0f);
            this.motionX += (strafe *= magSqr) * cosYaw - (forward *= magSqr) * sinYaw;
            this.motionZ += forward * cosYaw + strafe * sinYaw;
        }
        this.motionY -= 0.08;
        this.motionY *= 0.98f;
        this.x += this.motionX;
        this.y += this.motionY;
        this.z += this.motionZ;
    }

    private void tickWithFriction() {
        float strafe = this.strafeSpeed * 0.98f;
        float forward = this.forwardSpeed * 0.98f;
        float magSqr = strafe * strafe + forward * forward;
        if (magSqr >= 1.0E-4f) {
            if ((magSqr = Mth.sqrt(magSqr)) < 1.0f) {
                magSqr = 1.0f;
            }
            float speed = this.jumpPower;
            if (ClientBase.mc.player.isSprinting()) {
                speed *= 1.3f;
            }
            magSqr = speed / magSqr;
            float sinYaw = Mth.sin(this.yaw * (float)Math.PI / 180.0f);
            float cosYaw = Mth.cos(this.yaw * (float)Math.PI / 180.0f);
            this.motionX += (strafe *= magSqr) * cosYaw - (forward *= magSqr) * sinYaw;
            this.motionZ += forward * cosYaw + strafe * sinYaw;
        }
        this.motionY -= 0.08;
        this.motionY *= 0.98f;
        this.x += this.motionX;
        this.y += this.motionY;
        this.z += this.motionZ;
        this.motionX *= 0.91;
        this.motionZ *= 0.91;
    }

    public BlockPos findLandingBlock(int maxTicks) {
        for (int i = 0; i < maxTicks; ++i) {
            Vec3 fromPos = new Vec3(this.x, this.y, this.z);
            this.tickWithFriction();
            Vec3 toPos = new Vec3(this.x, this.y, this.z);
            float halfWidth = ClientBase.mc.player.getBbWidth() / 2.0f;
            BlockPos hit = this.rayTraceBlock(fromPos, toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(halfWidth, 0.0, halfWidth), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(-halfWidth, 0.0, halfWidth), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(halfWidth, 0.0, -halfWidth), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(-halfWidth, 0.0, -halfWidth), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(halfWidth, 0.0, halfWidth / 2.0f), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(-halfWidth, 0.0, halfWidth / 2.0f), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(halfWidth / 2.0f, 0.0, halfWidth), toPos);
            if (hit != null) {
                return hit;
            }
            hit = this.rayTraceBlock(fromPos.add(halfWidth / 2.0f, 0.0, -halfWidth), toPos);
            if (hit == null) continue;
            return hit;
        }
        return null;
    }

    private BlockPos rayTraceBlock(Vec3 fromPos, Vec3 toPos) {
        BlockHitResult blockHitResult;
        HitResult hitResult = RayTraceUtil.clipWithEntity(fromPos, toPos, false, false, false, ClientBase.mc.player);
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK && hitResult instanceof BlockHitResult && (blockHitResult = (BlockHitResult)hitResult).getDirection() == Direction.UP) {
            return blockHitResult.getBlockPos();
        }
        return null;
    }

    public void simulate(int ticks) {
        for (int i = 0; i < ticks; ++i) {
            this.tick();
        }
    }

    public void simulateWithFriction(int ticks) {
        for (int i = 0; i < ticks; ++i) {
            this.tickWithFriction();
        }
    }
}
