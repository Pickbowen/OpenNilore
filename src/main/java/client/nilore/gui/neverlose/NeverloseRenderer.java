package client.nilore.gui.neverlose;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.neverlose.NeverloseLayout.Rect;
import client.nilore.gui.neverlose.NeverloseLayout.Viewport;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;
import client.nilore.render.GlHelper;
import client.nilore.render.GlyphMetrics;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.render.RenderUtil;

import static client.nilore.gui.neverlose.NeverloseLayout.*;

/**
 * Immediate-mode painter for the Neverlose click GUI.
 */
public final class NeverloseRenderer {
    public static final int ACCENT = 0xFF4385FF, TEXT = 0xFFC6C8D2, WHITE = 0xFFF1F3FA;
    private static final int MUTED = 0xFF777A88, HAIRLINE = 0x0CFFFFFF;

    // Font sizes are twice their local em size in this renderer (see Fonts.getCustomFont).
    private static final float SZ_BRAND = 38, SZ_GROUP = 15, SZ_NAV = 23,
            SZ_TITLE = 19, SZ_CHIP = 17, SZ_LABEL = 22,
            SZ_VALUE = 21, SZ_EDITOR = SEARCH_FONT, SZ_TOOLTIP = 22;

    public enum Kind {ENABLED, BOOLEAN, NUMBER, CHOICE}

    /**
     * Font weight plus the rasteriser's ascent/em ratio, measured from the Poppins metrics.
     *
     * <p>The metrics object reports the glyph cell from the font's own ascent, while glyphs are
     * rasterised with {@code Graphics2D}'s ascent, so the two have to be reconciled when centring.
     */
    private enum Face {
        REGULAR(1.1167f), MEDIUM(1.05f), BOLD(1.05f);

        private final float rasterAscent;

        Face(float rasterAscent) {
            this.rasterAscent = rasterAscent;
        }

        FontRenderer of(float size, float rasterScale) {
            return switch (this) {
                case REGULAR -> FontPresets.poppinsRegular(size, rasterScale);
                case MEDIUM -> FontPresets.poppinsMedium(size, rasterScale);
                case BOLD -> FontPresets.poppinsBold(size, rasterScale);
            };
        }
    }

    public record Editor(String value, int cursor, int selection, boolean focused) {
    }

    public record Row(Kind kind, Rect bounds, String label, String value, float progress, float feedback,
                      boolean hovered, boolean editing) {
    }

    public record Section(Rect bounds, String title, String binding, float reveal, List<Row> rows) {
    }

    public record Option(String label, boolean selected, boolean highlighted) {
    }

    public record Dropdown(Rect bounds, float reveal, float scroll, List<Option> options) {
    }

    public record Frame(Viewport viewport, float opacity, float openingScale, int navigation, float selectionY,
                        Editor search, List<Section> sections, Dropdown dropdown, float scroll, float maximumScroll,
                        float mouseX, float mouseY) {
    }

    private NeverloseRenderer() {
    }

