package shit.nilore.hud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import shit.nilore.utils.render.ColorUtil;

public class NotificationHud extends HudElement {

    private static final float CARD_WIDTH = 190.0f;
    private static final float CARD_HEIGHT = 50.0f;
    private static final float CARD_RADIUS = 6.0f;
    private static final float PADDING = 8.0f;
    private static final float BAR_HEIGHT = 3.0f;
    private static final float SPACING = 6.0f;
    private static final int BG_COLOR = 0xCC000000;
    private static final int BAR_COLOR = 0xFF4488FF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final NumberSetting margin = new NumberSetting("Margin", 8.0f, 0.0f, 100.0f, 1.0f);
    private final NumberSetting duration = new NumberSetting("Duration (ms)", 1600, 500, 10000, 100);
    private final NumberSetting maxNotifications = new NumberSetting("Max Notifications", 7, 1, 10, 1);

    private final List<NotificationEntry> notifications = new ArrayList<>();

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

        Iterator<NotificationEntry> it = notifications.iterator();
        while (it.hasNext()) {
            NotificationEntry entry = it.next();
            if (now - entry.time > dur) {
                it.remove();
            }
        }

        if (notifications.isEmpty()) {
            return;
        }

        float screenW = mc.getWindow().getGuiScaledWidth();
        float screenH = mc.getWindow().getGuiScaledHeight();
        float marginVal = margin.getValue().floatValue();

        Renderer.render(event.guiGraphics(), drawContext -> {
            for (int i = 0; i < notifications.size(); i++) {
                NotificationEntry entry = notifications.get(i);
                float elapsed = now - entry.time;
                float progress = 1.0f - Mth.clamp(elapsed / (float) dur, 0.0f, 1.0f);

                float fadeAlpha;
                if (elapsed < 200.0f) {
                    fadeAlpha = elapsed / 200.0f;
                } else if (elapsed > dur - 300.0f) {
                    fadeAlpha = (dur - elapsed) / 300.0f;
                } else {
                    fadeAlpha = 1.0f;
                }
                fadeAlpha = Mth.clamp(fadeAlpha, 0.0f, 1.0f);

                float cardX = screenW - CARD_WIDTH - marginVal;
                float cardY = screenH - CARD_HEIGHT - marginVal - i * (CARD_HEIGHT + SPACING);

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

        // Blue progress bar at the bottom
        float barWidth = CARD_WIDTH * progress;
        if (barWidth > 0.5f) {
            try (Paint paint = new Paint()) {
                paint.setColor(ColorUtil.withAlpha(BAR_COLOR, alpha));
                drawContext.drawRoundedRect(
                        RoundedRectangle.ofXYWHR(x, y + CARD_HEIGHT - BAR_HEIGHT, barWidth, BAR_HEIGHT, 1.5f),
                        paint
                );
            }
        }

        // Text: line 1 "Module" (larger font)
        FontRenderer titleFont = FontPresets.pingfang(18.0f);
        FontRenderer descFont = FontPresets.pingfang(14.0f);

        float textX = x + PADDING;
        float titleY = y + PADDING;
        float descY = titleY + 18.0f + 4.0f;

        int titleColor = ColorUtil.withAlpha(TEXT_COLOR, alpha);
        int descColor = ColorUtil.withAlpha(0xFFCCCCCC, alpha);

        GlHelper.drawText("Module", textX, titleY, titleFont, titleColor);

        String stateText = "Toggled " + entry.name + " " + (entry.enabled ? "ON" : "OFF");
        GlHelper.drawText(stateText, textX, descY, descFont, descColor);
    }

    @Override
    public void onGlRender(GlRenderEvent event, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    private static class NotificationEntry {
        final String name;
        final boolean enabled;
        final long time;

        NotificationEntry(String name, boolean enabled, long time) {
            this.name = name;
            this.enabled = enabled;
            this.time = time;
        }
    }
}
