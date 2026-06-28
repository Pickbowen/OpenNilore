package client.nilore.gui;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import client.nilore.NiloreClient;
import client.nilore.gui.material3.MD3Theme;
import client.nilore.gui.material3.setting.MD3SettingRegistry;
import client.nilore.gui.panel.setting.NumberSettingRenderer;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.render.DrawContext;
import client.nilore.render.FontRenderer;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.render.GlHelper;
import client.nilore.render.Renderer;
import client.nilore.settings.Setting;
import client.nilore.utils.math.LerpUtil;
import client.nilore.utils.render.RenderUtil;

public class MaterialClickGui extends Screen {

    public static final MaterialClickGui instance = new MaterialClickGui();

    private enum State { CLOSED, OPENING, OPEN, CLOSING }
    private State state = State.CLOSED;
    private float openT = 0f;

    @Getter private Category selected = Category.COMBAT;
    private List<Module> modules;
    private final Map<Category, Float> catHover = new HashMap<>();
    private final Map<Category, Float> catSelect = new HashMap<>();
    private final Map<Module, Float> modHover = new HashMap<>();
    private final Map<Module, Float> modToggleAnim = new HashMap<>();
    private Module focused;

    private float scrollY = 0f, scrollTarget = 0f, contentH = 0f;
    private float scrollAlpha = 0f;
    private long scrollTime = 0L;

    private float settingsAlpha = 0f;
    private float sScrollY = 0f, sScrollTarget = 0f, sContentH = 0f;
    private float sScrollAlpha = 0f;
    private long sScrollTime = 0L;

    private boolean searching = false, searchFocus = false;
    private String query = "";
    private long cursorTime = 0L;

    private static class Toast {
        final String msg;
        final long born;
        float y, targetY, alpha;
        Toast(String m) { msg = m; born = System.currentTimeMillis(); }
    }
    private final List<Toast> toasts = new CopyOnWriteArrayList<>();

    // ── Layout ──
    private static final float W = 600f, H = 360f, R = 14f;
    private static final float SIDEBAR_W = 170f, GAP = 0f;
    private static final float CARD = 28f, CARD_GAP = 3f, CARD_R = 8f;
    private static final float HEAD = 36f;
    private static final float TOGGLE_W = 22f, TOGGLE_H = 12f;
    private static final float CAT_H = 30f, CAT_GAP = 4f, CAT_R = 8f;
    private static final float CAT_TOP = 67f;
    private static final float USER_H = 32f, USER_R = 8f;
    private static final float SEARCH_H = 20f, SEARCH_R = 10f;

    private MaterialClickGui() {
        super(Component.nullToEmpty("Nilore ClickGui"));
    }

    // ──────────────── Lifecycle ────────────────

    public void init() {
        super.init();
        LerpUtil.reset();
        if (state == State.CLOSED) openT = 0f;
        state = State.OPENING;
        loadModules();
    }

    public boolean isPauseScreen() { return false; }
    public void onClose() { if (state != State.CLOSING) state = State.CLOSING; }

    // ──────────────── Geometry ────────────────

    private float detailW() {
        float base = 280f;
        if (settingsAlpha > 0.05f) base += GAP + 230f * settingsAlpha;
        return base;
    }
    private float panelW() { return SIDEBAR_W + GAP + detailW(); }
    private float ox() { return width / 2f - panelW() / 2f; }
    private float oy() { return height / 2f - H / 2f - 6f; }

    // ──────────────── Render ────────────────

    public void render(@Nonnull GuiGraphics gg, int mx, int my, float pt) {
        LerpUtil.update();
        tickState();
        if (state == State.CLOSED && openT <= 0f) return;

        float ease = ease(openT);
        float sc = 0.97f + 0.03f * ease;

        // Scrim
        gg.fill(0, 0, width, height, MD3Theme.withAlpha(MD3Theme.SCRIM, ease));

        // Settings alpha
        float st = focused != null ? 1f : 0f;
        settingsAlpha = lerp(settingsAlpha, st, 0.1f);

        // Smooth scroll
        scrollY = lerp(scrollY, scrollTarget, 0.25f);
        sScrollY = lerp(sScrollY, sScrollTarget, 0.25f);

        // Scale transform
        int cx = width / 2, cy = height / 2;
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        gg.pose().scale(sc, sc, 1);
        gg.pose().translate(-cx, -cy, 0);

        float a = ease;
        float px = ox(), py = oy();

        Renderer.renderConsumer(dc -> {
            float pw = panelW();

            // Shadow
            RenderUtil.drawBlurredRect(gg.pose(), px - 4, py - 4, pw + 8, H + 8, R, 28f, a * 0.45f, 0xFF000000);
            // Main surface (provides outer rounded boundary)
            RenderUtil.drawRoundedRect(gg.pose(), px, py, pw, H, R, MD3Theme.withAlpha(MD3Theme.SURFACE, a));

            // Clip to main panel so inner rects don't overflow the rounded corners
            gg.enableScissor((int)px, (int)py, (int)(px + pw), (int)(py + H));
            renderSidebar(dc, gg, px, py, mx, my, a);
            renderDetail(dc, gg, px, py, mx, my, a);
            gg.disableScissor();
            renderToasts(gg, px, py, a);
        });

        super.render(gg, mx, my, pt);
        gg.pose().popPose();
    }

