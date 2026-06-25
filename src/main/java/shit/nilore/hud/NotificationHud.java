package shit.nilore.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.ModuleToggleEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.render.Renderer;
import shit.nilore.render.RoundedRectangle;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.TextureUtil;

public class NotificationHud extends HudElement {

    private static final float CARD_WIDTH = 171.0f;
    private static final float CARD_HEIGHT = 45.0f;
    private static final float CARD_RADIUS = 5.4f;
    private static final float PADDING = 7.2f;
    private static final float BAR_HEIGHT = 2.7f;
    private static final float SPACING = 5.4f;
    private static final float ICON_SIZE = 21.6f;
    private static final int BG_COLOR = 0xFF111615;
    private static final int BAR_COLOR = 0xFF1E6BD0;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final NumberSetting margin = new NumberSetting("Margin", 8.0f, 0.0f, 100.0f, 1.0f);
    private final NumberSetting duration = new NumberSetting("Duration (ms)", 1600, 500, 10000, 100);
    private final NumberSetting maxNotifications = new NumberSetting("Max Notifications", 7, 1, 10, 1);

    private final List<NotificationEntry> notifications = new ArrayList<>();

    private DynamicTexture enabledIcon;
    private DynamicTexture disabledIcon;

    public NotificationHud() {
        super("Notification");
        this.setWidth(CARD_WIDTH);
        this.setHeight(CARD_HEIGHT);
        this.setEnabled(true);
    }

    @Override
    public void registerSettings() {
        this.registerSetting(margin, duration, maxNotifications);
    }

    @EventTarget
    public void onModuleToggle(ModuleToggleEvent event) {
        if (event.module() == this) {
            return;
        }
        loadTextures();
        notifications.add(new NotificationEntry(
                event.module().getName(),
                event.enabled(),
                System.currentTimeMillis()
        ));
        while (notifications.size() > maxNotifications.getValue().intValue()) {
            notifications.remove(0);
        }
    }

    @Override
    public void onRender2D(Render2DEvent event, float px, float py) {
        if (mc.getWindow() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long dur = duration.getValue().longValue();
        float screenW = mc.getWindow().getGuiScaledWidth();
        float screenH = mc.getWindow().getGuiScaledHeight();
        float marginVal = margin.getValue().floatValue();
        float targetX = screenW - CARD_WIDTH - marginVal;
        float baseY = screenH - CARD_HEIGHT - marginVal;

        Iterator<NotificationEntry> it = notifications.iterator();
        while (it.hasNext()) {
            NotificationEntry entry = it.next();
            long elapsed = now - entry.time;

            if (elapsed < dur) {
                // Visible: entrance or steady state
                if (!entry.entranceStarted) {
                    // First render: kick off entrance animations
                    entry.entranceStarted = true;
                    entry.xAnim.animate(targetX, 0.3, Easings.EASE_OUT_QUAD);
                    entry.alphaAnim.animate(1.0, 0.25, Easings.EASE_OUT_QUAD);
                }
                entry.xAnim.tick();
                entry.alphaAnim.tick();
            } else if (!entry.exiting) {
                // Time's up: start exit
                entry.exiting = true;
                entry.lastBarProgress = 0.0f;
                entry.exitStartTime = now;
                entry.alphaAnim.setCurrentValue(1.0);
                entry.alphaAnim.animate(0.0, 0.2, Easings.EASE_OUT_QUAD);
                entry.xAnim.animate(screenW + 10.0, 0.2, Easings.EASE_OUT_QUAD);
            } else {
                // Exiting
                entry.xAnim.tick();
                entry.alphaAnim.tick();
                if (!entry.alphaAnim.isAnimating() || (now - entry.exitStartTime > 300)) {
                    it.remove();
                    continue;
                }
            }
        }

        if (notifications.isEmpty()) {
            return;
        }

        Renderer.render(event.guiGraphics(), drawContext -> {
            for (int i = 0; i < notifications.size(); i++) {
                NotificationEntry entry = notifications.get(i);
                long elapsed = now - entry.time;
                float fadeAlpha = Mth.clamp(entry.alphaAnim.getValueF(), 0.0f, 1.0f);
                float cardX = entry.xAnim.getValueF();
                float cardY = baseY - i * (CARD_HEIGHT + SPACING);

                float progress;
                if (!entry.exiting) {
                    progress = 1.0f - Mth.clamp((float) elapsed / dur, 0.0f, 1.0f);
                    entry.lastBarProgress = progress;
                } else {
                    progress = entry.lastBarProgress;
                }

                renderCard(drawContext, entry, cardX, cardY, progress, fadeAlpha);
            }
        });
    }

    private void renderCard(DrawContext drawContext, NotificationEntry entry,
                            float x, float y, float progress, float alpha) {
        RoundedRectangle rect = RoundedRectangle.ofXYWHR(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS);

        // Background
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(BG_COLOR, alpha));
            drawContext.drawRoundedRect(rect, paint);
        }

