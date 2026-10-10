package client.nilore.gui.neverlose;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of the Neverlose click GUI.
 *
 * <p>Every constant below is copied verbatim from the reference implementation so the
 * ported window sits at the exact same coordinates on both clients.
 */
public final class NeverloseLayout {
    public static final float WIDTH = 760, HEIGHT = 496, SIDEBAR = 180, HEADER = 64;
    public static final float RADIUS = 5.5f;
    public static final float ROW = 29, SECTION_HEADER = 28, GAP = 18, OPTION = 25;

    public static final Rect WINDOW = new Rect(0, 0, WIDTH, HEIGHT);
    // Hide the backdrop's inner rounded corners beneath the opaque content panel.
    public static final Rect SIDEBAR_BACKDROP = new Rect(0, 0, SIDEBAR + RADIUS, HEIGHT);
    public static final Rect CONTENT = new Rect(202, 84, 536, HEIGHT - 84 - 20);
    public static final Rect SEARCH = new Rect(202, 19, CONTENT.width(), 27);

    public static final String[] NAV_NAMES = {"Combat", "Movement", "Player", "Render", "World", "Misc", "Ghost"};
    public static final int[] NAV_ICONS = {0, 2, 3, 4, 6, 5, 1};
    private static final float[] NAV_Y = {96, 132, 200, 236, 304, 372, 408};

    public static final String[] NAV_GROUPS = {"Combat", "Player", "World", "Miscellaneous"};
    private static final float[] NAV_GROUP_Y = {79, 183, 287, 355};

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
        float scale = Math.max(.05f, Math.min(1, Math.min((width - 16f) / WIDTH, (height - 16f) / HEIGHT)));
        return new Viewport(clamp(x, 4, Math.max(4, width - WIDTH * scale - 4)),
                clamp(y, 4, Math.max(4, height - HEIGHT * scale - 4)), scale);
    }

    public static Viewport centered(int width, int height) {
        Viewport size = viewport(width, height, 4, 4);
        return viewport(width, height, (width - WIDTH * size.scale) / 2, (height - HEIGHT * size.scale) / 2);
    }

    public static Rect navigation(int index) {
        return new Rect(10, NAV_Y[index], SIDEBAR - 20, 29);
    }

    public static float navigationGroupY(int index) {
        return NAV_GROUP_Y[index];
    }

    public static Rect control(Rect row) {
        return new Rect(row.right() - 114, row.y() + (ROW - 20) / 2, 114, 20);
    }

    public static Rect slider(Rect row) {
        return new Rect(row.right() - 114, row.y() + ROW / 2 - 6, 76, 12);
    }

    /** Editable value box at the right end of a number row. */
    public static Rect valueBox(Rect row) {
        Rect track = slider(row);
        return new Rect(track.right() + 7, row.y() + 5, 31, ROW - 10);
    }

    public static Rect binding(Rect section) {
        // Centre-aligned with the section title, which is drawn at section.y() + 10.
        return new Rect(section.right() - 67, section.y() + 1, 48, 18);
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
        float columnWidth = (CONTENT.width() - 24) / 2;
        for (int i = 0; i < heights.size(); i++) {
            float height = heights.get(i);
            int column = i < columns.size() ? columns.get(i) : 0;
            sections.add(new Rect(CONTENT.x() + column * (columnWidth + 24), CONTENT.y() + ends[column] - scroll,
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
        return new Rect(748, track.y() + (maximum <= 0 ? 0 : scroll / maximum) * (track.height() - h), 3, h);
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
