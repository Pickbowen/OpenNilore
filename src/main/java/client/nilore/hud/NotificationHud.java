package client.nilore.hud;

import client.nilore.NiloreClient;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.ModuleToggleEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Rectangle;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.render.Texture;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.SoundUtil;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.render.TextureUtil;
import com.mojang.blaze3d.vertex.PoseStack;

public class NotificationHud extends HudElement {

    private static final float SOUTHSIDE_WIDTH = 171.0f;
    private static final float SOUTHSIDE_HEIGHT = 45.0f;
    private static final float SOUTHSIDE_RADIUS = 5.4f;
    private static final float SOUTHSIDE_PADDING = 7.2f;
    private static final float SOUTHSIDE_BAR_HEIGHT = 2.7f;
    private static final float SOUTHSIDE_SPACING = 5.4f;
    private static final float SOUTHSIDE_ICON_SIZE = 21.6f;
    private static final float SIMPLE_HEIGHT = 30.0f;
    private static final float SIMPLE_RADIUS = 5.0f;
    private static final float SIMPLE_PADDING = 7.0f;
    private static final float SIMPLE_ICON_SIZE = 16.0f;
    private static final float SIMPLE_ICON_GAP = 6.0f;
    private static final float SIMPLE_SPACING = 4.0f;
    private static final float AKARIN_MIN_WIDTH = 110.0f;
    private static final float AKARIN_HEIGHT = 29.0f;
    private static final float AKARIN_RADIUS = 6.0f;
    private static final float AKARIN_TEXT_X = 6.0f;
    private static final float AKARIN_RIGHT_PADDING = 6.0f;
    private static final float AKARIN_STRIPE_X = 0.0f;
    private static final float AKARIN_STRIPE_WIDTH = 1.5f;
    private static final float AKARIN_SPACING = 5.0f;
    private static final FontRenderer AKARIN_TITLE_FONT = Fonts.getRenderer("quicksand.ttf", 17.0f);
    private static final FontRenderer AKARIN_STATUS_FONT = Fonts.getRenderer("quicksand.ttf", 18.0f);
    private static final int BG_COLOR = 0xFF111615;
    private static final int BAR_COLOR = 0xFFFFFFFF;
    private static final int BAR_BG_COLOR = 0xFF3A3A3A;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final NumberSetting margin = new NumberSetting("Margin", 18, 0.0f, 100.0f, 1.0f);
    private final NumberSetting duration = new NumberSetting("Duration (ms)", 1200, 500, 10000, 100);
    private final NumberSetting maxNotifications = new NumberSetting("Max Notifications", 6, 1, 10, 1);
    private final BooleanSetting needSound = new BooleanSetting("Sound", true);
    private final ModeSetting whichSound = new ModeSetting("Type", "Sigma", "Lever").withDefault("Lever");
    private final ModeSetting style = new ModeSetting("Style", "Southside", "Simple", "Naven", "Akarin").withDefault("Akarin");
    private final NumberSetting navenTextXOff = new NumberSetting("Naven Text X Off", 7, 0, 20, 0.5f);
    private final NumberSetting navenTextYOff = new NumberSetting("Naven Text Y Off", 11.5, 0, 20, 0.5f);
    private final NumberSetting navenWidthPad = new NumberSetting("Naven Width Pad", 10, 0, 20, 1);
    private final NumberSetting navenCardH = new NumberSetting("Naven Card Height", 19, 12, 30, 1);
    private final NumberSetting navenRadius = new NumberSetting("Naven Radius", 6, 0, 10, 0.5f);
    private final NumberSetting navenSlideMs = new NumberSetting("Naven Slide (ms)", 80, 20, 300, 10);

    private final List<NotificationEntry> notifications = new ArrayList<>();

    private DynamicTexture enabledIcon;
    private DynamicTexture disabledIcon;
    private Texture enabledTexture;
    private Texture disabledTexture;

