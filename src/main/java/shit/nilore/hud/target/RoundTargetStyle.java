package shit.nilore.hud.target;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.modules.impl.render.NameProtect;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Paint;
import shit.nilore.render.Renderer;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.render.RenderUtil;

public class RoundTargetStyle
extends TargetStyle {
    private static final Color COLOR_PANEL_BG;
    private static final Color COLOR_HEALTH_BG;
    private static final Color COLOR_HEALTH_BAR;
    private static final Color COLOR_HEALTH_BAR2;
    private static final Color COLOR_HEALTH_LAG;
    private final FontRenderer nameFont;
    private final FontRenderer subFont;
    private final SmoothAnimationTimer scaleAnim = new SmoothAnimationTimer();
    private LivingEntity lastTarget;
    private int lastHurtTime;
    private final ItemStack[] equipmentSlots = new ItemStack[4];
    private final Paint panelPaint = new Paint();
    private final Paint healthBgPaint = new Paint();
    private final Paint healthLagPaint = new Paint();
    private final SmoothAnimationTimer fadeAnim;
    private final SmoothAnimationTimer slideAnim;
    private final SmoothAnimationTimer contentAnim;
    private boolean visible = false;
    private LivingEntity currentTarget;
    private long lastActiveTime = 0L;
    private static final String currentTargetName;

    public RoundTargetStyle() {
        super(currentTargetName);
        this.nameFont = FontPresets.pingfang(14.0f);
        this.subFont = FontPresets.astaSans(13.0f);
        this.scaleAnim.setCurrentValue(1.0);
        this.fadeAnim = new SmoothAnimationTimer();
        this.fadeAnim.setCurrentValue(0.0);
        this.slideAnim = new SmoothAnimationTimer();
        this.slideAnim.setCurrentValue(5.0);
        this.contentAnim = new SmoothAnimationTimer();
        this.contentAnim.setCurrentValue(0.0);
    }

    @Override
    public void render(Render2DEvent render2DEvent, LivingEntity livingEntity, SmoothAnimationTimer healthAnim, SmoothAnimationTimer healthLagAnim, float healthPct, float x, float y) {
        float fade;
        boolean shouldShow;
        for (int i = 0; i < this.equipmentSlots.length; ++i) {
            this.equipmentSlots[i] = ItemStack.EMPTY;
        }
        float panelWidth = 120.0f;
        float panelHeight = 38.0f;
        float healthBarRadius = 3.0f;
        float panelRadius = 5.0f;
        boolean hasTarget = livingEntity != null;
        long now = System.currentTimeMillis();
        boolean targetChanged = false;
        if (hasTarget) {
            this.lastActiveTime = now;
            if (this.currentTarget != livingEntity) {
                this.currentTarget = livingEntity;
                this.lastTarget = livingEntity;
                targetChanged = true;
            }
        }
        boolean visibleNow = shouldShow = hasTarget || now - this.lastActiveTime < 300L;
        if (shouldShow != this.visible) {
            this.visible = shouldShow;
            if (this.visible) {
                this.fadeAnim.animate(1.0, 0.35, Easings.EASE_OUT_POW3);
                this.slideAnim.setCurrentValue(5.0);
                this.slideAnim.setStartTime(0L);
                this.contentAnim.setCurrentValue(0.0);
                this.contentAnim.setStartTime(0L);
                this.scaleAnim.setCurrentValue(1.0);
                this.scaleAnim.animate(1.0, 0.0);
            } else {
                this.fadeAnim.animate(0.0, 0.15, Easings.EASE_IN_POW3);
                this.slideAnim.animate(5.0, 0.15, Easings.EASE_IN_POW3);
                this.contentAnim.animate(0.0, 0.15, Easings.EASE_IN_POW3);
            }
        } else if (targetChanged && this.visible) {
            this.fadeAnim.animate(1.0, 0.35, Easings.EASE_OUT_POW3);
            this.slideAnim.setCurrentValue(5.0);
            this.slideAnim.setStartTime(0L);
            this.contentAnim.setCurrentValue(0.0);
            this.contentAnim.setStartTime(0L);
            this.scaleAnim.setCurrentValue(1.0);
            this.scaleAnim.animate(1.0, 0.0);
        }
        this.fadeAnim.tick();
        if (this.fadeAnim.isAnimating() && this.visible) {
            if (this.fadeAnim.getProgress() >= 0.08 && this.slideAnim.getStartTime() == 0L) {
                this.slideAnim.animate(0.0, 0.3, Easings.EASE_OUT_POW3);
            }
            if (this.fadeAnim.getProgress() >= 0.15 && this.contentAnim.getStartTime() == 0L) {
                this.contentAnim.animate(1.0, 0.4, Easings.EASE_OUT_POW3);
            }
        }
        if (this.slideAnim.getStartTime() != 0L) {
            this.slideAnim.tick();
        }
        if (this.contentAnim.getStartTime() != 0L) {
            this.contentAnim.tick();
        }
        if ((fade = this.fadeAnim.getValueF()) <= 0.01f) {
            return;
        }
        LivingEntity target = this.currentTarget;
        if (target == null) {
            return;
        }
        float headBoxSize = 30.0f;
        float headPadding = 4.0f;
        float contentX = x + 4.0f + 30.0f + 4.0f;
        float contentWidth = 120.0f - (contentX - x) - 3.0f;
        float nameY = y + 3.0f + 2.0f;
        float nameAscent = GlHelper.getFontAscent(this.nameFont);
        float belowNameY = nameY + nameAscent + 4.0f;
        PoseStack poseStack = render2DEvent.guiGraphics().pose();
        poseStack.pushPose();
        RenderUtil.drawBlurredRect(poseStack, x, y, 120.0f, 38.0f, 5.0f, 15.0f, 0.95f * fade, 0);
        poseStack.popPose();
        Renderer.renderConsumer((drawContext -> {
            this.panelPaint.setColor(new Color(0, 0, 0, (int)((float)COLOR_PANEL_BG.getAlpha() * fade)).getRGB());
            GlHelper.drawRoundedRect(x, y, 120.0f, 38.0f, 5.0f, this.panelPaint);
            if (hasTarget && livingEntity.hurtTime > this.lastHurtTime) {
                this.scaleAnim.setCurrentValue(0.7f);
                this.scaleAnim.animate(1.0, 1.5, Easings.EASE_OUT_ELASTIC);
            }
            if (hasTarget) {
                this.lastHurtTime = livingEntity.hurtTime;
            }
            this.scaleAnim.tick();
            float scaleValue = this.scaleAnim.getValueF();
            float minScale = (float)Math.max(0.7, fade);
            float combinedScale = scaleValue * minScale;
            float headSize = 30.0f * combinedScale;
            float headX = x + 4.0f + (30.0f - headSize) / 2.0f;
            float headY = y + (38.0f - headSize) / 2.0f;
            if (target instanceof AbstractClientPlayer abstractClientPlayer) {
                GlHelper.drawPlayerHeadRounded(abstractClientPlayer, headX, headY, headSize, headSize, fade, 5.0f * combinedScale);
            }
            float slideOff = this.slideAnim.getValueF();
            String displayName = target == mc.player ? NameProtect.getProtectedName() : target.getName().getString();
            GlHelper.drawTextShadowLegacy(displayName, contentX, nameY + 1.0f + slideOff, this.nameFont, new Color(1.0f, 1.0f, 1.0f, fade).getRGB());
            float healthY = belowNameY + 16.0f;
            float healthH = 4.0f;
            float healthW = contentWidth - 2.0f;
            this.healthBgPaint.setColor(new Color(0, 0, 0, (int)((float)COLOR_HEALTH_BG.getAlpha() * fade)).getRGB());
            GlHelper.drawRoundedRect(contentX, healthY, healthW, healthH, 3.0f, this.healthBgPaint);
            this.healthLagPaint.setColor(new Color(99, 99, 99, (int)((float)COLOR_HEALTH_LAG.getAlpha() * fade)).getRGB());
            float lagWidth = healthLagAnim.getValueF() * healthW;
            GlHelper.drawRoundedRect(contentX, healthY, lagWidth, healthH, 3.0f, this.healthLagPaint);
            float contentVal = this.contentAnim.getValueF();
            float barWidth = healthAnim.getValueF() * healthW * contentVal;
            Color barColor1 = new Color(COLOR_HEALTH_BAR.getRed(), COLOR_HEALTH_BAR.getGreen(), COLOR_HEALTH_BAR.getBlue(), (int)(255.0f * fade));
            Color barColor2 = new Color(COLOR_HEALTH_BAR2.getRed(), COLOR_HEALTH_BAR2.getGreen(), COLOR_HEALTH_BAR2.getBlue(), (int)(255.0f * fade));
            GlHelper.drawGradientRoundedRect(contentX, healthY, barWidth, healthH, 3.0f, barColor1, barColor2);
        }));
        if (target != null) {
            this.equipmentSlots[0] = target.getItemBySlot(EquipmentSlot.HEAD);
            this.equipmentSlots[1] = target.getItemBySlot(EquipmentSlot.CHEST);
            this.equipmentSlots[2] = target.getItemBySlot(EquipmentSlot.LEGS);
            this.equipmentSlots[3] = target.getItemBySlot(EquipmentSlot.FEET);
        }
        float itemX = contentX;
        float itemScale = 0.8f;
        float itemSize = 16.0f * itemScale;
        float itemGap = 2.0f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (ItemStack itemStack : this.equipmentSlots) {
            if (itemStack != null && !itemStack.isEmpty()) {
                PoseStack itemPose = render2DEvent.guiGraphics().pose();
                itemPose.pushPose();
                itemPose.translate(itemX, belowNameY, 0.0f);
                itemPose.scale(itemScale, itemScale, 1.0f);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, fade);
                render2DEvent.guiGraphics().renderItem(itemStack, 0, 0);
                itemPose.popPose();
            }
            itemX += itemSize + itemGap;
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    static {
        currentTargetName = "Round";
        COLOR_PANEL_BG = new Color(0, 0, 0, 80);
        COLOR_HEALTH_BG = new Color(0, 0, 0, 100);
        COLOR_HEALTH_BAR = new Color(0, 150, 255);
        COLOR_HEALTH_BAR2 = new Color(0, 100, 255);
        COLOR_HEALTH_LAG = new Color(99, 99, 99, 120);
    }
}