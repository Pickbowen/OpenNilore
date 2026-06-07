package shit.lizz.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import shit.lizz.LizzClient;
import shit.lizz.gui.legacy.CategoryPanel;
import shit.lizz.gui.legacy.ModuleButton;
import shit.lizz.modules.Category;

public class OldClickGui
extends Screen {
    private final List<CategoryPanel> categoryPanels = new ArrayList<>();
    private static final String TITLE = "Click GUI";

    public OldClickGui() {
        super(Component.nullToEmpty(TITLE));
        int x = 20;
        for (Category category : Category.values()) {
            this.categoryPanels.add(new CategoryPanel(x, 20, 140, 20, category));
            x += 160;
        }
    }

    public void init() {
        super.init();
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            for (ModuleButton moduleButton : categoryPanel.moduleButtons) {
                moduleButton.reset();
            }
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.render(guiGraphics, mouseX, mouseY, partialTicks);
            categoryPanel.mouseDragged(mouseX, mouseY);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseClicked(mouseX, mouseY, button);
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseScrolled(mouseX, mouseY, scrollDelta);
        }
        return true;
    }

    public void onClose() {
        if (LizzClient.isReady()) {
            LizzClient.instance.getConfigManager().saveAll();
        }
        super.onClose();
    }

    static {
        OldClickGui oldClickGui = new OldClickGui();
    }
}