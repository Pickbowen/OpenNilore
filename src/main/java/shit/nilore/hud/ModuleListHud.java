package shit.nilore.hud;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.util.Mth;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.render.Interface;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.FontPresets;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.render.Rectangle;
import shit.nilore.render.Renderer;
import shit.nilore.render.RoundedRectangle;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.event.EventTarget;

public class ModuleListHud extends HudElement {
    private enum Alignment {
        LEFT,
        RIGHT
    }

    private static final class AnimatedRow {
        private final Module module;
        private final SmoothAnimationTimer progressAnim = new SmoothAnimationTimer();
        private String name;
        private float textWidth;
        private float rowWidth;
        private boolean targetVisible;

        private AnimatedRow(Module module) {
            this.module = module;
            this.name = module.getName();
            this.progressAnim.setCurrentValue(0.0f);
            this.progressAnim.setToValue(0.0f);
        }

        private void updateMetrics(String displayName, float textWidth, float rowWidth) {
            this.name = displayName;
            this.textWidth = textWidth;
            this.rowWidth = rowWidth;
        }

        private void setTargetVisible(boolean visible) {
            if (this.targetVisible == visible) {
                return;
            }
            this.targetVisible = visible;
            this.progressAnim.animate(visible ? 1.0 : 0.0, visible ? 0.24 : 0.18,
                    visible ? Easings.EASE_OUT_POW3 : Easings.EASE_IN_POW3);
        }

        private void tick() {
            this.progressAnim.tick();
        }

        private float progress() {
            return Mth.clamp(this.progressAnim.getValueF(), 0.0f, 1.0f);
        }

        private boolean isFinishedRemoving() {
            return !this.targetVisible && this.progress() <= 0.01f && this.progressAnim.isDone();
        }
    }

    private static final class RowRenderLayout {
        private final AnimatedRow row;
        private final int rowIndex;
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final float fullHeight;
        private final float progress;
        private float linkHeight;

        private RowRenderLayout(AnimatedRow row, int rowIndex, float x, float y, float width, float height,
                                float fullHeight, float progress) {
            this.row = row;
            this.rowIndex = rowIndex;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.fullHeight = fullHeight;
            this.progress = progress;
        }

        private void linkTo(RowRenderLayout next) {
            this.linkHeight = Math.max(0.0f, next.y - (this.y + this.height));
        }

        private float visualHeight(boolean broken) {
            return this.height + (broken ? 0.0f : this.linkHeight);
        }
    }

    private static final float MIN_VISIBLE_EDGE = 4.0f;
    private static final float DEFAULT_ROW_HEIGHT = 12.5f;
    private static final float DEFAULT_PADDING_X = 3.3f;
    private static final float DEFAULT_PADDING_Y = 3.0f;
    private static final float DEFAULT_ROW_SPACING = 0.0f;
    private static final float DEFAULT_RADIUS = 0f;
    private static final float SLIDE_DISTANCE = 18.0f;

    // Layout settings
    private ModeSetting sideMode;
    private BooleanSetting breakEnabled;
    private BooleanSetting showSuffix;
    private BooleanSetting suffixColorEnabled;
    private BooleanSetting suffixLowercaseEnabled;
    private NumberSetting paddingX;
    private NumberSetting paddingY;
    private NumberSetting rowHeight;
    private NumberSetting rowSpacing;

    // Background settings
    private BooleanSetting backgroundEnabled;
    private NumberSetting backgroundRadius;

    // Side line settings
    private BooleanSetting sideLineEnabled;
    private ModeSetting sideLineMode;
    private NumberSetting sideLineWidth;

    // Text color settings
    private BooleanSetting useClientColor;
    private ModeSetting textColorMode;
    private NumberSetting rainbowSpeed;
    private NumberSetting rainbowSaturation;
    private NumberSetting rainbowBrightness;
    private NumberSetting rainbowOffset;

    // Font glow (fixed)
    private static final boolean FONT_GLOW_ENABLED = true;
    private static final float FONT_GLOW_RADIUS = 0.6f;
    private static final int FONT_GLOW_ALPHA = 180;
    private static final int FONT_GLOW_QUALITY = 1;

    private final SmoothAnimationTimer widthAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer heightAnim = new SmoothAnimationTimer();
    private final Map<Module, AnimatedRow> rowStates = new IdentityHashMap<>();
    private boolean animationReady;

