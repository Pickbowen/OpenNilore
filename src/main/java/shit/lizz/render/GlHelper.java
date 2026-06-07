package shit.lizz.render;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import shit.lizz.ClientBase;
import shit.lizz.utils.render.ColorUtil;

public final class GlHelper {
    private static final Map<FontRenderer, Map<String, Float>> stringWidthCache = new HashMap<>();

    public static DrawContext getCanvas() {
        DrawContext drawContext = Renderer.getCanvas();
        if (drawContext == null) {
            throw new IllegalStateException("GlHelper.getCanvas() called outside a GlRenderer.render block");
        }
        return drawContext;
    }

    public static float drawText(String text, float x, float y, FontRenderer fontRenderer, int color) {
        Paint paint = GlHelper.toPaint(color);
        return GlHelper.drawTextFormatted(text, x, y, fontRenderer, paint, false);
    }

    public static int getFontAscent(FontRenderer fontRenderer) {
        GlyphMetrics glyphMetrics = fontRenderer.getMetrics();
        float ascent = (glyphMetrics.getLineGap() - glyphMetrics.ascent() - glyphMetrics.descent()) / 2.0f;
        return (int)Math.ceil(ascent);
    }

    public static Texture wrapTexture(AbstractTexture abstractTexture) {
        if (abstractTexture == null) {
            return null;
        }
        int glId = abstractTexture.getId();
        int width = GL11.glGetTexLevelParameteri(3553, 0, 4096);
        int height = GL11.glGetTexLevelParameteri(3553, 0, 4097);
        if (width <= 0) {
            width = 64;
        }
        if (height <= 0) {
            height = 64;
        }
        return new Texture(glId, width, height);
    }

    public static void drawPlayerHead(AbstractClientPlayer player, float x, float y, float width, float height, float alpha) {
        GlHelper.drawPlayerHeadRounded(player, x, y, width, height, alpha, 0.0f);
    }

