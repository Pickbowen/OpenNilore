package shit.nilore.utils.animation;

import java.util.LinkedList;
import java.util.Optional;
import shit.nilore.utils.animation.AnimationTimer;
import shit.nilore.utils.animation.NamedAnimationTimer;
import shit.nilore.utils.math.Easing;
import shit.nilore.utils.math.Easings;

public class AnimationBuilder {
    private final LinkedList<NamedAnimationTimer> timers = new LinkedList<>();
    private double duration = 1.0;
    private Easing easing = Easings.EASE_OUT_QUAD;
    private boolean debug = false;

    public NamedAnimationTimer getOrCreate(String name) {
        NamedAnimationTimer timer;
        Optional<NamedAnimationTimer> existing = this.timers.stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst();
        if (!existing.isPresent()) {
            timer = new NamedAnimationTimer(name, this);
            this.timers.add(timer);
        } else {
            timer = existing.get();
        }
        return timer;
    }

    public AnimationBuilder animate(String name, double target, double duration) {
        return this.animate(name, target, duration, Easings.EASE_OUT_QUAD);
    }

    public AnimationBuilder animate(String name, double target, double duration, Easing easing) {
        return this.animate(name, target, duration, easing, false);
    }

    private AnimationBuilder animate(String name, double target, double duration, Easing easing, boolean debug) {
        this.setDuration(duration);
        this.setEasing(easing);
        this.setDebug(debug);
        this.getOrCreate(name).setCurrentValue(target);
        return this;
    }

    public AnimationBuilder withTask(double duration, Runnable runnable) {
        return this.withTask(duration, this.easing, runnable);
    }

    public AnimationBuilder withTask(double duration, Easing easing, Runnable runnable) {
        return this.withTask(duration, easing, false, runnable);
    }

    private AnimationBuilder withTask(double duration, Easing easing, boolean debug, Runnable runnable) {
        this.setDuration(duration);
        this.setEasing(easing);
        this.setDebug(debug);
        runnable.run();
        return this;
    }

    public boolean tick() {
        this.getTimers().forEach(AnimationTimer::tick);
        return this.getTimers().stream().anyMatch(AnimationTimer::isAnimating);
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public void setEasing(Easing easing) {
        this.easing = easing;
    }

    public double getDuration() {
        return this.duration;
    }

    public LinkedList<NamedAnimationTimer> getTimers() {
        return this.timers;
    }

    public Easing getEasing() {
        return this.easing;
    }

    public boolean isDebug() {
        return this.debug;
    }
}