    public ModuleListHud() {
        super("ModuleList");
        this.setX(4.0f);
        this.setY(18.0f);
        this.setWidth(0.0f);
        this.setHeight(0.0f);
        this.setEnabled(true);
    }

    @Override
    public void registerSettings() {
        // Layout settings
        this.sideMode = new ModeSetting("Side Mode", "Auto", "Auto", "Left", "Right").withDefault("Auto");
        this.breakEnabled = new BooleanSetting("Break", true);
        this.showSuffix = new BooleanSetting("Show Suffix", true);
        this.suffixColorEnabled = new BooleanSetting("Suffix Color", true);
        this.suffixLowercaseEnabled = new BooleanSetting("Suffix Lowercase", false);
        this.paddingX = new NumberSetting("Padding X", DEFAULT_PADDING_X, 0.0f, 12.0f, 0.25f);
        this.paddingY = new NumberSetting("Padding Y", DEFAULT_PADDING_Y, 0.0f, 8.0f, 0.25f);
        this.rowHeight = new NumberSetting("Row Height", DEFAULT_ROW_HEIGHT, 9.0f, 24.0f, 0.25f);
        this.rowSpacing = new NumberSetting("Row Spacing", DEFAULT_ROW_SPACING, 0.0f, 8.0f, 0.25f);

        // Background settings
        this.backgroundEnabled = new BooleanSetting("Background", true);
        this.backgroundRadius = new NumberSetting("Background Radius", DEFAULT_RADIUS, 0.0f, 10.0f, 0.25f);

        // Side line settings
        this.sideLineEnabled = new BooleanSetting("Side Line", true);
        this.sideLineMode = new ModeSetting("Side Line Mode", "Auto", "Auto", "Left", "Right").withDefault("Auto");
        this.sideLineWidth = new NumberSetting("Side Line Width", 0.8f, 0.5f, 5.0f, 0.25f);

        // Text color settings
        this.useClientColor = new BooleanSetting("Use Client Color", true);
        this.textColorMode = new ModeSetting("Text Color Mode", "Rainbow", "Rainbow", "Gradient", "Solid").withDefault("Rainbow");
        this.rainbowSpeed = new NumberSetting("Rainbow Speed", 60.0f, 1.0f, 240.0f, 1.0f);
        this.rainbowSaturation = new NumberSetting("Rainbow Saturation", 60.0f, 0.0f, 100.0f, 1.0f);
        this.rainbowBrightness = new NumberSetting("Rainbow Brightness", 100.0f, 10.0f, 100.0f, 1.0f);
        this.rainbowOffset = new NumberSetting("Rainbow Offset", 20.0f, 0.0f, 90.0f, 1.0f);

        // Register all settings
        this.registerSetting(sideMode, breakEnabled, showSuffix, suffixColorEnabled, suffixLowercaseEnabled,
                paddingX, paddingY, rowHeight, rowSpacing, backgroundEnabled, backgroundRadius,
                sideLineEnabled, sideLineMode, sideLineWidth,
                useClientColor, textColorMode, rainbowSpeed, rainbowSaturation, rainbowBrightness, rainbowOffset);
    }

    private List<AnimatedRow> updateRows() {
        FontRenderer font = FontPresets.pingfang(16.0f);
        for (Module module : NiloreClient.getInstance().getModuleManager().getModules()) {
            if (module == this || module.getName().isEmpty() || module.isHiddenInModuleList()) {
                this.rowStates.remove(module);
                continue;
            }
            AnimatedRow row = this.rowStates.get(module);
            if (module.isEnabled()) {
                if (row == null) {
                    row = new AnimatedRow(module);
                    this.rowStates.put(module, row);
                }
                String displayName = this.displayName(module);
                float textWidth = GlHelper.getStringWidth(displayName, font);
                row.updateMetrics(displayName, textWidth, this.rowWidth(textWidth));
                row.setTargetVisible(true);
            } else if (row != null) {
                row.setTargetVisible(false);
            }
        }
        this.rowStates.values().forEach(AnimatedRow::tick);
        this.rowStates.values().removeIf(AnimatedRow::isFinishedRemoving);

        List<AnimatedRow> rows = new ArrayList<>(this.rowStates.values());
        rows.sort((a, b) -> Float.compare(b.textWidth, a.textWidth));
        return rows;
    }

