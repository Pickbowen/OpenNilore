package shit.nilore.utils.game;

import lombok.Generated;
import shit.nilore.utils.math.MathUtil;

public final class CpsUtil {
    public static long toDelayMs(String mode, double cps) {
        if (mode.equals("DBC")) {
            return (long)(500.0 / cps + MathUtil.randomDouble(-50.0, 50.0));
        }
        return (long)(1000.0 / cps + MathUtil.randomDouble(-25.0, 25.0));
    }

    public static long toDelayMs(double cps) {
        return (long)(1000.0 / cps);
    }

    public static long toDelayMsWithJitter(double cps, double jitter) {
        double baseDelay = 1000.0 / cps;
        return (long)(baseDelay + MathUtil.randomDouble(-jitter, jitter));
    }

    @Generated
    private CpsUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
