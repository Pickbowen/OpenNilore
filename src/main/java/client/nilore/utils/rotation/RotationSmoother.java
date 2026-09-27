package client.nilore.utils.rotation;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 逐轴"速度惯性 + 随机波动"转头平滑器, KillAura / Scaffold 共用。
 *
 * 目标速度与旧实现同一收敛律 —— speed 以内一步到位, 超过之后每 tick 至少收敛掉误差的
 * TURN_RATIO; 所以调速度设置的手感、平均速度都不变。在此之上做三件"去机器味"的事:
 *
 *  1. 角速度惯性: 实际速度向目标速度靠拢而不是跳变。纯 clamp 是 0 -> 满速 -> 0 的方波,
 *     "匀速段 + 骤停"正是统计型反作弊最好抓的特征; 人手有惯性, 起步 1~2 tick 才到全速,
 *     接近目标时速度先降下来。加速响应比减速快, 大角度甩动的爆发感不受影响。
 *  2. 每 tick 速度随机波动(两轴独立 roll): 期望 1.0, 平均速度不变, 但连续两 tick 的
 *     增量不再相同。Grim 的 DuplicateRotPlace 专门抓"相邻两次放置时 yaw 增量完全相同",
 *     这一层就是它的解; 同时 yaw:pitch 比例每 tick 都不同, 轨迹不再是直线。
 *  3. 收尾速度自然衰减: 目标速度随剩余误差缩小而缩小, 减速段比加速段更缓。
 *
 * 输出不超过剩余误差的绝对值, 因此这一层永远不会过冲。发送前仍必须做 GCD 量化
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

    private final Random random;
    private double velYaw;
    private double velPitch;

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
        float yaw = from.getYaw() + (float) smoothAxisDelta(Mth.wrapDegrees(to.getYaw() - from.getYaw()), speed, true);
        float pitch = Mth.clamp(from.getPitch() + (float) smoothAxisDelta(to.getPitch() - from.getPitch(), speed, false), -90.0f, 90.0f);
        return new Rotation(yaw, pitch);
    }

    /**
     * 单轴转头: 输入这一 tick 的带符号角误差, 输出实际走的带符号步长, 并更新该轴速度状态。
     * 状态只有这一个来源, 所以即使 from 被别的模块改写(全局共享 prevRotation), 也不会
     * 像"从 (current - previous) 反推速度"那样把别人的运动当成自己的惯性。
     */
    public double smoothAxisDelta(double delta, double speed, boolean yawAxis) {
        double dist = Math.abs(delta);
        double targetVel = Math.max(speed, dist * TURN_RATIO);
        double prevVel = yawAxis ? this.velYaw : this.velPitch;

        double resp = prevVel < targetVel ? RESP_UP : RESP_DOWN;
        double vel = prevVel + (targetVel - prevVel) * resp;
        vel *= 1.0 + (this.random.nextDouble() * 2.0 - 1.0) * WOBBLE;
        // 剩余误差不够走时截断: 不会过冲, 截断值作为下一 tick 的速度起点(自然的减速)。
        vel = Math.min(vel, dist);

        if (yawAxis) {
            this.velYaw = vel;
        } else {
            this.velPitch = vel;
        }
        return delta < 0.0 ? -vel : vel;
    }

    /** 换目标/模块重开时调用: 速度状态清零, 下一 tick 从静止重新起步。 */
    public void reset() {
        this.velYaw = 0.0;
        this.velPitch = 0.0;
    }

    // ------------------------------------------------------------------
    // GCD 量化(静态工具): Grim AimProcessor 不变式
    // ------------------------------------------------------------------

    /**
     * 一次鼠标移动对应的角度步长(度)。真实鼠标产生的旋转增量永远是它的整数倍,
     * 所以发出去的 rotation 增量也必须落在同一张网格上, 否则就是"非人手"的旋转。
     */
    public static double sensitivityStep() {
        double sensitivity = Minecraft.getInstance().options.sensitivity().get().floatValue() * 0.6 + 0.2;
        return sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
    }

    /** 把角度增量取整到灵敏度步长的整数倍。 */
    public static float quantizeToStep(double delta, double step) {
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
}