    // ──────────────── Sidebar ────────────────

    private void renderSidebar(DrawContext dc, GuiGraphics gg, float px, float py, int mx, int my, float a) {
        // Sidebar bg: left rounded, right straight
        dc.drawRoundedRect(RoundedRectangle.ofXYWHRadii(px, py, SIDEBAR_W, H, new float[]{R, 0, 0, R}),
                new Paint().setColor(MD3Theme.SIDEBAR));

        // ── Brand ──
        // Plugin icon
        FontRenderer brandIcon = MD3Theme.fontMaterial(36f);
        String brandGlyph = "";
        float bix = px + 12f;
        float biy = py + 21f;
        GlHelper.drawText(brandGlyph, bix, biy, brandIcon, MD3Theme.withAlpha(MD3Theme.PRIMARY, a));

        // "Nilore" title
        FontRenderer titleF = MD3Theme.fontTitle(1f);
        float tx = px + 39f;
        float ty = py + 14f;
        MD3Theme.text("Nilore", tx, ty, titleF, MD3Theme.TEXT_HIGH, a);

        // "beta" chip
        FontRenderer betaF = MD3Theme.fontLabel(1f);
        float bx = tx;
        float by = ty + titleF.getMetrics().capHeight() + 2f;
        float betaW = GlHelper.getStringWidth("beta", betaF) + 8f;
        RenderUtil.drawRoundedRect(gg.pose(), bx, by - 1f, betaW, 10f, 5f,
                MD3Theme.withAlpha(MD3Theme.PRIMARY_CONTAINER, a * 0.6f));
        MD3Theme.text("beta", bx + 4f, by + 1f, betaF, MD3Theme.PRIMARY, a);

        // Divider below brand
        RenderUtil.drawFilledRect(gg.pose(), px + 12f, py + 52f, SIDEBAR_W - 24f, 0.5f,
                MD3Theme.withAlpha(MD3Theme.OUTLINE_VARIANT, a * 0.4f));

        // ── Categories ──
        Category[] cats = Category.values();
        float catBaseY = py + CAT_TOP;

        for (int i = 0; i < cats.length; i++) {
            Category c = cats[i];
            float cy = catBaseY + i * (CAT_H + CAT_GAP);
            boolean sel = c == selected;
            boolean hov = mx >= px + 8 && mx <= px + SIDEBAR_W - 8 && my >= cy && my <= cy + CAT_H;

            float hc = catHover.getOrDefault(c, 0f);
            catHover.put(c, lerp(hc, hov ? 1f : 0f, 0.12f));
            float sc2 = catSelect.getOrDefault(c, 0f);
            catSelect.put(c, lerp(sc2, sel ? 1f : 0f, 0.12f));
            float hv = catHover.get(c), sv = catSelect.get(c);

            // Background pill
            float catX = px + 8f, catW = SIDEBAR_W - 16f;
            if (sel) {
                RenderUtil.drawRoundedRect(gg.pose(), catX, cy, catW, CAT_H, CAT_R,
                        MD3Theme.withAlpha(MD3Theme.container(c), a * sv));
            } else if (hv > 0.01f) {
                RenderUtil.drawRoundedRect(gg.pose(), catX, cy, catW, CAT_H, CAT_R,
                        MD3Theme.withAlpha(MD3Theme.SURFACE_HIGHEST, a * hv * 0.5f));
            }

            // Icon
            FontRenderer iconF = MD3Theme.fontMaterial(18f);
            String icon = MD3Theme.icon(c);
            float ix = catX + 10f;
            float iy = cy + (CAT_H - iconF.getMetrics().capHeight()) / 2f + 2f;
            int ic = sel ? MD3Theme.primary(c) : (int)LerpUtil.lerp(MD3Theme.TEXT_LOW, MD3Theme.TEXT_MED, hv);
            GlHelper.drawText(icon, ix, iy, iconF, MD3Theme.withAlpha(ic, a));

            // Label
            FontRenderer lf = MD3Theme.fontBodyLarge(1f);
            int tc = sel ? MD3Theme.TEXT_HIGH : (int)LerpUtil.lerp(MD3Theme.TEXT_LOW, MD3Theme.TEXT_MED, hv);
            float lx = catX + 34f;
            float ly = cy + (CAT_H - lf.getMetrics().capHeight()) / 2f;
            MD3Theme.text(MD3Theme.label(c), lx, ly, lf, tc, a);
        }

        // ── Username ──
        float userY = py + H - USER_H - 12f;
        float userX = px + 8f, userW = SIDEBAR_W - 16f;
        RenderUtil.drawRoundedRect(gg.pose(), userX, userY, userW, USER_H, USER_R,
                MD3Theme.withAlpha(MD3Theme.SURFACE_CONTAINER, a));

        // User icon
        FontRenderer userIconF = MD3Theme.fontMaterial(16f);
        float uix = userX + 10f;
        float uiy = userY + (USER_H - userIconF.getMetrics().capHeight()) / 2f;
        GlHelper.drawText(MD3Theme.ICON_PERSON, uix, uiy, userIconF, MD3Theme.withAlpha(MD3Theme.TEXT_LOW, a));

        // Username text
        String username = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getGameProfile().getName() : "Player";
        FontRenderer uf = MD3Theme.fontBody(1f);
        float ux = userX + 32f;
        float uy = userY + (USER_H - uf.getMetrics().capHeight()) / 2f;
        MD3Theme.text(username, ux, uy, uf, MD3Theme.TEXT_MED, a);
    }

