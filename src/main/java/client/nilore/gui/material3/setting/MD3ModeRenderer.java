package client.nilore.gui.material3.setting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.material3.MD3Theme;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.utils.math.LerpUtil;
import client.nilore.utils.render.RenderUtil;

public class MD3ModeRenderer implements MD3SettingRenderer {

    private static final int ITEM_H = 20;
    private final Map<ModeSetting, Boolean> openState = new HashMap<>();
    private final Map<ModeSetting, Float> openAnim = new HashMap<>();
    private final Map<ModeSetting, Map<String, Float>> itemHover = new HashMap<>();

    @Override
    public int render(GuiGraphics gg, Setting<?> s, int x, int y, int w, int mx, int my, float alpha, float accent) {
        if (!(s instanceof ModeSetting ms)) return 0;

        boolean open = openState.getOrDefault(ms, false);
        float cur = openAnim.getOrDefault(ms, 0f);
        openAnim.put(ms, LerpUtil.smoothLerp(cur, open ? 1f : 0f, open ? 0.15f : 0.2f));
        float of = openAnim.getOrDefault(ms, 0f);

        String[] others = Arrays.stream((Object[])ms.getModes())
                .filter(m -> !Objects.equals(m, ms.getValue())).toArray(String[]::new);
        int expandedH = (int)(others.length * ITEM_H * of);
        int h = getHeight(ms);

        // Label
        FontRenderer lf = MD3Theme.fontBodyLarge(1f);
        MD3Theme.text(ms.getName(), x + 2f, y + 4f, lf, MD3Theme.TEXT_HIGH, alpha);

        // Current value chip
        FontRenderer vf = MD3Theme.fontLabelLarge(1f);
        String curVal = ms.getValue() != null ? ms.getValue() : "None";
        float vw = GlHelper.getStringWidth(curVal, vf);
        float chipW = vw + 12f, chipH = 14f;
        float chipX = x + w - chipW - 2f, chipY = y + 3f;
        RenderUtil.drawRoundedRect(gg.pose(), chipX, chipY, chipW, chipH, chipH / 2f,
                MD3Theme.withAlpha(MD3Theme.SURFACE_HIGHEST, alpha));
        MD3Theme.text(curVal, chipX + 6f, chipY + (chipH - vf.getMetrics().capHeight()) / 2f,
                vf, (int)accent, alpha);

        // Dropdown items
        if (of > 0.01f) {
            itemHover.putIfAbsent(ms, new HashMap<>());
            Map<String, Float> hMap = itemHover.get(ms);
            int itemY = y + 20;
            for (String mode : others) {
                boolean hov = mx >= x && mx <= x + w && my >= itemY && my <= itemY + ITEM_H;
                float hc = hMap.getOrDefault(mode, 0f);
                hMap.put(mode, LerpUtil.smoothLerp(hc, hov ? 1f : 0f, 0.2f));
                float hv = hMap.get(mode);

                // Hover bg
                if (hv > 0.01f) {
                    RenderUtil.drawRoundedRect(gg.pose(), x + 2f, itemY, w - 4f, ITEM_H, 4f,
                            MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, alpha * of * hv));
                }

                // Mode text
                FontRenderer mf = MD3Theme.fontBody(1f);
                int mc2 = MD3Theme.lerpColor(MD3Theme.TEXT_LOW, MD3Theme.TEXT_HIGH, hv);
                float mty = itemY + (ITEM_H - mf.getMetrics().capHeight()) / 2f;
                MD3Theme.text(mode, x + 8f, mty, mf, mc2, alpha * of);

                itemY += ITEM_H;
            }
        }

        return h;
    }

    @Override
    public boolean onClick(Setting<?> s, int x, int y, int w, int mx, int my, int btn, float scale) {
        if (!(s instanceof ModeSetting ms)) return false;
        boolean open = openState.getOrDefault(ms, false);

        // Click header to toggle
        if (my >= y && my < y + 20) {
            openState.put(ms, !open);
            return true;
        }

        // Click item
        if (open) {
            String[] others = Arrays.stream((Object[])ms.getModes())
                    .filter(m -> !Objects.equals(m, ms.getValue())).toArray(String[]::new);
            int itemY = y + 20;
            for (String mode : others) {
                if (mx >= x && mx <= x + w && my >= itemY && my <= itemY + ITEM_H && btn == 0) {
                    ms.setValue(mode);
                    openState.put(ms, false);
                    return true;
                }
                itemY += ITEM_H;
            }
        }
        return false;
    }

    @Override
    public int getHeight(Setting<?> s) {
        if (!(s instanceof ModeSetting ms)) return 20;
        String[] others = Arrays.stream((Object[])ms.getModes())
                .filter(m -> !Objects.equals(m, ms.getValue())).toArray(String[]::new);
        float of = openAnim.getOrDefault(ms, 0f);
        return 20 + (int)(others.length * ITEM_H * of);
    }

    @Override public boolean supports(Setting<?> s) { return s instanceof ModeSetting; }
    @Override public void onMouseRelease(double mx, double my, int btn) {}
    @Override public void onMouseDrag(Setting<?> s, double mx, double my, int btn) {}
}
