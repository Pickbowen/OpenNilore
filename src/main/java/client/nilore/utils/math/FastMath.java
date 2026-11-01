package client.nilore.utils.math;

public final class FastMath {
    private static final int BITS = 12;
    private static final int SIZE = 1 << BITS;
    private static final int MASK = SIZE - 1;

    private static final float[] SIN = new float[SIZE];

    private static final float RAD_TO_INDEX = SIZE / (float) (Math.PI * 2.0);
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private static volatile boolean enabled;

    static {
        for (int i = 0; i < SIZE; i++) {
            SIN[i] = (float) Math.sin(i * (Math.PI * 2.0 / SIZE));
        }
    }

    private FastMath() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static float sin(float radians) {
        if (!enabled) {
            return (float) Math.sin(radians);
        }
        return table(radians);
    }

    public static float cos(float radians) {
        if (!enabled) {
            return (float) Math.cos(radians);
        }
        return table(radians + HALF_PI);
    }

    public static float sinDeg(float degrees) {
        return sin(degrees * DEG_TO_RAD);
    }

    public static float cosDeg(float degrees) {
        return cos(degrees * DEG_TO_RAD);
    }

    private static float table(float radians) {
        float wrapped = radians % TWO_PI;
        if (wrapped < 0.0f) {
            wrapped += TWO_PI;
        }
        float x = wrapped * RAD_TO_INDEX;
        int i = (int) x;
        if (i >= SIZE) {
            i = SIZE - 1;
        }
        int j = (i + 1) & MASK;
        float a = SIN[i];
        return a + (SIN[j] - a) * (x - i);
    }
}
