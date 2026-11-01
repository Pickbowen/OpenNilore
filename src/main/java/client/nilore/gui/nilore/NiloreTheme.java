package client.nilore.gui.nilore;

/**
 * Design tokens of the Nilore click GUI: colour, radius, spacing, type, motion and effects.
 *
 * <p>Every value the painter uses is resolved here; drawing code references tokens only. Light and
 * dark palettes follow the macOS surface hierarchy, and the window chrome and traffic lights share
 * the same two palettes so a live theme switch needs no rebuild.
 */
public final class NiloreTheme {
    /** CSS font sizes, mapped to the size argument the rasteriser expects (about half a point). */
    public static final class Type {
        public static final float SCALE = 1.3f;
        public static final float CAPTION = size(11), SMALL = size(12), BODY = size(13), HEADLINE = size(15),
                TITLE = size(17), DOC_TITLE = size(22);

        private Type() {
        }

        private static float size(float css) {
            return Math.round(2 * css * SCALE);
        }
    }

    public static final class Radius {
        public static final float CONTROL = 6, ROW = 6, PANEL = 10, WINDOW = 12;

        private Radius() {
        }
    }

    public static final class Metrics {
        public static final float GRID = 8, CONTROL_H = 28, BUTTON_H = 32;
        public static final float TITLEBAR_H = 36;
        public static final float TRAFFIC_DOT = 13, TRAFFIC_GAP = 8, TRAFFIC_PAD = 14;

        private Metrics() {
        }
    }

    /**
     * Motion tokens. Durations follow the 150-250ms range; the springs are critically damped, so a
     * duration maps to a rate through the 5-tau settling constant. Any value in between stays a
     * pure exponential decay, which reads the same as cubic-bezier(0.4, 0, 0.2, 1) at these
     * durations.
     */
    public static final class Motion {
        public static final float FAST = .15f, NORMAL = .2f, SLOW = .25f;
        public static final double FAST_RATE = rate(FAST), NORMAL_RATE = rate(NORMAL), SLOW_RATE = rate(SLOW);

        private Motion() {
        }

        private static double rate(float seconds) {
            return 5.0 / seconds;
        }
    }

    public static final class Effects {
        public static final float BLUR = 20, SATURATE = 1.8f;
        public static final float SHADOW_BLUR = 30, SHADOW_OFFSET = 10;
        public static final int KNOB_SHADOW = 0x1F000000;

        private Effects() {
        }
    }

    public static final class Palette {
        public int window, chrome, sidebar, list, detail, card, popover, field;
        public int text, secondary, tertiary, titleFocused, titleIdle;
        public int accent, accentSoft, blue, green, red, orange;
        public int separator, hairline;
        public int rowHover, rowSelected, navHover, segmentActive, switchOff, switchOn, switchKnob, selectEditing;
        public int close, minimize, maximize, controlIdle, controlGlyph;
        public int shadow;

        private Palette() {
        }
    }

    public static final Palette LIGHT = light();
    public static final Palette DARK = dark();

    private NiloreTheme() {
    }

    public static Palette of(boolean dark) {
        return dark ? DARK : LIGHT;
    }

    private static Palette light() {
        Palette p = new Palette();
        p.window = 0xEDFFFFFF;
        p.chrome = 0x08FFFFFF;
        p.sidebar = 0x8CE8E8ED;
        p.list = 0x47FFFFFF;
        p.detail = 0xE3F5F5F7;
        p.card = 0xFFFFFFFF;
        p.popover = 0xFFFFFFFF;
        p.field = 0x14767680;
        p.text = 0xFF1D1D1F;
        p.secondary = 0xFF6E6E73;
        p.tertiary = 0xFFAEAEB2;
        p.titleFocused = 0xFF1D1D1F;
        p.titleIdle = 0xFF8E8E93;
        p.accent = 0xFF007AFF;
        p.accentSoft = 0x1F007AFF;
        p.blue = 0xFF007AFF;
        p.green = 0xFF34C759;
        p.red = 0xFFFF3B30;
        p.orange = 0xFFFF9F0A;
        p.separator = 0x1A000000;
        p.hairline = 0x33000000;
        p.rowHover = 0x0F787880;
        p.rowSelected = 0x1F007AFF;
        p.navHover = 0x1A787880;
        p.segmentActive = 0xFFFFFFFF;
        p.switchOff = 0x22787880;
        p.switchOn = 0xFF30D158;
        p.switchKnob = 0xFFFFFFFF;
        p.selectEditing = 0x2E767680;
        p.close = 0xFFFF5F57;
        p.minimize = 0xFFFEBC2E;
        p.maximize = 0xFF28C840;
        p.controlIdle = 0xFFD6D6D8;
        p.controlGlyph = 0x73000000;
        p.shadow = 0x26000000;
        return p;
    }

    private static Palette dark() {
        Palette p = new Palette();
        p.window = 0xF01E1E1E;
        p.chrome = 0x0FFFFFFF;
        p.sidebar = 0x8C1A1A1C;
        p.list = 0x2EFFFFFF;
        p.detail = 0xE8252527;
        p.card = 0xFF2C2C2E;
        p.popover = 0xFF2C2C2E;
        p.field = 0x3D767680;
        p.text = 0xFFF5F5F7;
        p.secondary = 0xFF98989D;
        p.tertiary = 0xFF636366;
        p.titleFocused = 0xFFF5F5F7;
        p.titleIdle = 0xFF6E6E73;
        p.accent = 0xFF0A84FF;
        p.accentSoft = 0x1F0A84FF;
        p.blue = 0xFF0A84FF;
        p.green = 0xFF30D158;
        p.red = 0xFFFF453A;
        p.orange = 0xFFFF9F0A;
        p.separator = 0x1FFFFFFF;
        p.hairline = 0x33FFFFFF;
        p.rowHover = 0x14FFFFFF;
        p.rowSelected = 0x1F0A84FF;
        p.navHover = 0x1AFFFFFF;
        p.segmentActive = 0xFF636366;
        p.switchOff = 0x28FFFFFF;
        p.switchOn = 0xFF30D158;
        p.switchKnob = 0xFFFFFFFF;
        p.selectEditing = 0x3DFFFFFF;
        p.close = 0xFFFF5F57;
        p.minimize = 0xFFFEBC2E;
        p.maximize = 0xFF28C840;
        p.controlIdle = 0xFF4A4A4C;
        p.controlGlyph = 0x99000000;
        p.shadow = 0x66000000;
        return p;
    }
}
