package shit.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import shit.nilore.ClientBase;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.NiloreClient;

public class NeverloseWatermark {
    private final FontRenderer boldFont = FontPresets.museoSans(18.0f);
    private final FontRenderer regularFont = FontPresets.pingfang(13.0f);
    private final FontRenderer smallFont = FontPresets.pingfang(13.0f);
    private final FontRenderer tinyFont = FontPresets.materialIcons(16.0f);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
    private final float logoWidth = 5.0f;
    private final Paint backgroundPaint = new Paint().setColor(ColorUtil.fromARGB(36, 36, 36, 120));
    private final Paint textPaint = new Paint().setColor(-1);
    private final Paint accentPaint = new Paint().setColor(ColorUtil.fromRGB(42, 180, 255));

    public void onRender2D(Render2DEvent render2DEvent) {
        if (ClientBase.mc.options.renderDebug) {
            return;
        }
        float screenWidth = ClientBase.mc.getWindow().getGuiScaledWidth();
        float currentX = screenWidth / 2.0f - this.getTotalWidth() / 2.0f;
        float y = 9.5f;
        float gap = 6.0f;
        float cornerRadius = 4.5f;
        float blurRadius = 15.0f;
        float nameWidth = this.measureText(NiloreClient.CLIENT_NAME_UPPER, this.boldFont);
        currentX += nameWidth + gap;
        currentX = this.renderSectionLegacy(render2DEvent.poseStack(), currentX, y, this.getServerName(), this.smallFont, "\uE8A6", cornerRadius, blurRadius, gap);
        currentX = this.renderSectionLegacy(render2DEvent.poseStack(), currentX, y, this.getPingText(), this.regularFont, "\uE8B8", cornerRadius, blurRadius, gap);
        currentX = this.renderSectionLegacy(render2DEvent.poseStack(), currentX, y, this.getFpsText(), this.regularFont, "\uEBCA", cornerRadius, blurRadius, gap);
        currentX = this.renderSectionLegacy(render2DEvent.poseStack(), currentX, y, this.getTimeText(), this.regularFont, "\uEB66", cornerRadius, blurRadius, gap);
        currentX = this.renderSectionLegacy(render2DEvent.poseStack(), currentX, y, this.getCpsText(), this.regularFont, "\uE0C8", cornerRadius, blurRadius, gap);
        this.renderSectionLegacy(render2DEvent.poseStack(), currentX, y, this.getCoordText(), this.regularFont, "\uE192", cornerRadius, blurRadius, gap);
    }

