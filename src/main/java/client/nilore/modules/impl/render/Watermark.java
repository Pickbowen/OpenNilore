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
import client.nilore.render.DrawContext;
import client.nilore.render.FontRenderer;
import client.nilore.render.FontPresets;
import client.nilore.render.Fonts;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.event.EventTarget;
import client.nilore.utils.render.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

public class Watermark extends Module {
    final ModeSetting styleSetting = new ModeSetting("Style", "Neverlose", "DynamicIsland", "Simple", "Pharos", "Exhibition", "Logo").withDefault("DynamicIsland");
    private final BooleanSetting showFpsSetting = new BooleanSetting("Show FPS", true, () -> this.styleSetting.is("Exhibition"));
    private final BooleanSetting showMsSetting = new BooleanSetting("Show MS", true, () -> this.styleSetting.is("Exhibition"));
    private final NumberSetting bgAlpha = new NumberSetting("BG Alpha", 160, 0, 255, 1, () -> this.styleSetting.is("Simple"));
    private final BooleanSetting glow = new BooleanSetting("Glow", false, () -> this.styleSetting.is("Simple"));
    private final NumberSetting glowRadius = new NumberSetting("Glow Radius", 12, 4, 40, 1, () -> this.styleSetting.is("Simple") && this.glow.getValue());
    private final NumberSetting glowAlpha = new NumberSetting("Glow Alpha", 120, 0, 255, 1, () -> this.styleSetting.is("Simple") && this.glow.getValue());
    private final DynamicIsland dynamicIsland = new DynamicIsland();
    private final LogoWatermark logoWatermark = new LogoWatermark();
    private final NeverloseWatermark neverloseWatermark = new NeverloseWatermark();

    // Pharos style (original Simple - client name + fps)
    private static final FontRenderer titleFont = Fonts.getRenderer("quicksand.ttf", 36.0f);
    private static final FontRenderer fpsFont = Fonts.getRenderer("quicksand.ttf", 20.0f);
    private static final float MARGIN = 8.0f;

    // Simple capsule style (centered)
    private static final FontRenderer iconFont = FontPresets.niloreIcon(24.0f);
    private static final FontRenderer textFont = FontPresets.pingfang(24.0f);
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int INFO_COLOR = 0xCCFFFFFF;
    private static final float ICON_SIZE = 20.0f;
    private static final float PAD = 3.0f;
    private static final int BG_COLOR_BASE = 0x0A0A0A;

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

        String icon = "N";
        String clientName = NiloreClient.CLIENT_NAME;
        String userName = mc.player.getGameProfile().getName();
        String fpsText = mc.getFps() + "fps";
        String pingText = getPing() + "ms";
        String infoText = " | " + userName + " | " + fpsText + " | " + pingText;

        float iconW = iconFont.getWidth(icon);
        float nameW = textFont.getWidth(clientName);
        float infoW = textFont.getWidth(infoText);

        float contentW = iconW + PAD + nameW + infoW;
        float lineH = Math.max(ICON_SIZE, textFont.getMetrics().height());
        float boxH = lineH + PAD * 2;
        float boxW = contentW + PAD * 2 + 6f;

        float screenW = mc.getWindow().getGuiScaledWidth();
        float screenH = mc.getWindow().getGuiScaledHeight();
        float x = (screenW - boxW) / 2;
        float y = screenH / 20;

        // Capsule background (full pill shape)
        float radius = boxH / 2;
        int bgColor = BG_COLOR_BASE | (bgAlpha.getValue().intValue() << 24);
        RoundedRectangle capsule = RoundedRectangle.ofXYWHR(x, y, boxW, boxH, radius);
        ctx.drawRoundedRect(capsule, new Paint().setColor(bgColor));

        // Glow behind background (like Armor module)
        if (glow.getValue()) {
            int gRadius = this.glowRadius.getValue().intValue();
            int gAlpha = this.glowAlpha.getValue().intValue();
            if (gAlpha > 0 && gRadius > 0) {
                RenderUtil.drawShadow(ctx.getPoseStack(), x, y, boxW, boxH, gRadius, (gAlpha << 24) | 0x000000);
                RenderUtil.enableBlend();
            }
        }

        float cursorX = x + PAD + 3f;
        float textY = y + PAD + (lineH - iconFont.getMetrics().height()) / 2 + 13f;

        // Icon "N"
        ctx.drawString(icon, cursorX, textY, iconFont, new Paint().setColor(TEXT_COLOR));
        cursorX += iconW + PAD;

        // Client name "Nilore"
        float nameY = y + PAD + (lineH - textFont.getMetrics().height()) / 2 + 10f;
        ctx.drawString(clientName, cursorX, nameY, textFont, new Paint().setColor(TEXT_COLOR));
        cursorX += nameW;

        // Info text (username | fps | ping) in dimmer white
        float infoY = y + PAD + (lineH - textFont.getMetrics().height()) / 2 + 10f;
        ctx.drawString(infoText, cursorX, infoY, textFont, new Paint().setColor(INFO_COLOR));
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
        String pingText = getPing() + "ms";

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
        boolean showMs = this.showMsSetting.getValue();
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
        if (mc.player == null || mc.player.connection == null) return 0;
        PlayerInfo playerInfo = mc.player.connection.getPlayerInfo(mc.player.getUUID());
        return playerInfo != null ? playerInfo.getLatency() : 0;
    }
}
