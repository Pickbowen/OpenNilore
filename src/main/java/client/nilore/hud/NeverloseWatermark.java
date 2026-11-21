package client.nilore.hud;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.multiplayer.PlayerInfo;
import client.nilore.ClientBase;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.misc.Assets;
import client.nilore.utils.render.RenderUtil;

/** Compact Neverlose strip; every glyph run is centred by its visible atlas ink, not a shared baseline. */
public class NeverloseWatermark {
    private static final float MARGIN = 6, HEIGHT = 18, PAD = 6, GAP = 7, ICON_GAP = 3, AVATAR = 14;
    private static final float END_PAD = (HEIGHT - AVATAR) / 2;
    private static final long FLIP_NANOS = 160_000_000L;
    private static final int BACKGROUND = 0xCB101117, TEXT = 0xFFE3E5EC, ACCENT = 0xFF739DE0;
    private final FontRenderer brandFont = FontPresets.poppinsBold(20);
    private final FontRenderer textFont = FontPresets.poppinsMedium(17);
    private final FontRenderer iconFont = FontPresets.materialIcons(18);
    // Font/raster variants and glyphs are both bounded: live usernames/counters cannot grow them forever.
    private final Map<FontKey, InkFont> inkFonts = new LinkedHashMap<>(12, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<FontKey, InkFont> entry) { return size() > 12; }
    };
    private final Map<String, Font> awtFonts = new LinkedHashMap<>();

    private String fpsCurrent, fpsPrevious, fpsPending;
    private long fpsStarted;
    private float fpsAnimatedWidth;

    public void onRender2D(Render2DEvent event) {
        // The strip and its blur share a single GL-pass transform.
    }

    public void onGlRender(GlRenderEvent event) {
        if (ClientBase.mc.options.renderDebug) return;
        float screenWidth = ClientBase.mc.getWindow().getGuiScaledWidth();
        float screenHeight = ClientBase.mc.getWindow().getGuiScaledHeight();
        float x = Math.min(MARGIN, screenWidth / 4), y = Math.min(MARGIN, screenHeight / 4);
        float available = Math.max(1, screenWidth - x * 2);
        Snapshot data = snapshot();
        long now = System.nanoTime();
        updateFps(data.fps(), now);
        Layout layout = layout(data, available);
        float scale = Math.min(1, Math.min(available / layout.width(), Math.max(1, screenHeight - y * 2) / HEIGHT));
        DrawContext context = event.drawContext();
        context.save();
        try {
            context.translate(x, y);
            context.scale(scale, scale);
            // Soft, offset capsule shadow. No rectangular blur plate around the rounded strip.
            try (Paint shadow = new Paint()) {
                for (int i = 6; i >= 1; i--) {
                    float spread = i * .65f;
                    context.drawRoundedRect(RoundedRectangle.ofXYWHR(-spread, 1 - spread,
                            layout.width() + spread * 2, HEIGHT + spread * 2, HEIGHT / 2 + spread),
                            shadow.setColor((Math.max(2, 16 / i) << 24)));
                }
            }
            RenderUtil.drawBlurredRect(context.getPoseStack(), 0, 0, layout.width(), HEIGHT, HEIGHT / 2, 15, .85f, 0);
            try (Paint paint = new Paint()) {
                context.drawRoundedRect(RoundedRectangle.ofXYWHR(0, 0, layout.width(), HEIGHT, HEIGHT / 2), paint.setColor(BACKGROUND));
                for (Part part : layout.parts()) {
                    float cursor = part.x();
                    if (!part.icon().isEmpty()) {
                        drawCentered(context, part.icon(), cursor, HEIGHT / 2, iconFont, ACCENT, paint);
                        cursor += part.iconWidth() + ICON_GAP;
                    }
                    if (part.font() == brandFont) {
                        // Blue shadow follows the glyph silhouettes: deliberately no badge/backplate.
                        for (float[] offset : new float[][]{{-.5f, .5f}, {.5f, .5f}, {0, 1}, {1, 1}}) {
                            drawCentered(context, part.text(), cursor + offset[0], HEIGHT / 2 + offset[1],
                                    part.font(), 0x356B9EEA, paint);
                        }
                        drawCentered(context, part.text(), cursor, HEIGHT / 2, part.font(), 0xFFF2F6FF, paint);
                    } else if (part.icon().equals("\uE1B8")) {
                        drawFps(context, cursor, now, paint);
                    } else {
                        drawCentered(context, part.text(), cursor, HEIGHT / 2, part.font(), TEXT, paint);
                    }
                }
                float headX = layout.width() - END_PAD - AVATAR, headY = (HEIGHT - AVATAR) / 2;
                if (ClientBase.mc.player != null) {
                    GlHelper.drawPlayerHeadRounded(ClientBase.mc.player, headX, headY, AVATAR, AVATAR, 1, AVATAR / 2);
                } else {
                    context.drawRoundedRect(RoundedRectangle.ofXYWHR(headX, headY, AVATAR, AVATAR, AVATAR / 2), paint.setColor(0xFF303542));
                    drawCentered(context, "\uE7FD", headX + (AVATAR - iconFont.getWidth("\uE7FD")) / 2,
                            HEIGHT / 2, iconFont, ACCENT, paint);
                }
            }
        } finally {
            context.restore();
        }
    }

    private Snapshot snapshot() {
        String user = ClientBase.mc.player == null ? ClientBase.mc.getUser().getName()
                : ClientBase.mc.player.getGameProfile().getName();
        PlayerInfo info = ClientBase.mc.player != null && ClientBase.mc.player.connection != null
                ? ClientBase.mc.player.connection.getPlayerInfo(ClientBase.mc.player.getUUID()) : null;
        String speed = "-- b/s";
        if (ClientBase.mc.player != null) {
            double dx = ClientBase.mc.player.getX() - ClientBase.mc.player.xo;
            double dz = ClientBase.mc.player.getZ() - ClientBase.mc.player.zo;
            speed = String.format(Locale.ROOT, "%.1f b/s", Math.hypot(dx, dz) * 20);
        }
        return new Snapshot(user, Integer.toString(ClientBase.mc.getFps()), speed, info == null ? "-- ms" : info.getLatency() + " ms");
    }

    private Layout layout(Snapshot data, float available) {
        List<Part> parts = new ArrayList<>();
        parts.add(part("NL", "", brandFont));
        parts.add(part(data.user(), "\uE7FD", textFont));
        parts.add(part(data.fps(), "\uE1B8", textFont));
        parts.add(part(data.speed(), "\uE9E4", textFont));
        parts.add(part(data.ping(), "\uE63E", textFont));
        if (width(parts) > available) {
            Part user = parts.get(1);
            float budget = Math.max(0, available - width(parts) + textFont.getWidth(user.text()));
            String fitted = fit(user.text(), budget);
            if (fitted.isEmpty()) parts.remove(1);
            else parts.set(1, part(fitted, user.icon(), textFont));
        }
        float cursor = PAD;
        List<Part> positioned = new ArrayList<>(parts.size());
        for (Part part : parts) {
            positioned.add(new Part(part.text(), part.icon(), part.font(), part.iconWidth(), part.width(), cursor));
            cursor += part.width() + GAP;
        }
        return new Layout(positioned, cursor + AVATAR + END_PAD);
    }

    private Part part(String text, String icon, FontRenderer font) {
        float iconWidth = icon.isEmpty() ? 0 : iconFont.getWidth(icon);
        return new Part(text, icon, font, iconWidth, (icon.equals("\uE1B8") ? fpsWidth() : font.getWidth(text)) + (icon.isEmpty() ? 0 : iconWidth + ICON_GAP), 0);
    }

    private float width(List<Part> parts) {
        float width = PAD + END_PAD + AVATAR + parts.size() * GAP;
        for (Part part : parts) width += part.width();
        return width;
    }

    /** Queue the latest sample during a flip: rapid changes never restart an invisible frame. */
    private void updateFps(String value, long now) {
        fpsPending = value;
        if (fpsCurrent == null) {
            fpsCurrent = value;
            fpsAnimatedWidth = textFont.getWidth(value);
            return;
        }
        if (fpsPrevious != null && now - fpsStarted >= FLIP_NANOS) fpsPrevious = null;
        if (fpsPrevious == null && !fpsCurrent.equals(fpsPending)) {
            fpsPrevious = fpsCurrent;
            fpsCurrent = fpsPending;
            fpsStarted = now;
        }
        float target = textFont.getWidth(fpsCurrent);
        if (fpsPrevious == null) {
            fpsAnimatedWidth = target;
        } else {
            float t = Math.max(0, Math.min(1, (now - fpsStarted) / (float) FLIP_NANOS));
            float eased = t * t * (3 - 2 * t);
            float from = textFont.getWidth(fpsPrevious);
            fpsAnimatedWidth = from + (target - from) * eased;
        }
    }

    private float fpsDigitsWidth() {
        return fpsAnimatedWidth;
    }

    private float fpsWidth() {
        return fpsDigitsWidth() + textFont.getWidth(" FPS");
    }

    private void drawFps(DrawContext context, float x, long now, Paint paint) {
        float digitsWidth = fpsDigitsWidth();
        drawCentered(context, " FPS", x + digitsWidth, HEIGHT / 2, textFont, TEXT, paint);
        context.save();
        try {
            context.clip(client.nilore.render.Rectangle.ofXYWH(x - 1, 2, digitsWidth + 2, HEIGHT - 4));
            if (fpsPrevious == null) {
                drawCentered(context, fpsCurrent, x,
                        HEIGHT / 2, textFont, TEXT, paint);
            } else {
                float t = Math.max(0, Math.min(1, (now - fpsStarted) / (float) FLIP_NANOS));
                // The outgoing number stays anchored: fast fade only, never scroll it upwards.
                float oldOpacity = Math.max(0, 1 - t / .55f);
                oldOpacity *= oldOpacity;
                // Incoming number starts slightly lower, then settles with a quick ease-out.
                float incoming = Math.max(0, Math.min(1, (t - .15f) / .85f));
                float settled = 1 - (float) Math.pow(1 - incoming, 3);
                drawCentered(context, fpsPrevious, x,
                        HEIGHT / 2, textFont, alpha(TEXT, oldOpacity), paint);
                drawCentered(context, fpsCurrent, x,
                        HEIGHT / 2 + 4.5f * (1 - settled), textFont, alpha(TEXT, incoming), paint);
            }
        } finally { context.restore(); }
    }

    private static int alpha(int color, float opacity) {
        return (Math.round((color >>> 24) * opacity) << 24) | (color & 0xFFFFFF);
    }

    private String fit(String value, float budget) {
        if (textFont.getWidth(value) <= budget) return value;
        String ellipsis = "\u2026";
        if (textFont.getWidth(ellipsis) > budget) return "";
        int end = value.length();
        while (end > 0) {
            end = value.offsetByCodePoints(end, -1);
            String fitted = value.substring(0, end) + ellipsis;
            if (textFont.getWidth(fitted) <= budget) return fitted;
        }
        return ellipsis;
    }

    private void drawCentered(DrawContext context, String text, float x, float centerY,
                              FontRenderer font, int color, Paint paint) {
        if (text.isEmpty() || font.getFont() == null) return;
        // Match CustomFont's actual atlas scale, rather than guessing an ascent/em ratio.
        float rasterScale = font.getFont().getScale();
        FontKey key = new FontKey(font.getFontName(), font.getSize(), rasterScale);
        InkFont ink = inkFonts.computeIfAbsent(key, this::createInkFont);
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) continue;
            GlyphInk glyph = ink.glyph(text.charAt(i));
            top = Math.min(top, glyph.top());
            bottom = Math.max(bottom, glyph.bottom());
        }
        if (!Float.isFinite(top)) return;
        // context adds model ascent; CustomFont then subtracts 1 and snaps the atlas cell origin.
        // Each atlas glyph was rasterised at Graphics2D ascent, included in the ink bounds above.
        float drawY = centerY - (top + bottom) / 2 - font.getMetrics().ascent() + 1;
        context.drawString(text, x, drawY, font, paint.setColor(color));
    }

    private InkFont createInkFont(FontKey key) {
        Font primary = loadFont(key.name()).deriveFont(key.size() * .5f * key.scale());
        Font fallback = loadFont("pingfang_sc_regular.ttf").deriveFont(key.size() * .5f * key.scale());
        return new InkFont(primary, fallback, key.scale());
    }

    private Font loadFont(String name) {
        return awtFonts.computeIfAbsent(name, value -> {
            try (InputStream stream = Assets.open("/assets/nilore/fonts/" + value)) {
                if (stream != null) return Font.createFont(Font.TRUETYPE_FONT, stream);
            } catch (Exception ignored) {
                // Keep the HUD readable if a packaged font is missing or corrupt.
            }
            return new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        });
    }

    private static final class InkFont {
        private final Font primary, fallback;
        private final float scale;
        private final FontRenderContext frc;
        private final int primaryAscent, fallbackAscent;
        private final Map<Character, GlyphInk> glyphs = new LinkedHashMap<>(128, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Character, GlyphInk> entry) { return size() > 256; }
        };

        InkFont(Font primary, Font fallback, float scale) {
            this.primary = primary;
            this.fallback = fallback;
            this.scale = scale;
            Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                frc = g.getFontRenderContext();
                primaryAscent = g.getFontMetrics(primary).getAscent();
                fallbackAscent = g.getFontMetrics(fallback).getAscent();
            } finally { g.dispose(); }
        }

        GlyphInk glyph(char value) {
            return glyphs.computeIfAbsent(value, ch -> {
                boolean usePrimary = primary.canDisplay(ch) || !fallback.canDisplay(ch);
                Font font = usePrimary ? primary : fallback;
                int ascent = usePrimary ? primaryAscent : fallbackAscent;
                Rectangle bounds = font.createGlyphVector(frc, new char[]{ch}).getPixelBounds(frc, 0, 0);
                return new GlyphInk((ascent + bounds.y) / scale, (ascent + bounds.y + bounds.height) / scale);
            });
        }
    }

    private record FontKey(String name, float size, float scale) { }
    private record GlyphInk(float top, float bottom) { }
    private record Snapshot(String user, String fps, String speed, String ping) { }
    private record Part(String text, String icon, FontRenderer font, float iconWidth, float width, float x) { }
    private record Layout(List<Part> parts, float width) { }
}