    private String displayName(Module module) {
        String name = module.getName();
        if (!this.showSuffix.getValue()) {
            return name;
        }
        String suffix = module.getSuffix();
        if (suffix == null || suffix.isBlank()) {
            return name;
        }
        suffix = suffix.trim();
        if (this.suffixLowercaseEnabled.getValue()) {
            suffix = suffix.toLowerCase(Locale.ROOT);
        }
        return name + " " + suffix;
    }

    @Override
    public void onRender2D(Render2DEvent event, float x, float y) {
        if (!this.shouldRender()) {
            return;
        }
        List<AnimatedRow> rows = this.updateRows();
        if (rows.isEmpty()) {
            this.setWidth(0.0f);
            this.setHeight(0.0f);
            return;
        }

        float targetWidth = this.measureWidth(rows);
        float targetHeight = this.measureHeight(rows);
        float previousWidth = this.getWidth();
        Alignment anchorBeforeResize = this.resolveAlignment(x, Math.max(previousWidth, targetWidth));
        this.updateSizeAnimation(targetWidth, targetHeight);

        float width = this.widthAnim.getValueF();
        float height = this.heightAnim.getValueF();
        if (anchorBeforeResize == Alignment.RIGHT && !this.isDragging() && previousWidth > 0.0f) {
            this.setX(this.getX() + previousWidth - width);
        }
        this.clampToScreen(width, height);
        this.setWidth(width);
        this.setHeight(height);

        float drawX = this.getX();
        float drawY = this.getY();
        Alignment alignment = this.resolveAlignment(drawX, width);
        Renderer.render(event.guiGraphics(), drawContext -> this.renderRows(drawContext, rows, drawX, drawY, width, alignment));
    }

    private boolean shouldRender() {
        if (!this.isEnabled()) {
            return false;
        }
        Interface interfaceModule = NiloreClient.getInstance().getModuleManager().getModule(Interface.class);
        return interfaceModule == null || interfaceModule.isEnabled();
    }

    private float rowWidth(float textWidth) {
        float lineReserve = this.sideLineEnabled.getValue() ? this.sideLineWidth.getValue().floatValue() : 0.0f;
        return textWidth + this.paddingX.getValue().floatValue() * 2.0f + lineReserve;
    }

    private float measureWidth(List<AnimatedRow> rows) {
        float maxWidth = 0.0f;
        for (AnimatedRow row : rows) {
            maxWidth = Math.max(maxWidth, row.rowWidth);
        }
        return maxWidth;
    }

    private float measureHeight(List<AnimatedRow> rows) {
        float rowHeightValue = this.rowHeight.getValue().floatValue();
        float spacing = this.rowSpacing.getValue().floatValue();
        float height = 0.0f;
        boolean hasVisibleRow = false;
        for (AnimatedRow row : rows) {
            float progress = row.progress();
            if (progress <= 0.01f) {
                continue;
            }
            if (hasVisibleRow) {
                height += spacing * progress;
            }
            height += rowHeightValue * progress;
            hasVisibleRow = true;
        }
        return height;
    }

    private void updateSizeAnimation(float targetWidth, float targetHeight) {
        if (!this.animationReady) {
            this.widthAnim.setCurrentValue(targetWidth);
            this.widthAnim.setToValue(targetWidth);
            this.heightAnim.setCurrentValue(targetHeight);
            this.heightAnim.setToValue(targetHeight);
            this.animationReady = true;
            return;
        }
        this.widthAnim.animate(targetWidth, 0.18, Easings.EASE_OUT_SINE);
        this.heightAnim.animate(targetHeight, 0.18, Easings.EASE_OUT_SINE);
        this.widthAnim.tick();
        this.heightAnim.tick();
    }

    private void renderRows(DrawContext drawContext, List<AnimatedRow> rows, float x, float y, float width, Alignment alignment) {
        List<RowRenderLayout> layouts = this.computeRowLayouts(rows, x, y, width, alignment);
        boolean broken = this.breakEnabled.getValue();
        for (RowRenderLayout layout : layouts) {
            this.renderRow(drawContext, rows, layout, broken, alignment);
        }
    }

