package client.nilore.gui.nilore;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.nilore.NiloreLayout.Rect;
import client.nilore.gui.nilore.NiloreLayout.Viewport;
import client.nilore.gui.nilore.NiloreTheme.Palette;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.GlyphMetrics;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.render.RenderUtil;

import static client.nilore.gui.nilore.NiloreLayout.*;

/**
 * Immediate-mode painter for the Nilore click GUI.
 *
 * <p>Geometry comes from {@link NiloreLayout}, colours from {@link NiloreTheme.Palette}; the
 * drawing order mirrors the reference stylesheet's stacking.
 */
public final class NiloreRenderer {
public enum Kind { SWITCH, SLIDER, SELECT, SEGMENTED, KEYBIND }

/**
 * Real font weights, the same triple the Neverlose GUI uses, plus the rasteriser's ascent/em ratio
 * measured from each face.
 *
 * <p>The metrics object reports the glyph cell from the font's own ascent, while glyphs are
 * rasterised with {@code Graphics2D}'s ascent, so the two have to be reconciled when centring.
 */
private enum Face {
    REGULAR(1.115f), MEDIUM(1.05f), BOLD(1.05f);

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

    public record Editor(String value, boolean focused) {
    }

    public record Nav(String label, int count, int icon, boolean active, boolean hovered) {
    }

    public record Note(Rect bounds, String title, String sub, boolean enabled, boolean selected,
                       boolean hovered, float toggle) {
    }

    public record Pill(Rect bounds, String label, boolean active) {
    }

    public record Row(Kind kind, Rect bounds, Rect control, Rect valueBox, String label, String value,
                      float progress, boolean hovered, boolean editing, List<Pill> pills) {
    }

    public record Group(Rect bounds, String title, List<Row> rows) {
    }

    public record Detail(Rect head, Rect toggle, String title, String sub, float progress, List<Group> groups) {
    }

    public record MenuOption(String label, boolean selected, boolean highlighted) {
    }

    public record Menu(Rect bounds, float reveal, List<MenuOption> options) {
    }

    public record Frame(Viewport viewport, float opacity, float openingScale, Palette palette, boolean dark,
                        String navLabel, List<Nav> nav, Editor search, String listTitle, List<Note> notes,
                        float listScroll, float listMax, Detail detail, float detailScroll, float detailMax,
                        Menu menu, float mouseX, float mouseY) {
    }

    private NiloreRenderer() {
    }