    public static void paint(DrawContext dc, GuiGraphics gg, Frame frame) {
        Viewport viewport = frame.viewport();
        dc.save();
        try {
            dc.translate(viewport.x() + WIDTH * viewport.scale() / 2, viewport.y() + HEIGHT * viewport.scale() / 2);
            dc.scale(viewport.scale() * frame.openingScale(), viewport.scale() * frame.openingScale());
            dc.translate(-WIDTH / 2, -HEIGHT / 2);

            Clipper clip = new Clipper(viewport, frame.openingScale());
            Surface surface = new Surface(dc, clip, frame.opacity(), Fonts.deviceScale(viewport.scale()));

            // Backdrop blur behind the window, then the rounded drop shadow in front of it.
            // The blend colour must be white: blur.fsh multiplies the sampled blur by the vertex
            // colour, so passing black (as this call used to) produced an opaque black plate and
            // the sidebar could never show the world through it.
            Rect screenWindow = viewport.screen(WINDOW, frame.openingScale());
            RenderUtil.drawBlurredRect(gg.pose(), screenWindow.x(), screenWindow.y(), screenWindow.width(),
                    screenWindow.height(), RADIUS * viewport.scale() * frame.openingScale(),
                    16f, Math.min(1f, frame.opacity()), 0);
            surface.shadow(WINDOW, RADIUS);

            // One blurred world sample, with separate smoke tints rather than an opaque black half.
            clip.push(new Rect(0, 0, SIDEBAR, HEIGHT));
            surface.gradient(WINDOW, RADIUS, 0xC52A292F, 0xCB202127);
            clip.pop();

            clip.push(new Rect(SIDEBAR, 0, WIDTH - SIDEBAR, HEIGHT));
            surface.gradient(WINDOW, RADIUS, 0xEE171820, 0xE810111A);
            clip.pop();

            surface.border(WINDOW, RADIUS, 0x12FFFFFF);
            surface.rounded(new Rect(SIDEBAR, 12, .7f, HEIGHT - 24), 0, HAIRLINE);
            surface.rounded(new Rect(SIDEBAR + 16, HEADER, WIDTH - SIDEBAR - 32, .7f), 0, HAIRLINE);
            surface.brand();
            surface.navigation(frame);
            surface.text(NAV_NAMES[frame.navigation()], CONTENT.x(), HEADER / 2f,
                    Face.MEDIUM, SZ_NAV, WHITE, SEARCH.x() - CONTENT.x() - 18);
            surface.editor(SEARCH, frame.search(), "Search modules", SEARCH_PADDING);
            surface.icon(8, SEARCH.x() + 14, SEARCH.y() + SEARCH.height() / 2, 12,
                    frame.search().focused() ? ACCENT : MUTED);

            clip.push(CONTENT);
            for (Section section : frame.sections()) {
                if (section.bounds().intersect(CONTENT).height() > 0) {
                    surface.section(section, frame.mouseX(), frame.mouseY());
                }
            }
            if (frame.sections().isEmpty()) {
                surface.text("No modules found", CONTENT.x() + 12, CONTENT.y() + 30, Face.MEDIUM, SZ_TITLE, TEXT, 300);
            }
            clip.pop();

            if (frame.maximumScroll() > 0) {
                surface.rounded(new Rect(CONTENT.right() + 6, CONTENT.y(), 3, CONTENT.height()), 2, 0xFF16191B);
                surface.rounded(thumb(frame.scroll(), frame.maximumScroll()), 2, 0xFF424C54);
            }
            if (frame.dropdown() == null) {
                surface.tooltips(frame);
            }
            if (frame.dropdown() != null) {
                surface.dropdown(frame.dropdown());
            }
        } finally {
            dc.restore();
        }
    }

    /**
     * Scissor helper that maps local window coordinates to the screen.
     *
     * <p>Delegates to {@link RenderUtil}, whose stack already intersects nested rectangles and
     * restores the parent scope, so nesting here is safe.
     */
    static final class Clipper {
        private final Viewport viewport;
        private final float openingScale;

        Clipper(Viewport viewport, float openingScale) {
            this.viewport = viewport;
            this.openingScale = openingScale;
        }

        void push(Rect local) {
            Rect screen = viewport.screen(local, openingScale);
            int minX = (int) Math.floor(screen.x()), minY = (int) Math.floor(screen.y());
            int width = (int) Math.ceil(screen.right()) - minX, height = (int) Math.ceil(screen.bottom()) - minY;
            RenderUtil.pushScissor(minX, minY, Math.max(0, width), Math.max(0, height));
        }

        void pop() {
            RenderUtil.popScissor();
        }
    }

    private static final class Surface {
        private final DrawContext dc;
        private final Clipper clip;
        private final float globalAlpha;
        private final float rasterScale;

        Surface(DrawContext dc, Clipper clip, float globalAlpha, float rasterScale) {
            this.dc = dc;
            this.clip = clip;
            this.globalAlpha = globalAlpha;
            this.rasterScale = rasterScale;
        }

