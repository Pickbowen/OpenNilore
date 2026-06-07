package shit.lizz.modules.impl.render.nametag;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.impl.render.NameTags;
import shit.lizz.modules.impl.world.Teams;
import shit.lizz.render.FontPresets;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.Paint;
import shit.lizz.render.Renderer;
import shit.lizz.render.RoundedRectangle;
import shit.lizz.utils.game.ItemAlertTracker;
import shit.lizz.utils.math.MathUtil;
import shit.lizz.utils.math.Vector2f;
import shit.lizz.utils.render.ProjectionUtil;
import shit.lizz.utils.render.RenderUtil;

public class SimpleNameTag extends NameTagStyle {
    private final Map<Entity, Vector2f> entityPositions = new ConcurrentHashMap<>();
    private final List<BlurRect> blurRects = new ArrayList<>();
    private final FontRenderer font = FontPresets.pingfang(16.0f);
    private Paint paint;

    public SimpleNameTag() {
        super("Simple");
    }

    @Override
    public String getName() {
        return "Simple";
    }

    @Override
    public void onEnable() {
        this.entityPositions.clear();
        ItemAlertTracker.clear();
    }

    @Override
    public void onDisable() {
        this.entityPositions.clear();
        ItemAlertTracker.clear();
    }

    private int getHealthColor(float ratio) {
        ratio = Math.max(0.0f, Math.min(1.0f, ratio));
        if (ratio >= 0.6f) {
            float t = (ratio - 0.6f) / 0.4f;
            return new Color((int) (255.0f * (1.0f - t)), 255, 0).getRGB();
        }
        if (ratio >= 0.3f) {
            float t = (ratio - 0.3f) / 0.3f;
            return new Color(255, (int) (255.0f * t), 0).getRGB();
        }
        float t = ratio / 0.3f;
        return new Color((int) (128.0f + 127.0f * t), 0, 0).getRGB();
    }

