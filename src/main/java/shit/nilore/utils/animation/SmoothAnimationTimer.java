package shit.nilore.utils.animation;

import shit.nilore.utils.animation.AnimationTimer;
import shit.nilore.utils.math.Easing;
import shit.nilore.utils.math.Easings;

public class SmoothAnimationTimer
extends AnimationTimer {
    public void animate(double target, double duration) {
        this.animate(target, duration, Easings.EASE_OUT_QUAD);
    }

    public void animate(double target, double duration, Easing easing) {
        this.animate(target, duration, easing, false);
    }

    public void animate(double target, double duration, Easing easing, boolean debug) {
        this.setDebug(debug);
        if (this.isAnimating() && (target == this.getFromValue() || target == this.getToValue() || target == (double)this.getValueF())) {
            if (this.isDebug()) {
                System.out.println("Animate cancelled due to valueTo equals fromValue");
            }
            return;
        }
        this.setEasing(easing);
        this.setDuration(duration * 1000.0);
        this.setStartTime(System.currentTimeMillis());
        this.setFromValue(this.getValueF());
        this.setToValue(target);
        if (this.isDebug()) {
            double durationMs = this.getDuration();
            float current = this.getValueF();
            double to = this.getToValue();
            System.out.println("#animate {\n    to value: " + to + "\n    from value: " + current + "\n    duration: " + durationMs + "\n}");
        }
    }
}