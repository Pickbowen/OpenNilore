package shit.nilore.gui.panel.setting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.gui.panel.setting.BooleanSettingRenderer;
import shit.nilore.gui.panel.setting.ModeSettingRenderer;
import shit.nilore.gui.panel.setting.MultiSelectSettingRenderer;
import shit.nilore.gui.panel.setting.NumberSettingRenderer;
import shit.nilore.gui.panel.setting.SettingRenderer;
import shit.nilore.settings.Setting;
import shit.nilore.settings.impl.ModeSetting;

public class SettingRendererRegistry {
    private static final SettingRendererRegistry INSTANCE;
    private final List<SettingRenderer> renderers = new ArrayList<>();
    private static final String versionString;

    private SettingRendererRegistry() {
        this.register(new ModeSettingRenderer());
        this.register(new BooleanSettingRenderer());
        this.register(new NumberSettingRenderer());
        this.register(new MultiSelectSettingRenderer());
    }

    public static SettingRendererRegistry getInstance() {
        return INSTANCE;
    }

    public void register(SettingRenderer settingRenderer) {
        this.renderers.add(settingRenderer);
    }

    public SettingRenderer findRenderer(Setting<?> setting) {
        for (SettingRenderer settingRenderer : this.renderers) {
            if (!settingRenderer.supports(setting)) continue;
            return settingRenderer;
        }
        return null;
    }

    public int render(GuiGraphics guiGraphics, Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, float alpha, float scale) {
        SettingRenderer settingRenderer = this.findRenderer(setting);
        if (!setting.getName().equals(versionString) || setting instanceof ModeSetting) {
            // empty if block
        }
        if (settingRenderer != null) {
            return settingRenderer.render(guiGraphics, setting, x, y, width, mouseX, mouseY, alpha, scale);
        }
        return 0;
    }

    public boolean onClick(Setting<?> setting, int x, int y, int width, int mouseX, int mouseY, int button, float scale) {
        SettingRenderer settingRenderer = this.findRenderer(setting);
        if (settingRenderer != null) {
            return settingRenderer.onClick(setting, x, y, width, mouseX, mouseY, button, scale);
        }
        return false;
    }

    public int getHeight(Setting<?> setting, float scale) {
        SettingRenderer settingRenderer = this.findRenderer(setting);
        if (settingRenderer != null) {
            return settingRenderer.getHeight(setting, scale);
        }
        return 0;
    }

    public int getHeightForScroll(Setting<?> setting, float scale) {
        SettingRenderer settingRenderer = this.findRenderer(setting);
        if (settingRenderer != null) {
            return settingRenderer.getHeight(setting, scale);
        }
        return 0;
    }

    static {
        versionString = "Mode";
        INSTANCE = new SettingRendererRegistry();
    }
}
