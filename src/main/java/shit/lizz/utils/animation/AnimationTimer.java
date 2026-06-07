package shit.lizz.utils.animation;

import lombok.Setter;
import shit.lizz.utils.math.Easing;

public abstract class AnimationTimer {
    @Setter
    private double currentValue;
    @Setter
    private long startTime;
    @Setter
    private double duration;
    @Setter
    private double fromValue;
    @Setter
    private double toValue;
    @Setter
    private Easing easing;
    @Setter
    private boolean debug;

    public boolean tick() {
        boolean animating = this.isAnimating();
        if (animating) {
            this.currentValue = this.lerp(this.getFromValue(), this.getToValue(), this.getEasing().ease(this.getProgress()));
        } else {
            this.setStartTime(0L);
            this.currentValue = this.getToValue();
        }
        return animating;
    }

    public boolean isAnimating() {
        return !this.isDone();
    }

    public boolean isDone() {
        return this.getProgress() >= 1.0;
    }

    public double getProgress() {
        return (double)(System.currentTimeMillis() - this.getStartTime()) / this.getDuration();
    }

    public double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    public float getValueF() {
        return (float)this.currentValue;
    }

    public int getValueI() {
        return (int)this.currentValue;
    }

    public long getStartTime() {
        return this.startTime;
    }

    public double getDuration() {
        return this.duration;
    }

    public double getFromValue() {
        return this.fromValue;
    }

    public double getToValue() {
        return this.toValue;
    }

    public Easing getEasing() {
        return this.easing;
    }

    public boolean isDebug() {
        return this.debug;
    }

    }