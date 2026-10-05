package client.nilore.utils.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import lombok.Generated;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.ClientBase;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.utils.math.MathUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.utils.rotation.RotationSmoother;

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
            diff = 360.0f - diff;
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


    private static final double HIT_SAMPLE_SPACING = 0.25;
    private static final int HIT_SAMPLE_MAX_SEGMENTS = 8;
    private static final int HIT_SEARCH_MAX_RAYCASTS = 48;
    private static final double HIT_CANDIDATE_DEDUP_DEG = 0.5;

    private static Entity hitCacheEntity;
    private static long hitCacheTick = Long.MIN_VALUE;
    private static boolean hitCacheIgnoreBlocks;
    private static double hitCacheEyeX;
    private static double hitCacheEyeY;
    private static double hitCacheEyeZ;
    private static double hitCacheMinX;
    private static double hitCacheMinY;
    private static double hitCacheMinZ;
    private static double hitCacheMaxX;
    private static double hitCacheMaxY;
    private static double hitCacheMaxZ;
    private static BestHitInfo hitCacheResult;

    private record HitCandidate(Vec3 point, Rotation rotation, double pitchAbs) {
    }

    public static BestHitInfo getBestHit(Entity entity) {
        return RotationUtil.getBestHit(entity, false);
    }

    public static BestHitInfo getBestHit(Entity entity, boolean ignoreBlocks) {
        if (entity == null || mc.player == null || mc.level == null) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        AABB aABB = entity.getBoundingBox();
        long tick = mc.level.getGameTime();
        if (entity == hitCacheEntity && tick == hitCacheTick && ignoreBlocks == hitCacheIgnoreBlocks
                && eyePos.x == hitCacheEyeX && eyePos.y == hitCacheEyeY && eyePos.z == hitCacheEyeZ
                && aABB.minX == hitCacheMinX && aABB.minY == hitCacheMinY && aABB.minZ == hitCacheMinZ
                && aABB.maxX == hitCacheMaxX && aABB.maxY == hitCacheMaxY && aABB.maxZ == hitCacheMaxZ) {
            return hitCacheResult;
        }
        BestHitInfo result = RotationUtil.searchBestHit(entity, eyePos, aABB, ignoreBlocks);
        hitCacheEntity = entity;
        hitCacheTick = tick;
        hitCacheIgnoreBlocks = ignoreBlocks;
        hitCacheEyeX = eyePos.x;
        hitCacheEyeY = eyePos.y;
        hitCacheEyeZ = eyePos.z;
        hitCacheMinX = aABB.minX;
        hitCacheMinY = aABB.minY;
        hitCacheMinZ = aABB.minZ;
        hitCacheMaxX = aABB.maxX;
        hitCacheMaxY = aABB.maxY;
        hitCacheMaxZ = aABB.maxZ;
        hitCacheResult = result;
        return result;
    }

    private static BestHitInfo searchBestHit(Entity entity, Vec3 eyePos, AABB aABB, boolean ignoreBlocks) {
        List<HitCandidate> candidates = new ArrayList<>();
        for (Vec3 point : RotationUtil.collectHitPoints(eyePos, aABB)) {
            Rotation rotation = RotationUtil.exactRotation(eyePos, point);
            if (rotation == null || Float.isNaN(rotation.getYaw()) || Float.isNaN(rotation.getPitch())) {
                continue;
            }
            candidates.add(new HitCandidate(point, rotation, Math.abs(rotation.getPitch())));
        }
        candidates.sort(Comparator.<HitCandidate>comparingDouble(HitCandidate::pitchAbs)
                .thenComparingDouble(candidate -> candidate.point().distanceToSqr(eyePos)));

        Rotation prevRotation = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Set<Long> tried = new HashSet<>();
        int raycasts = 0;
        for (HitCandidate candidate : candidates) {
            if (raycasts >= HIT_SEARCH_MAX_RAYCASTS) {
                break;
            }
            if (!tried.add(RotationUtil.quantizeRotation(candidate.rotation()))) {
                continue;
            }
            ++raycasts;
            HitResult hitResult = RotationUtil.performRaycast(candidate.rotation(), ignoreBlocks);
            if (hitResult == null || !RotationUtil.isHitValid(eyePos, hitResult, entity)) {
                continue;
            }
            Vec3 hitLocation = hitResult.getLocation();
            return new BestHitInfo(eyePos, hitLocation, hitLocation.distanceTo(eyePos),
                    RotationUtil.getSensitivitySnappedRotation(candidate.rotation().getYaw(), candidate.rotation().getPitch(),
                            prevRotation.getYaw(), prevRotation.getPitch()));
        }
        return new BestHitInfo(eyePos, eyePos, 1000.0, null);
    }

    private static List<Vec3> collectHitPoints(Vec3 eyePos, AABB aABB) {
        List<Vec3> points = new ArrayList<>();
        points.add(RotationUtil.closestPoint(eyePos, aABB));

        double sizeX = aABB.maxX - aABB.minX;
        double sizeY = aABB.maxY - aABB.minY;
        double sizeZ = aABB.maxZ - aABB.minZ;
        int segmentsX = RotationUtil.axisSegments(sizeX);
        int segmentsY = RotationUtil.axisSegments(sizeY);
        int segmentsZ = RotationUtil.axisSegments(sizeZ);

        boolean includeMinX = eyePos.x <= aABB.minX;
        boolean includeMaxX = eyePos.x >= aABB.maxX;
        boolean includeMinY = eyePos.y <= aABB.minY;
        boolean includeMaxY = eyePos.y >= aABB.maxY;
        boolean includeMinZ = eyePos.z <= aABB.minZ;
        boolean includeMaxZ = eyePos.z >= aABB.maxZ;
        if (!(includeMinX || includeMaxX || includeMinY || includeMaxY || includeMinZ || includeMaxZ)) {
            includeMinX = includeMaxX = includeMinY = includeMaxY = includeMinZ = includeMaxZ = true;
        }

        for (int ix = 0; ix <= segmentsX; ++ix) {
            double x = aABB.minX + sizeX * ix / segmentsX;
            for (int iy = 0; iy <= segmentsY; ++iy) {
                double y = aABB.minY + sizeY * iy / segmentsY;
                if (includeMinZ) {
                    points.add(new Vec3(x, y, aABB.minZ));
                }
                if (includeMaxZ) {
                    points.add(new Vec3(x, y, aABB.maxZ));
                }
            }
        }
        for (int ix = 0; ix <= segmentsX; ++ix) {
            double x = aABB.minX + sizeX * ix / segmentsX;
            for (int iz = 0; iz <= segmentsZ; ++iz) {
                double z = aABB.minZ + sizeZ * iz / segmentsZ;
                if (includeMinY) {
                    points.add(new Vec3(x, aABB.minY, z));
                }
                if (includeMaxY) {
                    points.add(new Vec3(x, aABB.maxY, z));
                }
            }
        }
        for (int iy = 0; iy <= segmentsY; ++iy) {
            double y = aABB.minY + sizeY * iy / segmentsY;
            for (int iz = 0; iz <= segmentsZ; ++iz) {
                double z = aABB.minZ + sizeZ * iz / segmentsZ;
                if (includeMinX) {
                    points.add(new Vec3(aABB.minX, y, z));
                }
                if (includeMaxX) {
                    points.add(new Vec3(aABB.maxX, y, z));
                }
            }
        }
        return points;
    }

    private static int axisSegments(double size) {
        return Mth.clamp((int)Math.ceil(size / HIT_SAMPLE_SPACING), 1, HIT_SAMPLE_MAX_SEGMENTS);
    }

    private static long quantizeRotation(Rotation rotation) {
        long yaw = Math.round(Mth.wrapDegrees(rotation.getYaw()) / HIT_CANDIDATE_DEDUP_DEG);
        long pitch = Math.round(rotation.getPitch() / HIT_CANDIDATE_DEDUP_DEG);
        return (yaw << 32) ^ (pitch & 0xFFFFFFFFL);
    }

    public static boolean canSeeAnyPoint(Entity entity) {
        if (entity == null || mc.player == null || mc.level == null) {
            return false;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        AABB aABB = entity.getBoundingBox();
        Vec3 nearest = RotationUtil.closestPoint(eyePos, aABB);
        if (RotationUtil.isPointVisible(entity, eyePos, nearest)) {
            return true;
        }
        double sizeY = aABB.maxY - aABB.minY;
        for (int i = 1; i <= 4; ++i) {
            double y = aABB.minY + sizeY * i / 5.0;
            if (RotationUtil.isPointVisible(entity, eyePos, new Vec3(nearest.x, y, nearest.z))) {
                return true;
            }
        }
        return RotationUtil.isPointVisible(entity, eyePos, new Vec3(aABB.minX, aABB.maxY, aABB.minZ))
                || RotationUtil.isPointVisible(entity, eyePos, new Vec3(aABB.minX, aABB.maxY, aABB.maxZ))
                || RotationUtil.isPointVisible(entity, eyePos, new Vec3(aABB.maxX, aABB.maxY, aABB.minZ))
                || RotationUtil.isPointVisible(entity, eyePos, new Vec3(aABB.maxX, aABB.maxY, aABB.maxZ));
    }

    private static boolean isPointVisible(Entity entity, Vec3 eyePos, Vec3 point) {
        if (eyePos.distanceToSqr(point) < 1.0E-4) {
            return true;
        }
        Rotation rotation = RotationUtil.exactRotation(eyePos, point);
        if (rotation == null) {
            return false;
        }
        HitResult hitResult = RotationUtil.performRaycast(rotation, false);
        return hitResult != null && RotationUtil.isHitValid(eyePos, hitResult, entity);
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
            return RotationUtil.isInsideAABB(RotationUtil.getEntityBB(entity), eyePos) || hitLocation.distanceTo(eyePos) <= reachLimit;
        }
        return false;
    }


    private static double reachLimit = 3.0;

    public static void setReachLimit(double reach) {
        reachLimit = reach;
    }

    public static double getReachLimit() {
        return reachLimit;
    }

    /** Kept under the reach limit: while a knockback is withheld the server measures from a position
     *  our client has not reached yet, and Grim's own offset for that ran around 0.03. */
    private static final double REACH_MARGIN = 0.05;

    public static boolean isWithinReach(Entity entity) {
        if (entity == null || mc.player == null) {
            return false;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        return RotationUtil.closestPoint(eyePos, entity.getBoundingBox()).distanceTo(eyePos) <= reachLimit - REACH_MARGIN;
    }

    public static HitResult performRaycast(Rotation rotation, boolean ignoreBlocks) {
        AABB expandedBB;
        double pickRange = mc.gameMode.getPickRange();
        HitResult hitResult = ignoreBlocks ? null : RayTraceUtil.rayTrace(pickRange, 1.0f, false, rotation);
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

    /**
     * Whether the entity sits inside the field of view around the rotation we actually send.
     *
     * <p>The silent aim is what the server sees and what the aura works with, so it is the right
     * reference - the player's real view never moves while the aura is aiming.
     *
     * @param fov total field of view in degrees, so a target may sit fov/2 off the aim
     */
    /** FoV is measured against the player's own crosshair, not the silent rotation. */
    public static boolean isEntityInFov(Entity entity, double fov) {
        if (entity == null || mc.player == null) {
            return false;
        }
        return RotationUtil.angleDiff(mc.player.getYRot(), RotationUtil.entityRotation(entity).getYaw()) <= fov / 2.0;
    }

    public static Vec3 directionFromRotation(Rotation rotation) {
        float cosYaw = (float)Math.cos(-rotation.getYaw() * ((float)Math.PI / 180) - (float)Math.PI);
        float sinYaw = (float)Math.sin(-rotation.getYaw() * ((float)Math.PI / 180) - (float)Math.PI);
        float cosPitch = (float)(-Math.cos(-rotation.getPitch() * ((float)Math.PI / 180)));
        float sinPitch = (float)Math.sin(-rotation.getPitch() * ((float)Math.PI / 180));
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }


    private static final double[] AIM_HEIGHT_FRACTIONS = {0.75, 0.5, 0.3, 0.1};

    public static Rotation rotationToPoint(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double horizontalDist = Math.hypot(delta.x, delta.z);
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float)(-Math.toDegrees(Math.atan2(delta.y, horizontalDist)));
        Rotation current = RotationHandler.prevRotation;
        float baseYaw = current != null
                ? current.getYaw()
                : (mc.player != null ? mc.player.getYRot() : 0.0f);
        yaw = baseYaw + Mth.wrapDegrees(yaw - baseYaw);
        return new Rotation(yaw, Mth.clamp(pitch, -90.0f, 90.0f));
    }

    private static Rotation stepTowards(Rotation from, Rotation to, double step) {
        if (from == null || to == null) {
            return to;
        }
        if (step <= 0.0) {
            return from;
        }
        float yawLimit = (float) step;
        float pitchLimit = (float) (step / 2.0);
        float yawDelta = Mth.clamp(Mth.wrapDegrees(to.getYaw() - from.getYaw()), -yawLimit, yawLimit);
        float pitchDelta = Mth.clamp(to.getPitch() - from.getPitch(), -pitchLimit, pitchLimit);
        return new Rotation(from.getYaw() + yawDelta,
                Mth.clamp(from.getPitch() + pitchDelta, -90.0f, 90.0f));
    }

    public static Rotation smoothRotationTo(Rotation from, Rotation to, double speed) {
        if (from == null || to == null) {
            return to;
        }
        double yawDiff = to.getYaw() - from.getYaw();
        double pitchDiff = to.getPitch() - from.getPitch();
        if (Math.hypot(yawDiff, pitchDiff) < 0.05) {
            return to;
        }
        return RotationSmoother.patchConstantRotation(RotationUtil.stepTowards(from, to, speed), from);
    }

    public static Vec3 findAimPoint(Entity entity, double range, float partialTicks) {
        if (entity == null || mc.player == null || mc.level == null) {
            return null;
        }
        AABB aabb = EntityUtil.getInterpolatedAABB(entity, partialTicks);
        Vec3 interpolatedPos = EntityUtil.getInterpolatedPos(entity, partialTicks);
        double eyeHeight = entity.getEyeHeight();
        Vec3 eyePoint = interpolatedPos.add(0.0, eyeHeight, 0.0);
        Vec3 fallback = mc.level.getBlockState(BlockPos.containing(eyePoint)).is(Blocks.COBWEB)
                ? interpolatedPos.add(0.0, eyeHeight * 0.3, 0.0)
                : eyePoint;
        if (RotationUtil.canHitPoint(entity, fallback, range, aabb)) {
            return fallback;
        }
        Vec3 myEye = mc.player.getEyePosition();
        double x = Mth.clamp(myEye.x, aabb.minX + 0.05, aabb.maxX - 0.05);
        double z = Mth.clamp(myEye.z, aabb.minZ + 0.05, aabb.maxZ - 0.05);
        double height = aabb.maxY - aabb.minY;
        for (double fraction : AIM_HEIGHT_FRACTIONS) {
            double y = aabb.minY + height * fraction;
            Vec3 columnPoint = new Vec3(interpolatedPos.x, y, interpolatedPos.z);
            if (RotationUtil.canHitPoint(entity, columnPoint, range, aabb)) {
                return columnPoint;
            }
            Vec3 eyeColumnPoint = new Vec3(x, y, z);
            if (RotationUtil.canHitPoint(entity, eyeColumnPoint, range, aabb)) {
                return eyeColumnPoint;
            }
        }
        return fallback;
    }

    public static Vec3 findAimPoint(Entity entity, double range) {
        return RotationUtil.findAimPoint(entity, range, 1.0f);
    }

    /**
     * Whether the aim ray hits that entity, with {@code inflate} as the tolerance.
     *
     * <p>rayTraceForEntity returns the block result or a final clip when the entity was NOT hit, so
     * testing it for null made this method true on every call and the aim gate useless - the server
     * bounces those attacks as Hitboxes. Ask for the entity result specifically instead, and keep
     * the inflation: the rotation is GCD quantised and carries a drift/jitter walk, so without a
     * little slack a good aim sits a fraction of a degree off the box and the attack waits ticks.
     *
     * <p>Judged against the freshest silent aim, not the angle already on the wire: that is the angle
     * the attack itself uses, since {@code setTargetRotation} is what feeds {@code ClientBase.yaw}.
     */
    public static boolean isLookingAt(Entity entity, double range, float inflate) {
        if (entity == null || mc.player == null || mc.level == null) {
            return false;
        }
        HitResult hit = RotationUtil.rayTraceForAim(currentAimRotation(), range, entity, entity.getBoundingBox().inflate(inflate));
        return hit instanceof EntityHitResult entityHit && entityHit.getEntity() == entity;
    }

    /**
     * Freshest aim first: the aura's own rotation for this tick, then the handler's target, then the
     * angle already on the wire, then the player's own view. Checks run from TickEvent, which is
     * before {@code onTickHigh} hands the rotation over, so without the first entry every check sees
     * the previous tick's angle.
     */
    private static Rotation currentAimRotation() {
        KillAura aura = KillAura.INSTANCE;
        if (aura != null && aura.isEnabled() && aura.rotation != null) {
            return aura.rotation;
        }
        if (RotationHandler.isRotating && RotationHandler.targetRotation != null) {
            return RotationHandler.targetRotation;
        }
        if (RotationHandler.sentRotation != null) {
            return RotationHandler.sentRotation;
        }
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private static boolean canHitPoint(Entity entity, Vec3 point, double range, AABB targetBox) {
        Rotation rotation = RotationUtil.rotationToPoint(mc.player.getEyePosition(), point);
        return RotationUtil.rayTraceForAim(rotation, range, entity, targetBox) instanceof EntityHitResult hit
                && hit.getEntity() == entity;
    }

    public static HitResult rayTraceForAim(Rotation rotation, double range) {
        return RotationUtil.rayTraceForAim(rotation, range, null, null);
    }

    public static HitResult rayTraceForAim(Rotation rotation, double range, Entity target, AABB interpolatedBox) {
        if (mc.player == null || mc.level == null || rotation == null) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 lookVec = RotationUtil.directionFromRotation(rotation);
        Vec3 endPos = eyePos.add(lookVec.scale(range));

        BlockHitResult blockHit = mc.level.clip(new ClipContext(eyePos, endPos,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        double blockDist = blockHit.getType() == HitResult.Type.MISS
                ? range
                : blockHit.getLocation().distanceTo(eyePos);

        Entity hitEntity = null;
        Vec3 hitVec = null;
        double bestDist = blockDist;
        AABB searchBox = mc.player.getBoundingBox().expandTowards(lookVec.scale(range)).inflate(1.0);
        List<Entity> candidates = mc.level.getEntities(mc.player, searchBox,
                candidate -> !candidate.isSpectator() && candidate.isPickable());
        for (Entity candidate : candidates) {
            AABB base = interpolatedBox != null && candidate == target ? interpolatedBox : candidate.getBoundingBox();
            AABB box = base.inflate(candidate.getPickRadius());
            Optional<Vec3> clip = box.clip(eyePos, endPos);
            if (box.contains(eyePos)) {
                if (bestDist < 0.0) {
                    continue;
                }
                hitEntity = candidate;
                hitVec = clip.orElse(eyePos);
                bestDist = 0.0;
                continue;
            }
            if (clip.isEmpty()) {
                continue;
            }
            double dist = eyePos.distanceTo(clip.get());
            if (dist >= bestDist && bestDist != 0.0) {
                continue;
            }
            if (candidate.getRootVehicle() == mc.player.getRootVehicle()) {
                if (bestDist != 0.0) {
                    continue;
                }
                hitEntity = candidate;
                hitVec = clip.get();
                continue;
            }
            hitEntity = candidate;
            hitVec = clip.get();
            bestDist = dist;
        }
        if (hitEntity != null && (bestDist < blockDist || blockHit.getType() == HitResult.Type.MISS)) {
            return new EntityHitResult(hitEntity, hitVec);
        }
        return blockHit;
    }

    @Generated
    private RotationUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
