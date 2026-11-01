package client.nilore.modules.impl.misc.music;

import java.util.List;

public final class LyricSprings {

    public static final Physics DEFAULT = new Physics(1.0, 18.0, 100.0);
    public static final double CASCADE_STEP = 0.05;
    private static final double LOG_ONE_PERCENT = -Math.log(0.01);

    private LyricSprings() {
    }

    public static Physics responsePhysics(double dampingRatio, double response, double mass) {
        double omega = Math.PI * 2.0 / Math.max(response, 0.001);
        return new Physics(mass, 2.0 * dampingRatio * mass * omega, mass * omega * omega);
    }

    public static Physics gapDrivenLineSpring(double interLineGap) {
        double amount = clamp((interLineGap - 0.2) / 0.55, 0.0, 1.0);
        return responsePhysics(lerp(0.9, 0.78, amount), lerp(0.48, 0.75, amount), 1.0);
    }

    public static Physics lineTransitionSpring(boolean hasTimedSyllables, double interLineGap) {
        return hasTimedSyllables ? gapDrivenLineSpring(interLineGap) : DEFAULT;
    }

    public static Physics criticalEnvelopeSpring(double mass, double response) {
        double rate = LOG_ONE_PERCENT / Math.max(response, 0.01);
        return new Physics(mass, 2.0 * mass * rate, mass * rate * rate);
    }

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

    public static boolean isLargeSeek(int indexDelta, double timeDelta) {
        return Math.abs(indexDelta) > 3 || Math.abs(timeDelta) > 2.0;
    }

    public static boolean isHardClockDiscontinuity(double oldTime, double newTime) {
        return Math.abs(newTime - oldTime) >= 1.5
                || oldTime - newTime > 1.0
                || oldTime > 5.0 && newTime < oldTime * 0.1
                || Math.abs(newTime - oldTime) > 2.0;
    }

    public static Physics seekSpring(int indexDelta, double timeDelta) {
        return isLargeSeek(indexDelta, timeDelta)
                ? DEFAULT
                : criticalEnvelopeSpring(2.0, 0.1);
    }

    public static double cascadeDelay(int validDistance, boolean disabled) {
        return disabled ? 0.0 : Math.max(validDistance - 1, 0) * CASCADE_STEP;
    }

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

    public record Physics(double mass, double damping, double stiffness) {
    }
}
