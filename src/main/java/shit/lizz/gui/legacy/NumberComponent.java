package shit.lizz.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import shit.lizz.gui.legacy.ModuleButton;
import shit.lizz.gui.legacy.SettingComponent;
import shit.lizz.render.FontStore;
import shit.lizz.settings.Setting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.math.MathUtil;
import shit.lizz.utils.render.RenderUtil;

public class NumberComponent
extends SettingComponent {
    private boolean dragging;
    private final NumberSetting numberSetting;

    public NumberComponent(Setting<?> setting, ModuleButton moduleButton, int yOffset) {
        super(setting, moduleButton, yOffset);
        this.numberSetting = (NumberSetting)setting;
        this.dragging = false;
    }

    @Override
    public void renderWithAlpha(PoseStack poseStack, int mouseX, int mouseY, float partialTicks, float alpha) {
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int rowHeight = this.parentButton.panel.rowHeight;
        int textColor = new Color(255, 255, 255, (int)(255.0f * alpha)).getRGB();
        int sliderColor = new Color(138, 180, 248, (int)(255.0f * alpha)).getRGB();
        int textPadding = 8;
        int sliderX = panelX + textPadding;
        int valueTextWidth = (int)FontStore.OPENSANS_16.getStringWidth("00.00");
        int sliderWidth = panelWidth - textPadding * 2 - valueTextWidth + 20;
        if (this.dragging) {
            this.updateSliderValue(mouseX, sliderX, sliderWidth);
        }
        String name = this.numberSetting.getName();
        String valueText = this.numberSetting.getStep().doubleValue() % 1.0 == 0.0 ? String.format("%d", new Object[]{this.numberSetting.getValue().intValue()}) : String.format("%.2f", new Object[]{this.numberSetting.getValue().floatValue()});
        float textY = (float)rowY + ((float)rowHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, name, sliderX, textY, textColor);
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, valueText, (float)(panelX + panelWidth - textPadding) - FontStore.OPENSANS_16.getStringWidth(valueText), textY, textColor);
        float min = this.numberSetting.getMin().floatValue();
        float max = this.numberSetting.getMax().floatValue();
        float current = this.numberSetting.getValue().floatValue();
        float progress = (current - min) / (max - min);
        int sliderY = rowY + rowHeight / 2 + 10;
        int sliderHeight = 2;
        RenderUtil.drawFilledRect(poseStack, sliderX, sliderY, sliderWidth, sliderHeight, new Color(10, 10, 10, 200).getRGB());
        RenderUtil.drawFilledRect(poseStack, sliderX, sliderY, (int)((float)sliderWidth * progress), sliderHeight, sliderColor);
    }

    private void updateSliderValue(double mouseX, int sliderX, int sliderWidth) {
        float min = this.numberSetting.getMin().floatValue();
        float max = this.numberSetting.getMax().floatValue();
        float range = max - min;
        double clampedX = Math.max(sliderX, Math.min(mouseX, sliderX + sliderWidth));
        float newValue = min + range * (float)((clampedX - (double)sliderX) / (double)sliderWidth);
        this.numberSetting.setValue(MathUtil.roundDecimal(newValue, 2));
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        boolean overSlider;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
        int rowHeight = this.parentButton.panel.rowHeight;
        int textPadding = 8;
        int sliderX = panelX + textPadding;
        int valueTextWidth = (int)FontStore.OPENSANS_16.getStringWidth("00.00");
        int sliderWidth = panelWidth - textPadding * 2 - valueTextWidth + 20;
        boolean dup = overSlider = mouseX >= (double)sliderX && mouseX <= (double)(sliderX + sliderWidth) && mouseY >= (double)rowY && mouseY <= (double)(rowY + rowHeight);
        if (button == 0 && overSlider) {
            this.dragging = true;
            this.updateSliderValue(mouseX, sliderX, sliderWidth);
        }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.dragging = false;
        }
    }
}
