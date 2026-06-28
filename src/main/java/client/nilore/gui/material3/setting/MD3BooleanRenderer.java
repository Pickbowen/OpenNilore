package client.nilore.gui.material3.setting;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.material3.MD3Theme;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.utils.math.LerpUtil;
import client.nilore.utils.render.RenderUtil;

public class MD3BooleanRenderer implements MD3SettingRenderer {

    private final Map<BooleanSetting, Float> toggleAnim = new HashMap<>();
    private final Map<BooleanSetting, Float> hoverAnim = new HashMap<>();

    @Override
    public int render(GuiGraphics gg, Setting<?> s, int x, int y, int w, int mx, int my, float alpha, float accent) {
        if (!(s instanceof BooleanSetting bs)) return 0;
        int h = getHeight(s);

        // Toggle animation
        float cur = toggleAnim.getOrDefault(bs, bs.getValue() ? 1f : 0f);
        float tgt = bs.getValue() ? 1f : 0f;
        toggleAnim.put(bs, Math.abs(tgt - cur) > 0.01f ? LerpUtil.smoothLerp(cur, tgt, 0.2f) : tgt);
        float tv = toggleAnim.get(bs);

        // Toggle geometry
        float tw = 22f, th = 12f;
        float tx = x + w - tw - 4f;
        float ty = y + (h - th) / 2f;
        boolean hov = mx >= tx && mx <= tx + tw && my >= ty && my <= ty + th;
        float hc = hoverAnim.getOrDefault(bs, 0f);
        hoverAnim.put(bs, LerpUtil.smoothLerp(hc, hov ? 1f : 0f, 0.2f));
        float hv = hoverAnim.get(bs);

        // Label
        FontRenderer lf = MD3Theme.fontBodyLarge(1f);
        float ly = y + (h - lf.getMetrics().capHeight()) / 2f;
        MD3Theme.text(bs.getName(), x + 2f, ly, lf, MD3Theme.TEXT_HIGH, alpha);

        // Track
        int trackColor = MD3Theme.lerpColor(MD3Theme.SURFACE_HIGHEST, (int)accent, tv);
        if (hv > 0.01f) trackColor = MD3Theme.brighten(trackColor, 1f + 0.12f * hv);
        // Glow when on
        if (tv > 0.01f) {
            RenderUtil.drawRoundedRect(gg.pose(), tx - 1f, ty - 1f, tw + 2f, th + 2f,
                    (th + 2f) / 2f, MD3Theme.withAlpha((int)accent, alpha * tv * 0.25f));
        }
        RenderUtil.drawRoundedRect(gg.pose(), tx, ty, tw, th, th / 2f,
                MD3Theme.withAlpha(trackColor, alpha));

        // Knob
        float ks = th - 3f;
        float kx = tx + 1.5f + (tw - ks - 3f) * tv;
        float ky = ty + 1.5f;
        if (hv > 0.01f) {
            RenderUtil.drawRoundedRect(gg.pose(), kx - 0.5f, ky - 0.5f, ks + 1f, ks + 1f,
                    (ks + 1f) / 2f, MD3Theme.withAlpha(MD3Theme.argb((int)(40 * hv), 255, 255, 255), alpha));
        }
        RenderUtil.drawRoundedRect(gg.pose(), kx, ky, ks, ks, ks / 2f,
                MD3Theme.withAlpha(-1, alpha));

        return h;
    }

    @Override
    public boolean onClick(Setting<?> s, int x, int y, int w, int mx, int my, int btn, float scale) {
        if (!(s instanceof BooleanSetting bs) || btn != 0) return false;
        int h = getHeight(s);
        float tw = 22f, th = 12f;
        float tx = x + w - tw - 4f;
        float ty = y + (h - th) / 2f;
        if (mx >= tx && mx <= tx + tw && my >= ty && my <= ty + th) {
            bs.setValue(!bs.getValue());
            return true;
        }
        return false;
    }

    @Override public boolean supports(Setting<?> s) { return s instanceof BooleanSetting; }
    @Override public int getHeight(Setting<?> s) { return 28; }
    @Override public void onMouseRelease(double mx, double my, int btn) {}
    @Override public void onMouseDrag(Setting<?> s, double mx, double my, int btn) {}
}
