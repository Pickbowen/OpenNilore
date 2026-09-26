package client.nilore.utils.game;

import java.util.List;
import java.util.Optional;
import lombok.Generated;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.ClientBase;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.rotation.Rotation;

public final class RayTraceUtil
extends ClientBase {
    private static final String UTILITY_MSG = "This is a utility class and cannot be instantiated";

    public static boolean canRayTrace(Rotation rotation, Direction direction, BlockPos blockPos, boolean checkFace) {
        Vec3 lookDir;
        Vec3 endPos;
        if (mc.player == null || mc.level == null) {
            return false;
        }
        float yaw = rotation.getYaw();
        float pitch = rotation.getPitch();
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        BlockHitResult blockHitResult = mc.level.clip(new ClipContext(eyePos, endPos = eyePos.add((lookDir = Vec3.directionFromRotation(pitch, yaw)).scale(5.0)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (blockHitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        boolean samePos = blockHitResult.getBlockPos().equals(blockPos);
        boolean sameFace = !checkFace || blockHitResult.getDirection() == direction;
        return samePos && sameFace;
    }

    public static HitResult rayTrace(float partialTicks, Rotation rotation) {
        HitResult hitResult = null;
        Entity entity = mc.getCameraEntity();
        if (entity != null && mc.level != null) {
            double pickRange = mc.gameMode.getPickRange();
            hitResult = RayTraceUtil.rayTrace(pickRange, partialTicks, true, rotation.getYaw(), rotation.getPitch());
        }
        return hitResult;
    }

    public static HitResult rayTrace(double range, float partialTicks, boolean clipFluids, Rotation rotation) {
        HitResult hitResult = null;
        Entity entity = mc.getCameraEntity();
        if (entity != null && mc.level != null) {
            hitResult = RayTraceUtil.rayTrace(range, partialTicks, clipFluids, rotation.getYaw(), rotation.getPitch());
        }
        return hitResult;
    }

    /**
     * 由 yaw/pitch 得到单位朝向向量: yaw 0=南(+Z)、90=西(-X)、pitch 90=下(-Y)。
     * 与 {@link RotationUtil#getDirection(float, float)} 和 vanilla 的
     * {@code Vec3.directionFromRotation} 同一定义。
     *
     * 原实现把 x/y 两个分量写反了(y 分量里甚至不含 pitch), 唯一调用者是
     * {@link #rayTrace(double, float, boolean, float, float)} 的方块裁剪, 因此会让
     * KillAura 的遮挡判定整条射线指到别的方向上去。
     */
    public static Vec3 getViewVector(float pitch, float yaw) {
        float yawRad = yaw * ((float)Math.PI / 180);
        float pitchRad = pitch * ((float)Math.PI / 180);
        float cosPitch = Mth.cos(pitchRad);
        float sinPitch = Mth.sin(pitchRad);
        float cosYaw = Mth.cos(yawRad);
        float sinYaw = Mth.sin(yawRad);
        return new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    public static HitResult rayTrace(double range, float partialTicks, boolean clipFluids, float yaw, float pitch) {
        // 起点必须和上层比较命中距离用的眼位一致: 原来硬编码 y+1.62, 潜行(1.27)、游泳(0.4)、
        // 鞘翅姿态下会和真正的眼睛差出一大截, 等于换个高度去打遮挡。
        Vec3 eyePos = mc.player.getEyePosition(partialTicks);
        Vec3 viewVec = RayTraceUtil.getViewVector(pitch, yaw);
        Vec3 endPos = eyePos.add(viewVec.x * range, viewVec.y * range, viewVec.z * range);
        return mc.player.level().clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, clipFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, mc.player));
    }

    public static HitResult clipWithEntity(Vec3 fromPos, Vec3 toPos, boolean clipFluids, boolean useCollider, boolean useVisual, Entity entity) {
        ClipContext.Block blockMode = useCollider ? ClipContext.Block.COLLIDER : (useVisual ? ClipContext.Block.VISUAL : ClipContext.Block.OUTLINE);
        ClipContext.Fluid fluidMode = clipFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
        ClipContext clipContext = new ClipContext(fromPos, toPos, blockMode, fluidMode, entity);
        return mc.level.clip(clipContext);
    }

    public static EntityHitResult getEntityHit(AABB aABB, Vec3 fromPos, Vec3 toPos) {
        Optional<Vec3> clipResult = aABB.clip(fromPos, toPos);
        return clipResult.map(hit -> new EntityHitResult(null, hit)).orElse(null);
    }

    public static HitResult rayTraceForEntity(Rotation rotation, double range, float inflate, Entity entity, Entity onlyTarget, boolean ignoreBlocks) {
        if (entity == null || mc.level == null) {
            return null;
        }
        float partialTicks = mc.getFrameTime();
        Vec3 eyePos = entity.getEyePosition(partialTicks);
        Vec3 lookDir = RotationUtil.directionFromRotation(rotation);
        Vec3 endPos = eyePos.add(lookDir.x * range, lookDir.y * range, lookDir.z * range);
        BlockHitResult blockHitResult = null;
        double blockDist = range;
        if (!ignoreBlocks) {
            blockHitResult = mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
            blockDist = blockHitResult.getType() == HitResult.Type.BLOCK ? eyePos.distanceTo(blockHitResult.getLocation()) : range;
        }
        double searchRange = Math.min(range, blockDist) + (double)inflate;
        AABB searchBox = new AABB(eyePos.x - searchRange, eyePos.y - searchRange, eyePos.z - searchRange, eyePos.x + searchRange, eyePos.y + searchRange, eyePos.z + searchRange);
        List<Entity> entities = mc.level.getEntitiesOfClass(Entity.class, searchBox, candidate -> candidate != entity && (onlyTarget == null || candidate == onlyTarget) && EntitySelector.NO_SPECTATORS.test(candidate) && candidate.isPickable());
        Entity hitEntity = null;
        Vec3 hitVec = null;
        double bestDistSqr = Math.min(range, blockDist);
        bestDistSqr *= bestDistSqr;
        for (Entity candidate : entities) {
            Vec3 centerVec;
            BlockHitResult occlusionResult;
            Vec3 entityHitVec;
            double candidateDistSqr;
            AABB inflatedBox = candidate.getBoundingBox().inflate(inflate);
            Optional<Vec3> optional = inflatedBox.clip(eyePos, endPos);
            if (!optional.isPresent() || !((candidateDistSqr = eyePos.distanceToSqr(entityHitVec = optional.get())) < bestDistSqr)) continue;
            boolean visible = ignoreBlocks || (occlusionResult = mc.level.clip(new ClipContext(eyePos, centerVec = inflatedBox.getCenter(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity))).getType() != HitResult.Type.BLOCK || !(eyePos.distanceToSqr(occlusionResult.getLocation()) <= candidateDistSqr);
            if (!visible) continue;
            bestDistSqr = candidateDistSqr;
            hitEntity = candidate;
            hitVec = entityHitVec;
        }
        if (hitEntity != null) {
            return new EntityHitResult(hitEntity, hitVec);
        }
        if (!ignoreBlocks && blockHitResult != null) {
            return blockHitResult;
        }
        return mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
    }

    @Generated
    private RayTraceUtil() {
        throw new UnsupportedOperationException(UTILITY_MSG);
    }
}
