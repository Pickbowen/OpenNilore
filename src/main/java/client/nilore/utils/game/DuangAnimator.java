package client.nilore.utils.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 *
 *
 * <ol>
 * </ol>
 */
public final class DuangAnimator {

    private static final double MIN_SCALE = 0.35;
    private static final double MAX_SCALE = 1.75;
    private static final double DT = 1.0 / 20.0;
    private static final int SUBSTEPS = 8;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        private double value = 1.0;
        private double velocity;
        private double omega = 9.0;
        private double zeta = 0.9;
    }

    private DuangAnimator() {
    }

    public static void kick(int entityId, double strength) {
        if (strength <= 0.0 || !Double.isFinite(strength)) {
            return;
        }
        State state = STATES.computeIfAbsent(entityId, key -> new State());
        state.velocity -= strength * 6.0;
    }

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

    public static float verticalScale(int entityId, float partialTick) {
        State state = STATES.get(entityId);
        if (state == null) {
            return 1.0f;
        }
        double t = Mth_clamp(partialTick, 0.0f, 1.0f) * DT;
        double offset = solve(state.value - 1.0, state.velocity, t, state.omega, state.zeta);
        return (float) Math.min(MAX_SCALE, Math.max(MIN_SCALE, 1.0 + offset));
    }

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