    // ──────────────── Detail Panel ────────────────

    private void renderDetail(DrawContext dc, GuiGraphics gg, float px, float py, int mx, int my, float a) {
        float dx = px + SIDEBAR_W + GAP;
        float dw = detailW();
        boolean settingsOpen = settingsAlpha > 0.05f;

        // Module list width
        float listW = settingsOpen ? (dw - GAP) * (1f - settingsAlpha * 0.45f) : dw;

        // ── 1) Module list bg: left straight, right rounded when settings closed ──
        float listR = settingsOpen ? 0 : R;
        dc.drawRoundedRect(RoundedRectangle.ofXYWHRadii(dx, py, listW, H, new float[]{0, listR, listR, 0}),
                new Paint().setColor(MD3Theme.SURFACE_DIM));

        // ── 2) Settings panel bg: left straight, right rounded ──
        if (settingsOpen) {
            float sw = 230f * settingsAlpha;
            float sx = dx + dw - sw;
            dc.drawRoundedRect(RoundedRectangle.ofXYWHRadii(sx, py, sw, H, new float[]{0, R, R, 0}),
                    new Paint().setColor(MD3Theme.SURFACE_CONTAINER));
        }

        // ── Header ──
        FontRenderer headF = MD3Theme.fontTitle(1f);
        String headText = selected.displayName + " Modules";
        MD3Theme.text(headText, dx + 14f, py + 15f, headF, MD3Theme.TEXT_HIGH, a);

        // Divider
        RenderUtil.drawFilledRect(gg.pose(), dx + 10f, py + HEAD - 2f, listW - 20f, 0.5f,
                MD3Theme.withAlpha(MD3Theme.OUTLINE_VARIANT, a * 0.4f));

        // ── Content: Module list + Settings ──
        float contentY = py + HEAD;
        float contentH = H - HEAD;

        // "ACTIVE MODULES" + count
        FontRenderer labelF = MD3Theme.fontLabel(1f);
        MD3Theme.text("ACTIVE MODULES", dx + 14f, contentY + 6f, labelF, MD3Theme.TEXT_LOW, a);

        int activeCount = modules != null ? (int)modules.stream().filter(Module::isEnabled).count() : 0;
        String countStr = activeCount + "/" + (modules != null ? modules.size() : 0);
        FontRenderer countF = MD3Theme.fontLabel(1f);
        float countW = GlHelper.getStringWidth(countStr, countF);
        MD3Theme.text(countStr, dx + listW - countW - 14f, contentY + 6f, countF, MD3Theme.TEXT_LOW, a);

        // Search bar
        float searchY = contentY + 20f;
        renderSearch(gg, dx + 10f, searchY, listW - 20f, mx, my, a);

        // Module cards
        float cardTop = searchY + SEARCH_H + 4f;
        float cardAreaH = contentH - (cardTop - contentY) - 8f;

        gg.enableScissor((int)dx, (int)cardTop, (int)(dx + listW), (int)(cardTop + cardAreaH));

        if (modules != null) {
            contentH = modules.size() * (CARD + CARD_GAP);
            for (int i = 0; i < modules.size(); i++) {
                Module m = modules.get(i);
                float cardY = cardTop + i * (CARD + CARD_GAP) - scrollY;
                if (cardY + CARD < cardTop || cardY > cardTop + cardAreaH) continue;
                renderCard(gg, dx + 10f, cardY, listW - 20f, CARD, m, mx, my, a);
            }
        } else {
            contentH = 0f;
        }
        gg.disableScissor();

        // Store for scroll
        this.contentH = contentH;
        tickScrollAlpha();
        renderScrollbar(gg, dx + listW - 4f, cardTop, cardAreaH, scrollY, contentH, scrollAlpha, a);

        // Right: settings panel
        if (settingsAlpha > 0.02f) {
            renderSettings(gg, dx, py, dw, mx, my, a);
        }
    }

