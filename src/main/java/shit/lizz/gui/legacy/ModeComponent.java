package shit.lizz.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import lombok.Getter;
import shit.lizz.gui.legacy.ModuleButton;
import shit.lizz.gui.legacy.SettingComponent;
import shit.lizz.render.FontStore;
import shit.lizz.settings.Setting;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.utils.render.RenderUtil;

public class ModeComponent
extends SettingComponent {
    private final ModeSetting modeSetting;
    @Getter
    private boolean dropdownOpen = false;

    public ModeComponent(Setting<?> setting, ModuleButton moduleButton, int yOffset) {
        super(setting, moduleButton, yOffset);
        this.modeSetting = (ModeSetting)setting;
    }

    @Override
    public void renderWithAlpha(PoseStack poseStack, int mouseX, int mouseY, float partialTicks, float alpha) {
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset + 3;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int rowHeight = this.parentButton.panel.rowHeight;
        int verticalPadding = 4;
        int contentHeight = rowHeight - verticalPadding * 2;
        int outerPadding = 8;
        int innerPadding = 4;
        int boxX = panelX + outerPadding + innerPadding;
        int boxWidth = panelWidth - (outerPadding + innerPadding) * 2;
        int textColor = new Color(255, 255, 255, (int)(255.0f * alpha)).getRGB();
        boolean hovered = mouseX >= boxX && mouseX <= boxX + boxWidth && mouseY >= rowY + verticalPadding && mouseY <= rowY + verticalPadding + contentHeight;
        Color boxColor = hovered && !this.dropdownOpen ? new Color(45, 45, 45, (int)(180.0f * alpha)) : new Color(25, 25, 25, (int)(150.0f * alpha));
        RenderUtil.drawFilledRect(poseStack, boxX, rowY + verticalPadding, boxWidth, contentHeight, boxColor.getRGB());
        Color edgeColor = new Color(100, 100, 100, (int)(180.0f * alpha));
        RenderUtil.drawFilledRect(poseStack, panelX + outerPadding + 2, rowY + verticalPadding, innerPadding - 2, contentHeight, edgeColor.getRGB());
        RenderUtil.drawFilledRect(poseStack, panelX + panelWidth - outerPadding - innerPadding, rowY + verticalPadding, innerPadding - 2, contentHeight, edgeColor.getRGB());
        String name = this.modeSetting.getName();
        float textY = (float)(rowY + verticalPadding) + ((float)contentHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f - 1.5f;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, name, boxX + innerPadding, textY, textColor);
        String selectedValue = this.modeSetting.getValue();
        float valueX = (float)(boxX + boxWidth) - FontStore.OPENSANS_16.getStringWidth(selectedValue) - (float)innerPadding;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, selectedValue, valueX, textY, new Color(138, 180, 248).getRGB());
        if (this.dropdownOpen) {
            int dropdownY = rowY + verticalPadding + contentHeight;
            int dropdownHeight = this.getDropdownHeight();
            RenderUtil.drawFilledRect(poseStack, boxX, dropdownY, boxWidth, dropdownHeight, new Color(33, 33, 33, 150).getRGB());
            String[] modes = this.getModes();
            for (int i = 0; i < modes.length; ++i) {
                boolean itemHovered;
                String mode = modes[i];
                float itemY = dropdownY + i * contentHeight;
                boolean dup = itemHovered = mouseX >= boxX && mouseX <= boxX + boxWidth && (float)mouseY >= itemY && (float)mouseY < itemY + (float)contentHeight;
                if (itemHovered) {
                    RenderUtil.drawFilledRect(poseStack, boxX, itemY, boxWidth, contentHeight, new Color(0, 0, 0, 100).getRGB());
                }
                float modeTextWidth = FontStore.OPENSANS_16.getStringWidth(mode);
                float modeTextX = (float)boxX + ((float)boxWidth - modeTextWidth) / 2.0f;
                float modeTextY = itemY + ((float)contentHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f;
                boolean isSelected = mode.equals(this.modeSetting.getValue());
                FontStore.OPENSANS_16.drawStringWithShadow(poseStack, mode, modeTextX, modeTextY, isSelected ? new Color(138, 180, 248).getRGB() : textColor);
            }
        }
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        int overHeader;
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset + 3;
        int rowHeight = this.parentButton.panel.rowHeight;
        int verticalPadding = 4;
        int contentHeight = rowHeight - verticalPadding * 2;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int outerPadding = 8;
        int innerPadding = 4;
        int boxX = panelX + outerPadding + innerPadding;
        int boxWidth = panelWidth - (outerPadding + innerPadding) * 2;
        String[] modes = this.modeSetting.getModes();
        if (this.dropdownOpen) {
            overHeader = rowY + verticalPadding + contentHeight;
            int dropdownHeight = this.getDropdownHeight();
            if (mouseX >= (double)boxX && mouseX <= (double)(boxX + boxWidth) && mouseY >= (double)overHeader && mouseY < (double)(overHeader + dropdownHeight)) {
                int itemIndex = (int)((mouseY - (double)overHeader) / (double)contentHeight);
                if (itemIndex >= 0 && itemIndex < modes.length) {
                    this.modeSetting.setValue(modes[itemIndex]);
                    this.dropdownOpen = false;
                    this.parentButton.panel.recalcLayout();
                }
                return;
            }
        }
        int headerHit = overHeader = mouseX >= (double)boxX && mouseX <= (double)(boxX + boxWidth) && mouseY >= (double)(rowY + verticalPadding) && mouseY <= (double)(rowY + verticalPadding + contentHeight) ? 1 : 0;
        if (overHeader != 0 && (button == 0 || button == 1)) {
            this.dropdownOpen = !this.dropdownOpen;
            this.parentButton.panel.recalcLayout();
        }
    }

    @Override
    public boolean isHovered(double mouseX, double mouseY) {
        boolean overHeader;
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int rowHeight = this.parentButton.panel.rowHeight;
        boolean dup = overHeader = mouseX >= (double)panelX && mouseX <= (double)(panelX + panelWidth) && mouseY >= (double)rowY && mouseY <= (double)(rowY + rowHeight);
        if (overHeader) {
            return true;
        }
        if (this.dropdownOpen) {
            int dropdownY = rowY + rowHeight;
            int dropdownHeight = this.getDropdownHeight();
            return mouseX >= (double)panelX && mouseX <= (double)(panelX + panelWidth) && mouseY >= (double)dropdownY && mouseY <= (double)(dropdownY + dropdownHeight);
        }
        return false;
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        super.mouseReleased(mouseX, mouseY, button);
    }

    public int getDropdownHeight() {
        int verticalPadding = 4;
        int contentHeight = this.parentButton.panel.rowHeight - verticalPadding * 2;
        return contentHeight * this.modeSetting.getModes().length;
    }

    public String[] getModes() {
        return this.modeSetting.getModes();
    }

    }
