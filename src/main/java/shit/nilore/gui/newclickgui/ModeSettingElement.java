package shit.nilore.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.nilore.gui.NewClickGui;
import shit.nilore.gui.newclickgui.CategoryPanel;
import shit.nilore.gui.newclickgui.SettingElement;
import shit.nilore.render.FontStore;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.misc.CursorUtil;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.RenderUtil;

public class ModeSettingElement
extends SettingElement<ModeSetting> {
    private final SmoothAnimationTimer hoverTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer visTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer highlightTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer highlightYTimer = new SmoothAnimationTimer();
    private boolean isDropdownHovered;
    private boolean isOpen;
    private String hoveredMode;

    public ModeSettingElement(CategoryPanel categoryPanel, ModeSetting modeSetting) {
        super(categoryPanel, modeSetting);
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        float hoverAmount;
        float dropdownHeight;
        float totalHeight = 36.0f;
        float dropdownY = this.y + totalHeight / 2.0f + 2.0f - 2.0f;
        float dropdownWidth = 108.0f;
        float itemHeight = 14.0f;
        this.hoveredMode = null;
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
        float nameY = this.y + (totalHeight / 2.0f - FontStore.AXIFORMA_REGULAR_14.getFontHeight()) / 2.0f + 1.0f;
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, this.setting.getName(), this.x + 6.0f, nameY, ColorUtil.withAlpha(-1, alpha * 0.8f));
        float openAmount = this.visTimer.getValueF();
        if (openAmount > 0.0f) {
            dropdownHeight = itemHeight + (float) this.setting.getModes().length * itemHeight * openAmount;
            RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, dropdownY, dropdownWidth, dropdownHeight, 3.0f, ColorUtil.withAlpha(ColorUtil.fromRGB(60, 60, 60), alpha * openAmount));
            hoverAmount = (itemHeight - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f;
            float itemY = dropdownY + hoverAmount + itemHeight;
            for (String mode : this.setting.getModes()) {
                if (CursorUtil.isInBounds(mouseX, mouseY, this.x + 6.0f, itemY - hoverAmount, dropdownWidth, itemHeight)) {
                    this.hoveredMode = mode;
                    this.highlightYTimer.animate(itemY - hoverAmount, 0.2, Easings.EASE_OUT_POW2);
                }
                if (dropdownY + dropdownHeight > itemY + FontStore.AXIFORMA_BOLD_13.getFontHeight()) {
                    FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, mode, this.x + 60.0f, itemY, ColorUtil.withAlpha(-1, alpha * 0.8f * openAmount));
                }
                itemY += itemHeight;
            }
        }
        this.highlightTimer.animate(this.hoveredMode == null || !this.isOpen ? 0.0 : 1.0, 0.18, Easings.EASE_OUT_POW2);
        this.highlightTimer.tick();
        this.highlightYTimer.tick();
        dropdownHeight = this.highlightTimer.getValueF();
        if (dropdownHeight > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, this.highlightYTimer.getValueF(), dropdownWidth, itemHeight, 3.0f, ColorUtil.withAlpha(ColorUtil.fromRGB(255, 255, 255), alpha * dropdownHeight * 1.0f * 0.1f));
        }
        hoverAmount = this.hoverTimer.getValueF();
        RenderUtil.drawRoundedRect(poseStack, this.x + 6.0f, dropdownY, dropdownWidth, itemHeight, 3.0f, ColorUtil.withAlpha(ColorUtil.fromRGB((int)(60.0f + 30.0f * hoverAmount), (int)(60.0f + 30.0f * hoverAmount), (int)(60.0f + 30.0f * hoverAmount)), alpha));
        FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, this.setting.getValue(), this.x + 60.0f, dropdownY + (itemHeight - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f, ColorUtil.withAlpha(-1, alpha * 0.8f));
        String arrowIcon = String.valueOf('\ueb5d');
        FontStore.MATERIAL_20.drawStringCentered(poseStack, arrowIcon, this.x + 6.0f + dropdownWidth - FontStore.MATERIAL_20.getStringWidth(arrowIcon) + 2.0f, dropdownY + (itemHeight - FontStore.MATERIAL_20.getFontHeight()) / 2.0f + 0.5f, ColorUtil.withAlpha(-1, alpha * 0.8f));
    }

    @Override
    public float getHeight() {
        return 36 + (this.isOpen ? 14 * this.setting.getModes().length : 0);
    }

    @Override
    public float getAnimatedHeight() {
        return 36.0f + (float)(14 * this.setting.getModes().length) * this.visTimer.getValueF();
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
        if (this.hoveredMode != null && this.isOpen) {
            this.setting.setValue(this.hoveredMode);
            this.isOpen = false;
            this.hoveredMode = null;
            return true;
        }
        return false;
    }
}