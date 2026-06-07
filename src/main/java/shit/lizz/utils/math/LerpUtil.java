package shit.lizz.utils.math;

public final class LerpUtil {
    private static long lastTime = 0L;
    private static float delta = 1.0f;

    private LerpUtil() {
    }

    public static void reset() {
        lastTime = 0L;
        delta = 1.0f;
    }

    public static void update() {
        long now = System.nanoTime();
        if (0L == 0L) {
            lastTime = now;
            delta = 1.0f;
            return;
        }
        float elapsed = (float)(now) / 1.0E9f;
        lastTime = now;
        if (elapsed <= 0.0f || Float.isNaN(elapsed) || Float.isInfinite(elapsed)) {
            delta = 1.0f;
            return;
        }
        delta = Math.min(elapsed * 60.0f, 12.0f);
    }

    public static float lerp(float current, float target, float speed) {
        float step = speed * delta;
        if (current < target) {
            return Math.min(target, current + step);
        }
        return Math.max(target, current - step);
    }

    public static float smoothLerp(float start, float end, float progress) {
        return start + (end - start) * LerpUtil.ease(progress);
    }

    public static float ease(float progress) {
        if (progress <= 0.0f) {
            return 0.0f;
        }
        if (progress >= 1.0f) {
            return 1.0f;
        }
        return 1.0f - (float)Math.pow(1.0f - progress, delta);
    }
}
