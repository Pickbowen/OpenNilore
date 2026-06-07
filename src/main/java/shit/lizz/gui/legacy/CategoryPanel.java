package shit.lizz.gui.legacy;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import shit.lizz.LizzClient;
import shit.lizz.gui.legacy.ModuleButton;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.render.FontStore;
import shit.lizz.utils.render.RenderUtil;

public class CategoryPanel {
    public int x;
    public int y;
    public int dragOffsetX;
    public int dragOffsetY;
    public int width;
    public int rowHeight;
    public Category category;
    public boolean dragging;
    public boolean expanded;
    public List<ModuleButton> moduleButtons;
    private float[] targetOffsets;
    private float[] currentOffsets;
    private final float lerpFactor = 0.2f;
    private long lastTime = System.currentTimeMillis();
    private boolean needsLayout = false;

    public CategoryPanel(int x, int y, int width, int rowHeight, Category category) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.rowHeight = rowHeight;
        this.category = category;
        this.dragging = false;
        this.expanded = false;
        this.moduleButtons = new ArrayList<>();
        int buttonY = rowHeight;
        for (Module module : LizzClient.getInstance().getModuleManager().getModulesByCategory(category)) {
            this.moduleButtons.add(new ModuleButton(module, this, buttonY));
            buttonY += rowHeight;
        }
        this.initOffsets();
    }

    private void initOffsets() {
        this.targetOffsets = new float[this.moduleButtons.size()];
        this.currentOffsets = new float[this.moduleButtons.size()];
        for (int i = 0; i < this.moduleButtons.size(); ++i) {
            this.targetOffsets[i] = this.rowHeight + i * this.rowHeight;
            this.currentOffsets[i] = this.rowHeight + i * this.rowHeight;
        }
    }

    public void tick() {
        if (!this.needsLayout) {
            return;
        }
        boolean stillAnimating = false;
        long now = System.currentTimeMillis();
        float deltaSeconds = (float)(now - this.lastTime) / 1000.0f;
        this.lastTime = now;
        for (ModuleButton moduleButton : this.moduleButtons) {
            if (!moduleButton.isAnimating()) continue;
            stillAnimating = true;
        }
        for (int i = 0; i < this.moduleButtons.size(); ++i) {
            float diff = this.targetOffsets[i] - this.currentOffsets[i];
            if (Math.abs(diff) > 0.5f) {
                int idx = i;
                this.currentOffsets[idx] = this.currentOffsets[idx] + diff * 0.2f * (deltaSeconds * 60.0f);
                stillAnimating = true;
                this.moduleButtons.get(i).yOffset = (int)this.currentOffsets[i];
                continue;
            }
            this.currentOffsets[i] = this.targetOffsets[i];
            this.moduleButtons.get(i).yOffset = (int)this.targetOffsets[i];
        }
        this.needsLayout = stillAnimating;
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.tick();
        RenderUtil.drawFilledRect(guiGraphics.pose(), this.x, this.y, this.width, this.rowHeight, new Color(16, 16, 20, 245).getRGB());
        String iconChar = String.valueOf(switch (this.category) {
            case COMBAT -> 'a';
            case MOVEMENT -> 'b';
            case PLAYER -> 'c';
            case RENDER -> 'd';
            case EXPLOIT -> 'e';
            case WORLD -> 'f';
            case MISC -> 'g';
        });
        float iconY = (float)this.y + ((float)this.rowHeight / 2.0f - FontStore.ICON_30.getFontHeight() / 2.0f) + 3.0f;
        FontStore.ICON_30.drawStringWithShadow(guiGraphics.pose(), iconChar, this.x + 4, iconY, -1);
        float labelY = (float)this.y + ((float)this.rowHeight / 2.0f - FontStore.OPENSANS_18.getFontHeight() / 2.0f) - 0.5f;
        FontStore.OPENSANS_18.drawStringWithShadow(guiGraphics.pose(), this.category.displayName, this.x + this.rowHeight + 4, labelY, -1);
        for (ModuleButton moduleButton : this.moduleButtons) {
            moduleButton.render(guiGraphics.pose(), mouseX, mouseY, partialTicks);
        }
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isHovered(mouseX, mouseY)) {
            if (button == 0) {
                this.dragging = true;
                this.dragOffsetX = (int)(mouseX - (double)this.x);
                this.dragOffsetY = (int)(mouseY - (double)this.y);
            } else if (button == 1) {
                this.expanded = !this.expanded;
                this.recalcLayout();
            }
        }
        for (ModuleButton moduleButton : this.moduleButtons) {
            moduleButton.mouseClicked(mouseX, mouseY, button);
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        for (ModuleButton moduleButton : this.moduleButtons) {
            moduleButton.mouseReleased(mouseX, mouseY, button);
        }
        if (button == 0 && this.dragging) {
            this.dragging = false;
        }
    }

    public void mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        for (ModuleButton moduleButton : this.moduleButtons) {
            moduleButton.mouseScrolled(mouseX, mouseY, scrollDelta);
        }
    }

    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX > (double)this.x && mouseX < (double)(this.x + this.width) && mouseY > (double)this.y && mouseY < (double)(this.y + this.rowHeight);
    }

    public void mouseDragged(double mouseX, double mouseY) {
        if (this.dragging) {
            this.x = (int)(mouseX - (double)this.dragOffsetX);
            this.y = (int)(mouseY - (double)this.dragOffsetY);
        }
    }

    public void recalcLayout() {
        int currentY = this.rowHeight;
        for (int i = 0; i < this.moduleButtons.size(); ++i) {
            ModuleButton moduleButton = this.moduleButtons.get(i);
            this.targetOffsets[i] = currentY;
            currentY += moduleButton.getTotalHeight();
        }
        this.needsLayout = true;
        this.lastTime = System.currentTimeMillis();
    }
}
