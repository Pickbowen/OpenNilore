package client.nilore.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import client.nilore.ClientBase;
import client.nilore.render.Glyph;
import client.nilore.utils.misc.ReflectionUtil;

class GlyphPage {
    final char startChar;
    final char endChar;
    final Font font;
    final ResourceLocation textureLocation;
    final int padding;
    /**
     * 伪粗体的水平偏移量，单位是纹理像素；0 表示按普通字重渲染。
     *
     * <p>光栅化是在放大 {@code scale} 倍之后做的，所以这个值由调用方按 scale 折算，
     * 保证缩回屏幕上的加粗量是一致的。
     */
    final int boldPx;
    /**
     * 主字体画不出来的字符交给它们兜底，按顺序试。
     *
     * <p>典型用法是主字体选 Apple 风格的西文字体、中文字符落到苹方——
     * 之前这里两个分支返回的是同一个字体，等于 fallback 从来没生效过。
     */
    private final Font[] fallbacks;
    private final Char2ObjectArrayMap<Glyph> glyphMap = new Char2ObjectArrayMap();
    private final BitSet rasterized = new BitSet();
    int imageWidth;
    int imageHeight;
    boolean uploaded = false;
    private BufferedImage atlasImage;
    private Graphics2D atlasGraphics;
    private NativeImage nativeImage;
    private DynamicTexture texture;

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding) {
        this(startChar, endChar, font, textureLocation, padding, 0, null);
    }

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding, int boldPx) {
        this(startChar, endChar, font, textureLocation, padding, boldPx, null);
    }

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding,
                     int boldPx, Font[] fallbacks) {
        this.startChar = startChar;
        this.endChar = endChar;
        this.font = font;
        this.textureLocation = textureLocation;
        this.padding = padding;
        this.boldPx = Math.max(0, boldPx);
        this.fallbacks = fallbacks == null ? new Font[0] : fallbacks;
    }

    /**
     * Returns the glyph for {@code c}, rasterizing it on demand.
     *
     * <p>Only the glyphs that are actually drawn get rasterized; the page layout is
     * still computed up front (bounds measuring is cheap), so the atlas keeps the same
     * compact packing as before while avoiding the one-shot cost of rasterizing a whole
     * 64-character block whenever a single character appears.
     */
    public Glyph getGlyph(char c) {
        if (!this.uploaded) {
            this.buildAtlas();
        }
        Glyph glyph = this.glyphMap.get(c);
        if (glyph != null && !this.rasterized.get(c - this.startChar)) {
            this.rasterizeGlyph(glyph);
        }
        return glyph;
    }

    public void reset() {
        ClientBase.mc.getTextureManager().release(this.textureLocation);
        if (this.atlasGraphics != null) {
            this.atlasGraphics.dispose();
            this.atlasGraphics = null;
        }
        this.glyphMap.clear();
        this.rasterized.clear();
        this.atlasImage = null;
        this.nativeImage = null;
        this.texture = null;
        this.imageWidth = -1;
        this.imageHeight = -1;
        this.uploaded = false;
    }

    public boolean contains(char c) {
        return c >= this.startChar && c < this.endChar;
    }

    private Font getFontForChar(char c) {
        if (this.font.canDisplay(c)) {
            return this.font;
        }
        for (Font fallback : this.fallbacks) {
            if (fallback.canDisplay(c)) {
                return fallback;
            }
        }
        return this.font;
    }

    public void buildAtlas() {
        if (this.uploaded) {
            return;
        }
        int total = this.endChar - this.startChar - 1;
        int columns = (int)(Math.ceil(Math.sqrt(total)) * 1.5);
        this.glyphMap.clear();
        int index = 0;
        int colCount = 0;
        int maxWidth = 0;
        int maxHeight = 0;
        int curX = 0;
        int curY = 0;
        int rowHeight = 0;
        ArrayList<Glyph> glyphs = new ArrayList<>();
        AffineTransform affineTransform = new AffineTransform();
        FontRenderContext fontRenderContext = new FontRenderContext(affineTransform, true, false);
        while (index <= total) {
            char c = (char)(this.startChar + index);
            Font font = this.getFontForChar(c);
            Rectangle2D bounds = font.getStringBounds(String.valueOf(c), fontRenderContext);
            // 伪粗体要往右多占一点，不然加粗出来的那半边会被字形格子裁掉
            int width = (int)Math.ceil(bounds.getWidth()) + this.boldPx;
            int height = (int)Math.ceil(bounds.getHeight());
            ++index;
            maxWidth = Math.max(maxWidth, curX + width);
            maxHeight = Math.max(maxHeight, curY + height);
            if (colCount >= columns) {
                curX = 0;
                curY += rowHeight + this.padding;
                colCount = 0;
                rowHeight = 0;
            }
            rowHeight = Math.max(rowHeight, height);
            glyphs.add(new Glyph(curX, curY, width, height, c, this));
            curX += width + this.padding;
            ++colCount;
        }
        this.atlasImage = new BufferedImage(
                Math.max(maxWidth + this.padding, 1),
                Math.max(maxHeight + this.padding, 1), 2);
        this.imageWidth = this.atlasImage.getWidth();
        this.imageHeight = this.atlasImage.getHeight();
        this.atlasGraphics = this.atlasImage.createGraphics();
        this.atlasGraphics.setColor(Color.WHITE);
        this.atlasGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        this.atlasGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.atlasGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.atlasGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        this.atlasGraphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Glyph glyph : glyphs) {
            this.glyphMap.put(glyph.value(), glyph);
        }
        this.createTexture();
        this.uploaded = true;
    }

    private void createTexture() {
        try {
            this.nativeImage = new NativeImage(NativeImage.Format.RGBA, this.imageWidth, this.imageHeight, false);
            GlyphPage.fillBackground(this.nativeImage);
            this.texture = new DynamicTexture(this.nativeImage);
            RenderSystem.bindTexture(this.texture.getId());
            // TextureUtil.prepareImage pins TEXTURE_MAX_LEVEL to 0; release it so a mip chain can be
            // generated. The atlas is rasterized at twice the physical resolution, so drawing it
            // without mipmaps minifies with a bare 2x2 tap and the text shimmers.
            GL11.glTexParameteri(3553, 33085, 4);
            GL11.glTexParameteri(3553, 10241, 9729);
            GL11.glTexParameteri(3553, 10240, 9729);
            ClientBase.mc.getTextureManager().register(this.textureLocation, this.texture);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private void rasterizeGlyph(Glyph glyph) {
        if (this.atlasGraphics == null || this.nativeImage == null || this.texture == null) {
            return;
        }
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            return;
        }
        try {
            this.atlasGraphics.setFont(this.getFontForChar(glyph.value()));
            FontMetrics fontMetrics = this.atlasGraphics.getFontMetrics();
            String text = String.valueOf(glyph.value());
            int baseline = glyph.v() + fontMetrics.getAscent();
            this.atlasGraphics.drawString(text, glyph.u(), baseline);
            if (this.boldPx > 0) {
                // Synthetic bold: draw the same glyph again shifted right to thicken the strokes.
                this.atlasGraphics.drawString(text, glyph.u() + this.boldPx, baseline);
            }
            int width = Math.max(1, glyph.width());
            int height = Math.max(1, glyph.height());
            this.writeRegion(glyph.u(), glyph.v(), width, height);
            this.texture.bind();
            this.nativeImage.upload(0, glyph.u(), glyph.v(), glyph.u(), glyph.v(), width, height, false, false);
            // NativeImage.upload() calls setFilter(false, false), which drops the texture back to
            // GL_NEAREST. Re-apply linear filtering after every region upload or the atlas ends up
            // minified with nearest sampling and the text looks aliased.
            this.texture.setFilter(true, true);
            GL30.glGenerateMipmap(3553);
            this.rasterized.set(glyph.value() - this.startChar);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private void writeRegion(int x, int y, int width, int height) {
        long pixelsPtr = (Long)ReflectionUtil.getStaticField(this.nativeImage, "pixels", "com/mojang/blaze3d/platform/NativeImage");
        IntBuffer intBuffer = MemoryUtil.memIntBuffer(pixelsPtr, this.nativeImage.getWidth() * this.nativeImage.getHeight());
        WritableRaster raster = this.atlasImage.getRaster();
        ColorModel colorModel = this.atlasImage.getColorModel();
        int numBands = raster.getNumBands();
        int dataType = raster.getDataBuffer().getDataType();
        Object pixelData = switch (dataType) {
            case 0 -> new byte[numBands];
            case 1 -> new short[numBands];
            case 3 -> new int[numBands];
            case 4 -> new float[numBands];
            case 5 -> new double[numBands];
            default -> throw new IllegalArgumentException("Unknown data buffer type: " + dataType);
        };
        for (int row = 0; row < height; ++row) {
            int srcY = y + row;
            if (srcY < 0 || srcY >= this.atlasImage.getHeight()) {
                continue;
            }
            int rowBase = srcY * this.nativeImage.getWidth();
            for (int col = 0; col < width; ++col) {
                int srcX = x + col;
                if (srcX < 0 || srcX >= this.atlasImage.getWidth()) {
                    continue;
                }
                raster.getDataElements(srcX, srcY, pixelData);
                int a = colorModel.getAlpha(pixelData);
                int r = colorModel.getRed(pixelData);
                int g = colorModel.getGreen(pixelData);
                int b = colorModel.getBlue(pixelData);
                intBuffer.put(rowBase + srcX, a << 24 | b << 16 | g << 8 | r);
            }
        }
    }

    /**
     * Fills the atlas with an almost transparent white wash so bilinear sampling at
     * glyph edges never bleeds the fully transparent black of unset pixels.
     */
    private static void fillBackground(NativeImage image) {
        long pixelsPtr = (Long)ReflectionUtil.getStaticField(image, "pixels", "com/mojang/blaze3d/platform/NativeImage");
        int count = image.getWidth() * image.getHeight();
        IntBuffer intBuffer = MemoryUtil.memIntBuffer(pixelsPtr, count);
        for (int i = 0; i < count; ++i) {
            intBuffer.put(i, 0x01FFFFFF);
        }
    }
}