    private void renderCard(GuiGraphics gg, float x, float y, float w, float h,
                            Module m, int mx, int my, float a) {
        boolean on = m.isEnabled();
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + h;
        boolean rc = m == focused;

        float hc = modHover.getOrDefault(m, 0f);
        modHover.put(m, lerp(hc, hov ? 1f : 0f, 0.12f));
        float hv = modHover.get(m);

        // Toggle animation
        float tc = modToggleAnim.getOrDefault(m, 0f);
        modToggleAnim.put(m, lerp(tc, on ? 1f : 0f, 0.15f));
        float tv = modToggleAnim.get(m);

        int cp = MD3Theme.primary(selected);
        int cc = MD3Theme.container(selected);

        // Card bg
        RenderUtil.drawRoundedRect(gg.pose(), x, y, w, h, CARD_R,
                MD3Theme.withAlpha(MD3Theme.lerpColor(MD3Theme.SURFACE_CONTAINER, cc, tv * 0.6f), a));

        // Hover
        if (hv > 0.01f && !on) {
            RenderUtil.drawRoundedRect(gg.pose(), x, y, w, h, CARD_R,
                    MD3Theme.withAlpha(MD3Theme.argb((int)(255 * 0.06f * hv), 255, 255, 255), a));
        }

        // Accent bar (left)
        if (tv > 0.01f) {
            RenderUtil.drawRoundedRect(gg.pose(), x + 1.5f, y + 5f, 2.5f, h - 10f, 1.25f,
                    MD3Theme.withAlpha(cp, a * tv));
        }

        // Name
        FontRenderer nf = MD3Theme.fontBodyLarge(1f);
        int nc = on ? MD3Theme.TEXT_HIGH : (int)LerpUtil.lerp(MD3Theme.TEXT_LOW, MD3Theme.TEXT_MED, hv);
        MD3Theme.text(m.getName(), x + 9f, y + (h - nf.getMetrics().capHeight()) / 2f,
                nf, nc, a);

        // Keybind
        String b = m.getBind().getName();
        if (!b.equalsIgnoreCase("None")) {
            FontRenderer bf = MD3Theme.fontLabel(1f);
            float bw = GlHelper.getStringWidth(b, bf) + 6f;
            float bh = 9f, bx = x + w - bw - 4f, by = y + (h - bh) / 2f;
            RenderUtil.drawRoundedRect(gg.pose(), bx, by, bw, bh, bh / 2f,
                    MD3Theme.withAlpha(MD3Theme.SURFACE_HIGHEST, a * (on ? 0.7f : 0.4f)));
            MD3Theme.text(b, bx + 3f, by + 2f, bf, on ? MD3Theme.TEXT_MED : MD3Theme.TEXT_DISABLED, a * 0.8f);
        }

        // Focus indicator
        if (rc) {
            RenderUtil.drawRoundedRect(gg.pose(), x + w - 2f, y + 4f, 2f, h - 8f, 1f,
                    MD3Theme.withAlpha(cp, a));
        }
    }

    // ──────────────── Settings Panel ────────────────

