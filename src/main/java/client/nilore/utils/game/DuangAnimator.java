package client.nilore.utils.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受击"duang"弹簧。
 *
 * <p>模型不是直接插值回 1.0, 而是一个二阶系统: 受击时给竖直缩放一个向下的初速度, 弹簧
 * 把它拉回来, 因为阻尼略低于临界, 它会先压扁 -> 过冲 -> 回弹 -> 收敛。
 *
 * <p>两个和"丝滑"直接相关的实现细节:
 *
 * <ol>
 *   <li><b>积分子步</b>: 一个 tick 的 1/20 秒被切成 {@link #SUBSTEPS} 份再积分。之前是
 *       单步欧拉 —— 当 Duang Speed 拉到 20 时 {@code omega * dt = 1.0}, 单步欧拉在这个
 *       边界上会数值发散(表现为抖、甚至越弹越大), 这就是"不丝滑"的第一个来源。</li>
 *   <li><b>解析插值</b>: 渲染 60~200 帧/秒, 而状态只在 20Hz 更新。之前用
 *       {@code value + velocity * dt * partialTick} 做线性外推, 弹簧越陡接缝越明显;
 *       现在直接把阻尼振子的闭合解在 {@code partialTick} 时刻求值, 帧间是真正的曲线。</li>
 * </ol>
 */
public final class DuangAnimator {

    /** 竖直缩放允许的范围, 防止连续受击把模型压成一条线。 */
    private static final double MIN_SCALE = 0.35;
    private static final double MAX_SCALE = 1.75;
    /** 一个 tick。 */
    private static final double DT = 1.0 / 20.0;
    /** 积分子步数: 1/160 秒一步, 对 omega 到 20 仍然稳定。 */
    private static final int SUBSTEPS = 8;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        private double value = 1.0;
        private double velocity;
        /** 积分用的刚度与阻尼比, 记在状态上, 供解析插值复用同一组参数。 */
        private double omega = 9.0;
        private double zeta = 0.9;
    }

    private DuangAnimator() {
    }

    /**
     * 记一次受击。
     *
     * @param strength 0~1, 由模块的 Duang Strength 提供; 0 表示关闭
     */
    public static void kick(int entityId, double strength) {
        if (strength <= 0.0 || !Double.isFinite(strength)) {
            return;
        }
        State state = STATES.computeIfAbsent(entityId, key -> new State());
        // 初速度向下: 先压, 再由弹簧拉回。乘 6 是把 0~1 的强度映射到"一 tick 内明显形变"。
        state.velocity -= strength * 6.0;
    }

    /**
     * 推进一步。{@code stiffness} 越大回弹越快, {@code damping} 越小弹得越久。
     */
    public static void tick(double stiffness, double damping) {
        if (STATES.isEmpty()) {
            return;
        }
        double omega = Math.max(stiffness, 0.5);
        double zeta = Math.max(damping, 0.05);
        double dampingCoefficient = 2.0 * zeta * omega;
        double h = DT / SUBSTEPS;
        STATES.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            state.omega = omega;
            state.zeta = zeta;
            for (int step = 0; step < SUBSTEPS; step++) {
                double accel = -omega * omega * (state.value - 1.0) - dampingCoefficient * state.velocity;
                state.velocity += accel * h;
                state.value += state.velocity * h;
                state.value = Math.min(MAX_SCALE, Math.max(MIN_SCALE, state.value));
            }
            return Math.abs(state.value - 1.0) < 1.0E-3 && Math.abs(state.velocity) < 1.0E-3;
        });
    }

    /**
     * 当前竖直缩放。{@code partialTick} 用来把两个 tick 之间按阻尼振子的闭合解补出来,
     * 帧间连续可导, 不会出现 20Hz 的台阶。
     */
    public static float verticalScale(int entityId, float partialTick) {
        State state = STATES.get(entityId);
        if (state == null) {
            return 1.0f;
        }
        double t = Mth_clamp(partialTick, 0.0f, 1.0f) * DT;
        double offset = solve(state.value - 1.0, state.velocity, t, state.omega, state.zeta);
        return (float) Math.min(MAX_SCALE, Math.max(MIN_SCALE, 1.0 + offset));
    }

    /**
     * 阻尼振子 {@code x'' + 2*zeta*omega*x' + omega^2*x = 0} 在 t 时刻的解。
     * 欠阻尼用闭合形式, 临界/过阻尼退化成指数项 —— 后者只在阻尼比被改大时才会走到。
     */
    private static double solve(double x0, double v0, double t, double omega, double zeta) {
        if (t <= 0.0) {
            return x0;
        }
        if (zeta < 1.0) {
            double damped = omega * Math.sqrt(1.0 - zeta * zeta);
            double decay = Math.exp(-zeta * omega * t);
            double a = x0;
            double b = (v0 + zeta * omega * x0) / damped;
            return decay * (a * Math.cos(damped * t) + b * Math.sin(damped * t));
        }
        double decay = Math.exp(-omega * t);
        return x0 * decay + v0 * t * decay;
    }

    public static boolean isAnimating(int entityId) {
        return STATES.containsKey(entityId);
    }

    /** 体积近似守恒: 竖直缩放为 v 时, 水平取 1/sqrt(v)。 */
    public static float horizontalScale(float verticalScale) {
        if (verticalScale <= 0.0f) {
            return 1.0f;
        }
        return (float) (1.0 / Math.sqrt(verticalScale));
    }

    public static void clear() {
        STATES.clear();
    }

    private static float Mth_clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
