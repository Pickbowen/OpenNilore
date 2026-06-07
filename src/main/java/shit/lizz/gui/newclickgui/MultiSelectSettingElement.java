package shit.lizz.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.lizz.gui.NewClickGui;
import shit.lizz.gui.newclickgui.CategoryPanel;
import shit.lizz.gui.newclickgui.SettingElement;
import shit.lizz.render.FontStore;
import shit.lizz.settings.impl.MultiSelectSetting;
import shit.lizz.utils.animation.SmoothAnimationTimer;
import shit.lizz.utils.math.Easings;
import shit.lizz.utils.misc.CursorUtil;
import shit.lizz.utils.render.ColorUtil;
import shit.lizz.utils.render.RenderUtil;

public class MultiSelectSettingElement
extends SettingElement<MultiSelectSetting> {
    @Getter
    private final SmoothAnimationTimer hoverTimer = new SmoothAnimationTimer();
    @Getter
    private final SmoothAnimationTimer visTimer = new SmoothAnimationTimer();
    @Getter
    private final SmoothAnimationTimer highlightTimer = new SmoothAnimationTimer();
    @Getter
    private final SmoothAnimationTimer highlightYTimer = new SmoothAnimationTimer();
    @Getter @Setter
    private boolean isDropdownHovered;
    @Getter @Setter
    private boolean isOpen;
    @Getter @Setter
    private String hoveredOption;
    @Getter @Setter
    private boolean hasMultipleSelected;
    @Getter @Setter
    private boolean isTooltipShown;
    private static final String ELLIPSIS = "...";

    public MultiSelectSettingElement(CategoryPanel categoryPanel, MultiSelectSetting multiSelectSetting) {
        super(categoryPanel, multiSelectSetting);
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        float hoverAmount;
        float dropdownHeight;
        float dropdownY = this.y + 18.0f + 2.0f;
        float dropdownWidth = 108.0f;
        float itemHeight = 14.0f;
        this.hoveredOption = null;
        this.isDropdownHovered = CursorUtil.isInBounds(mouseX, mouseY, this.x + 6.0f, dropdownY, dropdownWidth, itemHeight);
        this.hoverTimer.animate(this.isDropdownHovered ? 1.0 : 0.0, 0.22, Easings.EASE_OUT_POW2);
        this.hoverTimer.tick();
        this.visibilityTimer.animate(this.setting.getVisibility().displayable() ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.visibilityTimer.tick();
        this.visTimer.animate(this.isOpen ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.visTimer.tick();
        if (Mth.equal(alpha *= this.visibilityTimer.getValueF(), 0.0f)) {
            return;
        }
        float nameY = this.y + (18.0f - FontStore.AXIFORMA_REGULAR_14.getFontHeight()) / 2.0f + 1.0f;
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, this.setting.getName(), this.x + 6.0f, nameY, ColorUtil.withAlpha(-1, alpha * 0.8f));
        float openAmount = this.visTimer.getValueF();
        if (openAmount > 0.0f) {
            dropdownHeight = itemHeight + (float) this.setting.getOptions().size() * itemHeight * openAmount;
            RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, dropdownY, dropdownWidth, dropdownHeight, 3.0f, ColorUtil.withAlpha(ColorUtil.fromRGB(60, 60, 60), alpha * openAmount));
            hoverAmount = (itemHeight - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f;
            float itemY = dropdownY + hoverAmount + itemHeight;
            for (String option : this.setting.getOptions()) {
                if (CursorUtil.isInBounds(mouseX, mouseY, this.x + 6.0f, itemY - hoverAmount, dropdownWidth, itemHeight)) {
                    this.hoveredOption = option;
                    this.highlightYTimer.animate(itemY - hoverAmount, 0.2, Easings.EASE_OUT_POW2);
                }
                if (dropdownY + dropdownHeight > itemY + FontStore.AXIFORMA_BOLD_13.getFontHeight()) {
                    FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, option, this.x + 60.0f, itemY, ColorUtil.withAlpha(-1, alpha * 0.8f * openAmount));
                    if (this.setting.getValue().contains(option)) {
                        FontStore.MATERIAL_14.drawString(poseStack, "", this.x + 6.0f + dropdownWidth - FontStore.MATERIAL_14.getStringWidth("") - 4.0f, itemY - hoverAmount + (itemHeight - FontStore.MATERIAL_14.getFontHeight()) / 2.0f + 0.5f, ColorUtil.withAlpha(-1, alpha * 0.56f * openAmount));
                    }
                }
                itemY += itemHeight;
            }
        }
        this.highlightTimer.animate(this.hoveredOption == null || !this.isOpen ? 0.0 : 1.0, 0.18, Easings.EASE_OUT_POW2);
        this.highlightTimer.tick();
        this.highlightYTimer.tick();
        dropdownHeight = this.highlightTimer.getValueF();
        if (dropdownHeight > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, this.highlightYTimer.getValueF(), dropdownWidth, itemHeight, 3.0f, ColorUtil.withAlpha(ColorUtil.fromRGB(255, 255, 255), alpha * dropdownHeight * 1.0f * 0.1f));
        }
        hoverAmount = this.hoverTimer.getValueF();
        RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, dropdownY, dropdownWidth, itemHeight, 3.0f, ColorUtil.withAlpha(ColorUtil.fromRGB((int)(60.0f + 30.0f * hoverAmount), (int)(60.0f + 30.0f * hoverAmount), (int)(60.0f + 30.0f * hoverAmount)), alpha));
        String selectedLabel = this.setting.getValue().get(0);
        if (this.setting.getValue().size() > 1) {
            selectedLabel = selectedLabel + ELLIPSIS;
            this.hasMultipleSelected = true;
        }
        FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, selectedLabel, this.x + 60.0f, dropdownY + (itemHeight - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f, ColorUtil.withAlpha(-1, alpha * 0.8f));
        Object arrowIcon = String.valueOf('');
        FontStore.MATERIAL_20.drawString(poseStack, (String)arrowIcon, this.x + 6.0f + dropdownWidth - FontStore.MATERIAL_20.getStringWidth((String)arrowIcon) - 2.0f, dropdownY + (itemHeight - FontStore.MATERIAL_20.getFontHeight()) / 2.0f + 0.5f, ColorUtil.withAlpha(-1, alpha * 0.8f));
        if (this.isDropdownHovered && this.hasMultipleSelected) {
            this.parentPanel.setHoveredSettingElement(this);
            this.parentPanel.setTooltipText(this.setting.getValue().toString());
            this.parentPanel.setShowTooltip(true);
        } else if (this.parentPanel.getHoveredSettingElement() == this) {
            this.parentPanel.setShowTooltip(false);
            this.parentPanel.setHoveredSettingElement(null);
        }
    }

    @Override
    public float getHeight() {
        return 36 + (this.isOpen ? 14 * this.setting.getOptions().size() : 0);
    }

    @Override
    public float getAnimatedHeight() {
        return 36.0f + (float)(14 * this.setting.getOptions().size()) * this.visTimer.getValueF();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.setting.getVisibility().displayable()) {
            return false;
        }
        if (this.isDropdownHovered) {
            this.isOpen = !this.isOpen;
            return true;
        }
        if (this.hoveredOption != null && this.isOpen) {
            ArrayList<String> selected = new ArrayList<>(this.setting.getValue());
            if (selected.contains(this.hoveredOption)) {
                if (selected.size() > 1) {
                    selected.remove(this.hoveredOption);
                }
            } else {
                selected.add(this.hoveredOption);
            }
            this.setting.setValue(selected);
            return true;
        }
        return false;
    }

}
