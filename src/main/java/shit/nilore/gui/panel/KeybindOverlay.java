package shit.nilore.gui.panel;

import java.awt.Color;

import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.gui.PanelClickGui;
import shit.nilore.modules.KeyBind;
import shit.nilore.modules.Module;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Renderer;
import shit.nilore.render.TextGlow;
import shit.nilore.utils.math.LerpUtil;
import shit.nilore.utils.render.RenderUtil;

public class KeybindOverlay
extends ClientBase {
    private static final Color OVERLAY_BG_COLOR = new Color(124, 124, 124, 13);
    private boolean isActive = false;
    private Module targetModule = null;
    private float alpha = 0.0f;
    private long startTime = 0L;

    public void startBinding(Module module) {
        this.targetModule = module;
        this.isActive = true;
        this.startTime = System.currentTimeMillis();
    }

    public void cancel() {
        this.isActive = false;
        this.targetModule = null;
    }

    public boolean isVisible() {
        return this.isActive && this.alpha > 0.01f;
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

    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!this.isVisible()) {
            return false;
        }
        if (keyCode == 256) {
            if (this.targetModule != null) {
                this.targetModule.setKey(-1);
                if (NiloreClient.isReady()) {
                    NiloreClient.instance.getConfigManager().saveAll();
                }
                PanelClickGui.panelClickGui.addToast(this.targetModule.getName() + " keybind cleared");
            }
            this.cancel();
            return true;
        }
        if (this.targetModule != null && keyCode != -1) {
            this.targetModule.setKey(keyCode);
            if (NiloreClient.isReady()) {
                NiloreClient.instance.getConfigManager().saveAll();
            }
            KeyBind keyBind = new KeyBind(keyCode);
            String keyName = keyBind.getName();
            PanelClickGui.panelClickGui.addToast(this.targetModule.getName() + " bound to " + keyName.toUpperCase());
            this.cancel();
            return true;
        }
        return false;
    }

    private void updateAlpha() {
        this.alpha = this.isActive ? LerpUtil.lerp(this.alpha, 1.0f, 0.08f) : LerpUtil.lerp(this.alpha, 0.0f, 0.08f);
    }

    private void onRenderExtra() {
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
            int textColor;
            float textY;
            float textX;
            float textWidth;
            String text;
            FontRenderer textFont;
            int alphaByte = (int)(255.0f * this.alpha);
            FontRenderer titleFont = FontPresets.axiformaBold(24.0f * scale);
            String title = "KEYBIND";
            float titleWidth = GlHelper.getStringWidth(title, titleFont);
            float titleX = (float)boxX + (boxWidth - titleWidth) / 2.0f;
            float titleY = (float)boxY + 45.0f * scale;
            int titleColor = alphaByte << 24 | 0xFFFFFF;
            int glowColor = alphaByte << 24 | 0xFFFFFF;
            TextGlow.drawGlowText(title, titleX, titleY, titleFont, titleColor, glowColor, 10.0f * scale);
            if (this.targetModule != null) {
                textFont = FontPresets.axiformaRegular(18.0f * scale);
                text = "Module: " + this.targetModule.getName();
                textWidth = GlHelper.getStringWidth(text, textFont);
                textX = (float)boxX + (boxWidth - textWidth) / 2.0f;
                textY = (float)boxY + 75.0f * scale;
                textColor = alphaByte << 24 | 0xFFFFFF;
                GlHelper.drawText(text, textX, textY, textFont, textColor);
            }
            textFont = FontPresets.axiformaRegular(16.0f * scale);
            text = "Press any key to bind";
            textWidth = GlHelper.getStringWidth(text, textFont);
            textX = (float)boxX + (boxWidth - textWidth) / 2.0f;
            textY = (float)boxY + 105.0f * scale;
            textColor = alphaByte << 24 | 0xCCCCCC;
            GlHelper.drawText(text, textX, textY, textFont, textColor);
            this.drawAnimatedDots(boxX, (int)((float)boxY + 125.0f * scale), (int)boxWidth, alphaByte, scale);
            FontRenderer cancelFont = FontPresets.axiformaRegular(14.0f * scale);
            String cancelText = "Press ESC to cancel";
            float cancelWidth = GlHelper.getStringWidth(cancelText, cancelFont);
            float cancelX = (float)boxX + (boxWidth - cancelWidth) / 2.0f;
            float cancelY = (float)boxY + 155.0f * scale;
            int cancelColor = alphaByte << 24 | 0xCCCCCC;
            GlHelper.drawText(cancelText, cancelX, cancelY, cancelFont, cancelColor);
        }));
    }

    private void drawAnimatedDots(int boxX, int dotsY, int boxWidth, int alphaByte, float scale) {
        FontRenderer dotFont = FontPresets.axiformaBold(20.0f * scale);
        String dot = "•";
        float dotWidth = GlHelper.getStringWidth(dot, dotFont);
        float totalWidth = dotWidth * 3.0f + 20.0f * scale;
        float startX = (float)boxX + ((float)boxWidth - totalWidth) / 2.0f;
        int dotColor = alphaByte << 24 | 0xFFFFFF;
        long now = System.currentTimeMillis();
        long elapsed = now - this.startTime;
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

    public Module getTargetModule() {
        return this.targetModule;
    }

    static {
        new Color(255, 255, 255, 40);
    }
}