    private void renderSettings(GuiGraphics gg, float dx, float py, float dw, int mx, int my, float a) {
        if (focused == null) return;
        float sx = dx + dw - 230f * settingsAlpha;
        float sw = 230f * settingsAlpha;
        float pa = a * settingsAlpha;
        int cp = MD3Theme.primary(selected);

        // Bg already drawn in renderDetail

        // Title
        FontRenderer tf = MD3Theme.fontTitle(1f);
        MD3Theme.text(focused.getName(), sx + 12f, py + 15f, tf,
                focused.isEnabled() ? cp : MD3Theme.TEXT_HIGH, pa);

        // Toggle
        float ttx = sx + sw - TOGGLE_W - 12f, tty = py + (HEAD - TOGGLE_H) / 2f + 1f;
        boolean tHov = mx >= ttx && mx <= ttx + TOGGLE_W && my >= tty && my <= tty + TOGGLE_H;
        float toggleHov = lerp(0f, tHov ? 1f : 0f, 0.2f);
        renderToggle(gg, ttx, tty, focused.isEnabled(), toggleHov, pa, cp);

        // Divider
        RenderUtil.drawFilledRect(gg.pose(), sx + 8f, py + HEAD, sw - 16f, 0.5f,
                MD3Theme.withAlpha(MD3Theme.OUTLINE_VARIANT, pa * 0.4f));

        // Settings content
        float cY = py + HEAD + 4f, cH = H - HEAD - 4f;
        List<Setting<?>> settings = focused.getSettings();
        if (settings != null && !settings.isEmpty()) {
            sContentH = 0;
            for (Setting<?> s : settings) {
                if (s.getVisibility() != null && !s.getVisibility().displayable()) continue;
                sContentH += MD3SettingRegistry.get().getHeight(s);
            }

            gg.enableScissor((int)sx, (int)cY, (int)(sx + sw), (int)(cY + cH));
            int sy = (int)cY - (int)sScrollY;
            for (Setting<?> s : settings) {
                if (s.getVisibility() != null && !s.getVisibility().displayable()) continue;
                int dy = MD3SettingRegistry.get().render(gg, s,
                        (int)(sx + 10f), sy, (int)(sw - 20f), mx, my, pa, cp);
                sy += dy;
            }
            gg.disableScissor();

            tickSScrollAlpha();
            renderScrollbar(gg, sx + sw - 4f, cY, cH, sScrollY, sContentH, sScrollAlpha, pa);
        }
    }

    private void renderToggle(GuiGraphics gg, float x, float y, boolean on, float hov, float a, int accent) {
        int track = on ? accent : MD3Theme.SURFACE_HIGHEST;
        if (hov > 0.01f) track = MD3Theme.brighten(track, 1f + 0.12f * hov);
        if (on) {
            RenderUtil.drawRoundedRect(gg.pose(), x - 1f, y - 1f, TOGGLE_W + 2, TOGGLE_H + 2,
                    (TOGGLE_H + 2) / 2f, MD3Theme.withAlpha(accent, a * 0.25f));
        }
        RenderUtil.drawRoundedRect(gg.pose(), x, y, TOGGLE_W, TOGGLE_H, TOGGLE_H / 2f,
                MD3Theme.withAlpha(track, a));
        float ks = TOGGLE_H - 3f;
        float kx = on ? x + TOGGLE_W - ks - 1.5f : x + 1.5f;
        float ky = y + 1.5f;
        if (hov > 0.01f) {
            RenderUtil.drawRoundedRect(gg.pose(), kx - 0.5f, ky - 0.5f, ks + 1, ks + 1,
                    (ks + 1) / 2f, MD3Theme.withAlpha(MD3Theme.argb((int)(40 * hov), 255, 255, 255), a));
        }
        RenderUtil.drawRoundedRect(gg.pose(), kx, ky, ks, ks, ks / 2f, MD3Theme.withAlpha(-1, a));
    }

    // ──────────────── Search ────────────────

