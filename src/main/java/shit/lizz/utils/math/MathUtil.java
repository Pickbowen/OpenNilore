package shit.lizz.utils.math;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.Random;
import lombok.Generated;
import shit.lizz.ClientBase;

public final class MathUtil
extends ClientBase {
    public static final Random RANDOM;
    private static final String UTILITY_MSG;

    public static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }

    public static double round(double value, int decimals) {
        if (decimals == 0) {
            return Math.floor(value);
        }
        double factor = Math.pow(10.0, decimals);
        return (double)Math.round(value * factor) / factor;
    }

    public static double randomInt(int min, int max) {
        return min >= max ? (double)min : (double)(RANDOM.nextInt() * (max - min) + min);
    }

    public static double randomDouble(double min, double max) {
        return min >= max ? min : RANDOM.nextDouble() * (max - min) + min;
    }

    public static double snap(double value, double step) {
        double snapped = (double)Math.round(value / step) * step;
        snapped *= 1000.0;
        snapped = (int)snapped;
        return snapped /= 1000.0;
    }

    public static double roundDecimal(double value, int decimals) {
        if (decimals < 0) {
            return value;
        }
        BigDecimal bigDecimal = new BigDecimal(value);
        bigDecimal = bigDecimal.setScale(decimals, RoundingMode.HALF_UP);
        return bigDecimal.doubleValue();
    }

    public static float randomFloat(float max, float min) {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom.nextFloat() * (max - min) + min;
    }

    public static float clampPitch(float pitch) {
        if (pitch > 90.0f) {
            return 90.0f;
        }
        if (pitch < -90.0f) {
            return -90.0f;
        }
        return pitch;
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static float lerp(float progress, float start, float end) {
        return start + progress * (end - start);
    }

    public static double lerp(double progress, double start, float end) {
        return start + progress * ((double)end - start);
    }

    public static double lerp(float progress, double start, double end) {
        return start + (double)progress * (end - start);
    }

    public static int lerpColor(int colorA, int colorB, float progress) {
        progress = Math.max(0.0f, Math.min(1.0f, progress));
        int alphaA = colorA >> 24 & 0xFF;
        int redA = colorA >> 16 & 0xFF;
        int greenA = colorA >> 8 & 0xFF;
        int blueA = colorA & 0xFF;
        int alphaB = colorB >> 24 & 0xFF;
        int redB = colorB >> 16 & 0xFF;
        int greenB = colorB >> 8 & 0xFF;
        int blueB = colorB & 0xFF;
        int alpha = (int)((float)alphaA * (1.0f - progress) + (float)alphaB * progress);
        int red = (int)((float)redA * (1.0f - progress) + (float)redB * progress);
        int green = (int)((float)greenA * (1.0f - progress) + (float)greenB * progress);
        int blue = (int)((float)blueA * (1.0f - progress) + (float)blueB * progress);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    @Generated
    private MathUtil() {
        throw new UnsupportedOperationException(UTILITY_MSG);
    }

    static {
        UTILITY_MSG = "This is a utility class and cannot be instantiated";
        RANDOM = new Random();
    }
}
