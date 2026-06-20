package shit.nilore.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import shit.nilore.gui.legacy.ModuleButton;
import shit.nilore.gui.legacy.SettingComponent;
import shit.nilore.render.FontStore;
import shit.nilore.settings.Setting;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.utils.render.RenderUtil;

public class BooleanComponent
extends SettingComponent {
    private final BooleanSetting booleanSetting;
    private float toggleAnim = 0.0f;
    private final float animSpeed = 0.2f;

    public BooleanComponent(Setting<?> setting, ModuleButton moduleButton, int yOffset) {
        super(setting, moduleButton, yOffset);
        this.booleanSetting = (BooleanSetting)setting;
        this.toggleAnim = this.booleanSetting.getValue() != false ? 1.0f : 0.0f;
    }

    @Override
    public void renderWithAlpha(PoseStack poseStack, int mouseX, int mouseY, float partialTicks, float alpha) {
        float target;
        float dup = target = this.booleanSetting.getValue() != false ? 1.0f : 0.0f;
        this.toggleAnim = Math.abs(this.toggleAnim - target) > 0.01f ? (this.toggleAnim += (target - this.toggleAnim) * 0.2f) : target;
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
        int verticalPadding = 4;
        int contentHeight = this.parentButton.panel.rowHeight - verticalPadding * 2;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int textPadding = 8;
        String name = this.booleanSetting.getName();
        float textY = (float)(rowY + verticalPadding) + ((float)contentHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f;
        int textColor = new Color(255, 255, 255, (int)(255.0f * alpha)).getRGB();
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, name, panelX + textPadding, textY, textColor);
        int toggleWidth = 18;
        int toggleHeight = 8;
        int toggleX = panelX + panelWidth - textPadding - toggleWidth;
        int toggleY = rowY + verticalPadding + (contentHeight - toggleHeight) / 2 + 2;
        Color trackColor = this.booleanSetting.getValue() != false ? new Color(138, 180, 248, (int)(200.0f * alpha)) : new Color(100, 100, 100, (int)(180.0f * alpha));
        RenderUtil.drawFilledRect(poseStack, toggleX, toggleY, toggleWidth, toggleHeight, trackColor.getRGB());
        float knobWidth = 7.0f;
        float knobHeight = toggleHeight + 4;
        float knobX = (float)(toggleX + 1) + ((float)toggleWidth - knobWidth - 2.0f) * this.toggleAnim;
        float knobY = toggleY - 2;
        RenderUtil.drawFilledRect(poseStack, knobX, knobY, knobWidth, knobHeight, new Color(160, 195, 255, (int)(255.0f * alpha)).getRGB());
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isHovered(mouseX, mouseY) && button == 0) {
            boolean previous = this.booleanSetting.getValue();
            this.booleanSetting.setValue(!previous);
            if (previous != this.booleanSetting.getValue()) {
                this.parentButton.panel.recalcLayout();
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        super.mouseReleased(mouseX, mouseY, button);
    }
}
