package shit.lizz.utils.animation;

import java.util.ArrayList;
import java.util.List;

public class TickTimer {
    private static final List<TickTimer> instances = new ArrayList<>();
    private int ticks = 0;

    public static void tickAll() {
        for (TickTimer tickTimer : instances) {
            ++tickTimer.ticks;
        }
    }

    public TickTimer() {
        instances.add(this);
    }

    public boolean hasPassed(int requiredTicks) {
        return this.ticks >= requiredTicks;
    }

    public boolean hasPassed(float requiredTicks) {
        return (float)this.ticks >= requiredTicks;
    }

    public void reset() {
        this.ticks = 0;
    }
}