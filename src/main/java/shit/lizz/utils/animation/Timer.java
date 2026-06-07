package shit.lizz.utils.animation;

import net.minecraft.util.Mth;

public class Timer {
    private long time = System.currentTimeMillis();

    public void reset() {
        this.time = System.currentTimeMillis();
    }

    public void setTime(long time) {
        this.time = time;
    }

    public void setTimeMs(long time) {
        this.time = time;
    }

    public boolean hasPassedReset(long durationMs, boolean reset) {
        if (System.currentTimeMillis() - this.time > durationMs) {
            if (reset) {
                this.reset();
            }
            return true;
        }
        return false;
    }

    public boolean hasPassed(float durationMs) {
        return (float)(System.currentTimeMillis() - this.time) >= durationMs;
    }

    public boolean hasPassedDouble(double durationMs, boolean reset) {
        boolean passed = (double)Mth.clamp((float)(System.currentTimeMillis() - this.time), 0.0f, (float)durationMs) >= durationMs;
        if (passed && reset) {
            this.reset();
        }
        return passed;
    }

    public long getElapsed() {
        return System.currentTimeMillis() - this.time;
    }

    public boolean hasPassed(long durationMs) {
        return System.currentTimeMillis() - this.time > durationMs;
    }

    public boolean hasPassedOrEqual(long durationMs) {
        return System.currentTimeMillis() - this.time >= durationMs;
    }
}