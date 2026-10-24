package client.nilore.utils.rotation;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 逐轴"速度惯性 + 随机波动"转头平滑器, KillAura / Scaffold / AutoWebPlace 共用。
 *
 * <p>目标速度与旧实现同一收敛律 —— speed 以内一步到位, 超过之后每 tick 至少收敛掉误差的
 * {@link #TURN_RATIO}; 所以调速度设置的手感、平均速度都不变。在此之上做四件"去机器味"的事:
 *
 * <ol>
 *   <li><b>角速度惯性</b>: 实际速度向目标速度靠拢而不是跳变。纯 clamp 是 0 -&gt; 满速 -&gt; 0
 *       的方波, "匀速段 + 骤停"正是统计型反作弊最好抓的特征; 人手有惯性, 起步 1~2 tick
 *       才到全速, 接近目标时速度先降下来。</li>
 *   <li><b>接近目标时软收尾</b>: 误差小于 speed 之后不再一步到位, 而是按剩余误差的平方根
 *       收敛。原实现把 vel 直接截断成 dist, 最后一 tick 精确落到目标角上, 每一轮都留下
 *       同一个"正好停在瞄准点"的指纹。</li>
 *   <li><b>每 tick 速度随机波动</b>(两轴独立 roll): 期望 1.0, 平均速度不变, 但连续两 tick
 *       的增量不再相同。Grim 的 DuplicateRotPlace 抓"相邻两次放置 yaw 增量完全相同"。</li>
 *   <li><b>重复增量守卫</b>: 量化之后的增量如果与上一次完全相等, 沿灵敏度格点挪一格。
 *       挪动仍然落在格点上, 所以 GCD 不变式不被破坏 —— 这是 DuplicateRotPlace /
 *       AimDuplicateLook 的直接解, 而且不需要引入任何格点外的偏移。</li>
 * </ol>
 *
 * <p>输出不超过剩余误差的绝对值, 因此这一层永远不会过冲。发送前仍必须做 GCD 量化
 * (见 {@link #patchConstantRotation}): Grim AimProcessor 用连续增量 GCD 反推灵敏度,
 * 只有全部增量落在真实灵敏度网格上, 推出来的结果才和真人一致。
 */
public class RotationSmoother {

    /** 角误差超过 2×speed 之后, 每 tick 至少收敛掉误差的这个比例(等效指数收敛)。 */
    private static final double TURN_RATIO = 0.5;
    /** 加速段的惯性响应(0~1): 1 = 无惯性(纯 clamp 的方波), 越小起步越软。 */
    public static final double RESP_UP = 0.8;
    /** 减速段的惯性响应, 故意比加速段小: 人手收尾是先把速度降下来再停。 */
    public static final double RESP_DOWN = 0.4;
    /** 每 tick 角速度的随机波动幅度(±比例), 两轴独立 roll。期望 1.0, 不影响平均速度。 */
    public static final double WOBBLE = 0.2;
    /**
     * pitch 相对于 yaw 的速度系数。人手上下看比左右甩慢, 而且 pitch 的大角度修正更容易
     * 让准星离开判定盒; 0.65 保留手感的同时把 pitch 的抖动幅度压到 yaw 的水平以下。
     */
    public static final double PITCH_SPEED_SCALE = 0.65;
    /**
     * 误差进入这个范围(度)之后改用软收尾, 不再一步到位。取默认灵敏度步长的量级:
     * 比它更小的误差本来就会被量化抹平, 一步到位只会制造"精确命中"的终点。
     */
    private static final double ARRIVE_SOFT_ZONE = 1.2;
    /** 判定"增量相同"的阈值, 与 Grim DuplicateRotPlace 的 xDiff < 0.0001 对齐。 */
    private static final double DUPLICATE_EPSILON = 1.0E-4;

    private final Random random;
    private double velYaw;
    private double velPitch;
    /** 上一次真正发出的量化增量, 用于重复增量守卫。NaN = 还没有历史。 */
    private float lastEmittedYawDelta = Float.NaN;
    private float lastEmittedPitchDelta = Float.NaN;

    public RotationSmoother(Random random) {
        this.random = random;
    }

    /**
     * 从 from 向 to 转一个 tick。
     *
     * @param speed 目标角速度上限(度/tick), 语义与旧的 clamp(diff, ±speed) 一致
     * @return 本 tick 应到达的旋转(未做 GCD 量化, 发送前由调用方补上)
     */
    public Rotation tick(Rotation from, Rotation to, double speed) {
        return this.tick(from, to, speed, 1.0, PITCH_SPEED_SCALE);
    }

    /**
     * 带轴系系数的版本: {@code yawScale}/{@code pitchScale} 各自缩放该轴的目标速度。
     * Scaffold 用它把"搭路时的水平对准"和"落点的俯角修正"分开调, 而不是两个轴共用一个速度。
     */
    public Rotation tick(Rotation from, Rotation to, double speed, double yawScale, double pitchScale) {
        float yaw = from.getYaw() + (float) this.smoothAxisDelta(Mth.wrapDegrees(to.getYaw() - from.getYaw()), speed * yawScale, true);
        float pitch = Mth.clamp(from.getPitch() + (float) this.smoothAxisDelta(to.getPitch() - from.getPitch(), speed * pitchScale, false), -90.0f, 90.0f);
        return new Rotation(yaw, pitch);
    }

    /**
     * 单轴转头: 输入这一 tick 的带符号角误差, 输出实际走的带符号步长, 并更新该轴速度状态。
     * 状态只有这一个来源, 所以即使 from 被别的模块改写(全局共享 prevRotation), 也不会
     * 像"从 (current - previous) 反推速度"那样把别人的运动当成自己的惯性。
     */
    public double smoothAxisDelta(double delta, double speed, boolean yawAxis) {
        double dist = Math.abs(delta);
        if (dist <= 0.0 || !Double.isFinite(dist)) {
            this.storeVelocity(yawAxis, 0.0);
            return 0.0;
        }
        double maxVel = Math.max(speed, 0.0);
        double targetVel;
        if (dist <= ARRIVE_SOFT_ZONE) {
            // 软收尾: 目标速度随剩余误差的平方根下降, 而不是一直冲到 dist 再骤停。
            // 平方根让最后一两个 tick 仍有可见步长(不至于停在半路), 但永远不一步跨完。
            targetVel = Math.max(maxVel * 0.25, Math.sqrt(dist / ARRIVE_SOFT_ZONE) * maxVel);
        } else {
            targetVel = Math.max(maxVel, dist * TURN_RATIO);
        }

        double prevVel = yawAxis ? this.velYaw : this.velPitch;
        double resp = prevVel < targetVel ? RESP_UP : RESP_DOWN;
        double vel = prevVel + (targetVel - prevVel) * resp;
        vel *= 1.0 + (this.random.nextDouble() * 2.0 - 1.0) * WOBBLE;
        // 剩余误差不够走时截断: 不会过冲, 截断值作为下一 tick 的速度起点(自然的减速)。
        vel = Math.min(Math.max(vel, 0.0), dist);

        this.storeVelocity(yawAxis, vel);
        return delta < 0.0 ? -vel : vel;
    }

    private void storeVelocity(boolean yawAxis, double vel) {
        if (yawAxis) {
            this.velYaw = vel;
        } else {
            this.velPitch = vel;
        }
    }

    /** 换目标/模块重开时调用: 速度状态清零, 下一 tick 从静止重新起步。 */
    public void reset() {
        this.velYaw = 0.0;
        this.velPitch = 0.0;
        this.lastEmittedYawDelta = Float.NaN;
        this.lastEmittedPitchDelta = Float.NaN;
    }

    // ------------------------------------------------------------------
    // GCD 量化(静态工具): Grim AimProcessor 不变式
    // ------------------------------------------------------------------

    /**
     * 一次鼠标移动对应的角度步长(度)。真实鼠标产生的旋转增量永远是它的整数倍,
     * 所以发出去的 rotation 增量也必须落在同一张网格上, 否则就是"非人手"的旋转。
     */
    public static double sensitivityStep() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return 0.15;
        }
        double sensitivity = minecraft.options.sensitivity().get().floatValue() * 0.6 + 0.2;
        return sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
    }

    /** 把角度增量取整到灵敏度步长的整数倍。 */
    public static float quantizeToStep(double delta, double step) {
        if (!Double.isFinite(delta) || step <= 0.0) {
            return 0.0f;
        }
        return (float) (Math.round(delta / step) * step);
    }

    /**
     * GCD 对齐：将旋转增量取整到灵敏度步长的整数倍，锚定在上一次发出的 rotation 上。
     * 于是网格随目标一起漂移，不会把瞄准点吸到固定格点上；收到攻击时横竖都是整数倍步长。
     */
    public static Rotation patchConstantRotation(Rotation rotation, Rotation prevRotation) {
        double step = sensitivityStep();
        float yaw = prevRotation.getYaw() + quantizeToStep(rotation.getYaw() - prevRotation.getYaw(), step);
        float pitch = Mth.clamp(prevRotation.getPitch() + quantizeToStep(rotation.getPitch() - prevRotation.getPitch(), step), -90.0f, 90.0f);
        return new Rotation(yaw, pitch);
    }

    /**
     * 在已量化的增量上做"重复增量"守卫: 若本 tick 的 yaw 增量与上一次发出的完全相同,
     * 沿灵敏度格点挪一格; pitch 同理。挪动整格保证 GCD 不变式与量化网格都不被破坏,
     * 而这正是 Grim DuplicateRotPlace / AimDuplicateLook 唯一的判据(增量差 &lt; 1e-4)。
     *
     * <p>只在增量非零时触发: 完全静止(两个轴都不动)是另一类指纹, 由
     * {@link RotationHandler#guardDuplicateRotation} 在出站那一刻处理, 这里不重复介入。
     */
    public Rotation breakDuplicateDelta(Rotation rotation, Rotation prev) {
        if (rotation == null || prev == null) {
            return rotation;
        }
        double step = sensitivityStep();
        if (step <= 0.0) {
            return rotation;
        }
        float yawDelta = rotation.getYaw() - prev.getYaw();
        float pitchDelta = rotation.getPitch() - prev.getPitch();

        if (Math.abs(yawDelta) > 1.0E-6F
                && !Float.isNaN(this.lastEmittedYawDelta)
                && Math.abs(yawDelta - this.lastEmittedYawDelta) < DUPLICATE_EPSILON) {
            float nudged = yawDelta + (yawDelta >= 0.0f ? (float) step : (float) -step);
            rotation.setYaw(prev.getYaw() + nudged);
        }
        if (Math.abs(pitchDelta) > 1.0E-6F
                && !Float.isNaN(this.lastEmittedPitchDelta)
                && Math.abs(pitchDelta - this.lastEmittedPitchDelta) < DUPLICATE_EPSILON) {
            float nudged = pitchDelta + (pitchDelta >= 0.0f ? (float) step : (float) -step);
            rotation.setPitch(Mth.clamp(prev.getPitch() + nudged, -90.0f, 90.0f));
        }

        this.lastEmittedYawDelta = rotation.getYaw() - prev.getYaw();
        this.lastEmittedPitchDelta = rotation.getPitch() - prev.getPitch();
        return rotation;
    }
}