    public static void drawPlayerHeadRounded(AbstractClientPlayer player, float x, float y, float width, float height, float alpha, float radius) {
        ResourceLocation resourceLocation = player.getSkinTextureLocation();
        if (resourceLocation == null || ClientBase.mc.getTextureManager().getTexture(resourceLocation) == null) {
            return;
        }
        AbstractTexture abstractTexture = ClientBase.mc.getTextureManager().getTexture(resourceLocation);
        int glId = abstractTexture.getId();
        DrawContext drawContext = GlHelper.getCanvas();
        int packedColor = (int)Math.max(0.0f, Math.min(255.0f, alpha * 255.0f)) << 24 | 0xFFFFFF;
        RoundedRectShader roundedRectShader = DrawContext.getRoundedRectShader();
        float baseU1 = 0.125f;
        float baseV1 = 0.125f;
        float baseU2 = 0.25f;
        float baseV2 = 0.25f;
        float hatU1 = 0.625f;
        float hatV1 = 0.125f;
        float hatU2 = 0.75f;
        float hatV2 = 0.25f;
        Matrix4f pose = drawContext.getPoseStack().last().pose();
        roundedRectShader.drawTextured(pose, x, y, x + width, y + height, radius, radius, radius, radius, packedColor, glId, baseU1, baseV1, baseU2, baseV2);
        roundedRectShader.drawTextured(pose, x, y, x + width, y + height, radius, radius, radius, radius, packedColor, glId, hatU1, hatV1, hatU2, hatV2);
        if (player.hurtTime > 0) {
            int hurtColor = ColorUtil.withAlphaColor(new Color(255, 0, 0, player.hurtTime * 18), alpha).getRGB();
            Paint paint = new Paint().setColor(hurtColor);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, radius), paint);
        }
    }

    public static float drawTextFormatted(String text, float x, float y, FontRenderer fontRenderer, Paint paint, boolean keepColor) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        DrawContext drawContext = GlHelper.getCanvas();
        int color = paint.getColor();
        float cursorX = x;
        float baselineY = y + (float)GlHelper.getFontAscent(fontRenderer);
        String[] parts = text.split("§");
        for (int i = 0; i < parts.length; ++i) {
            char code;
            ChatFormatting chatFormatting;
            String part = parts[i];
            if (i > 0 && !part.isEmpty() && (chatFormatting = ChatFormatting.getByCode(code = part.charAt(0))) != null) {
                if (!keepColor && chatFormatting.getColor() != null) {
                    color = ColorUtil.withAlpha(chatFormatting.getColor(), (float)(color >> 24 & 0xFF) / 255.0f);
                }
                part = part.substring(1);
            }
            drawContext.drawString(part, cursorX, baselineY, fontRenderer, paint.setColor(color));
            cursorX += GlHelper.getStringWidth(part, fontRenderer);
        }
        return cursorX;
    }

    public static float drawTextWithShadow(String text, float x, float y, FontRenderer fontRenderer, Paint paint) {
        int color = paint.getColor();
        GlHelper.drawTextFormatted(text, x + 0.5f, y + 0.5f, fontRenderer, paint.setColor(ColorUtil.fromARGB(0, 0, 0, (int)((float)ColorUtil.getAlpha(paint.getColor()) * 0.65f * 255.0f))), true);
        paint.setColor(color);
        return GlHelper.drawTextFormatted(text, x, y, fontRenderer, paint, false);
    }

    public static float drawTextShadowLegacy(String text, float x, float y, FontRenderer fontRenderer, int color) {
        Paint paint = GlHelper.toPaint(color);
        float alpha = (float)(color >> 24 & 0xFF) / 255.0f;
        GlHelper.drawTextFormatted(text, x + 0.5f, y + 0.5f, fontRenderer, GlHelper.toPaint(ColorUtil.fromARGB(0, 0, 0, (int)(alpha * 0.65f * 255.0f))), true);
        return GlHelper.drawTextFormatted(text, x, y, fontRenderer, paint, false);
    }

    public static float drawTextBlurred(String text, float x, float y, FontRenderer fontRenderer, int color, int blurColor, float radius) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        DrawContext drawContext = GlHelper.getCanvas();
        float width = fontRenderer.getWidth(text);
        float height = fontRenderer.getFont() != null ? fontRenderer.getFont().getFontHeight() : fontRenderer.getSize();
        float halfBlur = Math.max(0.01f, radius * 0.5f);
        drawContext.drawBlur(x, y - height, width, height * 2.0f, halfBlur, () -> drawContext.drawString(text, x, y, fontRenderer, GlHelper.toPaint(blurColor)));
        return GlHelper.drawText(text, x, y, fontRenderer, color);
    }

    public static float drawTextCentered(float centerX, float centerY, String text, FontRenderer fontRenderer, Paint paint) {
        float width = fontRenderer.getWidth(text);
        float height = fontRenderer.getFont() != null ? fontRenderer.getFont().getFontHeight() : fontRenderer.getSize();
        return GlHelper.drawTextFormatted(text, centerX - width / 2.0f, centerY - height / 2.0f, fontRenderer, paint, false);
    }

    public static Paint toPaint(Object source) {
        Paint paint = new Paint();
        if (source instanceof float[]) {
            paint.setColorFromArray((float[])source);
        } else if (source instanceof Color color) {
            paint.setColorARGB(color.getAlpha(), color.getRed(), color.getGreen(), color.getBlue());
        } else if (source instanceof Integer color) {
            paint.setColor(color);
        }
        return paint;
    }

    public static float getStringWidth(String text, FontRenderer fontRenderer) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        return stringWidthCache.computeIfAbsent(fontRenderer, key -> new HashMap<>()).computeIfAbsent(text, key -> key != null ? fontRenderer.getWidth(key.replaceAll("§.", "")) : 0.0f);
    }

    public static void drawRoundedRect(float x, float y, float width, float height, float radius, Paint paint) {
        GlHelper.getCanvas().drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, radius), paint);
    }

    public static void drawRoundedRectCorners(float x, float y, float width, float height, float tlRadius, float trRadius, float brRadius, float blRadius, Paint paint) {
        float[] radii = new float[]{tlRadius, tlRadius, trRadius, trRadius, brRadius, brRadius, blRadius, blRadius};
        GlHelper.getCanvas().drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, width, height, radii), paint);
    }

    private static int colorToInt(Color color) {
        return color.getAlpha() << 24 | color.getRed() << 16 | color.getGreen() << 8 | color.getBlue();
    }

    public static void drawGradientRoundedRect(float x, float y, float width, float height, float radius, Color color, Color color2) {
        Paint.GradientCoords gradCoords = new Paint.GradientCoords(x, y, x, y + height, GlHelper.colorToInt(color), GlHelper.colorToInt(color2));
        Paint paint = new Paint().setGradCoords(gradCoords);
        GlHelper.getCanvas().drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, radius), paint);
    }

    public static void drawBlurredRoundedRectColor(float x, float y, float width, float height, float radius, Color color, float blurRadius, float offsetX, float offsetY) {
        GlHelper.getCanvas().drawBlurredRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, radius), offsetX, offsetY, blurRadius, 1.0f, color.getRGB());
    }

    public static void drawShadowRoundedRect(float x, float y, float width, float height, float radius, Color color) {
        GlHelper.getCanvas().drawBlurredRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, radius), 0.0f, 0.0f, 10.0f, 1.0f, color.getRGB());
    }

    public static void drawRoundedRectCornersColor(float x, float y, float width, float height, float tlRadius, float trRadius, float brRadius, float blRadius, Color color) {
        float[] radii = new float[]{tlRadius, tlRadius, trRadius, trRadius, brRadius, brRadius, blRadius, blRadius};
        GlHelper.getCanvas().drawBlurredRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, width, height, radii), 0.0f, 0.0f, 10.0f, 1.0f, color.getRGB());
    }

    public static void drawRect(float x, float y, float width, float height, Paint paint) {
        GlHelper.getCanvas().drawRect(Rectangle.ofXYWH(x, y, width, height), paint);
    }

    public static void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, int color) {
        Paint paint = GlHelper.toPaint(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.StrokeCap.STROKE);
        GlHelper.getCanvas().drawLine(x1, y1, x2, y2, paint);
    }

    static {
        new HashMap<>();
    }
}