package client.nilore.utils.animation;

/**
 * 解析法弹簧。
 *
 * <p>和 {@link SpringAnimation} 的区别：那个是固定参数的半隐式欧拉积分，参数在构造时定死；
 * 这个把位置写成时间的闭式解，换目标或换参数时用「当前位置 + 当前速度」重新起解，
 * 所以运行中动态改软硬不会跳变。歌词每行按行间间隔换一组弹簧参数，靠的就是这一点。
 *
 * <p>另外支持延迟目标值（{@link #setTarget(double, double)}），用来做逐行错峰入场。
 */
public final class SpringSolver {

    /** 数值微分步长，和解析解保持一致的量级即可。 */
    private static final double H = 0.001;
    /** 单帧最大推进时间，防止卡顿后弹簧一口气跳过整段弧线。 */
    private static final double MAX_STEP = 0.05;

    /** 位置关于本地时间的解。 */
    private interface Solver {
        double at(double t);
    }

    private double mass;
    private double damping;
    private double stiffness;

    private double current;
    private double target;
    /** 解的本地时间，每次重解归零。 */
    private double time;
    private Solver solver = t -> 0.0;
    private long lastNanos = System.nanoTime();

    private boolean hasQueuedTarget;
    private double queuedTarget;
    private double queuedTargetDelay;

    private boolean hasQueuedParams;
    private double queuedMass;
    private double queuedDamping;
    private double queuedStiffness;
    private double queuedParamsDelay;

    public SpringSolver() {
        this(1.0, 18.0, 100.0);
    }

    public SpringSolver(double mass, double damping, double stiffness) {
        this.mass = mass;
        this.damping = damping;
        this.stiffness = stiffness;
    }

    /** 直接跳到某个位置，不产生动画。换歌、跳转对齐时用。 */
    public void setValue(double value) {
        this.current = value;
        this.target = value;
        this.time = 0.0;
        this.hasQueuedTarget = false;
        this.hasQueuedParams = false;
        this.solver = t -> value;
        this.lastNanos = System.nanoTime();
    }

    public double getValue() {
        return this.current;
    }

    public double getTarget() {
        return this.target;
    }

    /** 是否已经停在目标上（含速度趋零），可以省掉后续计算。 */
    public boolean arrived() {
        return Math.abs(this.target - this.current) < 0.01
                && Math.abs(velocityAt(this.time)) < 0.01
                && !this.hasQueuedTarget
                && !this.hasQueuedParams;
    }

    public void setParams(double mass, double damping, double stiffness) {
        this.setParams(mass, damping, stiffness, 0.0);
    }

    /** 改弹簧参数；{@code delay > 0} 时排队，到点再生效。 */
    public void setParams(double mass, double damping, double stiffness, double delay) {
        if (delay > 0.0) {
            this.hasQueuedParams = true;
            this.queuedMass = mass;
            this.queuedDamping = damping;
            this.queuedStiffness = stiffness;
            this.queuedParamsDelay = delay;
            return;
        }
        this.hasQueuedParams = false;
        this.mass = mass;
        this.damping = damping;
        this.stiffness = stiffness;
        this.resolve();
    }

    public void setTarget(double target) {
        this.setTarget(target, 0.0);
    }

    /** 改目标位置；{@code delay > 0} 时排队，到点再开始移动。 */
    public void setTarget(double target, double delay) {
        if (delay > 0.0) {
            this.hasQueuedTarget = true;
            this.queuedTarget = target;
            this.queuedTargetDelay = delay;
            return;
        }
        this.hasQueuedTarget = false;
        this.target = target;
        this.resolve();
    }

    /** 按真实时钟推进一帧。 */
    public double animate() {
        long now = System.nanoTime();
        double dt = (now - this.lastNanos) / 1.0e9;
        this.lastNanos = now;
        this.update(dt);
        return this.current;
    }

    public void update(double dt) {
        if (dt <= 0.0) {
            return;
        }
        if (dt > MAX_STEP) {
            dt = MAX_STEP;
        }
        this.time += dt;
        this.current = this.solver.at(this.time);

        if (this.hasQueuedParams) {
            this.queuedParamsDelay -= dt;
            if (this.queuedParamsDelay <= 0.0) {
                this.hasQueuedParams = false;
                this.setParams(this.queuedMass, this.queuedDamping, this.queuedStiffness);
            }
        }
        if (this.hasQueuedTarget) {
            this.queuedTargetDelay -= dt;
            if (this.queuedTargetDelay <= 0.0) {
                this.hasQueuedTarget = false;
                this.setTarget(this.queuedTarget);
            }
        }
        if (this.arrived()) {
            // 闭式解只会无限逼近目标，不 snap 的话永远差那么零点几个像素
            this.setValue(this.target);
        }
    }

    /** 换目标或换参数后，用当前位置和速度重新起解，保证速度连续。 */
    private void resolve() {
        double velocity = this.velocityAt(this.time);
        this.time = 0.0;
        this.solver = solveSpring(this.current, velocity, this.target,
                this.mass, this.damping, this.stiffness);
    }

    private double velocityAt(double t) {
        // 前向差分：解在 t<=0 区间是常数（起点），中心差分在 t≈0 处会取到那个常数而失真
        return (this.solver.at(t + H) - this.solver.at(t)) / H;
    }

    /**
     * 闭式解。阻尼比 ζ = c / (2√(km)) ≥ 1 走临界/过阻尼分支，否则走欠阻尼分支。
     *
     * <p>两个分支都在描述「相对目标的偏移 u(t)」，实际位置 = to - u(t)。
     */
    private static Solver solveSpring(double from, double velocity, double to,
                                      double mass, double damping, double stiffness) {
        double delta = to - from;
        if (damping / (2.0 * Math.sqrt(stiffness * mass)) >= 1.0) {
            // 临界/过阻尼：u(t) = (delta + t·over)·e^(-ωt)
            // 由 u'(0) = -velocity（位置往正方向走时偏移在缩小）得 over = ω·delta - velocity。
            // 代入 v=0 就是 u = delta·(1 + ωt)·e^(-ωt)，恒不过冲。
            double omega = Math.sqrt(stiffness / mass);
            double over = omega * delta - velocity;
            return t -> {
                if (t <= 0.0) {
                    return from;
                }
                return to - (delta + t * over) * Math.exp(-omega * t);
            };
        }
        // 欠阻尼：带阻尼的振荡
        double dampingFrequency = Math.sqrt(4.0 * mass * stiffness - damping * damping);
        double over = (damping * delta - 2.0 * mass * velocity) / dampingFrequency;
        double omegaD = 0.5 * dampingFrequency / mass;
        double decay = -(0.5 * damping) / mass;
        return t -> {
            if (t <= 0.0) {
                return from;
            }
            return to - (Math.cos(t * omegaD) * delta + Math.sin(t * omegaD) * over) * Math.exp(t * decay);
        };
    }
}
