package client.nilore.gui.nilore;

/**
 * Geometry of the Nilore click GUI.
 *
 * <p>Every length below is a literal from the reference stylesheet, so the ported window keeps the
 * exact same proportions, offsets and spacings. The whole design is laid out in a fixed
 * 1360x860 space and scaled uniformly to the screen afterwards.
 */
public final class NiloreLayout {
    public static final float WIDTH = 1360, HEIGHT = 860, RADIUS = 22;
    public static final float SIDEBAR_W = 220, LIST_W = 780, DETAIL_W = 360;
    public static final float LIST_X = SIDEBAR_W;
    public static final float DETAIL_X = SIDEBAR_W + LIST_W;

    public static final Rect WINDOW = new Rect(0, 0, WIDTH, HEIGHT);

/**
 * Window scale relative to the fitted size. Glyphs share the canvas transform, so shrinking the panel
 * would shrink the type with it; {@link #size(float)} divides by this factor to hand the loss back,
 * which keeps every glyph the same size on screen while the panel itself gets smaller.
 */
public static final float WINDOW_SCALE = .7f;

/**
 * Per-size type scale. Both ends of the ramp were measured on the same screenshot: an 18px label has
 * to land on 30px (1.667x) while a 58px heading only reaches 70px (1.207x). Sizes in between are
 * interpolated so the ramp stays smooth instead of stepping.
 */
public static float typeScale(float css) {
    float small = 1.667f, large = 1.207f;
    if (css <= 13f) {
        return small;
    }
    if (css >= 26f) {
        return large;
    }
    return large + (small - large) * (26f - css) / 13f;
}

    /**
     * Calibrated so a label matches the Neverlose settings panel exactly: that panel is 600 GUI px
     * wide with an 11.5 GUI px label, a ratio of 1.92%. This design is 1360 wide, so the nominal
     * size below is pulled back to reproduce the same proportion.
     */
    private static final float WIDTH_COMPENSATION = .8f;
    /** Breathing room added to line boxes that hold nothing but text. */
    private static final float LINE_PAD = 2f;
    /** Tallest cap and deepest descender across the three weights, measured from the outlines. */
    public static final float FONT_CAP = .702f, FONT_DESC = .275f;

    /**
     * Type sizes, held here rather than in the renderer because the row heights below are derived
     * from them. Each is the reference's CSS pixel size doubled - the {@code FontRenderer} size
     * argument renders at about half its value - then scaled, divided back out of
     * {@link #WINDOW_SCALE} and rounded to a whole point, since AWT can only grid-fit a glyph at an
     * integral size and a fractional one silently loses hinting, which is what makes text look rough.
     */
    public static final float SZ_SMALL = size(11), SZ_COUNT = size(12), SZ_BODY = size(13),
            SZ_LABEL = size(14), SZ_BRAND = size(14), SZ_NAV = size(14), SZ_TITLE = size(15),
            SZ_DOC_TITLE = size(26);

    private static float size(float css) {
        return Math.round(2 * css * typeScale(css) * WIDTH_COMPENSATION / WINDOW_SCALE);
    }

    /** Cap height of one line, which is what {@code text()} centres on. */
    public static float lineCap(float sizeArgument) {
        return sizeArgument / 2f * FONT_CAP;
    }

    /** Cap-top to descender-bottom for one line, the space a label actually needs. */
    public static float lineInk(float sizeArgument) {
        return sizeArgument / 2f * (FONT_CAP + FONT_DESC);
    }