        private int col(int color) {
            int a = (int) (((color >>> 24) * globalAlpha) + .5f);
            return (a << 24) | (color & 0xFFFFFF);
        }

        /**
         * Top edge to hand to the text renderer so the glyph's capital centre lands on
         * {@code centerY}.
         *
         * <p>Walked through the glyph pipeline: the atlas cell starts at
         * {@code y + ceil((lineGap - ascent - descent) / 2) + ascent - 1}, the baseline sits
         * {@code rasterAscent * size / 2} into the cell (the atlas is rasterised at
         * {@code size * guiScale} and drawn at {@code 1 / (2 * guiScale)}) and capitals are
         * {@code capHeight} tall.
         */
        private float textTop(Face face, FontRenderer font, float size, float centerY) {
            GlyphMetrics metrics = font.getMetrics();
            float ascent = metrics.ascent();
            float fontAscent = (float) Math.ceil((metrics.getLineGap() - ascent - metrics.descent()) / 2f);
            return centerY - fontAscent - ascent + 1f - face.rasterAscent * size / 2f + metrics.capHeight() / 2f;
        }

        void text(String value, float x, float centerY, Face face, float size, int color, float maxWidth) {
            if (value == null || value.isEmpty()) {
                return;
            }
            FontRenderer font = face.of(size, this.rasterScale);
            String shown = maxWidth > 0 ? elide(value, font, maxWidth) : value;
            GlHelper.drawText(shown, x, textTop(face, font, size, centerY), font, col(color));
        }

        String elide(String value, FontRenderer font, float width) {
            if (width <= 0 || GlHelper.getStringWidth(value, font) <= width) {
                return value;
            }
            int end = value.length();
            while (end > 0 && GlHelper.getStringWidth(value.substring(0, end) + "...", font) > width) {
                end = value.offsetByCodePoints(end, -1);
            }
            return value.substring(0, end).stripTrailing() + "...";
        }

