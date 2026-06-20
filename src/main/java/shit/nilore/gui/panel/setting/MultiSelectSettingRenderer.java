package shit.nilore.gui.panel.setting;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.ClientBase;
import shit.nilore.gui.PanelClickGui;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.TextGlow;
import shit.nilore.settings.Setting;
import shit.nilore.settings.impl.MultiSelectSetting;
import shit.nilore.utils.math.LerpUtil;
import shit.nilore.utils.render.RenderUtil;

public class MultiSelectSettingRenderer
extends ClientBase
implements SettingRenderer {
    private static final int COLOR_SELECTED = new Color(76, 175, 80).getRGB();
    private static final int COLOR_SELECTED_GLOW = new Color(76, 175, 80, 100).getRGB();
    private final Map<String, Float> hoverAnimations = new HashMap<>();
    private final Map<String, Float> selectedAnimations = new HashMap<>();

    @Override
    public int render(GuiGraphics guiGraphics, Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, float alpha, float scale) {
        if (!(setting instanceof MultiSelectSetting multiSelectSetting)) {
            return 0;
        }
        FontRenderer nameFont = FontPresets.axiformaRegular(14.0f * scale);
        float nameY = (float)y + 20.0f * scale / 2.0f - nameFont.getMetrics().capHeight() / 2.0f;
        TextGlow.drawGlowText(multiSelectSetting.getName(), x, nameY, nameFont, this.applyAlpha(-1, alpha), this.applyAlpha(new Color(255, 255, 255, 120).getRGB(), alpha), 8.0f * scale);
        int optionY = y + Math.round(20.0f * scale);
        int rowHeight = Math.round(20.0f * scale);
        int boxSize = Math.round(10.0f * scale);
        int rightPadding = Math.round(5.0f * scale);
        for (String option : multiSelectSetting.getOptions()) {
            int color;
            boolean selected = multiSelectSetting.isSelected(option);
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= optionY && mouseY <= optionY + rowHeight;
            this.updateAnimation(this.hoverAnimations, option, hovered, 0.28f);
            this.updateAnimation(this.selectedAnimations, option, selected, 0.25f);
            float hoverAmount = this.hoverAnimations.getOrDefault(option, 0.0f).floatValue();
            float selectedAmount = this.selectedAnimations.getOrDefault(option, 0.0f).floatValue();
            int boxX = x + width - boxSize - rightPadding;
            int boxY = optionY + (rowHeight - boxSize) / 2;
            if (selectedAmount > 0.01f) {
                color = this.applyAlpha(COLOR_SELECTED_GLOW, alpha * selectedAmount);
                RenderUtil.drawRoundedRect(guiGraphics.pose(), (float)boxX - scale, (float)boxY - scale, (float)boxSize + 2.0f * scale, (float)boxSize + 2.0f * scale, 2.0f * scale, color);
            }
            RenderUtil.drawRoundedRect(guiGraphics.pose(), boxX, boxY, boxSize, boxSize, 2.0f * scale, this.applyAlpha(-5592406, alpha));
            if ((double)selectedAmount > 0.01) {
                float inset = (1.0f - selectedAmount) * ((float)boxSize / 2.0f);
                RenderUtil.drawRoundedRect(guiGraphics.pose(), (float)boxX + inset, (float)boxY + inset, (float)boxSize - inset * 2.0f, (float)boxSize - inset * 2.0f, scale, this.applyAlpha(COLOR_SELECTED, alpha));
            }
            color = selected ? -1 : -5592406;
            int labelColor = this.lerpColor(color, -1, hoverAmount);
            FontRenderer labelFont = FontPresets.axiformaRegular(13.0f * scale);
            float labelY = (float)optionY + (float)rowHeight / 2.0f - labelFont.getMetrics().capHeight() / 2.0f;
            if (selectedAmount > 0.01f) {
                int glowColor = this.applyAlpha(COLOR_SELECTED_GLOW, alpha * selectedAmount);
                TextGlow.drawGlowText(option, x + rightPadding, labelY, labelFont, this.applyAlpha(labelColor, alpha), glowColor, 8.0f * scale * selectedAmount);
            } else {
                GlHelper.drawText(option, x + rightPadding, labelY, labelFont, this.applyAlpha(labelColor, alpha));
            }
            optionY += rowHeight;
        }
        return (multiSelectSetting.getOptions().size() + 1) * rowHeight;
    }

    private void updateAnimation(Map<String, Float> map, String key, boolean target, float speed) {
        float current = map.getOrDefault(key, 0.0f).floatValue();
        float targetValue = target ? 1.0f : 0.0f;
        current = Math.abs(targetValue - current) > 0.01f ? LerpUtil.smoothLerp(current, targetValue, speed) : targetValue;
        map.put(key, current);
    }

    @Override
    public boolean onClick(Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, int button, float scale) {
        if (!(setting instanceof MultiSelectSetting multiSelectSetting) || button != 0) {
            return false;
        }
        int optionY = y + Math.round(20.0f * scale);
        int rowHeight = Math.round(20.0f * scale);
        for (String option : multiSelectSetting.getOptions()) {
            if (mouseX >= x && mouseX <= x + width && mouseY >= optionY && mouseY <= optionY + rowHeight) {
                if (multiSelectSetting.isSelected(option)) {
                    multiSelectSetting.getValue().remove(option);
                } else {
                    multiSelectSetting.getValue().add(option);
                }
                boolean nowSelected = multiSelectSetting.isSelected(option);
                PanelClickGui.panelClickGui.addToast(option + (nowSelected ? " enabled" : " disabled"));
                return true;
            }
            optionY += rowHeight;
        }
        return false;
    }

    @Override
    public boolean supports(Setting<?> setting) {
        return setting instanceof MultiSelectSetting;
    }

    @Override
    public int getHeight(Setting<?> setting, float scale) {
        if (!(setting instanceof MultiSelectSetting multiSelectSetting)) {
            return 0;
        }
        return Math.round((float)((multiSelectSetting.getOptions().size() + 1) * 20) * scale);
    }

    @Override
    public void onMouseRelease(double mouseX, double mouseY, int button) {
    }

    private int applyAlpha(int color, float alpha) {
        int origAlpha = color >> 24 & 0xFF;
        int newAlpha = (int)((float)origAlpha * alpha);
        return newAlpha << 24 | color & 0xFFFFFF;
    }

    private int lerpColor(int fromColor, int toColor, float t) {
        float inv = 1.0f - t;
        int aFrom = fromColor >> 24 & 0xFF;
        int rFrom = fromColor >> 16 & 0xFF;
        int gFrom = fromColor >> 8 & 0xFF;
        int bFrom = fromColor & 0xFF;
        int aTo = toColor >> 24 & 0xFF;
        int rTo = toColor >> 16 & 0xFF;
        int gTo = toColor >> 8 & 0xFF;
        int bTo = toColor & 0xFF;
        int a = (int)((float)aFrom * inv + (float)aTo * t);
        int r = (int)((float)rFrom * inv + (float)rTo * t);
        int g = (int)((float)gFrom * inv + (float)gTo * t);
        int b = (int)((float)bFrom * inv + (float)bTo * t);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
