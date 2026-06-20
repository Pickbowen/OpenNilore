package shit.nilore.gui;

import java.awt.Color;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.event.EventTarget;
import shit.nilore.NiloreClient;

public class IntroAnimation
extends ClientBase {
    private static volatile boolean isActive = false;
    private long startTime = -1L;
    private boolean finished = false;

    public IntroAnimation() {
        isActive = true;
    }

    public static boolean isRunning() {
        return isActive;
    }

    @EventTarget(value=4)
    public void onRender(GlRenderEvent glRenderEvent) {
        float fadeFactor;
        float bgAlpha;
        long elapsed;
        if (this.finished) {
            return;
        }
        if (this.startTime < 0L) {
            this.startTime = System.currentTimeMillis();
        }
        long dup = elapsed = System.currentTimeMillis() - this.startTime;
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;
        long zAppearStart = 1100L;
        long fadeOutStart = zAppearStart + 900L + 700L + 500L + 300L + 500L + 1300L;
        if (elapsed <= 800L) {
            float fadeIn = IntroAnimation.easeOutCubic(IntroAnimation.clamp01((float)elapsed / 800.0f));
            bgAlpha = 0.6f * fadeIn;
        } else if (elapsed <= fadeOutStart) {
            bgAlpha = 0.6f;
        } else if (elapsed <= fadeOutStart + 700L) {
            float fadeOut = 1.0f - IntroAnimation.easeInCubic(IntroAnimation.clamp01((float)(elapsed - fadeOutStart) / 700.0f));
            bgAlpha = 0.6f * fadeOut;
        } else {
            this.finish();
            return;
        }
        Paint paint = GlHelper.toPaint(new Color(0, 0, 0, (int)(bgAlpha * 255.0f)));
        GlHelper.drawRect(0.0f, 0.0f, screenWidth, screenHeight, paint);
        float zScale = 1.0f;
        float zAlpha = 0.0f;
        if (elapsed >= zAppearStart) {
            long sinceZ = elapsed - zAppearStart;
            if (sinceZ <= 900L) {
                float zProgress = IntroAnimation.easeOutCubic(IntroAnimation.clamp01((float)sinceZ / 900.0f));
                zScale = IntroAnimation.lerp(2.0f, 1.0f, zProgress);
                zAlpha = zProgress;
            } else {
                zScale = 1.0f;
                zAlpha = 1.0f;
            }
        }
        float slideProgress = 0.0f;
        long slideStart = zAppearStart + 900L + 700L;
        if (elapsed > slideStart && elapsed <= slideStart + 500L) {
            slideProgress = IntroAnimation.easeOutCubic((float)(elapsed - slideStart) / 500.0f);
        } else if (elapsed > slideStart + 500L) {
            slideProgress = 1.0f;
        }
        FontRenderer scaledZFont = FontPresets.axiformaBold(64.0f * zScale);
        FontRenderer baseFont = FontPresets.axiformaBold(64.0f);
        float zWidth = GlHelper.getStringWidth("N", scaledZFont);
        float enWidth = GlHelper.getStringWidth(NiloreClient.CLIENT_NAME_UPPER.substring(1), baseFont);
        float zCenterX = centerX - zWidth / 2.0f;
        float zSlideX = centerX - (zWidth + 0.0f + enWidth) / 2.0f;
        float zRenderX = IntroAnimation.lerp(zCenterX, zSlideX, slideProgress);
        float zRenderY = centerY - scaledZFont.getMetrics().capHeight() / 2.0f;
        float enAlpha = 0.0f;
        float enOffsetY = 12.0f;
        long enStart = slideStart + 500L + 300L;
        if (elapsed > enStart && elapsed <= enStart + 500L) {
            enAlpha = fadeFactor = IntroAnimation.easeOutCubic((float)(elapsed - enStart) / 500.0f);
            enOffsetY = (1.0f - fadeFactor) * 12.0f;
        } else if (elapsed > enStart + 500L) {
            enAlpha = 1.0f;
            enOffsetY = 0.0f;
        }
        fadeFactor = 1.0f;
        if (elapsed > fadeOutStart) {
            fadeFactor = 1.0f - IntroAnimation.clamp01((float)(elapsed - fadeOutStart) / 700.0f);
        }
        int zColor = new Color(1.0f, 1.0f, 1.0f, IntroAnimation.clamp01(zAlpha * fadeFactor)).getRGB();
        GlHelper.drawText("N", zRenderX, zRenderY, scaledZFont, zColor);
        if (enAlpha > 0.0f) {
            float enX = zRenderX + zWidth + 0.0f;
            float enY = centerY - baseFont.getMetrics().capHeight() / 2.0f + enOffsetY;
            int enColor = new Color(1.0f, 1.0f, 1.0f, IntroAnimation.clamp01(enAlpha * fadeFactor)).getRGB();
            GlHelper.drawText(NiloreClient.CLIENT_NAME_UPPER.substring(1), enX, enY, baseFont, enColor);
        }
    }

    private void finish() {
        if (!this.finished) {
            this.finished = true;
            try {
                NiloreClient.instance.getEventBus().unregister(this);
            } catch (Throwable throwable) {
                // empty catch block
            }
            isActive = false;
        }
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static float easeOutCubic(float t) {
        float clamped = IntroAnimation.clamp01(t);
        clamped = (float)(1.0 - Math.pow(1.0f - clamped, 3.0));
        return clamped;
    }

    private static float easeInCubic(float t) {
        float clamped = IntroAnimation.clamp01(t);
        clamped = clamped * clamped * clamped;
        return clamped;
    }
}