    public NotificationHud() {
        super("Notification");
        this.setWidth(SOUTHSIDE_WIDTH);
        this.setHeight(SOUTHSIDE_HEIGHT);
        this.setEnabled(true);
        navenTextXOff.setVisibility(() -> style.is("Naven"));
        navenTextYOff.setVisibility(() -> style.is("Naven"));
        navenWidthPad.setVisibility(() -> style.is("Naven"));
        navenCardH.setVisibility(() -> style.is("Naven"));
        navenRadius.setVisibility(() -> style.is("Naven"));
        navenSlideMs.setVisibility(() -> style.is("Naven"));
    }

    @Override
    public void registerSettings() {
        this.registerSetting(margin, duration, maxNotifications, needSound, whichSound, style, navenTextXOff, navenTextYOff, navenWidthPad, navenCardH, navenRadius, navenSlideMs);
    }

    @EventTarget
    public void onModuleToggle(ModuleToggleEvent event) {
        if (event.module() == this) {
            return;
        }
        loadTextures();
        String entryStyle = style.getValue();
        boolean simple = "Simple".equals(entryStyle);
        boolean naven = "Naven".equals(entryStyle);
        boolean akarin = "Akarin".equals(entryStyle);
        FontRenderer navenFont = FontPresets.openSans(18f);
        String displayText = akarin
                ? (event.enabled() ? "Enabled " : "Disabled ") + event.module().getName()
                : event.module().getName() + (event.enabled() ? " Enabled" : " Disabled");
        float width = akarin
                ? Math.max(AKARIN_MIN_WIDTH, AKARIN_TEXT_X + AKARIN_STATUS_FONT.getWidth(displayText) + AKARIN_RIGHT_PADDING)
                : naven
                ? navenFont.getWidth(displayText) + navenWidthPad.getValue().floatValue()
                : simple
                ? SIMPLE_PADDING * 2.0f + SIMPLE_ICON_SIZE + SIMPLE_ICON_GAP
                + GlHelper.getStringWidth(displayText, FontPresets.pingfang(18.0f)) + 3.0f
                : SOUTHSIDE_WIDTH;
        float height = akarin ? AKARIN_HEIGHT : naven ? navenCardH.getValue().floatValue() + 4f : simple ? SIMPLE_HEIGHT : SOUTHSIDE_HEIGHT;
        float spacing = akarin ? AKARIN_SPACING : naven ? 4f : simple ? SIMPLE_SPACING : SOUTHSIDE_SPACING;
        notifications.add(new NotificationEntry(event.module().getName(), event.enabled(), System.currentTimeMillis(),
                entryStyle, displayText, width, height, spacing));
        while (notifications.size() > maxNotifications.getValue().intValue()) {
            notifications.remove(0);
        }
        if (this.isEnabled() && needSound.getValue()) {
            if ("Lever".equals(whichSound.getValue())) {
                if (mc.player != null) {
                    if (event.enabled()) mc.player.playSound(SoundEvents.LEVER_CLICK, 1f, 0.6f);
                    else mc.player.playSound(SoundEvents.LEVER_CLICK, 1f, 0.5f);
                }
            } else {
                String soundPath = event.enabled() ? "/assets/nilore/notifications/Enabled.wav" : "/assets/nilore/notifications/Disabled.wav";
                SoundUtil.playResourceSound(soundPath, 0.0f);
            }
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
        this.setWidth(isSimpleStyle() || isNavenStyle() || isAkarinStyle() ? 0.0f : SOUTHSIDE_WIDTH);
        this.setHeight(isAkarinStyle() ? AKARIN_HEIGHT
                : isNavenStyle() ? navenCardH.getValue().floatValue() + 4f
                : isSimpleStyle() ? SIMPLE_HEIGHT : SOUTHSIDE_HEIGHT);

        Iterator<NotificationEntry> it = notifications.iterator();
        while (it.hasNext()) {
            NotificationEntry entry = it.next();
            long elapsed = now - entry.time;
            float targetX = screenW - entry.width - marginVal;
            float offscreenX = screenW + 10.0f;

            if (elapsed < dur) {
                if (!entry.entranceStarted) {
                    entry.entranceStarted = true;
                    entry.entranceTime = now;
                    entry.x = offscreenX;
                }
                float entranceElapsed = (now - entry.entranceTime) / 1000f;
                float slideSec = "Naven".equals(entry.style) ? navenSlideMs.getValue().floatValue() / 1000f : 0.08f;
                float t = Math.min(1.0f, entranceElapsed / slideSec);
                entry.x = offscreenX + (targetX - offscreenX) * t;
                entry.alpha = Math.min(1.0f, entry.alpha + 0.05f);
            } else if (!entry.exiting) {
                entry.exiting = true;
                entry.lastBarProgress = 0.0f;
                entry.exitStartTime = now;
            } else {
                float exitElapsed = (now - entry.exitStartTime) / 1000f;
                float exitSec = "Naven".equals(entry.style) ? navenSlideMs.getValue().floatValue() / 1000f : 0.04f;
                float t = Math.min(1.0f, exitElapsed / exitSec);
                entry.x = targetX + (offscreenX - targetX) * t;
                entry.alpha = 1.0f - t;
                if (t >= 1.0f) {
                    it.remove();
                }
            }
        }

        if (notifications.isEmpty()) {
            return;
        }

        Renderer.render(event.guiGraphics(), drawContext -> {
            float nextY = screenH - marginVal;
            for (NotificationEntry entry : notifications) {
                float cardY = nextY - entry.height;
                nextY = cardY - entry.spacing;
                long elapsed = now - entry.time;
                float progress = entry.exiting ? entry.lastBarProgress
                        : Mth.clamp((float) elapsed / dur, 0.0f, 1.0f);
                if (!entry.exiting) {
                    entry.lastBarProgress = progress;
                }
                renderCard(drawContext, entry, entry.x, cardY, progress, Mth.clamp(entry.alpha, 0.0f, 1.0f));
            }
        });
    }

    private void renderCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float progress, float alpha) {
        switch (entry.style) {
            case "Simple" -> renderSimpleCard(drawContext, entry, x, y, alpha);
            case "Naven" -> renderNavenCard(drawContext, entry, x, y, alpha);
            case "Akarin" -> renderAkarinCard(drawContext, entry, x, y, alpha);
            default -> renderSouthsideCard(drawContext, entry, x, y, progress, alpha);
        }
    }