    public static void paint(DrawContext dc, GuiGraphics gg, Frame frame) {
        Viewport viewport = frame.viewport();
        Palette palette = frame.palette();
        dc.save();
        try {
            dc.translate(viewport.x() + WIDTH * viewport.scale() / 2, viewport.y() + HEIGHT * viewport.scale() / 2);
            dc.scale(viewport.scale() * frame.openingScale(), viewport.scale() * frame.openingScale());
            dc.translate(-WIDTH / 2, -HEIGHT / 2);

            float screenScale = viewport.scale() * frame.openingScale();
            Clipper clip = new Clipper(viewport, frame.openingScale());
            Surface surface = new Surface(dc, clip, palette, frame.opacity(), frame.dark());

            // Frosted backdrop, then the window's own drop shadow and translucent fill.
            Rect screenWindow = viewport.screen(WINDOW, frame.openingScale());
            RenderUtil.drawBlurredRect(gg.pose(), screenWindow.x(), screenWindow.y(), screenWindow.width(),
                    screenWindow.height(), RADIUS * screenScale, Math.max(6f, 32f * screenScale),
                    Math.min(1f, frame.opacity()), 0xFF000000);
            surface.shadow(WINDOW, RADIUS, palette.shadow, 30, 90);
            surface.rounded(WINDOW, RADIUS, palette.window);

            // Panes: only the two outer edges keep the window's rounded corners.
            surface.corners(new Rect(0, 0, SIDEBAR_W, HEIGHT), RADIUS, 0, 0, RADIUS, palette.sidebar);
            surface.rounded(new Rect(LIST_X, 0, LIST_W, HEIGHT), 0, palette.list);
            surface.corners(new Rect(DETAIL_X, 0, DETAIL_W, HEIGHT), 0, RADIUS, RADIUS, 0, palette.detail);
            surface.rounded(new Rect(SIDEBAR_W - .5f, 0, 1, HEIGHT), 0, palette.separator);
            surface.rounded(new Rect(DETAIL_X - .5f, 0, 1, HEIGHT), 0, palette.separator);
            surface.border(WINDOW, RADIUS, palette.hairline);

            clip.push(new Rect(0, 0, SIDEBAR_W, HEIGHT));
            surface.sidebar(frame);
            clip.pop();

            clip.push(new Rect(LIST_X, 0, LIST_W, HEIGHT));
            surface.search(frame);
            clip.push(LIST_VIEWPORT);
            surface.notes(frame);
            clip.pop();
            if (frame.listMax() > 0) {
                surface.scrollbar(LIST_VIEWPORT, frame.listScroll(), frame.listMax());
            }
            clip.pop();

            clip.push(new Rect(DETAIL_X, 0, DETAIL_W, HEIGHT));
            clip.push(DETAIL_VIEWPORT);
            if (frame.detail() == null) {
                surface.textCenter("Select a module", DETAIL_X + DETAIL_W / 2f, DETAIL_PAD_TOP + 70, Face.REGULAR,
                        SZ_TITLE, palette.secondary, DETAIL_W - 40);
            } else {
                surface.detail(frame.detail());
            }
            clip.pop();
            if (frame.detailMax() > 0) {
                surface.scrollbar(DETAIL_VIEWPORT, frame.detailScroll(), frame.detailMax());
            }
            clip.pop();

            if (frame.menu() != null) {
                surface.menu(frame.menu());
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
        private final Palette palette;
        private final float globalAlpha;
        private final boolean dark;

        Surface(DrawContext dc, Clipper clip, Palette palette, float globalAlpha, boolean dark) {
            this.dc = dc;
            this.clip = clip;
            this.palette = palette;
            this.globalAlpha = globalAlpha;
            this.dark = dark;
        }

        private int col(int color) {
            int a = (int) (((color >>> 24) * globalAlpha) + .5f);
            return (a << 24) | (color & 0xFFFFFF);
        }

        /**
         * Top edge to hand to the text renderer so that the glyphs' capital centre lands on
         * {@code centerY}. Walked through the glyph pipeline: the atlas cell starts at
         * {@code y + ceil((lineGap - ascent - descent) / 2) + ascent - 1} and the baseline sits
         * {@code rasterAscent * size / 2} into the cell.
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

        void textRight(String value, float right, float centerY, Face face, float size, int color, float maxWidth) {
            if (value == null || value.isEmpty()) {
                return;
            }
            FontRenderer font = face.of(size);
            String shown = maxWidth > 0 ? elide(value, font, maxWidth) : value;
            GlHelper.drawText(shown, right - GlHelper.getStringWidth(shown, font), textTop(face, font, size, centerY),
                    font, col(color));
        }

        void textCenter(String value, float centerX, float centerY, Face face, float size, int color, float maxWidth) {
            if (value == null || value.isEmpty()) {
                return;
            }
            FontRenderer font = face.of(size);
            String shown = maxWidth > 0 ? elide(value, font, maxWidth) : value;
            GlHelper.drawText(shown, centerX - GlHelper.getStringWidth(shown, font) / 2f,
                    textTop(face, font, size, centerY), font, col(color));
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
            if (bounds.height() <= 0 || bounds.width() <= 0 || (color >>> 24) == 0) {
                return;
            }
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius),
                    new Paint().setColor(col(color)));
        }

        void corners(Rect bounds, float topLeft, float topRight, float bottomRight, float bottomLeft, int color) {
            dc.drawRoundedRect(RoundedRectangle.ofXYWHRadii(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                            new float[]{topLeft, topRight, bottomRight, bottomLeft}),
                    new Paint().setColor(col(color)));
        }

        void border(Rect bounds, float radius, int color) {
            if (bounds.height() <= 0 || bounds.width() <= 0) {
                return;
            }
            Paint paint = new Paint().setColor(col(color)).setStrokeCap(Paint.StrokeCap.STROKE).setStrokeWidth(1f);
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(bounds.x() + .5f, bounds.y() + .5f,
                    bounds.width() - 1, bounds.height() - 1, Math.max(0, radius - .5f)), paint);
        }

        void circle(float x, float y, float radius, int color) {
            rounded(new Rect(x - radius, y - radius, radius * 2, radius * 2), radius, color);
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

        void shadow(Rect bounds, float radius, int color, float offsetY, float blur) {
            int layers = 16;
            int baseA = color >>> 24;
            for (int i = layers; i >= 1; i--) {
                float t = (float) i / layers;
                float spread = blur * t;
                int a = Math.max(1, Math.round(baseA * (1f - t) / layers * 2.6f));
                rounded(new Rect(bounds.x() - spread, bounds.y() - spread + offsetY * t,
                                bounds.width() + spread * 2, bounds.height() + spread * 2),
                        radius + spread, (a << 24) | (color & 0xFFFFFF));
            }
        }

        // -- widgets --------------------------------------------------------------

        void toggle(Rect bounds, float progress) {
            rounded(bounds, bounds.height() / 2f, blend(palette.switchOff, palette.green, progress));
            float radius = SWITCH_KNOB / 2f;
            float x = bounds.x() + SWITCH_PAD + radius + progress * 20f;
            float y = bounds.y() + bounds.height() / 2f;
            circle(x, y + 1.5f, radius + 1.5f, 0x14000000);
            circle(x, y, radius, 0xFFFFFFFF);
        }

        void slider(Rect track, float progress, boolean active) {
            float centerY = track.y() + track.height() / 2f;
            float barY = centerY - SLIDER_TRACK / 2f;
            float knobX = track.x() + SLIDER_KNOB / 2f + progress * (track.width() - SLIDER_KNOB);
            rounded(new Rect(track.x(), barY, track.width(), SLIDER_TRACK), SLIDER_TRACK / 2f, palette.field);
            if (progress > 0) {
                rounded(new Rect(track.x(), barY, track.width() * progress, SLIDER_TRACK), SLIDER_TRACK / 2f,
                        palette.blue);
            }
            circle(knobX, centerY + 1f, SLIDER_KNOB / 2f + 1.5f, 0x1F000000);
            circle(knobX, centerY, SLIDER_KNOB / 2f + (active ? .6f : 0), 0xFFFFFFFF);
        }

        void selectBox(Rect bounds, String value) {
            rounded(bounds, SELECT_RADIUS, palette.field);
            text(value, bounds.x() + SELECT_PAD_LEFT, bounds.y() + bounds.height() / 2f, Face.REGULAR, SZ_BODY,
                    palette.text, bounds.width() - SELECT_PAD_LEFT - SELECT_PAD_RIGHT);
            // Chevron path "m2.6 4.4 3.4 3.4 3.4-3.4" from the reference's 12x12 viewBox.
            float cx = bounds.right() - 9 - SELECT_CHEVRON / 2f;
            float cy = bounds.y() + bounds.height() / 2f;
            stroke(cx - 3.4f, cy - 1.6f, cx, cy + 1.8f, 1.55f, palette.secondary);
            stroke(cx, cy + 1.8f, cx + 3.4f, cy - 1.6f, 1.55f, palette.secondary);
        }

        void segmented(Rect bounds, List<Pill> pills) {
            rounded(bounds, SEGMENT_RADIUS, palette.field);
            for (Pill pill : pills) {
                if (pill.active()) {
                    rounded(new Rect(pill.bounds().x(), pill.bounds().y() + 1, pill.bounds().width(),
                            pill.bounds().height()), SEGMENT_BTN_RADIUS, 0x24000000);
                    rounded(pill.bounds(), SEGMENT_BTN_RADIUS, palette.segmentActive);
                }
                textCenter(pill.label(), pill.bounds().x() + pill.bounds().width() / 2f,
                        pill.bounds().y() + pill.bounds().height() / 2f, pill.active() ? Face.MEDIUM : Face.REGULAR,
                        SZ_BODY, palette.text, pill.bounds().width() - 6);
            }
        }

        // -- panes ----------------------------------------------------------------

        void sidebar(Frame frame) {
            text("Nilore", BRAND_TEXT_X, BRAND_CENTER_Y, Face.BOLD, SZ_BRAND, palette.text,
                    SIDEBAR_W - SIDE_PAD_X - BRAND_TEXT_X);

            text(frame.navLabel(), SIDE_PAD_X + NAV_LABEL_PAD_X, NAV_LABEL_CENTER, Face.BOLD,
                    SZ_SMALL, palette.secondary, SIDEBAR_W - 40);
            for (int i = 0; i < frame.nav().size(); i++) {
                Nav nav = frame.nav().get(i);
                Rect bounds = navItem(i);
                if (nav.active()) {
                    rounded(bounds, NAV_RADIUS, palette.accentSoft);
                } else if (nav.hovered()) {
                    rounded(bounds, NAV_RADIUS, palette.navHover);
                }
                float centerY = bounds.y() + bounds.height() / 2f;
                icon(nav.icon(), bounds.x() + NAV_PAD + NAV_ICON / 2f, centerY, 19, palette.text);
                text(nav.label(), bounds.x() + NAV_PAD + NAV_ICON + NAV_ICON_GAP, centerY,
                        nav.active() ? Face.BOLD : Face.REGULAR, SZ_NAV, palette.text,
                        bounds.width() - NAV_PAD * 2 - NAV_ICON - NAV_ICON_GAP - 26);
                String count = Integer.toString(nav.count());
                textRight(count, bounds.right() - NAV_PAD, centerY, Face.REGULAR, SZ_COUNT, palette.secondary,
                        bounds.width());
            }
        }

        void search(Frame frame) {
            Rect box = searchBox();
            rounded(box, box.height() / 2f, palette.field);
            // Magnifier from the reference's own 20x20 viewBox, scaled into the 16px slot.
            float ix = box.x() + SEARCH_INNER_PAD;
            float iy = box.y() + (box.height() - SEARCH_ICON) / 2f;
            float k = SEARCH_ICON / 20f;
            ring(ix + 9 * k, iy + 9 * k, 5.4f * k, 1.7f * k, palette.secondary);
            stroke(ix + 13.2f * k, iy + 13.2f * k, ix + 16.5f * k, iy + 16.5f * k, 1.7f * k, palette.secondary);
            float cy = iy + SEARCH_ICON / 2f;
            float textX = box.x() + SEARCH_INNER_PAD + SEARCH_ICON + SEARCH_ICON_GAP;
            float textMax = box.right() - SEARCH_INNER_PAD - textX;
            Editor editor = frame.search();
            if (editor.value().isEmpty()) {
                text("Search", textX, cy, Face.REGULAR, SZ_BODY, palette.secondary, textMax);
            } else {
                text(editor.value(), textX, cy, Face.REGULAR, SZ_BODY, palette.text, textMax);
            }
            if (editor.focused() && System.nanoTime() / 500_000_000 % 2 == 0) {
                FontRenderer font = Face.REGULAR.of(SZ_BODY);
                float caret = textX + GlHelper.getStringWidth(editor.value(), font) + 1.5f;
                stroke(caret, cy - 8, caret, cy + 8, 1.4f, palette.blue);
            }
        }

        void notes(Frame frame) {
            if (frame.notes().isEmpty()) {
                textCenter("No results", LIST_X + LIST_W / 2f, LIST_TOP + LIST_PAD_TOP + 70 + 8.4f, Face.REGULAR,
                        SZ_LABEL, palette.secondary, LIST_W - 40);
                return;
            }
            text(frame.listTitle(), LIST_CONTENT_X + GROUP_TITLE_PAD_X,
                    LIST_TOP + LIST_PAD_TOP + 8 + 7, Face.MEDIUM, SZ_SMALL, palette.tertiary,
                    LIST_CONTENT_W - GROUP_TITLE_PAD_X * 2);
            for (Note note : frame.notes()) {
                Rect bounds = note.bounds();
                if (bounds.bottom() < LIST_TOP || bounds.y() > HEIGHT) {
                    continue;
                }
                if (note.selected()) {
                    rounded(bounds, NOTE_RADIUS, palette.rowSelected);
                } else if (note.hovered()) {
                    rounded(bounds, NOTE_RADIUS, palette.rowHover);
                }
                float textX = bounds.x() + NOTE_PAD_X;
                float textMax = bounds.width() - NOTE_PAD_X * 2 - SWITCH_W - NOTE_GAP;
                text(note.title(), textX, noteTitleY(bounds), Face.MEDIUM, SZ_TITLE, palette.text, textMax);
                text(note.sub(), textX, noteSubY(bounds), Face.REGULAR, SZ_BODY, palette.secondary, textMax);
                toggle(rowSwitch(bounds), note.toggle());
            }
        }

        void detail(Detail detail) {
            Rect head = detail.head();
            float titleX = head.x() + DOC_PAD_X;
            float textMax = head.width() - DOC_PAD_X * 2 - SWITCH_W - 14;
            text(detail.title(), titleX, docTitleY(head), Face.BOLD, SZ_DOC_TITLE, palette.text, textMax);
            text(detail.sub(), titleX, docSubY(head), Face.REGULAR, SZ_BODY, palette.secondary, textMax);
            toggle(detail.toggle(), detail.progress());
            for (Group group : detail.groups()) {
                if (group.bounds().y() > HEIGHT || group.bounds().bottom() < DETAIL_PAD_TOP) {
                    continue;
                }
                // A group without a title collapses its header, as the reference hides that element.
                float header = group.title().isEmpty() ? 0 : GROUP_TITLE_LINE + GROUP_TITLE_GAP;
                if (header > 0) {
                    text(group.title(), GROUP_X + GROUP_TITLE_GAP, group.bounds().y() + GROUP_TITLE_LINE / 2f,
                            Face.BOLD, SZ_SMALL, palette.secondary, GROUP_W - GROUP_TITLE_GAP * 2);
                }
                Rect card = new Rect(group.bounds().x(), group.bounds().y() + header, GROUP_W,
                        group.bounds().height() - header);
                rounded(card, GROUP_RADIUS, palette.card);
                border(card, GROUP_RADIUS, palette.separator);
                clip.push(card);
                for (Row row : group.rows()) {
                    if (row.bounds().bottom() < DETAIL_PAD_TOP || row.bounds().y() > HEIGHT) {
                        continue;
                    }
                    settingRow(row);
                }
                clip.pop();
            }
        }

        void settingRow(Row row) {
            Rect bounds = row.bounds();
            float contentX = rowContentX();
            float contentRight = bounds.right() - ROW_PAD_X;
            float centerY = bounds.y() + bounds.height() / 2f;
            switch (row.kind()) {
                case SWITCH -> {
                    text(row.label(), contentX, centerY, Face.REGULAR, SZ_LABEL, palette.text,
                            contentRight - contentX - SWITCH_W - NOTE_GAP);
                    toggle(row.control(), row.progress());
                }
                case SELECT -> {
                    text(row.label(), contentX, centerY, Face.REGULAR, SZ_LABEL, palette.text,
                            contentRight - contentX - row.control().width() - NOTE_GAP);
                    if (row.editing()) {
                        rounded(row.control(), SELECT_RADIUS, 0x2E767680);
                    }
                    selectBox(row.control(), row.value());
                }
                case SLIDER -> {
                    text(row.label(), contentX, bounds.y() + COL_PAD_TOP + ROW_LABEL_H / 2f, Face.REGULAR, SZ_LABEL,
                            palette.text, row.valueBox().x() - contentX - 8);
                    if (row.editing()) {
                        textRight(row.value(), contentRight, bounds.y() + COL_PAD_TOP + ROW_LABEL_H / 2f,
                                Face.REGULAR, SZ_BODY, palette.blue, row.valueBox().width());
                        if (System.nanoTime() / 500_000_000 % 2 == 0) {
                            FontRenderer font = Face.REGULAR.of(SZ_BODY);
                            float caret = contentRight - GlHelper.getStringWidth(row.value(), font) - 2f;
                            stroke(caret, bounds.y() + COL_PAD_TOP + 2, caret,
                                    bounds.y() + COL_PAD_TOP + ROW_LABEL_H - 2, 1.4f, palette.blue);
                        }
                    } else {
                        textRight(row.value(), contentRight, bounds.y() + COL_PAD_TOP + ROW_LABEL_H / 2f,
                                Face.REGULAR, SZ_BODY, row.hovered() ? palette.blue : palette.secondary,
                                row.valueBox().width());
                    }
                    slider(row.control(), row.progress(), row.hovered());
                }
                case SEGMENTED -> {
                    text(row.label(), contentX, bounds.y() + COL_PAD_TOP + ROW_LABEL_H / 2f, Face.REGULAR, SZ_LABEL,
                            palette.text, contentRight - contentX);
                    segmented(row.control(), row.pills());
                }
                case KEYBIND -> {
                    text(row.label(), contentX, centerY, Face.REGULAR, SZ_LABEL, palette.text,
                            contentRight - contentX - row.control().width() - NOTE_GAP);
                    rounded(row.control(), SELECT_RADIUS, row.editing() ? 0x24FF3B30 : palette.field);
                    textCenter(row.value(), row.control().x() + row.control().width() / 2f, centerY,
                            row.editing() ? Face.BOLD : Face.REGULAR, SZ_BODY,
                            row.editing() ? palette.red : palette.text, row.control().width() - 10);
                }
            }
        }

        void scrollbar(Rect viewport, float scroll, float maximum) {
            rounded(new Rect(viewport.right() - SCROLL_GUTTER + (SCROLL_GUTTER - SCROLL_KNOB) / 2f, viewport.y(),
                    SCROLL_KNOB, viewport.height()), SCROLL_KNOB / 2f, dark ? 0x1AFFFFFF : 0x14000000);
            rounded(thumb(scroll, maximum, viewport), SCROLL_KNOB / 2f, dark ? 0x59FFFFFF : 0x42000000);
        }

        void menu(Menu menu) {
            Rect bounds = menu.bounds();
            shadow(bounds, MENU_RADIUS, dark ? 0x73000000 : 0x380F172A, 8, 34);
            rounded(bounds, MENU_RADIUS, palette.popover);
            border(bounds, MENU_RADIUS, palette.separator);
            clip.push(new Rect(bounds.x() + 1, bounds.y() + MENU_PAD, bounds.width() - 2,
                    Math.max(0, (bounds.height() - MENU_PAD * 2) * menu.reveal())));
            for (int i = 0; i < menu.options().size(); i++) {
                MenuOption option = menu.options().get(i);
                Rect item = new Rect(bounds.x() + MENU_PAD, bounds.y() + MENU_PAD + i * MENU_OPTION_H,
                        bounds.width() - MENU_PAD * 2, MENU_OPTION_H);
                if (option.highlighted()) {
                    rounded(item, MENU_OPTION_RADIUS, palette.field);
                }
                text(option.label(), item.x() + 9, item.y() + MENU_OPTION_H / 2f,
                        option.selected() ? Face.MEDIUM : Face.REGULAR, SZ_BODY, palette.text,
                        item.width() - 9 - MENU_CHECK - 12);
                if (option.selected()) {
                    check(item.right() - 9 - MENU_CHECK / 2f, item.y() + MENU_OPTION_H / 2f, palette.blue);
                }
            }
            clip.pop();
        }

        /** Tick path "m3 7.3 2.55 2.55L11 4.4" from the reference's 14x14 viewBox. */
        void check(float cx, float cy, int color) {
            stroke(cx - 4, cy + .3f, cx - 1.45f, cy + 2.85f, 1.75f, color);
            stroke(cx - 1.45f, cy + 2.85f, cx + 4, cy - 2.6f, 1.75f, color);
        }

        // -- icon set -------------------------------------------------------------

        /**
         * Stroke icons laid out in the reference's own 22x22 viewBox, so each shape is the SVG
         * path of the original rather than a redrawn approximation.
         */
        void icon(int type, float x, float y, float size, int color) {
            float s = size / 22f;
            float w = 1.55f * s;
            switch (type) {
                case 0 -> { // sword
                    line(x, y, s, 17.5f, 4.5f, 9.5f, 12.5f, w, color);
                    line(x, y, s, 7.7f, 10.7f, 11.3f, 14.3f, w, color);
                    line(x, y, s, 9.5f, 12.5f, 7f, 15f, w, color);
                    line(x, y, s, 6f, 14f, 8f, 16f, w, color);
                }
                case 1 -> { // four-way arrows
                    line(x, y, s, 3, 11, 19, 11, w, color);
                    line(x, y, s, 11, 3, 11, 19, w, color);
                    line(x, y, s, 16, 8, 19, 11, w, color);
                    line(x, y, s, 19, 11, 16, 14, w, color);
                    line(x, y, s, 8, 6, 11, 3, w, color);
                    line(x, y, s, 11, 3, 14, 6, w, color);
                    line(x, y, s, 6, 8, 3, 11, w, color);
                    line(x, y, s, 3, 11, 6, 14, w, color);
                    line(x, y, s, 8, 16, 11, 19, w, color);
                    line(x, y, s, 11, 19, 14, 16, w, color);
                }
                case 2 -> { // person
                    ringAt(x, y, s, 11, 6.5f, 3.2f, w, color);
                    curve(x, y, s, 4.5f, 18, 4.5f, 14.5f, 7.5f, 11, 11, 11, w, color);
                    curve(x, y, s, 11, 11, 14.5f, 11, 17.5f, 14.5f, 17.5f, 18, w, color);
                }
                case 3 -> { // isometric cube
                    line(x, y, s, 11, 2.9f, 18.1f, 6.7f, w, color);
                    line(x, y, s, 18.1f, 6.7f, 18.1f, 15.3f, w, color);
                    line(x, y, s, 18.1f, 15.3f, 11, 19.1f, w, color);
                    line(x, y, s, 11, 19.1f, 3.9f, 15.3f, w, color);
                    line(x, y, s, 3.9f, 15.3f, 3.9f, 6.7f, w, color);
                    line(x, y, s, 3.9f, 6.7f, 11, 2.9f, w, color);
                    line(x, y, s, 3.9f, 6.7f, 11, 10.4f, w, color);
                    line(x, y, s, 11, 10.4f, 18.1f, 6.7f, w, color);
                    line(x, y, s, 11, 10.4f, 11, 19.1f, w, color);
                }
                case 4 -> { // globe
                    ringAt(x, y, s, 11, 11, 8, w, color);
                    line(x, y, s, 3, 11, 19, 11, w, color);
                    curve(x, y, s, 11, 3, 12.93f, 3, 14.5f, 6.58f, 14.5f, 11, w, color);
                    curve(x, y, s, 14.5f, 11, 14.5f, 15.42f, 12.93f, 19, 11, 19, w, color);
                    curve(x, y, s, 11, 19, 9.07f, 19, 7.5f, 15.42f, 7.5f, 11, w, color);
                    curve(x, y, s, 7.5f, 11, 7.5f, 6.58f, 9.07f, 3, 11, 3, w, color);
                }
                case 5 -> { // gear
                    ringAt(x, y, s, 11, 11, 3.1f, w, color);
                    line(x, y, s, 11, 2.8f, 11, 4.9f, w, color);
                    line(x, y, s, 11, 17.1f, 11, 19.2f, w, color);
                    line(x, y, s, 4.2f, 4.2f, 5.7f, 5.7f, w, color);
                    line(x, y, s, 16.3f, 16.3f, 17.8f, 17.8f, w, color);
                    line(x, y, s, 2.8f, 11, 4.9f, 11, w, color);
                    line(x, y, s, 17.1f, 11, 19.2f, 11, w, color);
                    line(x, y, s, 4.2f, 17.8f, 5.7f, 16.3f, w, color);
                    line(x, y, s, 16.3f, 5.7f, 17.8f, 4.2f, w, color);
                }
                case 6 -> { // ghost: domed head on a wavy hem
                    curve(x, y, s, 4, 13, 4, 3.67f, 18, 3.67f, 18, 13, w, color);
                    line(x, y, s, 4, 13, 4, 18.6f, w, color);
                    line(x, y, s, 18, 13, 18, 18.6f, w, color);
                    line(x, y, s, 4, 18.6f, 7.33f, 16.8f, w, color);
                    line(x, y, s, 7.33f, 16.8f, 11, 18.6f, w, color);
                    line(x, y, s, 11, 18.6f, 14.67f, 16.8f, w, color);
                    line(x, y, s, 14.67f, 16.8f, 18, 18.6f, w, color);
                    dot(x, y, s, 8.5f, 11, 1.15f, color);
                    dot(x, y, s, 13.5f, 11, 1.15f, color);
                }
                default -> {
                }
            }
        }

        private void line(float x, float y, float s, float x1, float y1, float x2, float y2, float w, int color) {
            stroke(x + (x1 - 11) * s, y + (y1 - 11) * s, x + (x2 - 11) * s, y + (y2 - 11) * s, w, color);
        }

        private void ringAt(float x, float y, float s, float cx, float cy, float radius, float w, int color) {
            ring(x + (cx - 11) * s, y + (cy - 11) * s, radius * s, w, color);
        }

        private void curve(float x, float y, float s, float x0, float y0, float c1x, float c1y, float c2x, float c2y,
                           float x1, float y1, float w, int color) {
            int steps = 12;
            float px = x + (x0 - 11) * s, py = y + (y0 - 11) * s;
            for (int i = 1; i <= steps; i++) {
                float t = (float) i / steps, u = 1 - t;
                float vx = x + (u * u * u * x0 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x1 - 11) * s;
                float vy = y + (u * u * u * y0 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y1 - 11) * s;
                stroke(px, py, vx, vy, w, color);
                px = vx;
                py = vy;
            }
        }

        private void dot(float x, float y, float s, float cx, float cy, float radius, int color) {
            circle(x + (cx - 11) * s, y + (cy - 11) * s, radius * s, color);
        }
    }

    private static int blend(int from, int to, float fraction) {
        int r = Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * fraction);
        int g = Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * fraction);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * fraction);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
