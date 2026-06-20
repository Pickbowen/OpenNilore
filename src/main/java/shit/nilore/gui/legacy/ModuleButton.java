package shit.nilore.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import shit.nilore.gui.legacy.BooleanComponent;
import shit.nilore.gui.legacy.CategoryPanel;
import shit.nilore.gui.legacy.ModeComponent;
import shit.nilore.gui.legacy.MultiSelectComponent;
import shit.nilore.gui.legacy.NumberComponent;
import shit.nilore.gui.legacy.SettingComponent;
import shit.nilore.modules.Module;
import shit.nilore.render.FontStore;
import shit.nilore.settings.Setting;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.MultiSelectSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.RenderUtil;

public class ModuleButton {
    public Module module;
    public CategoryPanel panel;
    public int yOffset;
    public List<SettingComponent> settingComponents;
    public boolean expanded;
    private final SmoothAnimationTimer expandAnim;
    private float hoverProgress = 0.0f;
    private final float hoverSpeed = 4.0f;
    private long lastTime = System.currentTimeMillis();

    public ModuleButton(Module module, CategoryPanel categoryPanel, int yOffset) {
        this.module = module;
        this.panel = categoryPanel;
        this.yOffset = yOffset;
        this.expanded = false;
        this.settingComponents = new ArrayList<>();
        for (Setting setting : module.getSettings()) {
            if (setting instanceof BooleanSetting) {
                this.settingComponents.add(new BooleanComponent(setting, this, 0));
                continue;
            }
            if (setting instanceof ModeSetting) {
                this.settingComponents.add(new ModeComponent(setting, this, 0));
                continue;
            }
            if (setting instanceof NumberSetting) {
                this.settingComponents.add(new NumberComponent(setting, this, 0));
                continue;
            }
            if (!(setting instanceof MultiSelectSetting)) continue;
            this.settingComponents.add(new MultiSelectComponent(setting, this, 0));
        }
        this.expandAnim = new SmoothAnimationTimer();
    }

    public int getTotalHeight() {
        if (!this.expanded) {
            return this.panel.rowHeight;
        }
        int total = this.panel.rowHeight;
        List<SettingComponent> visibleComponents = this.settingComponents.stream().filter(settingComponent -> settingComponent.setting.getVisibility().displayable()).collect(Collectors.toList());
        for (SettingComponent settingComponent : visibleComponents) {
            total += this.panel.rowHeight;
            if (settingComponent instanceof ModeComponent mode && mode.isDropdownOpen()) {
                total += mode.getDropdownHeight();
            }
            if (settingComponent instanceof MultiSelectComponent multi && multi.isDropdownOpen()) {
                total += multi.getDropdownHeight();
            }
        }
        return total;
    }

