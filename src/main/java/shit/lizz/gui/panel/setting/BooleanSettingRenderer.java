package shit.lizz.gui.panel.setting;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import shit.lizz.ClientBase;
import shit.lizz.gui.PanelClickGui;
import shit.lizz.render.FontPresets;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.TextGlow;
import shit.lizz.settings.Setting;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.utils.math.LerpUtil;
import shit.lizz.utils.render.RenderUtil;

public class BooleanSettingRenderer
extends ClientBase
implements SettingRenderer {
    private static final int COLOR_ON = new Color(76, 175, 80, 180).getRGB();
    private static final int COLOR_OFF = new Color(158, 158, 158, 150).getRGB();
    private final Map<BooleanSetting, Boolean> hoverStates = new HashMap<>();
    private final Map<BooleanSetting, Float> toggleAnimations = new HashMap<>();
    private final Map<BooleanSetting, Float> hoverAnimations = new HashMap<>();

    @Override
    public int render(GuiGraphics guiGraphics, Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, float alpha, float scale) {
        int toggleHeight;
        if (!(setting instanceof BooleanSetting booleanSetting)) {
            return 0;
        }
        int rowHeight = Math.round(24.0f * scale);
        int toggleH = toggleHeight = Math.round(10.0f * scale);
        int rightPadding = Math.round(5.0f * scale);
        float toggleAnim = this.toggleAnimations.getOrDefault(booleanSetting, booleanSetting.getValue() != false ? 1.0f : 0.0f).floatValue();
        float toggleTarget = booleanSetting.getValue() != false ? 1.0f : 0.0f;
        toggleAnim = Math.abs(toggleTarget - toggleAnim) > 0.01f ? LerpUtil.smoothLerp(toggleAnim, toggleTarget, 0.25f) : toggleTarget;
        this.toggleAnimations.put(booleanSetting, toggleAnim);
        int toggleWidth = toggleHeight * 2;
        int toggleX = x + width - toggleWidth - rightPadding;
        int toggleY = y + (rowHeight - toggleH) / 2;
        this.updateHoverState(booleanSetting, toggleX, toggleY, mouseX, mouseY, toggleHeight);
        float hoverAnim = this.hoverAnimations.getOrDefault(booleanSetting, 0.0f).floatValue();
        float hoverTarget = this.hoverStates.getOrDefault(booleanSetting, false) != false ? 1.0f : 0.0f;
        hoverAnim = Math.abs(hoverTarget - hoverAnim) > 0.01f ? LerpUtil.smoothLerp(hoverAnim, hoverTarget, 0.3f) : hoverTarget;
        this.hoverAnimations.put(booleanSetting, hoverAnim);
        FontRenderer nameFont = FontPresets.axiformaRegular(14.0f * scale);
        float nameY = (float)y + (float)rowHeight / 2.0f - nameFont.getMetrics().capHeight() / 2.0f;
        TextGlow.drawGlowText(booleanSetting.getName(), x, nameY, nameFont, this.applyAlpha(-1, alpha), this.applyAlpha(new Color(255, 255, 255, 120).getRGB(), alpha), 6.0f * scale);
        this.drawToggle(guiGraphics, toggleX, toggleY, toggleAnim, hoverAnim, alpha, scale);
        return rowHeight;
    }

    private int applyAlpha(int color, float alpha) {
        int origAlpha = color >> 24 & 0xFF;
        int newAlpha = (int)((float)origAlpha * alpha);
        return newAlpha << 24 | color & 0xFFFFFF;
    }

    private void drawToggle(GuiGraphics guiGraphics, int toggleX, int toggleY, float onFactor, float hoverFactor, float alpha, float scale) {
        int unit = Math.round(10.0f * scale);
        int toggleWidth = unit * 2;
        int toggleHeight = unit;
        int trackColor = this.lerpColor(COLOR_OFF, COLOR_ON, onFactor);
        if (hoverFactor > 0.0f) {
            float brightness = 1.0f + 0.3f * hoverFactor;
            trackColor = this.brightenColor(trackColor, brightness);
        }
        if (onFactor > 0.01f) {
            int overlayColor = new Color(76, 175, 80, (int)(70.0f * onFactor)).getRGB();
            RenderUtil.drawRoundedRect(guiGraphics.pose(), (float)toggleX - scale, (float)toggleY - scale, (float)toggleWidth + 2.0f * scale, (float)toggleHeight + 2.0f * scale, (float)toggleHeight / 2.0f, this.applyAlpha(overlayColor, alpha));
        }
        RenderUtil.drawRoundedRect(guiGraphics.pose(), toggleX, toggleY, toggleWidth, toggleHeight, (float)toggleHeight / 2.0f, this.applyAlpha(trackColor, alpha));
        int knobInset = Math.round(2.0f * scale);
        int knobSize = toggleHeight - knobInset * 2;
        int knobX = toggleX + knobInset + Math.round((float)(toggleWidth - knobSize - knobInset * 2) * onFactor);
        int knobY = toggleY + knobInset;
        int knobColor = -1;
        if (hoverFactor > 0.0f) {
            int highlightAlpha = (int)(50.0f * hoverFactor);
            int highlightColor = highlightAlpha << 24 | 0xFFFFFF;
            RenderUtil.drawRoundedRect(guiGraphics.pose(), knobX - 1, knobY - 1, knobSize + 2, knobSize + 2, (float)(knobSize + 2) / 2.0f, this.applyAlpha(highlightColor, alpha));
        }
        RenderUtil.drawRoundedRect(guiGraphics.pose(), knobX, knobY, knobSize, knobSize, (float)knobSize / 2.0f, this.applyAlpha(knobColor, alpha));
    }

    private void updateHoverState(BooleanSetting booleanSetting, int toggleX, int toggleY, int mouseX, int mouseY, int toggleHeight) {
        boolean hovered = mouseX >= toggleX && mouseX <= toggleX + toggleHeight * 2 && mouseY >= toggleY && mouseY <= toggleY + toggleHeight;
        this.hoverStates.put(booleanSetting, hovered);
    }

    private int brightenColor(int color, float factor) {
        int a = color >> 24 & 0xFF;
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        r = Math.min(255, (int)((float)r * factor));
        g = Math.min(255, (int)((float)g * factor));
        b = Math.min(255, (int)((float)b * factor));
        return a << 24 | r << 16 | g << 8 | b;
    }

    private int lerpColor(int fromColor, int toColor, float t) {
        int aFrom = fromColor >> 24 & 0xFF;
        int rFrom = fromColor >> 16 & 0xFF;
        int gFrom = fromColor >> 8 & 0xFF;
        int bFrom = fromColor & 0xFF;
        int aTo = toColor >> 24 & 0xFF;
        int rTo = toColor >> 16 & 0xFF;
        int gTo = toColor >> 8 & 0xFF;
        int bTo = toColor & 0xFF;
        int a = (int)((float)aFrom + (float)(aTo - aFrom) * t);
        int r = (int)((float)rFrom + (float)(rTo - rFrom) * t);
        int g = (int)((float)gFrom + (float)(gTo - gFrom) * t);
        int b = (int)((float)bFrom + (float)(bTo - bFrom) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    @Override
    public boolean onClick(Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, int button, float scale) {
        int toggleHeight;
        if (!(setting instanceof BooleanSetting booleanSetting)) {
            return false;
        }
        int rowHeight = Math.round(24.0f * scale);
        int toggleH = toggleHeight = Math.round(10.0f * scale);
        int rightPadding = Math.round(5.0f * scale);
        int toggleWidth = toggleHeight * 2;
        int toggleX = x + width - toggleWidth - rightPadding;
        int toggleY = y + (rowHeight - toggleH) / 2;
        if (button == 0 && mouseX >= toggleX && mouseX <= toggleX + toggleWidth && mouseY >= toggleY && mouseY <= toggleY + toggleH) {
            boolean newValue = booleanSetting.getValue() == false;
            booleanSetting.setValue(newValue);
            String stateLabel = newValue ? "On" : "Off";
            PanelClickGui.panelClickGui.addToast(booleanSetting.getName() + " is " + stateLabel);
            return true;
        }
        return false;
    }

    @Override
    public boolean supports(Setting<?> setting) {
        return setting instanceof BooleanSetting;
    }

    @Override
    public int getHeight(Setting<?> setting, float scale) {
        return Math.round(24.0f * scale);
    }

    @Override
    public void onMouseRelease(double mouseX, double mouseY, int button) {
    }
}
