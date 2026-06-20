package shit.nilore.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.nilore.gui.NewClickGui;
import shit.nilore.gui.newclickgui.CategoryPanel;
import shit.nilore.gui.newclickgui.SettingElement;
import shit.nilore.render.FontStore;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.math.MathUtil;
import shit.nilore.utils.misc.CursorUtil;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.RenderUtil;

public class NumberSettingElement
extends SettingElement<NumberSetting> {
    private final SmoothAnimationTimer sliderTimer = new SmoothAnimationTimer();
    private boolean isTruncated;
    private boolean isHovered;
    private boolean isDragging;

    public NumberSettingElement(CategoryPanel categoryPanel, NumberSetting numberSetting) {
        super(categoryPanel, numberSetting);
    }

    @Override
    public float getHeight() {
        return 30.0f;
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        float dragRatio;
        this.isHovered = CursorUtil.isInBounds(mouseX, mouseY, this.x, this.y, 120.0f, this.getHeight());
        float sliderWidth = 108.0f;
        float sliderHeight = 5.0f;
        float sliderY = this.y + this.getHeight() / 2.0f + (this.getHeight() / 2.0f - sliderHeight) / 2.0f;
        this.visibilityTimer.animate(this.setting.getVisibility().displayable() ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.visibilityTimer.tick();
        if (Mth.equal(alpha *= this.visibilityTimer.getValueF(), 0.0f)) {
            return;
        }
        float nameY = this.y + (this.getHeight() / 2.0f - FontStore.AXIFORMA_REGULAR_14.getFontHeight()) / 2.0f + 1.0f;
        String name = this.setting.getName();
        if (FontStore.AXIFORMA_REGULAR_14.getStringWidth(name) > 78.0f) {
            name = name.substring(0, 10);
            name = name + "...";
            this.isTruncated = true;
        }
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, name, this.x + 6.0f, nameY, ColorUtil.withAlpha(-1, alpha * 0.8f));
        String valueText = String.format("%.2f", new Object[]{this.setting.getValue().floatValue()});
        FontStore.AXIFORMA_BOLD_13.drawString(poseStack, valueText, this.x + 120.0f - FontStore.AXIFORMA_BOLD_13.getStringWidth(valueText) - 6.0f, nameY, ColorUtil.withAlpha(-1, alpha * 0.92f));
        float progress = this.setting.getValue().floatValue() / this.setting.getMax().floatValue();
        progress = Mth.clamp(progress, 0.0f, 1.0f);
        this.sliderTimer.animate(progress, 0.2, Easings.EASE_OUT_POW2);
        this.sliderTimer.tick();
        if (this.isDragging) {
            NumberSetting numberSetting = this.setting;
            dragRatio = ((float)mouseX - (this.x + 6.0f)) / sliderWidth;
            double rawValue = numberSetting.getMin().floatValue() + (numberSetting.getMax().floatValue() - numberSetting.getMin().floatValue()) * dragRatio;
            double step = numberSetting.getStep().floatValue();
            double stepped = (double)Math.round(MathUtil.clamp(rawValue, numberSetting.getMin().floatValue(), numberSetting.getMax().floatValue()) / step) * step;
            numberSetting.setValue((double)Math.round(stepped * 1000.0) / 1000.0);
        }
        RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, sliderY, sliderWidth, 5.0f, 2.0f, ColorUtil.withAlpha(ColorUtil.fromRGB(60, 60, 60), alpha));
        float fillAmount = this.sliderTimer.getValueF();
        dragRatio = 10.0f;
        float glowSize = 5.0f + dragRatio * 2.0f;
        RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f - dragRatio, sliderY - dragRatio, Math.max(sliderWidth * fillAmount + dragRatio * 2.0f, glowSize), glowSize, 2.0f + dragRatio / 2.0f + 1.0f, dragRatio, ColorUtil.withAlpha(-13768502, 0.26f * alpha));
        RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, sliderY, sliderWidth * fillAmount, 5.0f, 2.0f, ColorUtil.withAlpha(CategoryPanel.ACCENT_COLOR, alpha));
        float knobX = Math.max(this.x + 6.0f + this.sliderTimer.getValueF() * sliderWidth - 5.0f - 0.5f, this.x + 6.0f - 0.5f);
        RenderUtil.drawRoundedRect(poseStack, knobX - dragRatio, sliderY - 0.5f - dragRatio, 6.0f + dragRatio * 2.0f, 6.0f + dragRatio * 2.0f, 2.0f + dragRatio / 2.0f + 1.0f, dragRatio, ColorUtil.withAlpha(-1, 0.36f * alpha));
        RenderUtil.drawRoundedRect(poseStack, knobX, sliderY - 0.5f, 6.0f, 6.0f, 2.9f, ColorUtil.withAlpha(-1, alpha));
        if (this.isHovered && this.isTruncated) {
            this.parentPanel.setHoveredSettingElement(this);
            this.parentPanel.setTooltipText(this.setting.getName());
            this.parentPanel.setShowTooltip(true);
        } else if (this.parentPanel.getHoveredSettingElement() == this) {
            this.parentPanel.setShowTooltip(false);
            this.parentPanel.setHoveredSettingElement(null);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.setting.getVisibility().displayable()) {
            return false;
        }
        float sliderWidth = 108.0f;
        float sliderHeight = 5.0f;
        float sliderY = this.y + this.getHeight() / 2.0f + (this.getHeight() / 2.0f - sliderHeight) / 2.0f;
        if (this.isHovered && CursorUtil.isInBounds((float)mouseX, (float)mouseY, this.x + 6.0f, sliderY, sliderWidth, 5.0f)) {
            this.isDragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        float sliderWidth = 108.0f;
        float sliderHeight = 5.0f;
        float sliderY = this.y + this.getHeight() / 2.0f + (this.getHeight() / 2.0f - sliderHeight) / 2.0f;
        this.isDragging = false;
        return false;
    }
}