    private List<RowRenderLayout> computeRowLayouts(List<AnimatedRow> rows, float x, float y, float width, Alignment alignment) {
        List<RowRenderLayout> layouts = new ArrayList<>();
        float cursorY = y;
        float rowHeightValue = this.rowHeight.getValue().floatValue();
        float spacing = this.rowSpacing.getValue().floatValue();
        boolean hasRenderedRow = false;
        for (int i = 0; i < rows.size(); i++) {
            AnimatedRow row = rows.get(i);
            float progress = row.progress();
            if (progress <= 0.01f) {
                continue;
            }
            if (hasRenderedRow) {
                cursorY += spacing * progress;
            }
            float animatedWidth = Math.max(0.1f, row.rowWidth * progress);
            float animatedHeight = Math.max(0.1f, rowHeightValue * progress);
            float rowX = alignment == Alignment.RIGHT ? x + width - animatedWidth : x;
            float slideOffset = (alignment == Alignment.RIGHT ? SLIDE_DISTANCE : -SLIDE_DISTANCE) * (1.0f - progress);
            layouts.add(new RowRenderLayout(row, i, rowX + slideOffset, cursorY,
                    animatedWidth, animatedHeight, rowHeightValue, progress));
            cursorY += rowHeightValue * progress;
            hasRenderedRow = true;
        }
        for (int i = 0; i < layouts.size() - 1; i++) {
            layouts.get(i).linkTo(layouts.get(i + 1));
        }
        return layouts;
    }

    private void renderRow(DrawContext drawContext, List<AnimatedRow> rows, RowRenderLayout layout,
                           boolean broken, Alignment alignment) {
        RoundedRectangle bounds = this.rowBounds(layout, broken, alignment);
        int rowColor = this.colorForPosition(layout.rowIndex, 0.5f, Math.max(1, rows.size() - 1));

        if (this.backgroundEnabled.getValue()) {
            try (Paint paint = new Paint()) {
                int alpha = Math.round(80 * layout.progress);
                paint.setColor((alpha << 24) | 0x000000);
                drawContext.drawRoundedRect(bounds, paint);
            }
        }
        if (this.sideLineEnabled.getValue()) {
            this.drawSideLine(drawContext, bounds, Argb.withAlpha(rowColor, layout.progress), alignment, broken);
        }
        drawContext.save();
        drawContext.clipRoundedRect(bounds, true);
        this.drawModuleName(layout.row.name, layout.x, layout.y, layout.width, layout.fullHeight,
                layout.rowIndex, rows.size(), layout.progress, alignment);
        drawContext.restore();
    }

    private RoundedRectangle rowBounds(RowRenderLayout layout, boolean broken, Alignment alignment) {
        float radius = this.backgroundRadius.getValue().floatValue();
        if (broken) {
            return RoundedRectangle.ofXYWHR(layout.x, layout.y, layout.width, layout.height, radius);
        }
        if (alignment == Alignment.RIGHT) {
            return RoundedRectangle.ofXYWHRadii(layout.x, layout.y, layout.width, layout.visualHeight(false),
                    new float[]{0.0f, 0.0f, 0.0f, radius});
        }
        return RoundedRectangle.ofXYWHRadii(layout.x, layout.y, layout.width, layout.visualHeight(false),
                new float[]{0.0f, 0.0f, radius, 0.0f});
    }

    private RoundedRectangle expandedGlowBounds(RoundedRectangle bounds, float spread) {
        return RoundedRectangle.ofXYWHR(
                bounds.x1 - spread,
                bounds.y1 - spread,
                bounds.getWidth() + spread * 2.0f,
                bounds.getHeight() + spread * 2.0f,
                this.backgroundRadius.getValue().floatValue() + spread);
    }

