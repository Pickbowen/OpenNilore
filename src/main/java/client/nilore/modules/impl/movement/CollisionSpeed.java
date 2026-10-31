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
    /**
     * 两个玩家碰撞盒**相贴**（间隙 0）时的水平中心距。
     *
     * <p>玩家碰撞盒宽 0.6，各自半宽 0.3，所以贴在一起时中心距 = 0.3 + 0.3 = 0.6 格。
     * 设置里的 Distance 是"中心距"，换算成搜索盒的膨胀量要减掉这一项。
     */
    private static final double PLAYER_TOUCH_DISTANCE = 0.6;
    private static final double FULL_CONTACT = 0.0;
    private static final double MAX_CONTACT = 1.0;
    private static final double MIN_FALLOFF = 0.5;
    private static final double SPEED_LIMIT = 0.40;

    public final NumberSetting boost = new NumberSetting("Boost", 0.16, 0.005, 0.16, 0.005);
    public final NumberSetting speedLimit = new NumberSetting("Speed Limit", SPEED_LIMIT, 0.20, 1.0, 0.01);
    /**
     * 触发距离，按**两个玩家之间的中心距**计（格）。
     *
     * <p>默认 1.0 格对应碰撞盒间隙 0.4 格。以前这里是硬编码的 0.2 格间隙（≈0.8 中心距）。
     */
    public final NumberSetting distance = new NumberSetting("Distance", 1.0, 0.3, 6.0, 0.1);

    /** 最近一个接触者的水平间隙(格), 无接触时保持 MAX_VALUE。 */
    private double nearestContact;

    /** 设置里的中心距 → 碰撞盒之间的水平间隙（搜索盒的膨胀量）。 */
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

    /** 统计这一 tick 会被服务端算作推挤来源的实体数量。 */
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
                // 服务端同一 tick 最多只处理四个象限的推挤, 再数下去也不会改变位移。
                return contacts;
            }
        }
        return contacts;
    }

    /** 两个碰撞盒在水平面上的最近距离；重叠时为 0。 */
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

    /**
     * 谁会被服务端当成推挤来源。只按 Entity 类型筛，队伍/碰撞规则交给 {@link #canPush}。
     *
     * <p>这里刻意不收窄到 {@code Player}：船、矿车、钓钩、其它 LivingEntity 在服务端同样参与
     * 实体推挤判定，漏掉它们会让 {@code contacts} 直接归零。
     */
    private boolean isPusher(Entity entity) {
        if (entity == mc.player || !entity.isAlive() || entity instanceof ArmorStand) {
            return false;
        }
        if (entity.getId() == -8 || entity.getId() == -1337) {
            // 客户端为水域/实体碰撞生成的虚拟占位 id, 不是真实实体。
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

    /**
     * 队伍碰撞规则: ALWAYS / PUSH_OWN_TEAM / PUSH_OTHER_TEAMS, 与 sentinel 一致。
     *
     * <p>这里已经完整表达了"能不能被这个实体推动"：{@code ALWAYS} 无视队伍，其余按
     * 同盟关系分别对应 PUSH_OWN_TEAM / PUSH_OTHER_TEAMS。再叠一层"必须是敌人"的判定
     * 会把 collisionRule=ALWAYS 的同队实体也一起排掉，等于把 contacts 判成 0。
     */
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
