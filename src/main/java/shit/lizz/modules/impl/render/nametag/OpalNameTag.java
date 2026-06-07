package shit.lizz.modules.impl.render.nametag;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import shit.lizz.LizzClient;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.impl.render.NameTags;
import shit.lizz.modules.impl.world.Teams;
import shit.lizz.render.FontPresets;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.Fonts;
import shit.lizz.render.Paint;
import shit.lizz.render.Renderer;
import shit.lizz.render.RoundedRectangle;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.game.ItemAlertTracker;
import shit.lizz.utils.math.MathUtil;
import shit.lizz.utils.math.Vector2f;
import shit.lizz.utils.render.ProjectionUtil;

public class OpalNameTag extends NameTagStyle {
    private static final int PADDING = new Color(0, 0, 0, 120).getRGB();
    private static final int COLOR_LIGHT_GRAY = Color.LIGHT_GRAY.getRGB();
    private static final int COLOR_WHITE = Color.WHITE.getRGB();
    private static final int COLOR_RED = new Color(255, 85, 85).getRGB();
    private static final int COLOR_GOLD = new Color(255, 215, 0).getRGB();
    private static final int COLOR_GREEN = new Color(85, 255, 85).getRGB();
    private static final int COLOR_AQUA = new Color(85, 255, 255).getRGB();

    public static final Map<String, AtomicInteger> scoreboardHealthMap = new ConcurrentHashMap<>();

    private final NumberSetting scaleSetting;
    private final NumberSetting distanceSetting;
    private final BooleanSetting showHealthSetting;
    private final BooleanSetting showArmorSetting;
    private final FontRenderer mainFont;
    private final FontRenderer nameFont;
    private final FontRenderer iconFont;
    private final Paint paint;
    private final Map<Entity, Vector2f> entityPositions;
    private final Map<UUID, Long> itemCheckTimestamps;
    private final Map<String, String> decodedNameCache;
    private final Map<String, Long> nameDecodeTimestamps;

    public OpalNameTag() {
        super("Opal");
        this.scaleSetting = NameTags.INSTANCE.scaleSetting;
        this.distanceSetting = NameTags.INSTANCE.distanceSetting;
        this.showHealthSetting = NameTags.INSTANCE.showHealthSetting;
        this.showArmorSetting = NameTags.INSTANCE.showArmorSetting;
        this.mainFont = FontPresets.pingfang(28.0f);
        this.nameFont = Fonts.getRenderer("AstaSans-Medium.ttf", 28.0f);
        this.iconFont = Fonts.getRenderer("MaterialIcons-Regular.ttf", 28.0f);
        this.paint = new Paint();
        this.entityPositions = new ConcurrentHashMap<>();
        this.itemCheckTimestamps = new HashMap<>();
        this.decodedNameCache = new HashMap<>();
        this.nameDecodeTimestamps = new HashMap<>();
    }

    @Override
    public String getName() {
        return "Opal";
    }

    @Override
    public void onEnable() {
        this.entityPositions.clear();
        ItemAlertTracker.clear();
    }

    @Override
    public void onDisable() {
        this.onEnable();
    }

