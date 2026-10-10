package client.nilore.hud;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.util.Mth;
import client.nilore.NiloreClient;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.modules.Module;
import client.nilore.modules.impl.render.Interface;
import client.nilore.render.DrawContext;
import client.nilore.render.FontRenderer;
import client.nilore.render.FontPresets;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Rectangle;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.animation.SmoothAnimationTimer;
import client.nilore.utils.math.Easings;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.GradientTheme;
import client.nilore.utils.render.RenderUtil;
import client.nilore.event.EventTarget;

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
            double target = visible ? 1.0 : 0.0;
            // Bypass SmoothAnimationTimer.animate() entirely — its cancellation check
            // (target == fromValue || target == toValue || target == currentValue)
            // misfires on rapid toggle sequences like on→off→on where fromValue
            // is still 1.0 from the prior hide animation, silently dropping the show.
            // Direct field mutation always works regardless of prior animation state.
            float current = this.progressAnim.getValueF();
            this.progressAnim.setCurrentValue(current);
            this.progressAnim.setFromValue(current);
            this.progressAnim.setToValue(target);
            this.progressAnim.setStartTime(System.currentTimeMillis());
            this.progressAnim.setDuration(visible ? 240.0 : 180.0);
            this.progressAnim.setEasing(visible ? Easings.EASE_OUT_POW3 : Easings.EASE_IN_POW3);
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

    private static final float MIN_VISIBLE_EDGE = 0.0f;
    private static final float DEFAULT_ROW_HEIGHT = 15f;
    private static final float DEFAULT_PADDING_X = 3f;
    private static final float DEFAULT_PADDING_Y = 3.5f;
    private static final float DEFAULT_ROW_SPACING = 0.0f;
    private static final float DEFAULT_RADIUS = 2f;
    private static final float SLIDE_DISTANCE = 18.0f;

    // Layout settings
    private ModeSetting sideMode;
    private BooleanSetting breakEnabled;
    private BooleanSetting showSuffix;
    private BooleanSetting suffixColorEnabled;
    private BooleanSetting suffixLowercaseEnabled;
    private BooleanSetting important;
    private NumberSetting paddingX;
    private NumberSetting paddingY;
    private NumberSetting rowHeight;
    private NumberSetting rowSpacing;

    // Background settings
    private BooleanSetting backgroundEnabled;
    private NumberSetting backgroundRadius;
    private NumberSetting backgroundAlpha;

    // Glow settings
    private BooleanSetting glowEnabled;
    private NumberSetting glowRadius;
    private NumberSetting glowAlpha;

    // Side line settings
    private BooleanSetting sideLineEnabled;
    private ModeSetting sideLineMode;
    private NumberSetting sideLineWidth;

    // Text color settings
    private BooleanSetting useClientColor;
    private ModeSetting textColorMode;
    private ModeSetting gradientTheme;
    private NumberSetting rainbowSpeed;
    private NumberSetting rainbowSaturation;
    private NumberSetting rainbowBrightness;
    private NumberSetting rainbowOffset;
    private ModeSetting fontMode;

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
        this.breakEnabled = new BooleanSetting("Break", false);
        this.showSuffix = new BooleanSetting("Show Suffix", true);
        this.suffixColorEnabled = new BooleanSetting("Suffix Color", true);
        this.suffixLowercaseEnabled = new BooleanSetting("Suffix Lowercase", false);
        this.important = new BooleanSetting("Important", false);
        this.paddingX = new NumberSetting("Padding X", DEFAULT_PADDING_X, 0.0f, 12.0f, 0.25f);
        this.paddingY = new NumberSetting("Padding Y", DEFAULT_PADDING_Y, 0.0f, 8.0f, 0.25f);
        this.rowHeight = new NumberSetting("Row Height", DEFAULT_ROW_HEIGHT, 9.0f, 24.0f, 0.25f);
        this.rowSpacing = new NumberSetting("Row Spacing", DEFAULT_ROW_SPACING, 0.0f, 8.0f, 0.25f);

        // Background settings
        this.backgroundEnabled = new BooleanSetting("Background", true);
        this.backgroundRadius = new NumberSetting("Background Radius", DEFAULT_RADIUS, 0.0f, 10.0f, 0.25f);
        this.backgroundAlpha = new NumberSetting("Background Alpha", 80.0f, 0.0f, 255.0f, 1.0f);

        // Glow settings
        this.glowEnabled = new BooleanSetting("Glow", false);
        this.glowRadius = new NumberSetting("Glow Radius", 12.0f, 4.0f, 40.0f, 1.0f);
        this.glowAlpha = new NumberSetting("Glow Alpha", 120.0f, 0.0f, 255.0f, 1.0f);

        // Side line settings
        this.sideLineEnabled = new BooleanSetting("Side Line", false);
        this.sideLineMode = new ModeSetting("Side Line Mode", "Auto", "Auto", "Left", "Right").withDefault("Auto");
        this.sideLineWidth = new NumberSetting("Side Line Width", 0.8f, 0.5f, 5.0f, 0.25f);

        // Text color settings
        this.useClientColor = new BooleanSetting("Use Client Color", false);
        this.textColorMode = new ModeSetting("Text Color Mode", "Gradient", "Solid").withDefault("Gradient");
        this.gradientTheme = new ModeSetting("Gradient Theme", "Rainbow",
                "Rainbow", "Aurora", "Sunset", "Ocean", "Cotton Candy", "Lavender", "Peach", "Mint", "Cyberpunk", "Drift")
                .withDefault("Cotton Candy");
        this.rainbowSpeed = new NumberSetting("Rainbow Speed", 5.0f, 1.0f, 240.0f, 1.0f);
        this.rainbowSaturation = new NumberSetting("Rainbow Saturation", 90.0f, 0.0f, 100.0f, 1.0f);
        this.rainbowBrightness = new NumberSetting("Rainbow Brightness", 100.0f, 10.0f, 100.0f, 1.0f);
        this.rainbowOffset = new NumberSetting("Rainbow Offset", 85.0f, 0.0f, 90.0f, 1.0f);
        this.fontMode = new ModeSetting("Font", "Minecraft", "Minecraft", "PingFang", "Product Sans")
                .withDefault("PingFang");

        // Register all settings
        this.registerSetting(sideMode, breakEnabled, showSuffix, suffixColorEnabled, suffixLowercaseEnabled,
                important,
                paddingX, paddingY, rowHeight, rowSpacing, backgroundEnabled, backgroundRadius, backgroundAlpha,
                sideLineEnabled, sideLineMode, sideLineWidth,
                glowEnabled, glowRadius, glowAlpha,
                useClientColor, fontMode, textColorMode, gradientTheme, rainbowSpeed, rainbowSaturation, rainbowBrightness, rainbowOffset);
    }

    private List<AnimatedRow> updateRows() {
        FontRenderer font = this.resolveCustomFont();
        boolean importantOnly = this.important.getValue();
        boolean useMcFont = this.usesMinecraftFont();
        for (Module module : NiloreClient.getInstance().getModuleManager().getModules()) {
            if (module == this || module.getName().isEmpty() || module.isHiddenInModuleList()) {
                this.rowStates.remove(module);
                continue;
            }
            if (importantOnly && module.getCategory() == client.nilore.modules.Category.RENDER) {
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
                float textWidth = useMcFont
                        ? mc.font.width(displayName)
                        : GlHelper.getStringWidth(displayName, font);
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

    private boolean usesMinecraftFont() {
        return this.fontMode == null || "Minecraft".equals(this.fontMode.getValue());
    }

    private FontRenderer resolveCustomFont() {
        if (this.fontMode != null && "Product Sans".equals(this.fontMode.getValue())) {
            return FontPresets.productSans(18.0f);
        }
        return FontPresets.pingfang(18.0f);
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

        float drawX = this.snapToPixel(this.getX());
        float drawY = this.snapToPixel(this.getY());
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
        for (int i = 0; i < layouts.size(); i++) {
            this.renderRow(drawContext, rows, layouts.get(i), broken, alignment, i, layouts.size(), layouts);
        }
    }

    private void renderRow(DrawContext drawContext, List<AnimatedRow> rows, RowRenderLayout layout,
                           boolean broken, Alignment alignment, int rowIndex, int rowCount,
                           List<RowRenderLayout> layouts) {
        RoundedRectangle bounds = this.rowBounds(layout, broken, alignment, rowIndex, rowCount, layouts);
        int rowColor = this.colorForPosition(layout.rowIndex, 0.5f, Math.max(1, rows.size() - 1));

        // Glow effect (behind background)
        if (this.glowEnabled.getValue()) {
            float gRadius = this.glowRadius.getValue().floatValue();
            int gAlpha = Math.round(this.glowAlpha.getValue().floatValue() * layout.progress);
            if (gAlpha > 0 && gRadius > 0.0f) {
                RenderUtil.drawShadow(drawContext.getPoseStack(),
                    bounds.x1, bounds.y1, bounds.getWidth(), bounds.getHeight(),
                    (int) gRadius, (gAlpha << 24) | 0x000000);
                RenderUtil.enableBlend();
            }
        }

        if (this.backgroundEnabled.getValue()) {
            try (Paint paint = new Paint()) {
                int alpha = Math.round(this.backgroundAlpha.getValue().floatValue() * layout.progress);
                paint.setColor((alpha << 24) | 0x000000);
                if (this.backgroundRadius.getValue().floatValue() <= 0.0f) {
                    drawContext.drawRectXYWH(layout.x, layout.y, layout.width, layout.height, paint);
                } else {
                    drawContext.drawRoundedRect(bounds, paint);
                }
            }
        }
        if (this.sideLineEnabled.getValue()) {
            this.drawSideLine(drawContext, bounds, Argb.withAlpha(rowColor, layout.progress), alignment, broken);
        }
        drawContext.save();
        drawContext.clipRoundedRect(bounds, true);
        this.drawModuleName(drawContext, layout.row.name, layout.x, layout.y, layout.width, layout.fullHeight,
                layout.rowIndex, rows.size(), layout.progress, alignment);
        drawContext.restore();
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

    private RoundedRectangle rowBounds(RowRenderLayout layout, boolean broken, Alignment alignment, int rowIndex, int rowCount,
                                         List<RowRenderLayout> layouts) {
        float radius = this.backgroundRadius.getValue().floatValue();
        if (broken || radius <= 0.0f) {
            return RoundedRectangle.ofXYWHR(layout.x, layout.y, layout.width, layout.height, radius);
        }
        // Compute a per-row side corner radius based on the width difference
        // with the next row below, so adjacent rows with similar widths blend smoothly.
        float widthDiffRadius = radius;
        if (rowIndex < rowCount - 1) {
            float nextWidth = layouts.get(rowIndex + 1).width;
            float diff = Math.abs(layout.width - nextWidth);
            widthDiffRadius = Math.min(radius, diff);
        }

        float tl, tr, br, bl;
        if (alignment == Alignment.LEFT) {
            tl = 0; tr = 0; br = widthDiffRadius; bl = 0;
            if (rowIndex == 0) { tl = radius; tr = radius; }
            if (rowIndex == rowCount - 1) { bl = radius; br = radius; }
        } else {
            tl = 0; tr = 0; br = 0; bl = widthDiffRadius;
            if (rowIndex == 0) { tl = radius; tr = radius; }
            if (rowIndex == rowCount - 1) { br = radius; bl = radius; }
        }
        float snap = this.pixelSnap();
        float y1 = Math.round(layout.y / snap) * snap;
        float y2 = Math.round((layout.y + layout.visualHeight(false)) / snap) * snap;
        if (y2 < y1 + snap) {
            y2 = y1 + snap;
        }
        return RoundedRectangle.ofXYWHRadii(layout.x, y1, layout.width, y2 - y1,
                new float[]{tl, tr, br, bl});
    }

    private float snapToPixel(float value) {
        float snap = this.pixelSnap();
        return Math.round(value / snap) * snap;
    }

    private float pixelSnap() {
        if (mc == null || mc.getWindow() == null) {
            return 1.0f;
        }
        double scale = mc.getWindow().getGuiScale();
        if (scale <= 0.0) {
            return 1.0f;
        }
        return (float) (1.0 / scale);
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

    private void drawModuleName(DrawContext drawContext, String text, float rowX, float rowY, float rowWidth, float rowHeight,
                                int rowIndex, int rowCount, float alpha, Alignment alignment) {
        boolean useMcFont = this.usesMinecraftFont();
        float textWidth;
        float textY;
        if (useMcFont) {
            textWidth = mc.font.width(text);
            textY = rowY + (rowHeight + mc.font.lineHeight) / 2.0f
                    - 10.0f
                    + this.paddingY.getValue().floatValue() * 0.25f;
        } else {
            FontRenderer font = this.resolveCustomFont();
            textWidth = GlHelper.getStringWidth(text, font);
            textY = rowY + (rowHeight - (float) GlHelper.getFontAscent(font)) / 2.0f
                    + this.paddingY.getValue().floatValue() * 0.25f;
        }
        float padding = this.paddingX.getValue().floatValue();
        float lineReserve = this.sideLineEnabled.getValue() ? this.sideLineWidth.getValue().floatValue() : 0.0f;
        float textX = alignment == Alignment.RIGHT
                ? rowX + rowWidth - padding - textWidth - (this.resolveLineAlignment(alignment) == Alignment.RIGHT ? lineReserve : 0.0f)
                : rowX + padding + (this.resolveLineAlignment(alignment) == Alignment.LEFT ? lineReserve : 0.0f);
        int color = this.colorForPosition(rowIndex, 0.5f, Math.max(1, rowCount - 1));
        int finalColor = Argb.withAlpha(color, alpha);

        if (useMcFont) {
            var bufferSource = drawContext.getGuiGraphics().bufferSource();
            mc.font.drawInBatch(text, textX, textY, finalColor, false,
                    drawContext.getPoseStack().last().pose(),
                    bufferSource,
                    net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0xF000F0);
            bufferSource.endBatch();
        } else {
            FontRenderer font = this.resolveCustomFont();
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
    }

    public int getThemeColor(int positionIndex, float positionProgress, int maxPositionIndex) {
        return this.colorForPosition(positionIndex, positionProgress, maxPositionIndex);
    }

    private int colorForPosition(int rowIndex, float charProgress, int maxRowIndex) {
        if (this.useClientColor.getValue()) {
            return ColorUtil.getRainbowColor(this.rainbowSpeed.getValue().intValue(),
                    rowIndex * this.rainbowOffset.getValue().intValue()).getRGB();
        }
        String mode = this.textColorMode.getValue();
        int speed = this.rainbowSpeed.getValue().intValue();
        int offset = rowIndex * this.rainbowOffset.getValue().intValue();
        float saturation = this.rainbowSaturation.getValue().floatValue();
        float brightness = this.rainbowBrightness.getValue().floatValue();

        if ("Rainbow".equals(mode)) {
            return ColorUtil.getRainbowColor(speed, offset).getRGB();
        }
        if ("Gradient".equals(mode)) {
            GradientTheme theme = GradientTheme.fromName(this.gradientTheme.getValue());
            double position = (double) ((System.currentTimeMillis() / (long) speed + (long) offset) % 1000L) / 1000.0;
            return theme.getColorAt(position, saturation, brightness).getRGB();
        }
        if ("Solid".equals(mode)) {
            // Use client color as solid color
            return ColorUtil.getRainbowColor(speed, 0).getRGB();
        }
        // Default to rainbow
        return ColorUtil.getRainbowColor(speed, offset).getRGB();
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