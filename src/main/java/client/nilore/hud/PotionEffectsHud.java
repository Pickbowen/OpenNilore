package client.nilore.hud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.utils.math.Easings;
import client.nilore.utils.render.ColorUtil;
import client.nilore.event.EventTarget;

public class PotionEffectsHud
extends HudElement {
    public static final class EffectEntry {
        public final PotionEffectsHud outer;
        public MobEffectInstance effectInstance;
        public long originalDuration;
        public String effectName;
        public String durationText;
        public String amplifierText;
        public final client.nilore.utils.animation.SmoothAnimationTimer fadeAnim = new client.nilore.utils.animation.SmoothAnimationTimer();
        public final client.nilore.utils.animation.SmoothAnimationTimer heightAnim = new client.nilore.utils.animation.SmoothAnimationTimer();
        public final client.nilore.utils.animation.SmoothAnimationTimer widthAnim = new client.nilore.utils.animation.SmoothAnimationTimer();
        public boolean removing = false;
        public boolean visible = true;
        // widthAnim 存的是该行的高度；记录当前目标高度，样式切换时据此重新动画。
        public float rowHeight = -1.0f;

        public EffectEntry(PotionEffectsHud outer, MobEffectInstance instance) {
            this.outer = outer;
            this.effectInstance = instance;
            this.originalDuration = instance.getDuration();
            this.refreshDisplayText();
            this.heightAnim.setCurrentValue(0.0);
            this.widthAnim.setCurrentValue(0.0);
        }

        public int getDuration() {
            return this.effectInstance.getDuration();
        }

        public net.minecraft.world.effect.MobEffect getEffect() {
            return this.effectInstance.getEffect();
        }

        public void updateEffect(MobEffectInstance instance) {
            if (instance.getDuration() > this.effectInstance.getDuration()) {
                this.originalDuration = instance.getDuration();
            }
            this.effectInstance = instance;
            this.refreshDisplayText();
        }

        public void refreshDisplayText() {
            this.effectName = this.effectInstance.getEffect().getDisplayName().getString();
            this.durationText = this.outer.formatDuration(this.effectInstance);
            this.amplifierText = this.outer.formatAmplifier(this.effectInstance.getAmplifier() + 1);
        }

        public float getTotalWidth() {
            float iconBoxWidth = 22.0f;
            float padding = 5.0f;
            float nameWidth = GlHelper.getStringWidth(this.effectName, this.outer.effectNameFont);
            float durationWidth = GlHelper.getStringWidth(this.durationText, this.outer.amplifierFont);
            return iconBoxWidth + nameWidth + durationWidth + padding * 3.0f;
        }

        public void show(float targetHeight) {
            this.rowHeight = targetHeight;
            this.heightAnim.animate(1.0, 0.3, client.nilore.utils.math.Easings.EASE_OUT_POW3);
            this.widthAnim.animate(targetHeight, 0.3, client.nilore.utils.math.Easings.EASE_OUT_POW3);
        }

        // show() 只在首次渲染时触发，所以样式切换后必须在这里把行高重新动画到新样式的值。
        public void updateRowHeight(float targetHeight) {
            if (this.removing || this.rowHeight == targetHeight) return;
            this.rowHeight = targetHeight;
            this.widthAnim.animate(targetHeight, 0.3, client.nilore.utils.math.Easings.EASE_OUT_POW3);
        }

        public void startRemove() {
            if (this.removing) return;
            this.removing = true;
            this.heightAnim.animate(0.0, 0.2, client.nilore.utils.math.Easings.EASE_IN_POW3);
            this.widthAnim.animate(0.0, 0.2, client.nilore.utils.math.Easings.EASE_IN_POW3);
        }

        public boolean isRemoveDone() {
            return this.removing && this.heightAnim.isDone() && this.widthAnim.isDone();
        }

        public void tick() {
            this.fadeAnim.tick();
            this.heightAnim.tick();
            this.widthAnim.tick();
            if (!this.effectInstance.getEffect().isInstantenous()) {
                this.refreshDisplayText();
            }
        }
    }

    private final List<PotionEffectsHud.EffectEntry> effectEntryList = new ArrayList<>();
    public final ModeSetting style = new ModeSetting("Style", "Zen", "Rhythm").withDefault("Zen");
    final FontRenderer effectNameFont = FontPresets.pingfang(16.0f);
    private final FontRenderer timerFont = FontPresets.axiformaBold(16.0f);
    final FontRenderer amplifierFont = FontPresets.axiformaBold(14.0f);
    final FontRenderer rhythmNameFont = FontPresets.pingfang(18.0f);
    final FontRenderer rhythmTimerFont = FontPresets.pingfang(16.0f);
    private final Paint backgroundPaint = new Paint();
    private final Paint iconBgPaint = new Paint();
    private final Paint effectIconPaint = new Paint();
    private final Paint timerBarPaint = new Paint();

    public PotionEffectsHud() {
        super("Effects");
        this.setX(10.0f);
        this.setY(50.0f);
        this.setEnabled(true);
        this.backgroundPaint.setAntialias(true);
        this.iconBgPaint.setAntialias(true);
        this.effectIconPaint.setAntialias(true);
        this.timerBarPaint.setAntialias(true);
    }

    @Override
    public void onEnable() {
        if (this.effectEntryList != null) {
            this.effectEntryList.clear();
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            if (this.effectEntryList != null && !this.effectEntryList.isEmpty()) {
                this.effectEntryList.forEach(PotionEffectsHud.EffectEntry::startRemove);
            }
            return;
        }
        Collection<MobEffectInstance> collection = mc.player.getActiveEffects();
        this.effectEntryList.stream().filter(e -> collection.stream().noneMatch(eff -> eff.getEffect() == e.getEffect())).forEach(PotionEffectsHud.EffectEntry::startRemove);
        for (MobEffectInstance mobEffectInstance : collection) {
            Optional<PotionEffectsHud.EffectEntry> existing = this.effectEntryList.stream().filter(e -> !e.removing && e.getEffect() == mobEffectInstance.getEffect()).findFirst();
            if (existing.isEmpty()) {
                PotionEffectsHud.EffectEntry newEntry = new PotionEffectsHud.EffectEntry(this, mobEffectInstance);
                this.effectEntryList.add(newEntry);
                continue;
            }
            existing.get().updateEffect(mobEffectInstance);
        }
        this.effectEntryList.sort((a, b) -> Float.compare(b.getTotalWidth(), a.getTotalWidth()));
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
        if (!this.isEnabled()) {
            return;
        }
        this.renderEffects(glRenderEvent.drawContext(), x, y);
    }

    private void renderEffects(DrawContext drawContext, float x, float y) {
        if (drawContext == null) {
            return;
        }
        if (this.style.is("Rhythm")) {
            this.renderRhythmStyle(drawContext, x, y);
            return;
        }
        // Zen style (default)
        this.renderZenStyle(drawContext, x, y);
    }

    private void renderZenStyle(DrawContext drawContext, float x, float y) {
        this.effectEntryList.removeIf(PotionEffectsHud.EffectEntry::isRemoveDone);
        if (this.effectEntryList.isEmpty()) {
            this.setWidth(0.0f);
            this.setHeight(0.0f);
            return;
        }
        float spacing = 2.0f;
        float entryHeight = 18.0f;
        float cornerRadius = 4.5f;
        float minWidth = 100.0f;
        float currentY = y;
        float maxWidth = 0.0f;
        for (PotionEffectsHud.EffectEntry entry : this.effectEntryList) {
            entry.tick();
            float iconBoxWidth = 20.0f;
            float padding = 5.0f;
            float nameWidth = GlHelper.getStringWidth(entry.effectName, this.effectNameFont);
            float durationWidth = GlHelper.getStringWidth(entry.durationText, this.amplifierFont);
            float totalWidth = Math.max(minWidth, iconBoxWidth + nameWidth + durationWidth + padding * 3.0f);
            if (entry.visible) {
                entry.visible = false;
                entry.fadeAnim.setCurrentValue(currentY);
                entry.show(entryHeight);
            }
            entry.updateRowHeight(entryHeight);
            entry.fadeAnim.animate(currentY, 0.15, Easings.EASE_OUT_SINE);
            float animHeight = entry.heightAnim.getValueF();
            float entryY = entry.fadeAnim.getValueF();
            float animWidth = entry.widthAnim.getValueF();
            if (totalWidth > maxWidth) {
                maxWidth = totalWidth;
            }
            if (animHeight <= 0.01f) {
                currentY += animWidth + (animWidth > 0.0f ? spacing : 0.0f);
                continue;
            }
            int effectColor = this.getEffectColor(entry.effectInstance.getEffect());
            float barWidth = totalWidth - iconBoxWidth;
            float barX = x + iconBoxWidth;
            this.iconBgPaint.setColor(ColorUtil.fromARGB(30, 30, 35, (int)(80.0f * animHeight)));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(barX, entryY, barWidth, animWidth, new float[]{0.0f, 0.0f, 4.5f, 4.5f, 4.5f, 4.5f, 0.0f, 0.0f}), this.iconBgPaint);
            float durationPct = entry.effectInstance.isInfiniteDuration() ? 1.0f : (float)entry.effectInstance.getDuration() / (float)entry.originalDuration;
            this.effectIconPaint.setColor(ColorUtil.fromARGB(effectColor >> 16 & 0xFF, effectColor >> 8 & 0xFF, effectColor & 0xFF, (int)(140.0f * animHeight)));
            if (durationPct > 0.0f) {
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(barX, entryY, barWidth * durationPct, animWidth, new float[]{0.0f, 0.0f, cornerRadius * durationPct, cornerRadius * durationPct, cornerRadius * durationPct, cornerRadius * durationPct, 0.0f, 0.0f}), this.effectIconPaint);
            }
            int r = effectColor >> 16 & 0xFF;
            int g = effectColor >> 8 & 0xFF;
            int b = effectColor & 0xFF;
            this.backgroundPaint.setColor(ColorUtil.fromARGB((int)((float)r * 0.7f + 76.5f), (int)((float)g * 0.7f + 76.5f), (int)((float)b * 0.7f + 76.5f), (int)(160.0f * animHeight)));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, entryY, iconBoxWidth, animWidth, new float[]{4.5f, 4.5f, 0.0f, 0.0f, 0.0f, 0.0f, 4.5f, 4.5f}), this.backgroundPaint);
            this.timerBarPaint.setColor(ColorUtil.fromARGB(255, 255, 255, (int)(185.0f * animHeight)));
            float textY = entryY + (animWidth - (float)GlHelper.getFontAscent(this.effectNameFont)) / 2.0f;
            float ampWidth = GlHelper.getStringWidth(entry.amplifierText, this.timerFont);
            GlHelper.drawTextFormatted(entry.amplifierText, x + (iconBoxWidth - ampWidth) / 2.0f, textY+1f, this.timerFont, this.timerBarPaint, false);
            GlHelper.drawTextFormatted(entry.effectName, barX + padding, textY+1.25f, this.effectNameFont, this.timerBarPaint, false);
            durationWidth = GlHelper.getStringWidth(entry.durationText, this.amplifierFont);
            GlHelper.drawTextFormatted(entry.durationText, x + totalWidth - durationWidth - padding, textY + 1.0f, this.amplifierFont, this.timerBarPaint, false);
            currentY += animWidth + (animWidth > 0.0f ? spacing : 0.0f);
        }
        this.setWidth(maxWidth);
        this.setHeight(Math.max(0.0f, currentY - y - spacing));
    }

    private void renderRhythmStyle(DrawContext drawContext, float x, float y) {
        this.effectEntryList.removeIf(EffectEntry::isRemoveDone);
        if (this.effectEntryList.isEmpty()) {
            this.setWidth(0.0f);
            this.setHeight(0.0f);
            return;
        }

        float spacing = 2.0f;
        float cornerRadius = 6.0f;
        float padding = 5.0f;
        float iconSize = 18.0f;
        float currentY = y;
        float maxWidth = 0.0f;

        // 单栏高度：图标(18) 与 两行文字(18f + 2gap + 16f ≈ 36) 取较高者 + padding
        float entryHeight = Math.max(iconSize + padding * 2.0f, 36.0f + padding * 2.0f) - 16.0f;

        for (EffectEntry entry : this.effectEntryList) {
            entry.tick();

            if (entry.visible) {
                entry.visible = false;
                entry.fadeAnim.setCurrentValue(currentY);
                entry.show(entryHeight);
            }
            entry.updateRowHeight(entryHeight);
            entry.fadeAnim.animate(currentY, 0.15, Easings.EASE_OUT_SINE);
            float entryY = entry.fadeAnim.getValueF();

            float animHeight = entry.heightAnim.getValueF();
            if (animHeight <= 0.01f) {
                currentY += entryHeight + spacing;
                continue;
            }

            int effectColor = this.getEffectColor(entry.effectInstance.getEffect());

            // 计算文字宽度
            float nameWidth = GlHelper.getStringWidth(entry.effectName, this.rhythmNameFont);
            float durationWidth = GlHelper.getStringWidth(entry.durationText, this.rhythmTimerFont);
            float maxTextWidth = Math.max(nameWidth, durationWidth);
            float totalWidth = Math.max(100.0f, iconSize + padding + maxTextWidth + padding * 2.0f);

            if (totalWidth > maxWidth) maxWidth = totalWidth;

            // 进度比例
            float durationPct = entry.effectInstance.isInfiniteDuration() ? 1.0f : (float)entry.effectInstance.getDuration() / (float)entry.originalDuration;

            // 背景轨道（深色空底）
            int bgR = effectColor >> 16 & 0xFF;
            int bgG = effectColor >> 8 & 0xFF;
            int bgB = effectColor & 0xFF;
            int trackColor = ColorUtil.fromARGB(
                    (int)((float)bgR * 0.15f + 25.5f),
                    (int)((float)bgG * 0.15f + 25.5f),
                    (int)((float)bgB * 0.15f + 25.5f),
                    200
            );
            this.backgroundPaint.setColor(trackColor);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, entryY, totalWidth, entryHeight, cornerRadius), this.backgroundPaint);

            // 进度条填充（取药水颜色，按剩余时长比例）
            int fillColor = ColorUtil.fromARGB(
                    (int)((float)bgR * 0.55f + 25.5f),
                    (int)((float)bgG * 0.55f + 25.5f),
                    (int)((float)bgB * 0.55f + 25.5f),
                    200
            );
            float fillWidth = totalWidth * durationPct;
            if (fillWidth > 0.0f) {
                this.effectIconPaint.setColor(fillColor);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, entryY, fillWidth, entryHeight, cornerRadius), this.effectIconPaint);
            }

            // 药水图标精灵
            float iconX = x + padding;
            float iconY = entryY + (entryHeight - iconSize) / 2.0f - 1.0f;
            TextureAtlasSprite sprite = mc.getMobEffectTextures().get(entry.getEffect());
            if (sprite != null) {
                drawContext.getGuiGraphics().blit((int)iconX, (int)iconY, 0, (int)iconSize, (int)iconSize, sprite);
            }

            // 药水名称（白色，18f，上方）
            float textX = x + padding + iconSize + padding;
            float nameY = entryY + padding + 1.0f;
            this.timerBarPaint.setColor(ColorUtil.fromARGB(255, 255, 255, (int)(255.0f * animHeight)));
            GlHelper.drawTextFormatted(entry.effectName, textX, nameY, this.rhythmNameFont, this.timerBarPaint, false);

            // 药水时长（白色半透明，16f，下方）
            float durationY = nameY + 14f;
            this.timerBarPaint.setColor(ColorUtil.fromARGB(255, 255, 255, (int)(180.0f * animHeight)));
            GlHelper.drawTextFormatted(entry.durationText, textX, durationY, this.rhythmTimerFont, this.timerBarPaint, false);

            currentY += entryHeight + spacing;
        }

        this.setWidth(maxWidth);
        this.setHeight(Math.max(0.0f, currentY - y - spacing));
    }

    private int getEffectColor(MobEffect mobEffect) {
        int color = mobEffect.getColor();
        return color != 0 ? color : 3376639;
    }

    String formatAmplifier(int amplifier) {
        return switch (amplifier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(amplifier);
        };
    }

    String formatDuration(MobEffectInstance mobEffectInstance) {
        if (mobEffectInstance.isInfiniteDuration() || mobEffectInstance.getDuration() > 72000) {
            return "∞";
        }
        return MobEffectUtil.formatDuration(mobEffectInstance, 1.0f).getString();
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }
}
// Edit by P i ck bow en.