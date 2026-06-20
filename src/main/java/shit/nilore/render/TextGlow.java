package shit.nilore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.utils.render.RenderUtil;

public final class TextGlow {
    private TextGlow() {
    }

    public static void drawBackground(PoseStack poseStack, float x, float y, float width, float height, float radius, float alpha) {
        int darkAlpha = TextGlow.clampAlpha(150.0f * alpha);
        int lightAlpha = TextGlow.clampAlpha(35.0f * alpha);
        RenderUtil.drawRoundedRect(poseStack, x, y, width, height, radius, new Color(24, 24, 24, darkAlpha).getRGB());
        RenderUtil.drawRoundedRect(poseStack, x, y, width, height, radius, new Color(255, 255, 255, lightAlpha).getRGB());
    }

    public static float drawGlowText(String text, float x, float y, FontRenderer fontRenderer, int color, int glowColor, float radius) {
        return GlHelper.drawText(text, x, y, fontRenderer, color);
    }

    private static int clampAlpha(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}