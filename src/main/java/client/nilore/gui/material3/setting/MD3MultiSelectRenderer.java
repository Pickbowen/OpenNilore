package client.nilore.gui.material3.setting;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.material3.MD3Theme;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.MultiSelectSetting;
import client.nilore.utils.math.LerpUtil;
import client.nilore.utils.render.RenderUtil;

public class MD3MultiSelectRenderer implements MD3SettingRenderer {

    private static final int ROW = 22;
    private final Map<String, Float> checkAnim = new HashMap<>();
    private final Map<String, Float> hoverAnim = new HashMap<>();

    @Override
    public int render(GuiGraphics gg, Setting<?> s, int x, int y, int w, int mx, int my, float alpha, float accent) {
        if (!(s instanceof MultiSelectSetting ms)) return 0;

        // Label
        FontRenderer lf = MD3Theme.fontBodyLarge(1f);
        MD3Theme.text(ms.getName(), x + 2f, y + 3f, lf, MD3Theme.TEXT_HIGH, alpha);

        int ry = y + ROW;
        float boxSize = 12f;

        for (String opt : ms.getOptions()) {
            boolean sel = ms.isSelected(opt);
            boolean hov = mx >= x && mx <= x + w && my >= ry && my <= ry + ROW;

            // Animate
            float cc = checkAnim.getOrDefault(opt, 0f);
            checkAnim.put(opt, LerpUtil.smoothLerp(cc, sel ? 1f : 0f, 0.2f));
            float cv = checkAnim.get(opt);

            float hc = hoverAnim.getOrDefault(opt, 0f);
            hoverAnim.put(opt, LerpUtil.smoothLerp(hc, hov ? 1f : 0f, 0.18f));
            float hv = hoverAnim.get(opt);

            // Checkbox
            float bx = x + w - boxSize - 6f;
            float by = ry + (ROW - boxSize) / 2f;

            // Checked fill
            if (cv > 0.01f) {
                RenderUtil.drawRoundedRect(gg.pose(), bx, by, boxSize, boxSize, 3f,
                        MD3Theme.withAlpha((int)accent, alpha * cv));
                // Checkmark (simplified as inner square)
                float inset = (1f - cv) * boxSize * 0.3f;
                RenderUtil.drawRoundedRect(gg.pose(), bx + inset + 2f, by + inset + 2f,
                        boxSize - inset * 2f - 4f, boxSize - inset * 2f - 4f, 1.5f,
                        MD3Theme.withAlpha(MD3Theme.SURFACE, alpha * cv));
            } else {
                // Unchecked border
                RenderUtil.drawRoundedRect(gg.pose(), bx, by, boxSize, boxSize, 3f,
                        MD3Theme.withAlpha(MD3Theme.OUTLINE, alpha));
            }

            // Hover ripple
            if (hv > 0.01f) {
                float rSize = boxSize + 6f * hv;
                RenderUtil.drawRoundedRect(gg.pose(), bx - 3f * hv, by - 3f * hv,
                        rSize, rSize, rSize / 2f,
                        MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, alpha * hv * 0.4f));
            }

            // Option label
            FontRenderer of2 = MD3Theme.fontBody(1f);
            int tc = sel ? MD3Theme.TEXT_HIGH : (int)LerpUtil.lerp(MD3Theme.TEXT_LOW, MD3Theme.TEXT_MED, hv);
            float oly = ry + (ROW - of2.getMetrics().capHeight()) / 2f;
            MD3Theme.text(opt, x + 8f, oly, of2, tc, alpha);

            ry += ROW;
        }

        return getHeight(ms);
    }

    @Override
    public boolean onClick(Setting<?> s, int x, int y, int w, int mx, int my, int btn, float scale) {
        if (!(s instanceof MultiSelectSetting ms) || btn != 0) return false;
        int ry = y + ROW;
        for (String opt : ms.getOptions()) {
            if (mx >= x && mx <= x + w && my >= ry && my <= ry + ROW) {
                if (ms.isSelected(opt)) ms.getValue().remove(opt);
                else ms.getValue().add(opt);
                return true;
            }
            ry += ROW;
        }
        return false;
    }

    @Override
    public int getHeight(Setting<?> s) {
        if (!(s instanceof MultiSelectSetting ms)) return 0;
        return ROW + ms.getOptions().size() * ROW;
    }

    @Override public boolean supports(Setting<?> s) { return s instanceof MultiSelectSetting; }
    @Override public void onMouseRelease(double mx, double my, int btn) {}
    @Override public void onMouseDrag(Setting<?> s, double mx, double my, int btn) {}
}
