package shit.lizz.gui.panel.setting;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import shit.lizz.ClientBase;
import shit.lizz.gui.PanelClickGui;
import shit.lizz.render.FontPresets;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.GlHelper;
import shit.lizz.render.TextGlow;
import shit.lizz.settings.Setting;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.utils.math.LerpUtil;
import shit.lizz.utils.render.RenderUtil;

public class ModeSettingRenderer
extends ClientBase
implements SettingRenderer {
    private static final int DROPDOWN_BG_COLOR = new Color(255, 255, 255, 15).getRGB();
    private final Map<ModeSetting, Boolean> openStates = new HashMap<>();
    private final Map<ModeSetting, Float> openAnimations = new HashMap<>();
    private final Map<ModeSetting, Map<String, Float>> itemHoverAnimations = new HashMap<>();

    @Override
    public int render(GuiGraphics guiGraphics, Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, float alpha, float scale) {
        if (!(setting instanceof ModeSetting modeSetting)) {
            return 0;
        }
        this.updateOpenAnimation(modeSetting);
        String[] otherModes = Arrays.stream((Object[])modeSetting.getModes()).filter(mode -> !Objects.equals(mode, modeSetting.getValue())).toArray(String[]::new);
        float openFactor = this.openAnimations.getOrDefault(modeSetting, 0.0f).floatValue();
        int itemHeight = Math.round(16.0f * scale);
        int expandedHeight = Math.round((float)(otherModes.length * itemHeight) * openFactor);
        int rowHeight = Math.round(24.0f * scale);
        try {
            int dropdownWidth = Math.round(75.0f * scale);
            int dropdownX = x + width - Math.round(80.0f * scale);
            int dropdownY = y + 1;
            RenderUtil.drawRoundedRect(guiGraphics.pose(), (float)dropdownX, (float)dropdownY, (float)dropdownWidth, (float)(itemHeight + expandedHeight), 4.0f * scale, this.applyAlpha(DROPDOWN_BG_COLOR, alpha));
            FontRenderer nameFont = FontPresets.axiformaRegular(14.0f * scale);
            float nameY = (float)y + (float)rowHeight / 2.0f - nameFont.getMetrics().capHeight() / 2.0f;
            int nameGlow = new Color(255, 255, 255, 100).getRGB();
            TextGlow.drawGlowText(modeSetting.getName(), x, nameY, nameFont, this.applyAlpha(-1, alpha), this.applyAlpha(nameGlow, alpha), 8.0f * scale);
            FontRenderer valueFont = FontPresets.axiformaRegular(14.0f * scale);
            String selectedValue = modeSetting.getValue() != null ? modeSetting.getValue() : "None";
            float selectedWidth = GlHelper.getStringWidth(selectedValue, valueFont);
            float selectedX = (float)dropdownX + ((float)dropdownWidth - selectedWidth) / 2.0f;
            float selectedY = (float)dropdownY + (float)itemHeight / 2.0f - valueFont.getMetrics().capHeight() / 2.0f + 2.0f * scale;
            TextGlow.drawGlowText(selectedValue, selectedX, selectedY, valueFont, this.applyAlpha(-3355444, alpha), this.applyAlpha(new Color(255, 255, 255, 150).getRGB(), alpha), 10.0f * scale);
            float openFactor2 = this.openAnimations.getOrDefault(modeSetting, 0.0f).floatValue();
            if ((double)openFactor2 > 0.01) {
                this.itemHoverAnimations.putIfAbsent(modeSetting, new HashMap<>());
                Map<String, Float> hoverMap = this.itemHoverAnimations.get(modeSetting);
                for (int i = 0; i < otherModes.length; ++i) {
                    String mode = otherModes[i];
                    float modeWidth = GlHelper.getStringWidth(mode, valueFont);
                    float modeX = (float)dropdownX + ((float)dropdownWidth - modeWidth) / 2.0f;
                    int itemY = dropdownY + itemHeight + i * itemHeight;
                    float modeTextY = (float)itemY + (float)itemHeight / 2.0f - valueFont.getMetrics().capHeight() / 2.0f + 2.0f * scale;
                    boolean hovered = this.isMouseInBounds(mouseX, mouseY, dropdownX, itemY, dropdownWidth, itemHeight);
                    this.updateItemHoverAnim(hoverMap, mode, hovered);
                    float hoverAmount = hoverMap.getOrDefault(mode, 0.0f);
                    float clampedOpen = Math.min(1.0f, openFactor2);
                    if (!(clampedOpen > 0.01f)) continue;
                    int alphaByte = (int)(255.0f * clampedOpen * alpha);
                    int modeColor = this.lerpColor(-3355444, -1, hoverAmount);
                    int finalColor = alphaByte << 24 | modeColor & 0xFFFFFF;
                    int glowAlpha = (int)this.lerpFloat(80.0f, 150.0f, hoverAmount);
                    int glowColor = new Color(255, 255, 255, (int)((float)glowAlpha * clampedOpen * alpha)).getRGB();
                    float glowRadius = this.lerpFloat(6.0f, 10.0f, hoverAmount);
                    TextGlow.drawGlowText(mode, modeX, modeTextY, valueFont, finalColor, glowColor, glowRadius * scale);
                }
            }
        } catch (Exception exception) {
            // empty catch block
        }
        return rowHeight + expandedHeight;
    }

    private int applyAlpha(int color, float alpha) {
        int origAlpha = color >> 24 & 0xFF;
        int newAlpha = (int)((float)origAlpha * alpha);
        return newAlpha << 24 | color & 0xFFFFFF;
    }

    private void updateItemHoverAnim(Map<String, Float> hoverMap, String key, boolean hovered) {
        float current = hoverMap.getOrDefault(key, 0.0f).floatValue();
        float target = hovered ? 1.0f : 0.0f;
        current = Math.abs(current - target) > 0.01f ? LerpUtil.smoothLerp(current, target, 0.28f) : target;
        hoverMap.put(key, current);
    }

    private float lerpFloat(float from, float to, float t) {
        return from + (to - from) * t;
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

    private void updateOpenAnimation(ModeSetting modeSetting) {
        boolean open = this.openStates.getOrDefault(modeSetting, false);
        float current = this.openAnimations.getOrDefault(modeSetting, 0.0f).floatValue();
        if (open) {
            this.openAnimations.put(modeSetting, LerpUtil.lerp(current, 1.0f, 0.08f));
        } else {
            this.openAnimations.put(modeSetting, LerpUtil.lerp(current, 0.0f, 0.16f));
        }
    }

    @Override
    public boolean onClick(Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, int button, float scale) {
        if (!(setting instanceof ModeSetting modeSetting)) {
            return false;
        }
        int itemHeight = Math.round(16.0f * scale);
        int dropdownWidth = Math.round(75.0f * scale);
        int dropdownX = x + width - Math.round(80.0f * scale);
        int dropdownY = y - 2;
        boolean open = this.openStates.getOrDefault(modeSetting, false);
        String[] otherModes = Arrays.stream((Object[])modeSetting.getModes()).filter(mode -> !Objects.equals(mode, modeSetting.getValue())).toArray(String[]::new);
        boolean overHeader = this.isMouseInBounds(mouseX, mouseY, dropdownX, dropdownY, dropdownWidth, itemHeight);
        if (overHeader && button == 1) {
            this.openStates.put(modeSetting, !open);
            return true;
        }
        if (open) {
            int firstItemY = dropdownY + itemHeight;
            for (int i = 0; i < otherModes.length; ++i) {
                if (!this.isMouseInBounds(mouseX, mouseY, dropdownX, firstItemY + i * itemHeight, dropdownWidth, itemHeight) || button != 0) continue;
                String chosen = otherModes[i];
                modeSetting.setValue(chosen);
                this.openStates.put(modeSetting, false);
                PanelClickGui.panelClickGui.addToast(modeSetting.getName() + " set to " + chosen);
                return true;
            }
            if (!overHeader) {
                this.openStates.put(modeSetting, false);
            }
        }
        return false;
    }

    private boolean isMouseInBounds(int mouseX, int mouseY, int boxX, int boxY, int boxWidth, int boxHeight) {
        return mouseX >= boxX && mouseX <= boxX + boxWidth && mouseY >= boxY && mouseY <= boxY + boxHeight;
    }

    @Override
    public boolean supports(Setting<?> setting) {
        return setting instanceof ModeSetting;
    }

    @Override
    public int getHeight(Setting<?> setting, float scale) {
        if (!(setting instanceof ModeSetting modeSetting)) {
            return Math.round(24.0f * scale);
        }
        String[] otherModes = Arrays.stream((Object[])modeSetting.getModes()).filter(mode -> !Objects.equals(mode, modeSetting.getValue())).toArray(String[]::new);
        float openFactor = this.openAnimations.getOrDefault(modeSetting, 0.0f).floatValue();
        int itemHeight = Math.round(16.0f * scale);
        int expandedHeight = Math.round((float)(otherModes.length * itemHeight) * openFactor);
        return Math.round(24.0f * scale) + expandedHeight;
    }

    @Override
    public void onMouseRelease(double mouseX, double mouseY, int button) {
    }
}