    private void renderSearch(GuiGraphics gg, float x, float y, float w, int mx, int my, float a) {
        RenderUtil.drawRoundedRect(gg.pose(), x, y, w, SEARCH_H, SEARCH_R,
                MD3Theme.withAlpha(MD3Theme.SURFACE_CONTAINER, a));

        if (searchFocus) {
            RenderUtil.drawRoundedRect(gg.pose(), x - 0.5f, y - 0.5f, w + 1, SEARCH_H + 1,
                    SEARCH_R + 0.5f, MD3Theme.withAlpha(MD3Theme.PRIMARY, a * 0.3f));
        }

        // Search icon
        FontRenderer iconF = MD3Theme.fontMaterial(14f);
        GlHelper.drawText("", x + 7f, y + (SEARCH_H - iconF.getMetrics().capHeight()) / 2f + 3f,
                iconF, MD3Theme.withAlpha(MD3Theme.TEXT_LOW, a));

        float tx = x + 24f;
        if (!searching && query.isEmpty()) {
            FontRenderer pf = MD3Theme.fontBody(1f);
            MD3Theme.text("Search modules...", tx,
                    y + (SEARCH_H - pf.getMetrics().capHeight()) / 2f, pf, MD3Theme.TEXT_DISABLED, a);
        } else {
            FontRenderer qf = MD3Theme.fontBodyLarge(1f);
            float qy = y + (SEARCH_H - qf.getMetrics().capHeight()) / 2f;
            MD3Theme.text(query, tx, qy, qf, MD3Theme.TEXT_HIGH, a);
            if (searchFocus) {
                float blink = (float)(Math.sin((System.currentTimeMillis() - cursorTime) / 200.0) * 0.5 + 0.5);
                float ccx = tx + GlHelper.getStringWidth(query, qf) + 1f;
                RenderUtil.drawFilledRect(gg.pose(), ccx, qy, 0.5f, qf.getMetrics().capHeight(),
                        ((int)(blink * a * 255) << 24) | 0xFFFFFF);
            }
        }
    }

    // ──────────────── Scrollbar / Toasts ────────────────

    private void renderScrollbar(GuiGraphics gg, float x, float y, float h,
                                 float off, float total, float sbA, float a) {
        if (sbA <= 0.01f || total <= h) return;
        float max = total - h;
        float thumb = Math.max(14f, h / total * h);
        float ty = y + (off / max) * (h - thumb);
        RenderUtil.drawRoundedRect(gg.pose(), x, ty, 2f, thumb, 1f,
                MD3Theme.withAlpha(MD3Theme.TEXT_LOW, sbA * a * 0.5f));
    }

    private void tickScrollAlpha() {
        float ch = H - HEAD - 32f;
        float t = 0f;
        if (contentH > ch) {
            long s = System.currentTimeMillis() - scrollTime;
            t = s < 500 ? 1f : s < 1000 ? 1f - (s - 500) / 500f : 0f;
        }
        scrollAlpha = lerp(scrollAlpha, t, 0.3f);
    }

    private void tickSScrollAlpha() {
        float ch = H - HEAD - 4f;
        float t = 0f;
        if (sContentH > ch) {
            long s = System.currentTimeMillis() - sScrollTime;
            t = s < 500 ? 1f : s < 1000 ? 1f - (s - 500) / 500f : 0f;
        }
        sScrollAlpha = lerp(sScrollAlpha, t, 0.3f);
    }

    public void addToast(String msg) {
        for (Toast t : toasts) t.targetY -= 16f;
        Toast nt = new Toast(msg);
        toasts.add(nt);
    }

    private void renderToasts(GuiGraphics gg, float px, float py, float a) {
        if (toasts.isEmpty()) return;
        FontRenderer tf = MD3Theme.fontBodyLarge(1f);
        float baseY = py - 14f;
        for (Toast t : toasts) {
            long age = System.currentTimeMillis() - t.born;
            t.y = LerpUtil.smoothLerp(t.y, t.targetY, 0.18f);
            float ta = age < 2000 ? 1f : age < 2500 ? 1f - (age - 2000) / 500f : 0f;
            t.alpha = LerpUtil.smoothLerp(t.alpha, ta, 0.2f);
            if (t.alpha >= 0.01f || age <= 2000) {
                float tw = GlHelper.getStringWidth(t.msg, tf) + 16f, th = 14f;
                float tx = px + (panelW() - tw) / 2f, ty = baseY + t.y;
                RenderUtil.drawRoundedRect(gg.pose(), tx, ty, tw, th, th / 2f,
                        MD3Theme.withAlpha(MD3Theme.SURFACE_HIGHEST, a * t.alpha));
                MD3Theme.text(t.msg, tx + 8f, ty + (th - tf.getMetrics().capHeight()) / 2f,
                        tf, MD3Theme.TEXT_HIGH, a * t.alpha);
            }
        }
        toasts.removeIf(t -> t.alpha < 0.01f && System.currentTimeMillis() - t.born > 2000);
    }

