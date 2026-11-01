package client.nilore.modules.impl.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import client.nilore.event.impl.MotionEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.MovementUtil;
import client.nilore.event.EventTarget;

public class CollisionSpeed extends Module {
    private static final double PLAYER_TOUCH_DISTANCE = 0.6;
    private static final double FULL_CONTACT = 0.0;
    private static final double MAX_CONTACT = 1.0;
    private static final double MIN_FALLOFF = 0.5;
    private static final double SPEED_LIMIT = 0.40;

    public final NumberSetting boost = new NumberSetting("Boost", 0.03, 0.005, 0.16, 0.005);
    public final NumberSetting speedLimit = new NumberSetting("Speed Limit", SPEED_LIMIT, 0.20, 1.0, 0.01);
    public final NumberSetting distance = new NumberSetting("Distance", 0.6, 0.3, 6.0, 0.1);

    private double nearestContact;

    private double contactGap() {
        return Math.max(0.0, this.distance.getValue().doubleValue() - PLAYER_TOUCH_DISTANCE);
    }

    public CollisionSpeed() {
        super("CollisionSpeed", Category.MOVEMENT);
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (!motionEvent.isPost()
                || mc.player == null
                || mc.level == null
                || !MovementUtil.isInputActive()) {
            return;
        }

        this.nearestContact = Double.MAX_VALUE;
        int contacts = this.countContacts();
        if (contacts <= 0) {
            return;
        }

        Vec3 motion = mc.player.getDeltaMovement();
        SpeedAccess.Collision collision = SpeedAccess.Collision.of(contacts, motion.x, motion.z);

        double horizontal = Math.hypot(motion.x, motion.z);
        double yawRad = horizontal > 1.0E-4
                ? Math.atan2(-motion.x, motion.z)
                : MovementUtil.getMovementYaw();

        double increment = SpeedGrimCollide.increment(collision, (float) yawRad,
                this.boost.getValue().doubleValue() * this.falloff(),
                this.speedLimit.getValue().doubleValue());
        if (increment <= 0.0) {
            return;
        }
        MovementUtil.strafeWithYaw(yawRad, increment);
    }

    private int countContacts() {
        AABB playerBox = mc.player.getBoundingBox();
        AABB search = playerBox.inflate(this.contactGap());
        int contacts = 0;
        for (Entity entity : mc.level.getEntities(mc.player, search)) {
            if (!isPusher(entity)) {
                continue;
            }
            AABB target = entity.getBoundingBox();
            if (!search.intersects(target)) {
                continue;
            }
            double gap = horizontalGap(playerBox, target);
            if (gap < this.nearestContact) {
                this.nearestContact = gap;
            }
            if (++contacts >= 4) {
                return contacts;
            }
        }
        return contacts;
    }

    private static double horizontalGap(AABB a, AABB b) {
        double dx = Math.max(0.0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dz = Math.max(0.0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.hypot(dx, dz);
    }

    private double falloff() {
        if (this.nearestContact <= FULL_CONTACT) {
            return 1.0;
        }
        if (this.nearestContact >= MAX_CONTACT) {
            return 0.0;
        }
        return Math.max(MIN_FALLOFF, (MAX_CONTACT - this.nearestContact) / (MAX_CONTACT - FULL_CONTACT));
    }

    private boolean isPusher(Entity entity) {
        if (entity == mc.player || !entity.isAlive() || entity instanceof ArmorStand) {
            return false;
        }
        if (entity.getId() == -8 || entity.getId() == -1337) {
            return false;
        }
        if (entity instanceof Player player && player.isSpectator()) {
            return false;
        }
        if (!(entity instanceof LivingEntity || entity instanceof Boat || entity instanceof Minecart || entity instanceof FishingHook)) {
            return false;
        }
        return this.canPush(entity);
    }

    private boolean canPush(Entity entity) {
        if (mc.player.getTeam() == null || entity.getTeam() == null) {
            return true;
        }
        Team.CollisionRule rule = entity.getTeam().getCollisionRule();
        if (rule == Team.CollisionRule.ALWAYS) {
            return true;
        }
        boolean allied = mc.player.getTeam().isAlliedTo(entity.getTeam());
        return allied ? rule == Team.CollisionRule.PUSH_OWN_TEAM : rule == Team.CollisionRule.PUSH_OTHER_TEAMS;
    }
}
