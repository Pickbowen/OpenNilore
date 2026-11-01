package client.nilore.modules.impl.render;

import client.nilore.NiloreClient;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.ModuleToggleEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.hud.DynamicIsland;
import client.nilore.hud.LogoWatermark;
import client.nilore.hud.ModuleListHud;
import client.nilore.hud.NeverloseWatermark;
import client.nilore.hud.OpaiWatermark;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.network.PingTracker;
import client.nilore.render.DrawContext;
import client.nilore.render.FontRenderer;
import client.nilore.render.FontPresets;
import client.nilore.render.Fonts;
import client.nilore.render.GlyphMetrics;
import client.nilore.render.Paint;
import client.nilore.render.Rectangle;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.event.EventTarget;
import client.nilore.utils.render.RenderUtil;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.GuiGraphics;

public class Watermark extends Module {
    final ModeSetting styleSetting = new ModeSetting("Style", "Neverlose", "DynamicIsland", "Simple", "Pharos", "Exhibition", "Logo", "Naven", "Opai").withDefault("Exhibition");
    private final BooleanSetting showFpsSetting = new BooleanSetting("Show FPS", true, () -> this.styleSetting.is("Exhibition"));
    private final BooleanSetting showMsSetting = new BooleanSetting("Show MS", true, () -> this.styleSetting.is("Exhibition"));
    private final NumberSetting accentRed = new NumberSetting("Opai Accent R", 187, 0, 255, 1, () -> this.styleSetting.is("Opai"));
    private final NumberSetting accentGreen = new NumberSetting("Opai Accent G", 195, 0, 255, 1, () -> this.styleSetting.is("Opai"));
    private final NumberSetting accentBlue = new NumberSetting("Opai Accent B", 255, 0, 255, 1, () -> this.styleSetting.is("Opai"));
    private final DynamicIsland dynamicIsland = new DynamicIsland();
    private final LogoWatermark logoWatermark = new LogoWatermark();
    private final NeverloseWatermark neverloseWatermark = new NeverloseWatermark();
    private final OpaiWatermark opaiWatermark = new OpaiWatermark();

    // Pharos style (original Simple - client name + fps)
    private static final FontRenderer titleFont = Fonts.getRenderer("quicksand.ttf", 36.0f);
    private static final FontRenderer fpsFont = Fonts.getRenderer("quicksand.ttf", 20.0f);
    private static final float MARGIN = 8.0f;

    // Simple style (top-left glass panel)
    private static final FontRenderer textFont = FontPresets.productSans(24.0f);
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SEPARATOR_COLOR = 0xFF9E9E9E;
    private static final int TEXT_SHADOW_COLOR = 0x64000000;
    private static final float SIMPLE_PAD_X = 7.0f;
    private static final float SIMPLE_PAD_Y = 4.0f;
    private static final float SIMPLE_RADIUS = 6.0f;
    private static final float SIMPLE_BLUR_STRENGTH = 20.0f;
    private static final float SIMPLE_BLUR_OPACITY = 0.9f;

    public Watermark() {
        super("Watermark", Category.RENDER);
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        if (!this.isEnabled()) {
            return;
        }
        switch (this.styleSetting.getValue()) {
            case "Neverlose":
                this.neverloseWatermark.onRender2D(render2DEvent);
                break;
            case "DynamicIsland":
                this.dynamicIsland.onRender2D(render2DEvent);
                break;
            case "Exhibition":
                this.renderExhibition(render2DEvent);
                break;
        }
    }

    @EventTarget
    public void onModuleToggle(ModuleToggleEvent moduleToggleEvent) {
        this.opaiWatermark.onModuleToggle(moduleToggleEvent);
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (!this.isEnabled()) {
            return;
        }
        DrawContext ctx = glRenderEvent.drawContext();
        switch (this.styleSetting.getValue()) {
            case "Neverlose":
                this.neverloseWatermark.onGlRender(glRenderEvent);
                break;
            case "Pharos":
                this.renderPharos(ctx);
                break;
            case "Simple":
                this.renderSimple(ctx);
                break;
            case "Logo":
                this.logoWatermark.onGlRender(glRenderEvent);
                break;
            case "Naven":
                this.renderNaven(ctx);
                break;
            case "Opai":
                this.opaiWatermark.onGlRender(glRenderEvent, rgb(this.accentRed, this.accentGreen, this.accentBlue));
                break;
        }
    }

    // Naven style. Geometry follows Naven's own HUD watermark (panel sized text+14 x text+8,
    // radius 5, 3px header bar, text inset 7 from the left), while the header/body colours and
    // the way the bar hugs the rounded corners follow the client's own Naven look in
    // Scaffold.renderNavenBlockHud: draw the body, then clip to the top 3px and draw the very
    // same rounded rect again in red.
    private static final FontRenderer NAVEN_FONT = FontPresets.pingfang(24.0f);
    private static final float NAVEN_RADIUS = 5.0f;
    private static final float NAVEN_PAD_X = 6.0f;
    private static final float NAVEN_TEXT_TOP = 5.0f;
    private static final float NAVEN_PAD_BOTTOM = 3.0f;
    private static final float NAVEN_HEADER_H = 3.0f;
    private static final int NAVEN_ALPHA = 190;
    private static final int NAVEN_BODY_COLOR = NAVEN_ALPHA << 24;
    private static final int NAVEN_HEADER_COLOR = NAVEN_ALPHA << 24 | 0x962D2D;
    private static final int NAVEN_TEXT_COLOR = 0xFFFFFFFF;
    private static final int NAVEN_TEXT_SHADOW = 0x64000000;
    private static final DateTimeFormatter NAVEN_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final float NAVEN_RISE = 2.0f;
    private static final float NAVEN_TEXT_RISE = 2.0f;