    // ──────────────── Input ────────────────

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (openT < 1f) return true;
        int mx = (int)mouseX, my = (int)mouseY;
        float px = ox(), py = oy();

        // Search
        float dw = detailW();
        float dx = px + SIDEBAR_W + GAP;
        float searchY = py + HEAD + 20f;
        float searchX = dx + 10f, searchW = (settingsAlpha > 0.05f ? (dw - GAP) * (1f - settingsAlpha * 0.45f) : dw) - 20f;
        if (mx >= searchX && mx <= searchX + searchW && my >= searchY && my <= searchY + SEARCH_H) {
            if (!searching) { searching = true; query = ""; doSearch(); }
            searchFocus = true; cursorTime = System.currentTimeMillis();
            return true;
        }
        searchFocus = false;

        if (clickNav(px, py, mx, my)) return true;
        if (clickCard(px, py, mx, my, button)) return true;
        if (button == 0 && clickToggle(px, py, mx, my)) return true;
        if (clickSettings(px, py, mx, my, button)) return true;

        NumberSettingRenderer.clearEditing();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickNav(float px, float py, int mx, int my) {
        Category[] cats = Category.values();
        float catBaseY = py + CAT_TOP;
        for (int i = 0; i < cats.length; i++) {
            float cy = catBaseY + i * (CAT_H + CAT_GAP);
            if (mx >= px + 8 && mx <= px + SIDEBAR_W - 8 && my >= cy && my <= cy + CAT_H) {
                selected = cats[i]; focused = null;
                scrollY = 0; scrollTarget = 0;
                loadModules();
                if (searching) { searching = false; searchFocus = false; query = ""; }
                return true;
            }
        }
        return false;
    }