        // Bottom progress bar
        float barWidth = CARD_WIDTH * progress;
        if (barWidth > 0.5f) {
            try (Paint paint = new Paint()) {
                paint.setColor(ColorUtil.withAlpha(BAR_COLOR, alpha));
                drawContext.drawRoundedRect(
                        RoundedRectangle.ofXYWHR(x, y + CARD_HEIGHT - BAR_HEIGHT, barWidth, BAR_HEIGHT, CARD_RADIUS),
                        paint
                );
            }
        }

        // Icon (pure white, original PNG colors preserved)
        DynamicTexture icon = entry.enabled ? enabledIcon : disabledIcon;
        float textOffsetX = PADDING;
        if (icon != null) {
            float drawSize = entry.enabled ? ICON_SIZE * 0.9f : ICON_SIZE;
            float iconX = x + PADDING;
            float iconY = y + (CARD_HEIGHT - BAR_HEIGHT - drawSize) / 2.0f;
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, icon.getId());
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            Matrix4f pose = drawContext.getPoseStack().last().pose();
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tesselator.getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            bufferBuilder.vertex(pose, iconX, iconY, 0.0f).uv(0.0f, 0.0f).endVertex();
            bufferBuilder.vertex(pose, iconX, iconY + drawSize, 0.0f).uv(0.0f, 1.0f).endVertex();
            bufferBuilder.vertex(pose, iconX + drawSize, iconY + drawSize, 0.0f).uv(1.0f, 1.0f).endVertex();
            bufferBuilder.vertex(pose, iconX + drawSize, iconY, 0.0f).uv(1.0f, 0.0f).endVertex();
            BufferUploader.drawWithShader(bufferBuilder.end());
            textOffsetX = PADDING + ICON_SIZE + 5.4f;
        }

        // Title text
        FontRenderer titleFont = FontPresets.pingfang(16.2f);
        FontRenderer descFont = FontPresets.pingfang(12.6f);

        float textX = x + textOffsetX;
        float titleY = y + PADDING + 4.05f;
        float descY = titleY + 16.2f;

        int titleColor = ColorUtil.withAlpha(TEXT_COLOR, alpha);
        int descColor = ColorUtil.withAlpha(0xFFCCCCCC, alpha);

        GlHelper.drawText("Module", textX, titleY, titleFont, titleColor);

        String stateText = "Toggled " + entry.name + " " + (entry.enabled ? "on" : "off");
        GlHelper.drawText(stateText, textX, descY, descFont, descColor);
    }

    @Override
    public void onGlRender(GlRenderEvent event, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    private void loadTextures() {
        if (enabledIcon != null && disabledIcon != null) {
            return;
        }
        enabledIcon = TextureUtil.loadTexture("Enabled.png");
        disabledIcon = TextureUtil.loadTexture("Disabled.png");
    }

    private static class NotificationEntry {
        final String name;
        final boolean enabled;
        final long time;
        final SmoothAnimationTimer xAnim = new SmoothAnimationTimer();
        final SmoothAnimationTimer alphaAnim = new SmoothAnimationTimer();
        boolean entranceStarted;
        boolean exiting;
        long exitStartTime;
        float lastBarProgress = 1.0f;

        NotificationEntry(String name, boolean enabled, long time) {
            this.name = name;
            this.enabled = enabled;
            this.time = time;
            // Set initial values; animate() will be called on first render
            this.xAnim.setCurrentValue(9999.0);
            this.alphaAnim.setCurrentValue(0.0);
        }
    }
}
