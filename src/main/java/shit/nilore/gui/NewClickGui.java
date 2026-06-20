package shit.nilore.gui;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import shit.nilore.gui.newclickgui.CategoryPanel;
import shit.nilore.modules.Category;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;

public class NewClickGui
extends Screen {
    private static final List<CategoryPanel> categoryPanels;
    public static CategoryPanel focusedPanel;
    @Getter
    private boolean closing = false;
    @Getter
    private final SmoothAnimationTimer closeAnim = new SmoothAnimationTimer();

    public NewClickGui() {
        super(Component.literal("ClickGui"));
        // System.out.println("12");
    }

    protected void init() {
        // System.out.println("13");
        focusedPanel = categoryPanels.get(0);
        float panelX = (float)this.width / 2.0f - 380.0f;
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.setX(panelX);
            categoryPanel.setY(36.0f);
            panelX += 128.0f;
        }
        // System.out.println("14");
    }

    public void render(@NonNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.closeAnim.animate(this.closing ? 0.0 : 1.0, 0.2, Easings.EASE_OUT_POW2);
        this.closeAnim.tick();
        float closeProgress = this.closeAnim.getValueF();
        if (Mth.equal(closeProgress, 0.0f) && this.closing) {
            this.closing = false;
            super.onClose();
            categoryPanels.forEach(CategoryPanel::reset);
            return;
        }
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.render(this, guiGraphics, guiGraphics.pose(), mouseX, mouseY, closeProgress, partialTicks);
        }
    }

    public void onClose() {
        this.closing = true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : categoryPanels) {
            if (!categoryPanel.mouseClicked(mouseX, mouseY, button)) continue;
            focusedPanel = categoryPanel;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.mouseReleased(mouseX, mouseY, button);
        }
        return false;
    }

    public boolean isPauseScreen() {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        for (CategoryPanel categoryPanel : categoryPanels) {
            if (!categoryPanel.mouseScrolled(mouseX, mouseY, scrollDelta)) continue;
            return true;
        }
        return false;
    }

    static {
        categoryPanels = new ArrayList<>();
        for (Category category : Category.values()) {
            categoryPanels.add(new CategoryPanel(category));
        }
    }
}