        void rounded(Rect bounds, float radius, int color) {
            if (bounds.height() <= 0 || bounds.width() <= 0) {
                return;
            }
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius),
                    new Paint().setColor(col(color)));
        }

        void border(Rect bounds, float radius, int color) {
            if (bounds.height() <= 0 || bounds.width() <= 0) {
                return;
            }
            Paint paint = new Paint().setColor(col(color)).setStrokeCap(Paint.StrokeCap.STROKE).setStrokeWidth(1f);
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(bounds.x() + .5f, bounds.y() + .5f,
                    bounds.width() - 1, bounds.height() - 1, radius), paint);
        }

        void gradient(Rect bounds, float radius, int top, int bottom) {
            Paint.GradientCoords coords = new Paint.GradientCoords(bounds.x(), bounds.y(), bounds.x(), bounds.bottom(),
                    col(top), col(bottom));
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius),
                    new Paint().setGradCoords(coords));
        }

        void circle(float x, float y, float radius, int color) {
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(x - radius, y - radius, radius * 2, radius * 2, radius),
                    new Paint().setColor(col(color)));
        }

        /** Antialiased round-capped stroke: a capsule drawn as a rotated rounded rect. */
        void stroke(float x1, float y1, float x2, float y2, float width, int color) {
            float dx = x2 - x1, dy = y2 - y1;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < .01f) {
                circle(x1, y1, width / 2f, color);
                return;
            }
            dc.save();
            dc.translate((x1 + x2) / 2f, (y1 + y2) / 2f);
            dc.rotate((float) Math.toDegrees(Math.atan2(dy, dx)));
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(-length / 2f, -width / 2f, length, width, width / 2f),
                    new Paint().setColor(col(color)));
            dc.restore();
        }

        void ring(float cx, float cy, float radius, float width, int color) {
            int segments = Math.max(12, Math.round(radius * 2.6f));
            float step = (float) (Math.PI * 2 / segments);
            float px = cx + radius, py = cy;
            for (int i = 1; i <= segments; i++) {
                double angle = step * i;
                float nx = cx + (float) Math.cos(angle) * radius, ny = cy + (float) Math.sin(angle) * radius;
                stroke(px, py, nx, ny, width, color);
                px = nx;
                py = ny;
            }
        }

        void brand() {
            text("NEVERLOSE", 16.5f, HEADER / 2f + .5f, Face.BOLD, SZ_BRAND, 0x234385FF, SIDEBAR - 30);
            text("NEVERLOSE", 16f, HEADER / 2f, Face.BOLD, SZ_BRAND, 0xFFD9E0FF, SIDEBAR - 30);
        }

        void navigation(Frame frame) {
            for (int i = 0; i < NAV_GROUPS.length; i++) {
                text(NAV_GROUPS[i], 17, navigationGroupY(i), Face.MEDIUM, SZ_GROUP, MUTED, SIDEBAR - 34);
            }
            // Neutral graphite selection: a material surface, not a white wash that changes
            // colour with the world behind the sidebar. No glow, accent stripe or heavy outline.
            Rect selectedBounds = new Rect(10, frame.selectionY(), SIDEBAR - 20, NAV_ITEM);
            gradient(selectedBounds, 7, 0xF224252D, 0xF2202129);
            border(selectedBounds, 7, 0x06FFFFFF);
            for (int i = 0; i < NAV_NAMES.length; i++) {
                Rect bounds = NeverloseLayout.navigation(i);
                float centerY = bounds.y() + bounds.height() / 2f;
                if (i != frame.navigation() && bounds.contains(frame.mouseX(), frame.mouseY())) {
                    rounded(bounds, 7, 0x701C1E26);
                }
                boolean selected = i == frame.navigation();
                boolean hovered = bounds.contains(frame.mouseX(), frame.mouseY());
                icon(NAV_ICONS[i], 25, centerY, 13, selected ? 0xFFA6AEED : hovered ? 0xFFACAEBB : 0xFF81838F);
                text(NAV_NAMES[i], 42, centerY, Face.MEDIUM, SZ_NAV,
                        selected ? 0xFFE0E1E8 : hovered ? 0xFFBFC0CA : 0xFF9496A2, SIDEBAR - 54);
            }
        }

        void editor(Rect bounds, Editor editor, String hint, float padding) {
            rounded(bounds, 5, 0x45101119);
            border(bounds, 5, editor.focused() ? 0x804385FF : HAIRLINE);
            FontRenderer font = Face.REGULAR.of(SZ_EDITOR, this.rasterScale);
            float left = bounds.x() + padding, right = bounds.right() - 9;
            clip.push(new Rect(left, bounds.y() + 2, right - left, bounds.height() - 4));
            float offset = editorOffset(editor, bounds, padding);
            float caret = GlHelper.getStringWidth(editor.value().substring(0, editor.cursor()), font);
            if (editor.focused() && editor.selection() != editor.cursor()) {
                float other = GlHelper.getStringWidth(editor.value().substring(0, editor.selection()), font);
                rounded(new Rect(left + Math.min(caret, other) - offset, bounds.y() + 6, Math.abs(caret - other),
                        bounds.height() - 12), 2, 0x773A85E2);
            }
            text(editor.value().isEmpty() ? hint : editor.value(), left - offset, bounds.y() + bounds.height() / 2f,
                    Face.REGULAR, SZ_EDITOR, editor.value().isEmpty() ? 0xFF697277 : WHITE, bounds.width() + offset);
            if (editor.focused() && System.nanoTime() / 500_000_000 % 2 == 0) {
                float height = font.getMetrics().capHeight();
                stroke(left + caret - offset, bounds.y() + bounds.height() / 2f - height / 2f,
                        left + caret - offset, bounds.y() + bounds.height() / 2f + height / 2f, 1.2f, WHITE);
            }
            clip.pop();
        }

        float editorOffset(Editor editor, Rect box, float padding) {
            if (!editor.focused()) {
                return 0;
            }
            FontRenderer font = Face.REGULAR.of(SZ_EDITOR, this.rasterScale);
            return Math.max(0, GlHelper.getStringWidth(editor.value().substring(0, editor.cursor()), font)
                    - box.width() + padding + 18);
        }

        void section(Section section, float mouseX, float mouseY) {
            Rect bounds = section.bounds();
            gradient(bounds, 9, 0xB00D0F17, 0x900B0D14);
            border(bounds, 9, HAIRLINE);
            float centerY = bounds.y() + TITLE_CENTER;
            text(section.title().toUpperCase(java.util.Locale.ROOT), bounds.x() + INSET, centerY, Face.MEDIUM,
                    SZ_TITLE, MUTED, binding(bounds).x() - bounds.x() - INSET - 6);
            Rect key = NeverloseLayout.binding(bounds);
            boolean hoverKey = key.contains(mouseX, mouseY);
            rounded(key, 4, hoverKey ? 0xFF232735 : 0x601B1E29);
            border(key, 4, hoverKey ? 0x804385FF : HAIRLINE);
            text(section.binding(), key.x() + 6, centerY, Face.REGULAR, SZ_CHIP,
                    hoverKey ? ACCENT : 0xFF8E979C, key.width() - 10);
            // Chevron rotates from a right arrow to a down arrow while the section opens.
            float angle = (float) ((1 - section.reveal()) * Math.PI / 2);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float cx = bounds.right() - 7, cy = centerY;
            float[] a = rotated(-3.2f, -1.6f, cos, sin);
            float[] b = rotated(0, 1.6f, cos, sin);
            float[] c = rotated(3.2f, -1.6f, cos, sin);
            stroke(cx + a[0], cy + a[1], cx + b[0], cy + b[1], 1.5f, 0xFF65717A);
            stroke(cx + b[0], cy + b[1], cx + c[0], cy + c[1], 1.5f, 0xFF65717A);
            rounded(new Rect(bounds.x() + INSET, bounds.y() + TITLE_LINE, bounds.width() - INSET * 2, .7f), 0, HAIRLINE);
            clip.push(new Rect(bounds.x(), bounds.y() + SECTION_HEADER, bounds.width(),
                    Math.max(0, bounds.height() - SECTION_HEADER)));
            for (Row row : section.rows()) {
                if (row.bounds().intersect(bounds).intersect(CONTENT).height() > 0) {
                    row(row, section.reveal());
                }
            }
            clip.pop();
        }

        private float[] rotated(float x, float y, float cos, float sin) {
            return new float[]{x * cos - y * sin, x * sin + y * cos};
        }

        void row(Row row, float reveal) {
            Rect bounds = row.bounds();
            float centerY = bounds.y() + ROW / 2f;
            if (row.hovered()) {
                rounded(new Rect(bounds.x() + 3, bounds.y() + 1, bounds.width() - 6, ROW - 2), 3.5f, 0x08FFFFFF);
            }
            if (row.feedback() > .001f) {
                rounded(new Rect(bounds.x() + 3, bounds.y() + 1, bounds.width() - 6, ROW - 2), 3.5f,
                        ((int) (row.feedback() * 35) << 24) | (ACCENT & 0xFFFFFF));
            }
            float labelWidth = row.kind() == Kind.BOOLEAN || row.kind() == Kind.ENABLED
                    ? bounds.width() - INSET * 2 - 38 : bounds.width() - INSET * 2 - CONTROL_WIDTH - 8;
            text(row.label(), bounds.x() + INSET, centerY, Face.REGULAR, SZ_LABEL, TEXT, labelWidth);
            switch (row.kind()) {
                case BOOLEAN, ENABLED -> {
                    // Fixed right edge and shared row centre keep every switch in a column aligned.
                    float p = row.progress();
                    Rect track = new Rect(bounds.right() - INSET - 28, centerY - 8, 28, 16);
                    rounded(track, 8, blend(0xFF30333F, ACCENT, p));
                    float knobX = track.x() + 8 + p * (track.width() - 16);
                    circle(knobX, centerY, 6.6f, 0x33000000);
                    circle(knobX, centerY, 6f, 0xFFFFFFFF);
                }
                case NUMBER -> {
                    Rect track = slider(bounds);
                    float knob = track.x() + track.width() * row.progress();
                    rounded(new Rect(track.x(), centerY - 1.5f, track.width(), 3), 1.5f, 0xFF292D39);
                    rounded(new Rect(track.x(), centerY - 1.5f, Math.max(0, knob - track.x()), 3), 1.5f,
                            ACCENT);
                    if (row.feedback() > .001f) {
                        circle(knob, centerY, 7.5f, ((int) (row.feedback() * 60) << 24) | (ACCENT & 0xFFFFFF));
                    }
                    circle(knob, centerY, 5.4f, 0x88000000);
                    circle(knob, centerY, 4.4f, 0xFFFFFFFF);
                    Rect number = valueBox(bounds);
                    // The reference prints the number as plain text; the box only materialises on
                    // hover / while editing, so the row keeps its airy look.
                    if (row.editing() || row.hovered()) {
                        rounded(number, 3, row.editing() ? 0xFF161A1D : 0x14FFFFFF);
                        border(number, 3, row.editing() ? 0xFF3A6E97 : 0x1FFFFFFF);
                    }
                    FontRenderer font = Face.REGULAR.of(SZ_VALUE, this.rasterScale);
                    float width = GlHelper.getStringWidth(row.value(), font);
                    // Keep typed values inside their box too; long input scrolls to its caret.
                    float available = number.width() - 8;
                    float textX = row.editing() ? number.x() + 4 - Math.max(0, width - available)
                            : number.x() + Math.max(4, (number.width() - width) / 2f);
                    clip.push(new Rect(number.x() + 2, number.y(), number.width() - 4, number.height()));
                    text(row.value(), textX, centerY, Face.REGULAR, SZ_VALUE, row.editing() ? WHITE : TEXT,
                            row.editing() ? 0 : available);
                    if (row.editing() && System.nanoTime() / 500_000_000 % 2 == 0) {
                        float caret = textX + width + 1.5f;
                        stroke(caret, centerY - 4.5f, caret, centerY + 4.5f, 1.2f, WHITE);
                    }
                    clip.pop();
                }
                case CHOICE -> field(control(bounds), row.value(), row.hovered());
            }
        }

        void field(Rect bounds, String value, boolean hover) {
            rounded(bounds, 4, hover ? 0xFF232633 : 0x701B1D28);
            border(bounds, 4, hover ? 0x604385FF : 0x06FFFFFF);
            text(value, bounds.x() + 8, bounds.y() + bounds.height() / 2f, Face.REGULAR, SZ_LABEL,
                    hover ? WHITE : TEXT, bounds.width() - 26);
            float cy = bounds.y() + bounds.height() / 2f;
            int chevron = hover ? 0xFFB9C4CB : 0xFF7C868D;
            stroke(bounds.right() - 13, cy - 2, bounds.right() - 10, cy + 1.4f, 1.5f, chevron);
            stroke(bounds.right() - 10, cy + 1.4f, bounds.right() - 7, cy - 2, 1.5f, chevron);
        }

        void tooltips(Frame frame) {
            for (Section section : frame.sections()) {
                for (Row row : section.rows()) {
                    if (!row.hovered() || !row.bounds().intersect(section.bounds()).intersect(CONTENT)
                            .contains(frame.mouseX(), frame.mouseY())) {
                        continue;
                    }
                    float width = row.kind() == Kind.BOOLEAN || row.kind() == Kind.ENABLED
                            ? row.bounds().width() - INSET * 2 - 38 : row.bounds().width() - INSET * 2 - CONTROL_WIDTH - 8;
                    if (frame.mouseX() < control(row.bounds()).x()
                            && GlHelper.getStringWidth(row.label(), Face.REGULAR.of(SZ_LABEL, this.rasterScale)) > width) {
                        tooltip(row.label(), frame.mouseX(), frame.mouseY());
                    } else if (row.kind() == Kind.CHOICE && frame.mouseX() >= control(row.bounds()).x()
                            && GlHelper.getStringWidth(row.value(), Face.REGULAR.of(SZ_LABEL, this.rasterScale))
                            > control(row.bounds()).width() - 26) {
                        tooltip(row.value(), frame.mouseX(), frame.mouseY());
                    }
                }
            }
        }

        void tooltip(String label, float mouseX, float mouseY) {
            FontRenderer font = Face.REGULAR.of(SZ_TOOLTIP, this.rasterScale);
            float width = Math.min(460, GlHelper.getStringWidth(label, font) + 20);
            Rect bounds = new Rect(clamp(mouseX + 14, SIDEBAR + 8, WIDTH - width - 12),
                    clamp(mouseY + 15, HEADER + 8, HEIGHT - 40), width, 27);
            rounded(bounds, 4.5f, 0xFF1A2229);
            border(bounds, 4.5f, 0xFF345168);
            text(label, bounds.x() + 10, bounds.y() + bounds.height() / 2f, Face.REGULAR, SZ_TOOLTIP, WHITE,
                    bounds.width() - 20);
        }

        void dropdown(Dropdown dropdown) {
            Rect bounds = dropdown.bounds();
            shadow(bounds, 4.5f);
            rounded(bounds, 5, 0xFF101315);
            border(bounds, 5, 0xFF2A343C);
            clip.push(new Rect(bounds.x() + 3, bounds.y() + 4, bounds.width() - 6,
                    Math.max(0, (bounds.height() - 8) * dropdown.reveal())));
            for (int i = 0; i < dropdown.options().size(); i++) {
                Option option = dropdown.options().get(i);
                Rect item = new Rect(bounds.x() + 4, bounds.y() + 4 + i * OPTION - dropdown.scroll(),
                        bounds.width() - 8, OPTION);
                if (item.bottom() <= bounds.y() || item.y() >= bounds.bottom()) {
                    continue;
                }
                if (option.highlighted()) {
                    rounded(item, 3.5f, 0xFF203243);
                }
                if (option.selected()) {
                    circle(item.right() - 9, item.y() + OPTION / 2f, 2.2f, ACCENT);
                }
                text(option.label(), item.x() + 6, item.y() + OPTION / 2f, Face.REGULAR, SZ_LABEL,
                        option.selected() ? 0xFF73B2FA : TEXT, item.width() - 24);
            }
            clip.pop();
            float max = dropdown.options().size() * OPTION - bounds.height() + 8;
            if (max > 0) {
                float h = Math.max(15, (bounds.height() - 8) * (bounds.height() - 8)
                        / (dropdown.options().size() * OPTION));
                rounded(new Rect(bounds.right() - 3,
                        bounds.y() + 4 + dropdown.scroll() / max * (bounds.height() - 8 - h), 2, h), 1, 0xFF587189);
            }
        }

        void icon(int type, float x, float y, float size, int color) {
            float s = size / 16f;
            float w = 1.7f * s;
            switch (type) {
                case 0 -> {
                    ring(x, y, 5.5f * s, w, color);
                    seg(x, y, -8, 0, -3, 0, s, w, color);
                    seg(x, y, 3, 0, 8, 0, s, w, color);
                    seg(x, y, 0, -8, 0, -3, s, w, color);
                    seg(x, y, 0, 3, 0, 8, s, w, color);
                }
                case 1 -> {
                    ring(x, y, 5.5f * s, w, color);
                    seg(x, y, 0, 0, 4, -4, s, w, color);
                }
                case 2 -> {
                    seg(x, y, -7, 0, 7, 0, s, w, color);
                    seg(x, y, 0, -7, 0, 7, s, w, color);
                    for (int i = 0; i < 4; i++) {
                        double angle = i * Math.PI / 2;
                        float ax = (float) Math.cos(angle), ay = (float) Math.sin(angle);
                        seg(x, y, ax * 4 - ay * 2, ay * 4 + ax * 2, ax * 7, ay * 7, s, w, color);
                        seg(x, y, ax * 7, ay * 7, ax * 4 + ay * 2, ay * 4 - ax * 2, s, w, color);
                    }
                }
                case 3 -> {
                    ring(x, y - 4 * s, 3 * s, w, color);
                    seg(x, y, -6, 7, -6, 4, s, w, color);
                    cubic(x, y, -6, 4, -6, -1, 6, -1, 6, 4, s, w, color);
                    seg(x, y, 6, 4, 6, 7, s, w, color);
                }
                case 4 -> {
                    // Eye: two arcs meeting at the corners plus a filled pupil.
                    cubic(x, y, -7, 0, -3.2f, -6, 3.2f, -6, 7, 0, s, w, color);
                    cubic(x, y, 7, 0, 3.2f, 6, -3.2f, 6, -7, 0, s, w, color);
                    circle(x, y, 2.3f * s, color);
                }
                case 5 -> {
                    seg(x, y, -6, -6, 6, 6, s, w, color);
                    seg(x, y, 6, -6, -6, 6, s, w, color);
                    seg(x, y, -7, -3, -3, -7, s, w, color);
                    seg(x, y, 3, -7, 7, -3, s, w, color);
                    seg(x, y, -7, 3, -3, 7, s, w, color);
                    seg(x, y, 3, 7, 7, 3, s, w, color);
                }
                case 6 -> {
                    for (int i = -1; i <= 1; i++) {
                        seg(x, y, i * 5, -7, i * 5, 7, s, w, color);
                    }
                    for (float[] dot : new float[][]{{-5, -2}, {0, 3}, {5, -4}}) {
                        ring(x + dot[0] * s, y + dot[1] * s, 2 * s, w, color);
                    }
                }
                case 7 -> {
                    // World cube, distinct from the miscellaneous sliders.
                    seg(x, y, 0, -7, 6, -3.5f, s, w, color);
                    seg(x, y, 6, -3.5f, 6, 3.5f, s, w, color);
                    seg(x, y, 6, 3.5f, 0, 7, s, w, color);
                    seg(x, y, 0, 7, -6, 3.5f, s, w, color);
                    seg(x, y, -6, 3.5f, -6, -3.5f, s, w, color);
                    seg(x, y, -6, -3.5f, 0, -7, s, w, color);
                    seg(x, y, -6, -3.5f, 0, 0, s, w, color);
                    seg(x, y, 6, -3.5f, 0, 0, s, w, color);
                    seg(x, y, 0, 0, 0, 7, s, w, color);
                }
                case 8 -> {
                    ring(x - s, y - s, 5 * s, w, color);
                    seg(x, y, 3, 3, 7, 7, s, w, color);
                }
                default -> {
                }
            }
        }

        private void seg(float x, float y, float x1, float y1, float x2, float y2, float s, float w, int color) {
            stroke(x + x1 * s, y + y1 * s, x + x2 * s, y + y2 * s, w, color);
        }

        private void cubic(float x, float y, float x0, float y0, float c1x, float c1y, float c2x, float c2y,
                           float x1, float y1, float s, float w, int color) {
            int steps = 10;
            float px = x + x0 * s, py = y + y0 * s;
            for (int i = 1; i <= steps; i++) {
                float t = (float) i / steps, u = 1 - t;
                float vx = x + (u * u * u * x0 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x1) * s;
                float vy = y + (u * u * u * y0 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y1) * s;
                stroke(px, py, vx, vy, w, color);
                px = vx;
                py = vy;
            }
        }

        void shadow(Rect bounds, float radius) {
            int layers = 10;
            for (int i = layers; i >= 1; i--) {
                float spread = i * 1.6f;
                int a = Math.max(2, 26 / i);
                dc.drawRoundedRect(RoundedRectangle.ofXYWHR(bounds.x() - spread, bounds.y() - spread,
                                bounds.width() + spread * 2, bounds.height() + spread * 2, radius + spread),
                        new Paint().setColor(col((a << 24))));
            }
        }
    }

    private static int blend(int from, int to, float fraction) {
        int r = Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * fraction);
        int g = Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * fraction);
        int blue = Math.round((from & 255) + ((to & 255) - (from & 255)) * fraction);
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }
}
