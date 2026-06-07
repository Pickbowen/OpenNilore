package shit.lizz.hud.target;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.modules.impl.render.NameProtect;
import shit.lizz.render.FontPresets;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.GlHelper;
import shit.lizz.render.Paint;
import shit.lizz.render.Renderer;
import shit.lizz.utils.animation.SmoothAnimationTimer;
import shit.lizz.utils.math.Easings;

public class MoonTargetStyle extends TargetStyle {

    private static final Color COLOR_PANEL_BG = new Color(0, 0, 0, 120);
    private static final Color COLOR_HEALTH_BG = new Color(0, 0, 0, 200);
    private static final Color COLOR_THEME = new Color(0, 150, 255);

    private static final float BORDER = 3f;
    private static final float HEAD_SIZE = 32f;
    private static final float BAR_HEIGHT = 4f;
    private static final float CORNER_RADIUS = 6f;
    private static final int VANISH_DELAY = 20;

    private final FontRenderer nameFont;
    private final SmoothAnimationTimer fadeAnim;
    private final SmoothAnimationTimer barAnim;
    private final SmoothAnimationTimer scaleAnim;
    private LivingEntity lastTarget;
    private LivingEntity currentTarget;
    private int lastHurtTime;
    private boolean visible = false;
    private int delayCounter = 0;
    private boolean hasEverHadTarget = false;

    public MoonTargetStyle() {
        super("Moon");
        this.nameFont = FontPresets.pingfang(14.0f);
        this.fadeAnim = new SmoothAnimationTimer();
        this.fadeAnim.setCurrentValue(0.0);
        this.barAnim = new SmoothAnimationTimer();
        this.barAnim.setCurrentValue(0.0);
        this.scaleAnim = new SmoothAnimationTimer();
        this.scaleAnim.setCurrentValue(1.0);
    }

