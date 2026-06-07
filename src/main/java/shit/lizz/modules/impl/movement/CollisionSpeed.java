package shit.lizz.modules.impl.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.utils.game.MovementUtil;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.rotation.Rotation;
import shit.lizz.event.EventTarget;

public class CollisionSpeed
extends Module {
    public CollisionSpeed() {
        super("CollisionSpeed", Category.MOVEMENT);
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (motionEvent.isPost() && MovementUtil.isInputActive() && mc.player != null && mc.level != null) {
            AABB playerBox = mc.player.getBoundingBox().expandTowards(0.25, 0.25, 0.25);
            int collisionCount = 0;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity) && !(entity instanceof Boat) && !(entity instanceof Minecart) && !(entity instanceof FishingHook) || entity instanceof ArmorStand || entity.getId() == mc.player.getId() || !entity.isAlive() || !playerBox.intersects(entity.getBoundingBox()) || entity.getId() == -8 || entity.getId() == -1337 || mc.player.getTeam() != null && entity.getTeam() != null && !entity.getTeam().getCollisionRule().equals(Team.CollisionRule.ALWAYS) && (mc.player.getTeam().isAlliedTo(entity.getTeam()) ? !entity.getTeam().getCollisionRule().equals(Team.CollisionRule.PUSH_OWN_TEAM) : !entity.getTeam().getCollisionRule().equals(Team.CollisionRule.PUSH_OTHER_TEAMS))) continue;
                ++collisionCount;
            }
            float moveYaw = mc.player.getYRot();
            if (mc.options.keyDown.isDown()) {
                moveYaw += 180.0f;
                if (mc.options.keyLeft.isDown()) {
                    moveYaw += 45.0f;
                } else if (mc.options.keyRight.isDown()) {
                    moveYaw -= 45.0f;
                }
            } else if (mc.options.keyUp.isDown()) {
                if (mc.options.keyLeft.isDown()) {
                    moveYaw -= 45.0f;
                } else if (mc.options.keyRight.isDown()) {
                    moveYaw += 45.0f;
                }
            } else if (mc.options.keyRight.isDown()) {
                moveYaw += 90.0f;
            } else if (mc.options.keyLeft.isDown()) {
                moveYaw -= 90.0f;
            }
            double moveYawRad = Math.toRadians(moveYaw);
            double speedBoost = 0.065f * (float)Math.min(4, collisionCount);
            if (TargetStrafe.strafeTarget != null && TargetStrafe.INSTANCE.isEnabled() && (!TargetStrafe.isSmartStrafe() || mc.options.keyJump.isDown())) {
                float angleStep = (float)(speedBoost / ((double)TargetStrafe.getRange() * Math.PI * 2.0) * 360.0) * (float)TargetStrafe.strafeDirectionSign;
                Rotation rotation = RotationUtil.rotationToForBow(new Vec3(TargetStrafe.strafeTarget.getX(), TargetStrafe.strafeTarget.getY(), TargetStrafe.strafeTarget.getZ()), new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                rotation.setYaw(rotation.getYaw() + angleStep);
                float strafeYawRad = rotation.getYaw() * ((float)Math.PI / 180);
                double strafeX = TargetStrafe.strafeTarget.getX() - Math.sin(strafeYawRad) * (double)TargetStrafe.getRange();
                double strafeZ = TargetStrafe.strafeTarget.getZ() + Math.cos(strafeYawRad) * (double)TargetStrafe.getRange();
                moveYawRad = RotationUtil.rotationToForBow(new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ()), new Vec3(strafeX, TargetStrafe.strafeTarget.getY(), strafeZ)).getYaw() * ((float)Math.PI / 180);
            }
            MovementUtil.strafeWithYaw(moveYawRad, speedBoost);
            MovementUtil.strafeForward(1.0E-4f);
        }
    }
}