    private void renderSouthsideCard(DrawContext drawContext, NotificationEntry entry,
                                     float x, float y, float progress, float alpha) {
        float barY = y + entry.height - SOUTHSIDE_BAR_HEIGHT;
        float barH = SOUTHSIDE_BAR_HEIGHT;
        float barWidth = entry.width * progress;
        float[] radii = new float[]{SOUTHSIDE_RADIUS, SOUTHSIDE_RADIUS, SOUTHSIDE_RADIUS, SOUTHSIDE_RADIUS};

        try (Paint paint = new Paint()) {
            // 1) Full card in dark (base layer)
            paint.setColor(ColorUtil.withAlpha(BG_COLOR, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, entry.width, entry.height, radii), paint);

            // 2) Clip to bar area, draw same full rect in gray
            drawContext.save();
            drawContext.clip(Rectangle.ofXYWH(x, barY + 1, entry.width, barH - 1));
            paint.setColor(ColorUtil.withAlpha(BAR_BG_COLOR, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, entry.width, entry.height, radii), paint);
            drawContext.restore();

            // 3) Clip to progress width, draw same full rect in white
            if (barWidth > 0.5f) {
                drawContext.save();
                drawContext.clip(Rectangle.ofXYWH(x, barY + 1, barWidth, barH - 1));
                paint.setColor(ColorUtil.withAlpha(BAR_COLOR, alpha));
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, entry.width, entry.height, radii), paint);
                drawContext.restore();
            }
        }

        float drawSize = entry.enabled ? SOUTHSIDE_ICON_SIZE * 0.9f : SOUTHSIDE_ICON_SIZE;
        drawIcon(drawContext, entry.enabled, x + SOUTHSIDE_PADDING,
                y + (entry.height - SOUTHSIDE_BAR_HEIGHT - drawSize) / 2.0f, drawSize, alpha);
        float textX = x + SOUTHSIDE_PADDING + SOUTHSIDE_ICON_SIZE + 5.4f;
        FontRenderer titleFont = FontPresets.pingfang(16.2f);
        FontRenderer descFont = FontPresets.pingfang(12.6f);
        GlHelper.drawText("Module", textX, y + SOUTHSIDE_PADDING + 6.05f, titleFont, ColorUtil.withAlpha(TEXT_COLOR, alpha));
        GlHelper.drawText("Toggled " + entry.name + " " + (entry.enabled ? "on" : "off"), textX,
                y + SOUTHSIDE_PADDING + 18.25f, descFont, ColorUtil.withAlpha(0xFFCCCCCC, alpha));
    }

    private void renderSimpleCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float alpha) {
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(BG_COLOR, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, entry.width, entry.height, SIMPLE_RADIUS), paint);
        }
        float drawSize = entry.enabled ? SIMPLE_ICON_SIZE * 0.9f : SIMPLE_ICON_SIZE;
        drawIcon(drawContext, entry.enabled, x + SIMPLE_PADDING, y + (entry.height - drawSize) / 2.0f, drawSize, alpha);
        FontRenderer font = FontPresets.pingfang(18.0f);
        GlHelper.drawText(entry.displayText, x + SIMPLE_PADDING + SIMPLE_ICON_SIZE + SIMPLE_ICON_GAP,
                y + entry.height / 2.0f - 2f, font, ColorUtil.withAlpha(TEXT_COLOR, alpha));
    }

    private void renderAkarinCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float alpha) {
        // Gaussian blur background (same as RoundTargetStyle)
        PoseStack poseStack = drawContext.getPoseStack();
        poseStack.pushPose();
        RenderUtil.drawBlurredRect(poseStack, x, y, entry.width, entry.height, AKARIN_RADIUS, 15.0f, alpha * 0.95f, 0);
        poseStack.popPose();

        int backgroundAlpha = Math.round(80.0f * alpha);
        try (Paint paint = new Paint()) {
            paint.setColor((backgroundAlpha << 24) | 0x00080C16);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, entry.width, entry.height, AKARIN_RADIUS), paint);
        }

        ModuleListHud moduleList = NiloreClient.getInstance().getHudManager() == null
                ? null
                : NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class);
        int topColor = moduleList == null ? 0xFFFFFFFF : moduleList.getThemeColor(0, 0.0f, 1);
        int bottomColor = moduleList == null ? 0xFFFFFFFF : moduleList.getThemeColor(1, 1.0f, 1);
        float titleY = y + 7.0f;
        float stripeY = y + 5.0f;
        float stripeHeight = AKARIN_TITLE_FONT.getMetrics().capHeight();

        // Glow: only expands rightward since stripe is at left card edge
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(topColor, alpha * 0.13f));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(
                    x + AKARIN_STRIPE_X, stripeY - 1.5f,
                    AKARIN_STRIPE_WIDTH + 1.5f, stripeHeight + 3.0f, 2.0f), paint);
        }

        int gradientTop = ColorUtil.withAlpha(topColor, alpha);
        int gradientBottom = ColorUtil.withAlpha(bottomColor, alpha);
        try (Paint paint = new Paint()) {
            paint.setGradCoords(new Paint.GradientCoords(
                    x + AKARIN_STRIPE_X, stripeY,
                    x + AKARIN_STRIPE_X, stripeY + stripeHeight,
                    gradientTop, gradientBottom));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(
                    x + AKARIN_STRIPE_X, stripeY,
                    AKARIN_STRIPE_WIDTH, stripeHeight, AKARIN_STRIPE_WIDTH / 2.0f), paint);
        }

        GlHelper.drawText("Module", x + AKARIN_TEXT_X, titleY,
                AKARIN_TITLE_FONT, ColorUtil.withAlpha(0xFFF4F6FA, alpha));
        GlHelper.drawText(entry.displayText, x + AKARIN_TEXT_X, y + 19.0f,
                AKARIN_STATUS_FONT, ColorUtil.withAlpha(0xFFF4F6FA, alpha * (160.0f / 255.0f)));
    }

    private void drawIcon(DrawContext drawContext, boolean enabled, float x, float y, float size, float alpha) {
        Texture texture = enabled ? enabledTexture : disabledTexture;
        if (texture == null) {
            return;
        }
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(0xFFFFFFFF, alpha));
            drawContext.drawTexture(texture,
                    Rectangle.ofXYWH(0.0f, 0.0f, texture.getWidth(), texture.getHeight()),
                    Rectangle.ofXYWH(x, y, size, size), paint);
        }
    }

    @Override
    public void onGlRender(GlRenderEvent event, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    private boolean isSimpleStyle() {
        return "Simple".equals(style.getValue());
    }

    private boolean isNavenStyle() {
        return "Naven".equals(style.getValue());
    }

    private boolean isAkarinStyle() {
        return "Akarin".equals(style.getValue());
    }

    private void renderNavenCard(DrawContext drawContext, NotificationEntry entry, float x, float y, float alpha) {
        int bgColor = entry.enabled ? 0xFF179626 : 0xFF942A2B;
        float cardW = entry.width;
        float cardH = navenCardH.getValue().floatValue();
        float radius = navenRadius.getValue().floatValue();
        try (Paint paint = new Paint()) {
            paint.setColor(ColorUtil.withAlpha(bgColor, alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x + 2f, y + 4f, cardW, cardH, radius), paint);
        }
        FontRenderer font = FontPresets.openSans(18f);
        int textColor = ((int)(alpha * 255f) & 0xFF) << 24 | 0x00FFFFFF;
        GlHelper.drawText(entry.displayText, x + navenTextXOff.getValue().floatValue(), y + navenTextYOff.getValue().floatValue(), font, textColor);
    }

    private void loadTextures() {
        if (enabledIcon != null && disabledIcon != null) {
            return;
        }
        enabledIcon = TextureUtil.loadResourceTexture("/assets/nilore/notifications/Enabled.png");
        disabledIcon = TextureUtil.loadResourceTexture("/assets/nilore/notifications/Disabled.png");
        enabledTexture = wrapIcon(enabledIcon);
        disabledTexture = wrapIcon(disabledIcon);
    }

    private static Texture wrapIcon(DynamicTexture icon) {
        NativeImage pixels = icon == null ? null : icon.getPixels();
        if (pixels == null) {
            return null;
        }
        return new Texture(icon.getId(), pixels.getWidth(), pixels.getHeight());
    }

    private static class NotificationEntry {
        final String name;
        final boolean enabled;
        final long time;
        final String style;
        final String displayText;
        final float width;
        final float height;
        final float spacing;
        float x = 9999f;
        float alpha;
        boolean entranceStarted;
        long entranceTime;
        boolean exiting;
        long exitStartTime;
        float lastBarProgress = 1.0f;

        NotificationEntry(String name, boolean enabled, long time, String style, String displayText,
                          float width, float height, float spacing) {
            this.name = name;
            this.enabled = enabled;
            this.time = time;
            this.style = style;
            this.displayText = displayText;
            this.width = width;
            this.height = height;
            this.spacing = spacing;
        }
    }
}