    private boolean clickCard(float px, float py, int mx, int my, int btn) {
        if (modules == null) return false;
        float dw = detailW();
        float dx = px + SIDEBAR_W + GAP;
        float listW = settingsAlpha > 0.05f ? (dw - GAP) * (1f - settingsAlpha * 0.45f) : dw;
        float cardTop = py + HEAD + 20f + SEARCH_H + 4f;
        float cardAreaH = H - HEAD - (cardTop - py) - 8f;
        if (mx < dx + 10 || mx > dx + listW - 10 || my < cardTop || my > cardTop + cardAreaH) return false;
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            float cardY = cardTop + i * (CARD + CARD_GAP) - scrollY;
            if (my >= cardY && my <= cardY + CARD) {
                if (btn == 0) { m.toggle(); addToast(m.getName() + (m.isEnabled() ? " On" : " Off")); }
                else if (btn == 1) {
                    if (focused == m) { focused = null; }
                    else { focused = m; sScrollY = 0; sScrollTarget = 0; }
                }
                return true;
            }
        }
        return false;
    }

    private boolean clickToggle(float px, float py, int mx, int my) {
        if (focused == null || settingsAlpha < 0.5f) return false;
        float dw = detailW();
        float dx = px + SIDEBAR_W + GAP;
        float sx = dx + dw - 230f * settingsAlpha;
        float sw = 230f * settingsAlpha;
        float ttx = sx + sw - TOGGLE_W - 12f, tty = py + (HEAD - TOGGLE_H) / 2f + 1f;
        if (mx >= ttx && mx <= ttx + TOGGLE_W && my >= tty && my <= tty + TOGGLE_H) {
            focused.toggle();
            addToast(focused.getName() + (focused.isEnabled() ? " On" : " Off"));
            return true;
        }
        return false;
    }

    private boolean clickSettings(float px, float py, int mx, int my, int btn) {
        if (focused == null || settingsAlpha < 0.5f) return false;
        List<Setting<?>> ss = focused.getSettings();
        if (ss == null || ss.isEmpty()) return false;
        float dw = detailW();
        float dx = px + SIDEBAR_W + GAP;
        float sx = dx + dw - 230f * settingsAlpha;
        float sw = 230f * settingsAlpha;
        float cY = py + HEAD + 4f, cH = H - HEAD - 4f;
        if (mx < sx + 10 || mx > sx + sw - 10 || my < cY || my > cY + cH) return false;
        int sy = (int)cY - (int)sScrollY;
        for (Setting<?> s : ss) {
            if (s.getVisibility() != null && !s.getVisibility().displayable()) continue;
            int h = MD3SettingRegistry.get().getHeight(s);
            if (my >= sy && my <= sy + h) {
                if (MD3SettingRegistry.get().onClick(s,
                        (int)(sx + 10f), sy, (int)(sw - 20f), mx, my, btn, 1f))
                    return true;
            }
            sy += h;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        MD3SettingRegistry.get().onMouseRelease(mx, my, btn);
        return super.mouseReleased(mx, my, btn);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (focused == null || settingsAlpha < 0.5f) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        List<Setting<?>> ss = focused.getSettings();
        if (ss != null) {
            for (Setting<?> s : ss) {
                if (s.getVisibility() != null && !s.getVisibility().displayable()) continue;
                MD3SettingRegistry.get().onMouseDrag(s, mouseX, mouseY, button);
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (openT < 1f) return true;
        int mxI = (int)mx, myI = (int)my;
        float px = ox(), py = oy();
        float dw = detailW();
        float dx = px + SIDEBAR_W + GAP;

        // Module list scroll
        float listW = settingsAlpha > 0.05f ? (dw - GAP) * (1f - settingsAlpha * 0.45f) : dw;
        float cardTop = py + HEAD + 20f + SEARCH_H + 4f;
        float cardAreaH = H - HEAD - (cardTop - py) - 8f;
        if (mxI >= dx && mxI <= dx + listW && myI >= cardTop && myI <= cardTop + cardAreaH) {
            if (contentH > cardAreaH) {
                scrollTarget -= (float)delta * 20f;
                scrollTarget = Math.max(0, Math.min(scrollTarget, contentH - cardAreaH));
                scrollTime = System.currentTimeMillis();
            }
            return true;
        }

        // Settings scroll
        if (settingsAlpha > 0.05f && focused != null) {
            float sx = dx + dw - 230f * settingsAlpha;
            float sw = 230f * settingsAlpha;
            if (mxI >= sx && mxI <= sx + sw && myI >= py && myI <= py + H) {
                float sCH = H - HEAD - 4f;
                if (sContentH > sCH) {
                    sScrollTarget -= (float)delta * 20f;
                    sScrollTarget = Math.max(0, Math.min(sScrollTarget, sContentH - sCH));
                    sScrollTime = System.currentTimeMillis();
                }
                return true;
            }
        }
        return super.mouseScrolled(mx, my, delta);
    }

    public boolean keyPressed(int key, int scan, int mod) {
        if (NumberSettingRenderer.onKeyPress(key, scan, mod)) return true;
        if (searching) {
            if (key == 256) { searching = false; searchFocus = false; query = ""; loadModules(); return true; }
            if (searchFocus) {
                cursorTime = System.currentTimeMillis();
                if (key == 259 && !query.isEmpty()) { query = query.substring(0, query.length() - 1); doSearch(); return true; }
            }
        }
        if (key == 256 && !searching) { onClose(); return true; }
        return super.keyPressed(key, scan, mod);
    }

    public boolean charTyped(char c, int mod) {
        if (searching && searchFocus) { cursorTime = System.currentTimeMillis(); query += c; doSearch(); return true; }
        if (NumberSettingRenderer.onCharTyped(c)) return true;
        return super.charTyped(c, mod);
    }

    // ──────────────── Helpers ────────────────

    private void doSearch() {
        if (query.isEmpty()) { loadModules(); return; }
        modules = NiloreClient.instance.getModuleManager().getModules().stream()
                .filter(m -> m.getName().toLowerCase().contains(query.toLowerCase()))
                .sorted(Comparator.comparing(Module::getName)).collect(Collectors.toList());
        scrollY = 0; scrollTarget = 0;
    }

    private void loadModules() {
        modules = NiloreClient.instance.getModuleManager().getModules().stream()
                .filter(m -> m.getCategory() == selected)
                .sorted(Comparator.comparing(Module::getName)).collect(Collectors.toList());
    }

    private void tickState() {
        switch (state) {
            case OPENING -> { openT = LerpUtil.lerp(openT, 1f, 0.08f); if (openT >= 1f) state = State.OPEN; }
            case CLOSING -> {
                openT = LerpUtil.lerp(openT, 0f, 0.1f);
                if (openT <= 0f) {
                    state = State.CLOSED;
                    if (NiloreClient.isReady()) NiloreClient.instance.getConfigManager().saveAll();
                    minecraft.setScreen(null);
                }
            }
        }
    }

    private static float ease(float t) { return (float)(1 - Math.pow(1 - t, 3)); }
    private static float lerp(float c, float t, float s) {
        return Math.abs(t - c) > 0.005f ? LerpUtil.smoothLerp(c, t, s) : t;
    }
}