    private void updatePositions(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            this.entityPositions.clear();
            ItemAlertTracker.clear();
            return;
        }
        ProjectionUtil.updateMatrices();
        double rangeSq = Math.pow(this.distanceSetting.getValue().doubleValue(), 2.0);
        float partial = event.partialTick();
        HashSet<Entity> seen = new HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;
            if (entity.isInvisible() && !this.showHealthSetting.getValue()) continue;
            if (entity.distanceToSqr(mc.player) > rangeSq) continue;
            if (!(entity instanceof Player)) continue;
            if (entity.getName().getString().startsWith("CIT-")) continue;
            if (NameTags.INSTANCE.showPingSetting.getValue() && Teams.isSameTeam(entity)) continue;
            double x = MathUtil.lerp(partial, entity.xo, entity.getX());
            double y = MathUtil.lerp(partial, entity.yo, entity.getY()) + entity.getBbHeight() + 0.5;
            double z = MathUtil.lerp(partial, entity.zo, entity.getZ());
            Vector2f screen = ProjectionUtil.project(x, y, z);
            if (screen == null) continue;
            screen.setY(screen.getY() - 2.0f);
            this.entityPositions.put(entity, screen);
            seen.add(entity);
        }
        this.entityPositions.keySet().removeIf(e -> !seen.contains(e));
        ItemAlertTracker.updateItems(seen);
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        try {
            this.updatePositions(renderEvent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (this.entityPositions.isEmpty() || mc.level == null) {
            return;
        }
        float scale = this.scaleSetting.getValue().floatValue();
        int padding = 4;
        int gap = 4;
        float corner = 6.0f;
        List<ItemRenderData> deferredItems = new ArrayList<>();
        Renderer.renderConsumer(ctx -> {
            float ascent = this.mainFont.getMetrics().ascent();
            float mainLine = this.mainFont.getMetrics().getLineHeight();
            float nameLine = this.nameFont.getMetrics().getLineHeight();
            for (Map.Entry<Entity, Vector2f> entry : this.entityPositions.entrySet()) {
                Entity entity = entry.getKey();
                Vector2f screenPos = entry.getValue();
                screenPos.set(Math.round(screenPos.x), Math.round(screenPos.y));
                if (!(entity instanceof AbstractClientPlayer player)) continue;
                if (this.showArmorSetting.getValue()) {
                    long now = System.currentTimeMillis();
                    UUID uuid = player.getUUID();
                    Long last = this.itemCheckTimestamps.get(uuid);
                    if (last == null || now - last >= 250L) {
                        ItemAlertTracker.trackPlayerItem(player, player.getMainHandItem());
                        ItemStack main = player.getMainHandItem();
                        if (ItemAlertTracker.isNewItem(main)) {
                            ItemAlertTracker.trackEntityItem(player, main);
                        }
                        ItemStack off = player.getOffhandItem();
                        if (ItemAlertTracker.isNewItem(off)) {
                            ItemAlertTracker.trackEntityItem(player, off);
                        }
                        this.itemCheckTimestamps.put(uuid, now);
                    }
                }
                Set<ItemStack> alertItems = ItemAlertTracker.getEntityItems(player);
                int alertCount = this.showArmorSetting.getValue() && !alertItems.isEmpty() ? alertItems.size() : 0;
                boolean hasAlerts = alertCount > 0;
                int distance = (int) mc.player.distanceTo(entity);
                String distanceIcon = "";
                String distanceText = distance + "m";
                String nameIcon = "";
                String displayName = entity.getDisplayName().getString();
                String teamSuffix = "";
                int health = Math.round(player.getHealth());
                String healthIcon = "\uE87D";
                String healthText = String.valueOf(health);
                int absorb = Math.round(player.getAbsorptionAmount());
                String absorbText = absorb > 0 ? String.valueOf(absorb) : "";
                float distIconW = this.iconFont.getBounds(distanceIcon).getWidth();
                float distTextW = this.nameFont.getBounds(distanceText).getWidth();
                float distBoxW = distIconW + 2.0f + distTextW + padding * 2;
                float nameIconW = this.iconFont.getBounds(nameIcon).getWidth();
                float displayW = this.mainFont.getBounds(displayName).getWidth();
                float teamW = teamSuffix.isEmpty() ? 0.0f : this.mainFont.getBounds(teamSuffix).getWidth();
                float nameBoxW = nameIconW + 6.0f + displayW + teamW + padding * 2;
                float healthIconW = this.iconFont.getBounds(healthIcon).getWidth();
                float healthTextW = this.nameFont.getBounds(healthText).getWidth();
                float healthBoxW = healthIconW + 2.0f + healthTextW + padding * 2;
                float absorbIconW = this.iconFont.getBounds(healthIcon).getWidth();
                float absorbTextW = absorbText.isEmpty() ? 0.0f : this.nameFont.getBounds(absorbText).getWidth();
                float absorbBoxW = absorbText.isEmpty() ? 0.0f : absorbIconW + 2.0f + absorbTextW + padding * 2;
                float rowHeight = Math.max(mainLine, nameLine) + padding * 2 - 4.0f;
                float itemBoxH = rowHeight;
                float itemTotalW = itemBoxH * alertCount + (alertCount > 0 ? gap * (alertCount - 1) : 0);
                int boxCount = 2;
                if (!healthText.isEmpty()) boxCount++;
                if (!absorbText.isEmpty()) boxCount++;
                boxCount += alertCount;
                float fullWidth = distBoxW + nameBoxW + healthBoxW + absorbBoxW + itemTotalW + gap * (boxCount > 1 ? boxCount - 1 : 0);
                float originX = -fullWidth / 2.0f;
                float originY = -rowHeight;
                ctx.save();
                ctx.translate(screenPos.x, screenPos.y);
                ctx.scale(scale, scale);
                float cursorX = originX;
                float textBaseline = originY + padding - 1.0f + ascent + 28.0f;
                this.paint.setColor(PADDING);
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cursorX, originY, distBoxW, rowHeight, corner), this.paint);
                this.paint.setColor(COLOR_LIGHT_GRAY);
                ctx.drawString(distanceIcon, cursorX + padding, textBaseline + 1.0f, this.iconFont, this.paint);
                ctx.drawString(distanceText, cursorX + padding + distIconW + 2.0f, textBaseline - 1.0f, this.nameFont, this.paint);
                cursorX += distBoxW + gap;
                this.paint.setColor(PADDING);
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cursorX, originY, nameBoxW + 2.0f, rowHeight, corner), this.paint);
                this.paint.setColor(COLOR_WHITE);
                ctx.drawString(nameIcon, cursorX + padding, textBaseline, this.iconFont, this.paint);
                ctx.drawString(displayName, cursorX + padding + nameIconW + 6.0f, textBaseline - 3.0f, this.mainFont, this.paint);
                float nameCursor = cursorX + padding + nameIconW + 6.0f + displayW;
                if (!teamSuffix.isEmpty()) {
                    this.paint.setColor(COLOR_GREEN);
                    ctx.drawString(teamSuffix, nameCursor, textBaseline - 1.0f, this.mainFont, this.paint);
                    nameCursor += teamW;
                }
                cursorX += nameBoxW + gap;
                this.paint.setColor(PADDING);
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cursorX, originY, healthBoxW, rowHeight, corner), this.paint);
                this.paint.setColor(COLOR_RED);
                ctx.drawString(healthIcon, cursorX + padding, textBaseline + 1.0f, this.iconFont, this.paint);
                this.paint.setColor(COLOR_WHITE);
                ctx.drawString(healthText, cursorX + padding + healthIconW + 2.5f, textBaseline - 1.0f, this.nameFont, this.paint);
                cursorX += healthBoxW;
                if (!absorbText.isEmpty()) {
                    cursorX += gap;
                    this.paint.setColor(PADDING);
                    ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cursorX, originY, absorbBoxW, rowHeight, corner), this.paint);
                    this.paint.setColor(COLOR_GOLD);
                    ctx.drawString(healthIcon, cursorX + padding, textBaseline + 1.0f, this.iconFont, this.paint);
                    this.paint.setColor(COLOR_WHITE);
                    ctx.drawString(absorbText, cursorX + padding + absorbIconW + 2.5f, textBaseline, this.nameFont, this.paint);
                    cursorX += absorbBoxW;
                }
                if (hasAlerts) {
                    for (ItemStack item : alertItems) {
                        if (ItemAlertTracker.hasItem(player.getUUID(), item.getItem())) continue;
                        cursorX += gap;
                        this.paint.setColor(PADDING);
                        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cursorX, originY, itemBoxH, rowHeight, corner), this.paint);
                        float itemX = screenPos.x + cursorX * scale;
                        float itemY = screenPos.y + originY * scale;
                        float itemSize = rowHeight * scale;
                        float centerX = itemX + itemSize / 2.0f;
                        float centerY = itemY + itemSize / 2.0f;
                        deferredItems.add(new ItemRenderData(item, new Vector2f(centerX - 5.0f, centerY - 5.0f)));
                        cursorX += itemBoxH;
                    }
                }
                ctx.restore();
            }
        });
        for (ItemRenderData data : deferredItems) {
            PoseStack stack = event.guiGraphics().pose();
            stack.pushPose();
            stack.translate(data.position.x, data.position.y, 0.0f);
            stack.scale(scale, scale, 1.0f);
            event.guiGraphics().renderItem(data.itemStack, 0, 0);
            event.guiGraphics().renderItemDecorations(mc.font, data.itemStack, 0, 0);
            stack.popPose();
        }
    }

    @Override
    public void onPacket(PacketEvent packetEvent) {
        if (!(packetEvent.getPacket() instanceof ClientboundSetScorePacket packet)) return;
        if (mc.level == null || mc.player == null) return;
        String objective = packet.getObjectiveName();
        if (!"belowHealth".equals(objective) && !"health".equals(objective)) return;
        if (packet.getOwner().equals(mc.player.getGameProfile().getName())) return;
        scoreboardHealthMap.computeIfAbsent(packet.getOwner(), k -> new AtomicInteger()).set(packet.getScore());
    }

    private String getDecodedName(String name) {
        long now = System.currentTimeMillis();
        Long last = this.nameDecodeTimestamps.get(name);
        if (last != null && now - last < 1000L) {
            return this.decodedNameCache.get(name);
        }
        this.decodedNameCache.put(name, name);
        this.nameDecodeTimestamps.put(name, now);
        return name;
    }

    private record ItemRenderData(ItemStack itemStack, Vector2f position) {
    }
}
