package client.nilore.modules.impl.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import client.nilore.event.impl.MotionEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.MovementUtil;
import client.nilore.event.EventTarget;

/**
 * 实体碰撞加速。
 *
 * <p>原实现(自 d3838b2 恢复)把增量写死成 {@code 0.065 * min(4, contacts)} 再整块塞进水平
 * 速度里, 那个值既不看当前速度也不看还能吃下多少: 贴着人走的时候常常直接把水平速度顶到
 * 服务端预测上限之外, 于是 Grim 的 prediction 每 tick 都要为一段无法解释的位移找理由。
 *
 * <p>现在增量交给 {@link SpeedGrimCollide#increment} 解析求解: 先算出当前水平速度在移动
 * 方向上的投影, 再解出"投影正好落在 limit 圆上"所需的步长, 取它与剩余余量的较小值。
 * 两个性质因此是构造性成立的: 结果非负, 且叠加后的水平速度永远不会越过 {@code limit}。
 */
public class CollisionSpeed extends Module {
    /**
     * 判定碰撞用的 AABB 外扩量, 写死 0.1 格。
     *
     * <p>换算成中心距大约是 0.3(自身半宽) + 0.1 + 0.3(目标半宽) ≈ 0.7 格 —— 比原版的
     * 0.25 更"贴身"一档, 只有几乎重叠时才算接触。
     */
    private static final double COLLISION_EXPAND = 0.1;
    /** 单实体的基础增量(格/tick), 再乘接触数量。 */
    private static final double BASE_BOOST = 0.065;
    /**
     * 叠加后允许出现的最大水平速度。疾跑跳跃的水平速度约 0.33, 留一点余量取 0.34;
     * 超过这个值就说明增量根本没有空间, 此时增量函数自己返回 0。
     */
    private static final double SPEED_LIMIT = 0.34;

    public final BooleanSetting onlyOnGround = new BooleanSetting("Only On Ground", false);
    public final BooleanSetting strafeBoost = new BooleanSetting("Strafe Boost", true);

    /** 单实体基础增量(格/tick); 上界与 {@link SpeedGrimCollide} 的入参校验一致。 */
    public final NumberSetting boost = new NumberSetting("Boost", BASE_BOOST, 0.005, 0.08, 0.005);
    /** 叠加后允许达到的最大水平速度(格/tick)。疾跑跳跃约 0.33。 */
    public final NumberSetting speedLimit = new NumberSetting("Speed Limit", SPEED_LIMIT, 0.20, 1.0, 0.01);

    public CollisionSpeed() {
        super("CollisionSpeed", Category.MOVEMENT);
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (!motionEvent.isPost()
                || mc.player == null
                || mc.level == null
                || !MovementUtil.isInputActive()
                || (this.onlyOnGround.getValue() && !mc.player.onGround())) {
            return;
        }

        int contacts = this.countContacts();
        if (contacts <= 0) {
            return;
        }

        Vec3 motion = mc.player.getDeltaMovement();
        SpeedAccess.Collision collision = SpeedAccess.Collision.of(contacts, motion.x, motion.z);

        double yawRad = MovementUtil.getMovementYaw();
        if (this.strafeBoost.getValue() && TargetStrafe.INSTANCE != null && TargetStrafe.INSTANCE.isEnabled()
                && TargetStrafe.strafeTarget != null
                && (!TargetStrafe.isSmartStrafe() || mc.options.keyJump.isDown())) {
            yawRad = this.strafeYawRad(yawRad);
        }

        double increment = SpeedGrimCollide.increment(collision, (float) yawRad,
                this.boost.getValue().doubleValue(),
                this.speedLimit.getValue().doubleValue());
        if (increment <= 0.0) {
            return;
        }
        MovementUtil.strafeWithYaw(yawRad, increment);
    }

    /**
     * 绕目标横移时的移动方向: 把当前朝向按目标半径折算成一个很小的角度偏移, 再取
     * "目标 -> 玩家" 的反向作为前进方向。角度步长随注入量缩放, 所以速度越快绕得越快,
     * 但任何一 tick 的偏移都远小于 1 度。
     */
    private double strafeYawRad(double fallback) {
        Entity target = TargetStrafe.strafeTarget;
        if (target == null) {
            return fallback;
        }
        double range = Math.max(TargetStrafe.getRange(), 0.1);
        double stepDegrees = this.boost.getValue().doubleValue() / (range * Math.PI * 2.0) * 360.0 * TargetStrafe.strafeDirectionSign;
        Vec3 playerPos = mc.player.position();
        Vec3 targetPos = new Vec3(target.getX(), target.getY(), target.getZ());
        double baseYaw = Math.toDegrees(Math.atan2(targetPos.z - playerPos.z, targetPos.x - playerPos.x)) + 90.0 + stepDegrees;
        // 目标始终在移动, 用同一条路径把角度落后一 tick 的滞后补回来。
        return Math.toRadians(baseYaw);
    }

    /** 统计这一 tick 会被服务端算作推挤来源的实体数量。 */
    private int countContacts() {
        AABB box = mc.player.getBoundingBox().inflate(COLLISION_EXPAND);
        int contacts = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isPusher(entity) || !this.collidesWith(entity, box)) {
                continue;
            }
            if (++contacts >= 4) {
                // 服务端同一 tick 最多只处理四个象限的推挤, 再数下去也不会改变位移。
                return contacts;
            }
        }
        return contacts;
    }

    private boolean isPusher(Entity entity) {
        if (entity == mc.player || !entity.isAlive() || entity instanceof ArmorStand) {
            return false;
        }
        if (entity.getId() == -8 || entity.getId() == -1337) {
            // 客户端为水域/实体碰撞生成的虚拟占位 id, 不是真实实体。
            return false;
        }
        if (!(entity instanceof LivingEntity || entity instanceof Boat || entity instanceof Minecart || entity instanceof FishingHook)) {
            return false;
        }
        return this.canPush(entity);
    }

    private boolean collidesWith(Entity entity, AABB box) {
        return box.intersects(entity.getBoundingBox());
    }

    /** 队伍碰撞规则: ALLOW / PUSH_OWN_TEAM / PUSH_OTHER_TEAMS, 与 sentinel 一致。 */
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