    // sidebar
    public static final float SIDE_PAD_X = 10, SIDE_PAD_Y = 14, SIDE_GAP = 18;
    public static final float BRAND_PAD_X = 8, BRAND_PAD_TOP = 6, BRAND_PAD_BOTTOM = 2;
    public static final float BRAND_ROW = 34;
    public static final float BRAND_H = BRAND_PAD_TOP + BRAND_ROW + BRAND_PAD_BOTTOM;
    /** The brand is a single word, centred in the row the reference used for its icon and subtitle. */
    public static final float BRAND_TEXT_X = SIDE_PAD_X + BRAND_PAD_X;
    public static final float BRAND_CENTER_Y = SIDE_PAD_Y + BRAND_PAD_TOP + BRAND_ROW / 2f;
    public static final float NAV_TOP = SIDE_PAD_Y + BRAND_H + SIDE_GAP;
    public static final float NAV_LABEL_LINE = Math.max(13.2f, lineInk(SZ_SMALL) + LINE_PAD),
            NAV_LABEL_H = NAV_LABEL_LINE + 6;
    public static final float NAV_LABEL_CENTER = NAV_TOP + NAV_LABEL_LINE / 2f;
    public static final float NAV_LABEL_PAD_X = 10;
    public static final float NAV_START = NAV_TOP + NAV_LABEL_H;
    public static final float NAV_H = 34, NAV_RADIUS = 9, NAV_PAD = 9, NAV_ICON = 22, NAV_ICON_GAP = 9;

    // list pane
    public static final float SEARCH_PAD_TOP = 14, SEARCH_PAD_X = 20, SEARCH_PAD_BOTTOM = 10, SEARCH_H = 36;
    public static final float SEARCH_X = LIST_X + SEARCH_PAD_X;
    public static final float SEARCH_W = LIST_W - SEARCH_PAD_X * 2;
    public static final float SEARCH_INNER_PAD = 12, SEARCH_ICON = 16, SEARCH_ICON_GAP = 7;

    public static final float LIST_TOP = SEARCH_PAD_TOP + SEARCH_H + SEARCH_PAD_BOTTOM;
    public static final float LIST_PAD_X = 8, LIST_PAD_TOP = 4, LIST_PAD_BOTTOM = 18;
    public static final float LIST_CONTENT_X = LIST_X + LIST_PAD_X;
    public static final float LIST_CONTENT_W = LIST_W - LIST_PAD_X * 2;
    public static final float GROUP_TITLE_H = 30, GROUP_TITLE_PAD_X = 12;
    public static final float NOTE_PAD_Y = 13, NOTE_SUB_GAP = 6;
    public static final float NOTE_TITLE_INK = lineInk(SZ_TITLE), NOTE_SUB_INK = lineInk(SZ_BODY);
    public static final float NOTE_TEXT_H = NOTE_TITLE_INK + NOTE_SUB_GAP + NOTE_SUB_INK;
    public static final float NOTE_H = Math.max(65, NOTE_PAD_Y * 2 + NOTE_TEXT_H);
    public static final float NOTE_RADIUS = 10, NOTE_PAD_X = 12, NOTE_GAP = 12;
    public static final float SWITCH_W = 51, SWITCH_H = 31, SWITCH_KNOB = 27, SWITCH_PAD = 2;

    public static final Rect LIST_VIEWPORT = new Rect(LIST_X, LIST_TOP, LIST_W, HEIGHT - LIST_TOP);

    // detail pane
    public static final float DETAIL_PAD_TOP = 16;
    public static final float DOC_PAD_X = 14, DOC_PAD_TOP = 6, DOC_PAD_BOTTOM = 16;
    public static final float DOC_SUB_GAP = 6;
    public static final float DOC_TITLE_INK = lineInk(SZ_DOC_TITLE), DOC_SUB_INK = lineInk(SZ_BODY);
    public static final float DOC_TEXT_H = DOC_TITLE_INK + DOC_SUB_GAP + DOC_SUB_INK;
    public static final float DOC_H =
            Math.max(75, DOC_PAD_TOP + DOC_TEXT_H + DOC_PAD_BOTTOM);
    public static final float GROUP_MARGIN_X = 14, GROUP_MARGIN_BOTTOM = 20;
    public static final float GROUP_X = DETAIL_X + GROUP_MARGIN_X;
    public static final float GROUP_W = DETAIL_W - GROUP_MARGIN_X * 2;
    public static final float GROUP_TITLE_LINE = Math.max(14, lineInk(SZ_SMALL) + LINE_PAD), GROUP_TITLE_GAP = 8;
    public static final float GROUP_RADIUS = 14;

