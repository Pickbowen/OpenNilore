package shit.lizz.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.lizz.gui.NewClickGui;
import shit.lizz.gui.newclickgui.CategoryPanel;
import shit.lizz.gui.newclickgui.SettingElement;
import shit.lizz.render.FontStore;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.utils.animation.SmoothAnimationTimer;
import shit.lizz.utils.math.Easings;
import shit.lizz.utils.misc.CursorUtil;
import shit.lizz.utils.render.ColorUtil;
import shit.lizz.utils.render.RenderUtil;

public class BooleanSettingElement
extends SettingElement<BooleanSetting> {
    @Getter @Setter
    private boolean isTruncated;
    @Getter @Setter
    private boolean isHovered;
    @Getter
    private final SmoothAnimationTimer toggleTimer = new SmoothAnimationTimer();
    private static final String ELLIPSIS = "...";

    public BooleanSettingElement(CategoryPanel categoryPanel, BooleanSetting booleanSetting) {
        super(categoryPanel, booleanSetting);
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        this.isHovered = CursorUtil.isInBounds(mouseX, mouseY, this.x, this.y, 120.0f, this.getHeight());
        this.visibilityTimer.animate(this.setting.getVisibility().displayable() ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.visibilityTimer.tick();
        if (Mth.equal(alpha *= this.visibilityTimer.getValueF(), 0.0f)) {
            return;
        }
        String name = this.setting.getName();
        if (FontStore.AXIFORMA_REGULAR_14.getStringWidth(name) > 90.0f) {
            name = name.substring(0, 10);
            name = name + ELLIPSIS;
            this.isTruncated = true;
        }
        this.toggleTimer.animate(this.setting.getValue() != false ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.toggleTimer.tick();
        if (Mth.equal(alpha, 0.0f)) {
            return;
        }
        poseStack.pushPose();
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, name, this.x + 6.0f, this.y + (this.getHeight() - FontStore.AXIFORMA_REGULAR_14.getFontHeight()) / 2.0f, ColorUtil.withAlpha(-1, alpha * 0.8f));
        float toggleAmount = this.toggleTimer.getValueF();
        if (toggleAmount > 0.0f) {
            RenderUtil.drawShadow(poseStack, this.x + 120.0f - 20.0f - 6.0f, this.y + (this.getHeight() - 10.0f) / 2.0f, 20.0f, 10.0f, 12, ColorUtil.withAlpha(-13768502, 0.36f * alpha * toggleAmount));
        }
        RenderUtil.drawRoundedRect(poseStack, this.x + 120.0f - 20.0f - 6.0f, this.y + (this.getHeight() - 10.0f) / 2.0f, 20.0f, 10.0f, 4.0f, ColorUtil.withAlpha(ColorUtil.fromRGB(60, 60, 60), alpha));
        if (toggleAmount > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, this.x + 120.0f - 20.0f - 6.0f, this.y + (this.getHeight() - 10.0f) / 2.0f, 10.0f + 10.0f * toggleAmount, 10.0f, 4.0f, ColorUtil.withAlpha(CategoryPanel.ACCENT_COLOR, alpha * toggleAmount));
        }
        RenderUtil.drawRoundedRect(poseStack, this.x + 120.0f - 20.0f - 6.0f + 10.0f * toggleAmount, this.y + (this.getHeight() - 10.0f) / 2.0f, 10.0f, 10.0f, 4.8f, ColorUtil.withAlpha(-1, alpha));
        poseStack.popPose();
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
    public float getHeight() {
        return 18.0f;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.setting.getVisibility().displayable()) {
            return false;
        }
        if (this.isHovered && CursorUtil.isInBounds((float)mouseX, (float)mouseY, this.x, this.y, 120.0f, this.getHeight())) {
            this.setting.setValue(this.setting.getValue() == false);
            return true;
        }
        return false;
    }

}