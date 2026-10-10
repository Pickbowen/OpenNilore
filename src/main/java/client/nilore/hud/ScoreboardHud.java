package client.nilore.hud;

import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import net.minecraft.util.Mth;

/**
 * Scoreboard HUD — data provider only.
 *
 * Actual rendering happens inside {@link client.nilore.patch.GuiPatch}
 * where we have access to the original GuiGraphics from inside
 * Gui.render().  This avoids the buffer-flushing issue that occurs
 * when rendering on the separate onRender2D GuiGraphics.
 *
 * The x/y/width/height are set here so dragging works from the
 * HUD manager.
 */
public class ScoreboardHud extends HudElement {

    private static final float MIN_VISIBLE_EDGE = 2.0f;
    private static final float DEFAULT_X = 0.0f;
    private static final float DEFAULT_Y = 20.0f;

    private boolean autoPositioned = false;

    public final BooleanSetting backgroundEnabled = new BooleanSetting("Background", true);
    public final NumberSetting backgroundAlpha    = new NumberSetting("Background Alpha", 70, 0, 255, 1);
    public final NumberSetting backgroundRadius   = new NumberSetting("Background Radius", 2.0f, 0.0f, 10.0f, 0.25f);

    public final BooleanSetting blurEnabled  = new BooleanSetting("Blur", false);
    public final NumberSetting blurStrength  = new NumberSetting("Blur Strength", 15.0f, 1.0f, 30.0f, 1.0f);
    public final NumberSetting blurOpacity   = new NumberSetting("Blur Opacity", 0.95f, 0.0f, 1.0f, 0.05f);

    public final BooleanSetting glowEnabled  = new BooleanSetting("Glow", false);
    public final NumberSetting glowRadius    = new NumberSetting("Glow Radius", 12, 4, 40, 1);
    public final NumberSetting glowAlpha     = new NumberSetting("Glow Alpha", 120, 0, 255, 1);

    public ScoreboardHud() {
        super("Scoreboard");
        setX(DEFAULT_X);
        setY(DEFAULT_Y);
        setWidth(0.0f);
        setHeight(0.0f);
    }

    @Override
    public void registerSettings() {
        registerSetting(backgroundEnabled, backgroundAlpha, backgroundRadius,
                        blurEnabled, blurStrength, blurOpacity,
                        glowEnabled, glowRadius, glowAlpha);
    }

    /**
     * Called by ValuesConfig after restoring this HUD's X/Y from config.
     * Prevents first-time auto-position from overriding a saved position.
     */
    public void markPositionLoaded() {
        this.autoPositioned = true;
    }

    /** No-op — rendering is done in GuiPatch. */
    @Override
    public void onRender2D(Render2DEvent event, float x, float y) {}

    @Override
    public void onGlRender(GlRenderEvent event, float x, float y) {}

    @Override
    public void onSettings() {}

    /**
     * Clamp this HUD's position so it stays fully visible on screen.
     * Safe to call even when mc.getWindow() is not ready (no-op).
     */
    public void clampToScreen(float width, float height) {
        if (mc == null || mc.getWindow() == null) return;
        float sw = mc.getWindow().getGuiScaledWidth();
        float sh = mc.getWindow().getGuiScaledHeight();
        if (sw < 1.0f || sh < 1.0f) return;          // window not ready yet
        float maxX = Math.max(MIN_VISIBLE_EDGE, sw - width - MIN_VISIBLE_EDGE);
        float maxY = Math.max(MIN_VISIBLE_EDGE, sh - height - MIN_VISIBLE_EDGE);
        setX(Mth.clamp(getX(), MIN_VISIBLE_EDGE, maxX));
        setY(Mth.clamp(getY(), MIN_VISIBLE_EDGE, maxY));
    }

    @Override
    public void mouseDragged(int mouseX, int mouseY) {
        setX((float) mouseX - getDragOffsetX());
        setY((float) mouseY - getDragOffsetY());
        clampToScreen(Math.max(getWidth(), 1.0f), Math.max(getHeight(), 1.0f));
    }

    @Override
    public void stopDragging() {
        boolean wasDragging = isDragging();
        super.stopDragging();
        if (wasDragging) {
            client.nilore.NiloreClient.getInstance().getConfigManager().saveAll();
        }
    }

    /**
     * Reset auto-position flag so the next render re-positions.
     * Used when migrating from old configs that may have a stale position.
     */
    public void resetAutoPosition() {
        this.autoPositioned = false;
    }

    public boolean autoPositioned() {
        return autoPositioned;
    }

    public boolean isAtDefaultPosition() {
        return getX() == DEFAULT_X && getY() == DEFAULT_Y;
    }
}