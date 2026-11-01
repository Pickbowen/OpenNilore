package client.nilore.utils.animation;

public final class SpringSolver {

    private static final double H = 0.001;
    private static final double MAX_STEP = 0.05;

    private interface Solver {
        double at(double t);
    }

    private double mass;
    private double damping;
    private double stiffness;

    private double current;
    private double target;
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

    public boolean arrived() {
        return Math.abs(this.target - this.current) < 0.01
                && Math.abs(velocityAt(this.time)) < 0.01
                && !this.hasQueuedTarget
                && !this.hasQueuedParams;
    }

    public void setParams(double mass, double damping, double stiffness) {
        this.setParams(mass, damping, stiffness, 0.0);
    }

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
            this.setValue(this.target);
        }
    }

    private void resolve() {
        double velocity = this.velocityAt(this.time);
        this.time = 0.0;
        this.solver = solveSpring(this.current, velocity, this.target,
                this.mass, this.damping, this.stiffness);
    }

    private double velocityAt(double t) {
        return (this.solver.at(t + H) - this.solver.at(t)) / H;
    }

    private static Solver solveSpring(double from, double velocity, double to,
                                      double mass, double damping, double stiffness) {
        double delta = to - from;
        if (damping / (2.0 * Math.sqrt(stiffness * mass)) >= 1.0) {
            double omega = Math.sqrt(stiffness / mass);
            double over = omega * delta - velocity;
            return t -> {
                if (t <= 0.0) {
                    return from;
                }
                return to - (delta + t * over) * Math.exp(-omega * t);
            };
        }
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
