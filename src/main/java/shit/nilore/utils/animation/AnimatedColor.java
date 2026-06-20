package shit.nilore.utils.animation;

import lombok.Getter;
import lombok.Setter;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easing;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.render.ColorUtil;

public class AnimatedColor {
    @Getter @Setter
    private int color;
    @Getter @Setter
    private SmoothAnimationTimer rTimer = new SmoothAnimationTimer();
    @Getter @Setter
    private SmoothAnimationTimer gTimer = new SmoothAnimationTimer();
    @Getter @Setter
    private SmoothAnimationTimer bTimer = new SmoothAnimationTimer();
    @Getter @Setter
    private SmoothAnimationTimer aTimer = new SmoothAnimationTimer();

    public AnimatedColor(int color) {
        this.color = color;
    }

    public void animateTo(int targetColor, float speed) {
        this.animateTo(targetColor, speed, Easings.EASE_OUT_QUAD);
    }

    public void animateTo(int targetColor, float speed, Easing easing) {
        this.rTimer.animate(ColorUtil.getRed(targetColor), 0.2 / (double)speed, easing);
        this.gTimer.animate(ColorUtil.getGreen(targetColor), 0.2 / (double)speed, easing);
        this.bTimer.animate(ColorUtil.getBlue(targetColor), 0.2 / (double)speed, easing);
        this.aTimer.animate(ColorUtil.getAlpha(targetColor), 0.2 / (double)speed, easing);
        this.rTimer.tick();
        this.gTimer.tick();
        this.bTimer.tick();
        this.aTimer.tick();
        this.color = ColorUtil.fromARGB(this.rTimer.getValueI(), this.gTimer.getValueI(), this.bTimer.getValueI(), this.aTimer.getValueI());
    }

    }