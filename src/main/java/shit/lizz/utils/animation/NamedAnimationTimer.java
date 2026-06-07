package shit.lizz.utils.animation;

import shit.lizz.utils.animation.AnimationBuilder;
import shit.lizz.utils.animation.AnimationTimer;

public class NamedAnimationTimer
extends AnimationTimer {
    private final String name;
    private final AnimationBuilder builder;

    public NamedAnimationTimer(String name, AnimationBuilder builder) {
        this.name = name;
        this.builder = builder;
    }

    @Override
    public void setCurrentValue(double target) {
        if (target == (double)this.getValueF()) {
            return;
        }
        this.setDebug(this.getBuilder().isDebug());
        if (this.isAnimating() && (target == this.getFromValue() || target == this.getToValue() || target == (double)this.getValueF())) {
            if (this.isDebug()) {
                System.out.println("Animating " + this.name + " cancelled due to valueTo equals fromValue");
            }
            return;
        }
        this.setEasing(this.getBuilder().getEasing());
        this.setDuration(this.getBuilder().getDuration() * 1000.0);
        this.setStartTime(System.currentTimeMillis());
        this.setFromValue(this.getValueF());
        this.setToValue(target);
        if (this.isDebug()) {
            double duration = this.getDuration();
            float current = this.getValueF();
            double to = this.getToValue();
            System.out.println(this.name + "#animate {\n    to value: " + to + "\n    from value: " + current + "\n    duration: " + duration + "\n}");
        }
    }

    public AnimationBuilder getBuilder() {
        return this.builder;
    }

    public String getName() {
        return this.name;
    }
}