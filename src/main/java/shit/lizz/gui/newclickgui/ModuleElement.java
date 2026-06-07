package shit.lizz.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.client.gui.GuiGraphics;
import shit.lizz.gui.NewClickGui;
import shit.lizz.gui.newclickgui.BooleanSettingElement;
import shit.lizz.gui.newclickgui.CategoryPanel;
import shit.lizz.gui.newclickgui.ModeSettingElement;
import shit.lizz.gui.newclickgui.MultiSelectSettingElement;
import shit.lizz.gui.newclickgui.NumberSettingElement;
import shit.lizz.gui.newclickgui.SettingElement;
import shit.lizz.gui.newclickgui.UIElement;
import shit.lizz.modules.Module;
import shit.lizz.render.FontStore;
import shit.lizz.settings.Setting;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.settings.impl.MultiSelectSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.animation.SmoothAnimationTimer;
import shit.lizz.utils.math.Easings;
import shit.lizz.utils.misc.CursorUtil;
import shit.lizz.utils.render.ColorUtil;
import shit.lizz.utils.render.RenderHelper;
import shit.lizz.utils.render.RenderUtil;

public class ModuleElement
extends UIElement {
    public static final int BG_COLOR;
    @Getter
    private final List<SettingElement<?>> settingElements = new ArrayList<>();
    @Getter
    private final CategoryPanel parentPanel;
    @Getter
    private final Module module;
    @Getter
    private final SmoothAnimationTimer enabledTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer hoveredTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer expandTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer settingsHeightTimer = new SmoothAnimationTimer();
    private float posX;
    private float posY;
    private float totalHeight = 20.0f;
    @Getter @Setter
    private float scrollOffset;
    @Getter @Setter
    private boolean isHovered;
    @Getter @Setter
    private boolean isExpanded;
    private static final String BUILD_TAG;

    public ModuleElement(CategoryPanel categoryPanel, Module module) {
        this.parentPanel = categoryPanel;
        this.module = module;
        for (Setting setting : module.getSettings()) {
            if (setting instanceof BooleanSetting booleanSetting) {
                this.settingElements.add(new BooleanSettingElement(categoryPanel, booleanSetting));
                continue;
            }
            if (setting instanceof ModeSetting modeSetting) {
                this.settingElements.add(new ModeSettingElement(categoryPanel, modeSetting));
                continue;
            }
            if (setting instanceof MultiSelectSetting multiSelectSetting) {
                this.settingElements.add(new MultiSelectSettingElement(categoryPanel, multiSelectSetting));
                continue;
            }
            if (!(setting instanceof NumberSetting numberSetting)) continue;
            this.settingElements.add(new NumberSettingElement(categoryPanel, numberSetting));
        }
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        float titleY;
        float titleWidth;
        float enabledAmount;
        float settingsTotalHeight = 0.0f;
        for (SettingElement settingElement : this.settingElements) {
            if (!settingElement.getSetting().getVisibility().displayable()) continue;
            settingsTotalHeight += settingElement.getHeight();
        }
        this.settingsHeightTimer.animate(settingsTotalHeight, 0.2, Easings.EASE_OUT_POW2);
        this.settingsHeightTimer.tick();
        this.parentPanel.setCollapsed(!this.settingsHeightTimer.isDone());
        this.hoveredTimer.animate(this.isHovered ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.hoveredTimer.tick();
        this.enabledTimer.animate(this.module.isEnabled() ? 1.0 : 0.0, 0.3, Easings.EASE_OUT_POW2);
        this.enabledTimer.tick();
        this.expandTimer.animate(this.isExpanded ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW3);
        this.expandTimer.tick();
        float expandAmount = this.expandTimer.getValueF();
        this.totalHeight = 20.0f + expandAmount * this.settingsHeightTimer.getValueF();
        this.isHovered = this.parentPanel.equals(NewClickGui.focusedPanel) && CursorUtil.isInBounds(mouseX, mouseY, this.posX, this.posY, 120.0f, this.totalHeight);
        RenderUtil.drawFilledRect(poseStack, this.posX + 1.0f, this.posY + 20.0f, 118.0f, this.totalHeight - 20.0f, ColorUtil.withAlpha(BG_COLOR, expandAmount * alpha));
        float hoverAmount = this.hoveredTimer.getValueF();
        if (hoverAmount > 0.0f) {
            RenderUtil.drawFilledRect(poseStack, this.posX + 0.5f, this.posY, 119.0f, 20.0f, ColorUtil.withAlpha(-1, 0.1f * alpha * hoverAmount));
        }
        if (1.0f - (enabledAmount = this.enabledTimer.getValueF()) > 0.0f) {
            FontStore.AXIFORMA_REGULAR_16.drawStringCentered(poseStack, this.module.getName(), this.posX + 60.0f, this.posY + (20.0f - FontStore.AXIFORMA_REGULAR_16.getFontHeight()) / 2.0f, ColorUtil.withAlpha(-1, alpha * (1.0f - enabledAmount) * 0.6f));
        }
        if (enabledAmount > 0.0f) {
            titleWidth = FontStore.AXIFORMA_BOLD_16.getStringWidth(this.module.getName());
            titleY = this.posY + (20.0f - FontStore.AXIFORMA_BOLD_16.getFontHeight()) / 2.0f;
            RenderUtil.drawShadow(poseStack, this.posX + (120.0f - titleWidth) / 2.0f, titleY + FontStore.AXIFORMA_BOLD_16.getFontHeight() / 4.0f, titleWidth, FontStore.AXIFORMA_BOLD_16.getFontHeight() / 2.0f, 12, ColorUtil.withAlpha(-13768502, alpha * enabledAmount * 0.36f));
            FontStore.AXIFORMA_BOLD_16.drawStringCentered(poseStack, this.module.getName(), this.posX + 60.0f, titleY, ColorUtil.withAlpha(-13768502, alpha * enabledAmount));
        }
        if (!this.module.getSettings().isEmpty()) {
            String arrowIcon = String.valueOf('\ueb4e');
            titleY = FontStore.MATERIAL_20.getStringWidth(arrowIcon);
            float arrowX = this.posX + 120.0f - titleY - 6.0f;
            float arrowY = this.posY + (20.0f - FontStore.MATERIAL_20.getFontHeight()) / 2.0f + 1.0f;
            RenderHelper.pushRotateAround(poseStack, arrowX + titleY / 2.0f, arrowY + FontStore.MATERIAL_20.getFontHeight() / 2.0f - 1.0f, 180.0f * expandAmount);
            FontStore.MATERIAL_20.drawString(poseStack, arrowIcon, arrowX, arrowY, ColorUtil.withAlpha(-1, (0.8f - 0.3f * expandAmount) * alpha));
            RenderHelper.popPose(poseStack);
        }
        if (this.isExpanded) {
            titleWidth = this.posY + 20.0f;
            for (SettingElement settingElement : this.settingElements) {
                settingElement.setX(this.posX);
                settingElement.setY(titleWidth);
                settingElement.render(clickGui, guiGraphics, poseStack, mouseX, mouseY, alpha * expandAmount, partialTicks);
                titleWidth += settingElement.getAnimatedHeight() * settingElement.getVisibilityTimer().getValueF();
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.isHovered) {
            return false;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.posX, this.posY, 120.0f, 20.0f)) {
            if (button == 0) {
                this.module.setEnabled(!this.module.isEnabled());
            } else if (button == 1 && !this.module.getSettings().isEmpty()) {
                this.isExpanded = !this.isExpanded;
            }
            return true;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.posX, this.posY + 20.0f, 120.0f, this.totalHeight - 20.0f)) {
            Iterator<SettingElement<?>> iterator = this.settingElements.iterator();
            while (iterator.hasNext() && !iterator.next().mouseClicked(mouseX, mouseY, button)) {
            }
        }
        return this.isHovered;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.isHovered && CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.posX, this.posY + 20.0f, 120.0f, this.totalHeight - 20.0f)) {
            Iterator<SettingElement<?>> iterator = this.settingElements.iterator();
            while (iterator.hasNext() && !iterator.next().mouseReleased(mouseX, mouseY, button)) {
            }
        }
        return this.isHovered;
    }

    @Override
    @Generated
    public SmoothAnimationTimer getAnimTimer() {
        return this.hoveredTimer;
    }

    @Generated
    public SmoothAnimationTimer getHoveredTimer() {
        return this.expandTimer;
    }

    @Generated
    public SmoothAnimationTimer getExpandTimer() {
        return this.settingsHeightTimer;
    }

    @Override
    @Generated
    public float getX() {
        return this.posX;
    }

    @Override
    @Generated
    public float getY() {
        return this.posY;
    }

    @Override
    @Generated
    public float getHeight() {
        return this.totalHeight;
    }

    @Override
    @Generated
    public void setX(float x) {
        this.posX = x;
    }

    @Override
    @Generated
    public void setY(float y) {
        this.posY = y;
    }

    @Override
    @Generated
    public void setHeight(float height) {
        this.totalHeight = height;
    }

    static {
        BUILD_TAG = "17";
        BG_COLOR = ColorUtil.fromRGB(32, 32, 32);
    }
}