package shit.lizz.gui.panel.setting;

import net.minecraft.client.gui.GuiGraphics;
import shit.lizz.settings.Setting;

public interface SettingRenderer {
    int render(GuiGraphics guiGraphics, Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, float alpha, float scale);

    boolean onClick(Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, int button, float scale);

    boolean supports(Setting<?> setting);

    default int getHeight(Setting<?> setting, float scale) {
        return Math.round(20.0f * scale);
    }

    default void onMouseMove(double mouseX, double mouseY) {
    }

    void onMouseRelease(double mouseX, double mouseY, int button);
}
