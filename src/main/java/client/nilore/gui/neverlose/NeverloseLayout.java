package client.nilore.gui.neverlose;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of the Neverlose click GUI.
 */
public final class NeverloseLayout {
    public static final float WIDTH = 760, HEIGHT = 560, SIDEBAR = 160, HEADER = 62;
    public static final float RADIUS = 12f;
    /** Right padding every control keeps from the card edge — the value box used to sit flush. */
    public static final float INSET = 12;
    public static final float ROW = 34, GAP = 18, OPTION = 28;
    public static final float CONTROL_WIDTH = 124, VALUE_WIDTH = 44, COLUMN_GAP = 18;
    public static final float FOOTER_HEIGHT = 50;
    public static final float SEARCH_FONT = 21, SEARCH_PADDING = 29;
    /** Card title band: rows start below it, so this is what moves the whole card content down. */
    public static final float SECTION_HEADER = 37;
    /** Title centre and the hairline under it, both relative to the card's top edge. */
    public static final float TITLE_CENTER = 17, TITLE_LINE = 33;
    /** Breathing room between the last row of a card and its bottom edge — same as the side inset. */
    public static final float CARD_PAD = INSET;

    public static final Rect WINDOW = new Rect(0, 0, WIDTH, HEIGHT);
    // Hide the backdrop's inner rounded corners beneath the opaque content panel.
    public static final Rect SIDEBAR_BACKDROP = new Rect(0, 0, SIDEBAR + RADIUS, HEIGHT);
    public static final Rect CONTENT = new Rect(SIDEBAR + 16, HEADER + 16,
            WIDTH - SIDEBAR - 32, HEIGHT - HEADER - 32);
    /**
     * Compact field pinned to the right edge of the content columns, vertically centred in the
     * header band — the reference puts its search at the far right of the header, not across it.
     * Derived from CONTENT/HEADER so it stays aligned if either changes.
     */
    private static final float SEARCH_WIDTH = 220, SEARCH_HEIGHT = 28;
    public static final Rect SEARCH = new Rect(CONTENT.right() - SEARCH_WIDTH,
            (HEADER - SEARCH_HEIGHT) / 2f, SEARCH_WIDTH, SEARCH_HEIGHT);

    // Same order as NAV_CATEGORIES: labels must describe the category actually opened.
    public static final String[] NAV_NAMES = {"Combat", "Movement", "Player", "Render", "Exploit", "World", "Miscellaneous"};
    public static final int[] NAV_ICONS = {0, 2, 3, 4, 1, 7, 6};
    public static final String[] NAV_GROUPS = {"MODULES"};
    public static final float NAV_ITEM = 34;
    private static final float NAV_TOP = HEADER + 8, NAV_LABEL = 26, NAV_ITEM_GAP = 6;
    private static final float[] NAV_Y = new float[NAV_NAMES.length];
    private static final float[] NAV_GROUP_Y = {NAV_TOP + NAV_LABEL / 2f};

    static {
        for (int i = 0; i < NAV_Y.length; i++) {
            NAV_Y[i] = NAV_TOP + NAV_LABEL + i * (NAV_ITEM + NAV_ITEM_GAP);
        }
    }

    private NeverloseLayout() {
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

        public Rect offset(float dx, float dy) {
            return new Rect(x + dx, y + dy, width, height);
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
        // Capped at 0.8: the panel was too large against the HUD, so the whole thing renders 20%
        // smaller. Glyphs are rasterised with Fonts.deviceScale(scale), so text stays crisp.
        float scale = Math.max(.05f, Math.min(.8f, Math.min((width - 16f) / WIDTH, (height - 16f) / HEIGHT)));
        return new Viewport(clamp(x, 4, Math.max(4, width - WIDTH * scale - 4)),
                clamp(y, 4, Math.max(4, height - HEIGHT * scale - 4)), scale);
    }

    public static Viewport centered(int width, int height) {
        Viewport size = viewport(width, height, 4, 4);
        return viewport(width, height, (width - WIDTH * size.scale) / 2, (height - HEIGHT * size.scale) / 2);
    }

    public static Rect navigation(int index) {
        return new Rect(10, NAV_Y[index], SIDEBAR - 20, NAV_ITEM);
    }

    public static float navigationGroupY(int index) {
        return NAV_GROUP_Y[index];
    }

    public static Rect control(Rect row) {
        return new Rect(row.right() - INSET - CONTROL_WIDTH, row.y() + (ROW - 22) / 2, CONTROL_WIDTH, 22);
    }

    /** Shares the dropdown's left edge so every control column lines up. */
    public static Rect slider(Rect row) {
        Rect box = control(row);
        float right = valueBox(row).x() - 8;
        return new Rect(box.x(), row.y() + ROW / 2 - 6, right - box.x(), 12);
    }

    /** Editable value box at the right end of a number row, inset from the card edge. */
    public static Rect valueBox(Rect row) {
        return new Rect(row.right() - INSET - VALUE_WIDTH, row.y() + (ROW - 22) / 2, VALUE_WIDTH, 22);
    }

    public static Rect binding(Rect section) {
        // Centre-aligned with the section title.
        return new Rect(section.right() - INSET - 48, section.y() + TITLE_CENTER - 9, 48, 18);
    }

    public record Packed(List<Rect> sections, float height) {
    }

    public static Packed pack(List<Float> heights, float scroll) {
        List<Integer> columns = new ArrayList<>();
        float[] ends = {0, 0};
        for (float height : heights) {
            int column = ends[0] <= ends[1] ? 0 : 1;
            columns.add(column);
            ends[column] += height + GAP;
        }
        return pack(heights, columns, scroll);
    }

    public static Packed pack(List<Float> heights, List<Integer> columns, float scroll) {
        List<Rect> sections = new ArrayList<>();
        float[] ends = {0, 0};
        float columnWidth = (CONTENT.width() - COLUMN_GAP) / 2;
        for (int i = 0; i < heights.size(); i++) {
            float height = heights.get(i);
            int column = i < columns.size() ? columns.get(i) : 0;
            sections.add(new Rect(CONTENT.x() + column * (columnWidth + COLUMN_GAP), CONTENT.y() + ends[column] - scroll,
                    columnWidth, height));
            ends[column] += height + GAP;
        }
        return new Packed(List.copyOf(sections), Math.max(0, Math.max(ends[0], ends[1]) - GAP));
    }

    public static Rect dropdown(Rect anchor, int count) {
        float h = Math.min(240, count * OPTION + 8);
        float y = anchor.bottom() + 4;
        if (y + h > HEIGHT - 14) y = anchor.y() - h - 4;
        return new Rect(clamp(anchor.x(), SIDEBAR + 8, WIDTH - anchor.width() - 10),
                clamp(y, HEADER + 6, HEIGHT - h - 14), anchor.width(), h);
    }

    public static float fraction(double x, Rect track) {
        return clamp(((float) x - track.x()) / track.width(), 0, 1);
    }

    public static Rect thumb(float scroll, float maximum) {
        return thumb(scroll, maximum, CONTENT);
    }

    public static Rect thumb(float scroll, float maximum, Rect track) {
        float h = Math.max(24, track.height() * track.height() / (track.height() + maximum));
        return new Rect(track.right() + 6, track.y() + (maximum <= 0 ? 0 : scroll / maximum) * (track.height() - h), 3, h);
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

        public double velocity() {
            return velocity;
        }
    }
}
