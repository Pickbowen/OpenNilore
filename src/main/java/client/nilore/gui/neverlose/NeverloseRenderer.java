package client.nilore.gui.neverlose;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.neverlose.NeverloseLayout.Rect;
import client.nilore.gui.neverlose.NeverloseLayout.Viewport;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.GlyphMetrics;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.render.RenderUtil;

import static client.nilore.gui.neverlose.NeverloseLayout.*;

/**
 * Immediate-mode painter for the Neverlose click GUI.
 *
 * <p>Colours and offsets mirror the reference renderer; geometry comes from {@link NeverloseLayout}.
 */
public final class NeverloseRenderer {
    public static final int ACCENT = 0xFF3A85E2, TEXT = 0xFFBDC3C7, WHITE = 0xFFEDF3F5;

    /**
     * Global text scale. 1 = the reference's pixel sizes; raise or lower to tune every label at once.
     *
     * <p>Sizes are ~2x the reference values because {@link FontRenderer} is rasterised at
     * {@code size * guiScale} but drawn with a {@code 1 / (2 * guiScale)} scale, so the size
     * argument renders at about half its value.
     */
    private static final float TEXT_SCALE = 1f;
    private static final float SZ_BRAND = 48 * TEXT_SCALE, SZ_GROUP = 21 * TEXT_SCALE, SZ_NAV = 25 * TEXT_SCALE,
            SZ_TITLE = 26 * TEXT_SCALE, SZ_CHIP = 18 * TEXT_SCALE, SZ_LABEL = 23 * TEXT_SCALE,
            SZ_VALUE = 20 * TEXT_SCALE, SZ_EDITOR = 24 * TEXT_SCALE, SZ_TOOLTIP = 23 * TEXT_SCALE;

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