    public int getExpandedHeight() {
        int total = 0;
        List<SettingComponent> visibleComponents = this.settingComponents.stream().filter(settingComponent -> settingComponent.setting.getVisibility().displayable()).collect(Collectors.toList());
        for (SettingComponent settingComponent : visibleComponents) {
            total += this.panel.rowHeight;
            if (settingComponent instanceof ModeComponent mode && mode.isDropdownOpen()) {
                total += mode.getDropdownHeight();
            }
            if (settingComponent instanceof MultiSelectComponent multi && multi.isDropdownOpen()) {
                total += multi.getDropdownHeight();
            }
        }
        return total;
    }

    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        float expandedHeight;
        this.expandAnim.tick();
        long now = System.currentTimeMillis();
        float deltaSeconds = (float)(now - this.lastTime) / 1000.0f;
        this.lastTime = now;
        if (this.isHovered(mouseX, mouseY)) {
            if (this.hoverProgress < 1.0f) {
                this.hoverProgress += deltaSeconds * 4.0f;
            }
            if (this.hoverProgress > 1.0f) {
                this.hoverProgress = 1.0f;
            }
        } else {
            if (this.hoverProgress > 0.0f) {
                this.hoverProgress -= deltaSeconds * 4.0f;
            }
            if (this.hoverProgress < 0.0f) {
                this.hoverProgress = 0.0f;
            }
        }
        int bgAlpha = (int)(160.0f + 40.0f * this.hoverProgress);
        int rowY = this.panel.y + this.yOffset - (this.yOffset == 0 ? 0 : 1);
        int rowHeight = this.panel.rowHeight + (this.yOffset == 0 ? 0 : 1);
        RenderUtil.drawBlurredRect(poseStack, this.panel.x, rowY, this.panel.width, rowHeight, 4.0f, 6.0f, 0.9f, 0);
        RenderUtil.drawFilledRect(poseStack, this.panel.x, rowY, this.panel.width, rowHeight, new Color(21, 21, 21, bgAlpha).getRGB());
        String name = this.module.getName();
        float nameWidth = FontStore.OPENSANS_16.getStringWidth(name);
        float nameX = (float)this.panel.x + (float)this.panel.width / 2.0f - nameWidth / 2.0f;
        float nameY = (float)(this.panel.y + this.yOffset) + (float)this.panel.rowHeight / 2.0f - FontStore.OPENSANS_16.getFontHeight() / 2.0f;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, name, nameX, nameY, this.module.isEnabled() ? ColorUtil.fromARGB(138, 180, 248, 255) : -1);
        if (!this.module.getSettings().isEmpty()) {
            poseStack.pushPose();
            expandedHeight = this.panel.x + this.panel.width - 15;
            float arrowY = (float)(this.panel.y + this.yOffset) + (float)this.panel.rowHeight / 2.0f;
            float arrowAngle = 180.0f * this.expandAnim.getValueF();
            poseStack.translate(expandedHeight, arrowY, 0.0f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(arrowAngle));
            poseStack.translate(-expandedHeight, -arrowY, 0.0f);
            FontStore.MATERIAL_20.drawStringWithShadow(poseStack, "", expandedHeight - FontStore.MATERIAL_20.getStringWidth("") / 2.0f, arrowY - FontStore.MATERIAL_20.getFontHeight() / 2.0f, -1);
            poseStack.popPose();
        }
        if ((expandedHeight = (float)this.getExpandedHeight() * this.expandAnim.getValueF()) > 0.0f) {
            int contentY = this.panel.y + this.yOffset + this.panel.rowHeight - 1;
            RenderUtil.drawBlurredRect(poseStack, this.panel.x, contentY, this.panel.width, (int)expandedHeight + 1, 4.0f, 6.0f, 0.9f, 0);
            RenderUtil.pushScissor(this.panel.x, contentY, this.panel.width, (int)expandedHeight + 1);
            RenderUtil.drawFilledRect(poseStack, this.panel.x, contentY, this.panel.width, (int)expandedHeight + 1, new Color(11, 11, 11, 150).getRGB());
            List<SettingComponent> visibleComponents = this.settingComponents.stream().filter(settingComponent -> settingComponent.setting.getVisibility().displayable()).collect(Collectors.toList());
            int componentOffsetY = 0;
            for (SettingComponent settingComponent : visibleComponents) {
                SettingComponent ref;
                settingComponent.yOffset = componentOffsetY;
                settingComponent.renderWithAlpha(poseStack, mouseX, mouseY, partialTicks, 1.0f);
                componentOffsetY += this.panel.rowHeight;
                if (settingComponent instanceof ModeComponent && ((ModeComponent)(ref = settingComponent)).isDropdownOpen()) {
                    componentOffsetY += ((ModeComponent)ref).getDropdownHeight();
                }
                if (!(settingComponent instanceof MultiSelectComponent) || !((MultiSelectComponent)(ref = settingComponent)).isDropdownOpen()) continue;
                componentOffsetY += ((MultiSelectComponent)ref).getDropdownHeight();
            }
            RenderUtil.popScissor();
        }
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isHovered(mouseX, mouseY)) {
            if (button == 0) {
                this.module.setEnabled(!this.module.isEnabled());
            } else if (button == 1 && !this.module.getSettings().isEmpty()) {
                this.expanded = !this.expanded;
                this.expandAnim.animate(this.expanded ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_QUAD);
                this.panel.recalcLayout();
            }
        }
        if (this.expanded) {
            List<SettingComponent> visibleComponents = this.settingComponents.stream().filter(settingComponent -> settingComponent.setting.getVisibility().displayable()).collect(Collectors.toList());
            int componentOffsetY = 0;
            for (SettingComponent settingComponent : visibleComponents) {
                SettingComponent ref;
                settingComponent.yOffset = componentOffsetY;
                if (settingComponent.isHovered(mouseX, mouseY)) {
                    settingComponent.mouseClicked(mouseX, mouseY, button);
                }
                componentOffsetY += this.panel.rowHeight;
                if (settingComponent instanceof ModeComponent && ((ModeComponent)(ref = settingComponent)).isDropdownOpen()) {
                    componentOffsetY += ((ModeComponent)ref).getDropdownHeight();
                }
                if (!(settingComponent instanceof MultiSelectComponent) || !((MultiSelectComponent)(ref = settingComponent)).isDropdownOpen()) continue;
                componentOffsetY += ((MultiSelectComponent)ref).getDropdownHeight();
            }
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (this.expanded) {
            for (SettingComponent settingComponent : this.settingComponents) {
                settingComponent.mouseReleased(mouseX, mouseY, button);
            }
        }
    }

    public void mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
    }

    public void reset() {
    }

    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= (double)this.panel.x && mouseX <= (double)(this.panel.x + this.panel.width) && mouseY >= (double)(this.panel.y + this.yOffset) && mouseY <= (double)(this.panel.y + this.yOffset + this.panel.rowHeight);
    }

    public boolean isAnimating() {
        return !this.expandAnim.isDone();
    }
}
