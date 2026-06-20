package shit.nilore.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.List;
import lombok.Getter;
import shit.nilore.gui.legacy.ModuleButton;
import shit.nilore.gui.legacy.SettingComponent;
import shit.nilore.render.FontStore;
import shit.nilore.settings.Setting;
import shit.nilore.settings.impl.MultiSelectSetting;
import shit.nilore.utils.render.RenderUtil;

public class MultiSelectComponent
extends SettingComponent {
    private final MultiSelectSetting multiSelectSetting;
    @Getter
    private boolean dropdownOpen;

    public MultiSelectComponent(Setting<?> setting, ModuleButton moduleButton, int yOffset) {
        super(setting, moduleButton, yOffset);
        this.multiSelectSetting = (MultiSelectSetting)setting;
    }

    @Override
    public void renderWithAlpha(PoseStack poseStack, int mouseX, int mouseY, float partialTicks, float alpha) {
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
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
        int selectedColor = new Color(138, 180, 248, (int)(255.0f * alpha)).getRGB();
        int boxHeight = contentHeight;
        if (this.dropdownOpen) {
            boxHeight += this.getDropdownHeight();
        }
        boolean hovered = mouseX >= boxX && mouseX <= boxX + boxWidth && mouseY >= rowY + verticalPadding && mouseY <= rowY + verticalPadding + contentHeight;
        Color boxColor = hovered && !this.dropdownOpen ? new Color(40, 40, 40, (int)(180.0f * alpha)) : new Color(15, 15, 15, (int)(220.0f * alpha));
        RenderUtil.drawFilledRect(poseStack, boxX, rowY + verticalPadding, boxWidth, boxHeight, boxColor.getRGB());
        Color edgeColor = new Color(100, 100, 100, (int)(180.0f * alpha));
        RenderUtil.drawFilledRect(poseStack, panelX + outerPadding + 2, rowY + verticalPadding, innerPadding - 2, contentHeight, edgeColor.getRGB());
        RenderUtil.drawFilledRect(poseStack, panelX + panelWidth - outerPadding - innerPadding, rowY + verticalPadding, innerPadding - 2, contentHeight, edgeColor.getRGB());
        String name = this.multiSelectSetting.getName();
        float nameWidth = FontStore.OPENSANS_16.getStringWidth(name);
        float nameX = (float)boxX + ((float)boxWidth - nameWidth) / 2.0f;
        float nameY = (float)(rowY + verticalPadding) + ((float)contentHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f - 1.0f;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, name, nameX, nameY, textColor);
        if (this.dropdownOpen) {
            int dropdownY = rowY + verticalPadding + contentHeight;
            List<String> options = this.multiSelectSetting.getOptions();
            for (int i = 0; i < options.size(); ++i) {
                boolean itemHovered;
                String option = options.get(i);
                float itemY = dropdownY + i * contentHeight;
                boolean dup = itemHovered = mouseX >= boxX && mouseX <= boxX + boxWidth && (float)mouseY >= itemY && (float)mouseY < itemY + (float)contentHeight;
                if (itemHovered) {
                    RenderUtil.drawFilledRect(poseStack, boxX, itemY, boxWidth, contentHeight, new Color(0, 0, 0, 100).getRGB());
                }
                float optionWidth = FontStore.OPENSANS_16.getStringWidth(option);
                float optionX = (float)boxX + ((float)boxWidth - optionWidth) / 2.0f;
                float optionY = itemY + ((float)contentHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f;
                boolean isSelected = this.multiSelectSetting.isSelected(option);
                FontStore.OPENSANS_16.drawStringWithShadow(poseStack, option, optionX, optionY, isSelected ? selectedColor : textColor);
            }
        }
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isHeaderHovered(mouseX, mouseY) && button == 1) {
            this.dropdownOpen = !this.dropdownOpen;
            this.parentButton.panel.recalcLayout();
            return;
        }
        if (this.dropdownOpen) {
            int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
            int rowHeight = this.parentButton.panel.rowHeight;
            int dropdownY = rowY + rowHeight;
            int verticalPadding = 4;
            int contentHeight = rowHeight - verticalPadding * 2;
            int panelX = this.parentButton.panel.x;
            int panelWidth = this.parentButton.panel.width;
            int outerPadding = 8;
            int innerPadding = 4;
            int boxX = panelX + outerPadding + innerPadding;
            int boxWidth = panelWidth - (outerPadding + innerPadding) * 2;
            if (mouseX >= (double)boxX && mouseX <= (double)(boxX + boxWidth) && mouseY >= (double)dropdownY) {
                int itemIndex = (int)((mouseY - (double)dropdownY) / (double)contentHeight);
                List<String> options = this.multiSelectSetting.getOptions();
                if (itemIndex >= 0 && itemIndex < options.size()) {
                    String option = options.get(itemIndex);
                    if (this.multiSelectSetting.isSelected(option)) {
                        this.multiSelectSetting.getValue().remove(option);
                    } else {
                        this.multiSelectSetting.getValue().add(option);
                    }
                }
            }
        }
    }

    public int getDropdownHeight() {
        if (!this.dropdownOpen) {
            return 0;
        }
        int verticalPadding = 4;
        int contentHeight = this.parentButton.panel.rowHeight - verticalPadding * 2;
        return contentHeight * this.multiSelectSetting.getOptions().size();
    }

    private boolean isHeaderHovered(double mouseX, double mouseY) {
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
        return mouseX >= (double)this.parentButton.panel.x && mouseX <= (double)(this.parentButton.panel.x + this.parentButton.panel.width) && mouseY >= (double)rowY && mouseY <= (double)(rowY + this.parentButton.panel.rowHeight);
    }

    @Override
    public boolean isHovered(double mouseX, double mouseY) {
        if (this.isHeaderHovered(mouseX, mouseY)) {
            return true;
        }
        if (this.dropdownOpen) {
            int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
            int dropdownY = rowY + this.parentButton.panel.rowHeight;
            int dropdownHeight = this.getDropdownHeight();
            int panelX = this.parentButton.panel.x;
            int panelWidth = this.parentButton.panel.width;
            int outerPadding = 8;
            int innerPadding = 4;
            int boxX = panelX + outerPadding + innerPadding;
            int boxWidth = panelWidth - (outerPadding + innerPadding) * 2;
            return mouseX >= (double)boxX && mouseX <= (double)(boxX + boxWidth) && mouseY >= (double)dropdownY && mouseY <= (double)(dropdownY + dropdownHeight);
        }
        return false;
    }

    }
