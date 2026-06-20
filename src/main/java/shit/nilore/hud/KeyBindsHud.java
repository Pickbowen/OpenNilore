package shit.nilore.hud;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Module;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.render.Path;
import shit.nilore.render.RoundedRectangle;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.misc.Triple;
import shit.nilore.utils.misc.TripleProvider;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.event.EventTarget;

public class KeyBindsHud
extends HudElement {

    public static final class KeyBindEntry {
        public String name;
        public String key;
        public boolean enabled;
        public Module module;
        public float alpha;

        public KeyBindEntry(String name, String key, boolean enabled, Module module) {
            this.name = name;
            this.key = key;
            this.enabled = enabled;
            this.module = module;
            this.alpha = 1.0f;
        }
    }

    public static final class KeyBindRow {
        public final KeyBindsHud parent;
        public final Module module;
        public String displayName;
        public String keyName;
        public boolean enabled;
        public boolean visible = true;
        public final boolean rightAligned;
        public boolean animatingOut = false;
        public boolean animatingIn = false;
        public boolean removing = false;
        public float slideX;
        public float opacity;
        public float widthValue;
        public float rowHeightValue;
        public float alphaValue;
        private float nameWidth;
        private FontRenderer cachedFont;
        private String cachedKeyName;
        private float cachedMaxWidth;
        private float cachedKeyWidth;
        public final SmoothAnimationTimer slideAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer fadeAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer heightAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer alphaAnim = new SmoothAnimationTimer();

        public KeyBindRow(KeyBindsHud parent, Module module, KeyBindsHud.KeyBindEntry entry) {
            this.parent = parent;
            this.module = module;
            this.update(entry);
            this.rightAligned = parent.isRightAligned;
            this.slideAnim.setCurrentValue(this.rightAligned ? 20.0 : -20.0);
            this.heightAnim.setCurrentValue(0.0);
            this.alphaAnim.setCurrentValue(0.0);
        }

        public void update(KeyBindsHud.KeyBindEntry entry) {
            this.displayName = entry.name;
            String newKey = entry.key;
            if (!java.util.Objects.equals(this.keyName, newKey)) {
                this.cachedFont = null;
            }
            this.keyName = newKey;
            this.enabled = entry.enabled;
            this.nameWidth = GlHelper.getStringWidth(this.displayName, this.parent.keyFont);
        }

        public void startRemove() {
            if (this.removing) return;
            this.removing = true;
            this.animatingOut = true;
            this.animatingIn = false;
            float distance = 40.0f;
            this.slideAnim.animate(this.rightAligned ? distance : -distance, 0.2, Easings.EASE_IN_POW3);
            this.heightAnim.animate(0.0, 0.2, Easings.EASE_OUT_POW3);
            this.alphaAnim.animate(0.0, 0.2, Easings.EASE_OUT_POW3);
        }

        public void cancelRemove() {
            if (!this.removing) return;
            this.removing = false;
            this.animatingOut = false;
            this.animatingIn = true;
        }

        public void tick() {
            this.slideAnim.tick();
            this.fadeAnim.tick();
            this.heightAnim.tick();
            this.alphaAnim.tick();
        }

        public float getNameWidth() {
            return this.nameWidth;
        }

        public boolean isRemoveDone() {
            return this.removing && this.heightAnim.isDone() && this.alphaAnim.isDone();
        }

        public FontRenderer getFittingFont(FontRenderer baseFont, float maxWidth) {
            if (this.keyName == null) {
                return baseFont;
            }
            if (this.cachedFont != null
                    && this.keyName.equals(this.cachedKeyName)
                    && this.cachedMaxWidth == maxWidth) {
                return this.cachedFont;
            }
            float baseWidth = GlHelper.getStringWidth(this.keyName, baseFont);
            FontRenderer fit = baseFont;
            if (baseWidth > maxWidth) {
                float scale = maxWidth / baseWidth;
                fit = new FontRenderer(baseFont.getFontName(), baseFont.getSize() * scale);
                this.cachedKeyWidth = GlHelper.getStringWidth(this.keyName, fit);
            } else {
                this.cachedKeyWidth = baseWidth;
            }
            this.cachedFont = fit;
            this.cachedKeyName = this.keyName;
            this.cachedMaxWidth = maxWidth;
            return fit;
        }

        public float getKeyWidth() {
            return this.cachedKeyWidth;
        }
    }

    private final List<KeyBindsHud.KeyBindRow> rowList = new ArrayList<>();
    private final FontRenderer nameFont = FontPresets.poppinsMedium(15.0f);
    final FontRenderer keyFont = FontPresets.poppinsRegular(15.0f);
    private final FontRenderer bindFont = FontPresets.materialIcons(18.0f);
    private final SmoothAnimationTimer scrollAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer fadeAnim = new SmoothAnimationTimer();
    private final Paint backgroundPaint = new Paint();
    private final Paint enabledPaint = new Paint();
    private final Paint disabledPaint = new Paint();
    private float maxWidth = -1.0f;
    private float totalHeight;
    private float visibleHeight;
    private float scrollOffset;
    private float alpha;
    private float rowHeight;
    private final Path[] iconPaths = new Path[91];
    private final boolean[] iconLoaded = new boolean[91];
    private final Map<Module, KeyBindsHud.KeyBindEntry> rowMap = new IdentityHashMap<>();
    private final Map<Module, KeyBindsHud.KeyBindRow> removedRows = new IdentityHashMap<>();
    boolean isRightAligned = false;

    public KeyBindsHud() {
        super("KeyBinds");
        this.setWidth(150.0f);
        this.setHeight(100.0f);
        this.setEnabled(true);
        this.fadeAnim.setCurrentValue(1.0);
    }

    private void initSettings() {
        if (this.maxWidth >= 0.0f) {
            return;
        }
        this.maxWidth = GlHelper.getStringWidth("\uE1C6", this.bindFont);
        this.totalHeight = GlHelper.getStringWidth("Hotkeys", this.nameFont);
        this.visibleHeight = GlHelper.getStringWidth("\uE9F6", this.bindFont);
        this.scrollOffset = GlHelper.getFontAscent(this.nameFont);
        this.alpha = GlHelper.getFontAscent(this.keyFont);
        this.rowHeight = GlHelper.getFontAscent(this.bindFont);
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            this.rowList.clear();
            this.rowMap.clear();
            this.removedRows.clear();
            return;
        }
        this.rowMap.clear();
        this.removedRows.clear();
        List<Module> moduleList = NiloreClient.getInstance().getModuleManager().getModules();
        for (Module module : moduleList) {
            KeyBindsHud.KeyBindEntry entry = null;
            if (module instanceof TripleProvider tp) {
                Triple<String, String, Boolean> triple = (Triple<String, String, Boolean>) tp.getTriple();
                if (triple != null) {
                    entry = new KeyBindsHud.KeyBindEntry(triple.first(), triple.second(), triple.isEnabled(), module);
                }
            } else if (module.isEnabled() && module.getKey() > 0
                    && !module.getName().equals("Interface")
                    && !module.getName().equals("ClickGui")) {
                entry = new KeyBindsHud.KeyBindEntry(module.getName(), null, true, module);
            }
            if (entry == null) continue;
            this.rowMap.put(module, entry);
        }
        for (KeyBindsHud.KeyBindRow row : this.rowList) {
            this.removedRows.put(row.module, row);
        }
        for (KeyBindsHud.KeyBindRow row : this.rowList) {
            if (this.rowMap.containsKey(row.module)) continue;
            row.startRemove();
        }
        boolean added = false;
        for (java.util.Map.Entry<Module, KeyBindsHud.KeyBindEntry> e : this.rowMap.entrySet()) {
            KeyBindsHud.KeyBindRow existing = this.removedRows.get(e.getKey());
            if (existing != null) {
                if (existing.removing) existing.cancelRemove();
                existing.update(e.getValue());
                continue;
            }
            this.rowList.add(new KeyBindsHud.KeyBindRow(this, e.getKey(), e.getValue()));
            added = true;
        }
        if (added) {
            this.rowList.sort((a, b) -> Double.compare(b.getNameWidth(), a.getNameWidth()));
        }
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
        float panelWidth;
        boolean rightAligned;
        this.initSettings();
        this.rowList.removeIf(KeyBindsHud.KeyBindRow::isRemoveDone);
        this.rowList.forEach(KeyBindsHud.KeyBindRow::tick);
        this.scrollAnim.tick();
        this.fadeAnim.tick();
        if (this.rowList.isEmpty() && this.scrollAnim.isDone() && this.fadeAnim.isDone()) {
            return;
        }
        boolean rightSide = rightAligned = x > (float)mc.getWindow().getGuiScaledWidth() / 2.0f;
        if (this.isRightAligned != rightAligned) {
            this.isRightAligned = rightAligned;
        }
        float headerHeight = this.scrollOffset + 10.0f;
        float headerWidth = this.maxWidth + 3.0f + this.totalHeight + 10.0f;
        float rowH = this.alpha + 10.0f;
        float targetWidth = headerWidth;
        if (!this.rowList.isEmpty()) {
            panelWidth = 0.0f;
            for (KeyBindsHud.KeyBindRow row : this.rowList) {
                float keyBoxWidth = row.enabled ? rowH + 3.0f : 0.0f;
                float rowWidth = keyBoxWidth + (row.getNameWidth() + 10.0f);
                if (!(rowWidth > panelWidth)) continue;
                panelWidth = rowWidth;
            }
            targetWidth = Math.max(headerWidth, panelWidth);
        }
        if (this.scrollAnim.isDone() && this.rowList.isEmpty()) {
            this.scrollAnim.setCurrentValue(targetWidth);
        }
        this.scrollAnim.animate(targetWidth, 0.2, Easings.EASE_OUT_SINE);
        if (this.rowList.isEmpty()) {
            this.fadeAnim.animate(0.0, 0.2, Easings.EASE_IN_POW3);
        } else {
            this.fadeAnim.animate(1.0, 0.2, Easings.EASE_OUT_POW3);
        }
        panelWidth = this.scrollAnim.getValueF();
        float globalAlpha = this.fadeAnim.getValueF();
        float rowY = y + headerHeight + 3.0f;
        for (KeyBindsHud.KeyBindRow row : this.rowList) {
            if (row.visible) {
                row.visible = false;
                row.fadeAnim.setCurrentValue(rowY);
                row.slideAnim.setCurrentValue(row.rightAligned ? 20.0 : -20.0);
                row.heightAnim.setCurrentValue(0.0);
                row.alphaAnim.setCurrentValue(0.0);
                row.slideAnim.animate(0.0, 0.2, Easings.EASE_OUT_POW3);
                row.heightAnim.animate(1.0, 0.2, Easings.EASE_OUT_POW3);
                row.alphaAnim.animate(rowH + 3.0f, 0.2, Easings.EASE_OUT_POW3);
            } else if (row.animatingIn) {
                row.animatingIn = false;
                row.slideAnim.animate(0.0, 0.2, Easings.EASE_OUT_POW3);
                row.heightAnim.animate(1.0, 0.2, Easings.EASE_OUT_POW3);
                row.alphaAnim.animate(rowH + 3.0f, 0.2, Easings.EASE_OUT_POW3);
            }
            row.fadeAnim.animate(rowY, 0.15, Easings.EASE_OUT_SINE);
            rowY += row.alphaAnim.getValueF();
        }
        Object drawCtx = glRenderEvent.drawContext();
        this.renderRows((DrawContext)drawCtx, x, y, panelWidth, this.isRightAligned, globalAlpha, headerHeight, rowH);
        this.setWidth(panelWidth);
        this.setHeight(rowY - y);
    }

    private void renderRows(DrawContext drawContext, float x, float y, float width, boolean rightAligned, float alpha, float headerHeight, float rowH) {
        float keyBoxLeft;
        float boxWidth;
        float rowAlpha;
        float rowHeightFactor;
        if (alpha <= 0.01f) {
            return;
        }
        this.enabledPaint.setColor(ColorUtil.fromARGB(0, 0, 0, (int)(190.0f * alpha)));
        GlHelper.drawRoundedRect(x, y, width, headerHeight, 4.5f, this.enabledPaint);
        float headerTextY = y + 5.0f + (headerHeight - 10.0f - this.scrollOffset) / 2.0f + 1.0f;
        int whiteColor = ColorUtil.fromARGB(255, 255, 255, (int)(255.0f * alpha));
        this.disabledPaint.setColor(whiteColor);
        if (rightAligned) {
            float iconX = x + width - 5.0f - this.maxWidth;
            float titleX = iconX - 3.0f - this.totalHeight;
            GlHelper.drawTextWithShadow("Hotkeys", titleX, headerTextY, this.nameFont, this.disabledPaint);
            GlHelper.drawTextWithShadow("\uE1C6", iconX, headerTextY + 1.0f, this.bindFont, this.disabledPaint);
        } else {
            GlHelper.drawTextWithShadow("\uE1C6", x + 5.0f, headerTextY + 1.0f, this.bindFont, this.disabledPaint);
            GlHelper.drawTextWithShadow("Hotkeys", x + 5.0f + this.maxWidth + 3.0f, headerTextY, this.nameFont, this.disabledPaint);
        }
        if (this.rowList.isEmpty()) {
            return;
        }
        for (int i = 0; i < this.iconPaths.length; ++i) {
            if (!this.iconLoaded[i]) continue;
            this.iconPaths[i].reset();
            this.iconLoaded[i] = false;
        }
        for (KeyBindsHud.KeyBindRow row : this.rowList) {
            rowHeightFactor = row.heightAnim.getValueF();
            if (rowHeightFactor <= 0.0f) {
                row.slideX = 0.0f;
                continue;
            }
            rowAlpha = alpha * rowHeightFactor;
            if (rowAlpha <= 0.0f) {
                row.slideX = 0.0f;
                continue;
            }
            row.slideX = rowAlpha;
            float rowX = x + row.slideAnim.getValueF();
            float rowFadeY = row.fadeAnim.getValueF();
            boxWidth = row.getNameWidth() + 10.0f;
            row.opacity = rowFadeY;
            row.widthValue = boxWidth;
            int alphaBucket = Math.max(0, Math.min(90, Math.round(90.0f * rowAlpha)));
            Path path = this.iconPaths[alphaBucket];
            if (path == null) {
                this.iconPaths[alphaBucket] = path = new Path();
            }
            this.iconLoaded[alphaBucket] = true;
            if (row.enabled) {
                if (rightAligned) {
                    keyBoxLeft = rowX + width - rowH;
                    float nameBoxLeft = keyBoxLeft - 3.0f - boxWidth;
                    row.rowHeightValue = keyBoxLeft;
                    row.alphaValue = nameBoxLeft;
                    path.addRoundedRect(RoundedRectangle.ofXYWHR(nameBoxLeft, rowFadeY, boxWidth, rowH, 4.5f));
                    path.addRoundedRect(RoundedRectangle.ofXYWHR(keyBoxLeft, rowFadeY, rowH, rowH, 4.5f));
                    continue;
                }
                row.rowHeightValue = rowX;
                row.alphaValue = rowX + rowH + 3.0f;
                path.addRoundedRect(RoundedRectangle.ofXYWHR(rowX, rowFadeY, rowH, rowH, 4.5f));
                path.addRoundedRect(RoundedRectangle.ofXYWHR(row.alphaValue, rowFadeY, boxWidth, rowH, 4.5f));
                continue;
            }
            row.alphaValue = keyBoxLeft = rightAligned ? rowX + width - boxWidth : rowX;
            row.rowHeightValue = keyBoxLeft;
            path.addRoundedRect(RoundedRectangle.ofXYWHR(keyBoxLeft, rowFadeY, boxWidth, rowH, 4.5f));
        }
        for (int i = 0; i < this.iconPaths.length; ++i) {
            if (!this.iconLoaded[i]) continue;
            this.backgroundPaint.setColor(ColorUtil.fromARGB(0, 0, 0, i));
            drawContext.drawPath(this.iconPaths[i], this.backgroundPaint);
        }
        for (KeyBindsHud.KeyBindRow row : this.rowList) {
            if (row.slideX <= 0.0f) continue;
            rowHeightFactor = row.opacity;
            rowAlpha = row.widthValue;
            int alphaInt = (int)(255.0f * row.slideX);
            int rowColor = ColorUtil.fromARGB(255, 255, 255, alphaInt);
            boxWidth = rowHeightFactor + 5.0f + (rowH - 10.0f - this.alpha) / 2.0f;
            float keyTextY = rowHeightFactor + 5.0f + (rowH - 10.0f - this.rowHeight) / 2.0f + 2.5f;
            this.disabledPaint.setColor(rowColor);
            if (row.enabled) {
                float keyBoxX = row.rowHeightValue;
                keyBoxLeft = row.alphaValue;
                if (rightAligned) {
                    float nameX = keyBoxLeft + rowAlpha - 5.0f - row.getNameWidth();
                    GlHelper.drawTextWithShadow(row.displayName, nameX, boxWidth, this.keyFont, this.disabledPaint);
                } else {
                    GlHelper.drawTextWithShadow(row.displayName, keyBoxLeft + 5.0f, boxWidth, this.keyFont, this.disabledPaint);
                }
                if (row.keyName == null) {
                    float iconX = keyBoxX + (rowH - this.visibleHeight) / 2.0f;
                    GlHelper.drawTextWithShadow("\uE9F6", iconX, keyTextY, this.bindFont, this.disabledPaint);
                    continue;
                }
                FontRenderer fontRenderer = row.getFittingFont(this.keyFont, rowH - 5.0f);
                float keyWidth = row.getKeyWidth();
                float keyTextX = keyBoxX + (rowH - keyWidth) / 2.0f;
                float keyDrawY = rowHeightFactor + 5.0f + (rowH - 10.0f - (float)GlHelper.getFontAscent(fontRenderer)) / 2.0f;
                GlHelper.drawTextWithShadow(row.keyName, keyTextX, keyDrawY, fontRenderer, this.disabledPaint);
                continue;
            }
            GlHelper.drawTextWithShadow(row.displayName, row.alphaValue + 5.0f, boxWidth, this.keyFont, this.disabledPaint);
        }
    }

    @Override
    public void onSettings() {
    }
}
