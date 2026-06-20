package shit.nilore.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import shit.nilore.gui.legacy.ModuleButton;
import shit.nilore.settings.Setting;

public class SettingComponent {
    public Setting<?> setting;
    public ModuleButton parentButton;
    public int yOffset;

    public SettingComponent(Setting<?> setting, ModuleButton moduleButton, int yOffset) {
        this.setting = setting;
        this.parentButton = moduleButton;
        this.yOffset = yOffset;
    }

    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        this.renderWithAlpha(poseStack, mouseX, mouseY, partialTicks, 1.0f);
    }

    public void renderWithAlpha(PoseStack poseStack, int mouseX, int mouseY, float partialTicks, float alpha) {
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
    }

    public boolean isHovered(double mouseX, double mouseY) {
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset;
        int rowHeight = this.parentButton.panel.rowHeight;
        return mouseX >= (double)this.parentButton.panel.x && mouseX <= (double)(this.parentButton.panel.x + this.parentButton.panel.width) && mouseY >= (double)rowY && mouseY <= (double)(rowY + rowHeight);
    }
}