package shit.lizz.render;

import lombok.Getter;

public final class FontRenderer {
    @Getter
    private final String fontName;
    @Getter
    private final float size;
    private CustomFont customFont;

    public FontRenderer(String var1, float var2) {
        this.fontName = var1;
        this.size = var2;
    }

    public FontRenderer(CustomFont var1, float var2) {
        this.fontName = null;
        this.size = var2;
        this.customFont = var1;
    }

    public CustomFont getFont() {
        if (this.customFont == null && this.fontName != null) {
            this.customFont = Fonts.getCustomFont(this.fontName, this.size);
        }

        return this.customFont;
    }

    public int getId() {
        CustomFont var3 = this.getFont();
        return var3 == null ? 0 : System.identityHashCode(var3);
    }

    public int getHeight() {
        return this.getId();
    }

    public FontRenderer withBold(boolean var1) {
        return this;
    }

    public FontRenderer withItalic(boolean var1) {
        return this;
    }

    public FontRenderer withColor(Object var1) {
        return this;
    }

    public Rectangle getBounds(String var1) {
        if (var1 != null && !var1.isEmpty()) {
            CustomFont var4 = this.getFont();
            if (var4 == null) {
                return Rectangle.ofXYWH(0.0F, 0.0F, 0.0F, 0.0F);
            } else {
                float var5 = var4.getStringWidth(var1);
                float var6 = var4.getStringHeight(var1);
                return Rectangle.ofXYWH(0.0F, 0.0F, var5, var6);
            }
        } else {
            return Rectangle.ofXYWH(0.0F, 0.0F, 0.0F, 0.0F);
        }
    }

    public float getWidth(String var1) {
        if (var1 != null && !var1.isEmpty()) {
            CustomFont var4 = this.getFont();
            return var4 == null ? 0.0F : var4.getStringWidth(var1);
        } else {
            return 0.0F;
        }
    }

    public short[] getGlyphCodes(String var1) {
        return new short[0];
    }

    public Path getGlyphPath(short var1) {
        return null;
    }

    public GlyphMetrics getMetrics() {
        CustomFont var3 = this.getFont();
        if (var3 == null) {
            return new GlyphMetrics(-this.size * 0.8F, this.size * 0.2F, this.size * 1.2F, this.size * 0.7F);
        } else {
            FontMetricsImpl var4 = var3.getFontMetrics();
            int var5 = var3.getScale();
            if (var5 <= 0) {
                var5 = 1;
            }

            float var6 = -var4.getAscent() / var5;
            float var7 = (float)var4.getDescent() / var5;
            float var8 = (float)var4.getLeading() / var5;
            float var9 = (float)var4.getHeight() / var5;
            float var10 = var4.getAscent() * 0.7F / var5;
            return new GlyphMetrics(var6, var7, var9, var10);
        }
    }
}
