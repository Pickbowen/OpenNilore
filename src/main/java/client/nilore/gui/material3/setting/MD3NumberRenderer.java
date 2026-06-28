package client.nilore.gui.material3.setting;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import client.nilore.gui.material3.MD3Theme;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.math.LerpUtil;
import client.nilore.utils.render.RenderUtil;

public class MD3NumberRenderer implements MD3SettingRenderer {

    private final Map<NumberSetting, Float> hoverAnim = new HashMap<>();
    private final Map<NumberSetting, Boolean> dragging = new HashMap<>();
    private NumberSetting dragTarget;
    private float dragTrackX, dragTrackW;

    @Override
    public int render(GuiGraphics gg, Setting<?> s, int x, int y, int w, int mx, int my, float alpha, float accent) {
        if (!(s instanceof NumberSetting ns)) return 0;
        int h = getHeight(s);

        // Label
        FontRenderer lf = MD3Theme.fontBodyLarge(1f);
        MD3Theme.text(ns.getName(), x + 2f, y + 4f, lf, MD3Theme.TEXT_HIGH, alpha);

        // Value text
        FontRenderer vf = MD3Theme.fontTitleMedium(1f);
        String val = formatVal(ns.getValue().doubleValue());
        float vw = GlHelper.getStringWidth(val, vf);
        MD3Theme.text(val, x + w - vw - 2f, y + 4f, vf, (int)accent, alpha);

        // Slider track
        float trackY = y + h - 7f;
        float trackH = 3f;
        float trackX = x + 2f;
        float trackW = w - 4f;

        // Track bg
        RenderUtil.drawRoundedRect(gg.pose(), trackX, trackY, trackW, trackH, trackH / 2f,
                MD3Theme.withAlpha(MD3Theme.SURFACE_HIGHEST, alpha));

        // Filled portion
        double min = ns.getMin().doubleValue(), max = ns.getMax().doubleValue();
        double valD = ns.getValue().doubleValue();
        float fill = (float)((valD - min) / (max - min));
        fill = Math.max(0f, Math.min(1f, fill));
        if (fill > 0.01f) {
            RenderUtil.drawRoundedRect(gg.pose(), trackX, trackY, trackW * fill, trackH, trackH / 2f,
                    MD3Theme.withAlpha((int)accent, alpha));
        }

        // Thumb (circle)
        float thumbR = 5f;
        float thumbX = trackX + trackW * fill;
        float thumbY = trackY + trackH / 2f;
        boolean hov = dragging.getOrDefault(ns, false) ||
                (mx >= thumbX - thumbR - 2 && mx <= thumbX + thumbR + 2 && my >= thumbY - thumbR - 2 && my <= thumbY + thumbR + 2);
        float hc = hoverAnim.getOrDefault(ns, 0f);
        hoverAnim.put(ns, LerpUtil.smoothLerp(hc, hov ? 1f : 0f, 0.2f));
        float hv = hoverAnim.get(ns);

        // Thumb glow
        if (hv > 0.01f) {
            float glowR = thumbR + 3f * hv;
            RenderUtil.drawRoundedRect(gg.pose(), thumbX - glowR, thumbY - glowR,
                    glowR * 2, glowR * 2, glowR,
                    MD3Theme.withAlpha((int)accent, alpha * hv * 0.2f));
        }
        // Thumb body
        RenderUtil.drawRoundedRect(gg.pose(), thumbX - thumbR, thumbY - thumbR,
                thumbR * 2, thumbR * 2, thumbR,
                MD3Theme.withAlpha((int)accent, alpha));

        return h;
    }

    @Override
    public boolean onClick(Setting<?> s, int x, int y, int w, int mx, int my, int btn, float scale) {
        if (!(s instanceof NumberSetting ns) || btn != 0) return false;
        int h = getHeight(s);
        float trackX = x + 2f, trackW = w - 4f;
        float trackY = y + h - 7f;
        // Click on track area
        if (mx >= trackX && mx <= trackX + trackW && my >= trackY - 4 && my <= trackY + 10) {
            float ratio = Math.max(0f, Math.min(1f, (mx - trackX) / trackW));
            double min = ns.getMin().doubleValue(), max = ns.getMax().doubleValue();
            double step = ns.getStep().doubleValue();
            double raw = min + (max - min) * ratio;
            double snapped = Math.round(raw / step) * step;
            snapped = Math.max(min, Math.min(max, snapped));
            applyValue(ns, snapped);
            dragging.put(ns, true);
            dragTarget = ns;
            dragTrackX = trackX;
            dragTrackW = trackW;
            return true;
        }
        return false;
    }

    @Override
    public void onMouseRelease(double mx, double my, int btn) {
        dragging.clear();
        dragTarget = null;
    }

    @Override
    public void onMouseDrag(Setting<?> s, double mx, double my, int btn) {
        if (dragTarget == null || btn != 0) return;
        float ratio = Math.max(0f, Math.min(1f, (float)(mx - dragTrackX) / dragTrackW));
        double min = dragTarget.getMin().doubleValue(), max = dragTarget.getMax().doubleValue();
        double step = dragTarget.getStep().doubleValue();
        double raw = min + (max - min) * ratio;
        double snapped = Math.round(raw / step) * step;
        snapped = Math.max(min, Math.min(max, snapped));
        applyValue(dragTarget, snapped);
    }

    private void applyValue(NumberSetting ns, double v) {
        if (ns.getValue() instanceof Integer) ns.setValue((int)Math.round(v));
        else if (ns.getValue() instanceof Long) ns.setValue(Math.round(v));
        else if (ns.getValue() instanceof Float) ns.setValue((float)v);
        else ns.setValue(v);
    }

    private String formatVal(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((int)v);
        return String.format(Locale.US, "%.1f", v);
    }

    @Override public boolean supports(Setting<?> s) { return s instanceof NumberSetting; }
    @Override public int getHeight(Setting<?> s) { return 32; }
}
