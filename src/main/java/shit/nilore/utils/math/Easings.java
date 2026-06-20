package shit.nilore.utils.math;

import shit.nilore.utils.math.Easing;

public class Easings {
    public static final Easing BACK_OUT = t -> 1.0 + 2.70158 * Math.pow(t - 1.0, 3.0) + 1.70158 * Math.pow(t - 1.0, 2.0);
    public static final Easing EASE_OUT_QUAD = t -> 1.0 - (t - 1.0) * (t - 1.0);
    public static final Easing EASE_OUT_POW2 = Easings.easeOut(2);
    public static final Easing EASE_IN_POW3 = Easings.easeIn(3);
    public static final Easing EASE_OUT_POW3 = Easings.easeOut(3);
    public static final Easing EASE_OUT_POW4 = Easings.easeOut(4);
    public static final Easing EASE_OUT_POW5 = Easings.easeOut(5);
    public static final Easing EASE_OUT_SINE = t -> Math.sin(t * Math.PI / 2.0);
    public static final Easing EASE_OUT_ELASTIC = t -> {
        if (t == 0.0 || t == 1.0) {
            return t;
        }
        return Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * 2.0943951023931953) + 1.0;
    };
    public static final Easing EASE_OUT_BOUNCE = t -> {
        double n1 = 7.5625;
        double d1 = 2.75;
        if (t < 1.0 / d1) {
            return n1 * Math.pow(t, 2.0);
        }
        if (t < 2.0 / d1) {
            return n1 * Math.pow(t - 1.5 / d1, 2.0) + 0.75;
        }
        if (t < 2.5 / d1) {
            return n1 * Math.pow(t - 2.25 / d1, 2.0) + 0.9375;
        }
        return n1 * Math.pow(t - 2.625 / d1, 2.0) + 0.984375;
    };

    private Easings() {
    }

    public static Easing easeIn(double power) {
        return t -> Math.pow(t, power);
    }

    public static Easing easeIn(int power) {
        return Easings.easeIn((double)power);
    }

    public static Easing easeOut(double power) {
        return t -> 1.0 - Math.pow(1.0 - t, power);
    }

    public static Easing easeOut(int power) {
        return Easings.easeOut((double)power);
    }

    public static Easing easeInOut(double power) {
        return t -> {
            if (t < 0.5) {
                return Math.pow(2.0, power - 1.0) * Math.pow(t, power);
            }
            return 1.0 - Math.pow(-2.0 * t + 2.0, power) / 2.0;
        };
    }

    private static /* synthetic */ double easeInOutBounce(double t) {
        if (t < 0.5) {
            return (1.0 - EASE_OUT_BOUNCE.ease(1.0 - 2.0 * t)) / 2.0;
        }
        return (1.0 + EASE_OUT_BOUNCE.ease(2.0 * t - 1.0)) / 2.0;
    }

    private static /* synthetic */ double easeInBounce(double t) {
        return 1.0 - EASE_OUT_BOUNCE.ease(1.0 - t);
    }

    private static /* synthetic */ double easeInOutExpo(double t) {
        if (t == 0.0 || t == 1.0) {
            return t;
        }
        if (t < 0.5) {
            return Math.pow(2.0, 20.0 * t - 10.0) / 2.0;
        }
        return (2.0 - Math.pow(2.0, -20.0 * t + 10.0)) / 2.0;
    }

    private static /* synthetic */ double easeOutExpo(double t) {
        if (t != 1.0) {
            return 1.0 - Math.pow(2.0, -10.0 * t);
        }
        return t;
    }

    private static /* synthetic */ double easeInExpo(double t) {
        if (t != 0.0) {
            return Math.pow(2.0, 10.0 * t - 10.0);
        }
        return t;
    }

    private static /* synthetic */ double easeInOutElastic(double t) {
        if (t == 0.0 || t == 1.0) {
            return t;
        }
        if (t < 0.5) {
            return -(Math.pow(2.0, 20.0 * t - 10.0) * Math.sin((20.0 * t - 11.125) * 1.3962634015954636)) / 2.0;
        }
        return Math.pow(2.0, -20.0 * t + 10.0) * Math.sin((20.0 * t - 11.125) * 1.3962634015954636) / 2.0 + 1.0;
    }

    private static /* synthetic */ double easeInElastic(double t) {
        if (t == 0.0 || t == 1.0) {
            return t;
        }
        return Math.pow(-2.0, 10.0 * t - 10.0) * Math.sin((t * 10.0 - 10.75) * 2.0943951023931953);
    }

    private static /* synthetic */ double easeInOutCirc(double t) {
        if (t < 0.5) {
            return (1.0 - Math.sqrt(1.0 - Math.pow(2.0 * t, 2.0))) / 2.0;
        }
        return (Math.sqrt(1.0 - Math.pow(-2.0 * t + 2.0, 2.0)) + 1.0) / 2.0;
    }

    private static /* synthetic */ double easeOutCirc(double t) {
        return Math.sqrt(1.0 - Math.pow(t - 1.0, 2.0));
    }

    private static /* synthetic */ double easeInCirc(double t) {
        return 1.0 - Math.sqrt(1.0 - Math.pow(t, 2.0));
    }

    private static /* synthetic */ double easeInOutSine(double t) {
        return -(Math.cos(Math.PI * t) - 1.0) / 2.0;
    }

    private static /* synthetic */ double easeInSine(double t) {
        return 1.0 - Math.cos(t * Math.PI / 2.0);
    }

    private static /* synthetic */ double easeInBack(double t) {
        return 2.70158 * Math.pow(t, 3.0) - 1.70158 * Math.pow(t, 2.0);
    }

    private static /* synthetic */ double easeInOutBack(double t) {
        if (t < 0.5) {
            return Math.pow(2.0 * t, 2.0) * (7.189819 * t - 2.5949095) / 2.0;
        }
        return (Math.pow(2.0 * t - 2.0, 2.0) * (3.5949095 * (t * 2.0 - 2.0) + 2.5949095) + 2.0) / 2.0;
    }
}
