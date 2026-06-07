package shit.lizz.utils.game;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import lombok.Generated;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.antlr.v4.runtime.misc.OrderedHashSet;
import shit.lizz.ClientBase;
import shit.lizz.utils.math.MathUtil;
import shit.lizz.utils.rotation.Rotation;
import shit.lizz.utils.rotation.RotationHandler;

public final class RotationUtil
extends ClientBase {
    public record BestHitInfo(Vec3 hitPoint, Vec3 closestPoint, double distance, Rotation rotation) {
    }

    public static Rotation normalizeRotation(Rotation rotation) {
        return new Rotation(Mth.wrapDegrees(rotation.getYaw()), Mth.wrapDegrees(rotation.getPitch()));
    }

    public static Rotation smoothRotation(Rotation current, Rotation target, double speed) {
        float targetYaw = target.getYaw();
        float targetPitch = target.getPitch();
        float currYaw = current.getYaw();
        float currPitch = current.getPitch();
        if (speed != 0.0) {
            float maxStep = (float)speed;
            double yawDiff = Mth.wrapDegrees(target.getYaw() - current.getYaw());
            double pitchDiff = targetPitch - currPitch;
            double dist = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            double yawRatio = Math.abs(yawDiff / dist);
            double pitchRatio = Math.abs(pitchDiff / dist);
            double maxYawStep = (double)maxStep * yawRatio;
            double maxPitchStep = (double)maxStep * pitchRatio;
            float yawStep = (float)Math.max(Math.min(yawDiff, maxYawStep), -maxYawStep);
            float pitchStep = (float)Math.max(Math.min(pitchDiff, maxPitchStep), -maxPitchStep);
            targetYaw = currYaw + yawStep;
            targetPitch = currPitch + pitchStep;
        }
        boolean addJitter = Math.random() > 0.8;
        for (int i = 1; i <= (int)(2.0 + Math.random() * 2.0); ++i) {
            Rotation candidate;
            Rotation snapped;
            if (addJitter) {
                targetYaw += (float)((Math.random() - 0.5) / 1.0E8);
                targetPitch -= (float)(Math.random() / 2.0E8);
            }
            if ((snapped = (candidate = new Rotation(targetYaw, targetPitch)).snapToSensitivity(mc.options.sensitivity().get().floatValue())) == null) continue;
            targetYaw = snapped.getYaw();
            targetPitch = Mth.clamp(snapped.getPitch(), -90.0f, 90.0f);
        }
        return new Rotation(targetYaw, targetPitch);
    }

    public static float clampAngle(float angle, float max) {
        if (Math.abs(angle) < max) {
            return angle;
        }
        if (angle > 0.0f) {
            return max;
        }
        if (angle < 0.0f) {
            return -max;
        }
        return 0.0f;
    }

    public static Rotation rotationTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = RotationUtil.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = RotationUtil.toDegrees(-Math.atan2(dy, horizontalDist));
        return new Rotation(yaw, pitch);
    }

    public static float toDegrees(double radians) {
        return (float)(radians * 180.0 / Math.PI);
    }

    public static Rotation rotationToForBow(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        return RotationUtil.rotationFromDeltas(dx, dy, dz);
    }

    public static boolean isLookingAt(float maxAngle, LivingEntity livingEntity) {
        if (mc.player == null) {
            return false;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 targetPos = new Vec3(livingEntity.getX(), livingEntity.getY() + (double)livingEntity.getBbHeight() * 0.5, livingEntity.getZ());
        Vec3 lookVec = mc.player.getLookAngle();
        Vec3 deltaVec = targetPos.subtract(eyePos);
        if (deltaVec.lengthSqr() < 1.0E-7) {
            return true;
        }
        Vec3 deltaNorm = deltaVec.normalize();
        double dot = lookVec.dot(deltaNorm);
        double angleDegrees = Math.toDegrees(Math.acos(dot));
        return maxAngle >= 180.0f || angleDegrees <= (double)maxAngle;
    }

    public static Rotation rotationFromEyes(Vec3 target) {
        if (mc.player == null) {
            return null;
        }
        return RotationUtil.bowRotation(mc.player.position().add(0.0, mc.player.getEyeHeight(), 0.0), target);
    }

    public static Rotation bowRotation(Vec3 from, Vec3 to) {
        Vec3 delta = to.add(0.0, -0.7, 0.0).subtract(from);
        double horizontalDist = Math.hypot(delta.x, delta.z);
        double gravity = 0.03;
        double velocity = 1.5;
        float yaw = (float)(Mth.atan2(delta.z, delta.x) * 57.29577951308232) - 90.0f;
        double normalizedDist = horizontalDist / 1.5;
        double drop = 0.015 * normalizedDist * normalizedDist;
        double adjustedDy = delta.y + drop;
        double pitchRad = Math.atan2(adjustedDy, horizontalDist);
        float pitch = (float)(-(pitchRad * 57.29577951308232));
        return new Rotation(yaw, pitch);
    }

    public static Rotation rotationToBlock(BlockPos blockPos, float partialTicks) {
        Vec3 predictedPos = new Vec3(mc.player.getX() + mc.player.getDeltaMovement().x * (double)partialTicks, mc.player.getY() + (double)mc.player.getEyeHeight() + mc.player.getDeltaMovement().y() * (double)partialTicks, mc.player.getZ() + mc.player.getDeltaMovement().z() * (double)partialTicks);
        double dx = (double)blockPos.getX() - predictedPos.x + 0.5;
        double dy = (double)blockPos.getY() - predictedPos.y + 0.5;
        double dz = (double)blockPos.getZ() - predictedPos.z + 0.5;
        return RotationUtil.rotationFromDeltas(RotationUtil.addNoise(dx), RotationUtil.addNoise(dy), RotationUtil.addNoise(dz));
    }

    public static Rotation rotationFromDeltas(double dx, double dy, double dz) {
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
    }

    private static double addNoise(double value) {
        return value + MathUtil.randomDouble(0.05, 0.08) * (MathUtil.randomDouble(0.0, 1.0) * 2.0 - 1.0);
    }

    public static double getHitDistance(Entity entity, Vec3 eyePos, Rotation rotation) {
        AABB aABB = RotationUtil.getEntityBB(entity);
        HitResult hitResult = RotationUtil.raycastForBB(aABB, rotation, eyePos, 6.0);
        if (hitResult != null) {
            Vec3 hitLocation = hitResult.getLocation();
            return hitLocation.distanceTo(eyePos);
        }
        return 1000.0;
    }

    public static List<Float> getEyeHeights() {
        return List.of(mc.player.getEyeHeight());
    }

    public static double getMinHitDistance(Entity entity, Rotation rotation) {
        double minDist = Double.MAX_VALUE;
        Iterator<Float> iterator = RotationUtil.getEyeHeights().iterator();
        while (iterator.hasNext()) {
            double eyeHeight = iterator.next();
            Vec3 playerPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            Vec3 eyePos = playerPos.add(0.0, eyeHeight, 0.0);
            minDist = Math.min(minDist, RotationUtil.getHitDistance(entity, eyePos, rotation));
        }
        return minDist;
    }

    public static HitResult raycastForBB(AABB aABB, Rotation rotation, Vec3 eyePos, double range) {
        Vec3 direction = RotationUtil.getDirection(rotation.getYaw(), rotation.getPitch());
        Vec3 endPos = eyePos.add(direction.x * range, direction.y * range, direction.z * range);
        return ProjectileUtil.getEntityHitResult(mc.player, eyePos, endPos, aABB, entity -> !entity.isSpectator() && entity.isPickable(), range * range);
    }

    public static float moveTowards(float maxStep, float current, float target) {
        return RotationUtil.rotateTowards(current, target, maxStep);
    }

    public static float rotateTowards(float current, float target, float maxStep) {
        float diff = Mth.wrapDegrees(target - current);
        if (diff > maxStep) {
            diff = maxStep;
        }
        if (diff < -maxStep) {
            diff = -maxStep;
        }
        return current + diff;
    }

    public static float angleDiff(float angleA, float angleB) {
        float diff = Math.abs(angleA - angleB) % 360.0f;
        if (diff > 180.0f) {
            diff = 0.0f;
        }
        return diff;
    }

    public static float ballisticPitch(float horizontalDist, float verticalDist, float velocity, float gravity) {
        float discriminant = velocity * velocity * velocity * velocity - gravity * (gravity * (horizontalDist * horizontalDist) + 2.0f * verticalDist * (velocity * velocity));
        return (float)Math.toDegrees(Math.atan(((double)(velocity * velocity) - Math.sqrt(discriminant)) / (double)(gravity * horizontalDist)));
    }

    public static float[] getBallisticAngles(Vec3 target) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition();
        double velocity = 1.5;
        double gravity = 0.03;
        double drag = 0.99;
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist == 0.0) {
            return new float[]{yaw, dy > 0.0 ? -90.0f : 90.0f};
        }
        block0: for (float pitch = 90.0f; pitch >= -90.0f; pitch -= 0.5f) {
            double pitchRad = Math.toRadians(pitch);
            double velX = -Math.sin(Math.toRadians(yaw)) * Math.cos(pitchRad);
            double velY = -Math.sin(pitchRad);
            double velZ = Math.cos(Math.toRadians(yaw)) * Math.cos(pitchRad);
            double mag = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
            velX = velX / mag * velocity;
            velY = velY / mag * velocity;
            velZ = velZ / mag * velocity;
            Vec3 projectilePos = new Vec3(eyePos.x, eyePos.y, eyePos.z);
            Vec3 projectileVel = new Vec3(velX, velY, velZ);
            for (int i = 0; i < 300; ++i) {
                projectilePos = projectilePos.add(projectileVel);
                projectileVel = new Vec3(projectileVel.x * drag, projectileVel.y * drag - gravity, projectileVel.z * drag);
                if (projectilePos.y < (double)(mc.level.getMinBuildHeight() - 10)) continue block0;
                double traveledDist = Math.sqrt(Math.pow(projectilePos.x - eyePos.x, 2.0) + Math.pow(projectilePos.z - eyePos.z, 2.0));
                if (!(traveledDist >= horizontalDist)) continue;
                if (Math.abs(projectilePos.y - target.y) < 1.0) {
                    return new float[]{Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch)};
                }
                if (projectileVel.y < 0.0 && projectilePos.y < target.y) continue block0;
            }
        }
        return null;
    }

    public static RotationUtil.BestHitInfo getBestHit(Entity entity) {
        double sampleAxis2;
        double sampleAxis1;
        Vec3 playerPos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3 eyePos = playerPos.add(0.0, mc.player.getEyeHeight(), 0.0);
        AABB aABB = RotationUtil.getEntityBB(entity);
        double minX = aABB.minX;
        double minY = aABB.minY;
        double minZ = aABB.minZ;
        double maxX = aABB.maxX;
        double maxY = aABB.maxY;
        double maxZ = aABB.maxZ;
        double step = 0.1;
        OrderedHashSet<Vec3> samplePoints = new OrderedHashSet<>();
        samplePoints.add(new Vec3(minX + maxX / 2.0, minY + maxY / 2.0, minZ + maxZ / 2.0));
        samplePoints.add(RotationUtil.closestPoint(eyePos, aABB));
        for (sampleAxis1 = minX; sampleAxis1 <= maxX; sampleAxis1 += step) {
            for (sampleAxis2 = minY; sampleAxis2 <= maxY; sampleAxis2 += step) {
                samplePoints.add(new Vec3(sampleAxis1, sampleAxis2, minZ));
                samplePoints.add(new Vec3(sampleAxis1, sampleAxis2, maxZ));
            }
        }
        for (sampleAxis1 = minX; sampleAxis1 <= maxX; sampleAxis1 += step) {
            for (sampleAxis2 = minZ; sampleAxis2 <= maxZ; sampleAxis2 += step) {
                samplePoints.add(new Vec3(sampleAxis1, minY, sampleAxis2));
                samplePoints.add(new Vec3(sampleAxis1, maxY, sampleAxis2));
            }
        }
        for (sampleAxis1 = minY; sampleAxis1 <= maxY; sampleAxis1 += step) {
            for (sampleAxis2 = minZ; sampleAxis2 <= maxZ; sampleAxis2 += step) {
                samplePoints.add(new Vec3(minX, sampleAxis1, sampleAxis2));
                samplePoints.add(new Vec3(maxX, sampleAxis1, sampleAxis2));
            }
        }
        for (Vec3 samplePoint : samplePoints) {
            HitResult hitResult;
            Rotation rotation = RotationUtil.exactRotation(eyePos, samplePoint);
            if (rotation == null) {
                logger.error("NULL????");
            }
            if ((hitResult = RotationUtil.performRaycast(rotation)) == null) {
                logger.error("NULL2????");
            }
            if (!RotationUtil.isHitValid(eyePos, hitResult, entity)) continue;
            try {
                Vec3 hitLocation = hitResult.getLocation();
                return new RotationUtil.BestHitInfo(eyePos, hitLocation, hitLocation.distanceTo(eyePos), RotationUtil.getSensitivitySnappedRotation(rotation.getYaw(), rotation.getPitch(), RotationHandler.prevRotation.yaw, RotationHandler.prevRotation.pitch));
            } catch (Exception exception) {
                logger.error("er here");
                logger.error(exception);
                exception.printStackTrace();
                return null;
            }
        }
        return new RotationUtil.BestHitInfo(eyePos, eyePos, 1000.0, null);
    }

    public static Rotation getEntityRotation(Entity entity, float spreadFactor, float verticalSpread, float heightFraction) {
        if (entity == null) {
            return null;
        }
        LocalPlayer localPlayer = mc.player;
        if (localPlayer == null) {
            return null;
        }
        Random random = new Random();
        double offsetX = (random.nextDouble() - 0.5) * (double)entity.getBbWidth() * 0.5 * (double)spreadFactor;
        double offsetZ = (random.nextDouble() - 0.5) * (double)entity.getBbWidth() * 0.5 * (double)spreadFactor;
        double aimY = entity.getY();
        aimY = heightFraction <= 0.1f ? (aimY += entity.getBbHeight() * 0.1f) : (heightFraction >= 0.9f ? (aimY += entity.getEyeHeight() * (0.8f + random.nextFloat() * 0.2f)) : (aimY += entity.getBbHeight() * Mth.clamp(heightFraction, 0.1f, 0.9f)));
        double offsetY = (random.nextDouble() - 0.5) * (double)entity.getBbHeight() * 0.3 * (double)verticalSpread;
        double aimX = entity.getX() + offsetX;
        double targetY = aimY + offsetY;
        double aimZ = entity.getZ() + offsetZ;
        double dx = aimX - localPlayer.getX();
        double dy = targetY - (localPlayer.getY() + (double)localPlayer.getEyeHeight());
        double dz = aimZ - localPlayer.getZ();
        double horizontalDist = Mth.sqrt((float)(dx * dx + dz * dz));
        float rawYaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float rawPitch = (float)(-(Math.atan2(dy, horizontalDist) * 180.0 / Math.PI));
        float finalYaw = localPlayer.getYRot() + Mth.wrapDegrees(rawYaw - localPlayer.getYRot());
        float finalPitch = localPlayer.getXRot() + Mth.wrapDegrees(rawPitch - localPlayer.getXRot());
        finalPitch = Mth.clamp(finalPitch, -90.0f, 90.0f);
        return RotationUtil.getSensitivitySnappedRotation(finalYaw, finalPitch, RotationHandler.prevRotation.yaw, RotationHandler.prevRotation.pitch);
    }

    public static Rotation getSensitivitySnappedRotation(float yaw, float pitch, float prevYaw, float prevPitch) {
        float sensitivityFactor = (float)(mc.options.sensitivity().get() * (double)0.6f + (double)0.2f);
        float gcd = sensitivityFactor * sensitivityFactor * sensitivityFactor * 1.2f;
        float yawDelta = yaw - prevYaw;
        float pitchDelta = pitch - prevPitch;
        float snappedYawDelta = yawDelta - yawDelta % gcd;
        float snappedPitchDelta = pitchDelta - pitchDelta % gcd;
        float snappedYaw = prevYaw + snappedYawDelta;
        float snappedPitch = prevPitch + snappedPitchDelta;
        return new Rotation(snappedYaw, snappedPitch);
    }

    private static AABB getEntityBB(Entity entity) {
        return entity.getBoundingBox();
    }

    private static boolean isHitValid(Vec3 eyePos, HitResult hitResult, Entity entity) {
        if (hitResult.getType() == HitResult.Type.ENTITY && ((EntityHitResult)hitResult).getEntity() == entity) {
            Vec3 hitLocation = hitResult.getLocation();
            return RotationUtil.isInsideAABB(RotationUtil.getEntityBB(entity), eyePos) || hitLocation.distanceTo(eyePos) <= 3.0;
        }
        return false;
    }

    private static HitResult performRaycast(Rotation rotation) {
        AABB expandedBB;
        double pickRange = mc.gameMode.getPickRange();
        HitResult hitResult = RayTraceUtil.rayTrace(pickRange, 1.0f, false, rotation);
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        boolean checkClampedRange = false;
        double maxRangeSqr = pickRange;
        if (mc.gameMode.hasFarPickRange()) {
            pickRange = maxRangeSqr = 6.0;
        } else if (pickRange > 3.0) {
            checkClampedRange = true;
        }
        maxRangeSqr *= maxRangeSqr;
        if (hitResult != null) {
            maxRangeSqr = hitResult.getLocation().distanceToSqr(eyePos);
        }
        Vec3 direction = RotationUtil.getDirection(rotation.getYaw(), rotation.getPitch());
        Vec3 endPos = eyePos.add(direction.x * pickRange, direction.y * pickRange, direction.z * pickRange);
        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(mc.player, eyePos, endPos, expandedBB = mc.player.getBoundingBox().expandTowards(direction.scale(pickRange)).inflate(1.0, 1.0, 1.0), entity -> !entity.isSpectator() && entity.isPickable(), maxRangeSqr);
        if (entityHitResult != null) {
            Vec3 hitLocation = entityHitResult.getLocation();
            double hitDistSqr = eyePos.distanceToSqr(hitLocation);
            if (checkClampedRange && hitDistSqr > 9.0) {
                hitResult = BlockHitResult.miss(hitLocation, Direction.getNearest(direction.x, direction.y, direction.z), BlockPos.containing(hitLocation));
            } else if (hitDistSqr < maxRangeSqr || hitResult == null) {
                hitResult = entityHitResult;
            }
        }
        return hitResult;
    }

    public static Vec3 getDirection(float yaw, float pitch) {
        float cosYaw = Mth.cos(-yaw * ((float)Math.PI / 180) - (float)Math.PI);
        float sinYaw = Mth.sin(-yaw * ((float)Math.PI / 180) - (float)Math.PI);
        float cosPitch = -Mth.cos(-pitch * ((float)Math.PI / 180));
        float sinPitch = Mth.sin(-pitch * ((float)Math.PI / 180));
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }

    public static boolean isInsideAABB(AABB aABB, Vec3 point) {
        return point.x > aABB.minX && point.x < aABB.maxX && point.y > aABB.minY && point.y < aABB.maxY && point.z > aABB.minZ && point.z < aABB.maxZ;
    }

    public static Rotation exactRotation(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
    }

    public static Vec3 closestPoint(Vec3 point, AABB aABB) {
        double clampedX = Math.max(aABB.minX, Math.min(point.x, aABB.maxX));
        double clampedY = Math.max(aABB.minY, Math.min(point.y, aABB.maxY));
        double clampedZ = Math.max(aABB.minZ, Math.min(point.z, aABB.maxZ));
        return new Vec3(clampedX, clampedY, clampedZ);
    }

    public static Rotation rotationFromVec(Vec3 target) {
        return RotationUtil.rotationFromCoords(target.x, target.y, target.z);
    }

    public static Rotation rotationFromCoords(double x, double y, double z) {
        if (mc.player == null) {
            return new Rotation(0.0f, 0.0f);
        }
        return RotationUtil.rotationFromPoints(x, y, z, mc.player.getX(), mc.player.getY() + (double)mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());
    }

    private static double normalizeAngle(double angle) {
        return ((angle + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    public static double angleDiffDouble(float angleA, float angleB) {
        double diff = angleA - angleB;
        return RotationUtil.normalizeAngle(diff);
    }

    public static Rotation rotationFromPoints(double targetX, double targetY, double targetZ, double fromX, double fromY, double fromZ) {
        double dx = RotationUtil.addNoise(targetX - fromX);
        double dy = RotationUtil.addNoise(targetY - fromY);
        double dz = RotationUtil.addNoise(targetZ - fromZ);
        double horizontalDist = Mth.sqrt((float)(dx * dx + dz * dz));
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-(Math.atan2(dy, horizontalDist) * 180.0 / Math.PI));
        return new Rotation(yaw, pitch);
    }

    public static Rotation entityRotation(Entity entity) {
        if (entity == null) {
            return null;
        }
        double dx = entity.getX() - mc.player.getX();
        double dz = entity.getZ() - mc.player.getZ();
        double dy = entity.getY() + (double)entity.getEyeHeight() - (mc.player.getY() + (double)mc.player.getEyeHeight());
        return RotationUtil.createRotation(dx, dy, dz);
    }

    public static Rotation createRotation(double dx, double dy, double dz) {
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
    }

    public static boolean isEntityInFov(Entity entity, float fov) {
        Rotation rotation = RotationUtil.entityRotation(entity);
        float yawDiff = Math.abs(mc.player.getYRot() % 360.0f - rotation.getYaw());
        float wrappedDiff = Math.abs(Math.min(yawDiff, 360.0f - yawDiff));
        return wrappedDiff <= fov;
    }

    public static Vec3 directionFromRotation(Rotation rotation) {
        float cosYaw = (float)Math.cos(-rotation.getYaw() * ((float)Math.PI / 180) - (float)Math.PI);
        float sinYaw = (float)Math.sin(-rotation.getYaw() * ((float)Math.PI / 180) - (float)Math.PI);
        float cosPitch = (float)(-Math.cos(-rotation.getPitch() * ((float)Math.PI / 180)));
        float sinPitch = (float)Math.sin(-rotation.getPitch() * ((float)Math.PI / 180));
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }

    @Generated
    private RotationUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
