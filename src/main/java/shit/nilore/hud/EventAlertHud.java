package shit.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.render.Projectiles;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.Fonts;
import shit.nilore.render.GradientFactory;
import shit.nilore.render.Paint;
import shit.nilore.render.Path;
import shit.nilore.render.PathMeasure;

public class EventAlertHud
extends ClientBase
implements IHudElement {
    public record AlertEntry(Vec3 position, double distance, Optional<Float> timeRemaining, String title, String icon) {

        public Vec3 pos() {
            return position;
        }

            public String getFormattedDescription() {
                if (timeRemaining.isPresent()) {
                    return String.format("%.1fs · %.1fm", timeRemaining.get(), distance);
                }
                return String.format("%.1fm", distance);
            }
        }

    private static final FontRenderer titleFont;
    private static final FontRenderer subtitleFont;
    private static final FontRenderer iconFont;
    private static final FontRenderer timeFont;
    private static final Path iconPath;
    private static final float iconPathLength;
    private final Map<Vec3, Long> activeAlerts = new ConcurrentHashMap<>();
    private Vec3 lastAlertPos = null;
    private long lastAlertTime = 0L;

    private Optional<EventAlertHud.AlertEntry> findProjectileAlert() {
        if (mc == null || mc.player == null || Projectiles.projectileMap.isEmpty()) {
            return Optional.empty();
        }
        Optional<Projectiles.ProjectileEntry> nearest = Projectiles.projectileMap.entrySet().stream().filter(entry -> {
            if (mc.level == null) {
                return false;
            }
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive() || entity.onGround()) {
                return false;
            }
            if (!(entity instanceof ThrownEnderpearl)) {
                return false;
            }
            Entity owner = ((ThrownEnderpearl)entity).getOwner();
            return owner != null && !owner.equals(mc.player);
        }).map(java.util.Map.Entry::getValue).min(Comparator.comparingDouble(p -> p.getVelocity().distanceToSqr(mc.player.position())));
        return nearest.map(e -> new EventAlertHud.AlertEntry(e.getVelocity(), e.getZ(), Optional.of((float)e.getX()), "Find an ender pearl!", ""));
    }

    private Optional<EventAlertHud.AlertEntry> findEntityAlert() {
        if (mc == null || mc.player == null || mc.level == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false).filter(entity -> entity instanceof LightningBolt && entity.isAlive()).forEach(entity -> this.activeAlerts.put(entity.position(), now));
        this.activeAlerts.entrySet().removeIf(entry -> now - entry.getValue() > 5000L);
        if (this.activeAlerts.isEmpty()) {
            return Optional.empty();
        }
        return this.activeAlerts.keySet().stream().filter(v -> mc.player.position().distanceToSqr(v) < 65536.0).min(Comparator.comparingDouble(v -> mc.player.position().distanceToSqr(v))).map(v -> new EventAlertHud.AlertEntry(v, mc.player.position().distanceTo(v), Optional.empty(), "Found a lightning strike!", ""));
    }

    private Optional<EventAlertHud.AlertEntry> findBestAlert() {
        return java.util.stream.Stream.of(this.findProjectileAlert(), this.findEntityAlert()).filter(Optional::isPresent).map(Optional::get).min(Comparator.comparingDouble(a -> a.distance));
    }

    @Override
    public boolean isVisible() {
        return this.findBestAlert().isPresent();
    }

    @Override
    public IHudElement.Size getHudAlignment() {
        return new IHudElement.Size(200.0f, 40.0f);
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
        if (mc == null || mc.player == null || alpha <= 0.01f) {
            return;
        }
        this.findBestAlert().ifPresent(alert -> {
            float padding = 12.0f;
            float iconX = x + padding;
            float centerY = y + height / 2.0f;
            boolean hasPathIcon = "".equals(alert.icon());
            if (hasPathIcon) {
                if (this.lastAlertPos == null || !this.lastAlertPos.equals(alert.pos())) {
                    this.lastAlertPos = alert.pos();
                    this.lastAlertTime = System.currentTimeMillis();
                }
            } else {
                this.lastAlertPos = null;
            }
            try (Paint paint = new Paint()){
                Paint.LinearGradient gradient;
                float descY;
                paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
                float iconWidth = titleFont.getWidth(alert.icon());
                float iconY = centerY - (titleFont.getMetrics().ascent() + titleFont.getMetrics().descent()) / 2.0f - titleFont.getMetrics().descent();
                if (hasPathIcon && iconPath != null) {
                    long elapsed = System.currentTimeMillis() - this.lastAlertTime;
                    float drawProgress = Mth.clamp((float)elapsed / 1000.0f, 0.0f, 1.0f);
                    paint.setStrokeCap(Paint.StrokeCap.STROKE);
                    paint.setStrokeWidth(1.5f);
                    paint.setStrokeJoin(Paint.StrokeJoin.ROUND);
                    if (drawProgress < 1.0f) {
                        descY = iconPathLength;
                        float drawLen = drawProgress * descY;
                        gradient = GradientFactory.buildLinearGradient(new float[]{drawLen, descY}, 0.0f);
                        try {
                            paint.setLinGradient(gradient);
                        } finally {
                            if (gradient != null) {
                                gradient.close();
                            }
                        }
                    } else {
                        paint.setLinGradient(null);
                    }
                    drawContext.save();
                    drawContext.translate(iconX, iconY);
                    drawContext.drawPath(iconPath, paint);
                    drawContext.restore();
                    paint.setStrokeCap(Paint.StrokeCap.FILL);
                    paint.setLinGradient(null);
                } else {
                    drawContext.drawString(alert.icon(), iconX, iconY, titleFont, paint);
                }
                float textX = iconX + iconWidth + padding;
                paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
                float titleY = centerY - iconFont.getMetrics().getLineHeight() / 2.0f + 2.0f;
                drawContext.drawString(alert.title(), textX, titleY, iconFont, paint);
                String desc = alert.getFormattedDescription();
                paint.setColor(this.colorWithAlpha(new Color(170, 170, 170).getRGB(), alpha));
                descY = centerY + timeFont.getMetrics().getLineHeight() / 2.0f + 6.0f;
                drawContext.drawString(desc, textX, descY, timeFont, paint);
                paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
                Vec3 eyePos = mc.player.getEyePosition();
                Vec3 alertPos = alert.pos();
                double dx = alertPos.x - eyePos.x;
                double dz = alertPos.z - eyePos.z;
                if (Math.sqrt(dx * dx + dz * dz) < 1.0) {
                    String arrow = alertPos.y > eyePos.y ? "" : "";
                    float arrowWidth = subtitleFont.getWidth(arrow);
                    float arrowX = x + width - padding - arrowWidth;
                    float arrowY = centerY - (subtitleFont.getMetrics().ascent() + subtitleFont.getMetrics().descent()) / 2.0f - subtitleFont.getMetrics().descent();
                    drawContext.drawString(arrow, arrowX, arrowY, subtitleFont, paint);
                } else {
                    float arrowWidth = subtitleFont.getWidth("");
                    float arrowX = x + width - padding - arrowWidth;
                    float arrowY = centerY;
                    drawContext.save();
                    drawContext.translate(arrowX + arrowWidth / 2.0f, arrowY);
                    float targetAngle = (float)(Mth.atan2(dz, dx) * 57.29577951308232) - 90.0f;
                    float playerYaw = Mth.wrapDegrees(mc.player.getYRot());
                    float rotation = Mth.wrapDegrees(targetAngle - playerYaw);
                    drawContext.rotate(rotation);
                    float ascent = subtitleFont.getMetrics().ascent();
                    float descent = subtitleFont.getMetrics().descent();
                    float baseOffsetY = -(ascent + descent) / 2.0f;
                    drawContext.drawString("", -arrowWidth / 2.0f, baseOffsetY, subtitleFont, paint);
                    drawContext.restore();
                }
            }
        });
    }

    static {
        titleFont = Fonts.getRenderer("MaterialIcons-Regular.ttf", 48.0f);
        subtitleFont = Fonts.getRenderer("MaterialIcons-Regular.ttf", 44.0f);
        iconFont = FontPresets.poppinsBold(8.0f);
        timeFont = FontPresets.poppinsMedium(6.0f);
        Path path = null;
        float length = 0.0f;
        try {
            short[] glyphCodes = titleFont.getGlyphCodes("");
            if (glyphCodes != null && glyphCodes.length > 0) {
                path = titleFont.getGlyphPath(glyphCodes[0]);
                if (path != null) {
                    try (PathMeasure pathMeasure = new PathMeasure(path)) {
                        length = pathMeasure.getLength();
                    }
                }
            }
        } catch (Exception exception) {
            path = null;
        }
        iconPath = path;
        iconPathLength = length;
    }
}
