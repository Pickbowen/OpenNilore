package shit.nilore.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import shit.nilore.modules.impl.render.NameProtect;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.game.ItemUtil;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.event.EventTarget;

public class PlayerListHud
extends HudElement {
    public static final class PlayerEntry {
        public final PlayerListHud outer;
        public final net.minecraft.world.entity.player.Player player;
        public String displayName;
        public float nameWidth;
        public java.util.List<ItemStack> items;
        public float totalWidth;
        public final java.util.Map<net.minecraft.world.item.Item, Integer> cheatItems = new java.util.HashMap<>();
        public final java.util.Set<net.minecraft.world.item.Item> flaggedItems = new java.util.HashSet<>();
        public final java.util.Map<net.minecraft.world.item.Item, ItemStack> itemStacks = new java.util.HashMap<>();
        public final long createdTime;
        public final SmoothAnimationTimer slideAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer fadeAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer heightAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer alphaAnim = new SmoothAnimationTimer();
        public final SmoothAnimationTimer widthAnim = new SmoothAnimationTimer();
        public boolean removing = false;
        public boolean visible = true;
        public final boolean rightAligned;

        public PlayerEntry(PlayerListHud outer, net.minecraft.world.entity.player.Player player, java.util.List<ItemStack> initialItems) {
            this.outer = outer;
            this.player = player;
            this.rightAligned = outer.wasRightAligned;
            this.createdTime = System.currentTimeMillis();
            initialItems.forEach(this::addItemStack);
            ArrayList<ItemStack> initialList = new ArrayList<>();
            for (ItemStack stack : this.itemStacks.values()) {
                if (ItemUtil.isOtherCheat(stack)) {
                    this.cheatItems.put(stack.getItem(), stack.getDamageValue());
                }
                initialList.add(stack);
            }
            this.updateItems(initialList);
            this.slideAnim.setCurrentValue(this.rightAligned ? 20.0 : -20.0);
            this.heightAnim.setCurrentValue(0.0);
            this.alphaAnim.setCurrentValue(0.0);
            this.widthAnim.setCurrentValue(this.totalWidth);
        }

        public void updateItems(java.util.List<ItemStack> items) {
            this.items = items;
            this.displayName = NameProtect.replacePlayerName(this.player.getName().getString());
            this.nameWidth = GlHelper.getStringWidth(this.displayName, this.outer.headerFont);
            float padding = 5.0f;
            float gap = 3.0f;
            float headSize = 20.0f;
            float itemSize = 16.0f;
            this.totalWidth = padding + headSize + gap + this.nameWidth + gap + items.size() * (itemSize + gap) + padding;
            this.widthAnim.animate(this.totalWidth, 0.25, Easings.EASE_OUT_SINE);
        }

        public void startRemove() {
            if (this.removing) return;
            this.removing = true;
            float dist = 40.0f;
            this.slideAnim.animate(this.rightAligned ? dist : -dist, 0.2, Easings.EASE_IN_POW3);
            this.heightAnim.animate(0.0, 0.2, Easings.EASE_OUT_POW3);
        }

        public void tick() {
            this.slideAnim.tick();
            this.fadeAnim.tick();
            this.heightAnim.tick();
            this.alphaAnim.tick();
            this.widthAnim.tick();
        }

        public boolean isRemoveDone() {
            return this.removing && this.heightAnim.isDone();
        }

        private void addItemStack(ItemStack stack) {
            this.itemStacks.putIfAbsent(stack.getItem(), stack);
        }
    }

    private final List<PlayerListHud.PlayerEntry> playerEntryList = new ArrayList<>();
    private final FontRenderer nameFont = FontPresets.poppinsMedium(15.0f);
    final FontRenderer headerFont = FontPresets.pingfang(15.0f);
    private final FontRenderer subFont = FontPresets.materialIcons(18.0f);
    private final SmoothAnimationTimer slideAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer fadeAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer rightAlignAnim = new SmoothAnimationTimer();
    boolean wasRightAligned = false;
    private boolean rightAlign = false;
    private final Set<UUID> playerEntries = new HashSet<>();
    private final Set<UUID> removedEntries = new HashSet<>();

    public PlayerListHud() {
        super("PlayerList");
        this.setWidth(150.0f);
        this.setHeight(100.0f);
        this.setEnabled(true);
        this.fadeAnim.setCurrentValue(1.0);
        this.rightAlignAnim.setCurrentValue(1.0);
    }

    @Override
    public void onEnable() {
        if (this.playerEntryList != null) {
            this.playerEntryList.clear();
        }
        if (this.removedEntries != null) {
            this.removedEntries.clear();
        }
    }

    private boolean isCheatItem(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        return ItemUtil.isOtherCheat(itemStack) || ItemUtil.isWeaponItem(itemStack) || ItemUtil.isEnchantedGoldenApple(itemStack) || ItemUtil.isEndCrystal(itemStack) || ItemUtil.isKBSlimeBall(itemStack) || ItemUtil.isKBStick(itemStack) || ItemUtil.getPunchLevel(itemStack) > 2 && itemStack.getItem() instanceof BowItem || ItemUtil.getPowerLevel(itemStack) > 3 && itemStack.getItem() instanceof BowItem;
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            if (this.playerEntryList != null && !this.playerEntryList.isEmpty()) {
                this.playerEntryList.clear();
            }
            if (this.removedEntries != null && !this.removedEntries.isEmpty()) {
                this.removedEntries.clear();
            }
            return;
        }
        List<? extends Player> levelPlayers = mc.level.players();
        ArrayList<Player> playerList = new ArrayList<>(levelPlayers);
        for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
            if (!playerList.stream().noneMatch(p -> p.getUUID().equals(entry.player.getUUID()))) continue;
            entry.startRemove();
        }
        for (Player currentPlayer : playerList) {
            List<ItemStack> playerItems;
            boolean alreadyTracked = this.playerEntryList.stream().anyMatch(e -> e.player.getUUID().equals(currentPlayer.getUUID()));
            if (alreadyTracked || (playerItems = this.getPlayerItems(currentPlayer)).isEmpty()) continue;
            this.playerEntryList.add(0, new PlayerListHud.PlayerEntry(this, currentPlayer, playerItems));
        }
        for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
            playerList.stream().filter(p -> p.getUUID().equals(entry.player.getUUID())).findFirst().ifPresent(p -> {
                if (p == mc.player) {
                    entry.updateItems(this.getPlayerItems(p));
                } else {
                    this.getPlayerItems(p).forEach(itemStack -> entry.itemStacks.putIfAbsent(itemStack.getItem(), itemStack));
                    ItemStack handItem = p.getMainHandItem();
                    if (ItemUtil.isOtherCheat(handItem)) {
                        int dmg = handItem.getDamageValue();
                        Integer prev = entry.cheatItems.get(handItem.getItem());
                        if (prev != null && dmg > prev && !entry.flaggedItems.contains(handItem.getItem())) {
                            ChatUtil.print(String.format("§c[ALERT] §f%s used a God Axe!", p.getName().getString()));
                            entry.flaggedItems.add(handItem.getItem());
                        }
                        entry.cheatItems.put(handItem.getItem(), dmg);
                    }
                    ArrayList<ItemStack> filtered = new ArrayList<>();
                    for (ItemStack itemStack : entry.itemStacks.values()) {
                        if (entry.flaggedItems.contains(itemStack.getItem())) continue;
                        filtered.add(itemStack);
                    }
                    entry.updateItems(filtered);
                }
            });
        }
        this.removedEntries.removeIf(uuid -> {
            Player player = mc.level.getPlayerByUUID(uuid);
            if (player == null) {
                return true;
            }
            MobEffectInstance absorption = player.getEffect(MobEffects.ABSORPTION);
            return absorption == null || absorption.getAmplifier() < 3;
        });
    }

    private List<ItemStack> getPlayerItems(Player player) {
        Stream<ItemStack> stream = player == mc.player
                ? Stream.concat(player.getInventory().items.stream(), Stream.concat(player.getInventory().armor.stream(), player.getInventory().offhand.stream()))
                : Stream.concat(player.getInventory().armor.stream(), Stream.of(player.getMainHandItem(), player.getOffhandItem()));
        return stream.filter(this::isCheatItem).collect(Collectors.toList());
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        MobEffectInstance absorption;
        Player player;
        Entity entity;
        ClientboundEntityEventPacket entityEventPacket;
        if (mc.level == null) {
            return;
        }
        Packet<?> packet = packetEvent.getPacket();
        if (packet instanceof ClientboundEntityEventPacket && (entityEventPacket = (ClientboundEntityEventPacket)packet).getEventId() == 35 && (entity = entityEventPacket.getEntity(mc.level)) instanceof Player) {
            player = (Player)entity;
            ChatUtil.print("§e[提示] §f" + player.getName().getString() + " 触发了不死图腾!");
            this.updatePlayerItem(player, Items.TOTEM_OF_UNDYING);
        }
        if (packet instanceof ClientboundSetEntityDataPacket dataPacket
                && (entity = mc.level.getEntity(dataPacket.id())) instanceof Player
                && (absorption = (player = (Player)entity).getEffect(MobEffects.ABSORPTION)) != null
                && absorption.getAmplifier() >= 3
                && player.hasEffect(MobEffects.REGENERATION)
                && !this.removedEntries.contains(player.getUUID())) {
            ChatUtil.print("§6[提示] §f" + player.getName().getString() + " 吃下了附魔金苹果!");
            this.updatePlayerItem(player, Items.ENCHANTED_GOLDEN_APPLE);
            this.removedEntries.add(player.getUUID());
        }
    }

    private void updatePlayerItem(Player player, Item item) {
        this.playerEntryList.stream().filter(entry -> entry.player.getUUID().equals(player.getUUID())).findFirst().ifPresent(entry -> {
            entry.flaggedItems.add(item);
            entry.itemStacks.remove(item);
        });
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
        float rightFade;
        float leftFade;
        float entryY;
        float panelWidth;
        boolean isRightAligned;
        this.playerEntryList.removeIf(PlayerListHud.PlayerEntry::isRemoveDone);
        this.playerEntryList.forEach(PlayerListHud.PlayerEntry::tick);
        this.slideAnim.tick();
        this.fadeAnim.tick();
        this.rightAlignAnim.tick();
        if (this.playerEntryList.isEmpty() && this.slideAnim.isDone() && this.fadeAnim.isDone()) {
            return;
        }
        boolean rightSide = isRightAligned = x + this.getWidth() / 2.0f > (float)mc.getWindow().getGuiScaledWidth() / 2.0f;
        if (this.wasRightAligned != isRightAligned && this.rightAlignAnim.isDone()) {
            this.rightAlign = this.wasRightAligned;
            this.wasRightAligned = isRightAligned;
            this.rightAlignAnim.setCurrentValue(0.0);
            this.rightAlignAnim.animate(1.0, 0.3, Easings.EASE_OUT_SINE);
        }
        float padding = 5.0f;
        float gap = 3.0f;
        float spacing = 4.5f;
        float cornerRadius = 15.0f;
        float blurStrength = 0.7f;
        String iconText = "\ue7fd";
        String titleText = "Playerlist";
        float iconWidth = GlHelper.getStringWidth(iconText, this.subFont);
        float titleWidth = GlHelper.getStringWidth(titleText, this.nameFont);
        float headerHeight = (float)GlHelper.getFontAscent(this.nameFont) + padding * 2.0f;
        float headerWidth = iconWidth + gap + titleWidth + padding * 2.0f;
        float headSize = 20.0f;
        float itemSize = 16.0f;
        float entryHeight = headSize + padding * 2.0f;
        float targetWidth = headerWidth;
        if (!this.playerEntryList.isEmpty()) {
            panelWidth = 0.0f;
            for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
                float itemsWidth;
                entryY = GlHelper.getStringWidth(entry.displayName, this.headerFont);
                float rowWidth = headSize + gap + entryY + gap + (itemsWidth = (float)entry.items.size() * (itemSize + gap)) + padding * 2.0f;
                if (!(rowWidth > panelWidth)) continue;
                panelWidth = rowWidth;
            }
            targetWidth = Math.max(headerWidth, panelWidth);
        }
        if (this.slideAnim.isDone() && this.playerEntryList.isEmpty()) {
            this.slideAnim.setCurrentValue(targetWidth);
        }
        this.slideAnim.animate(targetWidth, 0.2, Easings.EASE_OUT_SINE);
        if (this.playerEntryList.isEmpty()) {
            this.fadeAnim.animate(0.0, 0.2, Easings.EASE_IN_POW3);
        } else {
            this.fadeAnim.animate(1.0, 0.2, Easings.EASE_OUT_POW3);
        }
        panelWidth = this.slideAnim.getValueF();
        float drawX = x;
        if (this.wasRightAligned) {
            drawX = x + this.getWidth() - panelWidth;
        }
        float finalX = drawX;
        entryY = y + headerHeight + gap;
        for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
            if (entry.visible) {
                entry.visible = false;
                entry.fadeAnim.setCurrentValue(entryY);
                entry.slideAnim.setCurrentValue(entry.rightAligned ? 20.0 : -20.0);
                entry.heightAnim.setCurrentValue(0.0);
                entry.alphaAnim.setCurrentValue(0.0);
                entry.slideAnim.animate(0.0, 0.2, Easings.EASE_OUT_POW3);
                entry.heightAnim.animate(1.0, 0.2, Easings.EASE_OUT_POW3);
                entry.alphaAnim.animate(entryHeight + gap, 0.2, Easings.EASE_OUT_POW3);
            }
            entry.fadeAnim.animate(entryY, 0.15, Easings.EASE_OUT_SINE);
            entryY += entry.alphaAnim.getValueF();
        }
        GuiGraphics guiGraphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        float globalAlpha = this.fadeAnim.getValueF();
        if (!this.rightAlignAnim.isDone()) {
            float prevSideFade = 1.0f - this.rightAlignAnim.getValueF();
            leftFade = this.rightAlignAnim.getValueF();
            this.renderLegacy(guiGraphics.pose(), finalX, y, panelWidth, this.rightAlign, globalAlpha * prevSideFade, entryHeight, spacing, cornerRadius, blurStrength, padding);
            this.renderLegacy(guiGraphics.pose(), finalX, y, panelWidth, this.wasRightAligned, globalAlpha * leftFade, entryHeight, spacing, cornerRadius, blurStrength, padding);
        } else {
            this.renderLegacy(guiGraphics.pose(), finalX, y, panelWidth, this.wasRightAligned, globalAlpha, entryHeight, spacing, cornerRadius, blurStrength, padding);
        }
        DrawContext drawContext = glRenderEvent.drawContext();
        if (!this.rightAlignAnim.isDone()) {
            leftFade = 1.0f - this.rightAlignAnim.getValueF();
            rightFade = this.rightAlignAnim.getValueF();
            this.renderEntry(drawContext, finalX, y, panelWidth, this.rightAlign, globalAlpha * leftFade, iconText, titleText, iconWidth, titleWidth, headerHeight, padding, gap, spacing, headSize);
            this.renderEntry(drawContext, finalX, y, panelWidth, this.wasRightAligned, globalAlpha * rightFade, iconText, titleText, iconWidth, titleWidth, headerHeight, padding, gap, spacing, headSize);
        } else {
            this.renderEntry(drawContext, finalX, y, panelWidth, this.wasRightAligned, globalAlpha, iconText, titleText, iconWidth, titleWidth, headerHeight, padding, gap, spacing, headSize);
        }
        if (!this.rightAlignAnim.isDone()) {
            leftFade = 1.0f - this.rightAlignAnim.getValueF();
            rightFade = this.rightAlignAnim.getValueF();
            this.renderEntryGui(guiGraphics, finalX, y, panelWidth, this.rightAlign, globalAlpha * leftFade, headSize, itemSize, padding, gap);
            this.renderEntryGui(guiGraphics, finalX, y, panelWidth, this.wasRightAligned, globalAlpha * rightFade, headSize, itemSize, padding, gap);
        } else {
            this.renderEntryGui(guiGraphics, finalX, y, panelWidth, this.wasRightAligned, globalAlpha, headSize, itemSize, padding, gap);
        }
        guiGraphics.flush();
        if (this.wasRightAligned) {
            this.setX(drawX);
        }
        this.setWidth(panelWidth);
        this.setHeight(entryY - y);
    }

    private void renderLegacy(PoseStack poseStack, float x, float y, float width, boolean rightAligned, float alpha, float entryHeight, float spacing, float cornerRadius, float blurStrength, float padding) {
        if (alpha <= 0.01f) {
            return;
        }
        for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
            float heightFactor = entry.heightAnim.getValueF();
            if (heightFactor <= 0.0f) continue;
            float baseX = rightAligned ? x + width - entry.widthAnim.getValueF() : x;
            float entryX = baseX + entry.slideAnim.getValueF();
            float entryY = entry.fadeAnim.getValueF();
            float entryAlpha = blurStrength * alpha * heightFactor;
            float entryWidth = entry.widthAnim.getValueF();
            float drawX = entryX;
            RenderUtil.drawBlurredRect(poseStack, drawX, entryY, entryWidth, entryHeight, spacing, cornerRadius, entryAlpha, 0);
        }
    }

    private void renderEntry(DrawContext drawContext, float x, float y, float width, boolean rightAligned, float alpha, String iconText, String titleText, float iconWidth, float titleWidth, float headerHeight, float padding, float gap, float spacing, float headSize) {
        if (alpha <= 0.01f) {
            return;
        }
        float headerTextY = y + padding + (headerHeight - padding * 2.0f - (float)GlHelper.getFontAscent(this.nameFont)) / 2.0f + 1.0f;
        int textColor = ColorUtil.fromARGB(255, 255, 255, (int)(255.0f * alpha));
        Paint textPaint = GlHelper.toPaint(textColor);
        try (Paint bgPaint = new Paint()){
            bgPaint.setColor(ColorUtil.fromARGB(0, 0, 0, (int)(190.0f * alpha)));
            GlHelper.drawRoundedRect(x, y, width, headerHeight, spacing, bgPaint);
        }
        if (rightAligned) {
            float iconX = x + width - padding - iconWidth;
            float titleX = iconX - gap - titleWidth;
            GlHelper.drawTextWithShadow(titleText, titleX, headerTextY, this.nameFont, textPaint);
            GlHelper.drawTextWithShadow(iconText, iconX, headerTextY + 1.0f, this.subFont, textPaint);
        } else {
            GlHelper.drawTextWithShadow(iconText, x + padding, headerTextY + 1.0f, this.subFont, textPaint);
            GlHelper.drawTextWithShadow(titleText, x + padding + iconWidth + gap, headerTextY, this.nameFont, textPaint);
        }
        for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
            float headX;
            float entryAlpha;
            float heightFactor = entry.heightAnim.getValueF();
            if (heightFactor <= 0.0f || (entryAlpha = alpha * heightFactor) <= 0.0f) continue;
            float baseX = rightAligned ? x + width - entry.widthAnim.getValueF() : x;
            float entryX = baseX + entry.slideAnim.getValueF();
            float entryY = entry.fadeAnim.getValueF();
            float entryHeight = headSize + padding * 2.0f;
            try (Paint entryBgPaint = new Paint()){
                entryBgPaint.setColor(ColorUtil.fromARGB(0, 0, 0, (int)(90.0f * entryAlpha)));
                float entryWidth = entry.widthAnim.getValueF();
                float entryDrawX = entryX;
                GlHelper.drawRoundedRect(entryDrawX, entryY, entryWidth, entryHeight, spacing, entryBgPaint);
            }
            float contentY = entryY + padding;
            int nameColor = entry.player == mc.player ? ColorUtil.fromRGB(100, 150, 255) : -1;
            int nameAlpha = ColorUtil.withAlpha(nameColor, (int)entryAlpha);
            float nameY = entryY + padding + (entryHeight - padding * 2.0f - (float)GlHelper.getFontAscent(this.headerFont)) / 2.0f;
            if (rightAligned) {
                headX = entryX + entry.widthAnim.getValueF();
                float headDrawX = headX - padding - headSize;
                if (entry.player instanceof AbstractClientPlayer) {
                    GlHelper.drawPlayerHeadRounded((AbstractClientPlayer)entry.player, headDrawX, contentY, headSize, headSize, entryAlpha, spacing);
                }
                float nameX = headDrawX - spacing - entry.nameWidth;
                GlHelper.drawTextShadowLegacy(entry.displayName, nameX, nameY, this.headerFont, nameAlpha);
                continue;
            }
            if (entry.player instanceof AbstractClientPlayer) {
                headX = entryX + padding;
                GlHelper.drawPlayerHeadRounded((AbstractClientPlayer)entry.player, headX, contentY, headSize, headSize, entryAlpha, spacing);
            }
            headX = entryX + padding + headSize + spacing;
            GlHelper.drawTextShadowLegacy(entry.displayName, headX, nameY, this.headerFont, nameAlpha);
        }
    }

    private void renderEntryGui(GuiGraphics guiGraphics, float x, float y, float width, boolean rightAligned, float alpha, float headSize, float itemSize, float padding, float gap) {
        if (alpha <= 0.01f) {
            return;
        }
        for (PlayerListHud.PlayerEntry entry : this.playerEntryList) {
            float entryAlpha;
            float heightFactor = entry.heightAnim.getValueF();
            if (heightFactor <= 0.0f || (entryAlpha = alpha * heightFactor) <= 0.01f) continue;
            float baseX = rightAligned ? x + width - entry.widthAnim.getValueF() : x;
            float entryX = baseX + entry.slideAnim.getValueF();
            float entryY = entry.fadeAnim.getValueF();
            float itemY = entryY + padding + (headSize - itemSize) / 2.0f;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, entryAlpha);
            float rightEdge;
            if (rightAligned) {
                rightEdge = entryX + entry.widthAnim.getValueF();
                float headRight = rightEdge - padding - headSize;
                float nameRight = headRight - gap - entry.nameWidth;
                float itemsTotal = (float)entry.items.size() * (itemSize + gap);
                float itemX = nameRight - gap - itemsTotal;
                for (ItemStack itemStack : entry.items) {
                    PoseStack poseStack = guiGraphics.pose();
                    poseStack.pushPose();
                    poseStack.translate(itemX, itemY, 0.0f);
                    guiGraphics.renderItem(itemStack, 0, 0);
                    poseStack.popPose();
                    itemX += itemSize + gap;
                }
            } else {
                rightEdge = entryX + padding + headSize + gap + entry.nameWidth + gap;
                for (ItemStack itemStack : entry.items) {
                    PoseStack poseStack = guiGraphics.pose();
                    poseStack.pushPose();
                    poseStack.translate(rightEdge, itemY, 0.0f);
                    guiGraphics.renderItem(itemStack, 0, 0);
                    poseStack.popPose();
                    rightEdge += itemSize + gap;
                }
            }
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.disableBlend();
        }
        guiGraphics.flush();
    }

    @Override
    public void onSettings() {
    }
}