    public static final float ROW_PAD_X = 14, ROW_PAD_Y = 11, ROW_MIN_H = 48;
    public static final float COL_PAD_TOP = 12, COL_PAD_BOTTOM = 14, COL_BLOCK_GAP = 10;
    public static final float ROW_LABEL_H = lineInk(SZ_LABEL);
    public static final float ROW_SWITCH_H =
            Math.max(ROW_PAD_Y * 2 + SWITCH_H, Math.max(ROW_MIN_H, ROW_LABEL_H + ROW_PAD_Y * 2));
    public static final float ROW_SELECT_H =
            Math.max(ROW_PAD_Y * 2 + 30, Math.max(ROW_MIN_H, ROW_LABEL_H + ROW_PAD_Y * 2));
    public static final float SELECT_H = 30, SELECT_RADIUS = 8, SELECT_MIN_W = 132, SELECT_MAX_W = 176;
    public static final float SELECT_PAD_LEFT = 10, SELECT_PAD_RIGHT = 29, SELECT_CHEVRON = 12;
    public static final float SLIDER_H = 28, SLIDER_TRACK = 4, SLIDER_KNOB = 20;
    public static final float ROW_SLIDER_H =
            COL_PAD_TOP + ROW_LABEL_H + COL_BLOCK_GAP + SLIDER_H + COL_PAD_BOTTOM;
    public static final float SEGMENT_PAD = 2, SEGMENT_GAP = 2;
    public static final float SEGMENTED_H =
            Math.max(30, lineInk(SZ_BODY) + SEGMENT_PAD * 2 + LINE_PAD);
    public static final float SEGMENT_RADIUS = 9, SEGMENT_BTN_RADIUS = 7;
    public static final float ROW_SEGMENTED_H =
            COL_PAD_TOP + ROW_LABEL_H + COL_BLOCK_GAP + SEGMENTED_H + COL_PAD_BOTTOM;

    public static final Rect DETAIL_VIEWPORT = new Rect(DETAIL_X, DETAIL_PAD_TOP, DETAIL_W, HEIGHT - DETAIL_PAD_TOP);

    // dropdown
    public static final float MENU_PAD = 4, MENU_RADIUS = 10, MENU_MIN_W = 150;
    public static final float MENU_OPTION_H = 34, MENU_OPTION_RADIUS = 7, MENU_CHECK = 14;

    // scrollbars
    public static final float SCROLL_GUTTER = 12, SCROLL_KNOB = 4;

    private NiloreLayout() {
    }

    public record Rect(float x, float y, float width, float height) {
        public float right() {
            return x + width;
        }

        public float bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return width > 0 && height > 0 && px >= x && px < right() && py >= y && py < bottom();
        }

