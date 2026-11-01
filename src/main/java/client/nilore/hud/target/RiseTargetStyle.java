package client.nilore.hud.target;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.NiloreClient;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.impl.render.NameProtect;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.animation.SmoothAnimationTimer;
import client.nilore.utils.math.Easings;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.RenderUtil;

public class RiseTargetStyle
        extends TargetStyle {
    private static final float PANEL_WIDTH = 194.0f;
    private static final float PANEL_HEIGHT = 45.0f;
    private static final float PANEL_RADIUS = 10.0f;
    private static final float AVATAR_SIZE = 35.0f;
    private static final float AVATAR_OFFSET = 5.0f;
    private static final float CONTENT_GAP = 5.0f;
    private static final float CONTENT_START_X;

    private static final Color COLOR_PANEL_BG;
    private static final Color COLOR_BAR_BG;
    private static final float BAR_WIDTH = 120.0f;
    private static final float BAR_GLOW_RADIUS = 6.0f;

    // Fallback gradient colors (when ModuleListHud is unavailable)
    private static final int FALLBACK_COLOR1;
    private static final int FALLBACK_COLOR2;

    private final FontRenderer nameFont;
    private final FontRenderer prefixFont;
    private final FontRenderer healthFont;
    private final SmoothAnimationTimer fadeAnim;
    private final SmoothAnimationTimer slideAnim;
    private final SmoothAnimationTimer contentAnim;
    private boolean visible = false;
    private LivingEntity currentTarget;
    private long lastActiveTime = 0L;
    private final Paint panelPaint = new Paint();
    private final Paint barBgPaint = new Paint();

    public RiseTargetStyle() {
        super("Rise");
        this.nameFont = FontPresets.pingfang(21.0f);
        this.prefixFont = Fonts.getRenderer("quicksand.ttf", 22.0f);
        this.healthFont = Fonts.getRenderer("quicksand.ttf", 18.0f);
        this.fadeAnim = new SmoothAnimationTimer();
        this.fadeAnim.setCurrentValue(0.0);
        this.slideAnim = new SmoothAnimationTimer();
        this.slideAnim.setCurrentValue(5.0);
        this.contentAnim = new SmoothAnimationTimer();
        this.contentAnim.setCurrentValue(0.0);
    }

    /**
     * Gets a theme color from ModuleListHud, so all Rise gradient follows
     * the same theme (Gradient / Rainbow / Solid) the user configured.
     */
    private int getThemeColor(int rowIndex) {
        try {
            ModuleListHud moduleList = (ModuleListHud) NiloreClient.getInstance().getHudManager()
                    .getHudElementByName("ModuleList");
            if (moduleList != null) {
                return moduleList.getThemeColor(rowIndex, 0.5f, Math.max(1, rowIndex));
            }
        } catch (Exception exception) {
            // fall through to fallback
        }
        return FALLBACK_COLOR1;
    }

    @Override
    public void render(Render2DEvent render2DEvent, LivingEntity livingEntity, SmoothAnimationTimer healthAnim, SmoothAnimationTimer healthLagAnim, float healthPct, float x, float y) {
        float fade;
        boolean shouldShow;
        boolean hasTarget = livingEntity != null;
        long now = System.currentTimeMillis();
        boolean targetChanged = false;
        if (hasTarget) {
            this.lastActiveTime = now;
            if (this.currentTarget != livingEntity) {
                this.currentTarget = livingEntity;
                targetChanged = true;
            }
        }
        boolean visibleNow = shouldShow = hasTarget || now - this.lastActiveTime < 300L;
        if (shouldShow != this.visible || (shouldShow && this.fadeAnim.getValueF() <= 0.01f)) {
            this.visible = shouldShow;
            if (this.visible) {
                this.fadeAnim.animate(1.0, 0.35, Easings.EASE_OUT_POW3);
                this.slideAnim.setCurrentValue(5.0);
                this.slideAnim.setStartTime(0L);
                this.contentAnim.setCurrentValue(0.0);
                this.contentAnim.setStartTime(0L);
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

        // === Layout ===
        String rawName = target == mc.player ? NameProtect.getProtectedName() : target.getName().getString();
        String healthStr = String.valueOf((int) Math.ceil(target.getHealth())) + ".0";

        // Font metrics
        float prefixW = GlHelper.getStringWidth("Name: ", this.prefixFont);
        float healthW = GlHelper.getStringWidth(healthStr, this.healthFont);

        // Fixed positions
        float avatarX = x + AVATAR_OFFSET;
        float avatarY = y + AVATAR_OFFSET;

        float contentX = x + CONTENT_START_X;                   // 45px from panel left
        float panelRight = x + PANEL_WIDTH - AVATAR_OFFSET;     // right edge - 5px padding
        float healthTextX = panelRight - healthW;               // health number flush right

        float slideOff = this.slideAnim.getValueF();
        float nameY = y + 10.0f + slideOff;

        float barY = y + 28.0f;
        float barH = 9.0f;
        float barW = BAR_WIDTH;                                       // fixed 120f width
        float barRadius = barH / 2.0f;                                 // capsule shape
        float barGlowAlpha = 45.0f;

        float healthTextY = barY + (barH - 18.0f) / 2.0f + 1.0f + 7.0f;  // vertically center in bar + 7f down

        int gradientColor1 = this.getThemeColor(0);
        int gradientColor2 = this.getThemeColor(4);

        // === Draw ===
        PoseStack poseStack = render2DEvent.guiGraphics().pose();
        poseStack.pushPose();
        RenderUtil.drawBlurredRect(poseStack, x, y, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS, 15.0f, 0.95f * fade, 0);
        poseStack.popPose();

        Renderer.renderConsumer(drawContext -> {
            // Panel background — same blur/alpha style as Round
            this.panelPaint.setColor(new Color(0, 0, 0, (int)((float)COLOR_PANEL_BG.getAlpha() * fade)).getRGB());
            GlHelper.drawRoundedRect(x, y, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS, this.panelPaint);

            // Player head — 35x35, 5px from top/left/bottom, rounded to match panel
            if (target instanceof AbstractClientPlayer abstractClientPlayer) {
                GlHelper.drawPlayerHeadRounded(abstractClientPlayer, avatarX, avatarY, AVATAR_SIZE, AVATAR_SIZE, fade, PANEL_RADIUS);
            }

            // "Name: " in white — Quicksand 22f
            int whiteArgb = new Color(255, 255, 255, (int)(255f * fade)).getRGB();
            GlHelper.drawTextShadowLegacy("Name: ", contentX, nameY + 1.0f, this.prefixFont, whiteArgb);

            float namePartX = contentX + prefixW;
            int charIdx = 0;
            for (char c : rawName.toCharArray()) {
                String charStr = String.valueOf(c);
                float charW = GlHelper.getStringWidth(charStr, this.nameFont);
                int charColor = ColorUtil.withAlpha(this.getThemeColor(charIdx), fade);
                GlHelper.drawTextShadowLegacy(charStr, namePartX, nameY - 0.5f, this.nameFont, charColor);
                namePartX += charW;
                charIdx++;
            }

            // Health bar background
            this.barBgPaint.setColor(new Color(0, 0, 0, (int)((float)COLOR_BAR_BG.getAlpha() * fade)).getRGB());
            GlHelper.drawRoundedRect(contentX, barY, barW, barH, barRadius, this.barBgPaint);

            // Health bar fill — horizontal gradient (left → right)
            float contentVal = this.contentAnim.getValueF();
            float barFill = healthAnim.getValueF() * barW * contentVal;
            Color c1 = new Color(ColorUtil.withAlpha(gradientColor1, fade), true);
            Color c2 = new Color(ColorUtil.withAlpha(gradientColor2, fade), true);

            // Glow beneath the bar
            Color glowColor = new Color(ColorUtil.withAlpha(gradientColor1, fade * barGlowAlpha / 255.0f), true);
            GlHelper.drawBlurredRoundedRectColor(contentX, barY, barFill, barH, barRadius, glowColor, BAR_GLOW_RADIUS, 0.0f, 0.0f);

            drawHorizontalGradientRoundedRect(contentX, barY, barFill, barH, barRadius, c1, c2);

            // Health number — white, same y-level as bar
            int healthWhite = new Color(255, 255, 255, (int)(255f * fade)).getRGB();
            GlHelper.drawTextShadowLegacy(healthStr, healthTextX, healthTextY, this.healthFont, healthWhite);
        });
    }

    private static void drawHorizontalGradientRoundedRect(float x, float y, float w, float h, float r, Color c1, Color c2) {
        int col1 = c1.getAlpha() << 24 | c1.getRed() << 16 | c1.getGreen() << 8 | c1.getBlue();
        int col2 = c2.getAlpha() << 24 | c2.getRed() << 16 | c2.getGreen() << 8 | c2.getBlue();
        Paint.GradientCoords gc = new Paint.GradientCoords(x, y, x + w, y, col1, col2);
        Paint p = new Paint().setGradCoords(gc);
        GlHelper.getCanvas().drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, r), p);
    }

    static {
        CONTENT_START_X = AVATAR_OFFSET + AVATAR_SIZE + CONTENT_GAP;

        COLOR_PANEL_BG = new Color(0, 0, 0, 45);
        COLOR_BAR_BG = new Color(0, 0, 0, 100);

        FALLBACK_COLOR1 = new Color(70, 130, 255).getRGB();
        FALLBACK_COLOR2 = new Color(140, 200, 255).getRGB();
    }
}
