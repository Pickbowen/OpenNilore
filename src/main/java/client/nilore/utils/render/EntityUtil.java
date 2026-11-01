package client.nilore.utils.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.level.Level;

import client.nilore.utils.misc.ReflectionUtil;
import client.nilore.utils.rotation.RotationHandler;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityUtil {

    private static final Map<Integer, Entity> FAKE_ENTITY_CACHE = new ConcurrentHashMap<>();
    private static Level lastLevel = null;

    public static Entity getOrCreateSyncedEntity(Entity original, String targetStr) {
        Level currentLevel = original.level();

        if (lastLevel != currentLevel) {
            FAKE_ENTITY_CACHE.clear();
            lastLevel = currentLevel;
        }

        EntityType<?> targetType = switch (targetStr) {
            case "Cow" -> EntityType.COW;
            case "Zombie" -> EntityType.ZOMBIE;
            case "Creeper" -> EntityType.CREEPER;
            default -> EntityType.PIG;
        };

        Entity fake = FAKE_ENTITY_CACHE.computeIfAbsent(original.getId(), id -> targetType.create(currentLevel));
        if (fake == null) return null;

        if (fake.getType() != targetType) {
            fake = targetType.create(currentLevel);
            if (fake == null) return null;
            FAKE_ENTITY_CACHE.put(original.getId(), fake);
        }

        fake.copyPosition(original);
        fake.setBoundingBox(original.getBoundingBox());
        fake.tickCount = original.tickCount;
        fake.setOnGround(original.onGround());

        if (fake instanceof LivingEntity fakeLiving && original instanceof LivingEntity origLiving) {
            fakeLiving.setPos(origLiving.getX(), origLiving.getY(), origLiving.getZ());

            fakeLiving.xo = origLiving.xo;
            fakeLiving.yo = origLiving.yo;
            fakeLiving.zo = origLiving.zo;

            fakeLiving.swingTime = origLiving.swingTime;
            fakeLiving.attackAnim = origLiving.attackAnim;
            fakeLiving.oAttackAnim = origLiving.oAttackAnim;
            fakeLiving.hurtTime = origLiving.hurtTime;
            fakeLiving.hurtDuration = origLiving.hurtDuration;
            fakeLiving.deathTime = origLiving.deathTime;
            fakeLiving.setYRot(origLiving.getYRot());
            fakeLiving.setXRot(origLiving.getXRot());
            fakeLiving.yRotO = origLiving.yRotO;
            fakeLiving.xRotO = origLiving.xRotO;

            fakeLiving.yBodyRot = origLiving.yBodyRot;
            fakeLiving.yBodyRotO = origLiving.yBodyRotO;

            fakeLiving.yHeadRot = origLiving.yHeadRot;
            fakeLiving.yHeadRotO = origLiving.yHeadRotO;
            if (origLiving == Minecraft.getInstance().player) {
                float pitch = RotationHandler.sentRotation.getPitch();
                float prevPitch = RotationHandler.prevSentRotation.getPitch();
                float yaw = RotationHandler.sentRotation.getYaw();
                float prevYaw = RotationHandler.prevSentRotation.getYaw();

                fakeLiving.setXRot(pitch);
                fakeLiving.xRotO = prevPitch;

                fakeLiving.yHeadRot = yaw;
                fakeLiving.yHeadRotO = prevYaw;
                fakeLiving.setYHeadRot(yaw);
            }

            WalkAnimationState origAnim = origLiving.walkAnimation;
            WalkAnimationState fakeAnim = fakeLiving.walkAnimation;
            copyWalkAnimation(origAnim, fakeAnim);
        }

        return fake;
    }

    public static void clearCache() {
        FAKE_ENTITY_CACHE.clear();
    }

    private static void copyWalkAnimation(WalkAnimationState from, WalkAnimationState to) {
        try {
            for (Field field : WalkAnimationState.class.getDeclaredFields()) {
                field.setAccessible(true);
                field.set(to, field.get(from));
            }
        } catch (Exception ignored) {
        }
    }
}