        FontRenderer of(float size) {
            return switch (this) {
                case REGULAR -> FontPresets.poppinsRegular(size);
                case MEDIUM -> FontPresets.poppinsMedium(size);
                case BOLD -> FontPresets.poppinsBold(size);
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
            Surface surface = new Surface(dc, clip, frame.opacity());

            // Backdrop blur behind the window, then the rounded drop shadow in front of it.
            Rect screenWindow = viewport.screen(WINDOW, frame.openingScale());
            RenderUtil.drawBlurredRect(gg.pose(), screenWindow.x(), screenWindow.y(), screenWindow.width(),
                    screenWindow.height(), RADIUS * viewport.scale() * frame.openingScale(),
                    16f, Math.min(1f, frame.opacity()), 0xFF000000);
            surface.shadow(WINDOW, RADIUS);

            // Left sidebar tinted over the blurred backdrop, right half opaque.
            clip.push(new Rect(0, 0, SIDEBAR, HEIGHT));
            surface.gradient(WINDOW, RADIUS, 0xD2090A0A, 0xCC0D0E0C);
            clip.pop();

            clip.push(new Rect(SIDEBAR, 0, WIDTH - SIDEBAR, HEIGHT));
            surface.rounded(WINDOW, RADIUS, 0xFF080808);
            clip.pop();

            surface.rounded(new Rect(SIDEBAR, 0, 1, HEIGHT), 0, 0xFF242525);
            surface.rounded(new Rect(SIDEBAR, HEADER, WIDTH - SIDEBAR, 1), 0, 0xFF1C1D1D);
            surface.brand();
            surface.navigation(frame);
            surface.editor(SEARCH, frame.search(), "Press CTRL+F to search within GUI", 30);
            surface.icon(8, SEARCH.x() + 15, SEARCH.y() + SEARCH.height() / 2, 11,
                    frame.search().focused() ? ACCENT : 0xFF677077);

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
                surface.rounded(new Rect(748, CONTENT.y(), 3, CONTENT.height()), 2, 0xFF16191B);
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

        Surface(DrawContext dc, Clipper clip, float globalAlpha) {
            this.dc = dc;
            this.clip = clip;
            this.globalAlpha = globalAlpha;
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
            FontRenderer font = face.of(size);
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
            text("NEVERLOSE", 18.5f, 35.5f, Face.BOLD, SZ_BRAND, 0x225CBADD, 0);
            text("NEVERLOSE", 18f, 35f, Face.BOLD, SZ_BRAND, 0xFFF1FAFD, 0);
        }

        void navigation(Frame frame) {
            for (int i = 0; i < NAV_GROUPS.length; i++) {
                text(NAV_GROUPS[i], 20, navigationGroupY(i), Face.MEDIUM, SZ_GROUP, 0xFF646B6D, 145);
            }
            rounded(new Rect(10, frame.selectionY(), SIDEBAR - 20, 29), 4.5f, 0xFF3D3D3B);
            for (int i = 0; i < NAV_NAMES.length; i++) {
                Rect bounds = NeverloseLayout.navigation(i);
                float centerY = bounds.y() + bounds.height() / 2f;
                if (i != frame.navigation() && bounds.contains(frame.mouseX(), frame.mouseY())) {
                    rounded(bounds, 4.5f, 0x452F363C);
                }
                icon(NAV_ICONS[i], 28, centerY, 15, ACCENT);
                text(NAV_NAMES[i], 47, centerY, Face.MEDIUM, SZ_NAV,
                        i == frame.navigation() ? WHITE : TEXT, 115);
            }
        }

        void editor(Rect bounds, Editor editor, String hint, float padding) {
            rounded(bounds, 3.5f, 0xFF0E1011);
            border(bounds, 3.5f, editor.focused() ? 0xFF315374 : 0xFF202325);
            FontRenderer font = Face.REGULAR.of(SZ_EDITOR);
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
            FontRenderer font = Face.REGULAR.of(SZ_EDITOR);
            return Math.max(0, GlHelper.getStringWidth(editor.value().substring(0, editor.cursor()), font)
                    - box.width() + padding + 18);
        }

        void section(Section section, float mouseX, float mouseY) {
            Rect bounds = section.bounds();
            float centerY = bounds.y() + 10;
            text(section.title(), bounds.x() + 8, centerY, Face.MEDIUM, SZ_TITLE, WHITE, bounds.width() - 84);
            Rect key = NeverloseLayout.binding(bounds);
            boolean hoverKey = key.contains(mouseX, mouseY);
            rounded(key, 3.5f, hoverKey ? 0xFF15202A : 0xFF101314);
            text(section.binding(), key.x() + 5, centerY, Face.REGULAR, SZ_CHIP,
                    hoverKey ? ACCENT : 0xFF77858E, key.width() - 9);
            // Chevron rotates from a right arrow to a down arrow while the section opens.
            float angle = (float) ((1 - section.reveal()) * Math.PI / 2);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float cx = bounds.right() - 7, cy = centerY;
            float[] a = rotated(-3.2f, -1.6f, cos, sin);
            float[] b = rotated(0, 1.6f, cos, sin);
            float[] c = rotated(3.2f, -1.6f, cos, sin);
            stroke(cx + a[0], cy + a[1], cx + b[0], cy + b[1], 1.5f, 0xFF65717A);
            stroke(cx + b[0], cy + b[1], cx + c[0], cy + c[1], 1.5f, 0xFF65717A);
            rounded(new Rect(bounds.x() + 6, bounds.y() + 24, bounds.width() - 6, 1), 0, 0xFF222425);
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
                rounded(new Rect(bounds.x(), bounds.y() + 1, bounds.width(), ROW - 2), 3.5f, 0xFF0F1316);
            }
            if (row.feedback() > .001f) {
                rounded(new Rect(bounds.x(), bounds.y() + 1, bounds.width(), ROW - 2), 3.5f,
                        ((int) (row.feedback() * 35) << 24) | (ACCENT & 0xFFFFFF));
            }
            float labelWidth = row.kind() == Kind.BOOLEAN || row.kind() == Kind.ENABLED
                    ? bounds.width() - 40 : bounds.width() - 128;
            text(row.label(), bounds.x() + 8, centerY, Face.REGULAR, SZ_LABEL, TEXT, labelWidth);
            switch (row.kind()) {
                case BOOLEAN, ENABLED -> {
                    float p = row.progress(), x = bounds.right() - 29;
                    rounded(new Rect(x, centerY - 6, 28, 12), 6, blend(0xFF111518, 0xFF0B2537, p));
                    circle(x + 6 + p * 16, centerY, 8.5f, ((int) (p * 24) << 24) | (ACCENT & 0xFFFFFF));
                    circle(x + 6 + p * 16, centerY, 5.5f, blend(0xFF586168, ACCENT, p));
                }
                case NUMBER -> {
                    Rect track = slider(bounds);
                    float knob = track.x() + track.width() * row.progress();
                    rounded(new Rect(track.x(), centerY - 1, track.width(), 2), 1, 0xFF333A40);
                    rounded(new Rect(track.x(), centerY - 1, Math.max(0, knob - track.x()), 2), 1, 0xFF286090);
                    circle(knob, centerY, 8, ((int) (row.feedback() * 28) << 24) | (ACCENT & 0xFFFFFF));
                    circle(knob, centerY, 5.4f + row.feedback() * .8f, ACCENT);
                    Rect number = valueBox(bounds);
                    rounded(number, 2.5f, row.editing() ? 0xFF16202A : 0xFF0E1113);
                    if (row.editing()) {
                        border(number, 2.5f, 0xFF3A6E97);
                    }
                    FontRenderer font = Face.REGULAR.of(SZ_VALUE);
                    float width = GlHelper.getStringWidth(row.value(), font);
                    float textX = number.x() + Math.max(3, (number.width() - width) / 2f);
                    text(row.value(), textX, centerY, Face.REGULAR, SZ_VALUE, row.editing() ? WHITE : TEXT,
                            row.editing() ? 0 : number.width() - 3);
                    if (row.editing() && System.nanoTime() / 500_000_000 % 2 == 0) {
                        float caret = textX + width + 1.5f;
                        stroke(caret, centerY - 4.5f, caret, centerY + 4.5f, 1.2f, WHITE);
                    }
                }
                case CHOICE -> field(control(bounds), row.value(), row.hovered());
            }
        }

        void field(Rect bounds, String value, boolean hover) {
            rounded(bounds, 3, hover ? 0xFF11171D : 0xFF0B0C0D);
            border(bounds, 3, hover ? 0xFF2B4B65 : 0xFF1E2225);
            text(value, bounds.x() + 8, bounds.y() + bounds.height() / 2f, Face.REGULAR, SZ_LABEL, TEXT,
                    bounds.width() - 26);
            float cy = bounds.y() + bounds.height() / 2f;
            stroke(bounds.right() - 13, cy - 1.8f, bounds.right() - 10, cy + 1.5f, 1.4f, 0xFF8B979F);
            stroke(bounds.right() - 10, cy + 1.5f, bounds.right() - 7, cy - 1.8f, 1.4f, 0xFF8B979F);
        }

        void tooltips(Frame frame) {
            for (Section section : frame.sections()) {
                for (Row row : section.rows()) {
                    if (!row.hovered() || !row.bounds().intersect(section.bounds()).intersect(CONTENT)
                            .contains(frame.mouseX(), frame.mouseY())) {
                        continue;
                    }
                    float width = row.kind() == Kind.BOOLEAN || row.kind() == Kind.ENABLED
                            ? row.bounds().width() - 40 : row.bounds().width() - 128;
                    if (frame.mouseX() < control(row.bounds()).x()
                            && GlHelper.getStringWidth(row.label(), Face.REGULAR.of(SZ_LABEL)) > width) {
                        tooltip(row.label(), frame.mouseX(), frame.mouseY());
                    } else if (row.kind() == Kind.CHOICE && frame.mouseX() >= control(row.bounds()).x()
                            && GlHelper.getStringWidth(row.value(), Face.REGULAR.of(SZ_LABEL))
                            > control(row.bounds()).width() - 26) {
                        tooltip(row.value(), frame.mouseX(), frame.mouseY());
                    }
                }
            }
        }

        void tooltip(String label, float mouseX, float mouseY) {
            FontRenderer font = Face.REGULAR.of(SZ_TOOLTIP);
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
            rounded(bounds, 4.5f, 0xFF12171B);
            border(bounds, 4.5f, 0xFF2C3C49);
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
                    quad(x, y, -8, 0, -3, -7, 3, -7, s, w, color);
                    quad(x, y, 3, -7, 8, 0, 8, 0, s, w, color);
                    quad(x, y, 8, 0, 3, 7, -3, 7, s, w, color);
                    quad(x, y, -3, 7, -8, 0, -8, 0, s, w, color);
                    ring(x, y, 2.5f * s, w, color);
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

        private void quad(float x, float y, float x0, float y0, float cx, float cy, float x1, float y1, float s,
                          float w, int color) {
            int steps = 8;
            float px = x + x0 * s, py = y + y0 * s;
            for (int i = 1; i <= steps; i++) {
                float t = (float) i / steps, u = 1 - t;
                float vx = x + (u * u * x0 + 2 * u * t * cx + t * t * x1) * s;
                float vy = y + (u * u * y0 + 2 * u * t * cy + t * t * y1) * s;
                stroke(px, py, vx, vy, w, color);
                px = vx;
                py = vy;
            }
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
            // Layered translucent rings stand in for the reference box-gradient shadow.
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