    private String gameName() {
        if (mc.player != null) {
            String name = mc.player.getGameProfile().getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return NiloreClient.username;
    }

    private void renderNaven(DrawContext ctx) {
        if (mc.player == null) return;

        String text = NiloreClient.CLIENT_NAME + " | " + NiloreClient.VERSION + " | "
                + gameName() + " | " + mc.getFps() + " FPS | "
                + LocalTime.now().format(NAVEN_CLOCK);

        FontRenderer font = NAVEN_FONT;
        GlyphMetrics metrics = font.getMetrics();
        float lineHeight = metrics.getLineHeight();
        float boxW = font.getWidth(text) + NAVEN_PAD_X * 2.0f;
        float boxH = NAVEN_TEXT_TOP + lineHeight + NAVEN_PAD_BOTTOM;
        float x = MARGIN;
        float y = MARGIN - NAVEN_RISE;

        RenderUtil.drawBlurredRect(ctx.getPoseStack(), x, y, boxW, boxH, NAVEN_RADIUS,
                SIMPLE_BLUR_STRENGTH, SIMPLE_BLUR_OPACITY, 0);

        try (Paint paint = new Paint()) {
            paint.setColor(NAVEN_BODY_COLOR);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, boxW, boxH, NAVEN_RADIUS), paint);
            // Red header: same rounded rect, clipped to the top strip, so the bar follows the
            // panel's top corners instead of squaring them off.
            ctx.save();
            ctx.clip(Rectangle.ofXYWH(x, y, boxW, NAVEN_HEADER_H));
            paint.setColor(NAVEN_HEADER_COLOR);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, boxW, boxH, NAVEN_RADIUS), paint);
            ctx.restore();
        }

        // DrawContext.drawString treats y as "text top + ascent()", and ascent() is negative here,
        // so the offset has to add it back; without this the text floats above the panel.
        float textX = x + NAVEN_PAD_X;
        float textY = y + NAVEN_TEXT_TOP - metrics.ascent() + 1.0f - NAVEN_TEXT_RISE;
        try (Paint paint = new Paint()) {
            paint.setColor(NAVEN_TEXT_SHADOW);
            ctx.drawString(text, textX + 0.5f, textY + 0.5f, font, paint);
            paint.setColor(NAVEN_TEXT_COLOR);
            ctx.drawString(text, textX, textY, font, paint);
        }
    }

    private void renderSimple(DrawContext ctx) {
        if (mc.player == null) return;

        String clientName = NiloreClient.CLIENT_NAME;
        String version = NiloreClient.VERSION;
        String fpsText = mc.getFps() + "FPS";
        String separator = " | ";

        ModuleListHud moduleList = NiloreClient.getInstance().getHudManager() != null
                ? NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class)
                : null;

        float nameW = textFont.getWidth(clientName);
        float separatorW = textFont.getWidth(separator);
        float versionW = textFont.getWidth(version);
        float fpsW = textFont.getWidth(fpsText);

        float lineH = textFont.getMetrics().height();
        float boxW = SIMPLE_PAD_X * 2 + nameW + separatorW * 2 + versionW + fpsW;
        float boxH = lineH + SIMPLE_PAD_Y * 2;
        float x = MARGIN;
        float y = MARGIN;

        // Frosted glass background
        RenderUtil.drawBlurredRect(ctx.getPoseStack(), x, y, boxW, boxH, SIMPLE_RADIUS,
                SIMPLE_BLUR_STRENGTH, SIMPLE_BLUR_OPACITY, 0);

        // 60 alpha dark tint over the blur
        try (Paint paint = new Paint()) {
            paint.setColor(0x3C000000);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, boxW, boxH, SIMPLE_RADIUS), paint);
        }

        float textY = y + (boxH - lineH) / 2.0f + 11.5f;
        float cursorX = x + SIMPLE_PAD_X;

        // Client name follows the ModuleList theme; it is the one part without a text shadow.
        int nameLength = clientName.length();
        for (int i = 0; i < nameLength; i++) {
            String ch = String.valueOf(clientName.charAt(i));
            float t = nameLength <= 1 ? 0.0f : i / (float) (nameLength - 1);
            int color = moduleList != null
                    ? moduleList.getThemeColor(i, t, nameLength - 1)
                    : TEXT_COLOR;
            try (Paint paint = new Paint()) {
                paint.setColor(color);
                ctx.drawString(ch, cursorX, textY, textFont, paint);
            }
            cursorX += textFont.getWidth(ch);
        }

        cursorX = this.drawSimpleText(ctx, separator, cursorX, textY, SEPARATOR_COLOR);
        cursorX = this.drawSimpleText(ctx, version, cursorX, textY, TEXT_COLOR);
        cursorX = this.drawSimpleText(ctx, separator, cursorX, textY, SEPARATOR_COLOR);
        this.drawSimpleText(ctx, fpsText, cursorX, textY, TEXT_COLOR);
    }

    private float drawSimpleText(DrawContext ctx, String text, float x, float y, int color) {
        try (Paint paint = new Paint()) {
            paint.setColor(TEXT_SHADOW_COLOR);
            ctx.drawString(text, x + 0.5f, y + 0.5f, textFont, paint);
            paint.setColor(color);
            ctx.drawString(text, x, y, textFont, paint);
        }
        return x + textFont.getWidth(text);
    }

    private void renderPharos(DrawContext ctx) {
        if (mc.player == null) return;

        Paint textPaint = new Paint().setColor(0xFFFFFFFF);
        Paint fpsPaint = new Paint().setColor(0xFFFFFFFB);

        float padX = 6.0f;
        float padY = 4.0f;
        float baseX = MARGIN - 5f;
        float baseY = MARGIN;

        float titleX = baseX + padX;
        float titleY = baseY + padY + titleFont.getMetrics().capHeight();
        ctx.drawString(NiloreClient.CLIENT_NAME, titleX, titleY, titleFont, textPaint);

        String fpsStr = String.valueOf(mc.getFps());
        float fpsX = baseX + padX;
        float fpsY = titleY + titleFont.getMetrics().capHeight() - 7.0f + fpsFont.getMetrics().capHeight();
        ctx.drawString(fpsStr, fpsX, fpsY, fpsFont, fpsPaint);
    }

    // Exhibition style: top-left "Nilore [11fps][1ms]" using the vanilla font.
    // "Nilore" is drawn per-character with a left→right gradient, the
    // [fps][ms] brackets are grey.
    private void renderExhibition(Render2DEvent event) {
        if (mc.player == null) return;
        GuiGraphics gui = event.guiGraphics();

        ModuleListHud moduleList = NiloreClient.getInstance().getHudManager() != null
                ? NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class)
                : null;

        String name = NiloreClient.CLIENT_NAME;
        String fpsText = mc.getFps() + "fps";
        int ping = PingTracker.getPing();
        String pingText = ping + "ms";

        int x = (int) MARGIN;
        int y = (int) MARGIN;
        // "Nilore" — each char takes its own ModuleList theme color (the same
        // per-row gradient the ModuleList uses), so the name shows a clear
        // left→right gradient instead of a lerp between two nearby colors.
        int cursor = x;
        int len = name.length();
        for (int i = 0; i < len; i++) {
            String ch = String.valueOf(name.charAt(i));
            float t = len <= 1 ? 0.0f : i / (float) (len - 1);
            int color = moduleList != null
                    ? moduleList.getThemeColor(i, t, len - 1)
                    : this.lerpColor(0xFFFF8A00, 0xFFFF4EC5, t);
            gui.drawString(mc.font, ch, cursor, y, color);
            cursor += mc.font.width(ch);
        }
        // "[110fps][1ms]" — brackets grey, numbers/units white
        int bracketColor = 0xFF9E9E9E;
        int contentColor = 0xFFFFFFFF;
        gui.drawString(mc.font, " ", cursor, y, bracketColor);
        cursor += mc.font.width(" ");
        boolean showFps = this.showFpsSetting.getValue();
        boolean showMs = this.showMsSetting.getValue() && ping >= 0;
        if (showFps) {
            gui.drawString(mc.font, "[", cursor, y, bracketColor);
            cursor += mc.font.width("[");
            gui.drawString(mc.font, fpsText, cursor, y, contentColor);
            cursor += mc.font.width(fpsText);
        }
        if (showFps && showMs) {
            gui.drawString(mc.font, "][", cursor, y, bracketColor);
            cursor += mc.font.width("][");
        } else if (showFps) {
            gui.drawString(mc.font, "]", cursor, y, bracketColor);
        }
        if (showMs) {
            if (!showFps) {
                gui.drawString(mc.font, "[", cursor, y, bracketColor);
                cursor += mc.font.width("[");
            }
            gui.drawString(mc.font, pingText, cursor, y, contentColor);
            cursor += mc.font.width(pingText);
            gui.drawString(mc.font, "]", cursor, y, bracketColor);
        }
    }

    private static int rgb(NumberSetting red, NumberSetting green, NumberSetting blue) {
        return 0xFF000000 | red.getValue().intValue() << 16 | green.getValue().intValue() << 8 | blue.getValue().intValue();
    }

    private int lerpColor(int from, int to, float t) {
        int a = (int) ((from >>> 24 & 0xFF) + ((to >>> 24 & 0xFF) - (from >>> 24 & 0xFF)) * t);
        int r = (int) ((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = (int) ((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private int getPing() {
        return PingTracker.getPing();
    }
}