        public Rect intersect(Rect other) {
            float left = Math.max(x, other.x), top = Math.max(y, other.y);
            return new Rect(left, top, Math.max(0, Math.min(right(), other.right()) - left),
                    Math.max(0, Math.min(bottom(), other.bottom()) - top));
        }
    }

    public record Viewport(float x, float y, float scale) {
        public Rect screen(Rect local, float openingScale) {
            float size = scale * openingScale;
            return new Rect(x + WIDTH * scale / 2 + (local.x() - WIDTH / 2) * size,
                    y + HEIGHT * scale / 2 + (local.y() - HEIGHT / 2) * size,
                    local.width() * size, local.height() * size);
        }

        public float localX(double screenX, float openingScale) {
            return WIDTH / 2 + ((float) screenX - x - WIDTH * scale / 2) / (scale * openingScale);
        }

        public float localY(double screenY, float openingScale) {
            return HEIGHT / 2 + ((float) screenY - y - HEIGHT * scale / 2) / (scale * openingScale);
        }
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static Viewport viewport(int width, int height, float x, float y) {
        float fit = Math.min(1, Math.min((width - 16f) / WIDTH, (height - 16f) / HEIGHT));
        float scale = Math.min(1f, fit * WINDOW_SCALE);
        return new Viewport(clamp(x, 4, Math.max(4, width - WIDTH * scale - 4)),
                clamp(y, 4, Math.max(4, height - HEIGHT * scale - 4)), scale);
    }

    public static Viewport centered(int width, int height) {
        Viewport size = viewport(width, height, 4, 4);
        return viewport(width, height, (width - WIDTH * size.scale) / 2, (height - HEIGHT * size.scale) / 2);
    }

    public static Rect navItem(int index) {
        return new Rect(SIDE_PAD_X, NAV_START + index * NAV_H, SIDEBAR_W - SIDE_PAD_X * 2, NAV_H);
    }

    public static Rect searchBox() {
        return new Rect(SEARCH_X, SEARCH_PAD_TOP, SEARCH_W, SEARCH_H);
    }

    public static Rect noteRow(int index, float scroll) {
        return new Rect(LIST_CONTENT_X, LIST_TOP + LIST_PAD_TOP + GROUP_TITLE_H + index * NOTE_H - scroll,
                LIST_CONTENT_W, NOTE_H);
    }

    public static Rect rowSwitch(Rect row) {
        return new Rect(row.right() - NOTE_PAD_X - SWITCH_W, row.y() + (row.height() - SWITCH_H) / 2f,
                SWITCH_W, SWITCH_H);
    }

    /**
     * Centres of the two lines of a list row. The reference centres the whole text block against the
     * row, not each line against the padding, which is what keeps the title level with the switch.
     */
    public static float noteTitleY(Rect row) {
        return row.y() + (row.height() - NOTE_TEXT_H) / 2f + lineCap(SZ_TITLE);
    }

    public static float noteSubY(Rect row) {
        return row.y() + (row.height() - NOTE_TEXT_H) / 2f + NOTE_TITLE_INK + NOTE_SUB_GAP + lineCap(SZ_BODY);
    }

    public static Rect docHead() {
        return new Rect(DETAIL_X, DETAIL_PAD_TOP, DETAIL_W, DOC_H);
    }

    public static float docTitleY(Rect head) {
        return head.y() + (head.height() - DOC_TEXT_H) / 2f + lineCap(SZ_DOC_TITLE);
    }

    public static float docSubY(Rect head) {
        return head.y() + (head.height() - DOC_TEXT_H) / 2f + DOC_TITLE_INK + DOC_SUB_GAP + lineCap(SZ_BODY);
    }

    public static Rect docSwitch() {
        return new Rect(DETAIL_X + DETAIL_W - DOC_PAD_X - SWITCH_W, DETAIL_PAD_TOP + (DOC_H - SWITCH_H) / 2f,
                SWITCH_W, SWITCH_H);
    }

    /** Content box of a setting row, i.e. the row minus its horizontal padding. */
    public static float rowContentX() {
        return GROUP_X + ROW_PAD_X;
    }

    public static float rowContentW() {
        return GROUP_W - ROW_PAD_X * 2;
    }

    public static Rect thumb(float scroll, float maximum, Rect viewport) {
        float height = Math.max(30, viewport.height() * viewport.height() / (viewport.height() + maximum));
        float travel = maximum <= 0 ? 0 : scroll / maximum * (viewport.height() - height);
        return new Rect(viewport.right() - SCROLL_GUTTER + (SCROLL_GUTTER - SCROLL_KNOB) / 2,
                viewport.y() + travel, SCROLL_KNOB, height);
    }

    /** Second-order critically damped spring used for every hover / open / selection motion. */
    public static final class Motion {
        private final double rate;
        private double time = Double.NaN;
        private float value, target;
        private double velocity;

        public Motion(float value, double rate) {
            this.value = this.target = value;
            this.rate = rate;
        }

        public float to(float target, double now) {
            if (!Double.isNaN(time)) {
                double dt = Math.max(0, now - time), error = value - this.target;
                double c = velocity + rate * error, decay = Math.exp(-rate * dt);
                // Analytic critical damping keeps velocity continuous when the target changes.
                value = this.target + (float) ((error + c * dt) * decay);
                velocity = (velocity - rate * c * dt) * decay;
                if (Math.abs(value - this.target) < .0001f && Math.abs(velocity) < .001) {
                    value = this.target;
                    velocity = 0;
                }
            }
            time = now;
            this.target = target;
            return value;
        }

        public void snap(float value, double now) {
            this.value = this.target = value;
            velocity = 0;
            time = now;
        }

        public float value() {
            return value;
        }
    }
}