    @Override
    public void render(Render2DEvent event, LivingEntity target, SmoothAnimationTimer healthAnim,
                       SmoothAnimationTimer healthLagAnim, float healthPct, float x, float y) {

        boolean hasTarget = target != null;
        boolean targetChanged = false;

        // Vanish delay logic (matching Kotlin code)
        if (hasTarget) {
            hasEverHadTarget = true;
            if (this.currentTarget != target) {
                this.currentTarget = target;
                this.lastTarget = target;
                targetChanged = true;
            }
            this.delayCounter = 0;
        } else if (hasEverHadTarget) {
            this.delayCounter++;
        }

        boolean shouldShow = hasTarget || this.delayCounter < VANISH_DELAY;

        if (shouldShow != this.visible) {
            this.visible = shouldShow;
            if (this.visible) {
                this.fadeAnim.animate(1.0, 0.35, Easings.EASE_OUT_POW3);
                this.barAnim.setCurrentValue(0.0);
                this.barAnim.setStartTime(0L);
                this.scaleAnim.setCurrentValue(1.0);
                this.scaleAnim.animate(1.0, 0.0);
            } else {
                this.fadeAnim.animate(0.0, 0.5, Easings.EASE_IN_POW3);
                this.barAnim.animate(0.0, 0.3, Easings.EASE_IN_POW3);
            }
        } else if (targetChanged && this.visible) {
            this.fadeAnim.animate(1.0, 0.35, Easings.EASE_OUT_POW3);
            this.barAnim.setCurrentValue(0.0);
            this.barAnim.setStartTime(0L);
            this.scaleAnim.setCurrentValue(1.0);
            this.scaleAnim.animate(1.0, 0.0);
        }

        this.fadeAnim.tick();
        if (this.barAnim.getStartTime() != 0L) this.barAnim.tick();

        float fade = this.fadeAnim.getValueF();
        if (fade <= 0.01f) return;

        // Use lastTarget when no current target (vanish display)
        LivingEntity displayTarget = this.currentTarget != null ? this.currentTarget : this.lastTarget;
        if (displayTarget == null) return;

        if (hasTarget && displayTarget.hurtTime > this.lastHurtTime) {
            this.scaleAnim.setCurrentValue(0.7f);
            this.scaleAnim.animate(1.0, 1.5, Easings.EASE_OUT_ELASTIC);
        }
        if (hasTarget) this.lastHurtTime = displayTarget.hurtTime;
        this.scaleAnim.tick();
        float scaleValue = this.scaleAnim.getValueF();

        // Compute text
        String displayName = displayTarget == mc.player ? NameProtect.getProtectedName() : displayTarget.getName().getString();
        String targetName = displayName + "  ";
        float targetNameWidth = GlHelper.getStringWidth(targetName, this.nameFont);
        int targetHealth = (int) displayTarget.getHealth();
        String healthStr = String.valueOf(targetHealth);
        float targetHealthWidth = GlHelper.getStringWidth(healthStr, this.nameFont);

        // Layout (matching Kotlin: borderWidth + headWidth + borderWidth for text begin)
        float textBegin = BORDER + HEAD_SIZE + BORDER;
        float allTextLen = targetNameWidth + targetHealthWidth;
        float progressWidth = Math.max(allTextLen + textBegin + 8f, 120f);
        float panelW = BORDER * 2 + progressWidth;
        float panelH = BORDER + HEAD_SIZE + BORDER + BAR_HEIGHT + BORDER;

        // Health bar animation
        float maxHealth = Math.max(displayTarget.getMaxHealth(), 1f);
        float barFullWidth = progressWidth / maxHealth * targetHealth;
        float barFullClamped = Math.max(0, Math.min(barFullWidth, progressWidth));
        this.barAnim.animate(barFullClamped, 0.3, Easings.EASE_OUT_POW3);
        float animatedBarW = this.barAnim.getValueF();

        float fadeAlpha = fade;

        Renderer.renderConsumer(dc -> {
            // Shadow
            GlHelper.drawShadowRoundedRect(x, y, panelW, panelH, CORNER_RADIUS,
                    new Color(0, 0, 0, (int)(60f * fadeAlpha)));

            // Panel background
            Paint bgPaint = new Paint().setColor(new Color(0, 0, 0, (int)(COLOR_PANEL_BG.getAlpha() * fadeAlpha)).getRGB());
            GlHelper.drawRoundedRect(x, y, panelW, panelH, CORNER_RADIUS, bgPaint);

            // Player head
            if (displayTarget instanceof AbstractClientPlayer player) {
                float headS = HEAD_SIZE * scaleValue;
                float headX = x + BORDER + (HEAD_SIZE - headS) / 2f;
                float headY = y + BORDER + (HEAD_SIZE - headS) / 2f;
                GlHelper.drawPlayerHeadRounded(player, headX, headY, headS, headS, fadeAlpha, 4f * scaleValue);
            }

            // Health bar background (dark)
            float barY = y + BORDER + HEAD_SIZE + BORDER;
            Paint barBgPaint = new Paint().setColor(new Color(0, 0, 0, (int)(COLOR_HEALTH_BG.getAlpha() * fadeAlpha)).getRGB());
            GlHelper.drawRoundedRect(x + BORDER, barY, progressWidth, BAR_HEIGHT, 2f, barBgPaint);

            // Health bar (animated, theme color with alpha)
            if (animatedBarW > 0.5f) {
                Paint animPaint = new Paint().setColor(new Color(
                        COLOR_THEME.getRed(), COLOR_THEME.getGreen(), COLOR_THEME.getBlue(),
                        (int)(150f * fadeAlpha)).getRGB());
                GlHelper.drawRoundedRect(x + BORDER, barY, animatedBarW, BAR_HEIGHT, 2f, animPaint);
            }

            // Health bar (instant, full theme color)
            if (barFullClamped > 0.5f) {
                Paint fullPaint = new Paint().setColor(new Color(
                        COLOR_THEME.getRed(), COLOR_THEME.getGreen(), COLOR_THEME.getBlue(),
                        (int)(255f * fadeAlpha)).getRGB());
                GlHelper.drawRoundedRect(x + BORDER, barY, barFullClamped, BAR_HEIGHT, 2f, fullPaint);
            }

            // Name text (white)
            float textX = x + textBegin + BORDER;
            float textY = y + BORDER * 2;
            GlHelper.drawTextShadowLegacy(targetName, textX, textY, nameFont,
                    new Color(1f, 1f, 1f, fadeAlpha).getRGB());

            // Health number (theme color)
            float healthX = textX + targetNameWidth + BORDER;
            GlHelper.drawTextShadowLegacy(healthStr, healthX, textY, nameFont,
                    new Color(COLOR_THEME.getRed(), COLOR_THEME.getGreen(), COLOR_THEME.getBlue(),
                            (int)(255f * fadeAlpha)).getRGB());

            // Armor items below head
            drawArmor(event, displayTarget, x + BORDER+38f, y + BORDER + HEAD_SIZE - 18, fadeAlpha);
        });
    }

    private void drawArmor(Render2DEvent event, LivingEntity target, float armorX, float armorY, float alpha) {
        ItemStack[] armor = {
                target.getItemBySlot(EquipmentSlot.HEAD),
                target.getItemBySlot(EquipmentSlot.CHEST),
                target.getItemBySlot(EquipmentSlot.LEGS),
                target.getItemBySlot(EquipmentSlot.FEET)
        };
        float itemScale = 0.7f;
        float itemSize = 16f * itemScale;
        float ix = armorX;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (ItemStack stack : armor) {
            if (stack != null && !stack.isEmpty()) {
                PoseStack pose = event.guiGraphics().pose();
                pose.pushPose();
                pose.translate(ix, armorY, 0);
                pose.scale(itemScale, itemScale, 1f);
                RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
                event.guiGraphics().renderItem(stack, 0, 0);
                pose.popPose();
            }
            ix += itemSize + 2f;
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
