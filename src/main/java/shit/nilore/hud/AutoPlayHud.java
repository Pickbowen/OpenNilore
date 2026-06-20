package shit.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.world.AutoPlay;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.Paint;
import shit.nilore.render.Path;

public class AutoPlayHud
extends ClientBase
implements IHudElement {
    private static final FontRenderer font = FontPresets.poppinsRegular(24.0f);
    private float animProgress = 0.0f;
    private long lastUpdateTime = -1L;
    private long disableTime = -1L;

    @Override
    public boolean isVisible() {
        if (AutoPlay.instance == null || !AutoPlay.instance.isEnabled()) {
            return false;
        }
        if (AutoPlay.instance.pendingDisconnect) {
            return true;
        }
        long disconnectTime = AutoPlay.instance.disconnectTime;
        if (disconnectTime <= 0L) {
            return false;
        }
        double delayMs = AutoPlay.instance.getDelay().getValue().doubleValue() * 1000.0;
        long elapsed = System.currentTimeMillis() - disconnectTime;
        if ((double)elapsed >= delayMs) {
            long afterDoneMs = elapsed - (long)delayMs;
            return afterDoneMs < 500L;
        }
        return false;
    }

    @Override
    public IHudElement.Size getHudAlignment() {
        if (!this.isVisible()) {
            return new IHudElement.Size(0.0f, 40.0f);
        }
        long disconnectTime = AutoPlay.instance.disconnectTime;
        double delayMs = AutoPlay.instance.getDelay().getValue().doubleValue() * 1000.0;
        long elapsed = System.currentTimeMillis() - disconnectTime;
        boolean done = delayMs <= 0.0 || (double)elapsed >= delayMs;
        long afterDoneMs = done ? elapsed - (long)delayMs : -1L;
        String text = done ? "Done!" : "Sending you to next game...";
        float textWidth = font.getBounds(text).getWidth();
        float iconSize = 28.0f;
        float totalWidth = 18.0f + iconSize + 8.0f + textWidth + 18.0f;
        float padding = 30.0f;
        float width = totalWidth - 30.0f;
        float minWidth = 60.0f;
        width = Math.max(width, minWidth);
        float height = 40.0f;
        if (done) {
            float t = Mth.clamp((float)afterDoneMs / 400.0f, 0.0f, 1.0f);
            height = Mth.lerp(t, 40.0f, 25.0f);
        }
        return new IHudElement.Size(width, height);
    }

    @Override
    public IHudElement.Alignment getHudSize() {
        return IHudElement.Alignment.CENTER;
    }

    @Override
    public boolean hasBackground() {
        return true;
    }

    @Override
    public void renderGui(GuiGraphics guiGraphics, PoseStack poseStack, float x, float y, float width, float height, float alpha) {
    }

    @Override
    public void render(DrawContext drawContext, float x, float y, float width, float height, float alpha) {
        float checkEndY;
        float checkEndX;
        float checkMidY;
        float checkMidX;
        float arcBottom;
        float arcProgress;
        long afterDoneMs = 0L;
        if (mc == null || mc.player == null || alpha <= 0.01f || AutoPlay.instance == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!AutoPlay.instance.pendingDisconnect) {
            if (this.disableTime == -1L) {
                this.disableTime = now;
            }
        } else {
            this.disableTime = -1L;
        }
        if (this.lastUpdateTime == -1L) {
            this.lastUpdateTime = now;
        }
        long deltaTime = now - this.lastUpdateTime;
        this.lastUpdateTime = now;
        long disconnectTime = AutoPlay.instance.disconnectTime;
        double delaySec = AutoPlay.instance.getDelay().getValue().doubleValue();
        double delayMs = delaySec * 1000.0;
        long elapsed = System.currentTimeMillis() - disconnectTime;
        float targetProgress = delayMs > 0.0 ? (float)((double)elapsed / delayMs) : 1.0f;
        targetProgress = Mth.clamp(targetProgress, 0.0f, 1.0f);
        float lerpT = Mth.clamp((float)deltaTime / 200.0f, 0.0f, 1.0f);
        this.animProgress = Mth.lerp(lerpT, this.animProgress, targetProgress);
        if (Math.abs(this.animProgress - targetProgress) < 0.01f) {
            this.animProgress = targetProgress;
        }
        boolean done = false;
        if (disconnectTime > 0L) {
            long elapsed2 = System.currentTimeMillis() - disconnectTime;
            if (delayMs <= 0.0 || (double)elapsed2 >= delayMs) {
                done = true;
                afterDoneMs = delayMs > 0.0 ? elapsed2 - (long)delayMs : elapsed2;
            }
        }
        float centerY = y + height / 2.0f;
        float iconSize = height - 12.0f;
        float iconX = x + 18.0f;
        float iconY = y + 6.0f;
        float iconCx = iconX + iconSize / 2.0f;
        float iconCy = iconY + iconSize / 2.0f;
        float iconRadius = iconSize / 2.0f - 2.0f;
        try (Paint paint = new Paint();
             Path path = new Path()){
            float arcLeft;
            paint.setStrokeWidth(2.0f);
            paint.setStrokeCap(Paint.StrokeCap.STROKE);
            paint.setStrokeJoin(Paint.StrokeJoin.ROUND);
            paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
            if (this.animProgress > 0.001f) {
                arcProgress = 360.0f * this.animProgress;
                arcLeft = iconCx - iconRadius;
                arcBottom = iconCy - iconRadius;
                checkMidX = iconCx + iconRadius;
                checkMidY = iconCy + iconRadius;
                drawContext.drawArc(arcLeft, arcBottom, checkMidX, checkMidY, -90.0f, arcProgress, false, paint);
            }
            if (done) {
                path.reset();
                arcProgress = Mth.clamp((float)afterDoneMs / 400.0f, 0.0f, 1.0f);
                arcLeft = iconCx - iconRadius * 0.4f;
                arcBottom = iconCy;
                checkMidX = iconCx - iconRadius * 0.15f;
                checkMidY = iconCy + iconRadius * 0.3f;
                checkEndX = iconCx + iconRadius * 0.4f;
                checkEndY = iconCy - iconRadius * 0.3f;
                float seg1Len = (float)Math.hypot(checkMidX - arcLeft, checkMidY - arcBottom);
                float seg2Len = (float)Math.hypot(checkEndX - checkMidX, checkEndY - checkMidY);
                float totalLen = seg1Len + seg2Len;
                float drawLen = totalLen * arcProgress;
                path.moveTo(arcLeft, arcBottom);
                if (drawLen <= seg1Len) {
                    float seg1T = drawLen / seg1Len;
                    path.lineTo(Mth.lerp(seg1T, arcLeft, checkMidX), Mth.lerp(seg1T, arcBottom, checkMidY));
                } else {
                    path.lineTo(checkMidX, checkMidY);
                    float seg2T = (drawLen - seg1Len) / seg2Len;
                    path.lineTo(Mth.lerp(seg2T, checkMidX, checkEndX), Mth.lerp(seg2T, checkMidY, checkEndY));
                }
                drawContext.drawPath(path, paint);
            }
        }
        String statusText = done ? "Done!" : "Sending you to next game...";
        float textIconSize = iconSize;
        arcProgress = iconX + textIconSize + 8.0f;
        try (Paint paint = new Paint()){
            paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
            arcBottom = centerY - font.getMetrics().capHeight() / 2.0f + 8.0f;
            drawContext.drawString(statusText, arcProgress, arcBottom, font, paint);
            checkMidX = 2.5f;
            checkMidY = arcBottom + font.getMetrics().descent() + 8.0f;
            checkEndX = x + width - arcProgress - 18.0f;
            paint.setStrokeCap(Paint.StrokeCap.FILL);
            paint.setColor(this.colorWithAlpha(new Color(0, 0, 0, 40).getRGB(), alpha));
            if (this.animProgress > 0.0f) {
                paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
                checkEndY = checkEndX * this.animProgress;
            }
        }
    }
}