    private void updatePositions(float partialTick) {
        this.entityPositions.clear();
        if (mc.level == null || mc.player == null) {
            ItemAlertTracker.clear();
            return;
        }
        double rangeSq = Math.pow(NameTags.INSTANCE.distanceSetting.getValue().doubleValue(), 2.0);
        HashSet<Entity> seen = new HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (entity.distanceToSqr(mc.player) > rangeSq) continue;
            if (!(entity instanceof Player)) continue;
            if (entity.getName().getString().startsWith("CIT-")) continue;
            if (entity.isInvisible() && !NameTags.INSTANCE.showHealthSetting.getValue()) continue;
            if (NameTags.INSTANCE.showPingSetting.getValue() && Teams.isSameTeam(entity)) continue;
            double x = MathUtil.lerp(partialTick, entity.xo, entity.getX());
            double y = MathUtil.lerp(partialTick, entity.yo, entity.getY()) + entity.getBbHeight() + 0.5;
            double z = MathUtil.lerp(partialTick, entity.zo, entity.getZ());
            Vector2f screen = ProjectionUtil.project(x, y, z, partialTick);
            if (screen == null) continue;
            screen.setY(screen.getY() - 2.0f);
            this.entityPositions.put(entity, screen);
            seen.add(entity);
        }
        ItemAlertTracker.updateItems(seen);
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        try {
            this.updatePositions(renderEvent.partialTick());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (this.entityPositions.isEmpty() || mc.level == null || this.font == null) {
            return;
        }
        this.blurRects.clear();
        float scale = NameTags.INSTANCE.scaleSetting.getValue().floatValue();
        float corner = 6.0f;
        int padding = 4;
        int gap = 4;
        Map<ItemStack, Vector2f> itemPositions = new ConcurrentHashMap<>();
        Renderer.renderConsumer(ctx -> {
            for (Map.Entry<Entity, Vector2f> entry : this.entityPositions.entrySet()) {
                Entity entity = entry.getKey();
                Vector2f screenPos = entry.getValue();
                if (!(entity instanceof Player player)) continue;
                screenPos.set(Math.round(screenPos.x), Math.round(screenPos.y));
                if (NameTags.INSTANCE.showArmorSetting.getValue()) {
                    ItemStack main = player.getMainHandItem();
                    if (ItemAlertTracker.isNewItem(main)) {
                        ItemAlertTracker.trackEntityItem(player, main);
                    }
                    ItemStack off = player.getOffhandItem();
                    if (ItemAlertTracker.isNewItem(off)) {
                        ItemAlertTracker.trackEntityItem(player, off);
                    }
                }
                float health = Math.min(player.getHealth(), 20.0f);
                float ratio = health / player.getMaxHealth();
                String nameText = player.getName().getString() + " | ";
                String healthText = Math.round(health) + "";
                if (player.getAbsorptionAmount() > 0.0f) {
                    healthText = healthText + "+" + Math.round(player.getAbsorptionAmount());
                }
                healthText += "HP";
                Set<ItemStack> alertItems = ItemAlertTracker.getEntityItems(player);
                int alertCount = NameTags.INSTANCE.showArmorSetting.getValue() && !alertItems.isEmpty() ? alertItems.size() : 0;
                boolean hasAlerts = alertCount > 0;
                String combined = nameText + healthText;
                float textWidth = this.font.getBounds(combined).getWidth();
                float lineHeight = this.font.getMetrics().getLineHeight();
                float itemBoxSize = lineHeight + padding * 2;
                float alertWidth = itemBoxSize * alertCount + (alertCount > 0 ? gap * alertCount : 0);
                float boxWidth = textWidth + padding * 2 + alertWidth;
                float boxHeight = lineHeight + padding * 2;
                float originX = -boxWidth / 2.0f;
                float originY = -boxHeight;
                ctx.save();
                ctx.translate(screenPos.x, screenPos.y);
                ctx.scale(scale, scale);
                float blurX = screenPos.x + originX * scale;
                float blurY = screenPos.y + originY * scale;
                float blurW = boxWidth * scale;
                float blurH = boxHeight * scale;
                this.blurRects.add(new BlurRect(blurX, blurY, blurW, blurH));
                if (this.paint == null) {
                    this.paint = new Paint();
                }
                this.paint.setColor(new Color(0, 0, 0, 80).getRGB());
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(originX, originY, boxWidth, boxHeight, corner), this.paint);
                if (ratio > 0.0f) {
                    this.paint.setColor(new Color(0, 0, 0, 80).getRGB());
                    ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(originX, originY, boxWidth * ratio, boxHeight, corner), this.paint);
                }
                int healthColor = this.getHealthColor(ratio);
                float baseline = originY + padding + this.font.getMetrics().ascent() + 35.0f;
                this.paint.setColor(Color.WHITE.getRGB());
                ctx.drawString(nameText, originX + padding, baseline, this.font, this.paint);
                float nameW = this.font.getBounds(nameText).getWidth();
                this.paint.setColor(healthColor);
                ctx.drawString(healthText, originX + padding + nameW, baseline, this.font, this.paint);
                if (hasAlerts) {
                    float itemCursorX = originX + textWidth + padding * 2 + gap;
                    for (ItemStack item : alertItems) {
                        this.paint.setColor(new Color(0, 0, 0, 120).getRGB());
                        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(itemCursorX, originY, itemBoxSize, boxHeight, corner), this.paint);
                        float itemX = screenPos.x + itemCursorX * scale;
                        float itemY = screenPos.y + originY * scale;
                        float itemDrawSize = itemBoxSize * scale;
                        float centerX = itemX + itemDrawSize / 2.0f;
                        float centerY = itemY + itemDrawSize / 2.0f;
                        itemPositions.put(item, new Vector2f(centerX - 8.0f, centerY - 8.0f));
                        itemCursorX += itemBoxSize + gap;
                    }
                }
                ctx.restore();
            }
        });
        for (BlurRect rect : this.blurRects) {
            RenderUtil.drawBlurredRect(event.guiGraphics().pose(), rect.x, rect.y, rect.width, rect.height, 8.0f, 2.0f, 0.6f, 0);
        }
        for (Map.Entry<ItemStack, Vector2f> entry : itemPositions.entrySet()) {
            ItemStack item = entry.getKey();
            Vector2f pos = entry.getValue();
            event.guiGraphics().renderItem(item, Math.round(pos.x), Math.round(pos.y));
            event.guiGraphics().renderItemDecorations(mc.font, item, Math.round(pos.x), Math.round(pos.y));
        }
    }

    @Override
    public void onPacket(PacketEvent packetEvent) {
    }

    private record BlurRect(float x, float y, float width, float height) {
    }
}