    private float renderSectionLegacy(PoseStack poseStack, float x, float y, String text, FontRenderer fontRenderer, String icon, float cornerRadius, float blurRadius, float gap) {
        float sectionWidth = this.measureTextWithSub(text, fontRenderer, icon);
        return x + sectionWidth + gap;
    }

    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (ClientBase.mc.options.renderDebug) {
            return;
        }
        float screenWidth = ClientBase.mc.getWindow().getGuiScaledWidth();
        float currentX = screenWidth / 2.0f - this.getTotalWidth() / 2.0f;
        float y = 10.0f;
        float gap = 6.0f;
        float cornerRadius = 4.5f;
        currentX = this.renderSection(glRenderEvent.drawContext(), currentX, y, NiloreClient.CLIENT_NAME_UPPER, this.boldFont, cornerRadius, gap);
        currentX = this.renderSectionWithSub(glRenderEvent.drawContext(), currentX, y, this.getServerName(), this.smallFont, "", cornerRadius, gap);
        currentX = this.renderSectionWithSub(glRenderEvent.drawContext(), currentX, y, this.getPingText(), this.regularFont, "", cornerRadius, gap);
        currentX = this.renderSectionWithSub(glRenderEvent.drawContext(), currentX, y, this.getFpsText(), this.regularFont, "", cornerRadius, gap);
        currentX = this.renderSectionWithSub(glRenderEvent.drawContext(), currentX, y, this.getTimeText(), this.regularFont, "", cornerRadius, gap);
        currentX = this.renderSectionWithSub(glRenderEvent.drawContext(), currentX, y, this.getCpsText(), this.regularFont, "", cornerRadius, gap);
        this.renderSectionWithSub(glRenderEvent.drawContext(), currentX, y, this.getCoordText(), this.regularFont, "", cornerRadius, gap);
    }

    private float renderSection(DrawContext drawContext, float x, float y, String text, FontRenderer fontRenderer, float cornerRadius, float gap) {
        float padding = 8.0f;
        float boxHeight = (float)GlHelper.getFontAscent(this.boldFont) + 12.0f - 2.0f;
        float textWidth = GlHelper.getStringWidth(text, fontRenderer);
        float boxWidth = padding + textWidth + padding - 5.0f;
        GlHelper.drawRoundedRect(x, y, boxWidth, boxHeight, cornerRadius, this.backgroundPaint);
        float textY = y + (boxHeight - (float)GlHelper.getFontAscent(fontRenderer)) / 2.0f + 3.5f;
        GlHelper.drawTextShadowLegacy(text, x + padding - 2.0f, textY, fontRenderer, this.textPaint.getColor());
        return x + boxWidth + gap;
    }

    private float renderSectionWithSub(DrawContext drawContext, float x, float y, String text, FontRenderer fontRenderer, String icon, float cornerRadius, float gap) {
        float padding = 8.0f;
        float boxHeight = (float)GlHelper.getFontAscent(fontRenderer) + 12.0f;
        float textWidth = GlHelper.getStringWidth(text, fontRenderer);
        float iconWidth = GlHelper.getStringWidth(icon, this.tinyFont);
        float boxWidth = padding + iconWidth + 5.0f + textWidth + padding - 4.0f;
        GlHelper.drawRoundedRect(x, y, boxWidth, boxHeight, cornerRadius, this.backgroundPaint);
        float iconY = y + (boxHeight - (float)GlHelper.getFontAscent(this.tinyFont)) / 2.0f + 3.0f;
        GlHelper.drawTextShadowLegacy(icon, x + padding - 1.0f, iconY, this.tinyFont, this.accentPaint.getColor());
        float textY = y + (boxHeight - (float)GlHelper.getFontAscent(fontRenderer)) / 2.0f + 1.0f;
        GlHelper.drawTextShadowLegacy(text, x + padding + iconWidth + 5.0f - 3.0f, textY, fontRenderer, this.textPaint.getColor());
        return x + boxWidth + gap;
    }

    private float measureText(String text, FontRenderer fontRenderer) {
        float padding = 8.0f;
        return padding + GlHelper.getStringWidth(text, fontRenderer) + padding - 5.0f;
    }

    private float getTotalWidth() {
        float total = 0.0f;
        float gap = 6.0f;
        total += this.measureText(NiloreClient.CLIENT_NAME_UPPER, this.boldFont) + gap;
        total += this.measureTextWithSub(this.getServerName(), this.smallFont, "") + gap;
        total += this.measureTextWithSub(this.getPingText(), this.regularFont, "") + gap;
        total += this.measureTextWithSub(this.getFpsText(), this.regularFont, "") + gap;
        total += this.measureTextWithSub(this.getTimeText(), this.regularFont, "") + gap;
        total += this.measureTextWithSub(this.getCpsText(), this.regularFont, "") + gap;
        return total += this.measureTextWithSub(this.getCoordText(), this.regularFont, "");
    }

    private float measureTextWithSub(String text, FontRenderer fontRenderer, String icon) {
        float padding = 8.0f;
        return padding + GlHelper.getStringWidth(icon, this.tinyFont) + 5.0f + GlHelper.getStringWidth(text, fontRenderer) + padding - 4.0f;
    }

    private String getServerName() {
        return ClientBase.mc.player != null ? ClientBase.mc.player.getGameProfile().getName() : "Player";
    }

    private String getPingText() {
        return "Default Config";
    }

    private String getFpsText() {
        if (ClientBase.mc.player == null || ClientBase.mc.player.connection == null) {
            return "0ms";
        }
        PlayerInfo playerInfo = ClientBase.mc.player.connection.getPlayerInfo(ClientBase.mc.player.getUUID());
        if (playerInfo == null) {
            return "0ms";
        }
        return playerInfo.getLatency() + "ms";
    }

    private String getTimeText() {
        return ClientBase.mc.getFps() + "fps";
    }

    private String getCpsText() {
        ServerData serverData = ClientBase.mc.getCurrentServer();
        return serverData != null ? serverData.ip : "Singleplayer";
    }

    private String getCoordText() {
        return this.timeFormat.format(new Date());
    }
}
