package shit.nilore.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.nilore.NiloreClient;
import shit.nilore.gui.NewClickGui;
import shit.nilore.gui.newclickgui.ModuleElement;
import shit.nilore.gui.newclickgui.SettingElement;
import shit.nilore.gui.newclickgui.UIElement;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.render.FontStore;
import shit.nilore.render.StencilHelper;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.misc.CursorUtil;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.RenderHelper;
import shit.nilore.utils.render.RenderUtil;

public class CategoryPanel
extends UIElement {
    public static final int BG_COLOR = ColorUtil.fromRGB(23, 23, 23);
    public static final int ACCENT_COLOR_DARK = new Color(-13768502).darker().darker().getRGB();
    public static final int ACCENT_COLOR = new Color(-13768502).darker().getRGB();
    @Getter
    private final List<ModuleElement> moduleElements = new ArrayList<>();
    @Getter
    private final Category category;
    private float posX;
    private float posY;
    private float panelHeight;
    @Getter @Setter
    private boolean isHovered;
    @Getter @Setter
    private boolean isDragging;
    @Getter @Setter
    private float dragOffsetX;
    @Getter @Setter
    private float dragOffsetY;
    @Getter @Setter
    private float scrollAmount;
    @Getter @Setter
    private float prevHeight;
    @Getter
    private final SmoothAnimationTimer scaleTimer = new SmoothAnimationTimer();
    @Getter
    private final SmoothAnimationTimer scrollTimer = new SmoothAnimationTimer();
    @Getter
    private final SmoothAnimationTimer tooltipTimer = new SmoothAnimationTimer();
    @Getter
    private final SmoothAnimationTimer collapseTimer = new SmoothAnimationTimer();
    @Getter @Setter
    private SettingElement<?> hoveredSettingElement;
    @Getter @Setter
    private String tooltipText = "";
    @Getter @Setter
    private String tooltipText2 = "";
    @Getter @Setter
    private boolean isCollapsed;
    @Getter @Setter
    private boolean showTooltip;
    @Getter @Setter
    private float savedPosX;
    @Getter @Setter
    private float savedPosY;
    @Getter @Setter
    private float savedScrollAmount;
    @Getter @Setter
    private float savedHeight;

    public CategoryPanel(Category category) {
        this.category = category;
        for (Module module : NiloreClient.getInstance().getModuleManager().getModulesByCategory(category)) {
            this.moduleElements.add(new ModuleElement(this, module));
        }
        this.panelHeight = 20.0f + Math.min(240.0f, 20.0f * (float)this.moduleElements.size());
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        this.isHovered = CursorUtil.isInBounds(mouseX, mouseY, this.posX, this.posY, 120.0f, this.panelHeight);
        if (this.isHovered) {
            NewClickGui.focusedPanel = this;
        }
        this.scaleTimer.animate(clickGui.isClosing() ? 0.0 : 1.0, clickGui.isClosing() ? 0.22 : 0.32, Easings.BACK_OUT);
        this.scaleTimer.tick();
        float totalContentHeight = 0.0f;
        for (ModuleElement moduleElement : this.moduleElements) {
            totalContentHeight += moduleElement.getHeight();
        }
        if (this.moduleElements.size() < 10) {
            this.panelHeight = Math.min(totalContentHeight, 240.0f) + 20.0f;
        }
        this.scrollAmount = Mth.clamp(this.scrollAmount, 0.0f, totalContentHeight - this.panelHeight + 20.0f);
        this.scrollTimer.animate(this.scrollAmount, 0.22, Easings.EASE_OUT_POW2);
        this.scrollTimer.tick();
        this.tooltipTimer.animate(this.showTooltip ? 1.0 : 0.0, 0.3, Easings.EASE_OUT_POW2);
        this.tooltipTimer.tick();
        if (this.isDragging) {
            this.posX = (float)mouseX + this.dragOffsetX;
            this.posY = (float)mouseY + this.dragOffsetY;
        }
        this.collapseTimer.animate(this.isCollapsed ? 0.0 : 1.0, 0.2, Easings.EASE_OUT_POW2);
        this.collapseTimer.tick();
        float collapseAmount = this.collapseTimer.getValueF();
        if (!this.isCollapsed) {
            this.prevHeight = this.panelHeight;
        }
        float scaleAmount = this.scaleTimer.getValueF();
        RenderHelper.pushScaleAround(poseStack, this.posX + 60.0f, this.posY + this.panelHeight / 2.0f, 0.4f + 0.6f * scaleAmount);
        float shadowSize = 12.0f;
        RenderUtil.drawRoundedRect(poseStack, this.posX - shadowSize, this.posY - shadowSize, 120.0f + shadowSize * 2.0f, this.panelHeight + shadowSize * 2.0f, 6.0f + shadowSize / 2.0f, shadowSize, ColorUtil.fromARGB(0, 0, 0, (int)(80.0f * alpha * 1.0f)));
        RenderUtil.drawRoundedRect(poseStack, this.posX, this.posY, 120.0f, this.panelHeight, 6.0f, ColorUtil.withAlpha(BG_COLOR, alpha));
        StencilHelper.beginWrite(false);
        RenderUtil.drawRoundedRect(poseStack, this.posX + 0.5f, this.posY, 118.0f, 20.0f, 6.0f, -1);
        StencilHelper.beginRead(true);
        RenderUtil.drawGradientH(poseStack, this.posX, this.posY, 120.0f, 1.0f, ColorUtil.withAlpha(ColorUtil.animateColorOffset(-13768502, ACCENT_COLOR_DARK, 100L), alpha), ColorUtil.withAlpha(ColorUtil.animateColorOffset(-13768502, ACCENT_COLOR_DARK, 2000L), alpha));
        StencilHelper.end();
        FontStore.AXIFORMA_EXTRABOLD_18.drawString(poseStack, this.category.displayName, this.posX + 8.0f, this.posY + (20.0f - FontStore.AXIFORMA_EXTRABOLD_18.getFontHeight()) / 2.0f + 3.0f, ColorUtil.withAlpha(-1, alpha));
        float scrollOffset = this.scrollTimer.getValueF();
        float elementY = this.posY + 20.0f - scrollOffset;
        StencilHelper.beginWrite(false);
        RenderUtil.drawFilledRect(poseStack, this.posX + 0.5f, this.posY + 20.0f, 119.0f, 6.0f, -1);
        RenderUtil.drawRoundedRect(poseStack, this.posX, this.posY + 20.0f, 120.0f, this.panelHeight - 20.0f - 0.5f, 6.0f, -1);
        StencilHelper.beginRead(true);
        for (ModuleElement moduleElement : this.moduleElements) {
            moduleElement.setX(this.posX);
            moduleElement.setY(elementY);
            moduleElement.render(clickGui, guiGraphics, poseStack, mouseX, mouseY, alpha, partialTicks);
            elementY += moduleElement.getHeight();
        }
        RenderUtil.drawGradientV(poseStack, this.posX + 0.5f, this.posY + 20.0f - 0.5f, 119.0f, 6.0f, ColorUtil.withAlpha(-16777216, 0.36f * alpha), ColorUtil.withAlpha(-16777216, 0.0f));
        StencilHelper.end();
        float tooltipAmount = this.tooltipTimer.getValueF();
        if (tooltipAmount > 0.0f) {
            float tooltipWidth = FontStore.AXIFORMA_REGULAR_16.getStringWidth(this.tooltipText);
            RenderUtil.drawShadow(poseStack, mouseX + 5, mouseY + 5, tooltipWidth + 6.0f, FontStore.AXIFORMA_REGULAR_16.getFontHeight() + 4.0f, 12, ColorUtil.withAlpha(BG_COLOR, alpha * tooltipAmount * 0.66f));
            RenderUtil.drawRoundedRect(poseStack, mouseX + 5, mouseY + 5, tooltipWidth + 6.0f, FontStore.AXIFORMA_REGULAR_16.getFontHeight() + 4.0f, 3.0f, ColorUtil.withAlpha(BG_COLOR, alpha * tooltipAmount));
            FontStore.AXIFORMA_REGULAR_16.drawString(poseStack, this.tooltipText, mouseX + 5 + 3, mouseY + 5 + 1, ColorUtil.withAlpha(-1, alpha * tooltipAmount));
        }
        RenderHelper.popPose(poseStack);
    }

    @Override
    public void reset() {
        this.scaleTimer.setFromValue(0.0);
        this.scaleTimer.setCurrentValue(0.0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isHovered) {
            if (CursorUtil.isInBounds((float)mouseX, (float)mouseY, this.posX, this.posY, 120.0f, 20.0f)) {
                this.isDragging = true;
                this.dragOffsetX = this.posX - (float)mouseX;
                this.dragOffsetY = this.posY - (float)mouseY;
            } else if (CursorUtil.isInBounds((float)mouseX, (float)mouseY, this.posX, this.posY + 20.0f, 120.0f, this.panelHeight - 20.0f)) {
                for (ModuleElement moduleElement : this.moduleElements) {
                    if (!moduleElement.mouseClicked(mouseX, mouseY, button)) continue;
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (CursorUtil.isInBounds((float)mouseX, (float)mouseY, this.posX, this.posY + 20.0f, 120.0f, this.panelHeight - 20.0f)) {
            for (ModuleElement moduleElement : this.moduleElements) {
                if (!moduleElement.mouseReleased(mouseX, mouseY, button)) continue;
                return true;
            }
        }
        this.isDragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        if (this.isHovered) {
            this.scrollAmount -= (float)scrollDelta * 50.0f;
            return true;
        }
        return false;
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
        return this.panelHeight;
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
        this.panelHeight = height;
    }

    }