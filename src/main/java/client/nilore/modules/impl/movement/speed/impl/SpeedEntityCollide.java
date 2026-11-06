package client.nilore.modules.impl.movement.speed.impl;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.MotionEvent;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.SpeedModule;
import client.nilore.modules.impl.movement.speed.SpeedMode;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.utils.game.MovementUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class SpeedEntityCollide extends SpeedMode {
    private static final double BOOST_PER_ENTITY = 0.078;

    private final BooleanSetting moreExpand = new BooleanSetting("More Expand", false, () -> SpeedModule.is(this));

    public SpeedEntityCollide() {
        super("EntityCollide");
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!event.isPre()) return;
        if (mc.player == null || mc.level == null) return;
        if (!MovementUtil.isMoving()) return;

        double expand = this.moreExpand.getValue() ? 1.0 : 0.8;
        AABB box = mc.player.getBoundingBox().inflate(expand);
        int collisions = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player
                    && entity instanceof LivingEntity
                    && !(entity instanceof ArmorStand)
                    && box.intersects(entity.getBoundingBox())) {
                collisions++;
            }
        }

        double yaw = Math.toRadians(this.movementYaw());
        double boost = BOOST_PER_ENTITY * collisions;
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(
                motion.x - Math.sin(yaw) * boost,
                motion.y,
                motion.z + Math.cos(yaw) * boost);
    }

    private float movementYaw() {
        KillAura aura = KillAura.INSTANCE;
        if (aura != null && aura.isEnabled() && aura.rotation != null) {
            return aura.rotation.getYaw();
        }
        Rotation rotation = RotationHandler.targetRotation;
        if (RotationHandler.isRotating && rotation != null) {
            return rotation.getYaw();
        }
        return mc.player.getYRot();
    }
}