    private void drawSideLine(DrawContext drawContext, RoundedRectangle bounds, int color, Alignment rowAlignment, boolean broken) {
        float lineWidth = this.sideLineWidth.getValue().floatValue();
        Alignment lineAlignment = this.resolveLineAlignment(rowAlignment);
        float lineX = lineAlignment == Alignment.RIGHT ? bounds.x2 - lineWidth : bounds.x1;
        try (Paint paint = new Paint()) {
            paint.setColor(color);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(lineX, bounds.y1, lineWidth, bounds.getHeight(),
                    broken ? Math.min(this.backgroundRadius.getValue().floatValue(), lineWidth) : 0.0f), paint);
        }
    }

    private void drawModuleName(String text, float rowX, float rowY, float rowWidth, float rowHeight,
                                int rowIndex, int rowCount, float alpha, Alignment alignment) {
        FontRenderer font = FontPresets.pingfang(16.0f);
        float textWidth = GlHelper.getStringWidth(text, font);
        float padding = this.paddingX.getValue().floatValue();
        float lineReserve = this.sideLineEnabled.getValue() ? this.sideLineWidth.getValue().floatValue() : 0.0f;
        float textX = alignment == Alignment.RIGHT
                ? rowX + rowWidth - padding - textWidth - (this.resolveLineAlignment(alignment) == Alignment.RIGHT ? lineReserve : 0.0f)
                : rowX + padding + (this.resolveLineAlignment(alignment) == Alignment.LEFT ? lineReserve : 0.0f);
        float textY = rowY + (rowHeight - (float) GlHelper.getFontAscent(font)) / 2.0f
                + this.paddingY.getValue().floatValue() * 0.25f;
        int color = this.colorForPosition(rowIndex, 0.5f, Math.max(1, rowCount - 1));
        int finalColor = Argb.withAlpha(color, alpha);

        if (FONT_GLOW_ENABLED) {
            int glowAlphaValue = FONT_GLOW_ALPHA;
            float radius = FONT_GLOW_RADIUS;
            int quality = FONT_GLOW_QUALITY;
            if (radius > 0.0f && glowAlphaValue > 0) {
                int animatedGlowAlpha = Math.round((float) glowAlphaValue * alpha);
                for (int i = 0; i < quality; i++) {
                    double angle = Math.PI * 2.0 * (double) i / (double) quality;
                    float ox = (float) Math.cos(angle) * radius;
                    float oy = (float) Math.sin(angle) * radius;
                    GlHelper.drawText(text, textX + ox, textY + oy, font, Argb.withAlpha(color, animatedGlowAlpha / quality));
                }
            }
        }
        GlHelper.drawText(text, textX, textY, font, finalColor);
    }

    private int colorForPosition(int rowIndex, float charProgress, int maxRowIndex) {
        if (this.useClientColor.getValue()) {
            return ColorUtil.getRainbowColor(this.rainbowSpeed.getValue().intValue(),
                    rowIndex * this.rainbowOffset.getValue().intValue()).getRGB();
        }
        String mode = this.textColorMode.getValue();
        if ("Rainbow".equals(mode)) {
            return ColorUtil.getRainbowColor(this.rainbowSpeed.getValue().intValue(),
                    rowIndex * this.rainbowOffset.getValue().intValue()).getRGB();
        }
        // Default to rainbow
        return ColorUtil.getRainbowColor(this.rainbowSpeed.getValue().intValue(),
                rowIndex * this.rainbowOffset.getValue().intValue()).getRGB();
    }

    private Alignment resolveAlignment(float x, float width) {
        if ("Left".equals(this.sideMode.getValue())) {
            return Alignment.LEFT;
        }
        if ("Right".equals(this.sideMode.getValue())) {
            return Alignment.RIGHT;
        }
        return x + width / 2.0f < (float) mc.getWindow().getGuiScaledWidth() / 2.0f
                ? Alignment.LEFT
                : Alignment.RIGHT;
    }

    private Alignment resolveLineAlignment(Alignment rowAlignment) {
        if ("Left".equals(this.sideLineMode.getValue())) {
            return Alignment.LEFT;
        }
        if ("Right".equals(this.sideLineMode.getValue())) {
            return Alignment.RIGHT;
        }
        return rowAlignment;
    }

    private void clampToScreen(float width, float height) {
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float maxX = Math.max(MIN_VISIBLE_EDGE, screenWidth - Math.min(width, screenWidth) - MIN_VISIBLE_EDGE);
        float maxY = Math.max(MIN_VISIBLE_EDGE, screenHeight - Math.min(height, screenHeight) - MIN_VISIBLE_EDGE);
        this.setX(Mth.clamp(this.getX(), MIN_VISIBLE_EDGE, maxX));
        this.setY(Mth.clamp(this.getY(), MIN_VISIBLE_EDGE, maxY));
    }

    @Override
    public void mouseDragged(int mouseX, int mouseY) {
        this.setX((float) mouseX - this.getDragOffsetX());
        this.setY((float) mouseY - this.getDragOffsetY());
        this.clampToScreen(Math.max(this.getWidth(), 1.0f), Math.max(this.getHeight(), 1.0f));
    }

    @Override
    public void stopDragging() {
        boolean wasDragging = this.isDragging();
        super.stopDragging();
        if (wasDragging) {
            NiloreClient.getInstance().getConfigManager().saveAll();
        }
    }

    private static class Argb {
        static int withAlpha(int color, float alpha) {
            int a = Math.round(alpha * 255);
            return (a << 24) | (color & 0x00FFFFFF);
        }
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }
}
