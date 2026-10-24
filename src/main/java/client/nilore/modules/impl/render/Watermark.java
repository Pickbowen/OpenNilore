package client.nilore.modules.impl.render;

import client.nilore.NiloreClient;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.hud.DynamicIsland;
import client.nilore.hud.LogoWatermark;
import client.nilore.hud.ModuleListHud;
import client.nilore.hud.NeverloseWatermark;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.network.PingTracker;
import client.nilore.render.DrawContext;
import client.nilore.render.FontRenderer;
import client.nilore.render.FontPresets;
import client.nilore.render.Fonts;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.event.EventTarget;
import client.nilore.utils.render.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;

public class Watermark extends Module {
    final ModeSetting styleSetting = new ModeSetting("Style", "Neverlose", "DynamicIsland", "Simple", "Pharos", "Exhibition", "Logo").withDefault("Exhibition");
    private final BooleanSetting showFpsSetting = new BooleanSetting("Show FPS", true, () -> this.styleSetting.is("Exhibition"));
    private final BooleanSetting showMsSetting = new BooleanSetting("Show MS", true, () -> this.styleSetting.is("Exhibition"));
    private final DynamicIsland dynamicIsland = new DynamicIsland();
    private final LogoWatermark logoWatermark = new LogoWatermark();
    private final NeverloseWatermark neverloseWatermark = new NeverloseWatermark();

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
        // PingTracker 已经扣掉了本地压 keep-alive 造成的持有时长; 没有可信读数时返回 -1,
        // 这时候宁可不显示毫秒字段, 也不能显示一个凭空的 0ms。
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
