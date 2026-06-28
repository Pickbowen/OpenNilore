package client.nilore.gui.material3.setting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.settings.Setting;

public final class MD3SettingRegistry {

    private static final MD3SettingRegistry INSTANCE = new MD3SettingRegistry();
    private final List<MD3SettingRenderer> renderers = new ArrayList<>();

    private MD3SettingRegistry() {
        renderers.add(new MD3ModeRenderer());
        renderers.add(new MD3BooleanRenderer());
        renderers.add(new MD3NumberRenderer());
        renderers.add(new MD3MultiSelectRenderer());
    }

    public static MD3SettingRegistry get() { return INSTANCE; }

    public MD3SettingRenderer find(Setting<?> s) {
        for (MD3SettingRenderer r : renderers) {
            if (r.supports(s)) return r;
        }
        return null;
    }

    public int render(GuiGraphics gg, Setting<?> s, int x, int y, int w, int mx, int my, float alpha, float accent) {
        MD3SettingRenderer r = find(s);
        return r != null ? r.render(gg, s, x, y, w, mx, my, alpha, accent) : 0;
    }

    public boolean onClick(Setting<?> s, int x, int y, int w, int mx, int my, int btn, float scale) {
        MD3SettingRenderer r = find(s);
        return r != null && r.onClick(s, x, y, w, mx, my, btn, scale);
    }

    public int getHeight(Setting<?> s) {
        MD3SettingRenderer r = find(s);
        return r != null ? r.getHeight(s) : 0;
    }

    public void onMouseRelease(double mx, double my, int btn) {
        for (MD3SettingRenderer r : renderers) r.onMouseRelease(mx, my, btn);
    }

    public void onMouseDrag(Setting<?> s, double mx, double my, int btn) {
        MD3SettingRenderer r = find(s);
        if (r != null) r.onMouseDrag(s, mx, my, btn);
    }
}
