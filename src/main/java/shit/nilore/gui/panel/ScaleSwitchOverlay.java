package shit.nilore.gui.panel;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.ClientBase;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Renderer;
import shit.nilore.render.TextGlow;
import shit.nilore.utils.math.LerpUtil;
import shit.nilore.utils.render.RenderUtil;

public class ScaleSwitchOverlay
extends ClientBase {
    private static final Color OVERLAY_BG_COLOR = new Color(124, 124, 124, 13);
    private boolean isActive = false;
    private float alpha = 0.0f;
    private long startTime = 0L;
    private float fromScale = 1.0f;
    private float toScale = 1.0f;

    public void show(float fromScale, float toScale) {
        this.fromScale = fromScale;
        this.toScale = toScale;
        this.isActive = true;
        this.startTime = System.currentTimeMillis();
    }

    public void hide() {
        this.isActive = false;
    }

    public boolean isShowing() {
        return this.isActive;
    }

    public boolean isFullyShown() {
        return this.isActive && this.alpha >= 1.0f;
    }

    public boolean isFullyHidden() {
        return !this.isActive && this.alpha <= 0.0f;
    }

    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight, float scale) {
        if (!this.isActive && this.alpha <= 0.005f) {
            return;
        }
        this.updateAlpha();
        if (this.alpha <= 0.005f) {
            return;
        }
        try {
            this.drawBackground(guiGraphics, screenWidth, screenHeight);
            float boxWidth = 400.0f * scale;
            float boxHeight = 180.0f * scale;
            int boxX = (int)(((float)screenWidth - boxWidth) / 2.0f);
            int boxY = (int)(((float)screenHeight - boxHeight) / 2.0f);
            this.drawGlow(guiGraphics, boxX, boxY, boxWidth, boxHeight, scale);
            this.drawContent(guiGraphics, boxX, boxY, boxWidth, scale);
        } catch (Exception exception) {
            // empty catch block
        }
    }

    private void updateAlpha() {
        this.alpha = this.isActive ? LerpUtil.lerp(this.alpha, 1.0f, 0.08f) : LerpUtil.lerp(this.alpha, 0.0f, 0.08f);
    }

    private void drawBackground(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        Color color = new Color(OVERLAY_BG_COLOR.getRed(), OVERLAY_BG_COLOR.getGreen(), OVERLAY_BG_COLOR.getBlue(), (int)((float)OVERLAY_BG_COLOR.getAlpha() * this.alpha));
        RenderUtil.drawRoundedRect(guiGraphics.pose(), 0.0f, 0.0f, screenWidth, screenHeight, 0.0f, color.getRGB());
    }

    private void drawGlow(GuiGraphics guiGraphics, int boxX, int boxY, float boxWidth, float boxHeight, float scale) {
        TextGlow.drawBackground(guiGraphics.pose(), boxX, boxY, boxWidth, boxHeight, 12.0f * scale, this.alpha);
    }

    private void drawContent(GuiGraphics guiGraphics, int boxX, int boxY, float boxWidth, float scale) {
        Renderer.renderConsumer((drawContext -> {
            int alphaByte = (int)(255.0f * this.alpha);
            if (alphaByte <= 0) {
                return;
            }
            FontRenderer titleFont = FontPresets.axiformaBold(24.0f * scale);
            String title = "Waiting";
            float titleWidth = GlHelper.getStringWidth(title, titleFont);
            float titleX = (float)boxX + (boxWidth - titleWidth) / 2.0f;
            float titleY = (float)boxY + 45.0f * scale;
            int titleColor = alphaByte << 24 | 0xFFFFFF;
            int glowColor = alphaByte << 24 | 0xFFFFFF;
            TextGlow.drawGlowText(title, titleX, titleY, titleFont, titleColor, glowColor, 10.0f * scale);
            FontRenderer descFont = FontPresets.axiformaRegular(18.0f * scale);
            String description = String.format(Locale.US, "Switching scale from %.0f%% to %.0f%%", new Object[]{this.fromScale * 100.0f, this.toScale * 100.0f});
            float descWidth = GlHelper.getStringWidth(description, descFont);
            float descX = (float)boxX + (boxWidth - descWidth) / 2.0f;
            float descY = (float)boxY + 75.0f * scale;
            int descColor = alphaByte << 24 | 0xCCCCCC;
            GlHelper.drawText(description, descX, descY, descFont, descColor);
            this.drawAnimatedDots(boxX, (int)((float)boxY + 115.0f * scale), (int)boxWidth, alphaByte, scale);
        }));
    }

    private void drawAnimatedDots(int boxX, int dotsY, int boxWidth, int alphaByte, float scale) {
        FontRenderer dotFont = FontPresets.axiformaBold(20.0f * scale);
        String dot = "•";
        float dotWidth = GlHelper.getStringWidth(dot, dotFont);
        float totalWidth = dotWidth * 3.0f + 20.0f * scale;
        float startX = (float)boxX + ((float)boxWidth - totalWidth) / 2.0f;
        int dotColor = alphaByte << 24 | 0xFFFFFF;
        long elapsed = System.currentTimeMillis() - this.startTime;
        long cycleTime = elapsed % 1400L;
        for (int i = 0; i < 3; ++i) {
            float drawY;
            float dotX = startX + (float)i * (dotWidth + 10.0f * scale);
            long dotStart = (long)i * 150L;
            long dotEnd = dotStart + 300L;
            float verticalOffset = 0.0f;
            if (cycleTime >= dotStart && cycleTime <= dotEnd) {
                drawY = (float)(cycleTime - dotStart) / 300.0f;
                float angle = drawY * (float)Math.PI;
                verticalOffset = (float)(Math.sin(angle) * 6.0 * (double)scale);
            }
            drawY = (float)dotsY - verticalOffset;
            GlHelper.drawText(dot, dotX, drawY, dotFont, dotColor);
        }
    }

    static {
        new Color(255, 255, 255, 40);
    }
}
