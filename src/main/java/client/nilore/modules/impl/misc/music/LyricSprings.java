package client.nilore.modules.impl.misc.music;

import java.util.List;

/**
 * 歌词弹簧的参数推导。
 *
 * <p>歌词滑动不能用一组固定的弹簧参数：行与行挨得近时想要跟手、干脆，行间留白大时想要慢一点、
 * 有重量感。这里按行间间隔算出目标「响应时间」和「阻尼比」，再换算成质量/阻尼/刚度三元组
 * 喂给 {@link client.nilore.utils.animation.SpringSolver}。
 *
 * <p>另一块是「意外跳转」的判定：用户拖进度条、或者音频时钟跳变时，如果还按正常参数滑，
 * 会看到歌词缓慢飘过去——所以要换成更硬的弹簧让它快速落位。
 */
public final class LyricSprings {

    /** 一行一句的默认参数，也是背景和声、跳转回退时的落点。 */
    public static final Physics DEFAULT = new Physics(1.0, 18.0, 100.0);
    /** 逐行错峰入场的时间步长（秒）。 */
    public static final double CASCADE_STEP = 0.05;
    /** ln(0.01)，用来把「响应时间」换算成临界阻尼的衰减率。 */
    private static final double LOG_ONE_PERCENT = -Math.log(0.01);

    private LyricSprings() {
    }

    /**
     * 由响应时间（秒）和阻尼比反推三元组。
     *
     * @param dampingRatio 阻尼比，1.0 临界阻尼（不过冲），小于 1 会有回弹
     * @param response     从起点走到目标约 99% 所需的时间（秒）
     */
    public static Physics responsePhysics(double dampingRatio, double response, double mass) {
        double omega = Math.PI * 2.0 / Math.max(response, 0.001);
        return new Physics(mass, 2.0 * dampingRatio * mass * omega, mass * omega * omega);
    }

    /** 按行间间隔（秒）选参数：挨得近偏干脆，留白大偏柔和。 */
    public static Physics gapDrivenLineSpring(double interLineGap) {
        double amount = clamp((interLineGap - 0.2) / 0.55, 0.0, 1.0);
        return responsePhysics(lerp(0.9, 0.78, amount), lerp(0.48, 0.75, amount), 1.0);
    }

    /** 整行滚动用的参数：有逐字时间时跟着间隙走，否则退回默认。 */
    public static Physics lineTransitionSpring(boolean hasTimedSyllables, double interLineGap) {
        return hasTimedSyllables ? gapDrivenLineSpring(interLineGap) : DEFAULT;
    }

    /** 临界阻尼 + 指定响应时间，用于「必须准时到位」的场合。 */
    public static Physics criticalEnvelopeSpring(double mass, double response) {
        double rate = LOG_ONE_PERCENT / Math.max(response, 0.01);
        return new Physics(mass, 2.0 * mass * rate, mass * rate * rate);
    }

    /**
     * 行快唱完时缩短响应时间。
     *
     * <p>否则会出现「本行已经唱到最后一个字，歌词还慢悠悠往上飘」的错位感。
     * {@code remaining} 是距离本行结束还剩多少秒。
     */
    public static Physics retimeLineSpring(Physics physics, double lineEnd, double playhead, boolean continuous) {
        if (continuous) {
            return criticalEnvelopeSpring(physics.mass, 0.3);
        }
        double remaining = lineEnd - playhead - 0.5;
        double naturalRate = Math.sqrt(physics.stiffness / physics.mass);
        double dampingRatio = physics.damping / (2.0 * Math.sqrt(physics.mass * physics.stiffness));
        double oldEnvelope = LOG_ONE_PERCENT / (naturalRate * clamp(dampingRatio, 0.1, 1.0));
        if (remaining < 0.8 && oldEnvelope - remaining < -0.05) {
            double response = Math.max(0.01, Math.max(remaining - 0.4, 0.3));
            return criticalEnvelopeSpring(physics.mass, response);
        }
        return physics;
    }

    /** 跳转跨度是否够大，够大就不再补间动画，直接快速落位。 */
    public static boolean isLargeSeek(int indexDelta, double timeDelta) {
        return Math.abs(indexDelta) > 3 || Math.abs(timeDelta) > 2.0;
    }

    /**
     * 音频时钟是否发生硬跳变（拖进度条、切歌、时钟重置）。
     *
     * <p>注意这里只认「时间轴本身不连续」，正常播放时 position 是单调递增的。
     */
    public static boolean isHardClockDiscontinuity(double oldTime, double newTime) {
        return Math.abs(newTime - oldTime) >= 1.5
                || oldTime - newTime > 1.0
                || oldTime > 5.0 && newTime < oldTime * 0.1
                || Math.abs(newTime - oldTime) > 2.0;
    }

    /** 跳转时该用哪组参数。 */
    public static Physics seekSpring(int indexDelta, double timeDelta) {
        return isLargeSeek(indexDelta, timeDelta)
                ? DEFAULT
                : criticalEnvelopeSpring(2.0, 0.1);
    }

    /** 第 {@code validDistance} 行相对锚点的入场延迟。 */
    public static double cascadeDelay(int validDistance, boolean disabled) {
        return disabled ? 0.0 : Math.max(validDistance - 1, 0) * CASCADE_STEP;
    }

    /**
     * 两行之间隔着几个「真实歌词行」，用来算级联延迟。
     *
     * <p>本项目的歌词数据没有和声轨标记，所以就是纯行距；保留这个方法是为了以后接入
     * 多轨歌词（和声/对唱）时只改这里。
     */
    public static int validLineDistance(List<LyricLine> lines, int first, int second) {
        if (first < 0 || second < 0 || first == second) {
            return 0;
        }
        return Math.abs(second - first);
    }

    public static double clamp(double value, double lo, double hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** 质量 / 阻尼 / 刚度。 */
    public record Physics(double mass, double damping, double stiffness) {
    }
}
